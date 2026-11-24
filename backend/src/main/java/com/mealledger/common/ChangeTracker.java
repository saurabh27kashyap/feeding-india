package com.mealledger.common;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A counter that goes up whenever a write changes what screens show. Read-side caches
 * (the Ops dashboard) include it in their key, so a centre's tap is visible to Ops on the
 * very next refresh instead of after the cache expires.
 *
 * The bump happens after the transaction commits: bumping earlier would let a reader cache
 * old data under the new version. In-process only; with several app servers this would move
 * to Postgres LISTEN/NOTIFY or Redis.
 */
@Component
public class ChangeTracker {

    private final AtomicLong version = new AtomicLong();

    public void bump() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    version.incrementAndGet();
                }
            });
        } else {
            version.incrementAndGet();
        }
    }

    public long version() {
        return version.get();
    }
}
