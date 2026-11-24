package com.mealledger.kitchen;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.billing.Reconciler;
import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.order.Actor;
import com.mealledger.order.CutoffService;
import com.mealledger.order.MealSlot;

/**
 * Partner App, kitchen side. Everything here is read from the same orders,
 * audit events and feedback that the centre and Ops screens use, so the three
 * apps can never show different versions of the truth.
 */
@Service
public class KitchenService {

    /** A van leaving within this grace period after the deadline is not counted as late. */
    static final Duration LATE_GRACE = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final BusinessTime time;
    private final CutoffService cutoff;
    private final ChangeTracker changes;

    public KitchenService(JdbcClient jdbc, BusinessTime time, CutoffService cutoff, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.time = time;
        this.cutoff = cutoff;
        this.changes = changes;
    }

    // ---------- cooking list + van sheets ----------

    /** One stop on a van's route: who to hand food to, how much, and whether they confirmed. */
    public record Stop(int stop, long orderId, long centreId, String centreName, String workerName, String workerPhone,
                       String status, int requestedQty, Integer lockedQty, Integer receivedQty, String closedReason,
                       OffsetDateTime confirmBy, OffsetDateTime receivedAt) {
    }

    /**
     * A van's run for one meal. vanStatus: WAITING (before deadline), DUE (past deadline, not left),
     * ON_TIME / LATE (left), with minutesLate measured against dispatchBy.
     */
    public record RouteBatch(long routeId, String routeName, String mealSlot, int mealsToCook,
                             int open, int locked, int dispatched, int received, int closed,
                             boolean canDispatch, OffsetDateTime dispatchBy, OffsetDateTime dispatchedAt,
                             String vanStatus, int minutesLate, List<Stop> stops) {
    }

    public record PrepList(LocalDate serviceDate, OffsetDateTime now, OffsetDateTime cutoffAt, boolean finalCounts,
                           int totalMeals, int breakfastMeals, int lunchMeals, int vansLeft, int vansTotal, int vansLate,
                           List<RouteBatch> routes) {
    }

    public record Row(long orderId, long routeId, String routeName, String mealSlot, long centreId, String centreName,
                      String workerName, String workerPhone, String status, int requestedQty, Integer lockedQty,
                      Integer receivedQty, String closedReason, OffsetDateTime confirmBy, OffsetDateTime dispatchBy,
                      OffsetDateTime dispatchedAt, OffsetDateTime receivedAt) {
    }

