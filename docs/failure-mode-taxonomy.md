# Failure-Mode Taxonomy for Card Transaction Pipelines

**Status:** v0
**Author:** Pawan Pandey

## Purpose

Before a system can heal itself, its failure modes have to be named, classified, and mapped to detection signals and recovery actions. This document is that map for a typical card transaction pipeline: the path from a payment request arriving at a gateway, through authorization, to persistence and settlement.

Each entry follows the same schema:

| Field | Meaning |
|---|---|
| Description | What breaks and why |
| Detection signals | What the telemetry shows while it is happening |
| Blast radius | Who and what is affected if nothing intervenes |
| Conventional response | What the on-call human does today |
| Self-healing target | The automated behavior that should replace the human response |

The pattern catalog (`docs/patterns/`) will map each family below to a concrete detection and recovery implementation.

---

## Family 1: Node and instance failures

### 1.1 Hard node failure

- **Description:** A service instance dies outright: process crash, host loss, container eviction.
- **Detection signals:** Health check failures, connection refused errors from upstream callers, drop to zero in per-instance throughput.
- **Blast radius:** Transactions in flight on that node fail; overall capacity drops until traffic is redistributed.
- **Conventional response:** Orchestrator restarts the instance; on-call verifies traffic redistribution.
- **Self-healing target:** Instant re-route of new transactions to healthy instances, replay or safe retry of in-flight transactions where idempotency allows, capacity rebalanced before retry storms form.

### 1.2 Gray failure (partially degraded node)

- **Description:** The node is alive and passing health checks but degraded: responding slowly, timing out intermittently, or returning elevated error rates. More dangerous than hard failure because load balancers keep sending it traffic.
- **Detection signals:** Per-instance latency percentiles diverging from fleet median, rising timeout ratio on one instance while fleet average looks normal.
- **Blast radius:** A slice of all transactions experiences elevated latency or failures, often invisible in aggregate dashboards.
- **Conventional response:** Usually detected late, from customer complaints or merchant escalation; on-call manually drains the instance.
- **Self-healing target:** Outlier detection at per-instance granularity, automatic drain and replacement of the outlier, verification that fleet percentiles recover.

**Field notes:** two gray-failure shapes I have seen repeatedly on production container platforms. First, sibling pods running under the same image name but resolving it from different registry locations, so nominally identical replicas are actually running different builds; every health check passes while behavior quietly diverges. Second, traffic distribution failure: one pod receiving effectively all the traffic while its sibling sits idle, traced to a malfunctioning node underneath the quiet pod. Fleet-average dashboards miss both. The only thing that exposes them is comparing each instance against its siblings.

### 1.3 Traffic-layer and platform-migration failures

- **Description:** The application layer is healthy but nothing reaches it, or it cannot reach what it depends on. Two examples I have seen in production: a hardware load balancer's traffic engine becoming unresponsive, so card transactions stopped flowing while every application instance behind it reported healthy; and an application migrated between container platforms that could not connect to the message broker cluster in the new environment, because connectivity assumptions from the old platform did not carry over.
- **Detection signals:** Throughput dropping to zero while application health checks stay green; connection failures concentrated at one network hop; error signatures appearing immediately after a platform or infrastructure migration.
- **Blast radius:** Total for the affected path. These failures are costly because diagnosis starts in the wrong place: the application looks healthy, so responders debug the application while the real fault sits a layer below.
- **Conventional response:** Escalation across teams until someone checks the traffic layer. Detection is often slowed further when alerting is delivered by email rather than paging: an inbox is not a detection system, and minutes of delay during a card-processing halt are expensive.
- **Self-healing target:** End-to-end synthetic transactions that exercise the full path continuously, so "app healthy but path dead" is detected as fast as an app crash; migration-aware connectivity verification that runs before traffic cutover, not after; alert routing with latency guarantees for transaction-stopping conditions.

**Field note:** the gap between "every component reports healthy" and "transactions are actually flowing" is where the worst outages I have seen live.

---

## Family 2: Database and persistence failures

### 2.1 Query performance degradation

- **Description:** Previously fast queries slow down: plan regressions, missing index after schema change, table growth crossing a threshold, lock contention.
- **Detection signals:** Rising database time share of end-to-end transaction latency, slow-query log volume, growing connection pool wait time.
- **Blast radius:** Latency inflation across every transaction touching the affected path; under high concurrency, cascades into pool exhaustion (2.2).
- **Conventional response:** DBA or backend engineer profiles the slow query log and ships an optimization, hours to days later.
- **Self-healing target:** Detect the degradation from the latency decomposition, automatically shift read traffic where replicas allow, shed non-critical load, and page with the offending query already identified.

