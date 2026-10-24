package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Buckets are keyed by clock hour and created on first use, so without
 * seeding every hour of the first day starts blind, and learns whatever
 * is in progress at the rollover as its normal.
 */
class BaselineStoreTest {

    private static final int WARMUP = 4;

    private DetectionProperties props;
    private BaselineStore store;

    @BeforeEach
    void setUp() {
        props = new DetectionProperties();
        props.setWarmupWindows(WARMUP);
        store = new BaselineStore(props);
    }

    @Test
    void aNewHourStartsFromThePreviousHourAtTheWarmupWeight() {
        BaselineStore.RollingBaseline h10 = store.get("p", "s", 10);
        for (int i = 0; i < 20; i++) {
            h10.update(100 + (i % 2 == 0 ? 10 : -10));
        }

        BaselineStore.RollingBaseline h11 = store.get("p", "s", 11);

        assertThat(h11.samples()).isEqualTo(WARMUP);
        assertThat(h11.mean()).isEqualTo(h10.mean());
        assertThat(h11.stddev()).isCloseTo(h10.stddev(), within(1e-9));
    }

    @Test
    void aBucketThatAlreadyExistsIsNeverReseeded() {
        BaselineStore.RollingBaseline h11 = store.get("p", "s", 11);
        h11.update(5);
        BaselineStore.RollingBaseline h10 = store.get("p", "s", 10);
        for (int i = 0; i < WARMUP; i++) {
            h10.update(100);
        }

        assertThat(store.get("p", "s", 11)).isSameAs(h11);
        assertThat(h11.samples()).isEqualTo(1);
    }

    @Test
    void theSeedWalksBackThroughMidnightToTheNearestHourThatExists() {
        BaselineStore.RollingBaseline h22 = store.get("p", "s", 22);
        for (int i = 0; i < WARMUP; i++) {
            h22.update(100);
        }

        // Hour 23 was never touched, so hour 0 reaches back two hours.
        BaselineStore.RollingBaseline h0 = store.get("p", "s", 0);

        assertThat(h0.samples()).isEqualTo(WARMUP);
        assertThat(h0.mean()).isEqualTo(100.0);
    }

    @Test
    void withNoWarmHourTheNearestColdOneIsUsed() {
        store.get("p", "s", 10).update(7);

        BaselineStore.RollingBaseline h11 = store.get("p", "s", 11);

        assertThat(h11.samples()).isEqualTo(1);
        assertThat(h11.mean()).isEqualTo(7.0);
    }

    @Test
    void rateBaselinesAreCopiedWhole() {
        BaselineStore.RateBaseline h10 = store.rate("p", "s", 10);
        for (int i = 0; i < 6; i++) {
            h10.add(1, 50);
        }

        BaselineStore.RateBaseline h11 = store.rate("p", "s", 11);

        assertThat(h11.windows()).isEqualTo(6);
        assertThat(h11.failures()).isEqualTo(6.0);
        assertThat(h11.transactions()).isEqualTo(300.0);
        h11.add(0, 50);
        assertThat(h10.windows()).isEqualTo(6);
    }

    @Test
    void aWeightOfZeroTurnsSeedingOff() {
        BaselineStore off = new BaselineStore(0);
        BaselineStore.RollingBaseline h10 = off.get("p", "s", 10);
        for (int i = 0; i < WARMUP; i++) {
            h10.update(100);
        }

        assertThat(off.get("p", "s", 11).samples()).isZero();
    }

    @Test
    void aDeadPathKeepsFiringAcrossTheRollover() {
        VerdictStore verdicts = new VerdictStore(props, event -> { });
        ArrivalAnomalyPrimitive d4 = new ArrivalAnomalyPrimitive(store, props, verdicts);
        for (int i = 0; i < WARMUP; i++) {
            d4.observe(arrivals(10, 60));
        }

        assertThat(d4.observe(arrivals(10, 0))).hasSize(1);
        assertThat(d4.observe(arrivals(11, 0))).hasSize(1);
        assertThat(d4.observe(arrivals(12, 0))).hasSize(1);
    }

    @Test
    void anIncidentInTheFirstWindowOfANewHourIsJudgedNotLearned() {
        VerdictStore verdicts = new VerdictStore(props, event -> { });
        ArrivalAnomalyPrimitive d4 = new ArrivalAnomalyPrimitive(store, props, verdicts);
        for (int i = 0; i < WARMUP; i++) {
            d4.observe(arrivals(10, 60 + (i % 2 == 0 ? 2 : -2)));
        }

        assertThat(d4.observe(arrivals(11, 200))).hasSize(1);
        assertThat(store.get(d4.name(), "pipeline", 11).mean()).isCloseTo(60.0, within(1e-9));
    }

    @Test
    void aBrokenClientIsNamedInTheFirstWindowsOfANewHour() {
        props.setMinWindowSamples(10);
        VerdictStore verdicts = new VerdictStore(props, event -> { });
        ClientDivergencePrimitive d1 = new ClientDivergencePrimitive(store, props, verdicts);
        for (int i = 0; i < 2 * WARMUP; i++) {
            d1.observe(outcomes(10, Map.of()));
        }

        assertThat(d1.observe(outcomes(11, Map.of("client-3", 12)))).isEmpty();
        List<Verdict> out = d1.observe(outcomes(11, Map.of("client-3", 12)));

        assertThat(out).singleElement().satisfies(v -> assertThat(v.subject()).isEqualTo("client-3"));
    }

    private static WindowSnapshot arrivals(int hour, long arrived) {
        return new WindowSnapshot(hour, arrived, arrived, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), -1, 0.1);
    }

    /** Twelve transactions per client; failures maps a client to how many of its twelve failed. */
    private static WindowSnapshot outcomes(int hour, Map<String, Integer> failures) {
        Map<String, Long> success = new HashMap<>();
        Map<String, Long> failure = new HashMap<>();
        Map<String, Map<String, Long>> byClientStage = new HashMap<>();
        long processed = 0;
        long failed = 0;
        for (String client : List.of("client-1", "client-2", "client-3", "client-4", "client-5")) {
            int f = failures.getOrDefault(client, 0);
            if (12 - f > 0) {
                success.put(client, (long) (12 - f));
            }
            if (f > 0) {
                failure.put(client, (long) f);
                byClientStage.put(client, Map.of("authorization", (long) f));
            }
            processed += 12;
            failed += f;
        }
        return new WindowSnapshot(hour, processed, processed, failed, success, failure,
                Map.of("authorization", failed), byClientStage, Map.of(), -1, 0.1);
    }
}
