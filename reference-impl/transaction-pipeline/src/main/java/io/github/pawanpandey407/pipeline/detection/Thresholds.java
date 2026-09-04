package io.github.pawanpandey407.pipeline.detection;

/**
 * Threshold arithmetic shared by the ratio-based primitives.
 *
 * A failure ratio measured over n transactions carries sampling noise
 * of roughly sqrt(p (1 - p) / n) on its own, before any real change. A
 * fixed floor ignores that: with six transactions in a window, a single
 * failure moves the ratio by 0.17, which is not evidence of anything.
 * The trigger band therefore widens with the larger of the learned
 * window-to-window deviation and the binomial noise for this window.
 */
final class Thresholds {

    private static final double MIN_RATE_FOR_NOISE = 0.01;

    private Thresholds() {
    }

    static double ratioTrigger(double baselineMean, double baselineStddev, long windowSamples,
                               double sigma, double ratioFloor) {
        double p = Math.max(baselineMean, MIN_RATE_FOR_NOISE);
        double binomialNoise = windowSamples > 0 ? Math.sqrt(p * (1 - p) / windowSamples) : 0.0;
        double deviation = Math.max(baselineStddev, binomialNoise);
        return baselineMean + sigma * deviation + ratioFloor;
    }
}
