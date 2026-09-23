package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.AuthorizationStage;
import io.github.pawanpandey407.pipeline.stage.PipelineStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.github.pawanpandey407.pipeline.model.Transaction.Status.COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live shape of the problem: new arrivals keep coming on one thread
 * while probe rounds and the release replay run on another, the way the
 * scheduler runs them. The path heals partway through, so the release
 * and its replay happen while arrivals are still landing.
 *
 * Every held transaction must end exactly once, and no transaction for
 * the isolated client may complete ahead of one that arrived before it.
 * A third thread reads the episode the whole time, as the recovery
 * endpoint does, and every snapshot must account for every held
 * transaction.
 */
class ClientIsolationConcurrencyTest {

    private static final String CLIENT = "client-3";
    private static final int ARRIVALS = 3000;
    private static final int HEAL_AFTER = 1000;

    @Test
    void everyHeldTransactionCompletesExactlyOnceAndInArrivalOrderUnderConcurrentArrivals() throws Exception {
        PipelineProperties pipelineProps = new PipelineProperties();
        PipelineProperties.Stage authorization = new PipelineProperties.Stage();
        authorization.setClientFailureProbability(new ConcurrentHashMap<>(Map.of(CLIENT, 1.0)));
        pipelineProps.getStages().put("authorization", authorization);
        // One millisecond of work per transaction, so the replay takes long
        // enough for arrivals to land while it is still running.
        PipelineProperties.Stage recorderSettings = new PipelineProperties.Stage();
        recorderSettings.setMinLatencyMs(1);
        recorderSettings.setMaxLatencyMs(1);
        pipelineProps.getStages().put(CompletionRecorder.NAME, recorderSettings);
        CompletionRecorder recorder = new CompletionRecorder(pipelineProps);

        PipelineMetrics metrics = new PipelineMetrics(new SimpleMeterRegistry());
        RecoveryProperties props = new RecoveryProperties();
        ClientQuarantine quarantine = new ClientQuarantine(props);
        TransactionPipeline pipeline = new TransactionPipeline(
                List.of(new AuthorizationStage(pipelineProps), recorder), metrics, List.of(quarantine));
        ClientIsolationAction action = new ClientIsolationAction(quarantine, pipeline, metrics, props);

        action.execute(new Verdict(Instant.now(), ClientDivergencePrimitive.NAME, CLIENT,
                1.0, 0.02, 0.01, "test", Map.of()));

        List<Transaction> arrivals = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean arrivalsDone = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        Set<String> phasesSeen = ConcurrentHashMap.newKeySet();
        AtomicReference<String> firstBadSnapshot = new AtomicReference<>();
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> generator = threads.submit(() -> {
                for (int i = 0; i < ARRIVALS; i++) {
                    Transaction tx = new Transaction(CLIENT, BigDecimal.TEN);
                    arrivals.add(tx);
                    pipeline.submit(tx);
                    if (i % 4 == 0) {
                        pipeline.submit(new Transaction("client-1", BigDecimal.TEN));
                    }
                    if (i == HEAL_AFTER) {
                        authorization.getClientFailureProbability().remove(CLIENT);
                    }
                    LockSupport.parkNanos(200_000);
                }
                arrivalsDone.set(true);
            });
            Future<?> prober = threads.submit(() -> {
                while (!arrivalsDone.get() || quarantine.isIsolated(CLIENT)) {
                    action.probeRound();
                    LockSupport.parkNanos(1_000_000);
                }
            });
            Future<?> reader = threads.submit(() -> {
                while (!finished.get()) {
                    QuarantineEpisode.View v = quarantine.views().get(0);
                    phasesSeen.add(v.status());
                    String problem = unaccounted(v);
                    if (problem != null) {
                        firstBadSnapshot.compareAndSet(null, problem);
                    }
                }
            });
            generator.get(60, TimeUnit.SECONDS);
            prober.get(60, TimeUnit.SECONDS);
            finished.set(true);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        // Every arrival ended, and ended completed, because the path healed.
        assertThat(arrivals).hasSize(ARRIVALS);
        assertThat(arrivals).allSatisfy(tx -> assertThat(tx.getStatus()).isEqualTo(COMPLETED));

        // Exactly once: the last stage saw each id one time, no more.
        Map<String, Integer> timesCompleted = new HashMap<>();
        recorder.completed.forEach(id -> timesCompleted.merge(id, 1, Integer::sum));
        assertThat(arrivals).allSatisfy(tx -> assertThat(timesCompleted.get(tx.getId())).isEqualTo(1));

        // Arrival order: read the client's completions as arrival numbers.
        // They must count up without a gap or a swap.
        Map<String, Integer> arrivalNumber = new HashMap<>();
        for (int i = 0; i < arrivals.size(); i++) {
            arrivalNumber.put(arrivals.get(i).getId(), i);
        }
        List<Integer> completionOrder = recorder.completed.stream()
                .filter(arrivalNumber::containsKey).map(arrivalNumber::get).toList();
        assertThat(firstOvertake(completionOrder)).isEmpty();

        // The reader really did look mid-replay, and every snapshot balanced.
        assertThat(phasesSeen).contains("ISOLATED", "REPLAYING", "RELEASED");
        assertThat(firstBadSnapshot.get()).isNull();

        QuarantineEpisode.View view = quarantine.views().get(0);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(unaccounted(view)).isNull();
        assertThat(view.duplicateCompletions()).isZero();
        assertThat(view.restOfFleetFailureRatioDuringIsolation()).isNotNull();
    }

    /** Null if the snapshot accounts for every held transaction, else what is wrong. */
    private static String unaccounted(QuarantineEpisode.View v) {
        long accounted = v.heldNow() + v.inFlight() + v.probesSucceeded() + v.replayed()
                + v.skippedAlreadyCompleted();
        if (accounted != v.quarantined()) {
            return v.status() + " snapshot accounts for " + accounted + " of " + v.quarantined() + " held";
        }
        if (v.status().equals("RELEASED") && (v.heldNow() > 0 || v.inFlight() > 0)) {
            return "RELEASED with " + v.heldNow() + " held and " + v.inFlight() + " in flight";
        }
        return null;
    }

    private static Optional<String> firstOvertake(List<Integer> completionOrder) {
        for (int i = 0; i < completionOrder.size(); i++) {
            if (completionOrder.get(i) != i) {
                return Optional.of("completion " + i + " was arrival " + completionOrder.get(i)
                        + ", so it finished ahead of arrival " + i);
            }
        }
        return Optional.empty();
    }

    /** Last stage: it only runs for transactions every earlier stage passed. */
    private static final class CompletionRecorder extends PipelineStage {

        static final String NAME = "completion-recorder";

        final Queue<String> completed = new ConcurrentLinkedQueue<>();

        CompletionRecorder(PipelineProperties properties) {
            super(properties);
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        protected boolean apply(Transaction transaction) {
            completed.add(transaction.getId());
            return true;
        }
    }
}
