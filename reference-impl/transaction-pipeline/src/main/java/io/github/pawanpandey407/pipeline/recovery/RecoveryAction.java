package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.detection.Verdict;

/**
 * One entry in the recovery action catalog from docs/recovery-module.md.
 *
 * The module never diagnoses. An action acts only on a verdict, and every
 * decision it makes is recorded against the verdict that triggered it.
 */
public interface RecoveryAction {

    /** Catalog id, for example "R2-client-isolation". */
    String id();

    /** Fixed by policy before any incident. */
    AutonomyTier tier();

    /** Whether this action is the policy response to this kind of verdict. */
    boolean appliesTo(Verdict verdict);

    /** Carries out the action, or declines with a reason. */
    RecoveryDecision execute(Verdict verdict);
}
