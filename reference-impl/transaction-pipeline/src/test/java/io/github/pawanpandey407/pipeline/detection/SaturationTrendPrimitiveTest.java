package io.github.pawanpandey407.pipeline.detection;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * D5 stamps samples with epoch seconds, so the slope has to hold up with
 * x values around 1.8e9, not just the small ones a hand-written example
 * would use.
 */
class SaturationTrendPrimitiveTest {

    private static final double EPOCH_SECONDS = 1_790_000_000.0;

    @Test
    void recoversTheTrendWhateverTheWallClockSays() {
        // Utilization climbing 0.001 per second, sampled every 15 seconds,
        // eight samples: the default window and warmup. Start times are
        // spread across a day with millisecond fractions, as observe()
        // produces them.
        for (int i = 0; i < 100; i++) {
            double start = EPOCH_SECONDS + i * 863.917;
            Deque<double[]> samples = trend(start, 8, 15.0, 0.5, 0.001);

            assertThat(SaturationTrendPrimitive.slope(samples))
                    .as("slope for samples starting at %.3f", start)
                    .isCloseTo(0.001, within(1e-9));
        }
    }

    @Test
    void flatUtilizationHasNoSlope() {
        Deque<double[]> samples = trend(EPOCH_SECONDS + 0.417, 8, 15.0, 0.6, 0.0);

        assertThat(SaturationTrendPrimitive.slope(samples)).isCloseTo(0.0, within(1e-12));
    }

    private static Deque<double[]> trend(double start, int n, double stepSeconds, double from, double perSecond) {
        Deque<double[]> samples = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            double t = start + i * stepSeconds;
            samples.addLast(new double[] {t, from + (t - start) * perSecond});
        }
        return samples;
    }
}
