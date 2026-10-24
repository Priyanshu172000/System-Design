# 03 — Servers, scaling and where code runs

Chapter 01 followed a request to "the server". This chapter opens that box: what a server process
actually does, how it handles many requests at once, why we keep servers stateless, and how a
system grows from one machine to many. The staged evolution in section 03.7 is the backbone of
almost every design discussion — learn it well.

## 03.1 Processes and threads

### Process

A **process** is a running program: its code, its own private memory (heap, stack), open files
and sockets, and at least one thread. The operating system isolates processes from each other: one
process cannot read another's memory, and if one crashes, the others survive.

### Thread

A **thread** is a sequence of execution inside a process. All threads in a process **share the
same memory** but each has its own stack and instruction pointer. The OS scheduler runs threads on
CPU cores, switching between them.

```text
 +-------------------------- Process (one app server) --------------------------+
 |  Shared heap: caches, connection pools, config                               |
 |                                                                              |
 |   Thread 1          Thread 2          Thread 3           ...                 |
 |   [stack]           [stack]           [stack]                                |
 |   handling req A    handling req B    waiting on DB                          |
 +------------------------------------------------------------------------------+
```

| | Process | Thread |
|---|---|---|
| Memory | isolated | shared with other threads in the process |
| Creation cost | higher | lower |
| Crash impact | only itself | can take down the whole process |
| Communication | via OS (pipes, sockets, files) | via shared memory (needs locks) |
| Typical memory per unit | tens of MB+ | an OS thread stack is commonly reserved at around 1 MB (much less is actually used) |

Sharing memory makes threads cheap to communicate but dangerous: two threads updating the same
counter without synchronization produce wrong results (race conditions, chapter 26).

**Context switch**: when the CPU switches from one thread to another, it saves and restores
registers and may lose cache warmth. It costs on the order of microseconds. With thousands of busy
threads, the switching overhead adds up.

## 03.2 What a web server does

A **web server** (or application server) is a process that:

1. **Listens** on a port (e.g. 8080) for incoming TCP connections.
2. **Accepts** a connection, reads bytes, and **parses** them into an HTTP request.
3. **Routes** the request to your handler code based on method and path.
4. Runs your **handler**: authenticate, validate, call the database/cache/other services, build a
   result.
5. **Serializes** the result (JSON) and writes the HTTP response back.
6. Keeps the connection open for the next request (keep-alive) or closes it.

Most of the time in step 4 is usually spent **waiting**: for the database, for another service,
for the disk. A typical request might use 2 ms of CPU and spend 40 ms waiting on I/O. Such work is
**I/O-bound**. Work like image resizing, encryption, or ML inference that keeps the CPU busy is
**CPU-bound**. This difference decides which concurrency model fits.

## 03.3 Thread-per-request vs event loop

### Thread-per-request

Each incoming request is handled by one thread from a **thread pool**, start to finish. When the
handler calls the database, the thread **blocks** (sleeps) until the answer arrives.

```text
 Thread pool (200 threads)
  T1: [parse][--- waiting for DB 40 ms ---][respond]
  T2: [parse][--- waiting for service X 80 ms ---------][respond]
  T3: [parse][-- waiting --][respond]
  ...
  T200 busy -> request 201 waits in the queue
```

- **Gain**: simple, sequential code: `user = db.find(id); orders = svc.get(user)`. Easy to read,
  debug, and profile.
- **Pay**: each blocked thread holds memory and a slot. By Little's Law (chapter 01), at 2,000
  req/s and 100 ms latency you need about 200 concurrent threads. If a dependency slows to 1 s,
  you need 2,000 — the pool exhausts, requests queue, and the server appears down even though the
  CPU is idle.

### Event loop (non-blocking I/O)

A small number of threads (often one per CPU core) each run an **event loop**. I/O is
**non-blocking**: the handler starts a database call and registers "call me back when the result
arrives", then the thread immediately moves on to other requests. The OS notifies the loop when
sockets are ready (mechanisms like epoll/kqueue).

```text
 Event loop thread (1 per core)
  [start A: send DB query] [start B: send DB query] [start C] [A's DB reply -> finish A]
  [B's reply -> finish B] [start D] ...
  One thread juggles thousands of in-flight requests; nobody sleeps while waiting.
```

- **Gain**: tens of thousands of concurrent connections with little memory — ideal for
  I/O-bound work and long-lived connections (WebSockets, SSE, proxies).
