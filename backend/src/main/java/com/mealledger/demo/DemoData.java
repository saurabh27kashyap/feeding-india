package com.mealledger.demo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.BusinessTime;
import com.mealledger.config.AppClock;
import com.mealledger.order.OrderGenerationService;

/**
 * Seeds a realistic program, sized from Feeding India's FY26 report:
 *   ~1,150 schools (lunch) + ~850 Anganwadis (breakfast + lunch) = 2,000 centres, 2,850 meals/day,
 *   20 central kitchens, 200 routes of 10 centres (ASSUMPTION: route size is not public).
 * Enrolment: schools ~120 (1.4L children / 1,150 schools), Anganwadis ~35 (Nandurbar: ~8,000 / 224).
 * History: 4 weeks of received meals and attendance. Past orders were placed near full enrolment,
 * which is why history shows waste; the forecast for tomorrow is what fixes it.
 *
 * Everything is deterministic (setseed), so every demo run shows the same numbers.
 */
@Component
@Profile("demo")
public class DemoData {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    public static final String PASSWORD = "demo1234";
    public static final String OPS_PHONE = "9999999999";
    public static final String KITCHEN_PHONE = "8000000001";
    public static final String CENTRE_PHONE = "9000000001";
    public static final long FEATURED_CENTRE = 1;
    public static final long FEATURED_ROUTE = 1;
    public static final long FEATURED_VENDOR = 1;
    /** Demo "now" starts at this time on the anchor day: before the 20:00 cutoff for tomorrow. */
    public static final LocalTime START_TIME = LocalTime.of(18, 30);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AppClock clock;
    private final BusinessTime time;
    private final OrderGenerationService generation;

