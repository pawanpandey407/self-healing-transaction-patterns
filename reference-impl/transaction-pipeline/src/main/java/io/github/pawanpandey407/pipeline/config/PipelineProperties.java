package io.github.pawanpandey407.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration knobs for the reference pipeline.
 *
 * The per-stage failure probability and latency range are the failure
 * injection hooks. Detection and healing experiments tune these values
 * instead of touching stage code.
 */
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    private final Generator generator = new Generator();

    /** Keyed by stage name: validation, authorization, settlement. */
    private Map<String, Stage> stages = new HashMap<>();

    public Generator getGenerator() {
        return generator;
    }

    public Map<String, Stage> getStages() {
        return stages;
    }

    public void setStages(Map<String, Stage> stages) {
        this.stages = stages;
    }

    public Stage stage(String name) {
        return stages.getOrDefault(name, new Stage());
    }

    public static class Generator {

        /** Milliseconds between synthetic transactions. */
        private long intervalMs = 500;

        /** Number of distinct synthetic client ids. */
        private int clients = 5;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getClients() {
            return clients;
        }

        public void setClients(int clients) {
            this.clients = clients;
        }
    }

    public static class Stage {

        /** Probability in [0, 1] that this stage fails a transaction. */
        private double failureProbability = 0.0;

        /** Simulated processing latency range in milliseconds. */
        private long minLatencyMs = 0;
        private long maxLatencyMs = 0;

        public double getFailureProbability() {
            return failureProbability;
        }

        public void setFailureProbability(double failureProbability) {
            this.failureProbability = failureProbability;
        }

        public long getMinLatencyMs() {
            return minLatencyMs;
        }

        public void setMinLatencyMs(long minLatencyMs) {
            this.minLatencyMs = minLatencyMs;
        }

        public long getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(long maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }
    }
}