- **Pay**: code is written as callbacks/promises/async-await, which is harder to follow and debug.
  And the cardinal rule: **never block the loop**. One CPU-heavy computation (or one accidental
  blocking call) on the loop thread freezes every request on that thread. CPU work must be pushed
  to a separate worker pool.

### Lightweight threads

A newer middle path: runtimes that provide very cheap **virtual threads** (also called green
threads, goroutines, fibers). You write simple blocking-style code, but when a virtual thread
waits for I/O, the runtime parks it and reuses the underlying OS thread for other work. You can
have hundreds of thousands of them. This gives the scalability of an event loop with the
readability of thread-per-request.

| | Thread-per-request | Event loop | Virtual threads |
|---|---|---|---|
| Code style | simple, blocking | async/callbacks | simple, blocking-style |
| Concurrency per server | hundreds to a few thousand | tens of thousands+ | tens of thousands+ |
| Memory per in-flight request | high (OS thread) | low | low |
| CPU-heavy work | fine | must offload | fine (but CPU is still finite) |
| Danger | pool exhaustion under slow dependencies | blocking the loop | pinning, limited pools elsewhere (e.g. DB connections) |

Note that none of these makes the database faster. If 10,000 virtual threads all want a database
connection and the pool has 50, 9,950 still wait. Concurrency on the server moves the bottleneck;
it does not remove it.

> **Say it like this:** "This service is I/O-bound — it mostly waits on the database and other
> services — so I'd use an event loop or virtual threads to keep many requests in flight cheaply.
> CPU-heavy work like image processing goes to a separate worker pool so it can't stall request
> handling."

## 03.4 Connection pools

### The problem

Opening a database connection is expensive: TCP handshake, often TLS, authentication, and
session setup on the database side — easily several milliseconds, sometimes far more. Databases
also cap total connections; each connection costs the database memory (in some databases, a whole
process per connection). If each request opened and closed its own connection, you would waste
time on setup and could overwhelm the database with connection churn.

### The idea

A **connection pool** opens a fixed set of connections at startup and lends them out:

```text
 App server
 +--------------------------------------------+
 | request threads   ->  [ pool: c1 c2 ... c20 ] ======> Database
 |  borrow c3, run query, return c3            |         (max_connections = 500)
 +--------------------------------------------+
```

- Borrow a connection, run the query, **return it** promptly (never hold it while calling another
  service).
- If all are busy, the request waits up to a timeout, then fails fast.

### Sizing — the classic trap

Total connections = pool size × number of app server instances. 50 servers × 20 connections =
1,000 connections. That can exceed what the database handles well even if each pool looks small.
More connections is not more throughput: the database has a fixed number of cores and disks, and
beyond a point extra connections just contend for locks and CPU. A small pool per instance is
usually better, and at large scale people put a **connection proxy/pooler** between the app fleet
and the database to multiplex many client connections onto fewer database connections.

The same idea applies to HTTP clients calling other services: reuse connections (keep-alive, a
client connection pool) instead of a new TCP + TLS handshake per call.

> **Common mistake:** "The DB is slow, let's raise the pool size from 20 to 200." If the database
> is the bottleneck, more concurrent queries make each one slower and can make total throughput
> worse. Find out why queries are slow (indexes, locks, chapter 05) first.

## 03.5 Stateless vs stateful services

### What "state" means here

**State** is data a server remembers between requests: a logged-in user's session, a shopping
cart in memory, an uploaded file on local disk, a counter in a local variable.

### Why stateful servers are painful

Suppose app server 1 keeps sessions in its memory.

```text
 Request 1 (login)  -> LB -> server 1   (session stored in server 1's RAM)
 Request 2          -> LB -> server 2   "who are you?"  -> user logged out
 Server 1 crashes / is redeployed       -> every session on it is gone
```

- The load balancer must route each user to "their" server forever (**sticky sessions**), which
  makes load uneven and failover lossy.
- You cannot freely add/remove servers (autoscaling) or deploy without kicking users out.
- Disk-local files vanish when the server is replaced.

### Stateless: the default

A **stateless** service keeps no client-specific data between requests in its own memory or disk.
Any instance can handle any request, because everything needed comes with the request or lives in
a shared store.

