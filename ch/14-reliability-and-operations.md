# 14 — Reliability, observability and shipping safely

Designing a system is half the job; keeping it running while it changes every day is the other
half. This chapter gives you the vocabulary for that half: how availability is measured and
promised, how redundancy and backups protect you, how you see what a running system is doing,
and how teams deploy dozens of times a day without breaking things. In design reviews these are
the questions that come after "how does it work?" — "how do we know it works, and what happens
when it doesn't?"

## 14.1 Availability and the "nines"

**Availability** is the fraction of time (or of requests) a system is doing its job correctly.
It is usually quoted in "nines": 99.9% is "three nines".

Downtime allowed, using a 365-day year (8,760 hours) and an average month of 730 hours:

| Availability | Downtime per year | Downtime per month | Downtime per day |
|---|---|---|---|
| 90% ("one nine") | 36.5 days | 73 hours | 2.4 hours |
| 99% | 3.65 days (87.6 h) | 7.3 hours | 14.4 minutes |
| 99.5% | 1.83 days (43.8 h) | 3.65 hours | 7.2 minutes |
| 99.9% | 8.76 hours | 43.8 minutes | 1.44 minutes |
| 99.95% | 4.38 hours | 21.9 minutes | 43.2 seconds |
| 99.99% | 52.6 minutes | 4.38 minutes | 8.6 seconds |
| 99.999% | 5.26 minutes | 26.3 seconds | 0.86 seconds |

Each extra nine cuts allowed downtime by 10x, and usually costs far more than 10x in
engineering: at four nines (about 4 minutes a month), a human cannot even be paged and log in
before the budget is gone, so detection and recovery must be automatic.

Two ways to measure:

- **Time-based**: fraction of minutes the service was "up" (needs a definition of up).
- **Request-based**: successful requests / total valid requests. More common for services
  because it reflects real user impact: a 10-minute outage at 3 a.m. hurts less than one at
  peak.

## 14.2 Availability math: serial and parallel

### Serial (all must work)

If a request needs components A **and** B **and** C, and their failures are independent:

```text
  A(total) = A1 x A2 x A3

  client -> [LB 99.99%] -> [app 99.9%] -> [DB 99.9%]
  total = 0.9999 x 0.999 x 0.999 ≈ 0.9979  -> about 99.8%
```

Availability only goes **down** as you add serial dependencies. Ten serial services at 99.9%
each give about 0.999^10 ≈ 99.0%. This is a quantitative argument against long synchronous call
chains (chapter 13).

### Parallel (any one is enough)

If a request succeeds when **any** of N redundant copies works, and failures are independent:

```text
  A(total) = 1 - (1 - A)^N

  two app servers at 99% each:   1 - (0.01)^2 = 1 - 0.0001 = 99.99%
  three at 99%:                  1 - (0.01)^3 = 99.9999%
```

Redundancy is how you build reliable systems from unreliable parts.

The big caveat: **independence**. Two servers in the same rack share a power supply and a
switch; two replicas running the same buggy code fail together on the same bad input; two
regions configured by the same broken deploy fail together. **Correlated failures** are why real
systems never reach the numbers the formula promises. Redundancy only helps against failures
that do not hit all copies at once, which is why you spread copies across zones and regions and
roll out changes gradually.

> **Say it like this:** "Serial dependencies multiply availabilities, so each one we add makes
> us less available; redundant copies give 1 minus the product of failure probabilities — but
> only if the copies fail independently, so we spread them across zones and never deploy to all
> of them at once."

## 14.3 SLI, SLO, SLA and error budgets

Three terms that are often confused:

- **SLI (service level indicator)**: a measured number. "Fraction of HTTP requests in the last
  5 minutes that returned non-5xx in under 300 ms." "p99 latency of checkout."
- **SLO (service level objective)**: an internal target for an SLI over a time window. "99.9%
  of checkout requests succeed in under 300 ms, measured over 30 days."
