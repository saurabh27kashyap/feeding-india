package com.mealledger.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business settings. Times are kept as strings and parsed here so that
 * "20:00" always means 20:00, whatever the server locale is.
 */
@ConfigurationProperties(prefix = "mealledger")
public record MealLedgerProperties(
        String zone,
        String cutoffTime,
        String confirmByBreakfast,
        String confirmByLunch,
        String dispatchByBreakfast,
        String dispatchByLunch,
        int forecastBufferPercent,
        int largeReceiptPercent,
        String operatingDays,
        String fratApiKey,
        boolean schedulingEnabled,
        Llm llm) {

    public record Llm(String baseUrl, String apiKey, String model, int timeoutSeconds, int maxAttempts) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    public LocalTime cutoff() {
        return LocalTime.parse(cutoffTime);
    }

    public LocalTime confirmBy(com.mealledger.order.MealSlot slot) {
        return LocalTime.parse(slot == com.mealledger.order.MealSlot.BREAKFAST ? confirmByBreakfast : confirmByLunch);
    }

    public LocalTime dispatchBy(com.mealledger.order.MealSlot slot) {
        return LocalTime.parse(slot == com.mealledger.order.MealSlot.BREAKFAST ? dispatchByBreakfast : dispatchByLunch);
    }

    public Set<DayOfWeek> operatingDaySet() {
        return Arrays.stream(operatingDays.split(","))
                .map(String::trim)
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