```text
            +--------------+
 client --> | Load balancer| --> any of [app1] [app2] [app3] [app4]  (identical)
            +--------------+                 |      |      |
                                             v      v      v
                                  +------------------------------------+
                                  | Shared state:                      |
                                  |  database (source of truth)        |
                                  |  cache / session store (fast KV)   |
                                  |  object storage (files, images)    |
                                  +------------------------------------+
```

Where each kind of state goes:

| State | Where it lives |
|---|---|
| durable business data (users, orders) | database |
| sessions | a session store (fast key-value store such as an in-memory cache with persistence), or a signed token held by the client (e.g. a JWT, chapter 15) |
| hot read data | cache (chapter 08) |
| uploaded files, images, videos | object storage (chapter 09) |
| in-progress background work | a queue (chapter 11) |

Gains: add servers to scale, kill any server without loss, deploy by replacing instances, simple
load balancing. Cost: every request may need a network hop to fetch state (a cache read is
roughly sub-millisecond in the same data center), and the shared stores now carry the hard parts
(they must be scaled and replicated — chapters 07, 08).

Note: **stateless app tier** does not mean the *system* is stateless. State is pushed down into
specialized systems that are built to handle it. Some services are inherently stateful —
databases, caches, WebSocket servers holding connections, stream processors — and they need
special treatment (replication, partitioning, connection draining).

Local in-memory caches are fine on a stateless server as long as they are only an optimization:
losing them costs speed, not correctness.

> **Say it like this:** "I'll keep the app tier stateless — sessions in a shared store, files in
> object storage — so any instance can serve any request. That lets me autoscale and deploy by
> just replacing instances, and the hard state problems are confined to the database and cache."

## 03.6 Scaling up vs scaling out

### Vertical scaling (scale up)

Buy a bigger machine: more cores, more RAM, faster disks.

- **Gain**: no code changes, no distributed-systems problems. Today a single machine can have
  hundreds of cores and terabytes of RAM — vertical scaling goes further than many people expect.
- **Pay**: price grows faster than capacity at the high end; there is a hard ceiling; one machine
  is a single point of failure; upgrading often needs downtime.

### Horizontal scaling (scale out)

Add more machines and spread the load.

- **Gain**: near-unlimited growth for stateless tiers; redundancy (one dies, others continue);
  commodity hardware.
- **Pay**: you need load balancing, the software must be designed for it (stateless, or data must
  be partitioned), and you inherit distributed-systems problems: partial failures, network
  latency, consistency (chapters 07, 10).

| | Vertical | Horizontal |
|---|---|---|
| How | bigger machine | more machines |
| Code changes | none | must be stateless / partition-aware |
| Limit | largest machine available | mostly coordination and data limits |
| Fault tolerance | none (one box) | survives instance loss |
| Cost curve | steep at the top | roughly linear |
| Good for | databases early on, quick relief | stateless app servers, caches, partitioned data |

In practice: scale the stateless app tier horizontally from day one (it is easy), and scale the
database vertically for as long as you reasonably can, because scaling a database horizontally
(sharding) is hard (chapter 07).

> **Say it like this:** "App servers scale horizontally because they're stateless. For the
> database I'd go vertical plus read replicas first, and only shard when writes or data size
> outgrow one primary, since sharding adds real complexity."

## 03.7 The evolution of a system: from one server to many

This is the story to have in your head. Each stage exists because the previous one hits a
specific wall. In a design conversation you can literally walk through these stages, and each box
you add should be justified by the problem it removes.

### Stage 1: everything on one server

```text
  users --> [ one server: web app + database + files on disk ]
```

App, database, and uploaded files share one machine. Perfect for a prototype.

**Wall**: the app and the database fight over CPU, RAM, and disk. One crash takes everything down.
You cannot scale app and database independently.

### Stage 2: separate the database

```text
  users --> [ app server ] -----> [ database server ]
```

The database gets its own machine, tuned for it (lots of RAM, fast disks). Each tier can scale up
independently.

**Wall**: the single app server runs out of CPU, and is still a single point of failure; a deploy
means downtime.

### Stage 3: load balancer + N stateless app servers

```text
                         +--> [ app 1 ] --+
  users --> [ LB ] ------+--> [ app 2 ] --+--> [ database ]
                         +--> [ app 3 ] --+
```

Make the app stateless (section 03.5), put a **load balancer** in front, add instances as traffic
grows. Health checks remove dead instances; rolling deploys replace instances one by one with no
downtime.

