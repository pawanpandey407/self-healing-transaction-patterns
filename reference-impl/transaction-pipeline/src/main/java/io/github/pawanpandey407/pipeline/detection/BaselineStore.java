package io.github.pawanpandey407.pipeline.detection;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Learned baselines, keyed by primitive, subject, and time-of-day bucket.
 *
 * The hour bucket is in the key on purpose: normal has a daily rhythm,
 * and a threshold that fits the afternoon fits three in the morning
 * badly in both directions. The synthetic generator is flat today, but
 * the model is bucketed from the start so scheduled-load simulation can
 * be added without redesigning detection.
 */
@Component
public class BaselineStore {

    private final ConcurrentMap<String, RollingBaseline> baselines = new ConcurrentHashMap<>();

    public RollingBaseline get(String primitive, String subject, int hourBucket) {
        String key = primitive + ":" + subject + ":h" + hourBucket;
        return baselines.computeIfAbsent(key, k -> new RollingBaseline());
    }

    /** Running mean and variance, Welford's algorithm. */
    public static class RollingBaseline {

        private long samples;
        private double mean;
        private double m2;

        public synchronized void update(double value) {
            samples++;
            double delta = value - mean;
            mean += delta / samples;
            m2 += delta * (value - mean);
        }

        public synchronized long samples() {
            return samples;
        }

        public synchronized double mean() {
            return mean;
        }

        public synchronized double stddev() {
            return samples > 1 ? Math.sqrt(m2 / (samples - 1)) : 0.0;
        }
    }
}
