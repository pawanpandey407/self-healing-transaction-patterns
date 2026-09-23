package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.detection.Verdict;

import java.time.Instant;

/**
 * What recovery decided about one verdict, and why. Declines are recorded
 * as carefully as executions: an incident review should be able to see
 * every point where the system chose not to act.
 */
public record RecoveryDecision(
        Instant at,
        String verdictPrimitive,
        String subject,
        String action,
        AutonomyTier tier,
        String outcome,
        String reason) {

    static RecoveryDecision of(Verdict verdict, String action, AutonomyTier tier, String outcome, String reason) {
        return new RecoveryDecision(Instant.now(), verdict.primitive(), verdict.subject(), action, tier, outcome, reason);
    }
}
