package com.mealledger.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.BusinessTime;
import com.mealledger.config.MealLedgerProperties;

/**
 * Creates the orders for a service day, one per centre per meal, with a suggested count.
 *
 * Suggested count (the forecast):
 *   1. average attendance on the last 4 same weekdays (Monday is not Saturday), plus a small safety buffer;
 *   2. if the centre has no attendance data (FRAT is optional), the last locked count;
 *   3. if there is no history at all, the enrolment.
 *
 * Deliberately not ML: an average a centre manager can check in their head is easier to trust
 * and to debug. Re-running is safe: ON CONFLICT DO NOTHING keeps the first version.
 */
@Service
public class OrderGenerationService {

    private static final Logger log = LoggerFactory.getLogger(OrderGenerationService.class);

    private final JdbcClient jdbc;
    private final BusinessTime time;
    private final MealLedgerProperties props;

    public OrderGenerationService(JdbcClient jdbc, BusinessTime time, MealLedgerProperties props) {
        this.jdbc = jdbc;
        this.time = time;
        this.props = props;
    }

    /** Make sure today's and the next operating day's orders exist. */
    public void ensureUpcoming() {
        LocalDate today = time.today();
        if (time.isOperatingDay(today)) {
            generate(today);
        }
        generate(time.nextOperatingDay(today));
    }

    @Transactional
    public int generate(LocalDate date) {
        if (!time.isOperatingDay(date)) {
            return 0;
        }
        OffsetDateTime now = time.now();
        String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s";

        int created = jdbc.sql("""
                WITH hist AS (
                    SELECT a.centre_id, a.meal_slot,
                           COUNT(*)      AS n,
                           AVG(a.present) AS avg_present,
                           STRING_AGG(CAST(a.present AS TEXT), ', ' ORDER BY a.service_date) AS vals
                      FROM attendance a
                     WHERE a.service_date IN (:d7, :d14, :d21, :d28)
                     GROUP BY a.centre_id, a.meal_slot
                ), last_locked AS (
                    SELECT DISTINCT ON (o.centre_id, o.meal_slot) o.centre_id, o.meal_slot, o.locked_qty
                      FROM meal_order o
                     WHERE o.service_date < :d AND o.service_date >= :d28 AND o.locked_qty IS NOT NULL
                     ORDER BY o.centre_id, o.meal_slot, o.service_date DESC
                ), plan AS (
                    SELECT c.id AS centre_id, c.route_id, r.vendor_id, s.meal_slot,
                           CASE WHEN h.n IS NOT NULL THEN CAST(CEIL(h.avg_present * (100 + :buffer) / 100.0) AS INT)
                                WHEN ll.locked_qty IS NOT NULL THEN ll.locked_qty
                                ELSE c.enrolled END AS qty,
                           CASE WHEN h.n IS NOT NULL
                                    THEN 'Avg attendance of last ' || h.n || ' ' || :dayName || ' (' || h.vals || ') + ' || :buffer || '% buffer'
                                WHEN ll.locked_qty IS NOT NULL THEN 'No attendance data; using last locked count'
                                ELSE 'No history yet; using enrolment' END AS basis
                      FROM centre c
                      JOIN route r ON r.id = c.route_id
                      JOIN centre_meal_slot s ON s.centre_id = c.id
                      LEFT JOIN hist h ON h.centre_id = c.id AND h.meal_slot = s.meal_slot
                      LEFT JOIN last_locked ll ON ll.centre_id = c.id AND ll.meal_slot = s.meal_slot
                     WHERE c.active
                )
                INSERT INTO meal_order (centre_id, route_id, vendor_id, service_date, meal_slot,
                                        suggested_qty, suggestion_basis, requested_qty, status,
                                        cutoff_at, confirm_by, dispatch_by, created_at, updated_at)
                SELECT centre_id, route_id, vendor_id, :d, meal_slot,
                       qty, basis, qty, 'REQUESTED',
                       :cutoff, CASE meal_slot WHEN 'BREAKFAST' THEN :confirmBreakfast ELSE :confirmLunch END,
                       CASE meal_slot WHEN 'BREAKFAST' THEN :dispatchBreakfast ELSE :dispatchLunch END,
                       :now, :now
                  FROM plan
                ON CONFLICT (centre_id, service_date, meal_slot) DO NOTHING""")
                .param("d", date)
                .param("d7", date.minusDays(7))
                .param("d14", date.minusDays(14))
                .param("d21", date.minusDays(21))
                .param("d28", date.minusDays(28))
                .param("buffer", props.forecastBufferPercent())
                .param("dayName", dayName)
                .param("cutoff", time.cutoffFor(date))
                .param("confirmBreakfast", time.confirmBy(date, MealSlot.BREAKFAST))
                .param("confirmLunch", time.confirmBy(date, MealSlot.LUNCH))
                .param("dispatchBreakfast", time.dispatchBy(date, MealSlot.BREAKFAST))
                .param("dispatchLunch", time.dispatchBy(date, MealSlot.LUNCH))
                .param("now", now)
                .update();
        if (created > 0) {
            log.info("Generated {} orders for {}", created, date);
        }
        return created;
    }
}
