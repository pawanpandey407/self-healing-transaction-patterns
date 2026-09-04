package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D2: error-class shape shift.
 *
 * Watches each stage's share of window failures against processed
 * volume. The pipeline's 2% authorization decline baseline is the
 * calibration case: a healthy mix that must never alarm.
 */
@Component
@Order(2)
public class ShapeShiftPrimitive implements DetectionPrimitive {

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;

    public ShapeShiftPrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D2-shape-shift";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        if (w.processed() < props.getMinWindowSamples()) {
            return out;
        }
        for (Map.Entry<String, Long> entry : w.failuresByStage().entrySet()) {
            String stage = entry.getKey();
            double share = (double) entry.getValue() / w.processed();
            BaselineStore.RollingBaseline b = baselines.get(name(), stage, w.hourBucket());
            if (b.samples() >= props.getWarmupWindows()
                    && share > Thresholds.ratioTrigger(b.mean(), b.stddev(), w.processed(),
                            props.getSigma(), props.getRatioFloor())) {
                out.add(new Verdict(Instant.now(), name(), stage, share, b.mean(), b.stddev(),
                        "stage failure share shifted beyond its baseline mix",
                        Map.of(
                                "windowProcessed", w.processed(),
                                "stageFailures", entry.getValue())));
            } else {
                // Anomalous windows are excluded from learning, otherwise the
                // baseline absorbs the incident and the alarm silences itself.
                b.update(share);
            }
        }
        out.forEach(verdicts::add);
        return out;
    }
}
