package com.mealledger.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mealledger.IntegrationTest;
import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;

/**
 * The cutoff race: a centre keeps editing its count while 20:00 arrives and the lock job runs.
 * Invariant: no count change is ever recorded after the LOCKED event, and the kitchen gets
 * exactly the last accepted count.
 */
class CutoffRaceIT extends IntegrationTest {

    @Autowired OrderGenerationService generation;
    @Autowired OrderService orders;
    @Autowired CutoffService cutoff;
    @Autowired BusinessTime time;

    @Test
    void editsRacingTheCutoffNeverLandAfterTheLock() throws Exception {
        kitchen(1800);
        long centre = centre("Benipur-01", 80, "LUNCH");
        Actor worker = centreActor(centre);
        at(DAY.minusDays(1), "19:59:59.500");
        generation.generate(DAY);
        long orderId = orderId(centre, DAY, "LUNCH");

        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    try {
                        orders.changeCount(worker, orderId, ThreadLocalRandom.current().nextInt(40, 90));
                    } catch (ApiException e) {
                        if (e.code().equals("CUTOFF_PASSED")) {
                            return;
                        }
                        unexpected.add(e.code());
                    }
                }
            });
        }
        Thread.sleep(300);
        at(DAY.minusDays(1), "20:00:00");          // 20:00 arrives while edits are in flight
        int locked = cutoff.lockDue(time.now());
        Thread.sleep(200);
        stop.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(status(orderId)).isEqualTo("LOCKED");
        assertThat(locked).isLessThanOrEqualTo(1); // 0 if an edit thread locked it on touch first

        List<OrderRepository.OrderEventView> events = jdbc.sql("""
                SELECT id, from_status, to_status, qty, actor_label, reason, at
                  FROM order_event WHERE order_id = :id ORDER BY id""")
                .param("id", orderId).query(OrderRepository.OrderEventView.class).list();
        int lockIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).toStatus().equals("LOCKED")) {
                lockIndex = i;
            }
        }
        assertThat(lockIndex).as("exactly one lock event at the end").isEqualTo(events.size() - 1);
        assertThat(events.stream().filter(e -> e.toStatus().equals("LOCKED")).count()).isEqualTo(1);

        int lockedQty = jdbc.sql("SELECT locked_qty FROM meal_order WHERE id = :id").param("id", orderId)
                .query(Integer.class).single();
        Integer lastAccepted = events.stream().filter(e -> e.toStatus().equals("REQUESTED"))
                .reduce((a, b) -> b).map(OrderRepository.OrderEventView::qty)
                .orElse(jdbc.sql("SELECT suggested_qty FROM meal_order WHERE id = :id").param("id", orderId)
                        .query(Integer.class).single());
        assertThat(lockedQty).isEqualTo(lastAccepted);
    }

    @Test
    void lockJobIsIdempotent() {
        kitchen(1800);
        centre("Benipur-01", 80, "BREAKFAST", "LUNCH");
        at(DAY.minusDays(2), "12:00:00");
        generation.generate(DAY);

        at(DAY.minusDays(1), "20:00:01");
        assertThat(cutoff.lockDue(time.now())).isEqualTo(2);
        assertThat(cutoff.lockDue(time.now())).isZero();
        assertThat(generation.generate(DAY)).isZero(); // re-running generation does not duplicate orders
    }
}
