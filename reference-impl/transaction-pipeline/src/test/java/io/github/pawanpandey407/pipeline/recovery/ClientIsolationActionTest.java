package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.AuthorizationStage;
import io.github.pawanpandey407.pipeline.stage.SettlementStage;
import io.github.pawanpandey407.pipeline.stage.ValidationStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.github.pawanpandey407.pipeline.model.Transaction.Status.COMPLETED;
import static io.github.pawanpandey407.pipeline.model.Transaction.Status.FAILED;
import static io.github.pawanpandey407.pipeline.model.Transaction.Status.QUARANTINED;
import static org.assertj.core.api.Assertions.assertThat;

class ClientIsolationActionTest {

    private PipelineProperties.Stage authorization;
    private PipelineMetrics metrics;
    private RecoveryProperties props;
    private ClientQuarantine quarantine;
    private TransactionPipeline pipeline;
    private ClientIsolationAction action;

    @BeforeEach
    void setUp() {
        PipelineProperties pipelineProps = new PipelineProperties();
        authorization = new PipelineProperties.Stage();
        pipelineProps.getStages().put("authorization", authorization);
        metrics = new PipelineMetrics(new SimpleMeterRegistry());
        props = new RecoveryProperties();
        quarantine = new ClientQuarantine(props);
        pipeline = new TransactionPipeline(
                List.of(new ValidationStage(pipelineProps), new AuthorizationStage(pipelineProps),
                        new SettlementStage(pipelineProps)),
                metrics, List.of(quarantine));
        action = new ClientIsolationAction(quarantine, pipeline, metrics, props);
    }

    @Test
    void holdsTheIsolatedClientAndLetsTheRestOfTheFleetFlow() {
        RecoveryDecision decision = action.execute(d1("client-3"));

        Transaction held = submit("client-3");
        Transaction other = submit("client-1");

        assertThat(decision.outcome()).isEqualTo("executed");
        assertThat(held.getStatus()).isEqualTo(QUARANTINED);
        assertThat(other.getStatus()).isEqualTo(COMPLETED);
        assertThat(metrics.successByClientCounts()).doesNotContainKey("client-3");
    }

    @Test
    void staysIsolatedAndKeepsArrivalOrderWhileThePathIsStillBroken() {
        breakClient("client-3");
        action.execute(d1("client-3"));
        List<Transaction> held = IntStream.range(0, 30).mapToObj(i -> submit("client-3")).toList();

        // More rounds than the probe window, so the release rule is really
        // evaluated against a full window of failures.
        for (int i = 0; i < 25; i++) {
            action.probeRound();
        }

        QuarantineEpisode episode = quarantine.episodeFor("client-3").orElseThrow();
        assertThat(quarantine.isIsolated("client-3")).isTrue();
        assertThat(episode.heldCount()).isEqualTo(30);
        assertThat(episode.peekHead()).isSameAs(held.get(0));
        assertThat(episode.ledger().size()).isZero();
    }