**Field notes:** I lived this family on a client-facing configuration service backed by a transactional database. The symptoms: certain forms loading noticeably slower than the rest of the application, screens intermittently failing with null-pointer exceptions, and batch jobs that normally finish in minutes hanging indefinitely and needing a rerun after the underlying fix. Diagnosis meant jumping between log analytics queries, metrics dashboards, and email alerts. A recurring obstacle was error surfacing: failures showed up as raw technical jargon instead of meaningful messages, so the first minutes of every investigation went to translating the error rather than locating the fault. Bad error surfacing extends every incident a system has.

### 2.2 Connection pool exhaustion

- **Description:** Slow queries or downstream stalls hold connections longer; the pool empties; new transactions queue and time out. A classic amplification failure: a small slowdown becomes a full outage.
- **Detection signals:** Pool utilization approaching limit, pool wait time rising, throughput falling while request arrival rate is unchanged.
- **Blast radius:** Total: healthy requests fail because they cannot obtain a connection.
- **Conventional response:** Restart services, raise pool limits under pressure, or wait out the stall.
- **Self-healing target:** Early back-pressure when pool utilization trends toward saturation, fast-fail with retry-after on non-critical paths, automatic isolation of the query family holding connections.

### 2.3 Replication lag and failover

- **Description:** Primary loss forces failover; replicas lag behind the primary, risking stale reads or lost writes during the transition.
- **Detection signals:** Replication lag metrics, write errors on primary, failover events from the database layer.
- **Blast radius:** Write unavailability during failover window; correctness risk for read-after-write flows.
- **Conventional response:** Managed failover plus manual verification of consistency-sensitive flows.
- **Self-healing target:** Automated failover with transaction-pipeline awareness: consistency-sensitive flows are paused and buffered for the failover window instead of served stale.

---

## Family 3: Latency degradation and cascades

### 3.1 Latency inflation under load

- **Description:** Rising concurrency pushes some component past its knee point; p99 latency inflates first, then p50 follows.
- **Detection signals:** Percentile divergence (p99 rising while p50 flat is the early warning), queue depth growth, thread pool saturation.
- **Blast radius:** In payments, slow has a direct cost: authorizations that exceed client timeouts turn into declines, retries, and abandoned purchases.
- **Conventional response:** Scale out when someone notices; sometimes after the timeout storm has already started.
- **Self-healing target:** Scale-out triggered by percentile trend rather than average utilization, plus admission control that keeps accepted transactions fast instead of letting all transactions become slow.

### 3.2 Retry storms and cascade amplification

- **Description:** Clients and internal services retry failed or slow calls; retry volume multiplies load on the already-struggling component, deepening the failure.
- **Detection signals:** Request arrival rate rising while unique-transaction rate is flat; retry ratio per caller.
- **Blast radius:** Converts a partial degradation into a full outage, and lengthens recovery because the component cannot come back under amplified load.
- **Conventional response:** Emergency rate limiting or client-side hotfixes during the incident.
- **Self-healing target:** Retry budgets enforced pipeline-wide, exponential backoff with jitter as a policy default, and load shedding that distinguishes first-attempt traffic from retry traffic.

---

## Family 4: Traffic surge events

### 4.1 Anticipated surge (peak shopping events)

- **Description:** Known national demand peaks: holiday shopping, promotional events. Load may be 10x baseline but is broadly predictable.
- **Detection signals:** Calendar plus arrival rate trending against forecast.
- **Blast radius:** Whole pipeline; failures here are highest-cost because transaction value peaks at the same moment.
- **Conventional response:** Pre-scaled capacity from load testing forecasts, war rooms during the event.
- **Self-healing target:** Elastic scaling policies validated against surge models in advance, so scaling during the event is automatic and the war room mostly just watches.

### 4.2 Unanticipated surge

- **Description:** Demand spikes with no calendar warning: viral events, upstream outage recovery dumping queued traffic, misbehaving batch jobs.
- **Detection signals:** Arrival rate slope anomaly against rolling baseline.
- **Blast radius:** Same as 4.1 but with zero preparation time; most likely to trigger Families 2 and 3 as secondary failures.
- **Conventional response:** Reactive scaling and load shedding under pressure.
- **Self-healing target:** Slope-based early detection buys the scaling latency window; graceful degradation policies (defer non-critical work, protect authorization path) are pre-codified rather than improvised.

---

## Family 5: Deployment and change failures

### 5.1 Bad deployment

