# 14 — Reliability, observability and shipping safely
*Why it matters:* after "how does it work?" every review asks "how do we know it works, and what
happens when it doesn't?"; this is the vocabulary for that half of the job.

## Availability and the nines
**How it works** · **Availability** is the fraction of time (or requests) a system does its job
correctly. Each extra nine cuts allowed downtime 10x and usually costs far more than 10x.

| Availability | Per year | Per month | Per day |
|---|---|---|---|
| 99% | 3.65 days | 7.3 h | 14.4 min |
| 99.9% | 8.76 h | 43.8 min | 1.44 min |
| 99.95% | 4.38 h | 21.9 min | 43.2 s |
| 99.99% | 52.6 min | 4.38 min | 8.6 s |
| 99.999% | 5.26 min | 26.3 s | 0.86 s |

**Example** · Two 10-minute `order-service` outages. Time-based, both are 10 "down" minutes.
Request-based, the one at the 8-9 pm peak (~120 orders/s) fails 120 x 600 = 72,000 orders; the
one at 3 a.m. fails a few hundred. At 99.99% the monthly allowance (4.38 min) is gone before
anyone is paged and logged in. So: **request-based** (good / valid requests) reflects real impact; at four nines recovery must
be automatic.

## Availability math: serial and parallel
**How it works** · Assuming independent failures: **serial** (all needed) `A = A1 x A2 x A3`;
**parallel** (any one enough) `A = 1 - (1 - A)^N`.

**Example** · Asha's "Place order" needs `api-gateway` (99.99%), `order-service` (99.9%) and
`payment-service` (99.95%).
```text
serial:  0.9999 x 0.999 x 0.9995            ≈ 0.9984 -> 99.84%  (below every part)
order-service as 2 independent copies:  1 - (0.001)^2 = 0.999999
         0.9999 x 0.999999 x 0.9995         ≈ 0.9994 -> 99.94%  (now payment limits)
LB 99.99% -> app 99.9% -> DB 99.9% ≈ 99.8%; ten at 99.9% ≈ 99.0%; two at 99%: 99.99%
same bad deploy hits both copies at once -> back to ~99.84% or worse
```
So: serial dependencies only lower availability (an argument against long sync chains);
redundancy builds reliable systems from unreliable parts, but only for **independent**
failures. Same rack, same buggy code, same broken deploy in two regions are **correlated
failures**; spread copies across zones and roll out gradually.

## SLI, SLO, SLA and error budgets
**How it works** · **SLI**: a measured number. **SLO**: internal target for an SLI over a window.
**SLA**: customer contract with penalties. Keep `SLA <= SLO <= what we usually achieve`.
**Error budget** = 1 - SLO: the failures you are allowed.

