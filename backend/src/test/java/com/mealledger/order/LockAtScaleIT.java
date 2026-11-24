package com.mealledger.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mealledger.IntegrationTest;
import com.mealledger.common.BusinessTime;

/**
 * 10x today's scale: 30,000 orders (about 20,000 centres' worth of meals) reach cutoff at once.
 * One set-based UPDATE locks them all and writes 30,000 audit events.
 */
class LockAtScaleIT extends IntegrationTest {

    @Autowired CutoffService cutoff;
    @Autowired BusinessTime time;

    @Test
    void locksThirtyThousandOrdersInSeconds() {
        kitchen(1800);
        jdbc.sql("""
                INSERT INTO centre (route_id, name, type, district, enrolled)
                SELECT 1, 'C' || i, 'SCHOOL', 'X', 100 FROM generate_series(1, 15000) i""").update();
        jdbc.sql("""
                INSERT INTO meal_order (centre_id, route_id, vendor_id, service_date, meal_slot, suggested_qty,
                    suggestion_basis, requested_qty, status, cutoff_at, confirm_by, dispatch_by, created_at, updated_at)
                SELECT c.id, 1, 1, :d, s.slot, 90, 'x', 90, 'REQUESTED', :cutoff, :cutoff, :cutoff, now(), now()
                  FROM centre c CROSS JOIN (VALUES ('BREAKFAST'), ('LUNCH')) AS s(slot)""")
                .param("d", DAY).param("cutoff", time.cutoffFor(DAY)).update();

        at(DAY.minusDays(1), "20:00:00");
        long start = System.nanoTime();
        int locked = cutoff.lockDue(time.now());
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(locked).isEqualTo(30_000);
        assertThat(millis).as("lock 30,000 orders (took %d ms)", millis).isLessThan(5_000);
        assertThat(cutoff.lockDue(time.now())).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_event WHERE to_status = 'LOCKED'").query(Long.class).single())
                .isEqualTo(30_000);
        System.out.printf("Locked 30,000 orders in %d ms%n", millis);
    }
}
