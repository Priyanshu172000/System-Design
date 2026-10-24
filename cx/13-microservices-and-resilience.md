# 13 — Microservices and staying up when things fail
*Why it matters:* once services call each other over a network every call can be slow or fail,
and timeouts, retries, breakers and sagas are the vocabulary of every senior design review.

## Monolith, modular monolith, microservices
**How it works** · A **monolith** is one deployable app with all features and usually one DB;
calls are in-process (nanoseconds, never partial) and one ACID transaction covers everything. A
**modular monolith** is still one deploy, but modules own their tables behind small interfaces,
enforced by build rules. **Microservices** are separately deployable services, each owning one
business capability and its data, talking over HTTP/gRPC or messages.

**Example** · QuickBite's "Place order" both ways.
1. Monolith: `orders` and `payments` rows for `o_90311` commit in one DB transaction; a memory
   leak in the menu module can still crash the whole process.
2. Microservices: `order-service` calls `payment-service` over the network (roughly 0.5-5 ms);
   it can time out, or charge and lose the reply; no shared transaction. Worth it when many
   teams share one release train and `rider-service` must take ~25,000 writes/s at peak.

So: microservices buy independent deploys, scaling, ownership and crash containment; they pay
with tracing, many deploys, on-call rotations and a platform team. Monolith suits small teams;
a modular monolith suits most companies for a long time.
**Split when** there is a concrete driver: **team boundaries** (strongest; **Conway's law**),
independent scaling (200 CPU-heavy image boxes vs 10 for the rest), different deploy risk
(payments vs marketing page), different technical needs (GPU, runtime, compliance). **It
costs**: network calls that can fail or succeed silently, no joins or shared transactions,
per-service ops, cross-service testing, and API versioning with every caller.

> **Common mistake:** the **distributed monolith**: services deployed together, sharing one DB,
> or calling each other in long sync chains. All the costs, none of the independence.

## Service boundaries and database-per-service
**How it works** · Boundaries follow **business capabilities** (orders, payments), not technical
layers ("the validation service"); most features should touch one service. **Database per
service**: no one else reads or writes your tables; they use your API or events.

**Example** · Asha's order history shows restaurant names. Shared DB: `order-service` joins
`restaurants.name`; the restaurant team renames it to `display_name` and order history breaks in
production (a shared DB is a hidden, untyped, unversioned API; one heavy query slows everyone).
DB per service: `order-service` keeps a local `restaurant_id -> name` copy fed by restaurant
events; a rename of "Spice Route" shows up a moment later.

So: the former join becomes API composition or an eventually consistent local copy. It is about
ownership; schemas may share one physical server.

## Sync vs async communication
**How it works** · **Sync**: caller waits; REST/JSON (universal, readable) or **gRPC** (binary
protobuf over HTTP/2, typed, streaming; common internally). **Async**: an event on Kafka
("OrderPlaced") for any number of consumers, or a command on a queue for one worker.

**Example** · Placing `o_90311`.
1. Sync: `order-service` asks `payment-service` "authorize ₹450?"; Asha waits for the answer.
2. Async: the outbox publishes `OrderPlaced o_90311` to `order-events`; `notification-service`
   (SMS), `search-service` and a loyalty consumer read it whenever ready; a new consumer just
   subscribes, and `order-service` does not change.

So: sync = both up at once, user waits for the chain, immediate consistency, one trace, guarded
by timeouts/retries/breakers. Async = consumer can be down, fast ack, eventual consistency,
log retries and dead-letter queues, harder to debug. Long sync chains multiply failure: four
services at 99.9%, all required: 0.999^4 ≈ 99.6%.

## The cascading failure story
**How it works** · A slow dependency holds threads; **Little's law** (in-flight = arrival rate x
time in system) says how many.

**Example** · QuickBite's home-feed API: 1,000 req/s, each calls recommendations (normally
50 ms), 200 blocking threads, no timeout. A slow query makes recommendations take 5 s.

