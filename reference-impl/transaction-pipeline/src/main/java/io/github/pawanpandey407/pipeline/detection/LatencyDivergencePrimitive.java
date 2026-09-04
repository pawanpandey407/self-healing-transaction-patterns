package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * D3: latency percentile divergence per stage.
 *
 * p99 leaving its band while p50 holds is the early warning; both
 * moving together means saturation is already underway. The verdict
 * says which of the two shapes it saw.
 */
@Component
@Order(3)
public class LatencyDivergencePrimitive implements DetectionPrimitive {

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;

    public LatencyDivergencePrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D3-latency-divergence";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : w.stageLatenciesMs().entrySet()) {
            String stage = entry.getKey();
            List<Long> samples = entry.getValue();
            if (samples.size() < props.getMinWindowSamples()) {
                continue;
            }
            double p50 = percentile(samples, 0.50);
            double p99 = percentile(samples, 0.99);
            BaselineStore.RollingBaseline b50 = baselines.get(name() + "-p50", stage, w.hourBucket());
            BaselineStore.RollingBaseline b99 = baselines.get(name() + "-p99", stage, w.hourBucket());

            boolean triggered = b99.samples() >= props.getWarmupWindows()
                    && p99 > b99.mean() + props.getSigma() * b99.stddev()
                    && p99 > b99.mean() * (1 + props.getRelativeFloor());
            if (triggered) {
                boolean p50Moved = b50.samples() >= props.getWarmupWindows()
                        && p50 > b50.mean() + props.getSigma() * b50.stddev();
                out.add(new Verdict(Instant.now(), name(), stage, p99, b99.mean(), b99.stddev(),
                        p50Moved
                                ? "p99 and p50 both inflated, saturation likely underway"
                                : "p99 diverged while p50 held, early warning shape",
                        Map.of(
                                "windowP50Ms", p50,
                                "windowP99Ms", p99,
                                "baselineP50Ms", b50.mean(),
                                "samples", samples.size())));
            } else {
                // Anomalous windows are excluded from learning; see D1.
                b50.update(p50);
                b99.update(p99);
            }
        }
        out.forEach(verdicts::add);
        return out;
    }

    private double percentile(List<Long> values, double q) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
