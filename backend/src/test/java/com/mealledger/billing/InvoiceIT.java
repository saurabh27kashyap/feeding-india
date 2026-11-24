package com.mealledger.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mealledger.IntegrationTest;
import com.mealledger.common.ApiException;
import com.mealledger.kitchen.KitchenService;
import com.mealledger.order.Actor;
import com.mealledger.order.MealSlot;
import com.mealledger.order.OrderGenerationService;
import com.mealledger.order.OrderService;

/** Bill vs. confirmed receipt, approval rules, and frozen numbers. */
class InvoiceIT extends IntegrationTest {

    @Autowired OrderGenerationService generation;
    @Autowired OrderService orders;
    @Autowired KitchenService kitchenService;
    @Autowired InvoiceService invoices;

    @Test
    void reconcilesBlocksUnconfirmedAndFreezesOnApproval() {
        kitchen(2000); // ₹20.00 per meal
        long a = centre("A", 60, "LUNCH");
        long b = centre("B", 60, "LUNCH");
        long c = centre("C", 60, "LUNCH");
        Actor wa = centreActor(a), wb = centreActor(b), wc = centreActor(c);
        Actor kitchen = kitchenActor(1);
        Actor ops = opsActor();

        at(DAY.minusDays(1), "10:00:00");
        generation.generate(DAY);
        long oa = orderId(a, DAY, "LUNCH"), ob = orderId(b, DAY, "LUNCH"), oc = orderId(c, DAY, "LUNCH");
        orders.changeCount(wa, oa, 30);
        orders.changeCount(wb, ob, 30);
        orders.changeCount(wc, oc, 20);

        at(DAY, "11:00:00");
        kitchenService.dispatch(kitchen, 1, DAY, MealSlot.LUNCH);
        orders.receive(wa, oa, 30, false);
        orders.receive(wb, ob, 25, false); // 5 short
        // C has not confirmed yet.

        long invoiceId = invoices.submit(kitchen, DAY, List.of(
                new InvoiceService.SubmitLine(oa, 30),
                new InvoiceService.SubmitLine(ob, 30),
                new InvoiceService.SubmitLine(oc, 20)));

        InvoiceService.InvoiceDetail live = invoices.detail(invoiceId);
        assertThat(live.lines()).extracting(InvoiceService.LineView::flag)
                .containsExactlyInAnyOrder("OK", "OVER_BILLED", "NOT_CONFIRMED");
        assertThat(live.invoice().payableMeals()).isEqualTo(55);

        assertThatThrownBy(() -> invoices.approve(ops, invoiceId))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "UNCONFIRMED_DELIVERIES");

        orders.receive(wc, oc, 20, false);
        InvoiceService.InvoiceDetail approved = invoices.approve(ops, invoiceId);
        assertThat(approved.invoice().status()).isEqualTo("APPROVED");
        assertThat(approved.invoice().payableMeals()).isEqualTo(75);
        assertThat(approved.invoice().payablePaise()).isEqualTo(75 * 2000L);

        // Frozen: a later rate change does not rewrite an approved bill.
        jdbc.sql("UPDATE vendor SET rate_per_meal_paise = 9999").update();
        assertThat(invoices.detail(invoiceId).invoice().payablePaise()).isEqualTo(75 * 2000L);

