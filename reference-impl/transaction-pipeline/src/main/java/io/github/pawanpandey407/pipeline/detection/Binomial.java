package io.github.pawanpandey407.pipeline.detection;

/**
 * Exact binomial tail probabilities.
 *
 * A client's failures in a window are a count out of a count, and at the
 * sample sizes one window holds, the normal approximation is badly wrong
 * in exactly the tail that matters. One failure in five transactions at a
 * 3% normal rate looks like a 20% failure ratio; the exact question, how
 * likely is at least this many failures at the normal rate, answers "not
 * unusual at all".
 */
final class Binomial {

    private static final double NEGLIGIBLE = 1e-18;

    private Binomial() {
    }

    /** P(X >= k) for X ~ Binomial(n, p). */
    static double upperTail(long k, long n, double p) {
        if (k <= 0) {
            return 1.0;
        }
        if (k > n) {
            return 0.0;
        }
        if (p <= 0.0) {
            return 0.0;
        }
        if (p >= 1.0) {
            return 1.0;
        }
        double logRatio = Math.log(p) - Math.log1p(-p);
        double logPmf = logChoose(n, k) + k * Math.log(p) + (n - k) * Math.log1p(-p);
        double sum = 0.0;
        for (long i = k; i <= n; i++) {
            double term = Math.exp(logPmf);
            sum += term;
            if (term < NEGLIGIBLE && i > n * p) {
                break;
            }
            logPmf += Math.log((double) (n - i) / (i + 1)) + logRatio;
        }
        return Math.min(1.0, sum);
    }

    private static double logChoose(long n, long k) {
        long m = Math.min(k, n - k);
        double out = 0.0;
        for (long j = 1; j <= m; j++) {
            out += Math.log(n - m + j) - Math.log(j);
        }
        return out;
    }
}
