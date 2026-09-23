package io.github.pawanpandey407.pipeline.detection;

/**
 * Published once for every verdict the store records. Recovery listens
 * for these; detection never calls recovery directly, so either side can
 * change without the other knowing.
 */
public record VerdictRecorded(Verdict verdict) {
}
