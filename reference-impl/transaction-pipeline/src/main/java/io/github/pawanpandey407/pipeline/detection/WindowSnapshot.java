package io.github.pawanpandey407.pipeline.detection;

import java.util.List;
import java.util.Map;

/**
 * One evaluation window's worth of activity: deltas of the pipeline
 * counters since the previous window, plus drained stage latencies and
 * current resource readings.
 */
public record WindowSnapshot(
        int hourBucket,
        long processed,
        long failed,
        Map<String, Long> successByClient,
        Map<String, Long> failureByClient,
        Map<String, Long> failuresByStage,
        Map<String, Map<String, Long>> failuresByClientStage,
        Map<String, List<Long>> stageLatenciesMs,
        double cpuUtilization,
        double heapUtilization) {

    public long clientTotal(String clientId) {
        return successByClient.getOrDefault(clientId, 0L) + failureByClient.getOrDefault(clientId, 0L);
    }

    public double clientFailureRatio(String clientId) {
        long total = clientTotal(clientId);
        return total == 0 ? 0.0 : (double) failureByClient.getOrDefault(clientId, 0L) / total;
    }

    public double fleetFailureRatio() {
        return processed == 0 ? 0.0 : (double) failed / processed;
    }
}
