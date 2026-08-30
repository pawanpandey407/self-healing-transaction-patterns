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
