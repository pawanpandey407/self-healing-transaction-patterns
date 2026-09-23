package io.github.pawanpandey407.pipeline.model;

/**
 * Why a transaction is going through the stages right now.
 *
 * Only LIVE processing is traffic. Probes and replays are the recovery
 * module's own work on transactions that already arrived once; counted
 * as traffic, a replay burst looks like an arrival surge and a held
 * client's failing probes look like the fleet failing.
 */
public enum Origin {
    /** A new arrival admitted by every gate. */
    LIVE,
    /** A held transaction tried to test whether its client's path has healed. */
    PROBE,
    /** A held transaction replayed after its client was released. */
    REPLAY
}
