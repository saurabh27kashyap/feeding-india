package com.mealledger.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The only source of "now" in the app. Time is an input, not a global:
 * tests and the demo can move it, and every cutoff decision reads it from here.
 *
 * In production the offset is always zero, so this behaves exactly like the system clock.
 */
public class AppClock extends Clock {

    private final Clock base;
    private volatile Duration offset = Duration.ZERO;

    public AppClock(Clock base) {
        this.base = base;
    }

    public static AppClock systemUtc() {
        return new AppClock(Clock.systemUTC());
    }

    /** Jump to the given instant. Time keeps ticking from there. */
    public void travelTo(Instant target) {
        offset = Duration.between(base.instant(), target);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("AppClock is always UTC; convert with the business zone instead");
    }
}
