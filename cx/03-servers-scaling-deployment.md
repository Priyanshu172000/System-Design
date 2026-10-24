# 03 — Servers, scaling and where code runs

*Why it matters:* the staged evolution from one server to many is the backbone of almost every design discussion; every box you add must be justified by the wall it removes.

## Processes and threads

**How it works** · A **process** is a running program with its own private memory, files and sockets; the OS isolates processes, so one crash doesn't take down others. A **thread** runs inside a process; threads **share memory** (heap: caches, pools, config) but each has its own stack.

**Example** · One `order-service` process, 200 threads, shared counter `ordersToday = 41`, no lock:

```text
 T1 reads 41          T2 reads 41
 T1 writes 42         T2 writes 42        -> two orders counted, value is 42 (should be 43)
```

So: sharing memory is cheap communication but needs synchronization (race conditions, chapter 26); and if one thread corrupts memory and crashes, all 200 in-flight requests die with the process. A **context switch** (save/restore registers, lose cache warmth) costs on the order of microseconds; with thousands of busy threads it adds up.

| | Process | Thread |
|---|---|---|
| Memory | isolated | shared within the process |
| Creation cost | higher | lower |
| Crash impact | only itself | can take down the whole process |
| Communication | via OS (pipes, sockets, files) | shared memory (needs locks) |
| Memory per unit | tens of MB+ | OS thread stack commonly reserved at around 1 MB |

## What a web server does

**How it works** · Listen on a port -> accept, read bytes, **parse** HTTP -> **route** by method + path -> run the **handler** (auth, validate, call DB/cache/services) -> **serialize** (JSON) and respond -> keep-alive or close.

**Example** · `restaurant-service` handles `GET /restaurants/r_77/menu`:

```text
 accept + parse + route              ~0.2 ms CPU
 check token                         ~0.3 ms CPU
 cache GET menu:r_77 -> miss         ~1 ms waiting
 SELECT ... FROM menu_items          ~38 ms waiting
 build + serialize JSON (60 dishes)  ~1.5 ms CPU
 total ~41 ms: ~2 ms CPU, ~39 ms waiting
```

So: this is **I/O-bound** work; image resizing, encryption, ML inference are **CPU-bound**. The difference decides the concurrency model.

## Thread-per-request vs event loop vs virtual threads

**How it works** ·
- **Thread-per-request**: a pool thread handles one request start to finish and **blocks** on I/O. Gain: simple sequential code, easy to debug. Pay: one OS thread held per waiting request.
- **Event loop**: a few threads (often one per core) with **non-blocking** I/O: start the DB call, register a callback, move on; the OS signals readiness (epoll/kqueue). Gain: tens of thousands of connections in little memory, ideal for I/O-bound work and long-lived connections (WebSockets, SSE, proxies). Pay: async/callback code; **never block the loop**.
- **Virtual threads** (green threads, goroutines, fibers): blocking-style code, but the runtime parks a waiting virtual thread and reuses the OS thread. Hundreds of thousands possible: event-loop scale with thread-per-request readability.

**Example** · One `order-service` instance at the dinner peak: 2,000 req/s arrive, the pool has 200 threads, and in thread-per-request each request **holds its thread for its whole life, including time spent waiting**.

1. Normal: each request takes 100 ms, mostly waiting on `payment-service`. Watch the first moments:

```text
time     arrived  finished  in progress (= threads held)
10 ms       20        0        20
100 ms     200        0       200   <- request #1 only now finishes
101 ms     202        2       200   from here: 2 arrive and 2 finish every ms
500 ms    1000      800       200   stays at 200
```

   **Little's Law**: in progress = arrival rate x time each takes = 2,000 x 0.1 s = 200. The pool is exactly full. (Like a restaurant: 20 guests/hour staying 3 hours need 60 tables.)
2. `payment-service` slows to 1 s: in progress needed = 2,000 x 1 = 2,000, but only 200 threads exist. 200 threads busy for 1 s each finish only 200 req/s, while 2,000/s arrive, so the waiting queue **grows by 1,800 every second** until requests time out. The CPU sits idle: all 200 threads are asleep waiting for payment. The instance looks down without doing any work.
3. Same instance on an event loop: a waiting request holds no thread, just a small "continue #1234 when payment replies" record, so 2,000 waiting requests are cheap. But the loop thread runs everyone's code: if a developer adds a 200 ms receipt-PDF render on it, every request on that thread stalls 200 ms. Move CPU-heavy work to a worker pool.

