package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Origin;
import io.github.pawanpandey407.pipeline.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * R2: client isolation, with R5 safe replay on release.
 *
 * On a D1 verdict the diverging client is quarantined: its new
 * transactions are held in arrival order while the rest of the fleet
 * keeps flowing. Probe rounds then try held transactions for real. The
 * client is released only when a full window of probes shows at most
 * one failure, and the remaining backlog is replayed in order, skipping
 * anything the replay ledger shows as already completed.
 *
 * Recovery is verified by outcome, not by component health: the episode
 * records the rest of the fleet's failure ratio for the whole isolation,
 * and the ledger's duplicate count, which must be zero.
 */
@Component
public class ClientIsolationAction implements RecoveryAction {

    public static final String ID = "R2-client-isolation";

    private static final Logger log = LoggerFactory.getLogger(ClientIsolationAction.class);

    private final ClientQuarantine quarantine;
    private final TransactionPipeline pipeline;
    private final PipelineMetrics metrics;
    private final RecoveryProperties props;

    public ClientIsolationAction(ClientQuarantine quarantine, TransactionPipeline pipeline,
                                 PipelineMetrics metrics, RecoveryProperties props) {
        this.quarantine = quarantine;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.props = props;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AutonomyTier tier() {
        return AutonomyTier.AUTONOMOUS;
    }

    @Override
    public boolean appliesTo(Verdict verdict) {
        return ClientDivergencePrimitive.NAME.equals(verdict.primitive());
    }

    @Override
    public RecoveryDecision execute(Verdict verdict) {
        String client = verdict.subject();
        if (quarantine.isIsolated(client)) {
            return RecoveryDecision.of(verdict, ID, tier(), "declined",
                    "client is already isolated; detection still sees it diverging");
        }
        if (quarantine.isolatedCount() >= props.getMaxIsolatedClients()) {
            return RecoveryDecision.of(verdict, ID, tier(), "declined",
                    "isolation cap of " + props.getMaxIsolatedClients() + " reached; several clients "
                            + "diverging at once is not a single-client fault, so this needs a person");
        }
        long[] rest = restOfFleet(client);
        Optional<QuarantineEpisode> episode = quarantine.open(client, verdict, rest[0], rest[1]);
        if (episode.isEmpty()) {
            return RecoveryDecision.of(verdict, ID, tier(), "declined",
                    "isolation could not be opened; another verdict changed the quarantine first");
        }
        log.warn("RECOVERY [{}] isolated {}: new transactions held in order, probing every {} ms",
                ID, client, props.getProbeIntervalMs());
        return RecoveryDecision.of(verdict, ID, tier(), "executed",
                "new transactions for " + client + " are held in order; the rest of the fleet flows normally");
    }

    @Scheduled(fixedDelayString = "${recovery.probe-interval-ms:3000}")
    public void probeRound() {
        for (QuarantineEpisode episode : quarantine.activeEpisodes()) {
            probe(episode);
        }
    }

    /**
     * Tries up to one round of held transactions, head first. The round
     * stops at the first failure and puts that transaction back at the
     * head, so nothing behind it can complete ahead of it.
     */
    void probe(QuarantineEpisode episode) {
        for (int i = 0; i < props.getProbesPerRound(); i++) {
            Transaction tx = episode.pollHead();
            if (tx == null) {
                break;
            }
            if (episode.ledger().isCompleted(tx.getId())) {
                episode.countSkippedAlreadyCompleted();
                continue;
            }
            pipeline.process(tx, Origin.PROBE);
            boolean succeeded = tx.getStatus() == Transaction.Status.COMPLETED;
            episode.recordProbe(succeeded, props.getProbeWindow());
            if (succeeded) {
                episode.ledger().markCompleted(tx.getId());
            } else {
                episode.returnToHead(tx);
                break;
            }
        }
        if (episode.readyForRelease(props.getProbeWindow(), props.getProbeMaxFailures())) {
            release(episode);
        }
    }

    /**
     * Replays the backlog in order with reconciliation, then releases. The
     * queue stays open while it drains, so arrivals during the replay go
     * behind it instead of past it. A replay failure is final, like any
     * live decline, and is not retried.
     */
    void release(QuarantineEpisode episode) {
        episode.startReplay();
        while (true) {
            Transaction tx = episode.pollHead();
            if (tx == null) {
                if (episode.releaseIfDrained(restFailureRatioSinceIsolation(episode))) {
                    break;
                }
                continue;
            }
            if (episode.ledger().isCompleted(tx.getId())) {
                episode.countSkippedAlreadyCompleted();
                continue;
            }
            pipeline.process(tx, Origin.REPLAY);
            boolean succeeded = tx.getStatus() == Transaction.Status.COMPLETED;
            if (succeeded) {
                episode.ledger().markCompleted(tx.getId());
            }
            episode.countReplayed(succeeded);
        }
        quarantine.retire(episode);
        QuarantineEpisode.View v = episode.view();
        log.info("RECOVERY [{}] released {}: replayed {} ({} failed), skipped {} already completed, "
                        + "duplicate completions {}, rest-of-fleet failure ratio during isolation {}",
                ID, v.clientId(), v.replayed(), v.replayFailed(), v.skippedAlreadyCompleted(),
                v.duplicateCompletions(), v.restOfFleetFailureRatioDuringIsolation());
    }

    /** Null when the rest of the fleet processed nothing during the isolation. */
    private Double restFailureRatioSinceIsolation(QuarantineEpisode episode) {
        long[] rest = restOfFleet(episode.clientId());
        long processedDuring = rest[0] - episode.restProcessedAtStart();
        long failedDuring = rest[1] - episode.restFailedAtStart();
        return processedDuring > 0 ? (double) failedDuring / processedDuring : null;
    }

    /**
     * Live processed and failed counts for every client except this one.
     * Live counts only: another isolated client's probes and replays are
     * recovery work, and its failing probes are not the fleet failing.
     */
    private long[] restOfFleet(String client) {
        PipelineMetrics.Counts live = metrics.live();
        long clientSuccess = live.successByClient().getOrDefault(client, 0L);
        long clientFailure = live.failureByClient().getOrDefault(client, 0L);
        long processed = live.processedCount() - clientSuccess - clientFailure;
        long failed = live.failedCount() - clientFailure;
        return new long[] {processed, failed};
    }
}
