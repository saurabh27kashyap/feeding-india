package com.mealledger.order;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.config.MealLedgerProperties;

/**
 * Every change to a single order. Each method:
 * 1. locks the row (SELECT ... FOR UPDATE),
 * 2. applies the cutoff if it has passed (lock-on-touch, so we never depend on the sweeper's timing),
 * 3. checks the business rules and the state machine,
 * 4. writes the change and an audit event in the same transaction.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final BusinessTime time;
    private final MealLedgerProperties props;
    private final ChangeTracker changes;

    public OrderService(OrderRepository orders, BusinessTime time, MealLedgerProperties props, ChangeTracker changes) {
        this.orders = orders;
        this.time = time;
        this.props = props;
        this.changes = changes;
    }

    /** Centre changes tomorrow's count, or reopens an order it closed by mistake. Allowed only before cutoff. */
    @Transactional
    public OrderRow changeCount(Actor actor, long orderId, int qty) {
        OrderRow locked = lockOwnedByCentre(actor, orderId);
        OffsetDateTime now = time.now(); // read after the row lock: a request that waited sees the real time
        OrderRow o = applyCutoffIfDue(locked, now);

        if (o.cutoffPassed(now) || o.lockedQty() != null && o.status() != OrderStatus.CLOSED) {
            throw ApiException.conflict("CUTOFF_PASSED",
                    "Cutoff has passed. The kitchen already has the final count for this meal.");
        }
        if (o.status() == OrderStatus.REQUESTED && o.requestedQty() == qty) {
            return o; // same value again: nothing to do (safe retry)
        }
        if (o.status() == OrderStatus.CLOSED && o.lockedQty() != null) {
            throw ApiException.conflict("INVALID_TRANSITION", "This order was closed after it was locked");
        }
        o.status().requireMoveTo(OrderStatus.REQUESTED);

        orders.setRequested(o.id(), qty, now);
        orders.insertEvent(o.id(), o.status(), OrderStatus.REQUESTED, qty, actor,
                o.status() == OrderStatus.CLOSED ? "Reopened" : null, now);
        changes.bump();
        return reload(o.id());
    }

    /**
     * Centre reports it will be closed. Before cutoff the request simply becomes zero.
     * After cutoff the food is already planned, so the closure is recorded as planned waste.
     */
    @Transactional
    public OrderRow close(Actor actor, long orderId, String reason) {
        OrderRow locked = lockOwnedByCentre(actor, orderId);
        OffsetDateTime now = time.now(); // read after the row lock: a request that waited sees the real time
        OrderRow o = applyCutoffIfDue(locked, now);

        if (o.status() == OrderStatus.CLOSED) {
            return o; // already closed: safe retry
        }
        if (o.serviceDate().isBefore(time.today())) {
            throw ApiException.conflict("DAY_OVER", "This service day is over");
        }
        if (o.status() == OrderStatus.RECEIVED) {
            throw ApiException.conflict("ALREADY_RECEIVED", "Food was already received for this meal");
        }
        o.status().requireMoveTo(OrderStatus.CLOSED);

        boolean beforeLock = o.status() == OrderStatus.REQUESTED;
        String auditReason = beforeLock ? reason : "Closed after cutoff: " + reason;
        orders.setClosed(o.id(), beforeLock, reason, now);
        orders.insertEvent(o.id(), o.status(), OrderStatus.CLOSED, beforeLock ? 0 : o.lockedQty(), actor, auditReason, now);
        changes.bump();
        return reload(o.id());
    }

    /**
     * Centre confirms the food arrived. Receipt is a "set", not an "add":
     * sending the same count again (a double tap on a bad network) changes nothing.
     */
    @Transactional
    public ReceiveResult receive(Actor actor, long orderId, int qty, boolean confirmLarge) {
        OrderRow locked = lockOwnedByCentre(actor, orderId);
        OffsetDateTime now = time.now(); // read after the row lock: a request that waited sees the real time
        OrderRow o = applyCutoffIfDue(locked, now);

        if (o.status() == OrderStatus.RECEIVED) {
            if (Objects.equals(o.receivedQty(), qty)) {
                return new ReceiveResult(o, true);
            }
            throw ApiException.conflict("ALREADY_RECEIVED",
                    "Already confirmed as " + o.receivedQty() + " meals. Ask Ops to correct it.");
        }
        if (now.isBefore(time.startOf(o.serviceDate()))) {
            throw ApiException.conflict("TOO_EARLY", "You can confirm receipt only on the service day");
        }
        if (o.status() == OrderStatus.CLOSED) {
            throw ApiException.conflict("ORDER_CLOSED", "This order is closed");
        }
        o.status().requireMoveTo(OrderStatus.RECEIVED);

        int lockedQty = o.lockedQty();
        if (!confirmLarge && (long) qty * 100 > (long) lockedQty * props.largeReceiptPercent()) {
            throw ApiException.unprocessable("LARGE_QTY_NEEDS_CONFIRM",
                    qty + " is much more than the " + lockedQty + " meals sent. Please check and confirm.");
        }

        String reason = o.status() == OrderStatus.LOCKED ? "Kitchen had not recorded dispatch" : null;
        orders.setReceived(o.id(), qty, now);
        orders.insertEvent(o.id(), o.status(), OrderStatus.RECEIVED, qty, actor, reason, now);
        changes.bump();
        return new ReceiveResult(reload(o.id()), false);
    }

    /** Ops changes a locked count (escape hatch). Only before the food leaves the kitchen, always with a reason. */
    @Transactional
    public OrderRow overrideLockedCount(Actor ops, long orderId, int qty, String reason) {
        OrderRow locked = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        OffsetDateTime now = time.now();
        OrderRow o = applyCutoffIfDue(locked, now);

        if (o.status() != OrderStatus.LOCKED) {
            throw ApiException.conflict("NOT_LOCKED",
                    "Only a locked order that has not been dispatched can be overridden (status is " + o.status() + ")");
        }
        orders.setLocked(o.id(), qty, now);
        orders.insertEvent(o.id(), OrderStatus.LOCKED, OrderStatus.LOCKED, qty, ops, "Ops override: " + reason, now);
        changes.bump();
        return reload(o.id());
    }

    /**
     * A centre did not confirm (no network, forgot). Ops calls them and records the answer, so one
     * silent centre cannot block a kitchen's bill forever. qty = 0 means "food never arrived".
     * Allowed only after the confirm-by time, and always with a reason in the audit trail.
     */
    @Transactional
    public OrderRow confirmOnBehalf(Actor ops, long orderId, int qty, String reason) {
        OrderRow locked = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        OffsetDateTime now = time.now();
        OrderRow o = applyCutoffIfDue(locked, now);
        if (o.status() != OrderStatus.LOCKED && o.status() != OrderStatus.DISPATCHED) {
            throw ApiException.conflict("NOT_AWAITING_RECEIPT", "Only an order waiting for receipt can be confirmed (status is " + o.status() + ")");
        }
        if (now.isBefore(o.confirmBy())) {
            throw ApiException.conflict("TOO_EARLY", "Give the centre until its confirm-by time first");
        }
        orders.setReceived(o.id(), qty, now);
        orders.insertEvent(o.id(), o.status(), OrderStatus.RECEIVED, qty, ops, "Confirmed by Ops: " + reason, now);
        changes.bump();
        return reload(o.id());
    }

    private OrderRow lockOwnedByCentre(Actor actor, long orderId) {
        OrderRow o = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (actor.centreId() == null || o.centreId() != actor.centreId()) {
            // Same answer as "does not exist": never reveal other centres' orders.
            throw ApiException.notFound("Order");
        }
        return o;
    }

    /** If the cutoff passed but the sweeper has not run yet, lock now, exactly as the sweeper would. */
    private OrderRow applyCutoffIfDue(OrderRow o, OffsetDateTime now) {
        if (o.status() == OrderStatus.REQUESTED && o.cutoffPassed(now)) {
            orders.setLocked(o.id(), o.requestedQty(), now);
            orders.insertEvent(o.id(), OrderStatus.REQUESTED, OrderStatus.LOCKED, o.requestedQty(),
                    Actor.system("cutoff"), null, now);
            return reload(o.id());
        }
        return o;
    }

    private OrderRow reload(long id) {
        return orders.find(id).orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LOST", "Order vanished"));
    }

    public record ReceiveResult(OrderRow order, boolean replayed) {
    }
}
