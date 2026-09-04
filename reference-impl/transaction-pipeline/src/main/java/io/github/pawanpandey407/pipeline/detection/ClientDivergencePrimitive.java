package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D1: per-client outcome divergence.
 *
 * Triggers when one client's failure ratio leaves its own baseline while
 * the rest of the fleet stays inside its own. The quiet check excludes
 * the candidate client from the fleet ratio; with a small client pool,
 * one broken client moves the inclusive average enough to suppress the
 * very verdict it should allow.
 *
 * Baselines learn nothing from windows that trigger. An anomalous
 * window folded into its own baseline teaches the detector that broken
 * is the new normal, and the divergence disappears within minutes.
 */
@Component
@Order(1)
public class ClientDivergencePrimitive implements DetectionPrimitive {

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;

    public ClientDivergencePrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D1-client-divergence";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();

        BaselineStore.RollingBaseline fleet = baselines.get(name(), "fleet", w.hourBucket());

        Set<String> clients = new HashSet<>(w.successByClient().keySet());
        clients.addAll(w.failureByClient().keySet());

        boolean anyTriggered = false;
        for (String client : clients) {
            long clientTotal = w.clientTotal(client);
            if (clientTotal < props.getMinWindowSamples()) {
                continue;
            }
            double ratio = w.clientFailureRatio(client);
            BaselineStore.RollingBaseline b = baselines.get(name(), client, w.hourBucket());

            boolean triggered = b.samples() >= props.getWarmupWindows()
                    && ratio > Thresholds.ratioTrigger(b.mean(), b.stddev(), clientTotal,
                            props.getSigma(), props.getRatioFloor())
                    && restOfFleetQuiet(w, client, clientTotal, fleet);

            if (triggered) {
                anyTriggered = true;
                Map<String, Long> stageDist = w.failuresByClientStage().getOrDefault(client, Map.of());
                out.add(new Verdict(Instant.now(), name(), client, ratio, b.mean(), b.stddev(),
                        "client failure ratio diverged from its baseline while the rest of the fleet stayed quiet",
                        Map.of(
                                "windowTransactions", clientTotal,
                                "windowFailures", w.failureByClient().getOrDefault(client, 0L),
                                "failingStages", stageDist)));
            } else {
                b.update(ratio);
            }
        }
        if (!anyTriggered) {
            fleet.update(w.fleetFailureRatio());
        }

        out.forEach(verdicts::add);
        return out;
    }

    private boolean restOfFleetQuiet(WindowSnapshot w, String candidate, long candidateTotal,
                                     BaselineStore.RollingBaseline fleet) {
        if (fleet.samples() < props.getWarmupWindows()) {
            return true;
        }
        long restTotal = w.processed() - candidateTotal;
        if (restTotal <= 0) {
            return false;
        }
        long restFailed = w.failed() - w.failureByClient().getOrDefault(candidate, 0L);
        double restRatio = (double) restFailed / restTotal;
        return restRatio <= Thresholds.ratioTrigger(fleet.mean(), fleet.stddev(), restTotal,
                props.getSigma(), props.getRatioFloor());
    }
}
