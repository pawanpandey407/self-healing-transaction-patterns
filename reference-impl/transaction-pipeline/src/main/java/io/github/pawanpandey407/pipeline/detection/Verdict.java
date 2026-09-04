package io.github.pawanpandey407.pipeline.detection;

import java.time.Instant;
import java.util.Map;

/**
 * The only output of the detection module: what diverged, where, and the
 * evidence. By the time a human reads one of these, the localizing walk
 * (which stage, which client, how far from baseline) is already done.
 */
public record Verdict(
        Instant at,
        String primitive,
        String subject,
        double observed,
        double baselineMean,
        double baselineStddev,
        String message,
        Map<String, Object> evidence) {
}
