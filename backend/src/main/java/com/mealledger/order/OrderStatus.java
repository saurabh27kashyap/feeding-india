package com.mealledger.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.mealledger.common.ApiException;

/**
 * Life of one meal order. Status only moves forward:
 *
 * <pre>
 * REQUESTED --cutoff--> LOCKED --route dispatch--> DISPATCHED --centre--> RECEIVED
 *     |                   |  \_________________ centre confirms even if ____/
 *     |                   |                     kitchen forgot to tap dispatch
 *     +------+------------+----------+
 *            v                       |
 *          CLOSED  (reason required) <+ (also from DISPATCHED: food already on the road)
 * </pre>
 *
 * CLOSED can go back to REQUESTED only before cutoff, and only if it was never locked
 * (a centre that tapped "closed" by mistake can undo it the same evening).
 */
public enum OrderStatus {
    REQUESTED,
    LOCKED,
    DISPATCHED,
    RECEIVED,
    CLOSED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            REQUESTED, EnumSet.of(REQUESTED, LOCKED, CLOSED),
            LOCKED, EnumSet.of(LOCKED, DISPATCHED, RECEIVED, CLOSED),
            DISPATCHED, EnumSet.of(RECEIVED, CLOSED),
            RECEIVED, EnumSet.noneOf(OrderStatus.class),
            CLOSED, EnumSet.of(REQUESTED));

    public boolean canMoveTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public void requireMoveTo(OrderStatus next) {
        if (!canMoveTo(next)) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "An order that is " + this + " cannot become " + next);
        }
    }
}
