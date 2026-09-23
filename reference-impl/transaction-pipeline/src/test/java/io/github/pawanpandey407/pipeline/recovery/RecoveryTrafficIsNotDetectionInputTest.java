package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.ArrivalAnomalyPrimitive;
import io.github.pawanpandey407.pipeline.detection.BaselineStore;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.DetectionEngine;
import io.github.pawanpandey407.pipeline.detection.DetectionPrimitive;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.detection.VerdictRecorded;
import io.github.pawanpandey407.pipeline.detection.VerdictStore;
import io.github.pawanpandey407.pipeline.detection.WindowSnapshot;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.AuthorizationStage;
import io.github.pawanpandey407.pipeline.stage.SettlementStage;
import io.github.pawanpandey407.pipeline.stage.ValidationStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes and replays are the recovery module's own work, not traffic.
 * If detection counts them, the release replay reads as an arrival
 * surge (D4), and a quarantined client's probe failures read as the
 * fleet failing (D2, and D1's rest-of-fleet check).
 *
 * The fleet here never fails on its own, so after client-3 is isolated
 * every window detection sees must be clean: no stage failures, and no
 * more processed than arrived.
 */
class RecoveryTrafficIsNotDetectionInputTest {

    private static final int PER_CLIENT = 20;
    private static final int PER_WINDOW = PER_CLIENT * 5;
    private static final List<String> CLIENTS = List.of("client-1", "client-2", "client-3", "client-4", "client-5");

    @Test
    void detectionSeesLiveTrafficOnlyThroughIsolationProbingAndReplay() {
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
        VerdictStore verdicts = new VerdictStore(detectionProps,
                event -> recovery.onVerdict((VerdictRecorded) event));
        BaselineStore baselines = new BaselineStore();
        List<WindowSnapshot> windows = new ArrayList<>();
        DetectionEngine detection = new DetectionEngine(metrics, List.of(
                new ClientDivergencePrimitive(baselines, detectionProps, verdicts),
                new ArrivalAnomalyPrimitive(baselines, detectionProps, verdicts),
                recorder(windows)));

        // Probe rounds run between arrivals, five per window, the way the
        // probe schedule and the generator interleave in the live app.
        Runnable window = () -> {
            for (int i = 0; i < PER_CLIENT; i++) {
                for (String client : CLIENTS) {
                    pipeline.submit(new Transaction(client, BigDecimal.TEN));
                }
                if (i % 4 == 3) {
                    r2.probeRound();
                }
            }
            detection.tick();
        };

        for (int i = 0; i < 6; i++) {
            window.run();
        }
        authorization.getClientFailureProbability().put("client-3", 1.0);
        while (!quarantine.isIsolated("client-3")) {
            window.run();
        }
        int isolatedFrom = windows.size();

        // Isolated with the path still broken, so every probe round fails.
        for (int i = 0; i < 3; i++) {
            window.run();
        }
        // Healed: probes pass, the client is released mid-window, and the
        // backlog replays inside that same window.
        authorization.getClientFailureProbability().remove("client-3");
        while (quarantine.isIsolated("client-3")) {
            window.run();
        }
        window.run();
        window.run();

        assertThat(quarantine.views().get(0).status()).isEqualTo("RELEASED");
        assertThat(quarantine.views().get(0).replayed()).isGreaterThan(PER_WINDOW / 2);
        // /stats still shows all the work, with recovery's share broken out.
        assertThat(metrics.snapshot().get("totalArrived")).isEqualTo((long) windows.size() * PER_WINDOW);
        assertThat(metrics.totalProcessedCount()).isGreaterThan(metrics.live().processedCount());
        assertThat(verdicts.list()).extracting(Verdict::primitive).containsExactly(ClientDivergencePrimitive.NAME);
        List<WindowSnapshot> afterIsolation = windows.subList(isolatedFrom, windows.size());
        assertThat(afterIsolation).allSatisfy(w -> {
            assertThat(w.arrived()).isEqualTo(PER_WINDOW);
            assertThat(w.failuresByStage()).isEmpty();
            assertThat(w.failureByClient()).isEmpty();
            assertThat(w.processed()).isLessThanOrEqualTo(PER_WINDOW);
        });
    }

    private static DetectionPrimitive recorder(List<WindowSnapshot> windows) {
        return new DetectionPrimitive() {
            @Override
            public String name() {
                return "window-recorder";
            }

            @Override
            public List<Verdict> observe(WindowSnapshot window) {
                windows.add(window);
                return List.of();
            }
        };
    }
}
