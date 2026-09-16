# Recovery Module Specification

**Consumes:** verdicts from the detection module (`docs/detection-module.md`)
**Runs against:** the reference pipeline in `reference-impl/transaction-pipeline/`
**Status:** v0, specification only; implementation follows in the reference pipeline

## Purpose and scope

The detection module ends at a verdict: what diverged and where. This module starts there. It maps verdicts to recovery actions, decides which actions may run without a human, executes them, and verifies by outcome that the pipeline actually recovered. The taxonomy's split holds: detection is generic, recovery is where domain policy lives, so this module is mostly a policy engine wrapped around a small catalog of actions.

Scope is availability recovery: restoring transaction flow after the failure families in the taxonomy. Correctness repair (Family 7) is out of scope; the correct response to a reconciliation failure is to refuse to publish, which is a detection-side quarantine, not a recovery action.

## What recovery actually looked like

Before specifying what a machine should do, it is worth recording what people did.

**Field note:** recovery in my production experience was a mix of everything, but most incidents were infrastructure issues rather than code, so the two actions I performed far more than any other were restarting pods and then rerunning whatever had stopped. We did this tremendously often. Rollback existed as a pipeline that SRE had set up, and developers could run it ourselves because both the regular change requests and the rollback change requests were already approved by the senior roles ahead of time. The approval happened before the incident, not during it.

**Field note:** failover existed for us only inside a cluster. Instances could fail over to their siblings, and that handled single-instance problems. What we had no standby for was the cluster itself: if the pods went down across one or two clusters, processing of client data simply stopped until it was fixed, and once it was fixed we had to rerun what had stopped or was still pending. At that level recovery was never failover. It was repair, then replay.

Three lessons from those years shape every rule below.

1. **Pre-approved action classes are what make speed safe.** The rollback pipeline was fast precisely because the approval was already on file. Autonomous recovery is the same idea taken to its end: approve the class of action in advance, under stated conditions, and let the machine execute instances of it.
2. **The common actions are the dangerous ones.** Restart and rerun were routine and both bit us. See the safety rules.
3. **Where there is no standby, replay is the recovery path.** Instance-level failover handles instance-level faults and nothing above them. A system with no standby at the level that actually failed recovers by fixing the fault and reprocessing the backlog, which means replay safety (pattern 3.2) is a prerequisite for recovery, not an afterthought.

## Design principles

1. **Verdict in, action out.** The module never diagnoses. It acts only on verdicts, and every action it takes is attributed to the verdict that triggered it.
2. **Autonomy by action class, decided in advance.** Every action in the catalog carries an autonomy tier fixed by policy before any incident. No tier is decided at runtime.
3. **Verify by outcome.** An action has succeeded when the detection module's outcome signals return to baseline, not when the component reports healthy. A restart that yields green pods and no transaction flow has not recovered anything.
4. **Every action is reversible or replay-safe.** Actions that can destroy in-flight work are not permitted in their naive form.

## Autonomy tiers

**Field note:** asked what I would trust a machine to do on its own, my answer is most of it. The one place I want a human is where new code is being introduced to production. Everything else, restarting, rerunning, rolling back to something that already ran in production, is work that people did on autopilot anyway, under approvals that were granted in advance.

| Tier | Meaning | Examples |
|---|---|---|
| Autonomous | Executes on verdict, no human in the loop, reported after the fact | Restart an instance, isolate a client's traffic, shed retry load, replay a stopped batch with reconciliation |
| Approval-gated | Prepared automatically, executed on one human confirmation | Roll back to the previous release, scale beyond a declared capacity ceiling |
| Never automatic | Proposed only, executed by humans through the normal change process | Deploy new code, change a schema, alter a client's business configuration |

The dividing line is the one from the field: anything that reintroduces something already proven in production may be automatic or one-click; anything that introduces something new to production is human work. Rollback sits in the gated tier rather than the autonomous tier because of the second safety rule below.

## Recovery action catalog

Each action names the verdicts that can trigger it, its tier, its safety preconditions, and its outcome check.

### R1. Instance restart

- **Triggered by:** D3 latency divergence localized to a stage, D5 saturation trend, D4 zero-arrival where the instance is the suspected path.
- **Tier:** autonomous.
- **Preconditions:** the instance is drained first. In-flight work is completed or handed back before the process dies. For broker consumers, offsets are committed only for messages fully processed, so an uncommitted message is redelivered rather than lost.
- **Outcome check:** stage latency and per-client outcomes back within baseline within one detection window.