```text
 normal:    1,000/s x 0.05 s =    50 threads busy (of 200)
 degraded:  1,000/s x 5 s    = 5,000 threads needed (200 exist)
 t=0.2s  all 200 threads blocked on recommendations; login and checkout fail too
 t=1s    LB health checks to the API time out -> instances marked unhealthy
 t=2s    survivors get all traffic and lock up faster; clients retry (retry storm);
         callers of the API now block on it -> failure spreads upward
```
So: nothing "failed"; one slow, non-critical dependency took the product down. A **cascading
failure** spreads through shared resources (threads, connections, memory) and retries. Slow is
worse than down: every remote call needs a timeout, a bounded pool, and a fallback.

## Timeouts and deadline propagation
**How it works** · Every network call needs a **timeout**; many libraries default to none.
**Connect timeout** is short (roughly 100 ms to 1 s in a DC). **Request timeout**: a bit above
the dependency's normal p99/p99.9, within the user's budget, leaving room for retries.
**Deadline propagation**: the first service sets an absolute deadline and passes the remaining
budget downstream (gRPC natively; a header over HTTP).

**Example** · Asha's "Place order" with a 1,000 ms deadline set at `api-gateway`.
```text
payment-service p99 = 120 ms -> request timeout 300 ms; 2 attempts need 600 ms of budget
api-gateway      deadline = now + 1000 ms
 -> order-service      spent 100 ms, passes 900 ms
   -> restaurant-svc   spent 300 ms (price check), passes 600 ms
     -> payment-svc    timeout = min(300, 600) = 300 ms
if remaining <= 0 at any hop: fail now, start no work nobody will read
```
So: without propagation, **orphaned work**: deep services grind 10 s after the gateway quit at 1 s.

## Retries: backoff, jitter, budgets
**How it works** · Many failures are **transient** (dropped packet, restart, leader election),
but naive retries cause outages. Rules: retry only **idempotent** operations (`GET`, `PUT`,
`DELETE` are; `POST /charge` is not, unless it carries an **idempotency key** whose stored
result the server returns on repeats); retry only errors that can change (timeouts, resets, 503,
429 after `Retry-After`; never 400/403). **Exponential backoff**: `delay = min(cap, base *
2^attempt)`; **full jitter**: `sleep = random(0, min(cap, base * 2^attempt))`; cap attempts.

**Example** · `order-service` charges `o_90311`; base 100 ms, cap 10 s, 3 attempts total.
```text
t=0      POST /charge  Idempotency-Key: o_90311-charge  -> times out at 300 ms
         (payment-service DID charge ₹450; the reply was lost)
t=300    retry 1: ceiling min(10 s, 100 * 2^0) = 100 ms, jitter draws 37 ms
t=337    POST again (same key) -> 503
t=360    retry 2: ceiling 100 * 2^1 = 200 ms, jitter draws 154 ms
t=514    POST again (same key) -> 200, stored result returned: still ONE charge
no jitter: 10,000 clients that failed together all retry at exactly +100, +200, +400 ms;
full jitter spreads each wave evenly over 0-100, 0-200, 0-400 ms
```
So: idempotency makes the retry safe; jitter breaks synchronized waves; cap + deadline bound it.
- **Retry storm**: 3 layers x 3 attempts = 3 x 3 x 3 = **27** attempts on the bottom layer per
  user request, exactly when it is overloaded. Defenses: retry at **one layer** only; a **retry
  budget** (retries at most ~10% of requests over the last 10 s: at the 120 orders/s peak that
  is 1,200 requests -> at most 120 retries); circuit breakers; honor 429/503.

> **Common mistake:** "We'll add retries to make it more reliable." Retries help rare,
> independent, transient failures and worsen overload. Pair them with backoff, jitter, a cap,
> a budget, and idempotency.

## Circuit breakers
**How it works** · A **circuit breaker** tracks a dependency's recent failures and, past a
threshold, fails calls immediately for a cool-down: your threads stop waiting and the dependency
gets room to recover.
States: **closed** (calls pass, counted) -> **open** above threshold (fail fast) -> after a
cool-down **half-open** (a few trials) -> closed if they succeed, open again if one fails.

