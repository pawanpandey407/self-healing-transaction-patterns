package io.github.pawanpandey407.pipeline.metrics;

import io.github.pawanpandey407.pipeline.model.Origin;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pipeline telemetry.
 *
 * Counts are published twice on purpose: through Micrometer for anything
 * that scrapes actuator, and as plain in-memory counters that back the
 * /stats endpoint directly. Per-client success and failure counts are the
 * outcome anchor the detection work will baseline against.
 *
 * Outcomes are kept twice as well. The all-traffic counts cover every
 * attempt, including recovery probes and replays, and back /stats. The
 * live counts cover only new arrivals processed normally, and are what
 * detection reads: recovery's own work is not traffic. Arrivals are
 * counted separately, at the door, whether or not a gate then holds them.
 *
 * The detection module reads the plain counters through the typed
 * accessors below and drains per-stage latency samples once per window.
 */
@Component
public class PipelineMetrics {

    private static final int MAX_LATENCY_SAMPLES_PER_STAGE = 5000;

    private final MeterRegistry registry;

    private final AtomicLong totalArrived = new AtomicLong();
    private final Counts all = new Counts();
    private final Counts live = new Counts();
    private final Map<Origin, AtomicLong> attemptsByOrigin = new EnumMap<>(Origin.class);
    private final ConcurrentMap<String, Queue<Long>> stageLatenciesMs = new ConcurrentHashMap<>();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Origin origin : Origin.values()) {
            attemptsByOrigin.put(origin, new AtomicLong());
        }
    }

    /** A new transaction reached the pipeline, before any gate decides what to do with it. */
    public void recordArrival() {
        totalArrived.incrementAndGet();
    }

    public void recordSuccess(Transaction transaction, Origin origin) {
        all.recordSuccess(transaction);
        if (origin == Origin.LIVE) {
            live.recordSuccess(transaction);
        }
        attemptsByOrigin.get(origin).incrementAndGet();
        Counter.builder("pipeline.transactions")
                .tag("outcome", "success")
                .tag("client", transaction.getClientId())
                .tag("origin", origin.name().toLowerCase())
                .register(registry)
                .increment();
    }

    public void recordFailure(Transaction transaction, String stageName, Origin origin) {
        all.recordFailure(transaction, stageName);
        if (origin == Origin.LIVE) {
            live.recordFailure(transaction, stageName);
        }
        attemptsByOrigin.get(origin).incrementAndGet();
        Counter.builder("pipeline.transactions")
                .tag("outcome", "failure")
                .tag("client", transaction.getClientId())
                .tag("stage", stageName)
                .tag("origin", origin.name().toLowerCase())
                .register(registry)
                .increment();
    }

    public void recordStageLatency(String stageName, long millis) {
        Queue<Long> samples = stageLatenciesMs.computeIfAbsent(stageName, k -> new ConcurrentLinkedQueue<>());
        if (samples.size() < MAX_LATENCY_SAMPLES_PER_STAGE) {
            samples.add(millis);
        }
    }

    /** Returns and clears the latency samples gathered since the last drain. */
    public Map<String, List<Long>> drainStageLatencies() {
        Map<String, List<Long>> out = new HashMap<>();
        stageLatenciesMs.forEach((stage, queue) -> {
            List<Long> drained = new ArrayList<>();
            Long value;
            while ((value = queue.poll()) != null) {
                drained.add(value);
            }
            if (!drained.isEmpty()) {
                out.put(stage, drained);
            }
        });
        return out;
    }

    public long totalArrivedCount() {
        return totalArrived.get();
    }

    /** Outcomes of live processing only; what detection and recovery's fleet checks read. */
    public Counts live() {
        return live;
    }

    public long totalProcessedCount() {
        return all.processedCount();
    }

    public long totalFailedCount() {
        return all.failedCount();
    }

    public Map<String, Long> successByClientCounts() {
        return all.successByClient();
    }

    public Map<String, Long> failureByClientCounts() {
        return all.failureByClient();
    }

    public Map<String, Long> failuresByStageCounts() {
        return all.failuresByStage();
    }

    public Map<String, Map<String, Long>> failuresByClientStageCounts() {
        return all.failuresByClientStage();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new TreeMap<>();
        stats.put("totalArrived", totalArrived.get());
        stats.put("totalProcessed", all.processed.get());
        stats.put("totalSucceeded", all.succeeded.get());
        stats.put("totalFailed", all.failed.get());
        stats.put("failuresByStage", toSortedMap(all.failuresByStage));
        stats.put("successByClient", toSortedMap(all.successByClient));
        stats.put("failureByClient", toSortedMap(all.failureByClient));
        Map<String, Long> attempts = new TreeMap<>();
        attemptsByOrigin.forEach((origin, count) -> attempts.put(origin.name().toLowerCase(), count.get()));
        stats.put("attemptsByOrigin", attempts);
        return stats;
    }

    /** One set of outcome counters. */
    public static final class Counts {

        private final AtomicLong processed = new AtomicLong();
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final ConcurrentMap<String, AtomicLong> failuresByStage = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicLong> successByClient = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicLong> failureByClient = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> failuresByClientStage =
                new ConcurrentHashMap<>();

        private void recordSuccess(Transaction transaction) {
            processed.incrementAndGet();
            succeeded.incrementAndGet();
            increment(successByClient, transaction.getClientId());
        }

        private void recordFailure(Transaction transaction, String stageName) {
            processed.incrementAndGet();
            failed.incrementAndGet();
            increment(failuresByStage, stageName);
            increment(failureByClient, transaction.getClientId());
            increment(failuresByClientStage.computeIfAbsent(transaction.getClientId(),
                    k -> new ConcurrentHashMap<>()), stageName);
        }

        public long processedCount() {
            return processed.get();
        }

        public long failedCount() {
            return failed.get();
        }

        public Map<String, Long> successByClient() {
            return toPlainMap(successByClient);
        }

        public Map<String, Long> failureByClient() {
            return toPlainMap(failureByClient);
        }

        public Map<String, Long> failuresByStage() {
            return toPlainMap(failuresByStage);
        }

        public Map<String, Map<String, Long>> failuresByClientStage() {
            Map<String, Map<String, Long>> out = new HashMap<>();
            failuresByClientStage.forEach((client, byStage) -> out.put(client, toPlainMap(byStage)));
            return out;
        }
    }

    private static void increment(ConcurrentMap<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static Map<String, Long> toPlainMap(ConcurrentMap<String, AtomicLong> map) {
        Map<String, Long> out = new HashMap<>();
        map.forEach((key, value) -> out.put(key, value.get()));
        return out;
    }

    private static Map<String, Long> toSortedMap(ConcurrentMap<String, AtomicLong> map) {
        Map<String, Long> sorted = new TreeMap<>();
        map.forEach((key, value) -> sorted.put(key, value.get()));
        return sorted;
    }
}