    public DemoData(JdbcClient jdbc, PasswordEncoder encoder, AppClock clock, BusinessTime time,
                    OrderGenerationService generation) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.clock = clock;
        this.time = time;
        this.generation = generation;
    }

    public boolean isEmpty() {
        return jdbc.sql("SELECT COUNT(*) FROM vendor").query(Long.class).single() == 0;
    }

    /** The demo day: today in India, moved off Sunday so "today" has meals. */
    public LocalDate anchorDay() {
        clock.reset();
        LocalDate d = time.today();
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(1) : d;
    }

    @Transactional
    public void reset() {
        jdbc.sql("""
                TRUNCATE invoice_event, invoice_line, invoice, feedback, attendance, order_event, meal_order,
                         app_user, centre_meal_slot, centre, route, vendor RESTART IDENTITY""").update();
        LocalDate anchor = anchorDay();
        seed(anchor);
        clock.travelTo(anchor.atTime(START_TIME).atZone(time.zone()).toInstant());
        generation.ensureUpcoming();
        log.info("Demo data ready. Anchor day {}, clock {}", anchor, time.now());
    }

    private void seed(LocalDate anchor) {
        // Same connection for the whole transaction, so RANDOM() below is repeatable.
        jdbc.sql("SELECT SETSEED(0.42)").query((rs, n) -> 0).list();

        jdbc.sql("""
                INSERT INTO vendor (id, name, rate_per_meal_paise)
                SELECT v,
                       'Central Kitchen ' || (ARRAY['Varanasi','Nandurbar','Gurugram','Kushinagar','Darjeeling',
                                                    'Delhi','Jalandhar','Kolkata','Bengaluru','Mumbai'])[((v - 1) % 10) + 1]
                                          || '-' || ((v - 1) / 10 + 1),
                       1500 + ((v * 37) % 8) * 100
                  FROM generate_series(1, 20) v""").update();

        jdbc.sql("""
                INSERT INTO route (id, vendor_id, name)
                SELECT r, (r - 1) / 10 + 1, 'Route ' || LPAD(CAST(r AS TEXT), 3, '0')
                  FROM generate_series(1, 200) r""").update();

        // About 42.5% Anganwadis (850 of 2,000). Centre 1 is the featured demo Anganwadi.
        jdbc.sql("""
                INSERT INTO centre (id, route_id, name, type, district, enrolled)
                SELECT i, (i - 1) / 10 + 1,
                       CASE WHEN i = 1 THEN 'Anganwadi Benipur-01'
                            ELSE CASE WHEN i % 40 < 17 THEN 'Anganwadi ' ELSE 'School ' END || d.district
                                 || '-' || LPAD(CAST(i AS TEXT), 4, '0') END,
                       CASE WHEN i % 40 < 17 THEN 'ANGANWADI' ELSE 'SCHOOL' END,
                       d.district,
                       CASE WHEN i % 40 < 17 THEN 25 + (i * 29) % 21 ELSE 80 + (i * 53) % 90 END
                  FROM generate_series(1, 2000) i
                  CROSS JOIN LATERAL (
                      SELECT (ARRAY['Varanasi','Nandurbar','Gurugram','Kushinagar','Darjeeling',
                                    'Delhi','Jalandhar','Kolkata','Bengaluru','Mumbai'])[(((i - 1) / 100) % 10) + 1] AS district
                  ) d""").update();

        jdbc.sql("""
                INSERT INTO centre_meal_slot (centre_id, meal_slot)
                SELECT id, 'LUNCH' FROM centre
                UNION ALL
                SELECT id, 'BREAKFAST' FROM centre WHERE type = 'ANGANWADI'""").update();

        for (String table : new String[]{"vendor", "route", "centre"}) {
            jdbc.sql("SELECT SETVAL('" + table + "_id_seq', (SELECT MAX(id) FROM " + table + "))")
                    .query(Long.class).single();
        }

        String hash = encoder.encode(PASSWORD);
        jdbc.sql("""
                INSERT INTO app_user (phone, name, password_hash, role)
                VALUES (:phone, 'Saurabh (Ops team, Gurugram)', :hash, 'OPS')""")
                .param("phone", OPS_PHONE).param("hash", hash).update();
        jdbc.sql("""
                INSERT INTO app_user (phone, name, password_hash, role, vendor_id)
                SELECT CAST(8000000000 + id AS TEXT), name || ' (manager)', :hash, 'KITCHEN', id FROM vendor""")
                .param("hash", hash).update();
        jdbc.sql("""
                INSERT INTO app_user (phone, name, password_hash, role, centre_id)
                SELECT CAST(9000000000 + id AS TEXT), name || ' (worker)', :hash, 'CENTRE', id FROM centre""")
                .param("hash", hash).update();

        // The three people in the demo story.
        jdbc.sql("UPDATE app_user SET name = 'Sunita (Anganwadi worker)' WHERE centre_id = :c")
                .param("c", FEATURED_CENTRE).update();
        jdbc.sql("UPDATE app_user SET name = 'Ramesh (Kitchen manager)' WHERE vendor_id = :v")
                .param("v", FEATURED_VENDOR).update();

        // Four weeks of history, Monday to Saturday, all delivered and confirmed.
        // Orders were placed at ~97% of enrolment (the old habit), so waste is visible.
        jdbc.sql("""
                INSERT INTO meal_order (centre_id, route_id, vendor_id, service_date, meal_slot, suggested_qty,
                                        suggestion_basis, requested_qty, locked_qty, received_qty, status,
                                        cutoff_at, confirm_by, dispatch_by, dispatched_at, received_at, created_at, updated_at)
                SELECT c.id, c.route_id, r.vendor_id, d.day, s.meal_slot, q.qty, 'Imported history',
                       q.qty, q.qty, q.qty, 'RECEIVED',
                       (d.day - 1 + TIME '20:00') AT TIME ZONE 'Asia/Kolkata',
                       (d.day + CASE s.meal_slot WHEN 'BREAKFAST' THEN TIME '09:30' ELSE TIME '13:30' END) AT TIME ZONE 'Asia/Kolkata',
                       (d.day + CASE s.meal_slot WHEN 'BREAKFAST' THEN TIME '06:30' ELSE TIME '11:00' END) AT TIME ZONE 'Asia/Kolkata',
                       -- Vans usually leave 5-25 min early; kitchens 3 and 7 are late on about 1 day in 5.
                       (d.day + CASE s.meal_slot WHEN 'BREAKFAST' THEN TIME '06:30' ELSE TIME '11:00' END) AT TIME ZONE 'Asia/Kolkata'
                           + CASE WHEN r.vendor_id IN (3, 7) AND (c.route_id + EXTRACT(DOY FROM d.day)) % 5 = 0
                                  THEN INTERVAL '45 minutes'
                                  ELSE -(INTERVAL '5 minutes' + (c.route_id % 5) * INTERVAL '5 minutes') END,
                       (d.day + CASE s.meal_slot WHEN 'BREAKFAST' THEN TIME '08:05' ELSE TIME '12:20' END) AT TIME ZONE 'Asia/Kolkata',
                       (d.day - 2 + TIME '00:05') AT TIME ZONE 'Asia/Kolkata',
                       (d.day + TIME '12:30') AT TIME ZONE 'Asia/Kolkata'
                  FROM generate_series(CAST(:from AS DATE), CAST(:to AS DATE), INTERVAL '1 day') AS g(ts)
                  CROSS JOIN LATERAL (SELECT CAST(g.ts AS DATE) AS day) d
                  JOIN centre c ON TRUE
                  JOIN route r ON r.id = c.route_id
                  JOIN centre_meal_slot s ON s.centre_id = c.id
                  CROSS JOIN LATERAL (SELECT CAST(ROUND(c.enrolled * 0.97) AS INT) AS qty) q
                 WHERE EXTRACT(ISODOW FROM d.day) <> 7""")
                .param("from", anchor.minusDays(28)).param("to", anchor)
                .update();

        // Attendance: weekday pattern x a per-centre factor x daily noise.
        // 70% of centres use FRAT; the rest enter counts by hand.
        jdbc.sql("""
                INSERT INTO attendance (centre_id, service_date, meal_slot, present, source, recorded_at)
                SELECT o.centre_id, o.service_date, o.meal_slot,
                       LEAST(c.enrolled, CAST(ROUND(c.enrolled
                             * CASE CAST(EXTRACT(ISODOW FROM o.service_date) AS INT)
                                   WHEN 1 THEN 0.86 WHEN 2 THEN 0.90 WHEN 3 THEN 0.91
                                   WHEN 4 THEN 0.89 WHEN 5 THEN 0.85 ELSE 0.74 END
                             * (0.90 + ((c.id * 37) % 16) / 100.0)
                             * CASE o.meal_slot WHEN 'BREAKFAST' THEN 0.96 ELSE 1.0 END
                             * (0.95 + RANDOM() * 0.10)) AS INT)),
                       CASE WHEN c.id % 10 < 7 THEN 'FRAT' ELSE 'MANUAL' END,
                       o.received_at + INTERVAL '30 minutes'
                  FROM meal_order o JOIN centre c ON c.id = o.centre_id""").update();

        // Feedback on about a quarter of meals. Kitchens 3 and 7 are the weak ones.
        jdbc.sql("""
                INSERT INTO feedback (order_id, quantity_ok, quality_ok, on_time, tag_status, created_at)
                SELECT o.id, a.present <= o.received_qty,
                       RANDOM() > CASE WHEN o.vendor_id IN (3, 7) THEN 0.15 ELSE 0.04 END,
                       RANDOM() > CASE WHEN o.vendor_id IN (3, 7) THEN 0.25 ELSE 0.05 END,
                       'NONE', o.received_at + INTERVAL '2 hours'
                  FROM meal_order o
                  JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                   AND a.meal_slot = o.meal_slot
                 WHERE RANDOM() < 0.25""").update();

        // Some of those came with a note (already tagged, as if the LLM had processed them).
        jdbc.sql("""
                UPDATE feedback f SET
                       note = CASE WHEN NOT f.on_time THEN 'Khana 1 ghanta late aaya, bachche intezaar karte rahe'
                                   WHEN NOT f.quality_ok THEN 'Dal bahut patli thi aur roti thandi'
                                   ELSE 'Khana kam pada, kuch bachchon ko nahi mila' END,
                       tags = CASE WHEN NOT f.on_time THEN ARRAY['LATE']
                                   WHEN NOT f.quality_ok THEN ARRAY['QUALITY']
                                   ELSE ARRAY['QUANTITY'] END,
                       tag_status = 'TAGGED', tag_attempts = 1
                 WHERE (NOT f.on_time OR NOT f.quality_ok OR NOT f.quantity_ok) AND RANDOM() < 0.3""").update();
    }
}
