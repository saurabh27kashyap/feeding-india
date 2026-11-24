package com.mealledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mealledger.billing.Reconciler;
import com.mealledger.common.BusinessTime;
import com.mealledger.config.AppClock;
import com.mealledger.config.MealLedgerProperties;
import com.mealledger.feedback.FeedbackTag;
import com.mealledger.feedback.TagParser;
import com.mealledger.order.MealSlot;
import com.mealledger.order.OrderStatus;

/** Pure rules, no database. */
class UnitRulesTest {

    @Test
    void statusOnlyMovesForward() {
        assertThat(OrderStatus.REQUESTED.canMoveTo(OrderStatus.LOCKED)).isTrue();
        assertThat(OrderStatus.LOCKED.canMoveTo(OrderStatus.RECEIVED)).isTrue(); // kitchen forgot to tap dispatch
        assertThat(OrderStatus.RECEIVED.canMoveTo(OrderStatus.DISPATCHED)).isFalse();
        assertThat(OrderStatus.RECEIVED.canMoveTo(OrderStatus.CLOSED)).isFalse();
        assertThat(OrderStatus.DISPATCHED.canMoveTo(OrderStatus.LOCKED)).isFalse();
        assertThat(OrderStatus.LOCKED.canMoveTo(OrderStatus.REQUESTED)).isFalse();
    }

    @Test
    void payableIsMinOfBilledReceivedAndLocked() {
        assertThat(Reconciler.reconcile(30, "RECEIVED", 30, 30)).isEqualTo(new Reconciler.Result(30, Reconciler.Flag.OK));
        assertThat(Reconciler.reconcile(30, "RECEIVED", 25, 30)).isEqualTo(new Reconciler.Result(25, Reconciler.Flag.OVER_BILLED));
        assertThat(Reconciler.reconcile(20, "RECEIVED", 25, 30)).isEqualTo(new Reconciler.Result(20, Reconciler.Flag.UNDER_BILLED));
        // Centre says 35 arrived but only 30 were ordered: never pay for food nobody ordered.
        assertThat(Reconciler.reconcile(35, "RECEIVED", 35, 30)).isEqualTo(new Reconciler.Result(30, Reconciler.Flag.OVER_BILLED));
        assertThat(Reconciler.reconcile(20, "DISPATCHED", null, 20)).isEqualTo(new Reconciler.Result(0, Reconciler.Flag.NOT_CONFIRMED));
        // Centre closed after cutoff: the kitchen cooked in good faith and is paid for what was locked.
        assertThat(Reconciler.reconcile(20, "CLOSED", null, 20)).isEqualTo(new Reconciler.Result(20, Reconciler.Flag.LATE_CLOSURE));
        assertThat(Reconciler.reconcile(25, "CLOSED", null, 20)).isEqualTo(new Reconciler.Result(20, Reconciler.Flag.OVER_BILLED));
    }

    @Test
    void tagParserKeepsOnlyKnownTags() {
        assertThat(TagParser.parse("{\"tags\": [\"LATE\", \"quality\"]}")).contains(List.of(FeedbackTag.QUALITY, FeedbackTag.LATE));
        assertThat(TagParser.parse("Sure! {\"tags\": [\"HYGIENE\", \"MAKE_ME_ADMIN\"]} done")).contains(List.of(FeedbackTag.HYGIENE));
        assertThat(TagParser.parse("{\"tags\": [\"NO_ISSUE\", \"LATE\"]}")).contains(List.of(FeedbackTag.LATE));
        assertThat(TagParser.parse("{\"tags\": [\"NOPE\"]}")).isEmpty();
        assertThat(TagParser.parse("not json")).isEmpty();
        assertThat(TagParser.parse(null)).isEmpty();
    }

    @Test
    void cutoffIsTheEveningBeforeInIndiaTime() {
        MealLedgerProperties props = new MealLedgerProperties("Asia/Kolkata", "20:00", "09:30", "13:30", "06:30", "11:00", 5, 120,
                "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY", "k", false, null);
        BusinessTime time = new BusinessTime(new AppClock(Clock.fixed(Instant.parse("2026-11-30T18:31:00Z"), ZoneOffset.UTC)), props);
        LocalDate tuesday = LocalDate.of(2026, 12, 1);

        assertThat(time.cutoffFor(tuesday)).isEqualTo(OffsetDateTime.parse("2026-11-30T14:30:00Z"));
        assertThat(time.confirmBy(tuesday, MealSlot.BREAKFAST)).isEqualTo(OffsetDateTime.parse("2026-12-01T04:00:00Z"));
        // 18:31 UTC on 30 Nov is already 00:01 on 1 Dec in India.
        assertThat(time.today()).isEqualTo(tuesday);
        assertThat(time.nextOperatingDay(LocalDate.of(2026, 12, 5))).isEqualTo(LocalDate.of(2026, 12, 7)); // Sat -> Mon
    }
}