So: event loops and virtual threads survive waiting cheaply; thread pools exhaust. None makes the database faster: 10,000 virtual threads and a 50-connection DB pool means 9,950 wait. Concurrency moves the bottleneck, it does not remove it.

| | Thread-per-request | Event loop | Virtual threads |
|---|---|---|---|
| Code style | simple, blocking | async/callbacks | simple, blocking-style |
| Concurrency per server | hundreds to a few thousand | tens of thousands+ | tens of thousands+ |
| Memory per in-flight request | high | low | low |
| CPU-heavy work | fine | must offload | fine (CPU still finite) |
| Danger | pool exhaustion | blocking the loop | pinning, limited pools elsewhere |

> **Say it like this:** "This service is I/O-bound, so I'd use an event loop or virtual threads to keep many requests in flight cheaply, and push CPU-heavy work like image processing to a separate worker pool so it can't stall request handling."

## Connection pools

**How it works** · Opening a DB connection costs TCP, often TLS, auth and session setup (several ms or more), and the DB caps connections (each costs DB memory, sometimes a whole process). A **connection pool** opens a fixed set at startup and lends them out: borrow, query, **return promptly** (never hold one while calling another service); if all are busy, wait up to a timeout, then fail fast. Same idea for HTTP clients: keep-alive / client pools instead of TCP + TLS per call.

**Example** · The orders DB allows `max_connections = 500`; each `order-service` instance has a pool of 20.
1. Afternoon: 20 instances x 20 = 400 connections. Fine.
2. 8 pm: the autoscaler goes to 50 instances: 50 x 20 = 1,000 connections, double the DB limit. New instances fail to connect; the DB thrashes on the ones it accepted.
3. Someone "fixes" slowness by raising pools to 200: more concurrent queries contend for the same cores and locks, and each gets slower.
4. Real fix: smaller pools (say 8 each = 400 at 50 instances), a **connection proxy/pooler** in front of the DB to multiplex, and fix the slow queries (indexes, locks, chapter 05).

So: total connections = pool size x instances; beyond a point extra connections only add contention.

## Stateless vs stateful services

**How it works** · **State** = data a server remembers between requests (session, in-memory cart, file on local disk, local counter). A **stateless** service keeps no client-specific data in its own memory/disk between requests; any instance serves any request.

**Example** · Asha logs in to QuickBite:

```text
 Stateful:  POST /login -> LB -> app1 (session in app1 RAM)
            GET  /cart  -> LB -> app2  "who are you?" -> logged out
            app1 redeployed at 20:00 -> every session on it gone
 Stateless: POST /login -> app1 writes sess:9f2c -> {user: u_1842} to the session store
            GET  /cart  -> app2 reads sess:9f2c (~sub-ms) -> u_1842, works
            any app dies -> nothing lost
```

So: **sticky sessions** are the stateful workaround and they hurt (uneven load, lossy failover, no free autoscaling or deploys; local files vanish on replacement).

| State | Where it lives |
|---|---|
| durable business data | database |
| sessions | session store (fast KV / in-memory cache with persistence) or a signed client token (JWT, chapter 15) |
| hot read data | cache (chapter 08) |
| files, images (dish photos) | object storage (chapter 09) |
| in-progress background work | queue (chapter 11) |

Gains: scale by adding servers, kill any server without loss, deploy by replacing instances, simple balancing. Cost: a network hop for state, and shared stores carry the hard parts (chapters 07, 08). A stateless *app tier* is not a stateless *system*: databases, caches, WebSocket servers (order tracking) and stream processors are inherently stateful and need replication, partitioning, draining. Local in-memory caches are fine if losing them costs only speed.

> **Say it like this:** "I'll keep the app tier stateless, sessions in a shared store and files in object storage, so any instance can serve any request; I can autoscale and deploy by replacing instances, and the hard state problems stay in the database and cache."

## Scaling up vs scaling out

**How it works** · Scale up = bigger machine; scale out = more machines plus load balancing.