**Wall**: all those app servers hammer one database. Reads dominate (often 10:1 or 100:1), and the
database CPU/disk saturates.

### Stage 4: add a cache

```text
                         +--> [ app 1 ] --+--> [ cache ] (hot reads, ~sub-ms)
  users --> [ LB ] ------+--> [ app 2 ] --+
                         +--> [ app 3 ] --+--> [ database ]
```

Frequently read, rarely changed data (product pages, user profiles, sessions) is served from an
in-memory **cache**. If 90% of reads hit the cache, the database sees one tenth of the read load.

**Trade-off**: stale data and invalidation complexity (chapter 08). **Wall**: cache misses and
the remaining reads still overload one database; and the database is a single point of failure.

### Stage 5: database replication (primary + read replicas)

```text
                                     +--> [ cache ]
                                     |
  users --> [ LB ] --> [ app x N ] --+--> writes --> [ primary DB ]
                                     |                  |  replication
                                     |                  v
                                     +--> reads ---> [ replica 1 ] [ replica 2 ]
```

All writes go to the **primary**; it streams changes to **replicas**, which serve reads. If the
primary fails, a replica can be promoted (**failover**).

**Trade-off**: **replication lag** — a replica may be slightly behind, so a user might not see
their own just-written data unless you route such reads to the primary (chapter 07).
**Wall**: static content (images, JS, video) eats bandwidth from your servers, and far-away users
see high latency.

### Stage 6: CDN for static and cacheable content

```text
  users --> [ CDN edge near user ] --(miss)--> [ object storage / origin ]
    |
    +--> [ LB ] --> [ app x N ] --> cache / primary / replicas
```

A **CDN** (Content Delivery Network) caches static files (and sometimes API responses) in
locations close to users worldwide. Users download images from a nearby edge in tens of
milliseconds instead of crossing an ocean; your servers stop serving those bytes at all. Files
themselves move to **object storage** (chapter 09).

**Wall**: some requests do slow work synchronously (send email, resize images, update search
indexes, call slow partners), making responses slow and coupling you to every downstream's uptime.

### Stage 7: queues and background workers

```text
  [ app x N ] --(enqueue "send email", "resize image")--> [ message queue ]
                                                                 |
                                                   [ worker ] [ worker ] [ worker ]
                                                                 |
                                                   email provider, storage, search
```

The request path does only what the user must wait for, and **enqueues** the rest. Workers
process the queue at their own pace. Traffic spikes are absorbed by the queue (it grows, then
drains), and a slow email provider no longer slows down signup.

**Trade-off**: eventual completion instead of immediate, plus retry and duplicate handling
(chapter 11). **Wall**: write volume or total data size outgrows the single primary database;
even the biggest machine is not enough.

### Stage 8: sharding (partitioning) the database

```text
  [ app x N ] --> [ routing: shard = hash(user_id) % 4 ]
                     |            |            |            |
                 [ shard 0 ]  [ shard 1 ]  [ shard 2 ]  [ shard 3 ]
                 primary +    primary +    primary +    primary +
                 replicas     replicas     replicas     replicas
```

Split the data by a **shard key** (e.g. user ID) so each shard holds a subset, with its own
primary and replicas. Writes and storage scale with the number of shards.

**Trade-off**: cross-shard queries and transactions become hard, a bad shard key creates hot
spots, and re-sharding is painful (chapter 07). That is why it comes last.

### The full picture

```text
                  [ DNS (geo) ]
                        |
 users --> [ CDN ] --> [ Load balancer ] --> [ app servers (stateless, autoscaled) ]
              |                                 |              |              |
              v                                 v              v              v
   [ object storage ]                 [ cache cluster ] [ message queue ] [ shard router ]
     (files, media)                                             |              |
                                                           [ workers ]   [ shard 0..N ]
                                                                         primary + replicas
                                                                         each
 Side systems: search index, analytics pipeline (chapter 09)
```

Beyond this, systems split into multiple services (chapter 13) and run in multiple regions
(section 03.11).

> **Say it like this:** "I'd start simple — stateless app servers behind a load balancer and one
> database — then add a cache for hot reads, read replicas, a CDN for static content, queues for
> slow side effects, and only shard when write volume or data size outgrows a single primary."

