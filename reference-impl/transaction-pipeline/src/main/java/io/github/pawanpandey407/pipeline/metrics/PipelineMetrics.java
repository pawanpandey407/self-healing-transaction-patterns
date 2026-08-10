package io.github.pawanpandey407.pipeline.metrics;

import io.github.pawanpandey407.pipeline.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pipeline telemetry.
 *
 * Counts are published twice on purpose: through Micrometer for anything
 * that scrapes actuator, and as plain in-memory counters that back the
 * /stats endpoint directly. Per-client success and failure counts are the
 * outcome anchor the detection work will baseline against.
 */
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalSucceeded = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    private final ConcurrentMap<String, AtomicLong> failuresByStage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> successByClient = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> failureByClient = new ConcurrentHashMap<>();

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
        Counter.builder("pipeline.transactions")
                .tag("outcome", "failure")
                .tag("client", transaction.getClientId())
                .tag("stage", stageName)
                .register(registry)
                .increment();
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

    private Map<String, Long> toSortedMap(ConcurrentMap<String, AtomicLong> map) {
        Map<String, Long> sorted = new TreeMap<>();
        map.forEach((key, value) -> sorted.put(key, value.get()));
        return sorted;
    }
}
