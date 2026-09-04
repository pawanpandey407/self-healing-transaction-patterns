# Transaction Pipeline Reference Skeleton

A minimal Spring Boot service that simulates a three-stage payment
transaction pipeline. It exists so the detection and self-healing
patterns in this repo have something concrete to run against.

## What it does

- A scheduled generator emits synthetic transactions at a steady,
  configurable rate for a small pool of synthetic clients.
- Each transaction passes through three ordered stages:
  validation, authorization, settlement.
- Every stage has two configuration knobs: a failure probability and a
  simulated latency range. These are the failure injection hooks. The
  authorization stage ships with a 2% failure baseline, mirroring the
  small steady decline rate that is normal in production authorization
  traffic.
- The pipeline runs synchronously and records which stage failed a
  transaction.
- Telemetry is kept two ways: Micrometer counters (visible through the
  actuator metrics endpoint) and plain in-memory counters exposed at
  `/stats`, including per-client success and failure counts.

## Why a steady generator and a known baseline

Anomaly detection needs a boring, predictable control. With a fixed
transaction rate and a known 2% authorization decline rate, any shift
in throughput, stage failure mix, or a single client's outcome ratio is
attributable signal. Later work in this repo builds detection on top of
exactly these numbers.

## Run it

Requires Java 17+ and Maven.

```
mvn spring-boot:run
```

Then:

```
curl localhost:8080/stats
```

Example response shape:

```json
{
  "failureByClient": {"client-2": 1},
  "failuresByStage": {"authorization": 1},
  "successByClient": {"client-1": 12, "client-2": 9},
  "totalFailed": 1,
  "totalProcessed": 22,
  "totalSucceeded": 21
}
```

## Detection module

The pipeline runs the detection module from `docs/detection-module.md`
in-process. Once per window it samples the counters, computes the
window delta, and hands it to five primitives in order: per-client
outcome divergence (D1), stage failure shape shift (D2), latency
percentile divergence (D3), arrival anomaly (D4), and saturation
trending (D5). Every trigger is relative to a learned baseline; there
are no static thresholds.

```
curl localhost:8080/verdicts          # verdicts, newest first
curl localhost:8080/verdicts/status   # windows observed, verdicts recorded
```

Three lessons from building it are visible in the code and worth
knowing before you tune anything:

- Baselines learn nothing from windows that trigger. Fold an incident
  into its own baseline and the detector decides broken is normal
  within minutes.
- Ratio thresholds widen with sampling noise (`Thresholds.java`). With
  six transactions in a window, one failure moves the ratio by 0.17,
  which is not evidence of anything.
- Detection runs on its own scheduler thread. On the default single
  thread, a generator that falls behind starves the engine completely:
  monitoring silenced by the workload it watches.

## Runtime failure injection

Injection scenarios need to break things after baselines have learned
healthy behavior, which startup configuration cannot do, so the live
stage settings can be changed over HTTP:

```
# one client's authorization failures to 50%, the fleet stays at baseline
curl -X POST "localhost:8080/inject/stage/authorization/client/client-3?probability=0.5"
curl -X DELETE "localhost:8080/inject/stage/authorization/client/client-3"

# stage-wide failure probability, and stage latency range
curl -X POST "localhost:8080/inject/stage/settlement?probability=0.2"
curl -X POST "localhost:8080/inject/stage/settlement/latency?minMs=300&maxMs=500"

curl localhost:8080/inject            # current settings
```

A full demonstration: start the app, wait for warmup (two minutes at
defaults, check `windowsObserved` against `warmup-windows`), confirm
`/verdicts` is empty, inject client-3, and watch a D1 verdict name the
client and the failing stage while every other client stays quiet.
Then inject settlement latency and watch D3 localize it to settlement.

## Tuning

All knobs live in `src/main/resources/application.yml` under the
`pipeline` prefix: generator interval and client count, plus per-stage
`failure-probability`, `min-latency-ms`, and `max-latency-ms`. Raise a
stage's failure probability to simulate an incident and watch `/stats`
drift from the baseline.

Each stage also accepts `client-failure-probability`, a map of client id
to failure probability that overrides the stage default for those
clients only. This is the injection hook for per-client divergence
scenarios: one client's path breaks while the fleet stays at baseline,
which is the signature the detection module's per-client primitive is
specified against.