    /**
     * The kitchen's work list for a day, grouped into van runs. Applies the cutoff first,
     * so the kitchen never sees a count that could still change.
     */
    @Transactional
    public PrepList prepList(long vendorId, LocalDate date) {
        OffsetDateTime now = time.now();
        cutoff.lockDue(now);

        List<Row> rows = jdbc.sql("""
                SELECT o.id AS order_id, o.route_id, r.name AS route_name, o.meal_slot, c.id AS centre_id,
                       c.name AS centre_name, u.name AS worker_name, u.phone AS worker_phone, o.status,
                       o.requested_qty, o.locked_qty, o.received_qty, o.closed_reason, o.confirm_by, o.dispatch_by,
                       o.dispatched_at, o.received_at
                  FROM meal_order o
                  JOIN route r ON r.id = o.route_id
                  JOIN centre c ON c.id = o.centre_id
                  LEFT JOIN app_user u ON u.centre_id = c.id AND u.role = 'CENTRE'
                 WHERE o.vendor_id = :vendorId AND o.service_date = :date
                 ORDER BY o.meal_slot, r.name, c.id""")
                .param("vendorId", vendorId)
                .param("date", date)
                .query(Row.class)
                .list();

        boolean dayStarted = !now.isBefore(time.startOf(date));
        Map<String, List<Row>> byRoute = new LinkedHashMap<>();
        for (Row r : rows) {
            byRoute.computeIfAbsent(r.routeId() + ":" + r.mealSlot(), k -> new ArrayList<>()).add(r);
        }

        List<RouteBatch> routes = new ArrayList<>();
        int total = 0, breakfast = 0, lunch = 0, vansLeft = 0, vansLate = 0;
        boolean allFinal = !rows.isEmpty();
        for (List<Row> group : byRoute.values()) {
            int meals = 0, open = 0, locked = 0, dispatched = 0, received = 0, closed = 0;
            OffsetDateTime leftAt = null;
            List<Stop> stops = new ArrayList<>();
            int stopNo = 0;
            for (Row r : group) {
                switch (r.status()) {
                    case "REQUESTED" -> { open++; meals += r.requestedQty(); }
                    case "LOCKED" -> { locked++; meals += r.lockedQty(); }
                    case "DISPATCHED" -> { dispatched++; meals += r.lockedQty(); }
                    case "RECEIVED" -> { received++; meals += r.lockedQty(); }
                    default -> closed++;
                }
                OffsetDateTime left = r.dispatchedAt() != null ? r.dispatchedAt() : r.receivedAt();
                if (left != null && (leftAt == null || left.isBefore(leftAt))) {
                    leftAt = left;
                }
                stops.add(new Stop(++stopNo, r.orderId(), r.centreId(), r.centreName(), r.workerName(), r.workerPhone(),
                        r.status(), r.requestedQty(), r.lockedQty(), r.receivedQty(), r.closedReason(),
                        r.confirmBy(), r.receivedAt()));
            }
            Row first = group.getFirst();
            OffsetDateTime due = first.dispatchBy();
            String vanStatus;
            int minutesLate = 0;
            if (leftAt != null) {
                minutesLate = (int) Math.max(0, Duration.between(due, leftAt).toMinutes());
                vanStatus = leftAt.isAfter(due.plus(LATE_GRACE)) ? "LATE" : "ON_TIME";
                vansLeft++;
            } else if (locked > 0 && now.isAfter(due.plus(LATE_GRACE))) {
                minutesLate = (int) Duration.between(due, now).toMinutes();
                vanStatus = "DUE";
            } else {
                vanStatus = "WAITING";
            }
            if (!vanStatus.equals("ON_TIME") && !vanStatus.equals("WAITING")) {
                vansLate++;
            }
            allFinal &= open == 0;
            total += meals;
            if (first.mealSlot().equals("BREAKFAST")) {
                breakfast += meals;
            } else {
                lunch += meals;
            }
            routes.add(new RouteBatch(first.routeId(), first.routeName(), first.mealSlot(), meals,
                    open, locked, dispatched, received, closed,
                    dayStarted && open == 0 && locked > 0, due, leftAt, vanStatus, minutesLate, stops));
        }
        return new PrepList(date, now, time.cutoffFor(date), allFinal, total, breakfast, lunch,
                vansLeft, routes.size(), vansLate, routes);
    }

    public record DispatchResult(int dispatched, int alreadyDispatchedOrReceived) {
    }

    /**
     * Mark every locked order on a route as dispatched. Running it twice is harmless:
     * the second run finds no LOCKED orders.
     */
    @Transactional
    public DispatchResult dispatch(Actor actor, long routeId, LocalDate date, MealSlot slot) {
        OffsetDateTime now = time.now();
        boolean ownsRoute = jdbc.sql("SELECT COUNT(*) FROM route WHERE id = :id AND vendor_id = :vendorId")
                .param("id", routeId).param("vendorId", actor.vendorId())
                .query(Long.class).single() > 0;
        if (!ownsRoute) {
            throw ApiException.notFound("Route");
        }
        if (now.isBefore(time.startOf(date))) {
            throw ApiException.conflict("TOO_EARLY", "A route can be dispatched only on its service day");
        }
        cutoff.lockDue(now);

        int dispatched = jdbc.sql("""
                WITH d AS (
                    UPDATE meal_order
                       SET status = 'DISPATCHED', dispatched_at = :now, updated_at = :now
                     WHERE route_id = :routeId AND service_date = :date AND meal_slot = :slot AND status = 'LOCKED'
                 RETURNING id, locked_qty
                )
                INSERT INTO order_event (order_id, from_status, to_status, qty, actor_user_id, actor_label, at)
                SELECT id, 'LOCKED', 'DISPATCHED', locked_qty, :actorId, :actorLabel, :now FROM d""")
                .param("now", now).param("routeId", routeId).param("date", date).param("slot", slot.name())
                .param("actorId", actor.userId()).param("actorLabel", actor.label())
                .update();

        int done = jdbc.sql("""
                SELECT COUNT(*) FROM meal_order
                 WHERE route_id = :routeId AND service_date = :date AND meal_slot = :slot
                   AND status IN ('DISPATCHED', 'RECEIVED')""")
                .param("routeId", routeId).param("date", date).param("slot", slot.name())
                .query(Long.class).single().intValue();
        if (dispatched > 0) {
            changes.bump();
        }
        return new DispatchResult(dispatched, done - dispatched);
    }

