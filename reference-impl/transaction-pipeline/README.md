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
  `/stats`, including per-client success and failure counts, arrivals,
  and how many processing attempts were live traffic, recovery probes,
  or replays.

## Why a steady generator and a known baseline

Anomaly detection needs a boring, predictable control. With a fixed
transaction rate and a known 2% authorization decline rate, any shift
in throughput, stage failure mix, or a single client's outcome ratio is
attributable signal. Later work in this repo builds detection on top of
exactly these numbers.

The rate is steady only while the generator interval is longer than the
time a transaction takes to process. The generator submits
synchronously, so with a shorter interval it runs back to back at
whatever pace processing allows, and anything that makes submits
cheaper, such as the recovery module holding a client's transactions,
speeds it up. An early recovery run with 100 ms intervals and default
stage latencies produced false arrival surges this way. Keep the
interval above the processing time; the defaults and the recovery demo
settings both do.

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
  "attemptsByOrigin": {"live": 22, "probe": 0, "replay": 0},
  "failureByClient": {"client-2": 1},
  "failuresByStage": {"authorization": 1},
  "successByClient": {"client-1": 12, "client-2": 9},
  "totalArrived": 22,
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

Four lessons from building it are visible in the code and worth
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
- A per-client ratio over a small window is mostly noise. The first
  version of D1 compared each client's window ratio against its own
  rolling baseline, and in its first live run it isolated three healthy
  clients and never flagged the broken one: one failure in a small
  window looked like divergence, and baselines learned from windows they
  should have rejected. D1 now pools each client's counts, shrinks that
  rate toward the fleet rate until the client has history, judges each
  window with an exact binomial test, learns only windows that look
  ordinary, and names a client only after two consecutive divergent
  windows.

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

## Recovery module

The pipeline runs two actions from `docs/recovery-module.md`: R2 client
isolation and R5 safe replay. A D1 verdict isolates the named client:
its new transactions are held in arrival order while the rest of the
fleet keeps flowing. Probe rounds try held transactions for real, head
first, and a failed probe goes back to the head. The client is released
once the last 20 probes show at most one failure, and the backlog is
replayed in order, skipping anything the replay ledger shows as already
completed. Arrivals during the replay queue behind it, so nothing
overtakes an older held transaction.

```
curl localhost:8080/recovery          # episodes and recovery decisions, newest first
```

Each episode's status moves from ISOLATED to REPLAYING to RELEASED, and
its counts balance at any moment: `quarantined` equals `heldNow` plus
`inFlight` plus `probesSucceeded` plus `replayed` plus
`skippedAlreadyCompleted`. The rest-of-fleet failure ratio is measured
at release, so it is null until then. `duplicateCompletions` must stay
at zero.

Recovery work is not traffic. Probes and replays run through the same
stages but are tagged with their origin, and detection reads live
processing only, with arrivals counted before any gate holds them.
Without that, the release replay read as a D4 arrival surge, and a
quarantined client's failing probes read as the fleet failing.

A fast demonstration, with stages sped up so the generator is never the
bottleneck and 5 second windows:

```
mvn spring-boot:run -Dspring-boot.run.arguments="--pipeline.generator.interval-ms=50 \
  --pipeline.stages.validation.min-latency-ms=1 --pipeline.stages.validation.max-latency-ms=3 \
  --pipeline.stages.authorization.min-latency-ms=4 --pipeline.stages.authorization.max-latency-ms=12 \
  --pipeline.stages.settlement.min-latency-ms=2 --pipeline.stages.settlement.max-latency-ms=6 \
  --detection.window-ms=5000 --detection.warmup-windows=6 --recovery.probe-interval-ms=1000"
```

Wait a minute for baselines, then:

```
curl -X POST "localhost:8080/inject/stage/authorization/client/client-3?probability=0.5"
curl localhost:8080/recovery      # client-3 ISOLATED within a couple of windows
curl -X DELETE "localhost:8080/inject/stage/authorization/client/client-3"
curl localhost:8080/recovery      # REPLAYING, then RELEASED with zero duplicates
```

In one run on these settings client-3 was isolated 10 seconds after the
injection and released 8 seconds after the heal. Its 153 held
transactions ended as 51 successful probes and 102 replays, with zero
duplicates, and the only verdict of the run was the D1 verdict that
started it. Known limits are listed in `docs/recovery-module.md`.

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

Recovery knobs live under the `recovery` prefix: probe interval, probes
per round, the release window and the failures allowed in it, the hold
capacity per client, and the cap on isolated clients.
