package io.github.pawanpandey407.pipeline.detection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BinomialTest {

    @Test
    void atLeastZeroFailuresIsCertain() {
        assertThat(Binomial.upperTail(0, 12, 0.03)).isEqualTo(1.0);
    }

    @Test
    void atLeastOneFailureMatchesTheClosedForm() {
        assertThat(Binomial.upperTail(1, 5, 0.03)).isCloseTo(1 - Math.pow(0.97, 5), within(1e-12));
    }

    @Test
    void allFailuresMatchesTheClosedForm() {
        assertThat(Binomial.upperTail(12, 12, 0.5)).isCloseTo(Math.pow(0.5, 12), within(1e-15));
    }

    @Test
    void matchesADirectSumAtAModerateSize() {
        long n = 200;
        double p = 0.03;
        long k = 12;
        double direct = 0.0;
        for (long i = k; i <= n; i++) {
            direct += choose(n, i) * Math.pow(p, i) * Math.pow(1 - p, n - i);
        }
        assertThat(Binomial.upperTail(k, n, p)).isCloseTo(direct, within(1e-10));
    }

    @Test
    void oneDeclineInFiveIsOrdinaryAtANormalRate() {
        // The false positive that started this: 1 failure in 5 is a 20%
        // ratio, but at a 3% normal rate it happens about one window in seven.
        assertThat(Binomial.upperTail(1, 5, 0.03)).isGreaterThan(0.1);
    }

    private static double choose(long n, long k) {
        double out = 1.0;
        for (long j = 1; j <= k; j++) {
            out = out * (n - k + j) / j;
        }
        return out;
    }
}
