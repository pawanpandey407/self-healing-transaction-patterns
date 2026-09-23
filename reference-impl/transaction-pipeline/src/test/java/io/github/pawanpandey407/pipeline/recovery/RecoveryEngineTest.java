package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.detection.VerdictRecorded;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryEngineTest {

    private final RecoveryProperties props = new RecoveryProperties();

    @Test
    void executesAutonomousActionsForMatchingVerdicts() {
        FakeAction action = new FakeAction(AutonomyTier.AUTONOMOUS);
        RecoveryEngine engine = new RecoveryEngine(List.of(action), props);

        engine.onVerdict(new VerdictRecorded(verdict("D9-fake")));

        assertThat(action.executions).isEqualTo(1);
        assertThat(engine.decisions()).singleElement()
                .satisfies(d -> assertThat(d.outcome()).isEqualTo("executed"));
    }

    @Test
    void neverExecutesActionsAboveTheAutonomousTier() {
        FakeAction action = new FakeAction(AutonomyTier.APPROVAL_GATED);
        RecoveryEngine engine = new RecoveryEngine(List.of(action), props);

        engine.onVerdict(new VerdictRecorded(verdict("D9-fake")));

        assertThat(action.executions).isZero();
        assertThat(engine.decisions()).singleElement()
                .satisfies(d -> assertThat(d.outcome()).isEqualTo("not-executed"));
    }

    @Test
    void recordsVerdictsWithNoMappedActionAsReportedOnly() {
        RecoveryEngine engine = new RecoveryEngine(List.of(new FakeAction(AutonomyTier.AUTONOMOUS)), props);

        engine.onVerdict(new VerdictRecorded(verdict("D3-latency-divergence")));

        assertThat(engine.decisions()).singleElement()
                .satisfies(d -> assertThat(d.outcome()).isEqualTo("reported-only"));
    }

    @Test
    void reportsOnlyWhenRecoveryIsDisabled() {
        props.setEnabled(false);
        FakeAction action = new FakeAction(AutonomyTier.AUTONOMOUS);
        RecoveryEngine engine = new RecoveryEngine(List.of(action), props);

        engine.onVerdict(new VerdictRecorded(verdict("D9-fake")));

        assertThat(action.executions).isZero();
        assertThat(engine.decisions().get(0).reason()).contains("disabled");
    }

    private static Verdict verdict(String primitive) {
        return new Verdict(Instant.now(), primitive, "subject", 1, 0, 0, "test", Map.of());
    }

    private static final class FakeAction implements RecoveryAction {

        private final AutonomyTier tier;
        private int executions;

        FakeAction(AutonomyTier tier) {
            this.tier = tier;
        }

        @Override
        public String id() {
            return "FAKE";
        }

        @Override
        public AutonomyTier tier() {
            return tier;
        }

        @Override
        public boolean appliesTo(Verdict verdict) {
            return "D9-fake".equals(verdict.primitive());
        }

        @Override
        public RecoveryDecision execute(Verdict verdict) {
            executions++;
            return RecoveryDecision.of(verdict, id(), tier, "executed", "fake");
        }
    }
}