    @Test
    void releasesOnceProbesAreHealthyAndCompletesEveryHeldTransactionExactlyOnce() {
        breakClient("client-3");
        action.execute(d1("client-3"));
        List<Transaction> held = IntStream.range(0, 40).mapToObj(i -> submit("client-3")).toList();
        for (int i = 0; i < 5; i++) {
            action.probeRound();
        }
        assertThat(quarantine.isIsolated("client-3")).isTrue();

        healClient("client-3");
        for (int i = 0; i < 20 && quarantine.isIsolated("client-3"); i++) {
            action.probeRound();
        }

        assertThat(quarantine.isIsolated("client-3")).isFalse();
        assertThat(held).allSatisfy(tx -> assertThat(tx.getStatus()).isEqualTo(COMPLETED));
        QuarantineEpisode.View view = quarantine.views().get(0);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(view.duplicateCompletions()).isZero();
        assertThat(metrics.successByClientCounts().get("client-3")).isEqualTo(40L);
        assertThat(submit("client-3").getStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void replaySkipsTransactionsTheLedgerAlreadyShowsAsCompleted() {
        props.setProbesPerRound(1);
        props.setProbeWindow(1);
        props.setProbeMaxFailures(0);
        action.execute(d1("client-3"));
        Transaction first = submit("client-3");
        Transaction second = submit("client-3");
        Transaction third = submit("client-3");
        quarantine.episodeFor("client-3").orElseThrow().ledger().markCompleted(second.getId());

        action.probeRound();

        QuarantineEpisode.View view = quarantine.views().get(0);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(first.getStatus()).isEqualTo(COMPLETED);
        assertThat(second.getStatus()).isEqualTo(QUARANTINED);
        assertThat(third.getStatus()).isEqualTo(COMPLETED);
        assertThat(view.skippedAlreadyCompleted()).isEqualTo(1);
        assertThat(view.duplicateCompletions()).isZero();
        assertThat(metrics.successByClientCounts().get("client-3")).isEqualTo(2L);
    }

    @Test
    void anArrivalDuringReplayQueuesBehindItAndDelaysTheRelease() {
        QuarantineEpisode episode = new QuarantineEpisode("client-3", d1("client-3"), 0, 0);
        episode.startReplay();

        // The replay has just found the queue empty when an arrival lands.
        Transaction late = new Transaction("client-3", BigDecimal.TEN);
        assertThat(episode.hold(late, 10)).isEqualTo(QuarantineEpisode.HoldResult.HELD);
        assertThat(episode.releaseIfDrained(null)).isFalse();

        assertThat(episode.pollHead()).isSameAs(late);
        episode.countReplayed(true);
        assertThat(episode.releaseIfDrained(null)).isTrue();
        assertThat(episode.hold(new Transaction("client-3", BigDecimal.TEN), 10))
                .isEqualTo(QuarantineEpisode.HoldResult.NOT_ACTIVE);
    }

    @Test
    void restOfFleetRatioIgnoresAnotherIsolatedClientsProbes() {
        breakClient("client-3");
        breakClient("client-4");
        action.execute(d1("client-3"));
        action.execute(d1("client-4"));
        Runnable round = () -> {
            for (String client : List.of("client-1", "client-2", "client-3", "client-4", "client-5")) {
                submit(client);
            }
            action.probeRound();
        };
        for (int i = 0; i < 10; i++) {
            round.run();
        }

        healClient("client-3");
        for (int i = 0; i < 20 && quarantine.isIsolated("client-3"); i++) {
            round.run();
        }

        // client-4 is still isolated and every one of its probes failed, but
        // clients 1, 2 and 5 never failed, and they are the rest of the fleet.
        QuarantineEpisode.View client3 = quarantine.views().stream()
                .filter(v -> v.clientId().equals("client-3")).findFirst().orElseThrow();
        assertThat(client3.status()).isEqualTo("RELEASED");
        assertThat(client3.restOfFleetFailureRatioDuringIsolation()).isEqualTo(0.0);
    }

    @Test
    void declinesToIsolateBeyondTheCap() {
        action.execute(d1("client-1"));
        action.execute(d1("client-2"));

        RecoveryDecision third = action.execute(d1("client-3"));

        assertThat(third.outcome()).isEqualTo("declined");
        assertThat(third.reason()).contains("cap");
        assertThat(quarantine.isIsolated("client-3")).isFalse();
    }

    @Test
    void declinesWhenTheClientIsAlreadyIsolated() {
        action.execute(d1("client-3"));

        RecoveryDecision again = action.execute(d1("client-3"));

        assertThat(again.outcome()).isEqualTo("declined");
        assertThat(quarantine.isolatedCount()).isEqualTo(1);
    }

    @Test
    void failsNewArrivalsFastOnceTheQuarantineIsFull() {
        props.setQuarantineCapacity(5);
        action.execute(d1("client-3"));
        IntStream.range(0, 5).forEach(i -> submit("client-3"));

        Transaction overflow = submit("client-3");

        assertThat(overflow.getStatus()).isEqualTo(FAILED);
        assertThat(overflow.getFailedStage()).isEqualTo("quarantine-capacity");
        assertThat(quarantine.views().get(0).rejectedAtCapacity()).isEqualTo(1);
    }

    @Test
    void respondsOnlyToClientDivergenceVerdicts() {
        Verdict latency = new Verdict(Instant.now(), "D3-latency-divergence", "settlement",
                400, 80, 10, "test", Map.of());

        assertThat(action.appliesTo(latency)).isFalse();
        assertThat(action.appliesTo(d1("client-3"))).isTrue();
    }

    private Transaction submit(String client) {
        return pipeline.submit(new Transaction(client, BigDecimal.TEN));
    }

    private void breakClient(String client) {
        authorization.getClientFailureProbability().put(client, 1.0);
    }

    private void healClient(String client) {
        authorization.getClientFailureProbability().remove(client);
    }

    private static Verdict d1(String client) {
        return new Verdict(Instant.now(), ClientDivergencePrimitive.NAME, client, 1.0, 0.02, 0.01, "test", Map.of());
    }
}
