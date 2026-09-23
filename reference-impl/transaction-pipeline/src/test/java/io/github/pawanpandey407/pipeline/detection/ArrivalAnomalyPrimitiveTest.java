package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 reads arrivals, not processing. The two diverge whenever recovery is
 * active: held arrivals are traffic that did not get processed, and a
 * replay is processing of traffic that arrived earlier.
 */
class ArrivalAnomalyPrimitiveTest {

    private VerdictStore verdicts;
    private ArrivalAnomalyPrimitive d4;

    @BeforeEach
    void setUp() {
        DetectionProperties props = new DetectionProperties();
        props.setWarmupWindows(3);
        verdicts = new VerdictStore(props, event -> { });
        d4 = new ArrivalAnomalyPrimitive(new BaselineStore(), props, verdicts);
        for (int i = 0; i < 4; i++) {
            d4.observe(window(100, 100));
        }
    }

    @Test
    void heldArrivalsAreStillTrafficNotADeadPath() {
        // Everything that arrived was held, so nothing was processed.
        List<Verdict> out = d4.observe(window(100, 0));

        assertThat(out).isEmpty();
    }

    @Test
    void aReplayWithNoNewArrivalsIsStillADeadPath() {
        // Nothing new arrived; the only processing was replaying old traffic.
        List<Verdict> out = d4.observe(window(0, 140));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).observed()).isZero();
        assertThat(out.get(0).message()).contains("dropped to zero");
    }

    private static WindowSnapshot window(long arrived, long processed) {
        return new WindowSnapshot(10, arrived, processed, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), -1, 0.1);
    }
}
