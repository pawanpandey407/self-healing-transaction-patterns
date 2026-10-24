package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Learned baselines, keyed by primitive, subject, and time-of-day bucket.
 *
 * The hour bucket is in the key on purpose: normal has a daily rhythm,
 * and a threshold that fits the afternoon fits three in the morning
 * badly in both directions. The synthetic generator is flat today, but
 * the model is bucketed from the start so scheduled-load simulation can
 * be added without redesigning detection.
 *
 * A bucket is created the first time a window touches it, so for the
 * first day of uptime every clock hour starts with no baseline. Left
 * empty, the new hour is blind for a warmup, learns whatever is in
 * progress at the rollover as its normal, and a dead path can never warm
 * it at all. So a new bucket is seeded from the nearest earlier hour
 * that reached warmup: the new hour is armed at once, and its own
 * windows take over as they arrive. The seed weight is capped at the
 * warmup length so the previous hour's history cannot outweigh the new
 * hour's own evidence for long. With a real daily rhythm, a legitimate
 * step between adjacent hours larger than the trigger band would alarm
 * until that hour learned its own normal, and triggered windows are not
 * learned, so this rule must be revisited before scheduled load exists.
 */
@Component
public class BaselineStore {

    private static final int HOURS = 24;

    private final int seedWeight;
    private final ConcurrentMap<String, RollingBaseline> baselines = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RateBaseline> rates = new ConcurrentHashMap<>();

    public BaselineStore(DetectionProperties props) {
        this(props.getWarmupWindows());
    }

    /** Seeds new hour buckets at this weight; zero turns seeding off. */
    public BaselineStore(int seedWeight) {
        this.seedWeight = seedWeight;
    }

    public BaselineStore() {
        this(new DetectionProperties());
    }

    public RollingBaseline get(String primitive, String subject, int hourBucket) {
        return baselines.computeIfAbsent(key(primitive, subject, hourBucket), k -> {
            RollingBaseline seed = nearestEarlier(baselines, primitive, subject, hourBucket,
                    b -> b.samples() >= seedWeight);
            return seed == null ? new RollingBaseline() : RollingBaseline.seededFrom(seed, seedWeight);
        });
    }

    public RateBaseline rate(String primitive, String subject, int hourBucket) {
        return rates.computeIfAbsent(key(primitive, subject, hourBucket), k -> {
            RateBaseline seed = nearestEarlier(rates, primitive, subject, hourBucket,
                    b -> b.windows() >= seedWeight);
            return seed == null ? new RateBaseline() : RateBaseline.copyOf(seed);
        });
    }

    /**
     * The nearest earlier hour's bucket that is warm, or the nearest that
     * exists if none is warm yet. Walks back through midnight.
     */
    private <B> B nearestEarlier(ConcurrentMap<String, B> buckets, String primitive, String subject,
                                 int hourBucket, Predicate<B> warm) {
        if (seedWeight <= 0) {
            return null;
        }
        B nearest = null;
        for (int back = 1; back < HOURS; back++) {
            B candidate = buckets.get(key(primitive, subject, (hourBucket - back + HOURS) % HOURS));
            if (candidate == null) {
                continue;
            }
            if (warm.test(candidate)) {
                return candidate;
            }
            if (nearest == null) {
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static String key(String primitive, String subject, int hourBucket) {
        return primitive + ":" + subject + ":h" + hourBucket;
    }

    /**
     * A failure rate learned from pooled counts rather than averaged ratios.
     *
     * Averaging per-window ratios lets a single six-transaction window
     * weigh as much as a sixty-transaction one. Pooled counts weigh every
     * transaction equally. Counts are halved once they pass a cap, so the
     * rate keeps adapting to slow, legitimate change.
     */
    public static class RateBaseline {

        private static final long CAP = 5000;

        private double failures;
        private double transactions;
        private long windows;

        /** Copied whole; the halving above the cap already bounds its weight. */
        static RateBaseline copyOf(RateBaseline source) {
            RateBaseline copy = new RateBaseline();
            synchronized (source) {
                copy.failures = source.failures;
                copy.transactions = source.transactions;
                copy.windows = source.windows;
            }
            return copy;
        }

        public synchronized void add(long windowFailures, long windowTransactions) {
            failures += windowFailures;
            transactions += windowTransactions;
            windows++;
            if (transactions > CAP) {
                failures /= 2;
                transactions /= 2;
            }
        }

        public synchronized double failures() {
            return failures;
        }

        public synchronized double transactions() {
            return transactions;
        }

        public synchronized long windows() {
            return windows;
        }
    }

    /** Running mean and variance, Welford's algorithm. */
    public static class RollingBaseline {

        private long samples;
        private double mean;
        private double m2;

        /**
         * The source's mean and stddev at no more than cap samples. The
         * sum of squares is scaled with the sample count so the stddev,
         * and with it the trigger band, matches what the source earned.
         */
        static RollingBaseline seededFrom(RollingBaseline source, int cap) {
            RollingBaseline seeded = new RollingBaseline();
            synchronized (source) {
                seeded.mean = source.mean;
                if (source.samples > cap) {
                    seeded.samples = cap;
                    seeded.m2 = cap > 1 ? source.m2 * (cap - 1) / (source.samples - 1) : 0.0;
                } else {
                    seeded.samples = source.samples;
                    seeded.m2 = source.m2;
                }
            }
            return seeded;
        }

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
