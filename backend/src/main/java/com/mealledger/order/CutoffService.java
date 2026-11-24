package com.mealledger.order;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ChangeTracker;

/**
 * Locks every open order whose cutoff has passed.
 *
 * The cutoff is data (meal_order.cutoff_at), not a cron time. So this is safe to run
 * at any time, any number of times, on any number of instances: a second run finds
 * nothing to lock. Idempotent jobs instead of distributed locks.
 */
@Service
public class CutoffService {

    private static final Logger log = LoggerFactory.getLogger(CutoffService.class);

    private final JdbcClient jdbc;
    private final ChangeTracker changes;

    public CutoffService(JdbcClient jdbc, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.changes = changes;
    }

    @Transactional
    public int lockDue(OffsetDateTime now) {
        // The UPDATE's WHERE re-checks status under the row lock, so an order a centre is
        // editing at the same moment is either locked with the new count or the edit is rejected.
        int locked = jdbc.sql("""
                WITH locked AS (
                    UPDATE meal_order
                       SET status = 'LOCKED', locked_qty = requested_qty, updated_at = :now
                     WHERE status = 'REQUESTED' AND cutoff_at <= :now
                 RETURNING id, locked_qty
                )
                INSERT INTO order_event (order_id, from_status, to_status, qty, actor_label, at)
                SELECT id, 'REQUESTED', 'LOCKED', locked_qty, 'SYSTEM:cutoff', :now FROM locked""")
                .param("now", now)
                .update();
        if (locked > 0) {
            log.info("Cutoff: locked {} orders", locked);
            changes.bump();
        }
        return locked;
    }
}
