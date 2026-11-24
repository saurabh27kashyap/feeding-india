package com.mealledger.billing;

/**
 * The billing rule, as a pure function so it can be tested line by line.
 *
 *   delivered order:  payable = min(billed, received, locked)
 *   late closure:     payable = min(billed, locked)   (centre closed after cutoff; the kitchen cooked in good faith)
 *   not confirmed:    payable = 0, and approval waits until someone confirms
 *
 * Capping at "locked" stops paying for food nobody ordered. Paying late closures stops
 * kitchens from being punished for a centre's last-minute change (they would start padding).
 * Anything unusual gets a flag for a human to look at; money is never decided by a guess.
 */
public final class Reconciler {

    public enum Flag {
        /** Billed matches what was received. */
        OK,
        /** Billed more than received (or more than ordered). Pay the lower number. */
        OVER_BILLED,
        /** Billed less than received. Pay what was billed. */
        UNDER_BILLED,
        /** Centre has not confirmed receipt yet. Nothing can be paid until someone does. */
        NOT_CONFIRMED,
        /** Centre closed after the count was locked. The kitchen is paid for what it cooked. */
        LATE_CLOSURE
    }

    public record Result(int payableQty, Flag flag) {
    }

    private Reconciler() {
    }

    public static Result reconcile(int billedQty, String orderStatus, Integer receivedQty, Integer lockedQty) {
        int locked = lockedQty == null ? 0 : lockedQty;
        return switch (orderStatus) {
            case "CLOSED" -> new Result(Math.min(billedQty, locked), billedQty > locked ? Flag.OVER_BILLED : Flag.LATE_CLOSURE);
            case "RECEIVED" -> {
                int deliverable = Math.min(receivedQty, locked);
                if (billedQty > deliverable) {
                    yield new Result(deliverable, Flag.OVER_BILLED);
                }
                if (billedQty < deliverable) {
                    yield new Result(billedQty, Flag.UNDER_BILLED);
                }
                yield new Result(billedQty, Flag.OK);
            }
            default -> new Result(0, Flag.NOT_CONFIRMED);
        };
    }
}
