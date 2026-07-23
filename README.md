# Self-Healing Transaction Patterns

A standardization framework and reference implementation for self-healing financial transaction architectures.

**Author:** Pawan Pandey

## The problem

Most financial transaction infrastructure operates on a reactive model: a component fails, transactions are lost or delayed, humans get paged, and the fix happens after the damage. Payment systems are critical infrastructure. As instant payment rails (FedNow, RTP) remove the overnight maintenance window entirely, "detect and recover faster than a human can intervene" stops being a nice-to-have and becomes the design requirement.

This project documents and implements architecture patterns that let transaction pipelines:

1. **Detect** anomalies in transaction flow (node failures, latency degradation, volume surges) from telemetry, before they become outages
2. **Decide** using codified policies instead of a runbook and a pager
3. **Recover** automatically: re-route around failing nodes, shed or buffer load, roll back bad deployments, restore steady state without manual intervention

## Repository layout

```
docs/
  failure-mode-taxonomy.md    Taxonomy of failure modes in card transaction pipelines
  patterns/                   One doc per self-healing pattern (detection signal -> decision -> recovery action)
  adr/                        Architecture decision records
reference-impl/
  transaction-pipeline/       Minimal transaction pipeline (Java / Spring Boot) used as the test subject
  detection/                  Anomaly detection over pipeline telemetry
  recovery/                   Automated recovery actions (re-route, failover, rollback)
  chaos/                      Failure injection used to demonstrate each pattern end to end
```

## Status and roadmap

This is an active work in progress.

- [x] Failure-mode taxonomy for card transaction pipelines (v0, see `docs/failure-mode-taxonomy.md`)
- [ ] Pattern catalog: one document per failure mode family
- [ ] Reference pipeline skeleton (Spring Boot)
- [ ] Detection module: latency and error-rate anomaly signals
- [ ] Recovery module: automated re-route and failover demonstration
- [ ] Failure injection scenarios reproducing each taxonomy entry

## Scope and non-goals

Everything here is original work based on general, publicly known engineering principles. It contains no proprietary code, data, or architecture details from any employer. The reference implementation is a demonstration system, not a production payment processor: it processes synthetic transactions only.

## License

Apache License 2.0
