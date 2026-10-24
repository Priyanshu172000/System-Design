# 13 — Monoliths, microservices and staying up when things fail

This chapter has two halves that belong together. First: how to split (or not split) a system
into services, and what that really costs. Second: once services call each other over a
network, every call can be slow or fail, so you need timeouts, retries, circuit breakers,
bulkheads and sagas to stop one failure from taking everything down. These are the words
senior engineers use when they review a design, and each one exists because of a specific
failure story.

## 13.1 Three ways to structure an application

### Monolith

A **monolith** is one deployable application that contains all features: users, orders,
payments, search. One codebase, one build, one process (run as many identical copies behind a
load balancer), usually one database.

```text
          +-------------------------------------------+
          |              monolith (one deploy)        |
          |  users | orders | payments | search | ... |   function calls between modules
          +---------------------+---------------------+
                                |
                         +------v------+
                         |  one DB     |
                         +-------------+
```

Calls between features are in-process function calls: nanoseconds, never "partially fail", and
a single database transaction can update orders and payments atomically.

### Modular monolith

A **modular monolith** is still one deployable unit, but the code is split into modules with
strict boundaries: each module owns its tables, exposes a small public interface, and other
modules may not reach into its internals. Boundaries are enforced by build rules or package
visibility. It gets most of the code-organization benefits of microservices without the network.

### Microservices

**Microservices** split the system into separately deployable services, each owning one
business capability and its own data, communicating over the network (HTTP/gRPC calls or
messages).

```text
   +-----------+     +-----------+     +-----------+     +-----------+
   |  user     |     |  order    |---->|  payment  |     |  search   |
   |  service  |     |  service  |     |  service  |     |  service  |
   +-----+-----+     +-----+-----+     +-----+-----+     +-----+-----+
         |                 |                 |                 |
     [user DB]        [order DB]       [payment DB]      [search index]
```

### Honest trade-off table

| | Monolith | Modular monolith | Microservices |
|---|---|---|---|
| Deploy | One unit; whole app redeployed | One unit | Each service independently |
| Calls between parts | In-process, fast, reliable | In-process | Network: slow-ish, can fail, can time out |
| Transactions | Single DB transaction | Single DB transaction (if modules share the DB server) | No cross-service ACID; need sagas |
| Scaling | Scale the whole app together | Whole app | Scale each service separately |
| Team autonomy | Low at large size: everyone touches one codebase and release | Medium | High: team owns service end to end |
| Tech flexibility | One stack | One stack | Each service can choose (a cost too) |
| Debugging | One stack trace | One stack trace | Distributed tracing needed (chapter 14) |
| Operational cost | One thing to run | One thing to run | Many deploys, dashboards, on-call rotations, a platform team |
| Failure isolation | A memory leak in one feature can crash all | Same | A crash is contained to one service (if you design for it) |
| Best for | Small teams, early products | Most companies for a long time | Many teams, clear domains, uneven scaling needs |

### When to split

Split a piece out when there is a concrete reason:

