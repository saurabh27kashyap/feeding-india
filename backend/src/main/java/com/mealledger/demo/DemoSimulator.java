package com.mealledger.demo;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.order.CutoffService;
import com.mealledger.order.MealSlot;

/**
 * Plays the other 1,999 centres and 19 kitchens, so the dashboard shows a real
 * morning at scale while the presenter drives the featured centre and kitchen by hand.
 * Built-in trouble: two routes never leave the kitchen, ~1.5% of centres forget to
 * confirm, some deliveries arrive short, and Kitchen 3 over-bills.
 */
@Component
@Profile("demo")
public class DemoSimulator {

    private static final String ACTOR = "SIMULATOR";

    private final JdbcClient jdbc;
    private final BusinessTime time;
    private final CutoffService cutoff;
    private final ChangeTracker changes;

    public DemoSimulator(JdbcClient jdbc, BusinessTime time, CutoffService cutoff, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.time = time;
        this.cutoff = cutoff;
        this.changes = changes;
    }

    public record MorningResult(int dispatched, int received, int attendanceRows, int feedbackRows) {
    }

    @Transactional
    public MorningResult morning(LocalDate date, MealSlot slot) {
        OffsetDateTime now = time.now();
        if (now.isBefore(time.startOf(date))) {
            throw ApiException.conflict("TOO_EARLY", "Move the clock to the service day first");
        }
        cutoff.lockDue(now);

        int dispatched = jdbc.sql("""
                WITH d AS (
                    UPDATE meal_order
                       SET status = 'DISPATCHED', updated_at = :now,
                           -- Most vans leave 5-25 min before the deadline; every 41st route leaves 50 min late.
                           dispatched_at = LEAST(:now, CASE WHEN route_id % 41 = 0 THEN dispatch_by + INTERVAL '50 minutes'
                                                            ELSE dispatch_by - (INTERVAL '5 minutes' + (route_id % 5) * INTERVAL '5 minutes') END)
                     WHERE service_date = :d AND meal_slot = :slot AND status = 'LOCKED'
                       AND route_id NOT IN (57, 143)
                       -- At breakfast the presenter dispatches the featured route by hand.
                       AND (route_id <> :featuredRoute OR :slot = 'LUNCH')
                 RETURNING id, locked_qty, dispatched_at)
                INSERT INTO order_event (order_id, from_status, to_status, qty, actor_label, at)
                SELECT id, 'LOCKED', 'DISPATCHED', locked_qty, :actor, dispatched_at FROM d""")
                .param("now", now).param("d", date).param("slot", slot.name())
                .param("featuredRoute", DemoData.FEATURED_ROUTE).param("actor", ACTOR)
                .update();

        int received = jdbc.sql("""
                WITH r AS (
                    UPDATE meal_order
                       SET status = 'RECEIVED', updated_at = :now,
                           received_at = LEAST(:now, dispatched_at + INTERVAL '70 minutes' + (id % 30) * INTERVAL '1 minute'),
                           received_qty = CASE WHEN id % 23 = 0 THEN GREATEST(locked_qty - 3, 0) ELSE locked_qty END
                     WHERE service_date = :d AND meal_slot = :slot AND status = 'DISPATCHED'
                       AND centre_id <> :featuredCentre AND id % 67 <> 0
                 RETURNING id, received_qty, received_at)
                INSERT INTO order_event (order_id, from_status, to_status, qty, actor_label, at)
                SELECT id, 'DISPATCHED', 'RECEIVED', received_qty, :actor, received_at FROM r""")
                .param("now", now).param("d", date).param("slot", slot.name())
                .param("featuredCentre", DemoData.FEATURED_CENTRE).param("actor", ACTOR)
                .update();

        int attendance = jdbc.sql("""
                INSERT INTO attendance (centre_id, service_date, meal_slot, present, source, recorded_at)
                SELECT o.centre_id, o.service_date, o.meal_slot,
                       LEAST(c.enrolled, CAST(ROUND(o.locked_qty / 1.05 * (0.92 + RANDOM() * 0.12)) AS INT)),
                       CASE WHEN c.id % 10 < 7 THEN 'FRAT' ELSE 'MANUAL' END, :now
                  FROM meal_order o JOIN centre c ON c.id = o.centre_id
                 WHERE o.service_date = :d AND o.meal_slot = :slot AND o.status = 'RECEIVED'
                   AND o.centre_id <> :featuredCentre
                ON CONFLICT (centre_id, service_date, meal_slot) DO NOTHING""")
                .param("now", now).param("d", date).param("slot", slot.name())
                .param("featuredCentre", DemoData.FEATURED_CENTRE)
                .update();

        // Fresh notes are left PENDING on purpose: the LLM job tags them in the background.
        int feedback = jdbc.sql("""
                INSERT INTO feedback (order_id, quantity_ok, quality_ok, on_time, note, tag_status, created_at)
                SELECT o.id,
                       COALESCE(a.present, 0) <= o.received_qty,
                       RANDOM() > CASE WHEN o.vendor_id IN (3, 7) THEN 0.15 ELSE 0.04 END,
                       RANDOM() > CASE WHEN o.vendor_id IN (3, 7) THEN 0.25 ELSE 0.05 END,
                       CASE WHEN o.id % 25 = 0 THEN
                           (ARRAY['Aaj khichdi mein kankad mila, bachchon ne nahi khaya',
                                  'Khana 45 minute late aaya, prayer ke baad bhi wait karna pada',
                                  'Dal bilkul paani jaisi thi',
                                  'Bachchon ko poha bahut pasand aaya, sab ne plate khatam ki',
                                  'Roti kachchi thi aur sabzi thandi',
                                  'Food was short by 6 plates today',
                                  'Container ke dhakkan pe makkhi baithi thi, smell bhi aa rahi thi',
                                  'Kids did not like the ragi sheera, half was left'])[(o.id / 25) % 8 + 1]
                       END,
                       CASE WHEN o.id % 25 = 0 THEN 'PENDING' ELSE 'NONE' END,
                       :now
                  FROM meal_order o
                  LEFT JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                        AND a.meal_slot = o.meal_slot
                 WHERE o.service_date = :d AND o.meal_slot = :slot AND o.status = 'RECEIVED'
                   AND o.centre_id <> :featuredCentre AND (o.id % 5 = 0 OR o.id % 25 = 0)
                ON CONFLICT (order_id) DO NOTHING""")
                .param("now", now).param("d", date).param("slot", slot.name())
                .param("featuredCentre", DemoData.FEATURED_CENTRE)
                .update();

        changes.bump();
        return new MorningResult(dispatched, received, attendance, feedback);
    }