> **Common mistake:** Drawing the stage-8 diagram for a system that needs 50 requests per second.
> Every box adds cost and failure modes. Estimate first (chapter 04), then add only what the
> numbers justify, and say which stage you would move to as load grows.

## 03.8 Single points of failure

A **single point of failure (SPOF)** is any component whose failure takes the whole system down.
Walk your diagram and ask of each box: "If this dies, what happens?"

| Component | Typical SPOF fix |
|---|---|
| single app server | multiple instances behind a load balancer |
| load balancer itself | a redundant pair with a floating IP, or a managed LB that is redundant internally; DNS over multiple LBs |
| primary database | replicas with automatic failover (accepting a brief write outage and possible loss of the last un-replicated writes if replication is asynchronous) |
| cache | a cluster with replicas; and the system must survive a cold cache without the database collapsing |
| one data center / zone | spread across availability zones (section 03.11) |
| one region | multi-region (costly and complex) |
| a single config service, DNS provider, certificate | redundancy, monitoring of expiry |
| one person who knows how it works | runbooks and docs (yes, really, chapter 14) |

Redundancy costs money and complexity. The question is not "eliminate every SPOF" but "which
failures can we afford, for how long?", which ties to availability targets (chapter 14).

## 03.9 Where code runs: VMs, containers, Kubernetes, serverless

### Bare metal and virtual machines

- **Bare metal**: your program runs directly on a physical server. Maximum performance, slow to
  provision, you manage everything.
- **Virtual machine (VM)**: a **hypervisor** splits one physical machine into several virtual
  ones, each with its own full operating system. Cloud "instances" are VMs. Provisioned in
  minutes; isolation is strong. Each VM carries a whole OS (GBs of disk, slow-ish boot).

### Containers

A **container** packages your app with its libraries and runtime into an **image**, and runs it
as an isolated process that **shares the host's OS kernel**.

```text
 Virtual machines                         Containers
 +--------+ +--------+ +--------+         +-------+ +-------+ +-------+ +-------+
 | App A  | | App B  | | App C  |         | App A | | App B | | App C | | App D |
 | libs   | | libs   | | libs   |         | libs  | | libs  | | libs  | | libs  |
 | Guest  | | Guest  | | Guest  |         +-------+ +-------+ +-------+ +-------+
 | OS     | | OS     | | OS     |         |     Container runtime                |
 +--------+ +--------+ +--------+         +--------------------------------------+
 |        Hypervisor            |         |     Host OS (one shared kernel)      |
 +------------------------------+         +--------------------------------------+
 |        Hardware              |         |     Hardware (or a VM)               |
 +------------------------------+         +--------------------------------------+
```

Why containers took over:

- **"Works on my machine" solved**: the same image runs in dev, test, and prod.
- **Fast and light**: start in seconds or less, megabytes of overhead, many per machine.
- **Immutable deploys**: to change code, build a new image and replace containers; never patch
  a running server.

Trade-off: weaker isolation than VMs (shared kernel), and running hundreds of containers across
many machines needs a manager — which is Kubernetes' job.

### Kubernetes: the vocabulary and the why

**Kubernetes** (often "k8s") is a **container orchestrator**: you declare what you want running,
and it continuously makes reality match. "I want 6 copies of the orders service, each with 1 CPU
and 2 GB RAM, reachable at one stable address." If a machine dies, it reschedules those copies
elsewhere. This is **declarative**: you describe the desired state, a control loop fixes drift.

| Term | What it is | Why it exists |
|---|---|---|
| **Cluster** | a set of machines (**nodes**) managed together | pool of capacity |
| **Node** | one machine (VM or physical) running containers | where work actually runs |
| **Pod** | the smallest deployable unit: one (or a few tightly coupled) containers sharing a network address | the unit that gets scheduled, restarted, and replaced |
| **Deployment** | "keep N identical pods of this image running"; handles rolling updates and rollbacks | declarative scaling and zero-downtime deploys |
| **Service** | a stable virtual address/DNS name that load-balances across the current pods of a deployment | pods come and go with changing IPs; callers need one stable name |
| **Ingress** / gateway | routes external HTTP traffic into services by host/path | one entry point from the internet |
| **ConfigMap / Secret** | configuration and credentials injected into pods | keep config out of images (section 03.13) |
| **Horizontal Pod Autoscaler** | adds/removes pods based on CPU or custom metrics | follow traffic automatically |
| **Liveness / readiness probes** | health checks: "restart me if dead", "don't send traffic until I'm ready" | self-healing and safe rollouts |
| **Namespace** | a logical partition of the cluster | separate teams/environments |