- **SLA (service level agreement)**: a contract with customers, with consequences (usually
  service credits or refunds) if it is broken. "If monthly availability is below 99.9%, the
  customer gets 10% credit."

```text
   SLI  = what we measure            (e.g. 99.95% good requests this month)
   SLO  = what we aim for            (99.9% -- internal, stricter than SLA)
   SLA  = what we promise legally    (99.5% -- with penalties)

   SLA <= SLO <= what we usually achieve      (keep a safety margin at each step)
```

Good SLIs measure what users experience: success rate and latency at the edge (the load
balancer or client), not "CPU below 80%".

### Error budgets

If the SLO is 99.9% over 30 days, you are **allowed** 0.1% bad requests: that is the **error
budget**. With 100 million requests a month, the budget is 100,000 failed requests (or roughly
43 minutes of full downtime).

The budget turns a vague argument ("ship faster" vs "be careful") into a rule both sides accept:

- Budget remaining: ship features, run experiments, take reasonable risks.
- Budget burned: slow down; prioritize reliability work, freeze risky launches until it
  recovers.

It also means **100% is the wrong target**. Users cannot tell 99.99% from 100% (their own phone
and network fail more often), and chasing 100% stops all change. Pick the SLO users actually
need.

## 14.4 Redundancy patterns

**N+1** (and N+2): if you need N servers to carry peak load, run N+1 so losing any one still
leaves enough. With N+2 you can lose one during a planned maintenance of another. Example: peak
needs 8 servers -> run 9 or 10.

**Active-passive** (also called primary-standby or hot/warm standby): one copy serves traffic;
another stays ready and takes over on failure (failover). Simple and avoids conflicts, but the
standby is idle capacity, failover takes time, and an untested standby might not actually work.

**Active-active**: all copies serve traffic at the same time. Uses all capacity and failover is
just "stop sending to the dead one", but each copy must have headroom to absorb the others' load,
and if they accept writes, you must handle conflicting writes (chapter 07).

```text
  active-passive                        active-active
  traffic --> [ A: active ]             traffic --+--> [ A ] 50%
              [ B: standby ] (idle)               +--> [ B ] 50%
  A dies -> promote B (seconds-minutes) A dies -> B takes 100% (must have headroom)
```

Failure domains, from small to large:

| Level | Protects against | Typical cost / complexity |
|---|---|---|
| Multiple servers | One machine dying | Low |
| **Multi-AZ** | Loss of one data center (power, cooling, network) | Low-medium; AZs in a region are a few ms apart, so synchronous replication is practical |
| **Multi-region** | Loss of a whole region, large-scale network or provider issues | High: tens to 100+ ms between regions, so replication is usually async; data residency rules; much more complex |

An **availability zone (AZ)** is one or more data centers with independent power and networking,
close enough to others in the same region for low latency. Running across at least 2-3 AZs is
standard for anything important. Multi-region is for systems that truly must survive a regional
outage or need low latency worldwide.

## 14.5 RPO, RTO, backups vs replication, disaster recovery

Two numbers define what "recovering from a disaster" means:

- **RPO (recovery point objective)**: how much **data** you can afford to lose, measured in
  time. "RPO = 5 minutes" means after a disaster you may lose at most the last 5 minutes of
  writes.
- **RTO (recovery time objective)**: how long the service may be **down** before it is restored.
  "RTO = 1 hour."

```text
        last good copy               disaster                    service restored
  --------------|----------------------------X-------------------------------|------->
                |<---------- RPO ---------->|<------------ RTO ------------->|
                 writes in this span may be   time users are without service
                 lost
```

### Replication is not a backup

**Replication** continuously copies data to other machines (chapter 07). It protects against
hardware failure: if the primary disk dies, the replica has the data.

But replication copies **everything**, including mistakes. A bug that corrupts rows, a
`DELETE FROM orders` without a `WHERE`, ransomware encrypting the data — all are faithfully
replicated to every replica within milliseconds.