    public record InvoicesResult(int invoiceLines) {
    }

    /** Every kitchen except the featured one bills for the day. Kitchen 3 quietly over-bills some lines. */
    @Transactional
    public InvoicesResult invoices(LocalDate date) {
        if (date.isAfter(time.today())) {
            throw ApiException.conflict("FUTURE_DATE", "Kitchens bill only for a day that has started");
        }
        int lines = jdbc.sql("""
                WITH inv AS (
                    INSERT INTO invoice (vendor_id, service_date, status, submitted_at)
                    SELECT v.id, :d, 'SUBMITTED', :now FROM vendor v
                     WHERE v.id <> :featuredVendor
                       AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.vendor_id = v.id AND i.service_date = :d)
                 RETURNING id, vendor_id)
                INSERT INTO invoice_line (invoice_id, order_id, billed_qty)
                SELECT inv.id, o.id, o.locked_qty + CASE WHEN o.vendor_id = 3 AND o.id % 5 = 0 THEN 2 ELSE 0 END
                  FROM inv JOIN meal_order o ON o.vendor_id = inv.vendor_id AND o.service_date = :d
                 WHERE o.locked_qty IS NOT NULL""")
                .param("d", date).param("now", time.now()).param("featuredVendor", DemoData.FEATURED_VENDOR)
                .update();
        jdbc.sql("""
                INSERT INTO invoice_event (invoice_id, kind, actor_label, note, at)
                SELECT i.id, 'SUBMITTED', v.name || ' (manager)', COUNT(l.id) || ' lines', :now
                  FROM invoice i JOIN vendor v ON v.id = i.vendor_id JOIN invoice_line l ON l.invoice_id = i.id
                 WHERE i.service_date = :d AND NOT EXISTS (SELECT 1 FROM invoice_event e WHERE e.invoice_id = i.id)
                 GROUP BY i.id, v.name""")
                .param("d", date).param("now", time.now()).update();
        changes.bump();
        return new InvoicesResult(lines);
    }
}
