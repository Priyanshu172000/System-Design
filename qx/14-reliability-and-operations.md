# 14 — Reliability and operations: quick notes

This chapter answers "how do we know it works, and what happens when it doesn't?": uptime
targets, backups, monitoring, safe deploys and handling incidents.

## 1. Availability and the nines

**Availability** = the share of time (or requests) the system works correctly. Each extra 9
cuts allowed downtime 10x, and costs much more than 10x.

| Availability | Down per year | Down per month |
|---|---|---|
| 99.9% | 8.76 h | 43.8 min |
| 99.99% | 52.6 min | 4.38 min |
| 99.999% | 5.26 min | 26.3 s |

- Count **requests**, not minutes. *e.g.* 10 minutes down at the 8 pm peak fails
  120 × 600 = 72,000 orders; 10 minutes at 3 am fails a few hundred.

## 2. Availability math

- **Serial** (all must work): multiply. *e.g.* gateway 99.99% × order 99.9% × payment 99.95%
  ≈ 99.84%, lower than every part.
- **Parallel** (any one is enough): `1 − (1 − A)^N`. *e.g.* two order-service copies at 99.9%
  give 99.9999%.
- Only true if failures are **independent**. *e.g.* one bad deploy hits both copies at once.

## 3. SLI, SLO, SLA, error budget

- **SLI:** a measured number. *e.g.* % of `POST /v1/orders` that succeed in under 300 ms.
- **SLO:** our internal target for it. *e.g.* 99.9% over 30 days.
- **SLA:** a contract with customers, with penalties. *e.g.* 99.5%. Keep SLA ≤ SLO.
- **Error budget** = 1 − SLO. *e.g.* 0.1% of 60 M monthly orders = 60,000 bad requests.
  A bad deploy failing 21,600 uses 36% of it.
- Budget left: ship features. Budget burned: freeze risky launches. 100% is the wrong target.

## 4. Redundancy

- **N+1:** if peak needs N servers, run N+1. *e.g.* order-service needs 8, run 9 or 10.
- **Active-passive:** one serves, a standby waits. *e.g.* the orders DB primary dies and the
  standby in another zone is promoted.
- **Active-active:** all serve. *e.g.* gateway in 2 zones at 50% each; each needs room for 100%.
- **Multi-AZ** (several data centres a few ms apart) is standard. **Multi-region** survives a
  whole region but is costly and usually async.

## 5. Backups and disaster recovery

- **RPO:** how much data you may lose (in time). **RTO:** how long you may be down.
- **Replication is not a backup:** it copies mistakes too. *e.g.* `DELETE FROM orders` with no
  WHERE hits every replica within milliseconds.
- **Backup:** a separate point-in-time copy. With the database's change log saved every minute,
  you can restore to any moment (**PITR**). *e.g.* restore to 14:02:59, back in 52 min.
- Test restores; a drill's restore time is your real RTO.
- DR tiers, cheap to costly: backup-and-restore (hours) → pilot light → warm standby →
  active-active (near zero).

## 6. Observability: metrics, logs, traces

- **Metrics:** cheap numbers over time. They say **something** is wrong.
  *e.g.* place-order p99 = 1.2 s.
- **Traces:** one request's path with timings. They say **where**.
  *e.g.* 850 ms in a `SELECT promotions` query.
- **Logs:** event records. They say **why**. *e.g.* a JSON line with `order_id` and `trace_id`.
- Averages hide pain: 99 requests at 10 ms + 1 at 5 s average 60 ms. Use percentiles.
- **Counter** only goes up; **gauge** goes up and down; **histogram** holds buckets.
  Never average percentiles across servers.
- **Cardinality:** each label value is a new series. *e.g.* a `user_id` label = 20 M series.
- Checklists: **RED** for services (rate, errors, duration); **USE** for resources
  (utilization, saturation, errors).
- Traces pass the `traceparent` header; keep ~1% plus all slow or failed ones.
- Never log passwords, tokens or card numbers.

## 7. Alerting

- **Alert on symptoms, not causes.** *e.g.* "order errors 3% for 5 min" pages someone;
  "CPU 92% on one replica" is just a ticket.
- **Burn-rate alert:** page if the budget will run out soon. *e.g.* 1.4% errors vs 0.1% allowed
  = 14x, so the 30-day budget is gone in ~2 days.
- Every page links to a **runbook**. Delete alerts nobody acts on (alert fatigue).

## 8. Deploying safely

| Method | How | Trade-off |
|---|---|---|
| Rolling | Replace a few instances at a time | Cheap; v1 and v2 must coexist |
| Blue-green | Start v2 beside v1, switch all at once | Instant rollback; double infra |
| Canary | Send ~1% to v2, compare, ramp up | Best default; auto-rollback |

- *e.g.* canary at 1% shows errors 0.4% vs 0.1%, so it auto-rolls back. 99% never saw it.
- **Feature flag:** turn code on or off without a deploy. *e.g.* "surge_banner" off in seconds.
- **Shadow traffic:** copy real requests to the new version; users see only the old answer.
- **Roll back first, debug later.**

## 9. Schema changes: expand-contract

Old and new code share the DB during a deploy, so a rename breaks one of them. Do it in steps:
add `display_name` → write both → backfill old rows → read new → stop writing old → drop
`fullname`. *e.g.* Asha's row has both columns until the end; any step can roll back.

## 10. Capacity and autoscaling

- *e.g.* one instance serves 1,500 menu reads/s. Peak 12,000/s needs 8. Survive losing 1 of 3
  zones: 12. Add ~30% for spikes: about 16.
- **Autoscaling** adds instances on load, but lags by minutes. Pre-scale at 19:30 for dinner.
- Set max limits; scale out fast, in slowly.

## 11. Incidents and CI/CD

- **Chaos engineering:** break things on purpose, small and with an abort switch, to find
  weaknesses on your schedule.
- Incident flow: detect → set severity → an **incident commander** coordinates → **mitigate
  first** (rollback, flag off) → status page → **blameless postmortem** with owned fixes.
- **MTTR:** time to recover. Availability ≈ MTBF / (MTBF + MTTR). *e.g.* one incident a month
  with MTTR 12 min ≈ 99.97%; with 2 h ≈ 99.72%. Faster recovery is often cheaper.
- **CI/CD:** every commit is built and tested into one image (e.g. `order-service:a1b2c3d`),
  then the same image goes to staging, canary, and 100%. Small, frequent deploys are safer.

## Remember these 5

1. Each nine cuts downtime 10x; serial dependencies multiply availability down.
2. Error budget = 1 − SLO: spend it on change, freeze when it's gone.
3. Replication copies mistakes; only tested backups with PITR undo them.
4. Metrics say what, traces say where, logs say why; page on symptoms.
5. Canary + feature flags + expand-contract; roll back first, then debug.