A **backup** is a copy from a **point in time**, kept separately, that you can restore to go
back to before the mistake. Good backup practice:

- Full snapshots plus continuous **write-ahead log (WAL) / binlog archiving**, which allows
  **point-in-time recovery (PITR)**: "restore the database as it was at 14:02:00, one minute
  before the bad migration."
- Store backups in a different account/region, ideally immutable (cannot be deleted by the same
  credentials that run production).
- **Test restores regularly.** A backup that has never been restored is a hope, not a backup.
  Measure how long a restore takes; that is part of your RTO.

> **Say it like this:** "Replication protects against losing a machine; backups protect against
> losing the data itself, because replication faithfully copies a bad delete everywhere. We need
> both, plus regularly tested restores to know our real RTO."

### Disaster recovery tiers

A common framing for multi-region DR, from cheap/slow to expensive/fast:

| Tier | Second region contains | RPO | RTO | Cost |
|---|---|---|---|---|
| Backup and restore | Only backups | Hours (since last backup) | Hours to a day | Lowest |
| Pilot light | Data replicated; core infra defined but scaled to zero or minimal | Minutes | Tens of minutes to hours (scale up) | Low |
| Warm standby | A scaled-down but running copy of the full system | Seconds to minutes | Minutes | Medium |
| Multi-site active-active | Full capacity serving live traffic | Near zero (if sync) to seconds | Near zero | Highest |

Choose by business impact: an internal reporting tool might be fine with backup-and-restore; a
payment system probably needs warm standby or active-active.

## 14.6 Observability: metrics, logs, traces

**Monitoring** asks known questions: "is the error rate above 1%?" **Observability** is the
property of a system that lets you answer **new** questions about its behavior from the outside,
like "why are only Android users in Brazil seeing slow checkouts since 14:00?" The three main
data types (often called the three pillars):

| | Metrics | Logs | Traces |
|---|---|---|---|
| What | Numbers aggregated over time | Timestamped records of discrete events | The path and timing of one request across services |
| Example | `http_requests_total{status="500"}` | `{"level":"error","order_id":42,"msg":"card declined"}` | checkout took 1.2 s: 900 ms in pricing service |
| Cost | Cheap, fixed size per time series | Expensive at volume | Medium; usually sampled |
| Best for | Dashboards, alerts, trends | Details of a specific event | Finding where time went in a request |

### Metrics

A **metric** is a named number measured over time, often with **labels** (dimensions) such as
`service`, `endpoint`, `status`. Three basic types:

- **Counter**: only goes up (resets on restart). Total requests, total errors, bytes sent. You
  usually graph its **rate** (per second).
- **Gauge**: goes up and down. Current memory used, queue length, active connections,
  temperature.
- **Histogram**: counts observations into buckets (e.g. latency <10 ms, <50 ms, <100 ms, ...),
  so you can compute percentiles later, including across many servers.

Why histograms matter: averages hide pain. If 99 requests take 10 ms and 1 takes 5 seconds, the
average is about 60 ms and looks fine, but 1% of users wait 5 seconds. Use **percentiles**: p50
(median), p95, p99. And you **cannot average percentiles** across servers (the mean of each
server's p99 is not the fleet's p99); aggregate histogram buckets instead, then compute the
percentile.

**Cardinality** warning: each unique combination of label values is a separate time series. A
label like `user_id` with 10 million values creates 10 million series and can take down the
metrics system. Put high-cardinality details in logs and traces, not metric labels.

Three well-known checklists for which metrics to collect:

- **RED** (for request-driven services): **R**ate (requests/sec), **E**rrors (failed
  requests/sec), **D**uration (latency distribution).
- **USE** (for resources: CPU, disk, memory, network, thread pools): **U**tilization (% time
  busy), **S**aturation (queued work it cannot serve yet, e.g. run-queue length), **E**rrors.
- **Four golden signals** (a common SRE framing): **latency**, **traffic**, **errors**,
  **saturation**. Measure latency of successful and failed requests separately (fast errors can
  make latency look great).

