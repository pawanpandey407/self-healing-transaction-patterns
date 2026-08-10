package io.github.pawanpandey407.pipeline.stage;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.model.Transaction;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Base class for pipeline stages.
 *
 * Each stage simulates work by sleeping inside its configured latency
 * range, then rolls against its configured failure probability. Real
 * stage logic would replace the body of {@link #apply(Transaction)}.
 */
public abstract class PipelineStage {

    private final PipelineProperties properties;

    protected PipelineStage(PipelineProperties properties) {
        this.properties = properties;
    }

    /** Stage name as used in configuration and metrics. */
    public abstract String name();

    /**
     * Runs the stage against a transaction.
     *
     * @return true if the stage passed, false if it failed the transaction
     */
    public boolean process(Transaction transaction) {
        PipelineProperties.Stage settings = properties.stage(name());
        simulateLatency(settings);
        if (ThreadLocalRandom.current().nextDouble() < settings.getFailureProbability()) {
            return false;
        }
        return apply(transaction);
    }

    /** Stage-specific logic. Skeleton stages accept everything. */
    protected boolean apply(Transaction transaction) {
        return true;
    }

    private void simulateLatency(PipelineProperties.Stage settings) {
        long min = settings.getMinLatencyMs();
        long max = Math.max(min, settings.getMaxLatencyMs());
        if (max <= 0) {
            return;
        }
        long latency = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