| | Vertical (scale up) | Horizontal (scale out) |
|---|---|---|
| How | bigger machine (hundreds of cores, TBs of RAM exist) | more machines + load balancing |
| Code changes | none | must be stateless or partition-aware |
| Limit | largest machine; price grows faster than capacity at the top | mostly coordination and data limits |
| Fault tolerance | none, one box, upgrades often need downtime | survives instance loss |
| Pay | ceiling, SPOF | distributed problems: partial failure, latency, consistency (chapters 07, 10) |
| Good for | databases early on, quick relief | stateless app servers, caches, partitioned data |

**Example** · QuickBite's dinner peak needs about 5x the afternoon capacity:
1. `order-service` (stateless): 10 -> 50 instances behind the LB. No code change.
2. Orders DB: move from 16 to 64 cores and add two read replicas for order history. Still one primary, no sharding.

So: scale the stateless app tier out from day one; scale the database **up plus read replicas** as long as you reasonably can, because sharding is hard (chapter 07).

## The evolution of a system: one server to many

**How it works** · Each stage exists because the previous one hits a specific wall.

| Stage | What you add | Wall that forces the next stage |
|---|---|---|
| 1. One server | app + DB + files on one box (prototype) | app and DB fight for CPU/RAM/disk; one crash kills all; can't scale tiers separately |
| 2. Separate DB | DB on its own tuned machine (RAM, fast disks) | one app server runs out of CPU, is a SPOF, deploys mean downtime |
| 3. LB + N stateless app servers | load balancer, health checks, rolling deploys | all apps hammer one DB; reads dominate (often 10:1 or 100:1) |
| 4. Cache | hot, rarely changing data in memory; 90% hit rate = DB sees 1/10 of reads | trade-off: staleness, invalidation (ch 08); misses still overload one DB, DB is a SPOF |
| 5. Primary + read replicas | writes to **primary**, replicas serve reads, promote on **failover** | trade-off: **replication lag** (route read-your-own-writes to primary, ch 07); static bytes eat bandwidth, far users see high latency |
| 6. CDN + object storage | static (sometimes API) content cached at edges near users, tens of ms | slow synchronous side effects (email, resize, search indexing, partners) slow responses and couple uptime |
| 7. Queue + workers | request path does only what the user waits for, **enqueues** the rest; queue absorbs spikes | trade-off: eventual completion, retries, duplicates (ch 11); writes or data size outgrow one primary |
| 8. Sharding | split data by **shard key** (`hash(user_id) % 4`), each shard primary + replicas | cross-shard queries/transactions hard, hot spots from bad keys, painful re-sharding: so it comes last |

**Example** · QuickBite hitting the walls in order:
1. Stage 3 -> 4: menu views are about 100:1 to orders; at 120 orders/s peak that is about 12,000 menu reads/s. A cache with a 90% hit rate leaves the DB about 1,200 reads/s.
2. Stage 5: "my orders" reads go to replicas; right after placing `o_90311`, Asha's own order page reads the primary (replica may lag).
3. Stage 6: dish photos move to object storage behind a CDN; a Bengaluru user gets them from a nearby edge.
4. Stage 7: "Place order" commits the order, then enqueues SMS, email and the restaurant tablet push; a slow SMS provider no longer slows checkout.
5. Stage 8: only when order writes or data outgrow one primary: `orders` sharded by `hash(user_id) % 4`.

So: every box is justified by a number. Beyond stage 8: split into services (chapter 13) and multiple regions.

```text
                  [ DNS (geo) ]
                        |
 users --> [ CDN ] --> [ Load balancer ] --> [ app servers (stateless, autoscaled) ]
              |                                 |              |              |
   [ object storage ]                 [ cache cluster ] [ message queue ] [ shard router ]
                                                              |              |
                                                         [ workers ]  [ shard 0..N, each
                                                                        primary + replicas ]
 Side systems: search index, analytics pipeline (chapter 09)
```

> **Common mistake:** drawing the stage-8 diagram for a system needing 50 requests per second. Every box adds cost and failure modes. Estimate first (chapter 04), add only what the numbers justify, and say which stage you'd move to as load grows.

## Single points of failure

**How it works** · A **SPOF** is any component whose failure takes the system down. Ask of every box: "if this dies, what happens?"

**Example** · Walking QuickBite's diagram:
1. One `order-service` instance dies -> LB health check drops it; others serve. Not a SPOF.
2. Orders DB primary dies -> a replica is promoted; brief write outage, and with async replication the last few writes may be lost.
3. Cache cluster restarts empty at 8 pm -> 12,000 menu reads/s hit the DB at once. Unless the DB can survive a cold cache, the cache is a SPOF in disguise.
4. TLS certificate expires -> every client fails. Monitor expiry.

