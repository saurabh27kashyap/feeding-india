package com.mealledger.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mealledger.IntegrationTest;
import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.kitchen.KitchenService;

/** Double taps on a bad network, typo guards, closures, and centre isolation. */
class ReceiveIT extends IntegrationTest {

    @Autowired OrderGenerationService generation;
    @Autowired OrderService orders;
    @Autowired CutoffService cutoff;
    @Autowired KitchenService kitchenService;
    @Autowired BusinessTime time;

    long centre;
    long orderId;
    Actor worker;

    @BeforeEach
    void lockedAndDispatched() {
        kitchen(1800);
        centre = centre("Benipur-01", 40, "LUNCH");
        worker = centreActor(centre);
        at(DAY.minusDays(1), "12:00:00");
        generation.generate(DAY);
        orderId = orderId(centre, DAY, "LUNCH");
        orders.changeCount(worker, orderId, 30);
        at(DAY, "07:00:00");
        kitchenService.dispatch(kitchenActor(1), 1, DAY, MealSlot.LUNCH);
        at(DAY, "12:15:00");
    }

    @Test
    void twentyParallelIdenticalTapsRecordOneReceipt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<OrderService.ReceiveResult>> taps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            taps.add(() -> orders.receive(worker, orderId, 30, false));
        }
        int firstTime = 0;
        for (Future<OrderService.ReceiveResult> f : pool.invokeAll(taps)) {
            if (!f.get().replayed()) {
                firstTime++;
            }
        }
        pool.shutdown();

        assertThat(firstTime).isEqualTo(1);
        long receivedEvents = jdbc.sql("SELECT COUNT(*) FROM order_event WHERE order_id = :id AND to_status = 'RECEIVED'")
                .param("id", orderId).query(Long.class).single();
        assertThat(receivedEvents).isEqualTo(1);
    }

    @Test
    void aDifferentCountAfterReceiptIsRejected() {
        orders.receive(worker, orderId, 30, false);
        assertThatThrownBy(() -> orders.receive(worker, orderId, 28, false))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "ALREADY_RECEIVED");
    }

    @Test
    void aSuspiciouslyLargeCountNeedsConfirmation() {
        assertThatThrownBy(() -> orders.receive(worker, orderId, 300, false))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "LARGE_QTY_NEEDS_CONFIRM");
        assertThat(orders.receive(worker, orderId, 300, true).order().receivedQty()).isEqualTo(300);
    }

    @Test
    void anotherCentreCannotTouchThisOrder() {
        long other = centre("Benipur-02", 40, "LUNCH");
        Actor stranger = centreActor(other);
        assertThatThrownBy(() -> orders.receive(stranger, orderId, 30, false))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND");
    }

    @Test
    void closingAfterCutoffKeepsThePlannedCountAsWaste() {
        OrderRow closed = orders.close(worker, orderId, "Sudden holiday");
        assertThat(closed.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(closed.lockedQty()).isEqualTo(30);
        assertThatThrownBy(() -> orders.receive(worker, orderId, 30, false))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "ORDER_CLOSED");
    }

    @Test
    void cannotReceiveBeforeTheServiceDay() {
        at(DAY.minusDays(1), "21:00:00");
        assertThatThrownBy(() -> orders.receive(worker, orderId, 30, false))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "TOO_EARLY");
    }
}
