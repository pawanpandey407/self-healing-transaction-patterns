package io.github.pawanpandey407.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the recovery module.
 *
 * The release rule is deliberately strict: a client leaves quarantine
 * only after a full window of probes with at most one failure. A client
 * still failing half its traffic gets a lucky run of successes now and
 * then; a window rule makes that run far too unlikely to matter.
 */
@ConfigurationProperties(prefix = "recovery")
public class RecoveryProperties {

    /** Master switch. When false, verdicts are reported but never acted on. */
    private boolean enabled = true;

    /** Milliseconds between probe rounds for each quarantined client. */
    private long probeIntervalMs = 3000;

    /** Held transactions tried for real in each probe round. */
    private int probesPerRound = 4;

    /** Number of most recent probe outcomes the release rule looks at. */
    private int probeWindow = 20;

    /** Failures allowed inside a full probe window for release. */
    private int probeMaxFailures = 1;

    /** Transactions held per client before new arrivals are failed fast. */
    private int quarantineCapacity = 5000;

    /**
     * Most clients that may be isolated at once. More than a couple of
     * clients diverging together is not a single-client fault, and
     * quarantining half the fleet would turn an incident into an outage.
     */
    private int maxIsolatedClients = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getProbeIntervalMs() {
        return probeIntervalMs;
    }

    public void setProbeIntervalMs(long probeIntervalMs) {
        this.probeIntervalMs = probeIntervalMs;
    }

    public int getProbesPerRound() {
        return probesPerRound;
    }

    public void setProbesPerRound(int probesPerRound) {
        this.probesPerRound = probesPerRound;
    }

    public int getProbeWindow() {
        return probeWindow;
    }

    public void setProbeWindow(int probeWindow) {
        this.probeWindow = probeWindow;
    }

    public int getProbeMaxFailures() {
        return probeMaxFailures;
    }

    public void setProbeMaxFailures(int probeMaxFailures) {
        this.probeMaxFailures = probeMaxFailures;
    }

    public int getQuarantineCapacity() {
        return quarantineCapacity;
    }

    public void setQuarantineCapacity(int quarantineCapacity) {
        this.quarantineCapacity = quarantineCapacity;
    }

    public int getMaxIsolatedClients() {
        return maxIsolatedClients;
    }

    public void setMaxIsolatedClients(int maxIsolatedClients) {
        this.maxIsolatedClients = maxIsolatedClients;
    }
}