So: redundancy where a failure is unaffordable; the goal isn't zero SPOFs but "which failures can we afford, for how long?" (availability targets, chapter 14).

| Component | Typical fix |
|---|---|
| single app server | multiple instances behind an LB |
| the load balancer | redundant pair with floating IP, managed LB, DNS over multiple LBs |
| primary database | replicas with automatic failover (brief write outage; async replication may lose last writes) |
| cache | clustered with replicas; system must survive a cold cache without the DB collapsing |
| one zone / one region | multi-AZ / multi-region (costly, complex) |
| config service, DNS provider, certificate | redundancy, expiry monitoring |
| one person who knows how it works | runbooks and docs (chapter 14) |

## Where code runs: VMs, containers, Kubernetes, serverless

**How it works** · **Bare metal**: directly on hardware, max performance, slow to provision. **VM**: a **hypervisor** splits a machine into VMs each with a full guest OS (cloud "instances"); minutes to provision, strong isolation, GBs and slow-ish boot. **Container**: app + libraries + runtime in an **image**, run as an isolated process **sharing the host kernel**. Containers won because the same image runs in dev, test and prod, they start in seconds with MBs of overhead, and deploys are **immutable** (build a new image, replace, never patch). Cost: weaker isolation than VMs, and many containers need an orchestrator.

**Example** · `order-service:1.42` on Kubernetes:
1. Deployment says `replicas: 6`; Service `order-service` gives one stable name over the 6 pods.
2. node-2 dies at 19:40 taking 2 pods -> the control loop sees 4 != 6 and starts 2 on other nodes.
3. 20:00 CPU passes 70% -> HPA scales 6 -> 15 pods; readiness probes keep traffic off each pod until it's warm.
4. Release 1.43: new image, rolling update replaces pods a few at a time.

So: you declare state, the orchestrator keeps it. **Autoscaling** reacts in seconds to minutes, so it won't save you from a sudden 10x spike: keep headroom before 8 pm, scale on leading signals (queue depth) not only lagging ones (CPU).

> **Black box:** **Kubernetes** is a **declarative** container orchestrator: you state "6 copies, 1 CPU / 2 GB each, one stable address" and a control loop keeps reality matching. **Use when:** running many containerized services. **Cost:** complex to operate; small teams use managed Kubernetes or simpler platforms. It schedules and restarts; scalability still comes from a stateless app and a well-designed data tier.

| Term | Meaning |
|---|---|
| **Cluster** / **Node** | machines managed together / one machine running containers |
| **Pod** | smallest unit: one or a few containers sharing a network address; scheduled, restarted, replaced |
| **Deployment** | keep N identical pods running; rolling updates and rollbacks |
| **Service** | stable name/virtual address load-balancing over current pods (pod IPs change) |
| **Ingress** / gateway | routes external HTTP into services by host/path |
| **ConfigMap / Secret** | config and credentials injected into pods |
| **Horizontal Pod Autoscaler** | adds/removes pods on CPU or custom metrics |
| **Liveness / readiness probes** | "restart me if dead" / "no traffic until ready" |
| **Namespace** | logical partition for teams/environments |

> **Black box:** **serverless functions**: upload a function, the platform runs it per event (HTTP, queue message, upload), scales from zero to thousands, bills per invocation and time; e.g. a restaurant uploads a dish photo -> a function makes thumbnails. **Use when:** spiky or low traffic, event glue. **Cost:** **cold starts** (tens of ms to seconds), time limits, enforced statelessness, harder testing, lock-in, often pricier at high steady load, many concurrent functions can exhaust DB connections.

| | VMs | Containers + orchestrator | Serverless |
|---|---|---|---|
| Unit / startup | machine / minutes | process + deps / seconds | function / ms to s (cold start) |
| Who manages servers / scaling | you / you configure | you or managed / autoscaler | provider / automatic per request |
| Idle cost | full | full (nodes) | about zero |
| Best for | legacy, special OS needs, databases | most services | spiky, event-driven, glue |

## Cloud building blocks vocabulary