        assertThatThrownBy(() -> invoices.approve(ops, invoiceId))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "NOT_SUBMITTED");

        // Payment: approved -> paid with a bank reference. Same reference again is a safe retry.
        assertThat(invoices.markPaid(ops, invoiceId, "UTR123").invoice().status()).isEqualTo("PAID");
        assertThat(invoices.markPaid(ops, invoiceId, "UTR123").invoice().paymentRef()).isEqualTo("UTR123");
        assertThatThrownBy(() -> invoices.markPaid(ops, invoiceId, "UTR999"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "ALREADY_PAID");
    }

    @Test
    void opsCanConfirmForASilentCentreSoTheBillIsNotStuckForever() {
        kitchen(2000);
        long a = centre("A", 60, "LUNCH");
        Actor kitchen = kitchenActor(1);
        Actor ops = opsActor();
        at(DAY.minusDays(1), "10:00:00");
        generation.generate(DAY);
        long oa = orderId(a, DAY, "LUNCH");

        at(DAY, "11:00:00");
        kitchenService.dispatch(kitchen, 1, DAY, MealSlot.LUNCH);
        long invoiceId = invoices.submit(kitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 60)));

        // Too early: the centre still has until 1:30 PM to confirm.
        assertThatThrownBy(() -> orders.confirmOnBehalf(ops, oa, 58, "Called the centre"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "TOO_EARLY");

        at(DAY, "15:00:00");
        orders.confirmOnBehalf(ops, oa, 58, "Called the centre: 58 arrived");
        InvoiceService.InvoiceDetail approved = invoices.approve(ops, invoiceId);
        assertThat(approved.invoice().payableMeals()).isEqualTo(58);

        // A bank reference can settle only one invoice.
        invoices.markPaid(ops, invoiceId, "UTR777");
        long b = centre("B", 60, "LUNCH");
        jdbc.sql("""
                INSERT INTO invoice (vendor_id, service_date, status, submitted_at, approved_at, total_payable_paise)
                VALUES (1, :d, 'APPROVED', now(), now(), 0)""").param("d", DAY.minusDays(1)).update();
        long other = jdbc.sql("SELECT id FROM invoice WHERE service_date = :d").param("d", DAY.minusDays(1)).query(Long.class).single();
        assertThat(b).isPositive();
        assertThatThrownBy(() -> invoices.markPaid(ops, other, "UTR777"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "REF_ALREADY_USED");
    }

    @Test
    void rejectedBillIsFixedResentAndApprovedWithFullHistory() {
        kitchen(2000);
        long a = centre("A", 60, "LUNCH");
        Actor wa = centreActor(a);
        Actor kitchen = kitchenActor(1);
        Actor ops = opsActor();
        at(DAY.minusDays(1), "10:00:00");
        generation.generate(DAY);
        long oa = orderId(a, DAY, "LUNCH");
        orders.changeCount(wa, oa, 30);
        at(DAY, "11:00:00");
        kitchenService.dispatch(kitchen, 1, DAY, MealSlot.LUNCH);
        orders.receive(wa, oa, 30, false);

        long id = invoices.submit(kitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 40)));
        InvoiceService.InvoiceDetail rejected = invoices.reject(ops, id, "Billed 40 but 30 were ordered and received");
        assertThat(rejected.invoice().status()).isEqualTo("REJECTED");
        assertThat(rejected.invoice().rejectReason()).contains("30 were ordered");
        // Proof on the line: who confirmed receipt.
        assertThat(rejected.lines().getFirst().receivedBy()).isEqualTo("Centre " + a);

        assertThatThrownBy(() -> invoices.approve(ops, id))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "NOT_SUBMITTED");

        invoices.resubmit(kitchen, id, List.of(new InvoiceService.SubmitLine(oa, 30)));
        InvoiceService.InvoiceDetail approved = invoices.approve(ops, id);
        assertThat(approved.invoice().payableMeals()).isEqualTo(30);
        assertThat(approved.events()).extracting(InvoiceService.EventView::kind)
                .containsExactly("SUBMITTED", "REJECTED", "RESUBMITTED", "APPROVED");

        // Another kitchen cannot see or resend this bill.
        jdbc.sql("INSERT INTO vendor (name, rate_per_meal_paise) VALUES ('Kitchen B', 1500)").update();
        Actor other = kitchenActor(2);
        assertThatThrownBy(() -> invoices.detailForVendor(2, id))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND");
        assertThatThrownBy(() -> invoices.resubmit(other, id, List.of(new InvoiceService.SubmitLine(oa, 30))))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND");
    }

    @Test
    void rejectsDuplicatesForeignOrdersAndFutureDays() {
        kitchen(2000);
        long a = centre("A", 60, "LUNCH");
        Actor kitchen = kitchenActor(1);
        at(DAY.minusDays(1), "10:00:00");
        generation.generate(DAY);
        long oa = orderId(a, DAY, "LUNCH");

        assertThatThrownBy(() -> invoices.submit(kitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 10))))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "FUTURE_DATE");

        at(DAY, "15:00:00"); // after cutoff: the order is locked on the next touch
        kitchenService.prepList(1, DAY);

        jdbc.sql("INSERT INTO vendor (name, rate_per_meal_paise) VALUES ('Kitchen B', 1500)").update();
        Actor otherKitchen = kitchenActor(2);
        assertThatThrownBy(() -> invoices.submit(otherKitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 10))))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "INVALID_LINES");

        invoices.submit(kitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 10)));
        assertThatThrownBy(() -> invoices.submit(kitchen, DAY, List.of(new InvoiceService.SubmitLine(oa, 10))))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "INVOICE_EXISTS");
    }
}
