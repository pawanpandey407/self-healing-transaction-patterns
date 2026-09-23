package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.detection.VerdictRecorded;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Verdict in, action out.
 *
 * Every recorded verdict is matched against the action catalog. Only
 * autonomous actions execute; anything in a higher tier is recorded as
 * waiting for a person. A verdict with no mapped action is recorded as
 * reported only, so the decision log shows everything detection raised.
 */
@Component
public class RecoveryEngine {

    private static final int CAPACITY = 200;

    private final List<RecoveryAction> actions;
    private final RecoveryProperties props;
    private final ConcurrentLinkedDeque<RecoveryDecision> decisions = new ConcurrentLinkedDeque<>();

    public RecoveryEngine(List<RecoveryAction> actions, RecoveryProperties props) {
        this.actions = actions;
        this.props = props;
    }

    @EventListener
    public void onVerdict(VerdictRecorded event) {
        Verdict verdict = event.verdict();
        if (!props.isEnabled()) {
            record(RecoveryDecision.of(verdict, "none", null, "reported-only", "recovery is disabled"));
            return;
        }
        boolean matched = false;
        for (RecoveryAction action : actions) {
            if (!action.appliesTo(verdict)) {
                continue;
            }
            matched = true;
            if (action.tier() == AutonomyTier.AUTONOMOUS) {
                record(action.execute(verdict));
            } else {
                record(RecoveryDecision.of(verdict, action.id(), action.tier(), "not-executed",
                        "tier " + action.tier() + " requires a person to act"));
            }
        }
        if (!matched) {
            record(RecoveryDecision.of(verdict, "none", null, "reported-only",
                    "no recovery action is mapped to this verdict yet"));
        }
    }

    public List<RecoveryDecision> decisions() {
        return Collections.unmodifiableList(new ArrayList<>(decisions));
    }

    private void record(RecoveryDecision decision) {
        decisions.addFirst(decision);
        while (decisions.size() > CAPACITY) {
            decisions.pollLast();
        }
    }
}
