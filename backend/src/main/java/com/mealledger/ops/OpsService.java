package com.mealledger.ops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.order.OrderRepository;

/**
 * Read-only views for the Ops Console. Every number here is computed from the
 * same tables the partners write to; nothing is cached or copied.
 *
 * Definitions (per order, from FRAT/manual attendance):
 *   waste    = max(received - present, 0)
 *   shortage = max(present - received, 0)   (a child may not have eaten: highest priority)
 *   unconfirmed = locked or dispatched, and the confirm-by time has passed
 */
@Service
public class OpsService {

    private final JdbcClient jdbc;
    private final BusinessTime time;
    private final OrderRepository orders;
    private final ChangeTracker changes;

    public OpsService(JdbcClient jdbc, BusinessTime time, OrderRepository orders, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.time = time;
        this.orders = orders;
        this.changes = changes;
    }

    public record DaySummary(long orders, long open, long locked, long dispatched, long received, long closed,
                             long closedAfterLock, long mealsPlanned, long mealsReceived, long unconfirmed,
                             long notDispatched, long receivedWithoutHeadcount) {
    }

    public record FoodBalance(long childrenPresent, long mealsWithAttendance, long wasteMeals, long shortMeals,
                              long shortages, BigDecimal wastePercent, long plannedWasteFromClosures) {
    }

    public record Unconfirmed(long orderId, String centreName, String district, String routeName, String vendorName,
                              String mealSlot, String stage, int lockedQty, OffsetDateTime confirmBy) {
    }

    public record Shortage(long orderId, String centreName, String routeName, String vendorName, String mealSlot,
                           int receivedQty, int present, int gap) {
    }

    public record WasteCentre(long orderId, String centreName, String routeName, String mealSlot,
                              int receivedQty, int present, int waste, BigDecimal wastePercent) {
    }

    public record TrendPoint(LocalDate serviceDate, long mealsWithAttendance, long wasteMeals, BigDecimal wastePercent) {
    }

    /** A van that left after its deadline (plus 10 min grace), or has not left and is overdue. */
    public record LateVan(long routeId, String routeName, String vendorName, String mealSlot, OffsetDateTime dispatchBy,
                          OffsetDateTime leftAt, int minutesLate) {
    }

    public record NextDay(LocalDate serviceDate, OffsetDateTime cutoffAt, long orders, long closed,
                          long mealsRequested, long enrolledChildren) {
    }

    public record Dashboard(LocalDate date, OffsetDateTime now, DaySummary summary, FoodBalance balance,
                            List<Unconfirmed> unconfirmed, List<Shortage> shortages, List<WasteCentre> topWaste,
                            List<TrendPoint> trend, NextDay nextDay, long invoicesSubmitted, long invoicesApproved,
                            List<LateVan> lateVans) {
    }

    /**
     * Many Ops users poll the same day's numbers. Compute once and share the result until either
     * something changes (ChangeTracker version) or 10 seconds pass (so "overdue" counts move with time).
     * Partners' writes never read this cache, so it cannot cause a wrong order or payment.
     */
    private record CacheKey(LocalDate date, long version, long window) {
    }

    private final Map<CacheKey, Dashboard> dashboardCache = new ConcurrentHashMap<>();

    public Dashboard dashboard(LocalDate date) {
        CacheKey key = new CacheKey(date, changes.version(), time.now().toEpochSecond() / 10);
        if (dashboardCache.size() > 32) {
            dashboardCache.clear();
        }
        return dashboardCache.computeIfAbsent(key, k -> computeDashboard(date));
    }

