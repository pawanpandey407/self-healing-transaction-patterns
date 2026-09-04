package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D4: arrival anomaly.
 *
 * Two shapes: arrivals surging past the baseline band, and arrivals at
 * zero while the baseline says traffic should be flowing. The second is
 * the Family 1.3 signature, the application healthy while the path is
 * dead. Retry-vs-unique divergence from the spec waits until the
 * pipeline can generate retry traffic.
 */
@Component
@Order(4)
public class ArrivalAnomalyPrimitive implements DetectionPrimitive {

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;

    public ArrivalAnomalyPrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D4-arrival-anomaly";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        BaselineStore.RollingBaseline b = baselines.get(name(), "pipeline", w.hourBucket());
        double arrivals = w.processed();

        if (b.samples() >= props.getWarmupWindows()) {
            if (arrivals == 0 && b.mean() >= props.getMinWindowSamples()) {
                out.add(new Verdict(Instant.now(), name(), "pipeline", arrivals, b.mean(), b.stddev(),
                        "arrivals dropped to zero while baseline expects traffic, traffic-path failure signature",
                        Map.of("expectedPerWindow", b.mean())));
            } else if (arrivals > b.mean() + props.getSigma() * b.stddev()
                    && arrivals > b.mean() * (1 + props.getRelativeFloor())) {
                out.add(new Verdict(Instant.now(), name(), "pipeline", arrivals, b.mean(), b.stddev(),
                        "arrival rate surged beyond its baseline band",
                        Map.of("windowArrivals", w.processed())));
            }
        }
        // Empty windows (startup, or the path already dead) and triggered
        // windows are excluded from learning; a baseline dragged toward
        // zero by startup windows makes normal traffic look like a surge.
        if (out.isEmpty() && arrivals > 0) {
            b.update(arrivals);
        }
        out.forEach(verdicts::add);
        return out;
    }
}
