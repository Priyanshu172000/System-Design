# 13 — Microservices and resilience: quick notes

When one app becomes many services, every call between them goes over the network and can be
slow or fail. This chapter is about when to split, and how to keep working when calls fail.

## 1. Monolith vs microservices

- **Monolith:** one app, one deploy, usually one database. Calls are plain function calls.
  *e.g.* the order row and payment row for `o_90311` commit in one transaction.
- **Modular monolith:** still one deploy, but each module owns its own tables behind a small
  interface. *e.g.* the menu module can't touch the `payments` table. A good default for years.
- **Microservices:** separate apps, each owning one business job and its data, talking over the
  network. *e.g.* `order-service` calls `payment-service` (0.5–5 ms); the call can time out.
- **Split when** there's a concrete reason: separate teams (the strongest reason), different
  scaling, different deploy risk, different tech needs. *e.g.* `rider-service` takes ~25,000
  writes/s at peak; the rest doesn't.
- **Cost:** network failures, no joins or shared transactions, many deploys and on-call rotas.
- **Distributed monolith:** services that deploy together or share one DB. All the cost, none
  of the benefit.

## 2. Boundaries and one database per service

- Split by **business capability** (orders, payments), not by layer ("validation service").
- **Database per service:** nobody else reads your tables; they use your API or your events.
  *e.g.* if `order-service` joins `restaurants.name` and that column is renamed, order history
  breaks. Instead, it keeps a local copy of names fed by restaurant events.

## 3. Sync vs async

- **Sync:** the caller waits for the answer, over REST or **gRPC** (fast, typed binary calls).
  *e.g.* "authorize ₹450?" Asha waits for yes or no.
- **Async:** send a message and move on. An **event** goes to Kafka for any number of readers.
  *e.g.* `OrderPlaced o_90311` is read by notification-service and search-service when ready.
- Long sync chains multiply failure: 4 services at 99.9% each give 0.999^4 ≈ 99.6%.

## 4. Cascading failure: slow is worse than down

**Little's law:** busy threads = requests per second × seconds per request.

```text
normal:   1,000/s x 0.05 s = 50 threads busy (of 200)
degraded: 1,000/s x 5 s    = 5,000 needed -> all 200 stuck in 0.2 s
```

- *e.g.* home-feed waits on a slow recommendations call with no timeout. All threads block, so
  login and checkout fail too. Health checks fail, clients retry, and failure spreads upward.
- A **cascading failure** spreads through shared resources (threads, connections) and retries.

## 5. Timeouts and deadlines

- **Timeout:** give up after a fixed wait. Many libraries default to **none**.
  *e.g.* payment p99 is 120 ms, so set the timeout at 300 ms.
- **Deadline propagation:** the first service sets a total budget and passes what's left.
  *e.g.* gateway gives 1,000 ms; after 100 ms, order-service passes 900 ms on. If 0 is left,
  stop: nobody will read the answer (no **orphaned work**).

## 6. Retries done safely

- Retry only **idempotent** calls: running them twice has the same effect as once.
  *e.g.* `POST /charge` is safe only with an **idempotency key** (`o_90311-charge`); the server
  returns the stored result on repeats, so Asha is charged once.
- Retry only errors that can change (timeouts, 503), never 400/403.
- **Exponential backoff:** wait 100, 200, 400 ms... up to a cap.
- **Jitter:** pick a random wait in 0..that limit. *e.g.* 10,000 clients don't all retry at
  +100 ms together.
- **Retry storm:** 3 layers × 3 attempts = 27 calls on the bottom service. Fix: retry at one
  layer, plus a **retry budget** (≤ ~10% extra). *e.g.* 1,200 requests in 10 s → ≤ 120 retries.

## 7. Circuit breakers and bulkheads

- **Circuit breaker:** after too many failures, stop calling for a while and fail instantly.
  Like a house fuse.

| State | Behaviour | Example (payment, open above 50% of last 100) |
|---|---|---|
| Closed | Calls pass, failures counted | 38% failing: stays closed |
| Open | Fail in < 1 ms with a fallback | 61% failing: open for 30 s |
| Half-open | A few trial calls | Trials pass: closed. One fails: open again |

- **Bulkhead:** a separate limited pool per dependency, like ship compartments.
  *e.g.* recommendations gets 30 threads; call 31 is rejected fast, payment keeps its 80.

## 8. Fallbacks, load shedding, backpressure

- **Fallback:** a lesser answer when a dependency fails. *e.g.* show cached "popular near you"
  instead of recommendations. The bill total has **no** safe fallback, so fail.
- **Load shedding:** near capacity, reject low-priority requests with 503 so accepted ones
  finish. *e.g.* a request queued 1.2 s whose caller gave up at 1 s is dropped.
- **Backpressure:** a slow consumer slows the producer. *e.g.* pings at 25,000/s, writer handles
  20,000/s: an unbounded queue grows 18 M per hour. Fix: bounded queues, pull-based Kafka.

## 9. Transactions across services: 2PC and sagas

- **2PC (two-phase commit):** a coordinator asks all databases "ready?", then "commit". If it
  crashes after the votes, rows stay locked (**blocking**). Avoided across services.
- **Saga:** a chain of local transactions; on failure, run **compensations** in reverse.
  A compensation is a new business action (release a hold), not a rollback.

```text
T1 create o_90311 -> T2 hold ₹450 -> T3 r_77 confirm FAILS (biryani sold out)
run C2 release hold -> C1 cancel order -> notify Asha
```

- Put hard-to-undo steps last. *e.g.* hold money first, capture it later.
- **Choreography:** services react to each other's events (fine for 2–4 steps).
  **Orchestration:** one coordinator tracks every step (many steps, timeouts).
- Every step must be idempotent; publish via the **outbox** (write the event with the data).

## 10. Idempotency, composition, migration, tracing

- Messages arrive **at least once**, so handlers must survive duplicates. *e.g.* store message
  `m-123` in a `processed_messages` table with a unique key; a redelivery is skipped, one SMS.
- "Set" is safe to replay (`status = PICKED_UP`); "add" is not (`count = count + 1`).
- **API composition:** call several services in parallel and merge. *e.g.* order page in
  ~200 ms without the rider map when rider-service times out.
- **BFF (backend for frontend):** a thin backend per client (mobile, web) shaping its data.
- **Strangler fig:** move one feature at a time out of a monolith behind a router.
  *e.g.* `/api/invoices` first.
- **Distributed tracing:** one trace ID follows a request through every service (ch 14).
- Order of guards on one call: shed → deadline → bulkhead → breaker → timeout → retry → fallback.

## Remember these 5

1. Start with a modular monolith; split for teams, scaling or deploy risk; own your data.
2. Every network call needs a timeout; pass the remaining deadline down.
3. Retry only idempotent calls, with backoff, jitter, a cap and a budget.
4. Breakers, bulkheads and fallbacks stop one slow service taking down the rest.
5. No 2PC across services: use sagas with compensations and idempotent steps.
