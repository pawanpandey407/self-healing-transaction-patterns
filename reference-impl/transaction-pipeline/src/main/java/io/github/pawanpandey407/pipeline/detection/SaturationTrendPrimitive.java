package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * D5: saturation trending.
 *
 * Trend, not level: an alert at 90% utilization arrives after the
 * window to act has mostly closed. This primitive fits a slope to
 * recent samples and alarms when the trend reaches the saturation
 * threshold within the projection horizon.
 */
@Component
@Order(5)
public class SaturationTrendPrimitive implements DetectionPrimitive {

    private static final int MAX_SAMPLES = 30;

    private final DetectionProperties props;
    private final VerdictStore verdicts;

    private final Deque<double[]> cpuSamples = new ArrayDeque<>();
    private final Deque<double[]> heapSamples = new ArrayDeque<>();

    public SaturationTrendPrimitive(DetectionProperties props, VerdictStore verdicts) {
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D5-saturation-trend";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        double now = System.currentTimeMillis() / 1000.0;
        check(out, cpuSamples, "cpu", now, w.cpuUtilization());
        check(out, heapSamples, "heap", now, w.heapUtilization());
        out.forEach(verdicts::add);
        return out;
    }

    private void check(List<Verdict> out, Deque<double[]> samples, String resource, double now, double value) {
        if (value < 0) {
            return;
        }
        samples.addLast(new double[] {now, value});
        while (samples.size() > MAX_SAMPLES) {
            samples.pollFirst();
        }
        if (samples.size() < props.getWarmupWindows()) {
            return;
        }
        // Only project once utilization is meaningfully elevated. A slope
        // fitted to idle-level noise projects nonsense crossings.
        if (value < props.getSaturationThreshold() / 2) {
            return;
        }
        double slopePerSecond = slope(samples);
        if (slopePerSecond <= 0 || value >= props.getSaturationThreshold()) {
            return;
        }
        double secondsToSaturation = (props.getSaturationThreshold() - value) / slopePerSecond;
        if (secondsToSaturation <= props.getProjectionHorizonSeconds()) {
            out.add(new Verdict(Instant.now(), name(), resource, value, props.getSaturationThreshold(), 0.0,
                    "utilization trending toward saturation within the projection horizon",
                    Map.of(
                            "slopePerSecond", slopePerSecond,
                            "projectedSecondsToSaturation", (long) secondsToSaturation)));
        }
    }

    private double slope(Deque<double[]> samples) {
        int n = samples.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (double[] s : samples) {
            sumX += s[0];
            sumY += s[1];
            sumXY += s[0] * s[1];
            sumXX += s[0] * s[0];
        }
        double denominator = n * sumXX - sumX * sumX;
        return denominator == 0 ? 0 : (n * sumXY - sumX * sumY) / denominator;
    }
}