> **Black box:** every cloud offers the same generic blocks under different names: compute instances, managed containers/Kubernetes, serverless functions, managed relational DB (backups, replicas, failover; ch 05), managed NoSQL/KV (ch 06), managed cache (ch 08), **object storage** (by key, practically unlimited, very durable, cheap; ch 09), **block storage** (virtual disk for one VM, e.g. a DB's disk), queue/pub-sub/log streaming (ch 11), CDN, L4/L7 load balancer (ch 12), managed DNS with geo routing, secrets/key management (ch 15), monitoring/logging (ch 14). **Use when:** drawing boxes; speak generically (e.g. "orders live in a managed relational database with a primary and two read replicas across zones"). **Cost:** "managed" trades some control and money for not needing an ops team for it.

## Regions and availability zones

**How it works** · A **region** is a geographic area (Mumbai, Frankfurt) of several data centers; regions are hundreds to thousands of km apart, so failures are independent but cross-region round trips cost tens to hundreds of ms (~100+ ms). An **availability zone (AZ)** is one or more data centers in a region with independent power, cooling and network, close enough (often under ~2 ms round trip) for synchronous replication.

**Example** · QuickBite runs in one region, Mumbai, across three AZs:

```text
 AZ-a: app x5, orders DB primary    AZ-b: app x5, replica    AZ-c: app x5, replica
 AZ-a loses power -> LB sends traffic to b and c; replica in b (~1-2 ms away, nearly
 current) is promoted. Users see a brief blip, not an outage.
```
Expanding to Dubai means a second region: replication between regions is slow, so decide which orders are written where (chapter 10).

So: **multi-AZ** is the production baseline at modest cost (cross-AZ traffic, latency); **multi-region** survives a region outage and serves far users, but is expensive and hard (slow replication forces consistency choices, double infrastructure). Only when availability targets or geography demand it.

## Deploying without downtime, config and environments

**How it works** · Stateless + many instances allow gradual deploys: **rolling** (replace a few at a time, LB routes only to ready instances), **blue-green** (run new beside old, switch at once, switch back on trouble), **canary** (1–5% of traffic to the new version, compare errors/latency, expand). Requirement: old and new run side by side, so schema changes are **backward compatible** (add column, deploy code using it, remove old later; chapter 14).

**Example** · Shipping tips on orders (`order-service` 1.43):
1. Migration adds nullable column `tip_amount`; 1.42 ignores it.
2. Canary: 5% of traffic to 1.43. Errors 3% vs 0.2% on 1.42 -> roll back in seconds; only the canary slice was affected.
3. Fix, canary again, healthy -> roll out to 100%. The tip button stays behind a **feature flag** until Friday's launch.
4. The same image `order-service:1.43` went staging -> prod; only the injected DB URL and pool size differ.

So: deploy is not release; build once, inject config per environment.

**Environments**: dev/local (fake data), test/CI (generated data, every change), staging (production-like rehearsal, often anonymized data), production (real users). Staging is rarely identical at full scale, so prod canaries are still needed. **Config** = what differs per environment or changes without code (DB URLs, credentials, feature flags, timeouts, pool sizes): keep it out of code and image; **secrets** in a secrets manager, never in source control; review, version and roll out config like code (bad config pushes cause many large outages).

> **Common mistake:** building a separate image per environment. Then staging tested a different artifact than prod. Build once, promote the same artifact, inject config per environment.

## In one minute

- Processes isolate memory; threads share it, cheaper but need synchronization.
- Most requests are I/O-bound (~2 ms CPU, ~40 ms waiting): thread-per-request is simple but exhausts pools under slow dependencies; event loops and virtual threads keep many requests in flight; none make the DB faster.
- Pools avoid connection setup; total connections = pool x instances (50 x 20 = 1,000); bigger pools don't fix a slow DB.
- Keep the app tier stateless; sessions, files and data live in shared stores built for state.
- Scale app servers out; scale the DB up plus replicas as long as possible.
- Evolution: one server -> separate DB -> LB + N apps -> cache -> replicas -> CDN -> queue + workers -> shards, each step justified by a wall; estimate before adding boxes.
- Hunt SPOFs per box; multi-AZ is baseline, multi-region is a deliberate costly choice.
- Containers + Kubernetes keep declared state; serverless scales to zero but has cold starts and steady-load cost; build once, inject config.

## Go deeper

Full chapter 03: sections 03.3 (concurrency models), 03.5 (stateless vs stateful), 03.7 (system evolution), 03.11 (regions and zones)
