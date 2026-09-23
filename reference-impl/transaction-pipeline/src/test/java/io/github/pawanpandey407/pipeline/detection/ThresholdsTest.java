package io.github.pawanpandey407.pipeline.detection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ThresholdsTest {

    @Test
    void triggerBandWidensForSmallWindows() {
        double small = Thresholds.ratioTrigger(0.02, 0.0, 6, 3.0, 0.05);
        double large = Thresholds.ratioTrigger(0.02, 0.0, 600, 3.0, 0.05);

        assertThat(small).isGreaterThan(large);
        // One failure in six transactions moves the ratio by 0.17, which is
        // sampling noise, not evidence. It must stay inside the band.
        assertThat(1.0 / 6).isLessThan(small);
    }

    @Test
    void learnedDeviationWinsWhenItIsLargerThanSamplingNoise() {
        double trigger = Thresholds.ratioTrigger(0.02, 0.10, 600, 3.0, 0.05);

        assertThat(trigger).isCloseTo(0.02 + 3.0 * 0.10 + 0.05, within(1e-9));
    }
}