**Example** · Breaker on `payment-service`: last 100 calls, min 20, open above 50%, 30 s.
```text
03:00:00  CLOSED  1 call, it fails: 1/1 = 100% but < 20 calls -> stays CLOSED
20:30:00  CLOSED  38 of last 100 failed (38%) -> stays CLOSED
20:30:10  CLOSED  61 of last 100 failed or were too slow (61% > 50%) -> OPEN
20:30:10-40 OPEN  calls fail in < 1 ms with fallback "payment busy, retry in a minute";
                  cart kept; zero load reaches payment-service
20:30:40  HALF-OPEN  a few trial calls; one fails -> OPEN, timer restarts (until 20:31:10)
20:31:10  HALF-OPEN  trial calls all succeed -> CLOSED, counting starts fresh
```
So: some calls that would have succeeded fail while open; that is the price. Windows are
count-based (last 100) or time-based (last 10 s). Resilience4j implements it; meshes offer
outlier detection (eject one bad instance). Tune per dependency; always define the fallback.

## Bulkheads
> **Black box:** a bounded pool (threads, connections, concurrency) per dependency, like ship
> compartments: e.g. recommendations capped at 30 threads, call 31 rejected fast to a fallback,
> payment keeps its 80. **Use when:** one dependency could starve the rest (or per customer
> tier). **Cost:** too small rejects work despite spare capacity; size by Little's law + headroom.

## Fallbacks, load shedding, backpressure
**How it works** · A **fallback** is returned when a dependency fails or its breaker is open
(**graceful degradation** keeps the core working). **Load shedding** rejects some requests near
capacity so accepted ones finish fast. **Backpressure**: a slow consumer slows a fast producer.

**Example** · QuickBite at the dinner peak.
- Fallbacks: recommendations -> cached "popular near you"; ratings -> menu without them; live
  "restaurant busy" status -> stale cached value, verify at order time; fraud scoring -> allow
  ₹450 orders, queue large ones (business call); bill total -> **no safe fallback**, fail.
- Shedding: `order-service` in-flight passes its limit -> 503 + Retry-After for low-priority
  calls first; a request already queued 1.2 s when its caller's timeout is 1 s is dropped
  unprocessed. Accepting everything times most of it out and **goodput** collapses.
- Backpressure: rider pings arrive at 25,000/s, the location writer handles 20,000/s; an
  unbounded queue grows 5,000/s (18 M per hour) until memory runs out. A **pull-based** Kafka
  consumer lets the log absorb it on disk, with lag as the signal.

So: decide fallbacks with product owners before the incident and test them. Backpressure tools:
**bounded queues**, **flow control** (TCP/HTTP/2 windows, reactive streams), pull-based
consumers, 429/503. A bigger queue only absorbs bursts; if arrivals outpace processing, latency
grows without bound.

## Distributed transactions: 2PC and sagas
**How it works** · **Two-phase commit (2PC)**: a coordinator asks all participants to **prepare**
(write durably, lock rows, vote), then **commit** if all said yes, else **abort**. A **saga** is
a sequence of local transactions, one per service; on failure **compensating actions** run in
reverse. A compensation is a business action (refund, release, cancel), not a rollback: the step
really committed and may have been seen.

**Example** · 2PC: order DB and payment DB vote YES on `o_90311` and lock its rows; the
coordinator crashes; neither may decide alone, so the rows stay locked: **blocking**.

**Example** · Saga for `o_90311`; Spice Route (`r_77`) has sold out of an item.
```text
 step  service             local transaction                 compensation
 T1    order-service       create o_90311, status PLACED      C1: status -> CANCELLED
 T2    payment-service     authorize (hold) ₹450              C2: release the hold
 T3    restaurant-service  confirm items at r_77 -> FAILS (biryani sold out)
 T4    payment-service     capture ₹450 (pivot)    -- never runs
 T5    rider-service       assign rd_5521          -- never runs
 run:  T1 ok -> T2 ok -> T3 FAIL -> C2 (times out, retried with key o_90311-release, ok)
       -> C1 -> notification-service: "Sorry, Spice Route can't take this order"
```
So: hard-to-undo steps go last; authorizing before **capturing** makes C2 "release hold", not a
refund; after the **pivot** the saga must run forward. 2PC is avoided across services (blocking,
tied to the slowest participant, XA support many stores and payment APIs lack); it lives on
inside one database across its own shards.
- **Choreography**: services react to events (`OrderPlaced` -> `PaymentAuthorized` ->
  `ItemsUnavailable` -> payment releases -> order cancels). Loose coupling, but the flow is hard
  to see; cyclic events, "who handles failure?". Fits 2-4 simple steps.