- **Description:** A release introduces a functional or performance regression into the live pipeline. Change remains the leading cause of self-inflicted outages. In practice this family splits into two distinct modes: the release that fails to start, and the release that starts but behaves badly.
- **Detection signals:** For fail-to-start: pods that never come up or terminate immediately after starting, crash loops, readiness and liveness probe failures, image pull failures, secret-management init containers failing to initialize, and database connectivity failures on startup. These signatures are visible within the first minutes of a rollout and are almost entirely mechanical to detect. For starts-but-misbehaves: error rate or latency divergence between new and old version cohorts during rollout.
- **Blast radius:** Scales with rollout percentage at detection time. Fail-to-start modes also silently reduce fleet capacity if the orchestrator keeps old instances serving.
- **Conventional response:** In many production environments today, detection is a human watching the deployment happen in real time, backed by email alerts. That works only when someone is watching, and it does not scale past a handful of deployments.
- **Self-healing target:** The fail-to-start signatures should gate rollouts automatically: a deployment whose pods crash-loop or fail probes halts and rolls back itself, no observer required. For behavioral regressions, canary analysis comparing version cohorts automatically, halt and rollback on divergence without waiting for a human decision, deployment marked failed with the diverging metrics attached.

**Field note:** the complete list of ways a rollout fails to start (image pull, probe failures, secret initialization, database connectivity, immediate termination) is small, stable, and known in advance. There is no reason any of them should ever require a human watching a terminal.

### 5.2 Configuration and dependency drift

- **Description:** Non-code changes (feature flags, connection settings, certificate expiry, dependency version drift) alter behavior outside the deployment pipeline's guardrails.
- **Detection signals:** Behavior change without a correlated deployment event; certificate expiry horizons; config change audit stream.
- **Blast radius:** Often single-subsystem but frequently mysterious, which lengthens diagnosis.
- **Conventional response:** Long diagnosis, because "nothing was deployed."
- **Self-healing target:** Every change, not just code, enters the same observability stream; automated correlation between config events and metric shifts; auto-revert policies for flagged config classes.

---

## Family 6: External dependency failures

### 6.1 Downstream processor or network degradation

- **Description:** An external dependency (card network, issuing bank endpoint, third-party service) slows or fails. The pipeline cannot fix the dependency, only its own response to it.
- **Detection signals:** Per-dependency latency and error tracking, timeout ratio by destination.
- **Blast radius:** All transactions routed through the affected dependency.
- **Conventional response:** Wait, escalate to the vendor, manually re-route where alternate paths exist.
- **Self-healing target:** Circuit breakers with per-dependency policies, automatic re-route to alternate paths where the business relationship allows, queued store-and-forward for flows that tolerate deferred completion.

### 6.2 Platform and infrastructure provider outages

- **Description:** The dependency that fails is not a peer service but the ground the system stands on: a cloud provider region outage, or, in the hybrid architectures common across the financial industry, the on-premises and mainframe systems that cloud services still depend on for core processing.
- **Detection signals:** Simultaneous failure across otherwise unrelated services; provider status feeds; connectivity loss to on-premises endpoints.
- **Blast radius:** In incidents I have been part of, this family produces the longest outages by far: a cloud provider outage that shut services down for close to a day, on-premises server failures freezing dependent systems for hours, complex incidents dragging past half a working day. These are rare, but their length is what makes them expensive.
- **Conventional response:** Wait for the provider, or manually fail over if a secondary path exists and has actually been rehearsed.
- **Self-healing target:** Honest degraded modes designed in advance: queued store-and-forward so transactions survive the outage window, clear automatic demarcation of which capabilities remain available, and rehearsed automated failover for the paths where a secondary exists. A hybrid cloud-plus-mainframe reality also means self-healing designs cannot assume cloud-native primitives exist everywhere; patterns must degrade gracefully to the constraints of the oldest system in the chain.

**Field note:** during peak processing events, the visible symptom is heavy throttling and bursts of 5xx responses that succeed when retried later. When "retry until it works" is the de facto recovery mechanism, the system is depending on client patience as its resilience strategy. Retry-on-5xx is a load amplifier during exactly the window when the system can least afford it; the self-healing designs in Families 3 and 4 exist to replace it.

**Field note:** one more dependency lesson from production: when an authentication service handled by another team degraded, login screens froze for several minutes with no meaningful alert before eventually timing out. A dependency without an aggressive timeout and a user-facing degraded mode exports its failure to every system in front of it, in the least diagnosable form possible.

---

## Family 7: Silent data-correctness failures

The families above are availability failures: the system stops or slows. This family is different and, in financial systems, at least as dangerous: the pipeline runs at full health while producing wrong output. No latency alarm will ever fire.

### 7.1 Logic defects surfacing as data drift

