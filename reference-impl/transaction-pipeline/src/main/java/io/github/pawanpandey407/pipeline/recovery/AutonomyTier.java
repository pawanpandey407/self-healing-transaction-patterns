package io.github.pawanpandey407.pipeline.recovery;

/**
 * How much a recovery action may do on its own, decided in advance by
 * policy and never at runtime. The dividing line comes from production:
 * reintroducing something already proven in production may be automatic;
 * introducing something new to production is human work.
 */
public enum AutonomyTier {

    /** Executes on the verdict with no human in the loop, reported after the fact. */
    AUTONOMOUS,

    /** Prepared automatically, executed only after one human confirmation. */
    APPROVAL_GATED,

    /** Proposed only; humans carry it out through the normal change process. */
    NEVER_AUTOMATIC
}