- **Orchestration**: an order-saga orchestrator holds "T1 ok, T2 ok, T3 failed, running C2" in
  one place; risk of a god-service. Fits many steps, timeouts, human approvals.
- Reliability: **transactional outbox**; every step and compensation **idempotent**; retry
  compensations, then alert a human; no **isolation**, so explicit states (PLACED = "do not
  dispatch a rider yet": a **semantic lock**).

## Idempotency across services
**How it works** · Delivery is **at-least-once** almost everywhere (HTTP retries, redelivery,
re-run saga steps), so handlers must survive running twice: **idempotency keys** stored with the
side effect in one transaction; a **processed-message table** with a unique constraint on
message ID, inserted with the business update; **natural idempotency** ("set" not "add");
**version checks** ("update only if version = 7").

**Example** · `notification-service` handles `OrderPlaced` message `m-123`: in one transaction it
inserts `m-123` into `processed_messages` and queues the SMS row, commits, then crashes before
committing its Kafka offset. On restart Kafka redelivers `m-123`; the insert hits the unique
constraint -> skip. Asha gets one SMS. Likewise `status = PICKED_UP` (a set) replays safely;
`count = count + 1` does not.
So: pass one key down the chain (`o_90311-charge`) so saga retries never double-charge.

## API composition, BFF, strangler fig, tracing
**How it works** · **API composition**: a composer calls several services in parallel and
merges; latency = slowest call, availability depends on all. For heavy cross-service queries
build a **read model** fed by events (the **CQRS** idea). **Distributed tracing** propagates a
trace ID with timed **spans** through every call (chapter 14); a prerequisite.

**Example** · Asha opens the order page for `o_90311`.
```text
composer -> order-service 40 ms | user-service 25 ms | restaurant-service 30 ms
         -> rider-service (live tracking) 200 ms timeout hit
result   -> page in ~200 ms WITHOUT the map (partial response), not an error
trace    -> one trace ID shows rider-service's span is the slow one
```

> **Black box:** **BFF (backend for frontend)**: a thin backend per client type (mobile, web,
> public API) that composes and shapes data for its screens. **Use when:** clients need very
> different payloads. **Cost:** more services, duplicated logic; keep business rules in domains.

> **Black box:** **strangler fig**: router in front of the monolith; move one capability (with
> its data) to a service, route its paths there behind a flag, compare via shadow traffic, delete
> dead code, repeat (e.g. `/api/invoices` first). **Use when:** leaving a legacy monolith without
> a risky big-bang rewrite. **Cost:** a long period of running old and new side by side.

## One resilient call, outside in
**How it works** · load shedding (shed low priority) -> deadline check -> bulkhead slot (full ->
fallback) -> circuit breaker (open -> fallback) -> timeout = min(per-call, remaining deadline)
-> retry with backoff + jitter, within budget, idempotent only -> fallback or clear error.

**Example** · `order-service` calls `payment-service` for `o_90311` at 20:30.
```text
shed?     checkout is top priority -> admitted
deadline  900 ms left -> worth starting
bulkhead  payment pool 37 of 80 busy -> slot taken
breaker   CLOSED -> call allowed
timeout   min(300, 900) = 300 ms -> attempt 1 times out
retry     budget has room, key o_90311-charge, jitter 37 ms -> attempt 2 -> 200 OK
```

## In one minute
- Default to a modular monolith; split for teams, scaling or deploy risk; own your database.
- Sync when the user needs the answer now; async events for side effects and decoupling.
- Slow dependencies exhaust threads (Little's law) and cascade; timeouts and propagated deadlines.
- Retry only idempotent, transient failures with backoff, full jitter, cap and budget (27x storms).
- Breaker: closed -> open -> half-open trials; bulkheads, fallbacks, shedding, backpressure.
- Avoid 2PC across services; use sagas with reverse compensations, outbox and idempotent steps.

## Go deeper
Full chapter 13: sections 13.4 (cascading failure), 13.6 (retries), 13.7 (circuit breakers), 13.10 (2PC and sagas)
