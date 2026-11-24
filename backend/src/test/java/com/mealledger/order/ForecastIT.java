package com.mealledger.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mealledger.IntegrationTest;

/** The suggested count: same-weekday attendance average + 5% buffer, then two fallbacks. */
class ForecastIT extends IntegrationTest {

    @Autowired OrderGenerationService generation;

    @Test
    void suggestsAverageOfLastFourSameWeekdaysPlusBuffer() {
        kitchen(1800);
        long c = centre("Benipur-01", 70, "LUNCH");
        attendance(c, DAY.minusDays(28), "LUNCH", 58);
        attendance(c, DAY.minusDays(21), "LUNCH", 61);
        attendance(c, DAY.minusDays(14), "LUNCH", 55);
        attendance(c, DAY.minusDays(7), "LUNCH", 60);
        attendance(c, DAY.minusDays(1), "LUNCH", 10); // a Monday: must not count for a Tuesday

        at(DAY.minusDays(1), "09:00:00");
        generation.generate(DAY);

        var row = jdbc.sql("SELECT suggested_qty, requested_qty, suggestion_basis FROM meal_order WHERE centre_id = :c")
                .param("c", c)
                .query((rs, n) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getString(3)}).single();
        // avg(58, 61, 55, 60) = 58.5; x 1.05 = 61.4; rounded up = 62
        assertThat(row[0]).isEqualTo(62);
        assertThat(row[1]).isEqualTo(62);
        assertThat((String) row[2]).isEqualTo("Avg attendance of last 4 Tuesdays (58, 61, 55, 60) + 5% buffer");
    }

    @Test
    void fallsBackToLastLockedCountThenToEnrolment() {
        kitchen(1800);
        long noFrat = centre("Without FRAT", 50, "LUNCH");
        long brandNew = centre("Brand new", 45, "LUNCH");
        jdbc.sql("""
                INSERT INTO meal_order (centre_id, route_id, vendor_id, service_date, meal_slot, suggested_qty,
                    suggestion_basis, requested_qty, locked_qty, status, cutoff_at, confirm_by, dispatch_by, created_at, updated_at)
                VALUES (:c, 1, 1, :d, 'LUNCH', 40, 'x', 40, 41, 'LOCKED', now(), now(), now(), now(), now())""")
                .param("c", noFrat).param("d", DAY.minusDays(3)).update();

        at(DAY.minusDays(1), "09:00:00");
        generation.generate(DAY);

        assertThat(suggested(noFrat)).isEqualTo(41);
        assertThat(suggested(brandNew)).isEqualTo(45);
    }

    @Test
    void noOrdersOnNonOperatingDays() {
        kitchen(1800);
        centre("Benipur-01", 70, "LUNCH");
        assertThat(generation.generate(DAY.plusDays(5))).isZero(); // Sunday 6 Dec
    }

    private int suggested(long centre) {
        return jdbc.sql("SELECT suggested_qty FROM meal_order WHERE centre_id = :c AND service_date = :d")
                .param("c", centre).param("d", DAY).query(Integer.class).single();
    }
}