```text
           Ingress (external HTTP)
                 |
           Service "orders" (stable name: orders.default.svc)
          /        |         \
      [pod]     [pod]      [pod]      <- managed by Deployment "orders" (replicas: 3)
      node-1    node-2     node-3        HPA may change 3 -> 8 when CPU > 70%
```

Trade-off: Kubernetes is powerful but complex to operate; small teams often use a managed
Kubernetes offering or a simpler platform.

**Autoscaling** in general: add capacity when a metric (CPU, request rate, queue length) crosses
a threshold, remove it when load drops. It reacts in seconds to minutes, so it does not save you
from a sudden 10x spike — keep headroom, and scale on leading signals (queue depth) rather than
only lagging ones (CPU).

### Serverless (functions as a service)

With **serverless functions**, you upload a function; the platform runs it on demand per event
(HTTP request, queue message, file upload), scales it automatically from zero to thousands of
concurrent copies, and bills per invocation and execution time.

- **Gain**: no servers to manage, scale-to-zero (pay nothing when idle), great for spiky or
  low-traffic workloads and event glue ("when a file lands in storage, make a thumbnail").
- **Pay**: **cold starts** (the first request to a new instance can take from tens of milliseconds
  to seconds while the runtime starts), execution time limits, statelessness enforced, harder
  local testing and debugging, vendor lock-in, and at high steady load it is often more expensive
  than always-on servers. Many concurrent functions can also exhaust database connections
  (section 03.4).

| | VMs | Containers (+ orchestrator) | Serverless functions |
|---|---|---|---|
| Unit | whole machine | process + its dependencies | single function |
| Startup | minutes | seconds | ms to seconds (cold start) |
| Who manages servers | you | you (or a managed cluster) | the provider |
| Scaling | you configure | autoscaler | automatic, per request |
| Cost when idle | full | full (for nodes) | about zero |
| Best for | legacy, special OS needs, databases | most services | spiky, event-driven, glue code |

## 03.10 Cloud building blocks vocabulary

Every major cloud offers the same core building blocks under different product names. Speak in
generic terms in a design discussion:

| Generic name | What it gives you | Use it for |
|---|---|---|
| **Compute instances** (VMs) | rentable virtual machines | app servers, self-managed software |
| **Managed container service / managed Kubernetes** | runs your containers | most services |
| **Serverless functions** | run code per event | glue, spiky workloads |
| **Managed relational database** | SQL database with backups, replicas, failover handled | transactional data (chapter 05) |
| **Managed NoSQL / key-value store** | scalable key-value or document storage | high-scale simple access patterns (chapter 06) |
| **Managed cache** | in-memory key-value store | caching, sessions, counters (chapter 08) |
| **Object storage** | store files as objects by key, practically unlimited, very durable, cheap | images, videos, backups, data lake (chapter 09) |
| **Block storage** | virtual disks attached to one VM | a database's disk |
| **Message queue / pub-sub / log streaming** | asynchronous messaging | decoupling, background jobs, event streams (chapter 11) |
| **CDN** | edge caching worldwide | static assets, media |
| **Load balancer** | L4/L7 traffic distribution with health checks | front door of each service (chapter 12) |
| **DNS service** | managed authoritative DNS, often with geo/latency routing | domain names, failover |
| **Secrets manager / key management** | store credentials and encryption keys | chapter 15 |
| **Monitoring / logging** | metrics, logs, traces, alerts | chapter 14 |

"Managed" means the provider runs the software, patches it, handles backups and replica failover;
you trade some control and cost for not needing an operations team for it. For a design
discussion, "a managed relational database with a primary and two read replicas across zones" is
a perfectly good box.

## 03.11 Regions and availability zones

- A **region** is a geographic area (e.g. "Mumbai", "Frankfurt") containing several data centers.
  Regions are far apart (hundreds to thousands of km): independent failures, but cross-region
  round trips cost tens to hundreds of milliseconds.
- An **availability zone (AZ)** is one or more data centers within a region, with independent
  power, cooling and networking, close enough (low single-digit milliseconds apart, often under
  ~2 ms round trip) for synchronous replication.

