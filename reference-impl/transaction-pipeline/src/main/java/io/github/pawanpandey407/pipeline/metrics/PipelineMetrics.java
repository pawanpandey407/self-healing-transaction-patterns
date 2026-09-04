package io.github.pawanpandey407.pipeline.metrics;

import io.github.pawanpandey407.pipeline.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * The detection module reads the plain counters through the typed
 * accessors below and drains per-stage latency samples once per window.
 */
@Component
public class PipelineMetrics {

    private static final int MAX_LATENCY_SAMPLES_PER_STAGE = 5000;

    private final MeterRegistry registry;

    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalSucceeded = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    private final ConcurrentMap<String, AtomicLong> failuresByStage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> successByClient = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> failureByClient = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> failuresByClientStage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Queue<Long>> stageLatenciesMs = new ConcurrentHashMap<>();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(Transaction transaction) {
        totalProcessed.incrementAndGet();
        totalSucceeded.incrementAndGet();
        increment(successByClient, transaction.getClientId());
        Counter.builder("pipeline.transactions")
                .tag("outcome", "success")
                .tag("client", transaction.getClientId())
                .register(registry)
                .increment();
    }

    public void recordFailure(Transaction transaction, String stageName) {
        totalProcessed.incrementAndGet();
        totalFailed.incrementAndGet();
        increment(failuresByStage, stageName);
        increment(failureByClient, transaction.getClientId());
        increment(failuresByClientStage.computeIfAbsent(transaction.getClientId(), k -> new ConcurrentHashMap<>()),
                stageName);
        Counter.builder("pipeline.transactions")
                .tag("outcome", "failure")
                .tag("client", transaction.getClientId())
                .tag("stage", stageName)
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

    public long totalProcessedCount() {
        return totalProcessed.get();
    }

    public long totalFailedCount() {
        return totalFailed.get();
    }

    public Map<String, Long> successByClientCounts() {
        return toPlainMap(successByClient);
    }

    public Map<String, Long> failureByClientCounts() {
        return toPlainMap(failureByClient);
    }

    public Map<String, Long> failuresByStageCounts() {
        return toPlainMap(failuresByStage);
    }

    public Map<String, Map<String, Long>> failuresByClientStageCounts() {
        Map<String, Map<String, Long>> out = new HashMap<>();
        failuresByClientStage.forEach((client, byStage) -> out.put(client, toPlainMap(byStage)));
        return out;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new TreeMap<>();
        stats.put("totalProcessed", totalProcessed.get());
        stats.put("totalSucceeded", totalSucceeded.get());
        stats.put("totalFailed", totalFailed.get());
        stats.put("failuresByStage", toSortedMap(failuresByStage));
        stats.put("successByClient", toSortedMap(successByClient));
        stats.put("failureByClient", toSortedMap(failureByClient));
        return stats;
    }

    private void increment(ConcurrentMap<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private Map<String, Long> toPlainMap(ConcurrentMap<String, AtomicLong> map) {
        Map<String, Long> out = new HashMap<>();
        map.forEach((key, value) -> out.put(key, value.get()));
        return out;
    }

    private Map<String, Long> toSortedMap(ConcurrentMap<String, AtomicLong> map) {
        Map<String, Long> sorted = new TreeMap<>();
        map.forEach((key, value) -> sorted.put(key, value.get()));
        return sorted;
    }
}