### Logs

**Logs** are records of events. Make them **structured** — key-value data, usually JSON — so a
machine can filter and aggregate them:

```text
 unstructured:  "ERROR payment failed for order 42 user 7 after 3 retries"
 structured:    {"ts":"2026-10-10T14:02:11Z","level":"error","service":"payment",
                 "event":"charge_failed","order_id":42,"user_id":7,"retries":3,
                 "trace_id":"4bf92f3577b34da6a3ce929d0e0e4736"}
```

Now "all charge failures for user 7 in the last hour" is a query, not a grep with regexes.

A **correlation ID** (or request ID) is a unique ID generated at the edge for each request and
passed to every service and included in every log line. Given one user complaint, you can pull
every log line across all services for that one request. In practice the trace ID often plays
this role.

Practical rules: log levels (DEBUG, INFO, WARN, ERROR) with DEBUG off in production by default;
never log passwords, tokens, or full card numbers, and be careful with personal data (chapter 15);
logs at scale are expensive, so sample noisy success logs and keep errors.

### Traces

A **trace** records one request's journey through the system. It is a tree of **spans**: each
span is one unit of work (an HTTP call, a DB query) with a start time, duration, the service
name, a span ID, and the ID of its parent span.

```text
 trace 4bf92f...   total 1,240 ms
 |-- gateway  GET /checkout ............................................. 1240 ms
     |-- cart-svc  getCart ............ 80 ms
     |-- pricing-svc  computeTotal ..................................... 900 ms   <-- here
     |    |-- db  SELECT promotions ........................... 850 ms   <-- slow query
     |-- payment-svc  authorize ....................... 200 ms
```

**Trace context propagation**: for the tree to connect, each outgoing call must carry the trace
ID and the current span ID. The standard way is the W3C Trace Context `traceparent` HTTP header
(and the equivalent in message headers for Kafka), handled automatically by libraries like
OpenTelemetry.

```text
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             |  |                                |                |
             |  trace id (16 bytes, hex)         parent span id   flags (01 = sampled)
             version                             (8 bytes, hex)
```

Because tracing every request is expensive, systems **sample**: e.g. keep 1% of traces, plus all
traces that had errors or were very slow (deciding after the trace finishes is called
**tail-based sampling**).

> **Say it like this:** "Metrics tell us something is wrong, traces tell us where, and logs tell
> us why. I'd emit RED metrics per endpoint, structured logs with the trace ID on every line, and
> propagate traceparent through HTTP and Kafka headers."

## 14.7 Dashboards and alerting

**Dashboards** are for humans investigating. A good service dashboard starts at the top with
the user-facing SLIs (RED), then drills down to resources (USE) and dependencies.

**Alerts** wake people up, so they must be worth it. The key principle: **alert on symptoms, not
causes**.

| Alert on (symptom: users are hurting) | Do not page on (cause: might not matter) |
|---|---|
| Checkout error rate > 2% for 5 minutes | CPU at 90% on one host |
| p99 latency above SLO for 10 minutes | One replica restarted |
| Error budget burning 10x faster than sustainable | Disk 70% full (make it a ticket, not a page) |

High CPU might be fine (the system is doing its job efficiently); a dead replica might be fully
covered by redundancy. If users are not affected, it can be a ticket or a dashboard item for
working hours. Causes are for debugging; symptoms are for paging.

**Burn-rate alerts** are a good SLO-based style: page if the error budget is being consumed fast
enough to exhaust it within a short time (e.g. burning at 14x the sustainable rate over the last
hour), and open a ticket for slow burns.

**Alert fatigue**: when people get many alerts that need no action, they start ignoring all of
them, including the real ones. Rules of thumb:

- Every page must be **actionable** and **urgent**. If the response is "ignore it", delete or
  downgrade the alert.
- Each alert links to a **runbook**: what it means, how to check, how to mitigate.
- Review alert noise regularly (pages per on-call shift is a metric worth tracking).

