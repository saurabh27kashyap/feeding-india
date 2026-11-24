package com.mealledger.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mealledger.config.AppClock;
import com.mealledger.config.MealLedgerProperties;
import com.mealledger.order.MealSlot;

/**
 * Converts between "business time" (India dates and wall-clock times) and the
 * UTC timestamps stored in the database. Every cutoff and deadline is computed here.
 */
@Component
public class BusinessTime {

    private final AppClock clock;
    private final MealLedgerProperties props;
    private final ZoneId zone;
    private final Set<DayOfWeek> operatingDays;

    public BusinessTime(AppClock clock, MealLedgerProperties props) {
        this.clock = clock;
        this.props = props;
        this.zone = props.zoneId();
        this.operatingDays = props.operatingDaySet();
    }

    /** Now, as a UTC timestamp. This is what goes into the database. */
    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Today's date in India. */
    public LocalDate today() {
        return now().atZoneSameInstant(zone).toLocalDate();
    }

    public boolean isOperatingDay(LocalDate date) {
        return operatingDays.contains(date.getDayOfWeek());
    }

    /** Next operating day strictly after the given date. */
    public LocalDate nextOperatingDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!isOperatingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Counts for a service date lock at the cutoff time on the evening before. */
    public OffsetDateTime cutoffFor(LocalDate serviceDate) {
        return serviceDate.minusDays(1).atTime(props.cutoff()).atZone(zone)
                .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Receipt should be confirmed by this time on the service date. */
    public OffsetDateTime confirmBy(LocalDate serviceDate, MealSlot slot) {
        return serviceDate.atTime(props.confirmBy(slot)).atZone(zone)
                .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** The van carrying this meal must leave the kitchen by this time. */
    public OffsetDateTime dispatchBy(LocalDate serviceDate, MealSlot slot) {
        return serviceDate.atTime(props.dispatchBy(slot)).atZone(zone)
                .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Start of the service day in India, as UTC. */
    public OffsetDateTime startOf(LocalDate serviceDate) {
        return serviceDate.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    public ZoneId zone() {
        return zone;
    }
}
