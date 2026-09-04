package io.github.pawanpandey407.pipeline.core;

import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.PipelineStage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs a transaction through the ordered stages synchronously.
 *
 * The first stage that fails stops the pipeline and is recorded on the
 * transaction, so telemetry can attribute failures to a specific stage.
 */
@Service
public class TransactionPipeline {

    private final List<PipelineStage> stages;
    private final PipelineMetrics metrics;

    public TransactionPipeline(List<PipelineStage> stages, PipelineMetrics metrics) {
        this.stages = stages;
        this.metrics = metrics;
    }

    public Transaction process(Transaction transaction) {
        for (PipelineStage stage : stages) {
            long start = System.nanoTime();
            boolean passed = stage.process(transaction);
            metrics.recordStageLatency(stage.name(), (System.nanoTime() - start) / 1_000_000);
            if (!passed) {
                transaction.setStatus(Transaction.Status.FAILED);
                transaction.setFailedStage(stage.name());
                metrics.recordFailure(transaction, stage.name());
                return transaction;
            }
        }
        transaction.setStatus(Transaction.Status.COMPLETED);
        metrics.recordSuccess(transaction);
        return transaction;
    }
}