    private Dashboard computeDashboard(LocalDate date) {
        OffsetDateTime now = time.now();

        DaySummary summary = jdbc.sql("""
                SELECT COUNT(*) AS orders,
                       COUNT(*) FILTER (WHERE status = 'REQUESTED')  AS open,
                       COUNT(*) FILTER (WHERE status = 'LOCKED')     AS locked,
                       COUNT(*) FILTER (WHERE status = 'DISPATCHED') AS dispatched,
                       COUNT(*) FILTER (WHERE status = 'RECEIVED')   AS received,
                       COUNT(*) FILTER (WHERE status = 'CLOSED')     AS closed,
                       COUNT(*) FILTER (WHERE status = 'CLOSED' AND locked_qty IS NOT NULL) AS closed_after_lock,
                       COALESCE(SUM(locked_qty) FILTER (WHERE status IN ('LOCKED', 'DISPATCHED', 'RECEIVED')), 0) AS meals_planned,
                       COALESCE(SUM(received_qty), 0) AS meals_received,
                       COUNT(*) FILTER (WHERE status IN ('LOCKED', 'DISPATCHED') AND confirm_by < :now) AS unconfirmed,
                       COUNT(*) FILTER (WHERE status = 'LOCKED' AND confirm_by < :now) AS not_dispatched,
                       COUNT(*) FILTER (WHERE status = 'RECEIVED' AND NOT EXISTS (
                           SELECT 1 FROM attendance a WHERE a.centre_id = o.centre_id
                              AND a.service_date = o.service_date AND a.meal_slot = o.meal_slot)) AS received_without_headcount
                  FROM meal_order o WHERE service_date = :d""")
                .param("d", date).param("now", now)
                .query(DaySummary.class).single();

        FoodBalance balance = jdbc.sql("""
                SELECT COALESCE(SUM(a.present), 0) AS children_present,
                       COALESCE(SUM(o.received_qty), 0) AS meals_with_attendance,
                       COALESCE(SUM(GREATEST(o.received_qty - a.present, 0)), 0) AS waste_meals,
                       COALESCE(SUM(GREATEST(a.present - o.received_qty, 0)), 0) AS short_meals,
                       COUNT(*) FILTER (WHERE a.present > o.received_qty) AS shortages,
                       ROUND(100.0 * COALESCE(SUM(GREATEST(o.received_qty - a.present, 0)), 0)
                             / NULLIF(SUM(o.received_qty), 0), 1) AS waste_percent,
                       (SELECT COALESCE(SUM(locked_qty), 0) FROM meal_order
                         WHERE service_date = :d AND status = 'CLOSED' AND locked_qty IS NOT NULL) AS planned_waste_from_closures
                  FROM meal_order o
                  JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                   AND a.meal_slot = o.meal_slot
                 WHERE o.service_date = :d AND o.status = 'RECEIVED'""")
                .param("d", date)
                .query(FoodBalance.class).single();

        List<Unconfirmed> unconfirmed = jdbc.sql("""
                SELECT o.id AS order_id, c.name AS centre_name, c.district, r.name AS route_name, v.name AS vendor_name,
                       o.meal_slot, CASE o.status WHEN 'LOCKED' THEN 'NOT_DISPATCHED' ELSE 'NOT_CONFIRMED' END AS stage,
                       o.locked_qty, o.confirm_by
                  FROM meal_order o
                  JOIN centre c ON c.id = o.centre_id JOIN route r ON r.id = o.route_id JOIN vendor v ON v.id = o.vendor_id
                 WHERE o.service_date = :d AND o.status IN ('LOCKED', 'DISPATCHED') AND o.confirm_by < :now
                 ORDER BY stage, r.name, c.name
                 LIMIT 200""")
                .param("d", date).param("now", now)
                .query(Unconfirmed.class).list();

        List<Shortage> shortages = jdbc.sql("""
                SELECT o.id AS order_id, c.name AS centre_name, r.name AS route_name, v.name AS vendor_name, o.meal_slot,
                       o.received_qty, a.present, a.present - o.received_qty AS gap
                  FROM meal_order o
                  JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date AND a.meal_slot = o.meal_slot
                  JOIN centre c ON c.id = o.centre_id JOIN route r ON r.id = o.route_id JOIN vendor v ON v.id = o.vendor_id
                 WHERE o.service_date = :d AND o.status = 'RECEIVED' AND a.present > o.received_qty
                 ORDER BY gap DESC
                 LIMIT 100""")
                .param("d", date)
                .query(Shortage.class).list();

        List<WasteCentre> topWaste = jdbc.sql("""
                SELECT o.id AS order_id, c.name AS centre_name, r.name AS route_name, o.meal_slot,
                       o.received_qty, a.present, o.received_qty - a.present AS waste,
                       ROUND(100.0 * (o.received_qty - a.present) / o.received_qty, 1) AS waste_percent
                  FROM meal_order o
                  JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date AND a.meal_slot = o.meal_slot
                  JOIN centre c ON c.id = o.centre_id JOIN route r ON r.id = o.route_id
                 WHERE o.service_date = :d AND o.status = 'RECEIVED' AND o.received_qty - a.present >= 5
                 ORDER BY waste_percent DESC, waste DESC
                 LIMIT 15""")
                .param("d", date)
                .query(WasteCentre.class).list();

        List<TrendPoint> trend = jdbc.sql("""
                SELECT o.service_date,
                       COALESCE(SUM(o.received_qty), 0) AS meals_with_attendance,
                       COALESCE(SUM(GREATEST(o.received_qty - a.present, 0)), 0) AS waste_meals,
                       ROUND(100.0 * SUM(GREATEST(o.received_qty - a.present, 0)) / NULLIF(SUM(o.received_qty), 0), 1) AS waste_percent
                  FROM meal_order o
                  JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date AND a.meal_slot = o.meal_slot
                 WHERE o.service_date BETWEEN :from AND :d AND o.status = 'RECEIVED'
                 GROUP BY o.service_date
                 ORDER BY o.service_date""")
                .param("from", date.minusDays(20)).param("d", date)
                .query(TrendPoint.class).list();

        LocalDate next = time.nextOperatingDay(date);
        NextDay nextDay = jdbc.sql("""
                SELECT COUNT(*) AS orders,
                       COUNT(*) FILTER (WHERE o.status = 'CLOSED') AS closed,
                       COALESCE(SUM(CASE WHEN o.status = 'CLOSED' THEN 0 ELSE COALESCE(o.locked_qty, o.requested_qty) END), 0) AS meals_requested,
                       COALESCE(SUM(c.enrolled), 0) AS enrolled_children
                  FROM meal_order o JOIN centre c ON c.id = o.centre_id
                 WHERE o.service_date = :next""")
                .param("next", next)
                .query((rs, n) -> new NextDay(next, time.cutoffFor(next), rs.getLong("orders"), rs.getLong("closed"),
                        rs.getLong("meals_requested"), rs.getLong("enrolled_children")))
                .single();

        List<LateVan> lateVans = jdbc.sql("""
                SELECT o.route_id, r.name AS route_name, v.name AS vendor_name, o.meal_slot,
                       MIN(o.dispatch_by) AS dispatch_by,
                       MIN(COALESCE(o.dispatched_at, o.received_at)) AS left_at,
                       CAST(EXTRACT(EPOCH FROM (COALESCE(MIN(COALESCE(o.dispatched_at, o.received_at)), :now)
                                                - MIN(o.dispatch_by))) / 60 AS INT) AS minutes_late
                  FROM meal_order o
                  JOIN route r ON r.id = o.route_id JOIN vendor v ON v.id = o.vendor_id
                 WHERE o.service_date = :d AND o.status <> 'CLOSED' AND o.locked_qty IS NOT NULL
                 GROUP BY o.route_id, r.name, v.name, o.meal_slot
                HAVING COALESCE(MIN(COALESCE(o.dispatched_at, o.received_at)), :now) > MIN(o.dispatch_by) + INTERVAL '10 minutes'
                 ORDER BY minutes_late DESC
                 LIMIT 50""")
                .param("d", date).param("now", now)
                .query(LateVan.class).list();

        long submitted = countInvoices(date, "SUBMITTED");
        long approved = countInvoices(date, "APPROVED");

        return new Dashboard(date, now, summary, balance, unconfirmed, shortages, topWaste, trend, nextDay,
                submitted, approved, lateVans);
    }

