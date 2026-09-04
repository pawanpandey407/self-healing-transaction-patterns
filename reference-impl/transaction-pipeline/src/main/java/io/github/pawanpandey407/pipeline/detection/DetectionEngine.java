package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the pipeline's counters once per window, computes the window
 * delta, and hands it to every registered primitive in order. The
 * engine knows nothing about what the primitives look for; adding a
 * primitive is adding a component, the same way pipeline stages work.
 */
@Component
public class DetectionEngine {

    private final PipelineMetrics metrics;
    private final List<DetectionPrimitive> primitives;

    private Map<String, Long> lastSuccessByClient = new HashMap<>();
    private Map<String, Long> lastFailureByClient = new HashMap<>();
    private Map<String, Long> lastFailuresByStage = new HashMap<>();
    private Map<String, Map<String, Long>> lastFailuresByClientStage = new HashMap<>();
    private long lastProcessed;
    private long lastFailed;
    private final AtomicLong windowsObserved = new AtomicLong();

    public DetectionEngine(PipelineMetrics metrics, List<DetectionPrimitive> primitives) {
        this.metrics = metrics;
        this.primitives = primitives;
    }

    /** Windows evaluated so far. If this stops growing, detection is starved. */
    public long windowsObserved() {
        return windowsObserved.get();
    }

    @Scheduled(fixedDelayString = "${detection.window-ms:10000}")
    public synchronized void tick() {
        windowsObserved.incrementAndGet();
        Map<String, Long> successByClient = metrics.successByClientCounts();
        Map<String, Long> failureByClient = metrics.failureByClientCounts();
        Map<String, Long> failuresByStage = metrics.failuresByStageCounts();
        Map<String, Map<String, Long>> failuresByClientStage = metrics.failuresByClientStageCounts();
        long processed = metrics.totalProcessedCount();
        long failed = metrics.totalFailedCount();

        WindowSnapshot window = new WindowSnapshot(
                LocalTime.now().getHour(),
                processed - lastProcessed,
                failed - lastFailed,
                delta(successByClient, lastSuccessByClient),
                delta(failureByClient, lastFailureByClient),
                delta(failuresByStage, lastFailuresByStage),
                nestedDelta(failuresByClientStage, lastFailuresByClientStage),
                metrics.drainStageLatencies(),
                cpuUtilization(),
                heapUtilization());

        for (DetectionPrimitive primitive : primitives) {
            primitive.observe(window);
        }

        lastSuccessByClient = successByClient;
        lastFailureByClient = failureByClient;
        lastFailuresByStage = failuresByStage;
        lastFailuresByClientStage = failuresByClientStage;
        lastProcessed = processed;
        lastFailed = failed;
    }

    private Map<String, Long> delta(Map<String, Long> current, Map<String, Long> previous) {
        Map<String, Long> out = new HashMap<>();
        current.forEach((key, value) -> {
            long d = value - previous.getOrDefault(key, 0L);
            if (d > 0) {
                out.put(key, d);
            }
        });
        return out;
    }

    private Map<String, Map<String, Long>> nestedDelta(Map<String, Map<String, Long>> current,
                                                       Map<String, Map<String, Long>> previous) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        current.forEach((outerKey, innerCurrent) -> {
            Map<String, Long> innerDelta = delta(innerCurrent, previous.getOrDefault(outerKey, Map.of()));
            if (!innerDelta.isEmpty()) {
                out.put(outerKey, innerDelta);
            }
        });
        return out;
    }

    private double cpuUtilization() {
        // Process CPU, not system load: the host runs other things, and a
        // saturation verdict about someone else's workload is a false alarm.
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean osBean) {
            double load = osBean.getProcessCpuLoad();
            return load < 0 ? -1 : Math.min(load, 1.0);
        }
        return -1;
    }

    private double heapUtilization() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (double) used / runtime.maxMemory();
    }
}