## 14.8 Deploying safely

Most outages are caused by changes: deploys, config pushes, migrations. Safe-deploy techniques
limit how many users a bad change can hurt and how fast you can undo it.

### Rolling deployment

Replace instances a few at a time: drain one batch from the load balancer (chapter 12), upgrade,
check health, move on.

```text
 v1 v1 v1 v1 v1 v1 -> v2 v2 v1 v1 v1 v1 -> v2 v2 v2 v2 v1 v1 -> v2 v2 v2 v2 v2 v2
```

Cheap (no extra fleet). During the rollout, v1 and v2 serve traffic **at the same time**, so
they must be compatible (same API contracts, same DB schema — see 14.9). Rollback means rolling
forward to v1 again, which takes as long as the rollout.

### Blue-green deployment

Run two full environments. **Blue** serves live traffic (v1). Deploy v2 to **green**, test it,
then switch the load balancer (or DNS) to green all at once. Keep blue around for quick rollback.

```text
 before switch                              after switch
 router ---> [ blue: v1 ]  (live)            [ blue: v1 ]  (idle, kept for rollback)
             [ green: v2 ] (being tested)    router ---> [ green: v2 ] (live)
```

Instant switch and instant rollback; but double the infrastructure during the release, and a
bad change hits 100% of users at once after the switch. The database is usually shared, so
schema changes still need the expand-contract approach.

### Canary releases

Send a small slice of real traffic (say 1%) to the new version, compare its metrics (errors,
latency, business metrics like conversion) with the old version, and increase gradually: 1% ->
5% -> 25% -> 100%. Automate the comparison and auto-rollback when the canary is worse. Named
after canaries miners carried to detect gas early.

The best default for large services: a bad change hurts a small fraction of users for a few
minutes.

### Feature flags

A **feature flag** is a runtime switch, read from a config service, that turns code paths on or
off without deploying: `if (flags.isOn("new_checkout", user)) { ... }`. This separates
**deploy** (code is on servers) from **release** (users see the feature). Flags allow:

- Gradual rollouts by percentage, region, or user group.
- A **kill switch**: turn a broken feature off in seconds, no rollback needed.
- Merging unfinished code to the main branch safely (hidden behind an off flag).

Cost: every flag is a branch in the code; old flags pile up into confusing technical debt.
Remove them once the feature is fully launched. Flag-system outages need a safe default value.

### Dark launches and shadow traffic

- **Dark launch**: deploy and run a new feature in production without showing it to users. For
  example the new backend computes recommendations for every request but the UI does not display
  them yet. You learn about performance and errors under real load.
- **Shadow traffic** (traffic mirroring): copy real requests to the new version in parallel; the
  user only ever gets the old version's response; you compare responses and performance offline.
  Excellent for rewrites and migrations. Careful: shadowed requests must not cause side effects
  (charging cards twice, sending emails); point shadows at read-only paths or stubbed
  dependencies.

### Rollback

The most important deploy question is "how fast can we undo it?" Rules:

- Every deploy must be reversible quickly; rehearse rollbacks.
- **Roll back first, debug later.** When a deploy correlates with an incident, revert and then
  investigate.
- Watch out for **irreversible changes**: data migrations that drop columns, messages written in
  a new format old code cannot read. These need the expand-contract discipline.

> **Say it like this:** "I'd ship behind a feature flag, deploy with a canary at 1% and an
> automated comparison against the baseline, then ramp up. The flag is our kill switch, and
> schema changes go through expand-contract so old and new code can run side by side."

## 14.9 Database migrations safely: expand-contract

During a rolling deploy, old and new code run at the same time against the same database. A
migration that renames a column breaks whichever version expects the other name. The solution
is **expand-contract** (also called **parallel change**): split a breaking change into several
backward-compatible steps.

Example: rename `users.fullname` to `users.display_name`.