```text
 Region: ap-south (Mumbai)                    Region: eu-central (Frankfurt)
 +----------------------------------------+   +-------------------------------+
 |  AZ-a          AZ-b          AZ-c      |   |  AZ-a     AZ-b     AZ-c       |
 | [app][app]   [app][app]   [app][app]   |   |  ...                          |
 | [DB primary] [DB replica] [DB replica] |   |                               |
 +----------------------------------------+   +-------------------------------+
        <-- ~1-2 ms between AZs -->            <---- ~100+ ms between regions ---->
```

Design defaults:

- **Multi-AZ** is the standard baseline for production: app instances spread across zones,
  database replicas in different zones. A whole data center can fail and you keep running. The
  cost is modest (some cross-AZ traffic charges and latency).
- **Multi-region** protects against a region-wide outage and puts data closer to global users,
  but is expensive and hard: data replication across regions is slow, so you face consistency
  choices (chapter 10), plus double infrastructure. Do it when the availability target or the
  user geography demands it.

> **Say it like this:** "I'd run everything across three availability zones in one region —
> that covers a data-center failure cheaply. Multi-region only if we need to survive a full
> region outage or serve users on other continents with low latency, because cross-region
> replication forces consistency trade-offs."

## 03.12 Deploying without downtime (briefly)

Because the app tier is stateless and runs as many instances, deploys can be gradual:

- **Rolling deploy**: replace instances a few at a time; the load balancer only routes to
  healthy, ready ones.
- **Blue-green**: run the new version (green) alongside the old (blue), then switch traffic at
  once; switch back if something breaks.
- **Canary**: send a small percentage of traffic (1–5%) to the new version, compare error rates
  and latency, then expand.

The requirement that makes all of these possible: old and new versions must run side by side
safely, so database schema changes must be **backward compatible** (add a column first, deploy
code that uses it, remove old things later). Chapter 14 covers this in more depth.

## 03.13 Config and environments

### Environments

A typical pipeline has several copies of the system:

| Environment | Purpose | Data |
|---|---|---|
| **dev / local** | engineers build and try things | fake or small sample data |
| **test / CI** | automated tests on every change | generated test data |
| **staging (stage)** | production-like rehearsal before release | production-like, often anonymized |
| **production (prod)** | real users | real data |

The closer staging is to prod (same versions, configs, topology), the more bugs it catches. It is
rarely identical at full scale, which is why canaries in prod are still needed.

### Configuration

**Config** is everything that differs between environments or changes without code changes:
database URLs, credentials, feature flags, timeouts, pool sizes.

- **Keep config out of the code and the image.** The same build artifact (container image) moves
  from staging to prod unchanged; only injected config differs. That guarantees you test what you
  ship.
- **Secrets** (passwords, API keys) go in a secrets manager or the orchestrator's secret store —
  never in source control (chapter 15).
- **Feature flags** let you deploy code switched off and enable it gradually or instantly turn it
  off — decoupling deploy from release.
- Treat config changes like code changes: reviewed, versioned, rolled out gradually. A bad
  config push is one of the most common causes of large outages.

> **Common mistake:** Building a separate image per environment ("the prod build"). Then staging
> tested a different artifact than the one in prod. Build once, promote the same artifact, inject
> config per environment.

## 03.14 Misconceptions worth clearing up

- **"Stateless means no state."** It means state lives outside the app servers, in systems
  designed for it.
- **"Horizontal scaling is always better."** For databases, vertical scaling plus replicas is often
  simpler and enough for a long time.
- **"Event loops are faster."** They handle more concurrent waiting cheaply; per-request CPU work is
  no faster, and blocking the loop is catastrophic.
- **"More threads / bigger pools = more throughput."** Only until the real bottleneck (database,
  CPU, a downstream service) saturates; beyond that it adds contention.
- **"Kubernetes makes my app scalable."** It schedules and restarts containers. Scalability comes
  from your app being stateless and your data tier being designed for load.
- **"Serverless is always cheaper."** Cheaper for spiky or low traffic; often more expensive for
  high, steady traffic.

## 03.15 Recap

- A process has isolated memory; threads share memory within a process and are cheaper but need
  synchronization.
- Most web requests are I/O-bound: thread-per-request is simple but exhausts pools under slow
  dependencies; event loops and virtual threads keep many requests in flight cheaply.
- Connection pools avoid per-request connection setup; total connections = pool size x instances,
  and bigger pools do not fix a slow database.
