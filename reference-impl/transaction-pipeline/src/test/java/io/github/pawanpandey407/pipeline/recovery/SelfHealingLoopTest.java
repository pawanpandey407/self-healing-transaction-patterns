package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.BaselineStore;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.DetectionEngine;
import io.github.pawanpandey407.pipeline.detection.VerdictRecorded;
import io.github.pawanpandey407.pipeline.detection.VerdictStore;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.AuthorizationStage;
import io.github.pawanpandey407.pipeline.stage.SettlementStage;
import io.github.pawanpandey407.pipeline.stage.ValidationStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole loop with no human in it: a healthy baseline, one client's
 * path breaks, detection names the client, recovery isolates it, the rest
 * of the fleet never notices, the path heals, probes confirm it, and the
 * held backlog is replayed with every transaction completing exactly once.
 */
class SelfHealingLoopTest {

    private static final int PER_CLIENT = 12;
    private static final List<String> CLIENTS = List.of("client-1", "client-2", "client-3", "client-4", "client-5");

    @Test
    void detectsIsolatesProbesReleasesAndReplaysWithoutTouchingTheRestOfTheFleet() {
        PipelineProperties pipelineProps = new PipelineProperties();
        PipelineProperties.Stage authorization = new PipelineProperties.Stage();
        pipelineProps.getStages().put("authorization", authorization);
        PipelineMetrics metrics = new PipelineMetrics(new SimpleMeterRegistry());

        RecoveryProperties recoveryProps = new RecoveryProperties();
        ClientQuarantine quarantine = new ClientQuarantine(recoveryProps);
        TransactionPipeline pipeline = new TransactionPipeline(
                List.of(new ValidationStage(pipelineProps), new AuthorizationStage(pipelineProps),
                        new SettlementStage(pipelineProps)),
                metrics, List.of(quarantine));
        ClientIsolationAction r2 = new ClientIsolationAction(quarantine, pipeline, metrics, recoveryProps);
        RecoveryEngine recovery = new RecoveryEngine(List.of(r2), recoveryProps);

        DetectionProperties detectionProps = new DetectionProperties();
        detectionProps.setWarmupWindows(3);
        detectionProps.setMinWindowSamples(10);
        VerdictStore verdicts = new VerdictStore(detectionProps,
                event -> recovery.onVerdict((VerdictRecorded) event));
        DetectionEngine detection = new DetectionEngine(metrics,
                List.of(new ClientDivergencePrimitive(new BaselineStore(), detectionProps, verdicts)));

        Runnable window = () -> {
            for (int i = 0; i < PER_CLIENT; i++) {
                for (String client : CLIENTS) {
                    pipeline.submit(new Transaction(client, BigDecimal.TEN));
                }
            }
            detection.tick();
        };

        // Healthy baseline.
        for (int i = 0; i < 4; i++) {
            window.run();
        }
        assertThat(verdicts.list()).isEmpty();

        // client-3's authorization path breaks. One window is not an
        // incident; the second consecutive divergent window is, and
        // recovery isolates the client as soon as detection names it.
        authorization.getClientFailureProbability().put("client-3", 1.0);
        window.run();
        assertThat(quarantine.isIsolated("client-3")).isFalse();
        window.run();
        assertThat(quarantine.isIsolated("client-3")).isTrue();
        assertThat(recovery.decisions().get(0).action()).isEqualTo(ClientIsolationAction.ID);
        assertThat(recovery.decisions().get(0).outcome()).isEqualTo("executed");

        // Two more windows while isolated: client-3 is held, everyone else flows.
        window.run();
        window.run();
        QuarantineEpisode episode = quarantine.episodeFor("client-3").orElseThrow();
        assertThat(episode.heldCount()).isEqualTo(2 * PER_CLIENT);
        assertThat(verdicts.list()).hasSize(1);

        // The path heals. Probes confirm it, and the backlog replays.
        authorization.getClientFailureProbability().remove("client-3");
        for (int i = 0; i < 20 && quarantine.isIsolated("client-3"); i++) {
            r2.probeRound();
        }

        QuarantineEpisode.View view = quarantine.views().get(0);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(view.quarantined()).isEqualTo(2 * PER_CLIENT);
        assertThat(view.duplicateCompletions()).isZero();
        assertThat(view.restOfFleetFailureRatioDuringIsolation()).isEqualTo(0.0);
        assertThat(metrics.successByClientCounts().get("client-3")).isEqualTo(4L * PER_CLIENT + 2L * PER_CLIENT);
    }
}