- **Description:** Processing logic contains a defect that only manifests under specific data conditions, so outputs are wrong while every operational metric is green. Real examples I have dealt with: date arithmetic that fails to account for leap years, producing different results for affected periods; queries that include records flagged as deleted (soft-delete flag set, record still counted), silently inflating results; and duplicate records passing through processing in some instances, double-counting transactions.
- **Detection signals:** Almost none in operational telemetry, which is the defining problem. The failure is only visible in the data itself: reconciliation mismatches between upstream and downstream totals, invariant violations (deleted records contributing to counts, duplicate keys in output), period-over-period anomalies aligned with calendar edge cases.
- **Blast radius:** Downstream reports and files delivered to clients and partner institutions carry wrong numbers. The damage is to correctness and trust rather than uptime, and it compounds silently until an external party notices.
- **Conventional response:** The client notices. Tickets arrive from clients or vendors reporting that the numbers in delivered files are off, and investigation works backward from the complaint. This is the worst possible detection path: the customer is the monitoring system.
- **Self-healing target:** Reconciliation as a first-class pipeline stage: outputs are validated against source totals and known invariants (no soft-deleted records, no duplicate keys, calendar edge cases covered by test vectors) before delivery, and a failed validation quarantines the artifact instead of shipping it. The self-healing goal here is refusing to publish data that fails its invariants, rather than trying to repair it automatically.

**Field note:** availability engineering and correctness engineering need each other. A system that never goes down but ships wrong numbers to clients is failing just as surely as one that crashes, and its failures take longer to surface.

---

## A production-derived detection baseline

The detection module of the reference implementation starts from the metric set I have actually used to monitor card processing systems in production:

| Metric | What it anchors |
|---|---|
| HTTP response classes (2xx / 4xx / 5xx) by service | Error rate and its shape; 4xx vs 5xx separates client problems from system problems |
| Transactions processed per client, success vs failure | The business outcome. This is the metric that means "the system is actually working"; everything else is a proxy |
| Throughput | Arrival and completion rates; the input to surge detection (Family 4) |
| Message broker consumer lag | Early warning that processing is falling behind arrival, often before latency metrics move |
| CPU and memory consumption per service | Saturation trending for scale-out triggers and pool exhaustion prediction |

Two design rules follow from using this set day to day. First, the per-client success/failure view is the outcome anchor: fleet-level averages hid every gray failure described in Family 1, but a single client's failure rate diverging never lied. Second, consumer lag is the most underrated early signal in the set; it moves before user-visible latency does, which is exactly the window self-healing needs.

## How this taxonomy drives the framework

Writing the families down left me with four observations:

1. **Detection is mostly generic; recovery is mostly specific.** The detection signals above reduce to a small set of primitives (percentile divergence, arrival slope anomaly, cohort comparison, saturation trending, reconciliation checks). Recovery actions are where domain policy lives. The framework therefore separates a reusable detection layer from a policy-driven recovery layer.
2. **The costly failures are the amplifying ones.** Gray failures, pool exhaustion, and retry storms all share a shape: a small defect multiplied by the system's own behavior. Self-healing design is less about reacting to big failures and more about breaking amplification loops early.
3. **Component health is not system health.** The worst outages present as "everything reports healthy, nothing is flowing" (Family 1.3) or "everything is flowing, the numbers are wrong" (Family 7). Detection must be anchored to the outcomes that matter (transactions completing, outputs reconciling), not to component status.
4. **The detection channel is part of the system.** An alert that lands in an email inbox, or a rollout watched by human eyes, has human reaction time built into every incident. Self-healing begins with removing the human from the detection loop, before any recovery automation is added.

The pattern catalog will take each family and define: the detection primitive and its threshold policy, the automated decision, the recovery action, and a failure injection scenario in the reference implementation that demonstrates the loop closing without human intervention.

## References

- Beyer, Jones, Petoff, Murphy (eds.), *Site Reliability Engineering: How Google Runs Production Systems*, O'Reilly, 2016. Especially the chapters on monitoring distributed systems and managing incidents.
- Beyer, Murphy, Rensin, Kawahara, Thorne (eds.), *The Site Reliability Workbook*, O'Reilly, 2018. Alerting on SLOs and canarying releases.
- Nygard, *Release It! Design and Deploy Production-Ready Software*, 2nd ed., Pragmatic Bookshelf, 2018. Stability patterns including circuit breakers, bulkheads, and timeouts.
- Federal Reserve Financial Services, FedNow Service documentation, frbservices.org. Availability expectations for instant payment infrastructure.
- CISA, Financial Services Sector profile, cisa.gov. Critical infrastructure designation of the financial services sector.
- Public incident postmortems from major cloud and payments providers (AWS Service Health Dashboard post-event summaries, Cloudflare and Google Cloud incident reports), which document at industry scale the same failure families catalogued here.