    private long countInvoices(LocalDate date, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM invoice WHERE service_date = :d AND status = :s")
                .param("d", date).param("s", status).query(Long.class).single();
    }

    // ---------- one order, end to end ----------

    public record OrderDetail(long id, String centreName, String centreType, String district, int enrolled,
                              String routeName, String vendorName, LocalDate serviceDate, String mealSlot,
                              String status, int suggestedQty, String suggestionBasis, int requestedQty,
                              Integer lockedQty, Integer receivedQty, OffsetDateTime cutoffAt,
                              OffsetDateTime confirmBy, String closedReason, Integer present,
                              String attendanceSource, Boolean quantityOk, Boolean qualityOk, Boolean onTime,
                              String feedbackNote, String feedbackTags, String tagStatus) {
    }

    public record OrderTimeline(OrderDetail order, List<OrderRepository.OrderEventView> events) {
    }

    public OrderTimeline order(long id) {
        OrderDetail detail = jdbc.sql("""
                SELECT o.id, c.name AS centre_name, c.type AS centre_type, c.district, c.enrolled,
                       r.name AS route_name, v.name AS vendor_name, o.service_date, o.meal_slot, o.status,
                       o.suggested_qty, o.suggestion_basis, o.requested_qty, o.locked_qty, o.received_qty,
                       o.cutoff_at, o.confirm_by, o.closed_reason, a.present, a.source AS attendance_source,
                       f.quantity_ok, f.quality_ok, f.on_time, f.note AS feedback_note,
                       ARRAY_TO_STRING(f.tags, ',') AS feedback_tags, f.tag_status
                  FROM meal_order o
                  JOIN centre c ON c.id = o.centre_id JOIN route r ON r.id = o.route_id JOIN vendor v ON v.id = o.vendor_id
                  LEFT JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                        AND a.meal_slot = o.meal_slot
                  LEFT JOIN feedback f ON f.order_id = o.id
                 WHERE o.id = :id""")
                .param("id", id)
                .query(OrderDetail.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Order"));
        return new OrderTimeline(detail, orders.events(id));
    }

    // ---------- vendor scorecard ----------

    public record VendorScore(long vendorId, String vendorName, long ordersReceived, BigDecimal vansOnTimePercent,
                              long feedbackCount,
                              BigDecimal onTimePercent, BigDecimal qualityOkPercent, BigDecimal quantityOkPercent,
                              long shortages, long lateTags, long qualityTags, long hygieneTags, long quantityTags,
                              long tasteTags, long pendingTags) {
    }

    /**
     * Last 7 days ending on the given date, worst first. "Vans on time" is measured from timestamps
     * (objective); the other percentages are what centres tapped (subjective). Both together are fairer.
     */
    public List<VendorScore> scorecard(LocalDate endDate) {
        return jdbc.sql("""
                WITH orders AS (
                    SELECT o.id, o.vendor_id, o.received_qty, a.present,
                           COALESCE(o.dispatched_at, o.received_at) <= o.dispatch_by + INTERVAL '10 minutes' AS van_on_time
                      FROM meal_order o
                      LEFT JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                            AND a.meal_slot = o.meal_slot
                     WHERE o.service_date BETWEEN :from AND :to AND o.status = 'RECEIVED'
                )
                SELECT v.id AS vendor_id, v.name AS vendor_name,
                       COUNT(o.id) AS orders_received,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE o.van_on_time) / NULLIF(COUNT(o.id), 0), 1) AS vans_on_time_percent,
                       COUNT(f.id) AS feedback_count,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.on_time) / NULLIF(COUNT(f.id), 0), 1) AS on_time_percent,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.quality_ok) / NULLIF(COUNT(f.id), 0), 1) AS quality_ok_percent,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.quantity_ok) / NULLIF(COUNT(f.id), 0), 1) AS quantity_ok_percent,
                       COUNT(*) FILTER (WHERE o.present > o.received_qty) AS shortages,
                       COUNT(*) FILTER (WHERE 'LATE' = ANY (f.tags)) AS late_tags,
                       COUNT(*) FILTER (WHERE 'QUALITY' = ANY (f.tags)) AS quality_tags,
                       COUNT(*) FILTER (WHERE 'HYGIENE' = ANY (f.tags)) AS hygiene_tags,
                       COUNT(*) FILTER (WHERE 'QUANTITY' = ANY (f.tags)) AS quantity_tags,
                       COUNT(*) FILTER (WHERE 'TASTE' = ANY (f.tags)) AS taste_tags,
                       COUNT(*) FILTER (WHERE f.tag_status = 'PENDING') AS pending_tags
                  FROM vendor v
                  LEFT JOIN orders o ON o.vendor_id = v.id
                  LEFT JOIN feedback f ON f.order_id = o.id
                 GROUP BY v.id, v.name
                 ORDER BY vans_on_time_percent ASC NULLS LAST, v.name""")
                .param("from", endDate.minusDays(6)).param("to", endDate)
                .query(VendorScore.class)
                .list();
    }
}