- **Team boundaries**: several teams step on each other in one codebase and one release train.
  Services let each team deploy on its own schedule. This is the strongest reason; the
  architecture tends to mirror the communication structure of the organization (often called
  **Conway's law**).
- **Independent scaling**: image processing needs 200 CPU-heavy machines; the rest of the app
  needs 10. Splitting lets you scale only the hot part.
- **Independent deploy cadence or risk**: payments changes need heavy review; the marketing
  page changes ten times a day.
- **Different technical needs**: one component needs a GPU, a different runtime, or strict
  isolation for security or compliance.

### What it costs

- **Network calls**: a function call (nanoseconds) becomes a remote call (roughly 0.5-5 ms
  within a data center, chapter 04) that can be slow, fail, or succeed without you hearing
  back.
- **Distributed data**: no joins across services, no single transaction; consistency becomes
  eventual and needs sagas (13.10).
- **Operations**: every service needs CI/CD, monitoring, alerts, logs, on-call, capacity
  planning, security patches.
- **Testing**: integration tests span several services and versions.
- **Versioning**: changing an API now means coordinating with every caller.

> **Say it like this:** "I'd start with a modular monolith and split out a service only when
> there's a real driver — a separate team, a component with very different scaling, or a
> different deploy risk. Microservices buy team autonomy and independent scaling, but you pay
> with network failures, distributed data, and a lot of operational overhead."

> **Common mistake:** The **distributed monolith**: services that must be deployed together,
> share one database, or call each other synchronously in long chains. You pay all the costs of
> microservices and get none of the independence.

## 13.2 Service boundaries and database-per-service

A good service boundary follows a **business capability** (orders, payments, catalog,
notifications), not a technical layer ("the database service", "the validation service"). A
helpful test: can most features be built by changing just one service? If almost every feature
touches five services, the boundaries are wrong.

**Database per service** means each service owns its data, and no other service reads or
writes those tables directly. Others must go through the service's API or consume its events.

```text
  WRONG: shared database                         RIGHT: database per service

  order svc   payment svc                         order svc --API/events--> payment svc
      \          /                                    |                         |
       [ one shared DB ]                          [order DB]               [payment DB]
  payment team renames a column ->                payment team can change its schema
  order service breaks in production              freely behind its API
```

Why: a shared database is a hidden, untyped, unversioned API. Any schema change can break
another team, and one service's heavy query can slow everyone. Owning the data is what makes
independent deployment real.

What you pay: queries that used to be a SQL join ("orders with customer names") now need API
composition (13.12) or a locally kept copy of the other service's data, updated from its
events. That copy is eventually consistent. "Database per service" is about ownership; several
services' schemas may still sit on one physical database server to save cost, as long as they
do not touch each other's tables.

## 13.3 How services talk: sync vs async

**Synchronous** communication: the caller sends a request and waits for the response.
- **REST over HTTP** with JSON: universal, easy to debug, human readable.
- **gRPC**: binary Protocol Buffers over HTTP/2; typed contracts, code generation, streaming,
  smaller and faster to parse. Common for internal calls (chapter 02).

**Asynchronous** communication: the caller publishes a message or event and does not wait.
- **Events** via a log or broker like Kafka (chapter 11): "OrderPlaced {id: 42}". Any number
  of services consume it whenever they are ready.
- **Commands via queues**: "SendEmail to x" placed on a queue for one worker.

```text
 sync:   order --POST /charge--> payment    order waits; payment down -> order fails now

 async:  order --"OrderPlaced"--> [ Kafka ] --> payment   (consumes when ready)
                                            --> email     (consumes when ready)
                                            --> analytics (consumes when ready)
```

| | Synchronous (REST/gRPC) | Asynchronous (events/queues) |
|---|---|---|
| Caller needs an answer now? | Yes, natural fit | No — "accepted, will happen" |
| Coupling in time | Both must be up at the same moment | Consumer can be down; messages wait |
| Latency for the user | Sum of the call chain | User gets a fast ack; work happens later |
| Failure handling | Timeouts, retries, circuit breakers | Retries from the log, dead-letter queues |
| Consistency | Immediate (when it works) | Eventual |
| Debugging | Easier: one request, one trace | Harder: flow is spread over time |
| Adding a new consumer | Caller must change to call it | Just subscribe to the event |

Rule of thumb: use sync when the user is waiting for the answer and it is needed to continue
("is this card valid?"); use async for side effects that can happen later ("send receipt",
"update search index", "award loyalty points"). Long synchronous chains (A calls B calls C
calls D) multiply latency and failure probability: if each of 4 services is 99.9% available
and all must succeed, the chain is about 99.6% available (chapter 14 does this math).

## 13.4 The cascading failure story

This is the story behind every resilience pattern in this chapter. Read it slowly.

Setup: the API service handles 1,000 requests per second. Each request calls the
recommendations service, which normally answers in 50 ms. The API service uses a thread pool of
200 threads; each thread handles one request at a time and blocks while waiting for a remote
call.

How many threads are busy normally? Use **Little's law**: average number in the system =
arrival rate x average time in the system.

```text
 normal:   1,000 req/s x 0.05 s = 50 threads busy  (out of 200: fine)
```

Now the recommendations database gets a slow query and recommendations starts taking 5 seconds
to answer. Nothing has "failed" — it is just slow. The API service has no timeout.

```text
 degraded: 1,000 req/s x 5 s = 5,000 threads needed   (only 200 exist)

 t=0.0s   recommendations latency jumps 50 ms -> 5 s
 t=0.2s   all 200 API threads are blocked waiting on recommendations
 t=0.2s+  new requests queue up, then time out at the load balancer
          -> requests that do NOT need recommendations (login, checkout) fail too
 t=1s     load balancer health checks to API time out -> API instances marked unhealthy
 t=2s     remaining instances get all the traffic -> they lock up faster
          clients and upstream services retry -> load multiplies (retry storm)
          services that call the API now block on IT -> failure spreads upward
```

```text
 users --> [ gateway ] --> [ API: 200 threads ] --> [ recommendations ] --> [ DB ]
               ^             ALL threads stuck           5 s per call         slow
               |             waiting here <-------------------------------------+
        gateway threads also pile up waiting on API ... checkout, login: all down
```

One slow, non-critical dependency took down the whole product. That is a **cascading failure**:
a failure in one component that spreads to others through shared resources (threads,
connections, memory) and retries.

The lessons, each a pattern below:

1. Never wait forever: **timeouts** (13.5).
2. Do not make it worse by hammering: **retries with backoff, jitter and budgets** (13.6).
3. Stop calling something that is clearly broken: **circuit breakers** (13.7).
4. Do not let one dependency consume all your threads: **bulkheads** (13.8).
5. Have a plan B: **fallbacks and graceful degradation** (13.9).
6. Reject what you cannot handle: **load shedding and backpressure** (13.9).

> **Say it like this:** "Slow is worse than down. A dependency that hangs ties up our threads,
> and by Little's law a 100x latency increase needs 100x the threads, so we exhaust the pool
> and fail requests that never needed that dependency. So every remote call gets a timeout, a
> bounded pool, and a fallback."

## 13.5 Timeouts

A **timeout** is the maximum time you will wait for a remote call before giving up. Every
network call needs one; many libraries default to "no timeout" or to very long ones.

Kinds you will hear about:

- **Connect timeout**: how long to wait to establish the TCP connection. Short (roughly
  100 ms to 1 s inside a data center) — if the host is not answering, it is probably gone.
- **Read / request timeout**: how long to wait for the response once connected.

How to pick a request timeout:

- Look at the dependency's latency distribution. Set the timeout a bit above its normal p99 or
  p99.9 (for example, p99 = 120 ms -> timeout 300 ms). Too low and you fail healthy requests;
  too high and threads pile up as in 13.4.
- Work backwards from the user: if the whole page must load within 1 second, a single internal
  call cannot have a 10-second timeout.
- Account for retries: two attempts of 300 ms each need 600 ms of budget.

**Deadline propagation**: instead of each hop having its own independent timeout, the first
service sets a **deadline** (an absolute time by which the whole request must finish) and
passes the remaining budget downstream with each call (gRPC does this natively; with HTTP a
header carries it). Each service checks: "is there enough time left to be worth doing this?" If
not, it fails immediately instead of doing work the caller has already given up on.

```text
 gateway: deadline = now + 1000 ms
   -> API (spent 100 ms)            passes remaining 900 ms
       -> pricing (spent 300 ms)    passes remaining 600 ms
           -> inventory             sees 600 ms left; uses min(own timeout, 600 ms)
   if remaining <= 0 at any hop: stop immediately, don't start work nobody will use
```

Without propagation you get **orphaned work**: the gateway gave up after 1 second, but deep
services keep grinding for 10 more seconds, consuming capacity for an answer nobody will read.

## 13.6 Retries: backoff, jitter, budgets

Many failures are **transient**: a dropped packet, a server restarting, a brief leader election.
Retrying often succeeds. But naive retries are one of the most common causes of outages.

### Only retry what is safe to retry

Retrying is safe when the operation is **idempotent**: doing it twice has the same effect as
doing it once. `GET`, `PUT` (set to a value), and `DELETE` are idempotent by HTTP semantics.
`POST /charge` is not: if the first attempt actually succeeded but the response was lost, a
retry charges the customer twice. Make non-idempotent operations safe with an **idempotency
key** (chapter 02): the client sends a unique key with the request, and the server stores the
result per key and returns the stored result on a repeat.

Also, only retry errors that can succeed later: timeouts, connection resets, 503, 429 (after
`Retry-After`). Do not retry 400 Bad Request or 403 Forbidden; they will fail identically.

### Exponential backoff with jitter

**Exponential backoff**: wait longer after each failed attempt, doubling each time, up to a cap.

```text
  delay(attempt) = min(cap, base * 2^attempt)        attempt = 0, 1, 2, ...
  base = 100 ms, cap = 10 s  ->  100, 200, 400, 800, 1600 ms, ...
```

Problem: if 10,000 clients all failed at the same moment, they all retry at exactly 100 ms,
then 200 ms, then 400 ms — synchronized waves that hit the recovering server together.
**Jitter** adds randomness to spread them out. The most common form, **full jitter**:

```text
  sleep = random_between(0, min(cap, base * 2^attempt))
```

```text
 no jitter:    |||||           |||||                  |||||          spikes of retries
 full jitter:  | || |  ||| | |  | ||  |  | || |  | |  ||  | |        spread out smoothly
```

Always cap the number of attempts (for example 3 total) and respect the overall deadline.

### Retry storms and amplification

Retries multiply across layers. Suppose a request flows through 3 layers, and each layer tries
up to 3 times (1 attempt + 2 retries):

```text
  gateway (3 tries) -> API (3 tries each) -> database client (3 tries each)

  worst case at the bottom layer: 3 x 3 x 3 = 27 attempts for ONE user request
```

When the bottom layer is struggling because it is overloaded, retries multiply its load by 27x
exactly when it can least afford it. That is a **retry storm**, and it can keep a system down
long after the original cause has gone.

Defenses:

- **Retry at one layer only**, usually the one closest to the failure or the edge — not at
  every layer.
- **Retry budgets**: limit retries to a fraction of normal traffic, for example "retries may be
  at most 10% of requests over the last 10 seconds". When the dependency is broadly failing,
  the budget runs out and you stop amplifying; when failures are rare and transient, retries
  still help.
- **Circuit breakers** (next section) stop calls entirely when failure is widespread.
- **Honor Retry-After** and 429/503 signals from the server.

> **Common mistake:** "We'll add retries to make it more reliable." Retries help with rare,
> independent, transient failures. During overload they make things worse. Always pair retries
> with backoff, jitter, a cap, a budget, and idempotency.

## 13.7 Circuit breakers

A **circuit breaker** wraps calls to a dependency and tracks recent failures. When failures
cross a threshold, it "opens" and fails calls immediately, without even trying, for a cool-down
period. This protects your own threads (no waiting on a dead service) and gives the dependency
breathing room to recover. The name comes from the electrical breaker that cuts power before
wires overheat.

```text
                    failure rate over threshold
                    (e.g. >50% of last 100 calls,
                     min 20 calls in window)
        +-----------+ ------------------------------> +------------+
        |  CLOSED   |                                 |    OPEN    |<------+
        | calls go  |                                 | calls fail |       |
        | through,  |                                 | fast (or   |       |
        | failures  |                                 | fallback)  |       |
        | counted   |                                 +-----+------+       |
        +-----^-----+                                       |              |
              |                                             | cool-down    | a trial call
              | trial calls succeed                         | expires      | fails (timer
              |                                             | (e.g. 30 s)  | restarts)
              |                                       +-----v------+       |
              +---------------------------------------| HALF-OPEN  |-------+
                                                      | a few trial|
                                                      | calls pass |
                                                      +------------+
```

- **Closed** (normal): calls pass through; successes and failures are recorded in a sliding
  window (count-based, e.g. last 100 calls, or time-based, e.g. last 10 seconds). Slow calls
  above a threshold can count as failures too.
- **Open**: calls are rejected immediately with an error or served by a fallback. No load
  reaches the dependency.
- **Half-open**: after the cool-down, allow a small number of trial calls. If they succeed, go
  back to closed. If they fail, go back to open and wait again.

A minimum number of calls in the window (e.g. at least 20) prevents opening on "1 failure out
of 1 call". Libraries such as Resilience4j implement this; service meshes offer a similar idea
via outlier detection (ejecting single bad instances rather than a whole dependency).

Trade-off: during the open period, some requests fail that might have succeeded. That is the
price of protecting the system. Choose thresholds per dependency, and always decide what the
caller does when the breaker is open (a fallback).

> **Say it like this:** "I'd wrap the call in a circuit breaker: once more than half the calls
> in the window fail or exceed the slow threshold, it opens and we fail fast with a fallback for
> 30 seconds, then half-open lets a few trial calls through to see if the dependency is back."

## 13.8 Bulkheads

On a ship, **bulkheads** are walls that split the hull into compartments, so one leak floods
one compartment instead of sinking the ship. In software, a **bulkhead** gives each dependency
(or each class of work) its own bounded pool of resources — threads, connections, or a
concurrency limit — so one misbehaving dependency can exhaust only its own share.

```text
 without bulkheads                         with bulkheads
 +--------------------------------+        +----------+ +----------+ +----------+
 | one pool of 200 threads        |        | recs: 30 | | pay: 80  | | user: 60 |
 | recs is slow -> grabs all 200  |        | threads  | | threads  | | threads  |
 | -> checkout can't get a thread |        +----------+ +----------+ +----------+
 +--------------------------------+        recs slow -> its 30 are stuck, extra calls
                                           rejected fast; checkout keeps its 80
```

Replay the 13.4 story with bulkheads: recommendations can occupy at most 30 threads; call 31
is rejected immediately (and served a fallback). Checkout and login keep working. Bulkheads can
also be at bigger scales: separate instance pools for important customers versus free users,
or separate clusters for the public API versus internal batch jobs.

Trade-off: pools sized too small reject work even when the machine has spare capacity; sizing
requires measuring (Little's law again: concurrency = rate x latency, plus headroom).

## 13.9 Fallbacks, graceful degradation, load shedding, backpressure

### Fallbacks and graceful degradation

A **fallback** is what you return when a dependency fails or the breaker is open. **Graceful
degradation** means the product keeps its core function working with reduced features, instead
of failing entirely.

| Dependency down | Fallback |
|---|---|
| Personalized recommendations | Show generic "popular items" from a cache |
| Product reviews | Render the product page without the reviews block |
| Real-time inventory count | Show "in stock" from a slightly stale cached value; verify at checkout |
| Fraud scoring | Allow small orders, queue large orders for review (a business decision) |
| Pricing | No safe fallback: fail the request rather than show a wrong price |

The last row matters: some features have no safe fallback, and making something up is worse
than an error. Decide per dependency, with product owners, before the incident.

Also, fallbacks need testing. A fallback path that never runs in normal operation tends to be
broken exactly when you need it.

### Load shedding

**Load shedding** means deliberately rejecting some requests when the service is near capacity,
so that the requests it does accept finish quickly (chapter 12 contrasts it with rate
limiting). An overloaded server that accepts everything ends up serving everything slowly and
timing most of it out: the useful throughput collapses. Shedding keeps goodput (useful
completed work) high.

Signals for shedding: number of in-flight requests above a limit, queue wait time above a
threshold, CPU saturated. Shed lowest-priority work first, and shed as early as possible
(before parsing the body or touching the database). Return 503 with `Retry-After`.

A related trick: if a request has already been waiting in a queue longer than its caller's
timeout, drop it without processing; the caller has already given up.

### Backpressure

**Backpressure** is a signal flowing from a slow consumer back to a fast producer saying "slow
down". Without it, a fast producer fills an unbounded queue until memory runs out.

```text
 producer (10k msg/s) --> [ queue ] --> consumer (2k msg/s)
                          unbounded: grows by 8k/s until out of memory
                          bounded + backpressure: queue full -> producer blocks,
                          slows, or gets an error and sheds/defers its own input
```

Forms of backpressure:

- **Bounded queues** that block or reject when full (never use unbounded queues between stages).
- **Flow control** built into protocols: TCP's receive window, HTTP/2 stream windows, reactive
  streams where the consumer requests N items at a time.
- **Pull-based consumption**: Kafka consumers pull at their own pace (chapter 11); the log
  absorbs the difference on disk, and consumer lag is the visible signal.
- **429/503 responses** telling callers to back off.

> **Common mistake:** Adding a bigger queue to "fix" an overloaded service. A queue only
> absorbs short bursts. If arrival rate stays above processing rate, any queue eventually fills,
> and in the meantime latency for everything in it grows without bound.

## 13.10 Distributed transactions: 2PC and sagas

The problem: placing an order must (1) create the order, (2) charge the payment, (3) reserve
inventory, (4) schedule shipping. In a monolith with one database, that is one ACID
transaction (chapter 05): all happen or none do. With database-per-service, these are four
different databases. How do you get all-or-nothing?

### Two-phase commit (2PC), and why it is avoided

**Two-phase commit** uses a **coordinator**:

```text
 Phase 1 (prepare):  coordinator -> each participant: "can you commit?"
                     each participant writes its changes durably, LOCKS the rows,
                     and votes YES or NO
 Phase 2 (commit):   if all voted YES: coordinator -> all: "commit"
                     if any voted NO:  coordinator -> all: "abort"

   coordinator         order DB        payment DB      inventory DB
       | --prepare-->     |                |                |
       | --prepare-------------------->    |                |
       | --prepare------------------------------------->    |
       | <-- yes ---      | <-- yes ---    | <-- yes ---    |   rows now locked
       X  coordinator crashes here
                          participants: voted yes, can't commit or abort on their own,
                          locks held until coordinator recovers  -> BLOCKED
```

Why it is avoided across microservices:

- **Blocking**: a participant that voted yes must wait for the coordinator's decision. If the
  coordinator crashes, participants hold locks indefinitely; other transactions touching those
  rows stall.
- **Latency and availability**: every transaction waits for the slowest participant, and the
  whole operation is only as available as all participants together.
- **Coupling and support**: all participants must support the same protocol (XA); many modern
  stores, message brokers and third-party APIs (a payment provider) do not.

2PC (and consensus-based variants) is still used inside a single database system across its own
shards, where one team controls everything (chapter 10). Across independently owned services,
the common answer is the saga.

### The saga pattern

A **saga** is a sequence of local transactions, one per service. Each step commits on its own.
If a later step fails, the saga runs **compensating actions** — new transactions that
semantically undo the earlier steps — in reverse order.

Important: a compensation is not a rollback. The earlier step really committed and may have
been visible (an email sent, a card charged). Compensation is a business action: "refund the
charge", "release the reservation", "mark order cancelled".

### Worked example with a failure

Order flow: create order -> charge payment -> reserve inventory -> schedule shipping.
Inventory fails because the item is out of stock.

```text
 step  service     action (local transaction)          compensation
 T1    order       create order, status=PENDING        C1: mark order CANCELLED
 T2    payment     charge card 499.00                  C2: refund 499.00
 T3    inventory   reserve 1 x SKU-77   --> FAILS (out of stock)
 T4    shipping    schedule shipment    (never runs)

 execution:   T1 ok -> T2 ok -> T3 FAIL -> C2 (refund) -> C1 (cancel order)
 final state: order CANCELLED, money refunded, no stock reserved, customer notified
```

Design tip: order the steps so the hardest-to-compensate actions happen last. Many systems
reserve inventory first and **authorize** the card (hold funds) before **capturing** it, so the
compensation is "release hold" rather than a refund. Steps that cannot be undone (shipping a
parcel, sending an email) go at the end, after everything that might fail. A step after which
the saga can no longer fail and must run forward to completion is sometimes called the **pivot**.

### Choreography vs orchestration

**Choreography**: no central controller. Each service listens for events and reacts, emitting
its own events.

```text
 order svc --OrderCreated--> payment svc --PaymentCharged--> inventory svc
                                                               | OutOfStock
     ^                       payment svc <----OutOfStock-------+
     |                          | refunds, emits PaymentRefunded
     +------PaymentRefunded-----+
 order svc marks order CANCELLED
```

**Orchestration**: a central **orchestrator** (a service or workflow engine) tells each service
what to do and tracks the saga's state.

```text
                    +--------------------------+
                    |  order saga orchestrator  |  state: T1 ok, T2 ok, T3 failed,
                    +--+------+------+------+---+          running C2, C1 ...
                       |      |      |      |
            1.create   |  2.charge   |  3.reserve      on failure: 4.refund, 5.cancel
                       v      v      v
                    order  payment  inventory   (each replies success/failure)
```

| | Choreography | Orchestration |
|---|---|---|
| Control | Distributed; each service reacts to events | Central orchestrator drives steps |
| Coupling | Services know event types, not each other | Orchestrator knows every participant |
| Seeing the flow | Hard: logic spread across services | Easy: one place shows the whole workflow and its state |
| Adding steps | Easy for small flows; gets tangled as flows grow | Change the orchestrator |
| Risk | Cyclic event dependencies, "who handles failure?" confusion | Orchestrator becomes a bottleneck or a god-service |
| Fits | Simple flows with 2-4 steps | Complex flows, many steps, timeouts, human approvals |

Making sagas reliable:

- Each step's local DB update and its outgoing event must happen atomically, or a crash
  between them loses the event. Use the **transactional outbox** (chapter 11).
- Messages may be delivered more than once, so every step and every compensation must be
  **idempotent**.
- Compensations must eventually succeed; retry them, and alert humans if they keep failing.
- Sagas give no **isolation**: other requests can see intermediate states (an order that is
  PENDING, a charge about to be refunded). Handle it with explicit states ("PENDING" means "do
  not ship yet"), sometimes called **semantic locks**, and by designing the UI around them.

> **Say it like this:** "Across services I'd avoid 2PC because a crashed coordinator leaves
> participants blocked holding locks. I'd use a saga: each service commits locally, and on
> failure we run compensations in reverse — refund, release, cancel. For a flow this size I'd
> orchestrate it so the state is visible in one place, with an outbox and idempotent steps."

## 13.11 Idempotency across services

In a distributed system you get **at-least-once** delivery almost everywhere: HTTP retries,
message redelivery after a consumer crash, a saga step re-run after the orchestrator restarts.
So every handler should be safe to run twice.

Techniques:

- **Idempotency keys** on API calls (chapter 02): `Idempotency-Key: 7f3a...`. The server stores
  `key -> result` and returns the stored result for duplicates. Store the key in the same
  transaction as the side effect.
- **Processed-message table** for consumers: before handling message `m-123`, insert its ID
  into a `processed_messages` table with a unique constraint, in the same transaction as the
  business update. A duplicate fails the insert and is skipped.
- **Natural idempotency**: design operations as "set" rather than "add": `status = SHIPPED`
  instead of `count = count + 1`; "reserve for order 42" (unique on order ID) instead of
  "reserve one more".
- **Version checks**: "update only if version = 7" (optimistic concurrency) so a stale replay
  has no effect.

Pass the same key through the whole chain: the saga orchestrator uses `order-42-charge` as the
idempotency key for the payment call, so its own retries never double-charge.

## 13.12 API composition and backend for frontend (BFF)

### API composition

With data split across services, a screen like "order details" needs data from orders, users,
products, and shipping. **API composition** means a component (a gateway, a composer service, or
the BFF below) calls each service, ideally in parallel, and merges the results.

```text
 client --GET /order-page/42--> [ composer ]
                                   |--> order svc     (order 42)          \
                                   |--> user svc      (customer name)      } in parallel
                                   |--> product svc   (item details)       } total latency
                                   |--> shipping svc  (tracking status)   / ~ slowest call
                                   merge -> one JSON response
```

Trade-offs: latency is bounded by the slowest call; availability depends on all of them (use
timeouts and partial responses — show the page without tracking if shipping is slow). For heavy
query needs (search, reporting across services), composition breaks down; instead build a
**read model**: a separate store that subscribes to events from many services and keeps a
denormalized view ready to query (this is the CQRS idea: separate the write model from read
models).

### Backend for frontend (BFF)

Different clients need different shapes: the mobile app wants a small payload in one round trip
over a slow network; the web app wants richer data; a partner API wants stability above all. A
single general-purpose API serves all of them poorly.

A **backend for frontend** is a thin backend service per client type, owned by (or close to) the
team building that client. It composes calls to downstream services and returns exactly what
that client's screens need.

```text
 iOS / Android app --> [ mobile BFF ] --+
 web app           --> [ web BFF ]    --+--> user, order, product, shipping services
 partners          --> [ public API ] --+
```

Gains: client-specific optimization, client teams can move fast without negotiating changes to
a shared API. Costs: more services, risk of duplicated logic across BFFs. Keep BFFs thin:
composition and shaping only, with business rules staying in domain services.

## 13.13 The strangler fig migration

You have a large legacy monolith and want to move to services. A "big-bang rewrite" (build the
new system in parallel for two years, then switch over) is famously risky: the old system keeps
changing, the new one never catches up, and cutover day is a cliff.

The **strangler fig** pattern (named after a vine that grows around a tree and gradually
replaces it) migrates piece by piece:

```text
 step 1: put a routing layer (proxy / gateway) in front of the monolith
           clients --> [ router ] --> monolith (handles everything)

 step 2: build one capability as a new service; route just those paths to it
           clients --> [ router ] --/api/invoices--> invoice service (new)
                                  --everything else--> monolith

 step 3: repeat capability by capability; delete the dead code in the monolith
           clients --> [ router ] --> invoice | users | search | ... (monolith shrinks)

 step 4: monolith is empty (or a small, stable core) -> retire it
```

Keys to success: migrate the data ownership too (not just the code), run old and new side by
side and compare results (shadow traffic, chapter 14), route with a flag so you can switch back
instantly, and pick early slices that are valuable but loosely coupled. Each step is shippable
and reversible.

## 13.14 Seeing what happens: distributed tracing

In a monolith, one stack trace shows the whole request. With microservices, one user click may
touch 15 services, and "why was this request slow?" has no single log to look at. **Distributed
tracing** assigns each request a trace ID, propagates it through every call and message, and
records the timing of each step (**spans**), so you can see the whole request as one timeline.
Chapter 14 explains traces, spans, correlation IDs and context propagation in detail. Treat
tracing as a prerequisite for microservices, not an optional extra.

## 13.15 Putting it together: one resilient call

A well-protected call to a dependency stacks these patterns, roughly from outside in:

```text
 incoming request
   -> load shedding / admission control (am I overloaded? shed low priority)
   -> deadline check (enough time budget left?)
   -> bulkhead (acquire a slot from this dependency's pool; full -> fallback)
   -> circuit breaker (open -> fallback immediately)
   -> timeout = min(per-call timeout, remaining deadline)
   -> retry with exponential backoff + jitter, within retry budget, idempotent ops only
   -> on final failure: fallback / graceful degradation / clear error
```

Each layer has a reason, and each was learned from an outage like the one in 13.4.

## 13.16 Recap

- Monolith, modular monolith, microservices: choose by team structure and scaling needs, not
  fashion; a modular monolith is the right default for a long time.
- Microservices trade in-process calls and ACID transactions for independent deploys, scaling
  and ownership — and pay with network failures, distributed data and ops overhead.
- Boundaries follow business capabilities; each service owns its database; avoid the
  distributed monolith.
- Sync calls when the user needs the answer now; async events for side effects and decoupling.
- Slow dependencies cause cascading failures by exhausting shared threads (Little's law).
- Every remote call needs a timeout; propagate deadlines so no one works for a caller who gave up.
- Retry only idempotent operations and transient errors, with exponential backoff, full jitter,
  a cap, and a retry budget; retries at every layer multiply into storms.
- Circuit breaker: closed -> open on high failure rate -> half-open trial calls -> closed or open.
- Bulkheads isolate resources per dependency; fallbacks keep the core working; load shedding and
  backpressure keep goodput up under overload.
- Avoid 2PC across services (blocking coordinator); use sagas with compensations, choreographed
  or orchestrated, plus outbox and idempotent steps.
- API composition and BFFs shape data for clients; strangler fig migrates a monolith safely.

## 13.17 Check yourself

1. A service handles 400 requests/second, each making one call to a dependency with 25 ms
   latency. The dependency slows to 2 seconds. How many concurrent calls are now in flight, and
   why does this matter?

<details><summary>Answer</summary>

By Little's law: before, 400 x 0.025 = 10 concurrent calls. After, 400 x 2 = 800 concurrent
calls. If the service has, say, a 200-thread pool, it is exhausted and all requests fail,
including ones that do not need this dependency. This is why you need timeouts and bulkheads.

</details>

2. Three layers each retry up to 3 total attempts. The bottom service is failing all requests.
   How many attempts reach it per user request, and what are two defenses?

<details><summary>Answer</summary>

3 x 3 x 3 = 27 attempts per user request. Defenses: retry at only one layer; retry budgets
(retries capped at a small fraction of normal traffic); circuit breakers that stop calling a
failing dependency; exponential backoff with jitter; honoring Retry-After.

</details>

3. Write the full-jitter backoff formula and explain what the jitter is for.

<details><summary>Answer</summary>

sleep = random(0, min(cap, base * 2^attempt)). Without jitter, many clients that failed together
retry at exactly the same moments, producing synchronized spikes that can knock over a
recovering service. Randomizing the delay spreads retries over time.

</details>

4. Describe the circuit breaker's three states and what moves it between them.

<details><summary>Answer</summary>

Closed: calls pass through, failures counted in a window. When the failure (or slow-call) rate
exceeds a threshold with enough calls in the window, it moves to Open: calls fail fast or use a
fallback, with no load on the dependency. After a cool-down it moves to Half-open: a few trial
calls are allowed. If they succeed it returns to Closed; if they fail it returns to Open and the
cool-down restarts.

</details>

5. Why is two-phase commit a poor fit across independently owned microservices?

<details><summary>Answer</summary>

Participants that voted "yes" in the prepare phase must hold locks and wait for the coordinator's
decision; if the coordinator crashes, they are blocked indefinitely. It also ties every
transaction's latency and availability to the slowest/least available participant, and many
services, brokers and external APIs do not support the protocol at all.

</details>

6. In a saga of order -> payment -> inventory -> shipping, inventory fails. What runs, in what
   order, and what properties must those actions have?

<details><summary>Answer</summary>

Compensations run in reverse for the steps that completed: refund (or release the authorization
hold on) the payment, then cancel the order. Shipping never ran. Compensations must be
idempotent (they may be delivered or retried more than once) and must eventually succeed (retry,
then alert a human). The saga provides no isolation, so intermediate states like PENDING must
be handled explicitly.

</details>

7. When would you choose orchestration over choreography for a saga?

<details><summary>Answer</summary>

When the flow has many steps, branches, timeouts or human approvals, or when you need to see and
debug the state of each saga in one place. Choreography fits short, simple flows; as they grow,
logic spread across services with event chains becomes hard to follow and reason about.

</details>

8. Your team wants to move off a 10-year-old monolith. Outline a safe approach.

<details><summary>Answer</summary>

Strangler fig: put a routing layer in front of the monolith; extract one well-bounded,
valuable capability into a new service along with ownership of its data; route its paths to the
new service behind a switch that can be flipped back; validate with shadow traffic or comparison;
remove the dead code from the monolith; repeat. Avoid a big-bang rewrite.

</details>