```text
 1. EXPAND    add new column display_name (nullable). Old code ignores it.
 2. WRITE BOTH deploy code that writes both columns; it still reads fullname.
 3. BACKFILL  copy fullname -> display_name for existing rows, in small batches
              (avoid locking a huge table / flooding replication).
 4. READ NEW  deploy code that reads display_name (still writes both, so rollback is safe).
 5. STOP OLD  deploy code that no longer writes fullname.
 6. CONTRACT  drop column fullname -- only once no running code uses it.
```

At every step, the currently running code and the previous version both work, so any single
deploy can be rolled back. It is slower than one big migration, which is the price of zero
downtime. Also watch the mechanics: on large tables, adding indexes or changing column types
can lock the table; use online schema change tools or the database's concurrent options.

## 14.10 Capacity planning and autoscaling

**Capacity planning** means making sure you have enough resources for expected load plus a
margin, before you need them. A simple loop:

1. Measure what one instance can handle at acceptable latency (load test). Say 1,500 req/s at
   p99 < 200 ms.
2. Forecast peak (chapter 04 estimation): e.g. 30,000 req/s at peak next quarter.
3. Add headroom for failures and spikes: survive losing one AZ out of three, plus ~30% margin.

```text
  instances for peak = 30,000 / 1,500 = 20
  survive loss of 1 of 3 AZs -> remaining 2 AZs must carry everything: 20 / (2/3) = 30
  plus ~30% spike headroom -> roughly 39-40 instances, spread across 3 AZs
```

**Autoscaling** adds or removes instances automatically based on a metric: CPU utilization,
requests per instance, queue length (for workers). Things to know:

- It **reacts with a delay**: detect (a minute) + start a new instance (seconds for containers,
  minutes for VMs, plus warm-up of caches/JIT). It cannot save you from a spike that arrives in
  10 seconds; keep baseline headroom or pre-scale before known events.
- Scale on a metric that tracks load: queue length or requests per instance is often better
  than CPU for I/O-bound services.
- Use **cooldowns** to avoid flapping (scale out fast, scale in slowly).
- Set **max limits** — a bug or attack could otherwise scale you into a huge bill — and remember
  dependencies: doubling app servers can double connections to the database and overload it.
- **Scheduled scaling** for predictable daily patterns.

## 14.11 Chaos engineering

**Chaos engineering** is deliberately injecting failures into a system to verify it handles them
the way you believe it does — before a real failure tests it for you. Examples: kill random
instances, add 500 ms latency to a dependency, drop a network link, make a zone unavailable, fill
a disk.

The method:

1. Define **steady state** with metrics (e.g. checkout success rate).
2. Form a hypothesis: "if one cache node dies, success rate stays above 99.9%."
3. Inject the failure, starting small (staging, then a small production slice), with a clear
   abort switch.
4. Compare. If the hypothesis was wrong, you found a weakness on your schedule instead of at
   3 a.m.

**Game days** are planned exercises where a team practices failure scenarios together, including
the human parts: was the alert fired, did the runbook work, did failover actually happen?

## 14.12 Incidents, postmortems and on-call

An **incident** is an unplanned event that degrades service. A typical response flow:

```text
 detect (alert or user report)
   -> triage: how bad? assign severity (SEV1 = major outage ... SEV4 = minor)
   -> assemble: incident commander + responders + communications
   -> MITIGATE first (rollback, failover, disable flag, shed load) -- stop the bleeding
   -> communicate: status page, stakeholders, regular updates
   -> resolve: service healthy, monitoring confirms
   -> postmortem: understand and prevent recurrence
```

The key mindset: **mitigate before you fully understand**. Rolling back a suspicious deploy in 2
minutes beats finding the exact bug in 2 hours while users suffer.

**On-call vocabulary**:

| Term | Meaning |
|---|---|
| On-call / pager | The engineer responsible for responding to alerts during a shift |
| Primary / secondary | First responder, and backup if the primary does not acknowledge |
| Escalation policy | Who gets paged next and when |
| Acknowledge (ack) | "I've seen it and I'm on it" — stops further paging |
| Incident commander (IC) | Coordinates the response; does not necessarily debug |
| Runbook / playbook | Step-by-step instructions for a known alert or scenario |
| Severity (SEV) | Impact level that decides urgency and who gets involved |
| MTTD / MTTR | Mean time to detect / mean time to recover (or repair) |
| MTBF | Mean time between failures |
| Status page | Public or internal page communicating current incidents |

A useful relationship: availability ≈ MTBF / (MTBF + MTTR). You can improve availability either
by failing less often or by recovering faster — and recovering faster is often much cheaper.

### Blameless postmortems

After a significant incident, the team writes a **postmortem** (incident review): timeline,
impact, root causes and contributing factors, what went well, what went badly, and **action
items with owners**.

**Blameless** means the review focuses on how the system and process allowed the failure, not on
which person made the mistake. The reasoning: if an engineer could delete production with one
mistyped command, the problem is the system that allowed it. Blaming people makes them hide
information, and then the organization stops learning. Good action items change systems
("require a confirmation and a dry run for bulk deletes"), not people ("be more careful").

> **Common mistake:** Postmortems that end with a single "root cause". Real incidents usually
> need several contributing factors at once: a bug, plus a missing alert, plus a retry storm,
> plus a runbook that was out of date. Fix several of them.

## 14.13 From "it works on my machine" to CI/CD

The classic problem: code works on a developer laptop but fails in production because of
different dependency versions, configuration, OS, or data. And manual deploys done by a person
following a wiki page are slow and error-prone.

The fixes:

- **Containers** (chapter 03) package the app with its exact dependencies, so the same image
  runs on a laptop, in CI, and in production.
- **Configuration as environment-specific input** (environment variables, config service), not
  baked into code; secrets from a secrets manager (chapter 15).
- **Infrastructure as code**: servers, networks and load balancers defined in version-controlled
  files, reviewed like code.
- **CI/CD pipeline**: every change goes through the same automated path.

```text
 developer push
   |
   v
 CI (continuous integration) -- on every commit / merge request
   build -> unit tests -> static analysis / lint -> security scan of dependencies
   -> build container image (tagged with commit SHA) -> integration tests
   |
   v
 artifact registry (immutable image: the SAME bytes go to every environment)
   |
   v
 CD (continuous delivery / deployment)
   deploy to staging -> smoke tests / end-to-end tests
   -> production canary 1% -> automated metric comparison -> ramp to 100%
   -> (auto-rollback if SLIs degrade)
```

- **Continuous integration (CI)**: merge small changes to the main branch often, with automated
  build and tests on every change, so integration problems surface within minutes.
- **Continuous delivery**: every change that passes the pipeline is releasable; a human clicks
  "deploy to production".
- **Continuous deployment**: every passing change goes to production automatically.

The key idea is "build once, deploy many": the exact artifact tested in staging is the one that
reaches production, configured differently per environment. Small, frequent deploys are safer
than big, rare ones: each carries less change, so problems are easier to find and revert.

## 14.14 Recap

- Each nine cuts allowed downtime 10x: 99.9% ≈ 8.76 h/year (43.8 min/month), 99.99% ≈ 52.6
  min/year.
- Serial dependencies multiply availabilities (always down); parallel redundancy gives
  1 - (1-A)^N, but only for independent failures.
- SLI = measurement, SLO = internal target, SLA = contract with penalties; error budget = 1 - SLO,
  spent on change.
- N+1 capacity, active-passive vs active-active, multi-AZ as a baseline, multi-region when truly
  needed.
- RPO = data you can lose, RTO = time you can be down; replication copies mistakes, backups
  (with tested restores and PITR) undo them.
- Metrics (counters, gauges, histograms; RED, USE, golden signals), structured logs with
  correlation IDs, traces of spans propagated via traceparent.