    // ---------- what changed (from the audit trail) ----------

    public record Change(long eventId, long orderId, String centreName, String routeName, String mealSlot,
                         String kind, Integer beforeQty, Integer afterQty, String reason, String byWhom,
                         OffsetDateTime at) {
    }

    /**
     * Every change a centre (or Ops) made to this kitchen's orders for a day, newest first.
     * Read straight from order_event: the same log that is the audit trail and dispute evidence.
     * "before" is the previous count in the log, or the suggestion for the first change.
     */
    public List<Change> changes(long vendorId, LocalDate date) {
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT e.id AS event_id, e.order_id, c.name AS centre_name, r.name AS route_name, o.meal_slot,
                           CASE WHEN e.to_status = 'CLOSED' THEN 'CLOSED'
                                WHEN e.from_status = 'CLOSED' THEN 'REOPENED'
                                WHEN e.from_status = 'LOCKED' AND e.to_status = 'LOCKED' THEN 'OPS_OVERRIDE'
                                ELSE 'COUNT_CHANGED' END AS kind,
                           COALESCE(LAG(e.qty) OVER (PARTITION BY e.order_id ORDER BY e.id), o.suggested_qty) AS before_qty,
                           e.qty AS after_qty, e.reason, e.actor_label AS by_whom, e.at,
                           e.from_status, e.to_status
                      FROM order_event e
                      JOIN meal_order o ON o.id = e.order_id
                      JOIN centre c ON c.id = o.centre_id
                      JOIN route r ON r.id = o.route_id
                     WHERE o.vendor_id = :vendorId AND o.service_date = :date
                ) x
                 WHERE (x.to_status IN ('REQUESTED', 'CLOSED')) OR (x.from_status = 'LOCKED' AND x.to_status = 'LOCKED')
                 ORDER BY x.event_id DESC
                 LIMIT 200""")
                .param("vendorId", vendorId).param("date", date)
                .query(Change.class)
                .list();
    }

    // ---------- money preview ----------

    public record Pending(long orderId, String centreName, String routeName, String mealSlot, String status,
                          int lockedQty, String workerPhone) {
    }

    public record Money(LocalDate serviceDate, int ratePaise, int mealsCooked, int mealsConfirmed, int mealsPending,
                        int mealsNotPayable, long expectedPaise, long confirmedPaise, long pendingPaise,
                        List<Pending> pending) {
    }

    /**
     * What the kitchen will be paid if it bills what it cooked. Uses the same Reconciler
     * as Ops approval, so the kitchen's preview and the approved amount follow one rule.
     */
    public Money money(long vendorId, LocalDate date) {
        int rate = jdbc.sql("SELECT rate_per_meal_paise FROM vendor WHERE id = :v").param("v", vendorId)
                .query(Integer.class).single();
        record Line(long orderId, String centreName, String routeName, String mealSlot, String status,
                    int lockedQty, Integer receivedQty, String workerPhone) {
        }
        List<Line> lines = jdbc.sql("""
                SELECT o.id, c.name, r.name, o.meal_slot, o.status, o.locked_qty, o.received_qty, u.phone
                  FROM meal_order o
                  JOIN centre c ON c.id = o.centre_id
                  JOIN route r ON r.id = o.route_id
                  LEFT JOIN app_user u ON u.centre_id = c.id AND u.role = 'CENTRE'
                 WHERE o.vendor_id = :v AND o.service_date = :d AND o.locked_qty IS NOT NULL
                 ORDER BY r.name, o.meal_slot, c.id""")
                .param("v", vendorId).param("d", date)
                .query((rs, n) -> new Line(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getObject(7, Integer.class), rs.getString(8)))
                .list();

        int cooked = 0, confirmed = 0, pendingMeals = 0, notPayable = 0;
        List<Pending> pending = new ArrayList<>();
        for (Line l : lines) {
            cooked += l.lockedQty();
            Reconciler.Result r = Reconciler.reconcile(l.lockedQty(), l.status(), l.receivedQty(), l.lockedQty());
            if (r.flag() == Reconciler.Flag.NOT_CONFIRMED) {
                pendingMeals += l.lockedQty();
                pending.add(new Pending(l.orderId(), l.centreName(), l.routeName(), l.mealSlot(), l.status(),
                        l.lockedQty(), l.workerPhone()));
            } else {
                confirmed += r.payableQty();
                notPayable += l.lockedQty() - r.payableQty();
            }
        }
        return new Money(date, rate, cooked, confirmed, pendingMeals, notPayable,
                (long) cooked * rate, (long) confirmed * rate, (long) pendingMeals * rate, pending);
    }

    // ---------- reviews from centres ----------

    public record Review(long feedbackId, LocalDate serviceDate, String mealSlot, String centreName, String workerName,
                         boolean quantityOk, boolean qualityOk, boolean onTime, String note, String tags,
                         String tagStatus, OffsetDateTime createdAt) {
    }

    public record DaySummary(LocalDate serviceDate, int reviews, BigDecimal onTimePercent,
                             BigDecimal qualityOkPercent, BigDecimal quantityOkPercent, int notes) {
    }

    public record Reviews(List<DaySummary> days, List<Review> notes) {
    }

    /** Centre feedback on this kitchen's food, per day for the last week, plus the written notes with AI tags. */
    public Reviews reviews(long vendorId, LocalDate endDate) {
        List<DaySummary> days = jdbc.sql("""
                SELECT o.service_date, COUNT(*) AS reviews,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.on_time) / COUNT(*), 0) AS on_time_percent,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.quality_ok) / COUNT(*), 0) AS quality_ok_percent,
                       ROUND(100.0 * COUNT(*) FILTER (WHERE f.quantity_ok) / COUNT(*), 0) AS quantity_ok_percent,
                       COUNT(f.note) AS notes
                  FROM feedback f JOIN meal_order o ON o.id = f.order_id
                 WHERE o.vendor_id = :v AND o.service_date BETWEEN :from AND :to
                 GROUP BY o.service_date
                 ORDER BY o.service_date DESC""")
                .param("v", vendorId).param("from", endDate.minusDays(6)).param("to", endDate)
                .query(DaySummary.class)
                .list();
        List<Review> notes = jdbc.sql("""
                SELECT f.id AS feedback_id, o.service_date, o.meal_slot, c.name AS centre_name, u.name AS worker_name,
                       f.quantity_ok, f.quality_ok, f.on_time, f.note, ARRAY_TO_STRING(f.tags, ',') AS tags,
                       f.tag_status, f.created_at
                  FROM feedback f
                  JOIN meal_order o ON o.id = f.order_id
                  JOIN centre c ON c.id = o.centre_id
                  LEFT JOIN app_user u ON u.centre_id = c.id AND u.role = 'CENTRE'
                 WHERE o.vendor_id = :v AND o.service_date BETWEEN :from AND :to AND f.note IS NOT NULL
                 ORDER BY o.service_date DESC, f.id DESC
                 LIMIT 100""")
                .param("v", vendorId).param("from", endDate.minusDays(6)).param("to", endDate)
                .query(Review.class)
                .list();
        return new Reviews(days, notes);
    }
}
