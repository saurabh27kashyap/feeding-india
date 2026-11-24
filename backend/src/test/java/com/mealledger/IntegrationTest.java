package com.mealledger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mealledger.auth.Role;
import com.mealledger.config.AppClock;
import com.mealledger.order.Actor;

/**
 * Real Postgres (Testcontainers), real schema (Flyway), no background jobs:
 * tests move the clock and call jobs themselves, so every run is deterministic.
 * One container for the whole suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mealledger.scheduling-enabled=false", "mealledger.llm.api-key="})
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A Tuesday. Its cutoff is Monday 30 Nov 20:00 IST. */
    protected static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected AppClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE invoice_line, invoice, feedback, attendance, order_event, meal_order,
                         app_user, centre_meal_slot, centre, route, vendor RESTART IDENTITY CASCADE""").update();
        clock.reset();
    }

    protected void at(LocalDate date, String hhmmss) {
        Instant instant = date.atTime(LocalTime.parse(hhmmss)).atZone(IST).toInstant();
        clock.travelTo(instant);
    }

    /** One kitchen, one route. Returns the vendor id (always 1). */
    protected long kitchen(int ratePaise) {
        jdbc.sql("INSERT INTO vendor (name, rate_per_meal_paise) VALUES ('Kitchen A', :r)").param("r", ratePaise).update();
        jdbc.sql("INSERT INTO route (vendor_id, name) VALUES (1, 'Route 001')").update();
        return 1;
    }

    /** A centre on route 1 served the given slots. Returns its id. */
    protected long centre(String name, int enrolled, String... slots) {
        long id = jdbc.sql("""
                INSERT INTO centre (route_id, name, type, district, enrolled)
                VALUES (1, :name, 'ANGANWADI', 'Varanasi', :enrolled) RETURNING id""")
                .param("name", name).param("enrolled", enrolled).query(Long.class).single();
        for (String slot : slots) {
            jdbc.sql("INSERT INTO centre_meal_slot (centre_id, meal_slot) VALUES (:c, :s)")
                    .param("c", id).param("s", slot).update();
        }
        return id;
    }

    protected long user(String phone, Role role, Long centreId, Long vendorId, String passwordHash) {
        return jdbc.sql("""
                INSERT INTO app_user (phone, name, password_hash, role, centre_id, vendor_id)
                VALUES (:p, :p, :h, :r, :c, :v) RETURNING id""")
                .param("p", phone).param("h", passwordHash).param("r", role.name())
                .param("c", centreId).param("v", vendorId)
                .query(Long.class).single();
    }

    protected Actor centreActor(long centreId) {
        long id = user("centre-" + centreId, Role.CENTRE, centreId, null, "{noop}x");
        return new Actor(id, "Centre " + centreId, Role.CENTRE, centreId, null);
    }

    protected Actor kitchenActor(long vendorId) {
        long id = user("kitchen-" + vendorId, Role.KITCHEN, null, vendorId, "{noop}x");
        return new Actor(id, "Kitchen " + vendorId, Role.KITCHEN, null, vendorId);
    }

    protected Actor opsActor() {
        long id = user("ops", Role.OPS, null, null, "{noop}x");
        return new Actor(id, "Ops", Role.OPS, null, null);
    }

    protected long orderId(long centreId, LocalDate date, String slot) {
        return jdbc.sql("SELECT id FROM meal_order WHERE centre_id = :c AND service_date = :d AND meal_slot = :s")
                .param("c", centreId).param("d", date).param("s", slot).query(Long.class).single();
    }

    protected String status(long orderId) {
        return jdbc.sql("SELECT status FROM meal_order WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    protected void attendance(long centreId, LocalDate date, String slot, int present) {
        jdbc.sql("""
                INSERT INTO attendance (centre_id, service_date, meal_slot, present, source, recorded_at)
                VALUES (:c, :d, :s, :p, 'FRAT', now())""")
                .param("c", centreId).param("d", date).param("s", slot).param("p", present).update();
    }
}