- Alert on user-facing symptoms, every page actionable with a runbook; fight alert fatigue.
- Rolling, blue-green and canary deployments; feature flags separate deploy from release; dark
  launches and shadow traffic test with real load; roll back first.
- Expand-contract makes schema changes safe while old and new code run together.
- Capacity planning with failure headroom; autoscaling lags, so keep a baseline buffer.
- Chaos engineering verifies failure handling; incidents: mitigate first, blameless postmortems
  with system-level action items.
- CI/CD: build once, test automatically, deploy the same artifact progressively.

## 14.15 Check yourself

1. A request goes through a load balancer (99.99%), an app tier (99.9%) and a database
   (99.95%), all required. What is the end-to-end availability? Then: what if the app tier were
   two independent copies at 99.9% each, either sufficient?

<details><summary>Answer</summary>

Serial: 0.9999 x 0.999 x 0.9995 ≈ 0.9984, about 99.84%. With two app copies in parallel the app
tier becomes 1 - (0.001)^2 = 0.999999, so total ≈ 0.9999 x 0.999999 x 0.9995 ≈ 0.9994, about
99.94% — now limited by the database. (This assumes independent failures, which real systems
only approximate.)

</details>

2. Your SLO is 99.95% of requests successful over 30 days, and you serve 200 million requests a
   month. What is the error budget, and what happens when it is exhausted?

<details><summary>Answer</summary>

0.05% of 200 million = 100,000 failed requests per 30 days. When it is exhausted, the team slows
risky changes (feature launches, experiments) and prioritizes reliability work until the budget
recovers, as agreed in advance.

</details>

3. Why is having three synchronous replicas not a substitute for backups?

<details><summary>Answer</summary>

Replication copies every change, including bad ones: an accidental delete, a corrupting bug or
ransomware are replicated to all replicas within moments. Backups (snapshots plus log archiving
for point-in-time recovery), stored separately and tested by restoring, let you go back to the
state before the mistake.

</details>

4. Why can't you compute the fleet's p99 latency by averaging each server's p99?

<details><summary>Answer</summary>

Percentiles are not linear: the average of per-server p99s is not the p99 of the combined
distribution (servers have different traffic volumes and distributions). Collect latency as
histograms with shared buckets, sum the bucket counts across servers, then compute the
percentile from the merged histogram.

</details>

5. You get paged because CPU on one database replica is at 92%. Users see no errors and latency
   is normal. What is wrong with this alert?

<details><summary>Answer</summary>

It pages on a cause, not a symptom. High CPU may be harmless, and nobody is affected, so the page
is not actionable and contributes to alert fatigue. Page on user-facing SLIs (error rate, latency,
burn rate); turn resource warnings into tickets or dashboard items.

</details>

6. Compare blue-green and canary deployments: one advantage of each.

<details><summary>Answer</summary>

Blue-green: instant cutover and instant rollback by switching the router back to the old
environment (at the cost of running two full environments). Canary: a bad change only reaches a
small fraction of users at first, and you compare real metrics before ramping up, limiting the
blast radius.

</details>

7. You need to change a column from `price_cents INT` to `price DECIMAL` on a large table with
   zero downtime. Outline the steps.

<details><summary>Answer</summary>

Expand-contract: add a new `price` column; deploy code that writes both columns; backfill old rows
in batches; deploy code that reads `price` (still writing both); deploy code that stops writing
`price_cents`; finally drop `price_cents` once no running version uses it. Each step remains
compatible with the previous code version so any deploy can be rolled back.

</details>

8. What is the difference between RPO and RTO, with an example of each?

<details><summary>Answer</summary>

RPO is the maximum acceptable data loss, measured in time: with RPO = 5 minutes, you may lose at
most the last 5 minutes of writes (so you need replication or log shipping at least that
frequent). RTO is the maximum acceptable downtime: with RTO = 1 hour, the service must be serving
again within an hour of the disaster (so restore or failover must be practiced and fast enough).

</details>
