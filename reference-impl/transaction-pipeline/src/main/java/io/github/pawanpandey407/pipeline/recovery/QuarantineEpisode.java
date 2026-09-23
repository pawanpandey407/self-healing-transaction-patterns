package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.model.Transaction;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One client's stay in quarantine, from the verdict that isolated it to
 * the replay that released it.
 *
 * Held transactions leave only from the head, and a failed probe goes
 * back to the head, so completions always happen in arrival order. For
 * card lifecycle traffic that matters: an activation must not overtake
 * the issuance it depends on.
 *
 * Release is not a single moment. While the backlog replays, new arrivals
 * still join the tail and replay behind it; the episode is released only
 * when the queue is empty. Letting arrivals through as soon as replay
 * started would let them finish ahead of older held transactions.
 *
 * Every held transaction is always in exactly one place, so at any
 * snapshot quarantined = heldNow + inFlight + probesSucceeded + replayed
 * + skippedAlreadyCompleted.
 */
public final class QuarantineEpisode {

    enum HoldResult { HELD, REJECTED_AT_CAPACITY, NOT_ACTIVE }

    enum Phase { ISOLATED, REPLAYING, RELEASED }

    private final String clientId;
    private final Verdict trigger;
    private final Instant isolatedAt = Instant.now();
    private final long restProcessedAtStart;
    private final long restFailedAtStart;
    private final ReplayLedger ledger = new ReplayLedger();

    private final Deque<Transaction> held = new ArrayDeque<>();
    private final Deque<Boolean> recentProbes = new ArrayDeque<>();

    private Phase phase = Phase.ISOLATED;
    private Instant releasedAt;
    private long quarantined;
    private int inFlight;
    private long rejectedAtCapacity;
    private long probesSent;
    private long probesSucceeded;
    private long replayed;
    private long replayFailed;
    private long skippedAlreadyCompleted;
    private Double restFailureRatioDuringIsolation;

    QuarantineEpisode(String clientId, Verdict trigger, long restProcessedAtStart, long restFailedAtStart) {
        this.clientId = clientId;
        this.trigger = trigger;
        this.restProcessedAtStart = restProcessedAtStart;
        this.restFailedAtStart = restFailedAtStart;
    }

    String clientId() {
        return clientId;
    }

    ReplayLedger ledger() {
        return ledger;
    }

    long restProcessedAtStart() {
        return restProcessedAtStart;
    }

    long restFailedAtStart() {
        return restFailedAtStart;
    }

    synchronized HoldResult hold(Transaction transaction, int capacity) {
        if (phase == Phase.RELEASED) {
            return HoldResult.NOT_ACTIVE;
        }
        if (held.size() >= capacity) {
            rejectedAtCapacity++;
            transaction.setStatus(Transaction.Status.FAILED);
            transaction.setFailedStage("quarantine-capacity");
            return HoldResult.REJECTED_AT_CAPACITY;
        }
        transaction.setStatus(Transaction.Status.QUARANTINED);
        held.addLast(transaction);
        quarantined++;
        return HoldResult.HELD;
    }

    /**
     * Takes the head for a probe or a replay. It counts as in flight until
     * the caller reports how it ended: a probe result, a return to the
     * head, a replay, or a ledger skip.
     */
    synchronized Transaction pollHead() {
        if (phase == Phase.RELEASED) {
            return null;
        }
        Transaction head = held.pollFirst();
        if (head != null) {
            inFlight++;
        }
        return head;
    }

    synchronized void returnToHead(Transaction transaction) {
        inFlight--;
        transaction.setStatus(Transaction.Status.QUARANTINED);
        held.addFirst(transaction);
    }

    /** A failed probe is not an ending; the caller returns it to the head. */
    synchronized void recordProbe(boolean succeeded, int window) {
        probesSent++;
        if (succeeded) {
            probesSucceeded++;
            inFlight--;
        }
        recentProbes.addLast(succeeded);
        while (recentProbes.size() > window) {
            recentProbes.pollFirst();
        }
    }

    synchronized boolean readyForRelease(int window, int maxFailures) {
        return recentProbes.size() >= window && recentProbeFailures() <= maxFailures;
    }

    /** Probing is over; the backlog drains from the head, and arrivals still queue behind it. */
    synchronized void startReplay() {
        phase = Phase.REPLAYING;
    }

    /**
     * Releases the client if nothing is left to replay. Arrivals take the
     * same lock to join the queue, so none can slip in between the empty
     * check and the release; from here on they are processed directly.
     */
    synchronized boolean releaseIfDrained(Double restFailureRatio) {
        if (!held.isEmpty()) {
            return false;
        }
        phase = Phase.RELEASED;
        releasedAt = Instant.now();
        restFailureRatioDuringIsolation = restFailureRatio;
        return true;
    }

    synchronized void countReplayed(boolean succeeded) {
        inFlight--;
        replayed++;
        if (!succeeded) {
            replayFailed++;
        }
    }

    synchronized void countSkippedAlreadyCompleted() {
        inFlight--;
        skippedAlreadyCompleted++;
    }

    synchronized int heldCount() {
        return held.size();
    }

    synchronized Transaction peekHead() {
        return held.peekFirst();
    }

    private int recentProbeFailures() {
        int failures = 0;
        for (Boolean ok : recentProbes) {
            if (!ok) {
                failures++;
            }
        }
        return failures;
    }

    synchronized View view() {
        return new View(clientId, phase.name(), isolatedAt, releasedAt,
                trigger.observed(), trigger.baselineMean(),
                held.size(), inFlight, quarantined, rejectedAtCapacity,
                probesSent, probesSucceeded, recentProbeFailures(), recentProbes.size(),
                replayed, replayFailed, skippedAlreadyCompleted, ledger.duplicateCompletions(),
                restFailureRatioDuringIsolation);
    }

    /**
     * Read-only snapshot for the recovery endpoint. The rest-of-fleet ratio
     * is measured at release, so it stays null while ISOLATED or REPLAYING.
     */
    public record View(
            String clientId,
            String status,
            Instant isolatedAt,
            Instant releasedAt,
            double triggerFailureRatio,
            double triggerBaselineRatio,
            int heldNow,
            int inFlight,
            long quarantined,
            long rejectedAtCapacity,
            long probesSent,
            long probesSucceeded,
            int recentProbeFailures,
            int recentProbeWindow,
            long replayed,
            long replayFailed,
            long skippedAlreadyCompleted,
            long duplicateCompletions,
            Double restOfFleetFailureRatioDuringIsolation) {
    }
}
