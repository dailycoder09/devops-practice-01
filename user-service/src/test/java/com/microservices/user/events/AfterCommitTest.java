package com.microservices.user.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** An event must only go out once the data is really saved: after the commit, never before, never after a rollback. */
class AfterCommitTest {

    @AfterEach
    void clean() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void run_runsAtOnce_whenThereIsNoTransaction() {
        List<String> ran = new ArrayList<>();

        AfterCommit.run(() -> ran.add("sent"));

        assertThat(ran).containsExactly("sent");
    }

    @Test
    void run_waitsForTheCommit_insideATransaction() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> ran.add("sent"));

        assertThat(ran).as("not before the commit").isEmpty();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertThat(ran).containsExactly("sent");
    }

    @Test
    void run_neverRuns_whenTheTransactionIsRolledBack() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> ran.add("sent"));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(ran).isEmpty();
    }

    @Test
    void run_neverLetsAFailureOfTheActionBreakTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> {
            throw new IllegalStateException("the Lambda is down");
        });

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();   // must not throw
        }
    }
}