**Example** · QuickBite's "Place order", 30-day window, ~2 M orders/day = 60 M requests.
```text
SLI    fraction of POST /v1/orders at api-gateway that are non-5xx and under 300 ms
SLO    99.9%            SLA (with credits) 99.5%         this month so far: 99.95%
budget 0.1% x 60,000,000 = 60,000 bad requests  (≈ 43 min of full downtime in 30 days)
20:00  bad deploy fails 30% of orders for 10 min at 120/s: 120 x 600 x 0.3 = 21,600
       -> 36% of the budget gone in one evening; 38,400 left
```
So: budget left -> ship features, run experiments; budget burned -> freeze risky launches, do
reliability work. It ends the "faster vs careful" argument. Measure at the edge (not "CPU below
80%"). **100% is the wrong target**: users cannot tell 99.99% from 100%, and chasing it stops
all change.

## Redundancy patterns
**How it works** · **N+1**: if peak needs N servers run N+1 (N+2 to survive a failure during
maintenance). **Active-passive** (primary-standby): one serves, one waits. **Active-active**: all
serve.

**Example** · QuickBite's order path at peak. `order-service` needs 8 instances: run 9 (N+1) or
10 (N+2) over 3 AZs. The orders DB is active-passive: AZ-a primary loses power -> AZ-b standby
promoted in seconds to minutes (if it was ever tested). `api-gateway` is active-active over 2
AZs at 50% each: when one dies the other takes 100%, so each needs that headroom.
So: active-passive is simple and conflict-free but idles capacity; active-active uses everything
but needs headroom, and multi-writer setups must resolve write conflicts.

| Failure domain | Protects against | Cost |
|---|---|---|
| Multiple servers | One machine | Low |
| **Multi-AZ** | One data center (power, cooling, network) | Low-medium; AZs a few ms apart, sync replication practical; 2-3 AZs is standard |
| **Multi-region** | A whole region, large provider issues | High: tens to 100+ ms apart, usually async, data residency, complexity |

## RPO, RTO, backups and disaster recovery
**How it works** · **RPO** = how much data you may lose, in time. **RTO** = how long you may be
down. **Replication is not a backup**: it copies everything, including mistakes. A **backup** is
a separate point-in-time copy: full snapshots plus **WAL/binlog archiving** for **point-in-time
recovery (PITR)**.

**Example** · Targets RPO 5 min, RTO 1 h for the orders DB.
```text
02:00     nightly snapshot; WAL archived to another account/region every minute
14:03:00  bad migration runs DELETE FROM orders with no WHERE
14:03:00+ both replicas apply the delete within milliseconds (replication can't help)
14:05     incident declared; restore 02:00 snapshot + replay WAL up to 14:02:59 (PITR)
14:55     orders back: RTO 52 min <= 1 h; WAL shipped each minute -> worst case ~1 min
          lost even if the whole region dies, within RPO 5 min
```
So: replication protects against losing a machine; backups protect against losing the data.
Store them elsewhere, ideally immutable, and **test restores regularly**: the restore time you
measured in a drill is your real RTO.

| DR tier | Second region holds | RPO | RTO | Cost |
|---|---|---|---|---|
| Backup and restore | Backups only | Hours | Hours to a day | Lowest |
| Pilot light | Replicated data, core infra at zero/minimal | Minutes | Tens of min to hours | Low |
| Warm standby | Scaled-down running copy | Seconds to minutes | Minutes | Medium |
| Multi-site active-active | Full capacity, live | Near zero to seconds | Near zero | Highest |

## Observability: metrics, logs, traces
**How it works** · **Monitoring** answers known questions ("error rate > 1%?"); **observability**
lets you answer new ones ("why only Android users in Brazil since 14:00?"). **Metrics** are cheap
numbers over time (dashboards, alerts); **logs** are event records, expensive at volume (details
of one event); **traces** show one request's path, sampled (where time went).

**Example** · "Checkout feels slow" at 20:40; follow one request.
1. Metric: `place_order_latency` histogram, p99 = 1.2 s (SLO 300 ms). The average says 60 ms,
   because 99 requests at 10 ms + 1 at 5 s average (990 + 5,000) / 100 ≈ 60 ms.
2. Trace `4bf92f...` for one slow request:

```text
api-gateway  POST /v1/orders ........................................ 1,240 ms
  |-- restaurant-service  getPrices ...... 80 ms
  |-- order-service  computeTotal ............................... 900 ms   <-- here
  |     |-- db  SELECT promotions ........................ 850 ms         <-- slow query
  |-- payment-service  authorize ............. 200 ms
```

3. Log, structured, same trace ID: `{"ts":"2026-10-10T20:40:11Z","level":"warn",
   "service":"order","event":"slow_query","order_id":"o_90311","ms":850,"trace_id":"4bf92f..."}`

So: metrics say something is wrong, traces say where, logs say why.
- **Metrics**: **counter** (only up; graph its rate), **gauge** (up and down: queue length),
  **histogram** (buckets; percentiles later). You **cannot average percentiles** across servers;
  sum buckets, then compute. **Cardinality**: each label-value combination is a series; a
  `user_id` label would create ~20 M series and can kill the metrics system.
- Checklists: **RED** for services (Rate, Errors, Duration); **USE** for resources (Utilization,
  Saturation, Errors); **four golden signals** (latency, traffic, errors, saturation), with
  latency of successes and failures measured separately.
- Logs: a **correlation ID** from the edge rides every call and log line (often the trace ID).
  DEBUG off in production; never log passwords, tokens, card numbers; sample success logs.
- Traces: a tree of **spans** (start, duration, service, span ID, parent ID), connected by the
  W3C `traceparent` header `00-<16-byte trace id>-<8-byte parent span id>-01` (01 = sampled),
  also in Kafka headers (OpenTelemetry). **Sample** e.g. 1% plus all error and slow traces
  (**tail-based sampling** decides after the trace ends).

## Dashboards and alerting
**How it works** · Dashboards start with user-facing SLIs (RED), then drill to resources (USE)
and dependencies. **Alert on symptoms, not causes.** **Burn-rate alerts** page when the error
budget would be exhausted soon; slow burns become tickets.

**Example** · Friday 20:30, three signals.
1. "CPU 92% on one orders-db replica", users fine -> ticket, not a page (cause, not symptom).
2. "Place-order error rate 3% for 5 min" -> page (users are hurting); alert links to a runbook.
3. Burn rate: SLO 99.9% allows 0.1% errors; 1.4% errors over the last hour = 14x sustainable
   -> the 30-day budget would be gone in 30 / 14 ≈ 2.1 days -> page.

So: page only on actionable, urgent symptoms (also "p99 above SLO for 10 min"; never "one
replica restarted"). Fight **alert fatigue**: drop alerts nobody acts on; review noise per shift.

## Deploying safely
**How it works** · Most outages come from changes (deploys, config, migrations). Limit blast
radius and time to undo.
- **Rolling**: replace a batch at a time (drain, upgrade, health check). Cheap; v1 and v2 serve
  together so they must be compatible; rollback takes as long as rollout.
- **Blue-green**: deploy v2 to idle **green**, test, switch the router from **blue** all at once;
  keep blue for instant rollback. Costs double infra; a bad change hits 100% at once; the shared
  DB still needs expand-contract.
- **Canary**: ~1% of real traffic to v2, compare with the baseline automatically, ramp, auto-
  rollback if worse. The best default for large services.

**Example** · Shipping `order-service` v2 with a new surge-pricing banner.
```text
rolling (6 instances):  v1 v1 v1 v1 v1 v1 -> v2 v2 v1 v1 v1 v1 -> ... -> v2 x 6
canary:  20:00  1% -> v2   errors 0.4% vs baseline 0.1%, p99 410 vs 280 ms
         20:15  automated comparison fails -> auto-rollback; 99% of users never saw it
fix:     next day 1% -> 5% -> 25% -> 100%, metrics equal at each step
flag:    banner behind flag "surge_banner"; it confuses users -> off in seconds, no deploy
```
- **Feature flags** separate **deploy** from **release**: percentage/region rollouts, a **kill
  switch**, unfinished code merged safely. Cost: flags pile up as debt (remove after launch);
  the flag system needs a safe default.
- **Dark launch**: run the new path in production without showing users. **Shadow traffic**:
  mirror real requests (e.g. "biryani near me" to a new `search-service`), users get only the
  old response, compare offline; shadows must not cause side effects (double charges, SMS).
- **Rollback**: every deploy reversible and rehearsed; **roll back first, debug later**; beware
  irreversible changes (dropped columns, new message formats old code cannot read).

## Database migrations: expand-contract
**How it works** · During a rolling deploy old and new code share one DB, so a rename breaks one
of them. **Expand-contract** (parallel change) splits it into compatible steps.

**Example** · Rename `users.fullname` to `users.display_name` (Asha's row: "Asha").
```text
 1 EXPAND     add display_name (nullable); old code ignores it    Asha: fullname only
 2 WRITE BOTH new code writes both, still reads fullname          new rows: both set
 3 BACKFILL   copy old rows in small batches (no huge locks)      Asha: both "Asha"
 4 READ NEW   read display_name, still write both                 rollback still safe
 5 STOP OLD   stop writing fullname
 6 CONTRACT   drop fullname once no running code uses it
```
So: at every step the running code and the previous version both work, so any single deploy can
roll back; slower, the price of zero downtime. Big-table index or type changes can lock: use
online schema-change tools.

## Capacity planning and autoscaling
**How it works** · Load-test one instance, forecast peak, add failure and spike headroom.
**Autoscaling** adds or removes instances on CPU, requests per instance or queue length, but
**lags** (detect ~a minute, start seconds for containers to minutes for VMs, plus warm-up).

**Example** · `restaurant-service` (menus) at the dinner peak.
```text
one instance: 1,500 req/s at p99 < 200 ms (load test)
peak: 120 orders/s x ~100 page views per order ≈ 12,000 req/s
12,000 / 1,500 = 8 instances; survive losing 1 of 3 AZs -> 8 / (2/3) = 12
+ ~30% spike headroom -> 15.6 -> about 16 instances across 3 AZs
dinner rush starts at 20:00 -> scheduled pre-scale at 19:30 (autoscaling is too slow)
```
So: keep baseline headroom or pre-scale for known events. Prefer load-tracking metrics (queue
length beats CPU for I/O-bound work), cooldowns (out fast, in slowly), **max limits** (bugs and
attacks cost money; more app servers mean more DB connections), scheduled scaling for daily
patterns.

## Chaos, incidents, on-call
> **Black box:** **chaos engineering**: define steady state, hypothesize ("one cache node dies,
> success stays > 99.9%"), inject failure small (staging, then a prod slice) with an abort switch;
> **game days** rehearse the human side. **Use when:** you want to find weaknesses on your
> schedule. **Cost:** real risk; needs good observability and abort controls.

**How it works** · Incident flow: detect -> triage severity (SEV1 major ... SEV4 minor) ->
**incident commander (IC)** (coordinates, need not debug), responders, comms -> **mitigate
first** (rollback, failover, disable flag, shed load) -> communicate (status page) -> resolve ->
postmortem.

**Example** · The surge-banner deploy without a canary.
```text
20:02 deploy order-service v2       20:05 alert: place-order errors 4% (MTTD 3 min)
20:06 primary on-call acks          20:08 IC declares SEV2, opens the status page
20:10 rollback (not debugging)      20:14 errors back to 0.1%: MTTR 12 min
next day: blameless postmortem, action items with owners
```
So: availability ≈ MTBF / (MTBF + MTTR). One incident a month (MTBF ≈ 720 h) with MTTR 12 min:
720 / 720.2 ≈ 99.97%; with MTTR 2 h: 720 / 722 ≈ 99.72%. Recovering faster is often cheaper than
failing less. More vocabulary: primary/secondary, escalation policy, **ack**, runbook,
**MTTD / MTTR** (detect / recover), **MTBF** (between failures).

> **Black box:** **blameless postmortem**: timeline, impact, contributing factors, what went
> well/badly, action items with owners, focused on how the system allowed it. **Use when:** after
> any significant incident. **Cost:** time; but blame makes people hide information, and fixes
> must change systems ("dry run for bulk deletes"), not people ("be careful").

> **Common mistake:** ending with a single "root cause". Incidents usually combine factors (a
> bug, a missing alert, a retry storm, a stale runbook); fix several.

## From "works on my machine" to CI/CD
> **Black box:** **containers** pin dependencies, config comes from the environment, secrets from
> a secrets manager, infra as code; **CI** builds, tests, scans and tags an image by commit SHA on
> every change; **CD** deploys that same image to staging then a prod canary with auto-rollback
> (e.g. commit `a1b2c3d` -> image `order-service:a1b2c3d` -> staging -> 1% -> 100%).
> **Use when:** always. **Cost:** pipeline upkeep; pays back in small, safe, frequent deploys.

**Continuous delivery**: every passing change is releasable, a human clicks deploy; **continuous
deployment**: it ships automatically. "Build once, deploy many"; small frequent deploys are safer.

## In one minute
- Each nine cuts downtime 10x: 99.9% ≈ 8.76 h/year, 99.99% ≈ 52.6 min/year.
- Serial dependencies multiply; parallel copies give 1 - (1-A)^N, only if failures are independent.
- SLI measures, SLO targets, SLA contracts; error budget = 1 - SLO, spent on change. N+1,
  active-passive vs active-active, multi-AZ baseline, multi-region when truly needed.
- RPO = data lost, RTO = time down; replication copies mistakes, tested backups with PITR undo them.
- Metrics (RED/USE, histograms, no averaged percentiles), structured logs with trace IDs, sampled
  traces via traceparent; page on symptoms with runbooks.
- Canary + feature flags + roll back first; expand-contract for schema; capacity with AZ-loss
  headroom; mitigate first, blameless postmortems, build once deploy many.

## Go deeper
Full chapter 14: sections 14.3 (SLOs and error budgets), 14.6 (observability), 14.8 (deploying safely), 14.9 (expand-contract)
