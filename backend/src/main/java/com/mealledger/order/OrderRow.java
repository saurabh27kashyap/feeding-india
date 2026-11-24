package com.mealledger.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One row of meal_order, exactly as stored. */
public record OrderRow(
        long id,
        long centreId,
        long routeId,
        long vendorId,
        LocalDate serviceDate,
        MealSlot mealSlot,
        int suggestedQty,
        String suggestionBasis,
        int requestedQty,
        Integer lockedQty,
        Integer receivedQty,
        OrderStatus status,
        OffsetDateTime cutoffAt,
        OffsetDateTime confirmBy,
        String closedReason,
        OffsetDateTime dispatchedAt,
        OffsetDateTime receivedAt) {

    public static final String COLUMNS = """
            id, centre_id, route_id, vendor_id, service_date, meal_slot, suggested_qty, suggestion_basis,
            requested_qty, locked_qty, received_qty, status, cutoff_at, confirm_by, closed_reason,
            dispatched_at, received_at""";

    public static OrderRow map(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("centre_id"),
                rs.getLong("route_id"),
                rs.getLong("vendor_id"),
                rs.getObject("service_date", LocalDate.class),
                MealSlot.valueOf(rs.getString("meal_slot")),
                rs.getInt("suggested_qty"),
                rs.getString("suggestion_basis"),
                rs.getInt("requested_qty"),
                rs.getObject("locked_qty", Integer.class),
                rs.getObject("received_qty", Integer.class),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("cutoff_at", OffsetDateTime.class),
                rs.getObject("confirm_by", OffsetDateTime.class),
                rs.getString("closed_reason"),
                rs.getObject("dispatched_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    /** True once the count can no longer be changed by the centre. */
    public boolean cutoffPassed(OffsetDateTime now) {
        return !now.isBefore(cutoffAt);
    }
}
