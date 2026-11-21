package com.microservices.user.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action only once the data is saved for good: after the commit of the current transaction, never before
 * it and never after a rollback. Without a transaction the action runs at once.
 *
 * A failing action is logged and ignored: the data is already saved, so it must not turn the answer into an error.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("An action after the commit failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