**Field note:** restarts were our most common fix and they were not free. There were times a restart happened on an application that was a consumer on the message broker, and data was lost or unusual patterns appeared afterward, because whatever the consumer was holding when it died was gone. The drain precondition exists because of those incidents. A restart that is not drain-aware trades one incident for a quieter, harder-to-see second one.

### R2. Client isolation

- **Triggered by:** D1 per-client divergence.
- **Tier:** autonomous.
- **Action:** the diverging client's transactions are routed to a quarantine path (queued for replay, or failed fast with a retry-after) so that a client-specific fault, such as a broken client configuration or a client-specific vendor path, cannot consume shared capacity. The rest of the fleet keeps flowing.
- **Outcome check:** fleet outcomes unchanged; isolated client's backlog replayed safely once its path is repaired.

### R3. Load shedding

- **Triggered by:** D4 surge, D5 saturation trend, retry-ratio divergence.
- **Tier:** autonomous.
- **Action:** the retry budget and shedding policy from pattern 3.2: reject over-budget retries fast, protect first-attempt traffic.
- **Outcome check:** arrival rate back within band, first-attempt success rate at baseline.

### R4. Rollback to last known-good release

- **Triggered by:** D2 or D3 verdicts correlated with a recent deployment event; the fail-to-start gate in pattern 5.1.
- **Tier:** approval-gated (autonomous only inside the fail-to-start gate, where the new version never served traffic).
- **Preconditions:** the release and its configuration are versioned and rolled back as one unit. A rollback that restores the previous binary against the current configuration is not a rollback.
- **Outcome check:** version cohort outcomes back at pre-deployment baseline.

**Field note:** rollback was the second thing that bit us. The pipeline rolled the application back correctly, but the configuration setup that went with the release was missed, and the result was worse than the problem we were rolling back from. Code and config were changed together going forward and separately going back. The precondition above is that incident written as a rule.

### R5. Replay of stopped work

- **Triggered by:** any recovery that left a backlog: R1, R2, or a repaired traffic path (Family 1.3).
- **Tier:** autonomous, subject to the replay safety rule from pattern 3.2: reconcile first, replay only records with no completed correlation id, publish the reconciliation verdict before the rerun starts.
- **Outcome check:** backlog drained, zero duplicate completions.

## Failover

The taxonomy's self-healing targets mention failover to a standby. Failover is real at the instance level, where the orchestrator moves work to a healthy sibling, and that covers single-instance faults. Above that line it is a different matter: in my experience there was no standby for the cluster itself, so the failures that stopped client processing had no failover path at all. This spec therefore treats failover as an action only where a rehearsed standby actually exists at the level that failed. Where none exists, the honest recovery path is R1 through R5: repair, then replay, with the backlog protected in the meantime. A failover that has never been rehearsed is not a recovery action; it is a hypothesis, and incidents are the wrong time to test hypotheses.

## Execution record

Every action produces an execution record joined to its triggering verdict: the action, its tier, the preconditions checked and their results, the time to outcome-verified recovery, and the outcome check evidence. Records go to the same channel as verdicts. An incident postmortem should be assemblable from the verdict and record stream alone.

## Validation against the reference pipeline

The reference pipeline has a detection module and an injection API today; recovery actions are the implementation work this spec precedes. Planned scenarios:

1. **R2 on D1.** Inject a per-client failure, expect the D1 verdict, expect the client isolated and the fleet outcome rate unchanged through the injection. Clear the injection, expect the client's backlog replayed with zero duplicates.
2. **R3 on D4.** Add a naive retrying caller, expect the retry budget to trip and first-attempt success to hold at baseline.
3. **R1 on D3.** Inject latency into a stage, expect the drain-aware restart of that stage's worker and latency back in band within one window. This needs the pipeline to gain restartable stage workers, the same extension a broker-backed stage will need.
4. **R5 after a stop.** Stop the generator mid-batch, restart it, expect reconciliation to skip completed correlation ids and the duplicate count to be exactly zero.

## Non-goals

- Diagnosis. The module trusts verdicts; if a verdict is wrong, the fix belongs in detection.
- Correctness repair (Family 7).
- Capacity planning and cost policy. Scale-out is a gated action here; how much capacity to buy is a business decision the module reports into but does not make.
