package io.github.pawanpandey407.pipeline.detection;

import java.util.List;
import java.util.Map;

/**
 * One evaluation window's worth of activity: deltas of the pipeline
 * counters since the previous window, plus drained stage latencies and
 * current resource readings.
 *
 * arrived counts new transactions that reached the pipeline, including
 * any a gate held. processed, failed and the per-client and per-stage
 * maps count live processing only; recovery probes and replays are left
 * out, because they are the recovery module's work, not traffic. Stage
 * latencies include every attempt, since recovery work is real load.
 */
public record WindowSnapshot(
        int hourBucket,
        long arrived,
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
