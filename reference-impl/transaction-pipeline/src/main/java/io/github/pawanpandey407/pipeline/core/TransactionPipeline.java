package io.github.pawanpandey407.pipeline.core;

import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Origin;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.PipelineStage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs a transaction through the ordered stages synchronously.
 *
 * The first stage that fails stops the pipeline and is recorded on the
 * transaction, so telemetry can attribute failures to a specific stage.
 *
 * New arrivals enter through {@link #submit(Transaction)}, which counts
 * the arrival and then consults the admission gates. The recovery module
 * uses {@link #process} directly to try held transactions as probes and
 * to replay them, and says so through the origin, so its work is never
 * counted as traffic.
 */
@Service
public class TransactionPipeline {

    private final List<PipelineStage> stages;
    private final PipelineMetrics metrics;
    private final List<AdmissionGate> gates;

    public TransactionPipeline(List<PipelineStage> stages, PipelineMetrics metrics, List<AdmissionGate> gates) {
        this.stages = stages;
        this.metrics = metrics;
        this.gates = gates;
    }

    /** Entry point for new arrivals. A gate may hold the transaction instead. */
    public Transaction submit(Transaction transaction) {
        metrics.recordArrival();
        for (AdmissionGate gate : gates) {
            if (!gate.admit(transaction)) {
                return transaction;
            }
        }
        return process(transaction, Origin.LIVE);
    }

    /** Runs the stages now, with no admission check. */
    public Transaction process(Transaction transaction, Origin origin) {
        transaction.beginAttempt();
        for (PipelineStage stage : stages) {
            long start = System.nanoTime();
            boolean passed = stage.process(transaction);
            metrics.recordStageLatency(stage.name(), (System.nanoTime() - start) / 1_000_000);
            if (!passed) {
                transaction.setStatus(Transaction.Status.FAILED);
                transaction.setFailedStage(stage.name());
                metrics.recordFailure(transaction, stage.name(), origin);
                return transaction;
            }
        }
        transaction.setStatus(Transaction.Status.COMPLETED);
        metrics.recordSuccess(transaction, origin);
        return transaction;
    }
}