- Keep the app tier stateless; put sessions, files, and data in shared stores.
- Scale stateless tiers out; scale databases up (plus replicas) as long as possible.
- Evolution: 1 server → separate DB → LB + N app servers → cache → read replicas → CDN → queue +
  workers → shards. Each step is justified by a specific wall.
- Find SPOFs by asking "if this dies, what happens?" for every box.
- Containers package app + dependencies; Kubernetes keeps declared state (deployments of pods,
  services as stable names, autoscalers, probes).
- Serverless: no servers, scale to zero, but cold starts, limits, and cost at steady high load.
- Multi-AZ is the production baseline; multi-region is a deliberate, costly choice.
- Build once, promote the same artifact through environments, inject config and secrets.

## 03.16 Check yourself

1. An app server uses a 200-thread pool. Normally it serves 1,000 req/s at 50 ms. A dependency
   slows to 500 ms. What happens, and what are two ways to make the service survive it?

<details><summary>Answer</summary>

By Little's Law, needed concurrency goes from 1,000 x 0.05 = 50 to 1,000 x 0.5 = 500 in-flight
requests, beyond the 200 threads. Requests queue, latency explodes, and the service looks down
while CPU is idle. Fixes: timeouts and a circuit breaker on the slow dependency so threads are
released quickly (chapter 13); and/or non-blocking I/O or virtual threads so waiting requests do
not each hold an OS thread. Bulkheads (separate pools per dependency) stop one slow dependency
from starving everything.

</details>

2. Why is a stateless app tier the default, and where does session data go?

<details><summary>Answer</summary>

Any instance can serve any request, so you can add/remove instances, survive crashes, and do
rolling deploys without losing user state or needing sticky sessions. Sessions go in a shared
session store (fast key-value store/cache) or in a signed token held by the client; durable data
in the database, files in object storage.

</details>

3. You have 40 app instances, each with a database pool of 25. The database starts struggling
   with connections. What is going on and what do you do?

<details><summary>Answer</summary>

Total connections = 40 x 25 = 1,000, which can exceed what the database handles efficiently (each
connection costs database memory, and too many concurrent queries contend for CPU and locks).
Reduce per-instance pool sizes, use a connection pooler/proxy in front of the database to
multiplex, and fix slow queries so connections are held for less time.

</details>

4. Walk through the evolution of a web system from one server to a sharded database, naming the
   wall that triggers each step.

<details><summary>Answer</summary>

One server (app and DB fight for resources, SPOF) → separate DB (single app server CPU and SPOF) →
LB + N stateless app servers (database read load) → cache (remaining reads, DB SPOF) → primary +
read replicas with failover (static content bandwidth and far-away latency) → CDN + object storage
(slow synchronous side effects) → queue + workers (writes/data size exceed one primary) → shard the
database by a shard key.

</details>

5. What is the difference between a Kubernetes Pod, Deployment, and Service?

<details><summary>Answer</summary>

A Pod is the smallest scheduled unit: one or a few containers sharing a network address. A
Deployment declares "keep N identical pods of this image running" and performs rolling updates and
rollbacks. A Service gives a stable name/virtual address that load-balances across whichever pods
currently back it, since individual pods come and go with changing IPs.

</details>

6. When is serverless a good fit, and when is it a poor one?

<details><summary>Answer</summary>

Good: spiky or low-volume traffic, event-driven glue (process an uploaded file, react to a queue
message), when you want zero server management and scale-to-zero. Poor: high steady traffic (often
costlier than servers), latency-sensitive paths that cannot tolerate cold starts, long-running or
stateful work, and workloads that would open too many database connections.

</details>

7. Why is multi-AZ the default but multi-region a deliberate decision?

<details><summary>Answer</summary>

AZs are close (around 1–2 ms), so replication can be synchronous and failover is cheap; it protects
against a data-center failure at modest cost. Regions are far apart (tens to hundreds of ms), so
replication is asynchronous or slow, forcing consistency trade-offs and conflict handling, and you
pay for duplicate infrastructure. You choose it only when availability requirements or global user
latency justify it.

</details>

8. Why should the same container image be promoted from staging to production rather than
   rebuilt?

<details><summary>Answer</summary>

So that what you tested is exactly what you ship. Rebuilding can pull different dependency
versions or build settings. Environment differences belong in injected configuration and secrets,
not in the artifact.

</details>
