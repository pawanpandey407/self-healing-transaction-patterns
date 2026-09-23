package io.github.pawanpandey407.pipeline.recovery;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transaction ids that completed while held in one quarantine episode.
 *
 * Replay checks this ledger before running a held transaction and skips
 * anything already completed. The duplicate counter exists to prove the
 * rule held: it counts completions of an id the ledger had already seen,
 * and in a correct run it stays at zero.
 */
final class ReplayLedger {

    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final AtomicLong duplicateCompletions = new AtomicLong();

    boolean isCompleted(String transactionId) {
        return completed.contains(transactionId);
    }

    /** Records a completion. Returns false, and counts a duplicate, if the id was already there. */
    boolean markCompleted(String transactionId) {
        if (completed.add(transactionId)) {
            return true;
        }
        duplicateCompletions.incrementAndGet();
        return false;
    }

    long duplicateCompletions() {
        return duplicateCompletions.get();
    }

    int size() {
        return completed.size();
    }
}
