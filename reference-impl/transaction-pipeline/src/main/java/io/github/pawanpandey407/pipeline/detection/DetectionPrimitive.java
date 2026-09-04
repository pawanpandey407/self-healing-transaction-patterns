package io.github.pawanpandey407.pipeline.detection;

import java.util.List;

/**
 * One generic detection primitive from the detection module spec.
 *
 * A primitive observes a window: it evaluates the window against its
 * learned baselines first, then folds the window into those baselines.
 * Evaluate-before-learn matters; a window must never be judged against
 * a baseline it has already influenced.
 */
public interface DetectionPrimitive {

    String name();

    List<Verdict> observe(WindowSnapshot window);
}
