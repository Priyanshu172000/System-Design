# 11 — Queues, streams and event-driven systems

Many backend designs have a box labelled "Kafka" or "queue" in the middle. This chapter explains
why it is there, what guarantees it really gives (ordering, delivery, durability), and what you
still have to build yourself (idempotent consumers, retries, outbox). Kafka gets a deep dive
because it comes up in almost every design conversation, and its guarantees are often misquoted.

## 11.1 Why go asynchronous

### The pain

An order service handles "place order". Synchronously, it calls everyone who cares:

```text
 BEFORE (synchronous chain)

 Client -> Order service --> Payment  (must succeed now)
                         --> Email service       (slow SMTP, 800 ms)
                         --> Analytics service   (down for a deploy)
                         --> Loyalty points
                         --> Recommendation update
 Latency = sum of all calls. If Analytics is down, does the order fail?
 Adding a new consumer means changing and redeploying the order service.
```

Problems: latency adds up; one slow or failed downstream service breaks or delays the main flow;
the order service must know about every consumer; a traffic spike hits every service at once.

### The idea

Do only the essential work synchronously, then publish a message ("OrderPlaced") to a
**broker** (the server that stores and delivers messages). Interested services consume it in
their own time.

```text
 AFTER (asynchronous)

 Client -> Order service --> Payment (sync, essential)
                 |
                 +--> publish "OrderPlaced" --> [ Broker ]
                                                   |  |  |  |
                         Email <-------------------+  |  |  |
                         Analytics <------------------+  |  |
                         Loyalty <-----------------------+  |
                         Recommendations <------------------+
 Client gets a reply after payment. Others catch up in ms to minutes.
```

What async buys you:

| Benefit | Meaning |
|---|---|
| **Decoupling** | Producer does not know who consumes; add consumers without touching it |
| **Buffering spikes** | A burst of 50k msgs/s sits in the broker; consumers process at their own steady rate (**load leveling**) |
| **Failure isolation** | Email down for 10 minutes → messages wait; orders keep working |
| **Retries** | A failed message can be retried later without the user waiting |
| **Fan-out** | One event, many independent consumers |

What it costs:

- **Eventual consistency**: the loyalty points appear a bit later.
- Harder debugging: a flow is spread across services and time (you need tracing, chapter 14).
- New failure modes: duplicates, reordering, poison messages, consumer lag.
- One more critical system to run.

When NOT to use it: when the caller needs the result to continue (checking stock before
confirming), or when a simple synchronous call is fast, reliable, and has one consumer.

> **Say it like this:** "The order service does payment synchronously and then publishes an
> OrderPlaced event. Email, analytics, and loyalty consume it asynchronously, so a slow or broken
> downstream service can't fail or slow down checkout."

## 11.2 Queue vs pub/sub vs log

Three different models hide behind the word "messaging":

```text
 1) Point-to-point QUEUE: each message goes to ONE consumer, then is deleted
    Producer -> [m1 m2 m3 m4] -> worker A gets m1, m3
                              -> worker B gets m2, m4

 2) PUB/SUB: each message is copied to EVERY subscriber
    Publisher -> topic -> subscriber X gets m1 m2 m3
                       -> subscriber Y gets m1 m2 m3

 3) LOG (Kafka-style): messages appended to a durable ordered log and kept for a retention
    period; each consumer group tracks its own position (offset) and reads at its own pace
    Producer -> [m1 m2 m3 m4 m5 ...]   (kept 7 days)
                       ^        ^
          group "email" at 2    group "analytics" at 5  (can rewind and replay)
```

| | Queue | Pub/sub (classic) | Log |
|---|---|---|---|
| Who gets a message | One consumer | Every subscriber | Every consumer group; one consumer within a group per partition |
| After consumption | Deleted | Deleted per subscriber | Kept until retention expires |
| Replay | No | No | Yes (reset offset) |
| Ordering | Usually best-effort (strict in FIFO variants) | Varies | Strict within a partition |
| Typical tools | RabbitMQ, SQS, ActiveMQ | SNS, Redis pub/sub, RabbitMQ fanout exchanges | Kafka, Pulsar, Kinesis, Redis Streams |
| Best for | Task/job distribution | Notifications/fan-out | Event streams, multiple consumers, replay, high throughput |

A log combines both: within one consumer group it acts like a queue (work is split); across
groups it acts like pub/sub (each group sees everything).

## 11.3 Point-to-point queue semantics (RabbitMQ / SQS style)

A worker pool pulls jobs from a queue: "resize this image", "send this email".

### Acknowledgment and redelivery

```text
 1. Worker receives message m (broker marks it in-flight / unacked)
 2. Worker processes m
 3. Worker ACKs m -> broker deletes it

 If the worker crashes before step 3:
   RabbitMQ: the connection/channel closes -> unacked messages are requeued
   SQS:      the visibility timeout expires -> m becomes visible again
 -> another worker receives m and processes it again (a DUPLICATE if step 2 had finished)
```

- **Ack (acknowledge)**: the consumer tells the broker "done, delete it". Ack only after the work
  is complete.
- **Visibility timeout** (SQS term): when a consumer receives a message, it is hidden from others
  for N seconds (30 s by default in SQS). If not deleted in time, it reappears. Set it longer
  than your worst-case processing time, or extend it while working; otherwise a slow but healthy
  worker's message is handed to a second worker.
- **Nack / reject**: the consumer says "failed"; the broker can requeue it or route it to a
  dead-letter queue.
- **Prefetch** (RabbitMQ): how many unacked messages a consumer may hold at once; limits how much
  work one slow consumer hoards.

Consequence: queues give **at-least-once** delivery by default. Your workers must tolerate
duplicates (11.6).

Ordering: a standard SQS queue gives best-effort ordering only; FIFO queues give ordering per
**message group ID** and deduplicate within a short window. With several competing workers, even
an ordered queue can complete messages out of order, because worker B can finish m2 before worker
A finishes m1.

## 11.4 Kafka deep dive

**Apache Kafka** is a distributed, replicated, append-only log. It is built for high throughput
(hundreds of thousands to millions of messages per second per cluster, roughly), durable
retention, and many independent consumers.

### Topics, partitions, offsets

- A **topic** is a named stream of records, e.g. `orders`.
- A topic is split into **partitions**. Each partition is an ordered, append-only sequence of
  records, stored on disk.
- Each record in a partition has an **offset**: its position (0, 1, 2, ...). Offsets are
  per partition; there is no global order across partitions.
- A **record** has a key (optional), a value (bytes), a timestamp, and headers.

```text
 Topic "orders" (3 partitions)

 P0: [0][1][2][3][4][5][6] -> new records appended here
 P1: [0][1][2][3]
 P2: [0][1][2][3][4]

 Order is guaranteed inside P0 (offset 3 before 4).
 No ordering relationship between P0's offset 3 and P1's offset 3.
```

DSA bridge: a partition is an append-only array where the index is the offset; consumers keep
their own index pointer into it.

### Segments and retention

On disk, each partition is a sequence of **segment** files (e.g. 1 GB each). Only the newest
(active) segment receives appends. Old segments are deleted whole when they pass the
**retention** limit: by time (default 7 days) or by total size. Consuming a message does NOT
delete it; retention does.

```text
 P0 on disk:  seg-000000.log (closed)  seg-184022.log (closed)  seg-361930.log (active)
              ^ deleted when older than retention
```

Why it is fast: appends are sequential disk writes, reads are mostly sequential, the OS page
cache serves recent data, and records are sent in batches with compression.

### Producers: keys decide the partition

The producer picks the partition for each record:

- **With a key**: `partition = hash(key) mod number_of_partitions`. All records with the same key
  go to the same partition, so they stay in order relative to each other.
- **Without a key**: the producer spreads records across partitions (newer clients fill a batch
  for one partition, then switch, to batch efficiently).

```text
 key = order_id
   order 17 created   -> hash(17) mod 3 = P1
   order 17 paid      -> P1
   order 17 shipped   -> P1     consumers see created, paid, shipped in order
   order 42 created   -> P0     (independent; no order relative to order 17)
```

**Ordering guarantee: only within a partition.** Kafka never promises order across partitions
of a topic.

> **Common mistake:** Adding partitions to a keyed topic in production. `hash(key) mod N`
> changes when N changes, so new records for an existing key may go to a different partition
> than its older records, and per-key order breaks during the transition. Choose a partition
> count with headroom up front.

Choosing the key is a design decision: key by the entity whose events must be ordered (order ID,
account ID). A very hot key (one celebrity user) makes one partition hot, the same skew problem
as sharding (chapter 07).

### Consumer groups

A **consumer group** is a set of consumers sharing a group ID that cooperate to read a topic.
Kafka assigns each partition to **exactly one consumer in the group** at a time.

```text
 Topic with 4 partitions, group "billing":

 2 consumers:  C1 <- P0, P1      C2 <- P2, P3
 4 consumers:  C1 <- P0  C2 <- P1  C3 <- P2  C4 <- P3
 6 consumers:  C1..C4 one partition each, C5 and C6 IDLE

 A different group "analytics" independently reads all 4 partitions too.
```

- **Max parallelism within a group = number of partitions.** Extra consumers sit idle. This is
  why partition count is a capacity decision.
- Each group tracks its **committed offset** per partition (stored in Kafka's internal
  `__consumer_offsets` topic): "group billing has processed P2 up to offset 8812".
- A consumer reads, processes, and **commits** offsets. After a crash, the partition's new owner
  resumes from the last committed offset.

### Rebalancing

When a consumer joins, leaves, crashes (misses heartbeats), or takes too long between polls,
the group **rebalances**: partitions are reassigned.

- During an older-style ("eager") rebalance, all consumers stop and give up all partitions until
  reassignment completes, which causes a processing pause. Newer **cooperative (incremental)**
  rebalancing moves only the partitions that need to move.
- Messages processed but not yet committed before a rebalance are processed again by the new
  owner → duplicates.
- A consumer that takes longer than the max poll interval to process a batch is considered dead
  and triggers a rebalance; long processing per batch must be bounded or moved off the poll loop.

### Replication: leaders, followers, ISR

Each partition has a **replication factor** (commonly 3): one **leader** replica and followers on
other brokers. Producers write to the leader, and consumers normally read from it; followers copy
the leader's log.

```text
 Partition P0, replication factor 3
   Broker 1: P0 leader   [0..1000]
   Broker 2: P0 follower [0..1000]   in sync
   Broker 3: P0 follower [0..940]    lagging -> may drop out of ISR
```

- **ISR (in-sync replicas)**: the leader plus followers that are caught up (have fetched
  recently enough, within a configured lag time such as 30 s).
- A record is **committed** when all current ISR members have it. The **high watermark** is the
  offset up to which records are committed; consumers only see records below it.
- If the leader dies, a new leader is elected from the ISR, so committed records survive.
  (Allowing an out-of-sync replica to become leader — "unclean leader election", off by default
  — trades data loss for availability.)

### acks and min.insync.replicas

The producer's **acks** setting controls when a write is considered successful:

| acks | Producer gets success when | Risk |
|---|---|---|
| `0` | Immediately, without waiting for the broker | Lost if anything fails; fastest |
| `1` | The leader has written it | Lost if the leader dies before followers copy it |
| `all` (`-1`) | All current ISR members have it | Safest; slightly higher latency |

`acks=all` has a subtlety: if the ISR has shrunk to just the leader, "all ISR" means one copy.
**min.insync.replicas** fixes that: with `acks=all`, the write is rejected (an error to the
producer) unless at least that many replicas are in the ISR.

The standard durable setup: **replication factor 3, min.insync.replicas = 2, acks = all**. Every
acknowledged write is on at least 2 brokers; one broker can be down and writes continue; with two
down, writes are refused rather than silently weakened (consistency over availability).

> **Say it like this:** "For orders I'd use replication factor 3, min in-sync replicas 2, and
> acks=all, so every acknowledged event is on at least two brokers and we survive losing one
> broker without losing data or stopping writes."

### Log compaction

Instead of deleting by time, a topic can use **log compaction**: Kafka keeps at least the
**latest record for each key** and removes older records for that key in the background. A
record with a null value (a **tombstone**) marks a key as deleted, and is removed itself after a
while.

```text
 before:  (u1,A) (u2,B) (u1,C) (u3,D) (u2,null) (u1,E)
 after:   (u3,D) (u1,E)          (u2 removed by tombstone, after the tombstone retention)
```

Use for: changelog topics representing current state ("latest profile per user", CDC table
snapshots, stream processor state backups). A new consumer can rebuild the full current state
by reading the compacted topic from the start.

## 11.5 Delivery semantics

Delivery semantics describe what happens to a message across failures, from producer to the
consumer's processing.

### At-most-once

The message is processed zero or one times. Consumer pattern: **commit the offset, then
process**. If it crashes after committing but before processing, the message is skipped.

### At-least-once

The message is processed one or more times. Consumer pattern: **process, then commit**. If it
crashes after processing but before committing, the new owner reprocesses it. Producer side:
retries on timeout can also write duplicates (unless idempotent, below).

```text
 process(offset 41) -> process(offset 42) -> CRASH before commit(43)
 new owner resumes from last commit (41) -> processes 41 and 42 again
```

This is the most common choice: losing data is usually worse than duplicates, and duplicates
can be made harmless.

### Exactly-once

The message's *effect* happens once. What Kafka actually provides:

- **Idempotent producer**: the broker gives each producer an ID and tracks a sequence number per
  partition, so a retried send is detected and not written twice. This removes producer-retry
  duplicates (and preserves order across retries). Recent Kafka clients enable it by default.
- **Transactions**: a producer can write to several partitions **and** commit consumer offsets
  in one atomic transaction. Consumers set `isolation.level=read_committed` to see only committed
  transactions. This gives exactly-once for **read from Kafka → process → write to Kafka**
  pipelines (the model stream processors use).

What it does NOT cover: side effects outside Kafka. If your consumer charges a card, sends an
email, or writes to Postgres, a crash between "did the side effect" and "committed the offset"
means the side effect repeats on reprocessing. Kafka cannot roll back an email.

So **end-to-end exactly-once requires idempotent consumers** (or storing the output and the
consumed offset in the same external transaction).

| Semantics | Gain | Pay | Use for |
|---|---|---|---|
| At-most-once | Simple, no duplicates | Loses messages on failure | Metrics where a gap is OK |
| At-least-once | No loss | Duplicates; consumer must dedupe | Default for business events |
| Exactly-once (Kafka EOS) | No loss, no duplicates within Kafka pipelines | Throughput/latency overhead, more config, only inside Kafka | Stream processing jobs, aggregations |

> **Say it like this:** "Kafka's exactly-once covers Kafka-to-Kafka processing. Anything with an
> external side effect is at-least-once, so the consumer is idempotent: it dedupes on the event
> ID in the same transaction as its write."

## 11.6 Designing an idempotent consumer

Every event carries a unique **event ID** (set by the producer, not by the broker, so retries
keep the same ID).

### Option 1: dedupe table in the same transaction

```sql
BEGIN;
  INSERT INTO processed_events (event_id) VALUES ('evt-7f1c');  -- primary key on event_id
  -- if this fails with a duplicate-key error: already processed -> ROLLBACK, ack, skip
  UPDATE loyalty SET points = points + 50 WHERE user_id = 42;
COMMIT;
-- then commit the Kafka offset
```

Because the dedupe record and the effect commit atomically, a crash either leaves both or
neither. Clean old dedupe rows after a window longer than any possible redelivery.

### Option 2: make the operation naturally idempotent

- **Upserts by key**: `INSERT ... ON CONFLICT (order_id) DO UPDATE SET status = 'PAID'`.
  Applying it twice gives the same row.
- **Absolute values instead of increments**: "set points = 550" (computed upstream) rather than
  "add 50".
- **Version / sequence checks**: store the last applied version per entity and ignore events
  with a version ≤ it. This also protects against reordering.

```sql
UPDATE accounts
SET balance = :new_balance, version = :event_version
WHERE id = :account_id AND version < :event_version;
```

### Option 3: store the offset with the output

If the consumer writes to a database, store the Kafka offset (per partition) in the same
transaction as the data. On restart, read the offset from the database rather than from Kafka.
Duplicate deliveries are recognized because their offset is ≤ the stored one.

## 11.7 Ordering guarantees in practice

How to get **per-entity ordering** (all events for order 17 processed in order):

1. **Key by entity ID** → same partition → appended in order.
2. **Producer must not reorder on retry**: with the idempotent producer, retries keep order;
   without it, allowing several in-flight requests per connection can reorder after a retry.
3. **Consumer processes a partition sequentially** — or, if it uses a thread pool for speed,
   it must route by key to threads so one key is never processed concurrently.
4. **Do not re-partition mid-stream** (changing partition count).

```text
 Partition P1 -> consumer -> dispatch by hash(key) to worker threads
                              thread 0: keys 17, 33 (in order)
                              thread 1: keys 42, 58 (in order)
 (Offsets must be committed only up to the lowest offset still in progress.)
```

Global ordering of all events requires a single partition, which caps throughput at what one
partition and one consumer can do. Ask whether you really need it; usually per-entity order is
enough.

## 11.8 Failures: poison messages, retries, dead letters

A **poison message** always fails processing (malformed payload, a bug for one edge case, a
reference to a deleted entity). With "process then commit" and naive retry, the consumer retries
it forever and the whole partition is stuck behind it (**head-of-line blocking**).

### Retry topics with backoff and a dead letter queue

```text
 orders  --> consumer --fail--> orders.retry.1m  --> retry consumer (waits until 1 min old)
                                     |
                                     +--fail--> orders.retry.10m --> retry consumer
                                                     |
                                                     +--fail--> orders.DLQ (park, alert)
 On success at any stage -> done.
```

- **Transient errors** (timeout, dependency down): retry a few times with exponential backoff and
  jitter, usually in-process first, then via retry topics so the main partition keeps moving.
- **Permanent errors** (bad data, validation failure): do not retry; send straight to the DLQ.
- **Dead letter queue (DLQ)**: where messages go after exhausting retries. Must be monitored and
  alerted on, with tooling to inspect, fix, and **replay** messages back to the main topic.
  A DLQ nobody watches is a silent data-loss bin.

Trade-off: moving a message to a retry topic means later messages for the *same key* may be
processed before it, breaking per-key order. If order is critical, either block the key (park
subsequent messages for that key too) or block the partition and alert.

> **Common mistake:** Retrying forever in the main consumer. One bad message halts a partition,
> lag grows on every message behind it, and the outage looks like "Kafka is slow".

## 11.9 Backpressure and consumer lag

**Backpressure** is any mechanism that slows producers when consumers cannot keep up. A log like
Kafka absorbs the difference instead: producers keep writing, consumers fall behind, and the gap
grows. That gap is **consumer lag**.

```text
 Partition P2:  [ ... 9000 ... 9500 ................... 12000 ]
                           ^ committed offset (group billing)   ^ log end offset
 lag(P2) = log end offset - committed offset = 12000 - 9500 = 2500 messages
```

- Lag in messages is useful, but **lag in time** ("the oldest unprocessed message is 4 minutes
  old") is what matters for users and SLOs.
- Steady small lag = healthy. Lag that grows steadily = consumers are slower than producers
  (add consumers up to the partition count, speed up processing, batch writes). Lag that jumps
  and stays = a stuck consumer or poison message.
- If lag exceeds the retention period, unprocessed data is deleted: that is real data loss.
- Monitor per partition and per group: a single hot or stuck partition hides in an average.

With queues (RabbitMQ/SQS), the equivalent signals are queue depth and age of the oldest
message. Brokers can also apply backpressure directly (blocking publishers when memory is full).

## 11.10 The dual-write problem and the transactional outbox

### The pain

The order service must save the order **and** publish OrderPlaced:

```text
 1. INSERT order into DB      -> commit
 2. publish to Kafka          -> service crashes before this
 => order exists, no event; downstream never hears about it.

 Swap the order:
 1. publish to Kafka          -> ok
 2. INSERT order              -> fails (constraint)
 => event for an order that does not exist.
```

This is the **dual-write problem** (also in chapter 09): two systems, no shared transaction.
Distributed transactions across a DB and a broker are rarely available or worth their cost.

### Transactional outbox

Write the event into an **outbox table in the same database, in the same transaction** as the
business data. A separate **relay** publishes outbox rows to the broker.

```text
 Order service
   BEGIN
     INSERT INTO orders (...)
     INSERT INTO outbox (event_id, aggregate_id, type, payload, created_at)
   COMMIT                      <- both or neither

 Relay (poller or CDC)
   reads new outbox rows ---> publish to Kafka (key = aggregate_id) ---> mark sent / advance
                                                                          position

 +-----------+      +------------------+       +-------+      +-----------+
 | Order svc | ---> | DB: orders+outbox| ---> | Relay | ---> | Kafka     | ---> consumers
 +-----------+  tx  +------------------+  log  +-------+      +-----------+
```

Two relay styles:

| Relay | How | Gain | Pay |
|---|---|---|---|
| Polling publisher | Periodically `SELECT` unsent outbox rows, publish, mark sent | Simple | Polling load and delay; careful ordering |
| CDC (change data capture) | A connector (Debezium-style) tails the DB commit log and publishes outbox inserts | Low latency, no polling, preserves commit order | Extra infrastructure to run |

The relay can crash after publishing but before marking a row sent, so it may publish twice:
the outbox gives **at-least-once** publishing, and consumers stay idempotent using `event_id`.

Plain CDC (without an outbox) publishes raw row changes of your tables. That works for
replicating data (search indexes, caches, chapter 09), but couples consumers to your table
schema. The outbox lets you publish deliberate, versioned domain events.

> **Say it like this:** "To avoid dual writes, the order and its event go into the same database
> transaction via an outbox table, and a CDC relay publishes the outbox to Kafka. Publishing is
> at-least-once, so consumers dedupe on the event ID."

## 11.11 Event-driven architecture

### Events vs commands

| | Command | Event |
|---|---|---|
| Meaning | "Please do X" (`ChargeCard`) | "X happened" (`OrderPlaced`) |
| Tense | Imperative | Past tense, a fact |
| Receivers | One intended handler | Zero or many; producer does not care |
| Can be rejected? | Yes | No, it already happened |
| Coupling | Sender knows the receiver's capability | Producer does not know consumers |

### Event notification vs event-carried state transfer

- **Event notification**: a thin event, `{"type":"OrderPlaced","orderId":17}`. Consumers who
  need details call back the order service. Small events, always-fresh data, but callbacks add
  load and coupling (if the order service is down, consumers stall).
- **Event-carried state transfer**: a fat event with the data consumers need (items, amounts,
  address). Consumers keep their own local copy and never call back. More autonomy and
  resilience, but bigger events, duplicated data, and schema evolution matters more (use a
  schema registry and backward-compatible changes).

### Event sourcing (intro)

In **event sourcing**, the source of truth is the append-only list of events for each entity,
not a row with the current state. Current state is computed by replaying events (often with
periodic snapshots).

```text
 account 42 events:
   Opened(0) -> Deposited(100) -> Withdrew(30) -> Deposited(50)
 current balance = replay = 120
```

- Gain: complete audit history; can rebuild state or new views at any time; natural fit for
  domains that already think in events (ledgers, workflows).
- Pay: querying current state needs projections; event schemas live forever and must be
  versioned; correcting mistakes means appending compensating events; a steep learning curve.
- When not to: CRUD-style domains where history has no business value. Most services do not
  need it.

### CQRS (intro)

**CQRS (command query responsibility segregation)**: use separate models for writes (commands)
and reads (queries). Writes go to a normalized model; events update one or more **read models**
(denormalized tables, a search index, a cache) shaped for specific queries.

```text
 commands --> write model (DB) --events--> projector --> read model(s) <-- queries
```

- Gain: each side optimized independently; read models scale separately.
- Pay: read models are eventually consistent (a user may not see their own write immediately);
  more moving parts.
- When not to: simple apps where one model serves reads and writes fine.

Event sourcing and CQRS are often used together but are independent ideas.

### Choreography vs orchestration

When a business flow spans services (order → payment → inventory → shipping):

- **Choreography**: each service reacts to events and emits its own. No central coordinator.
  Loose coupling, but the overall flow is implicit and hard to see or change.
- **Orchestration**: a coordinator service sends commands to each step and tracks the state of
  the flow. The flow is explicit and easy to monitor, at the cost of a central component.

```text
 Choreography:  OrderPlaced -> Payment -> PaymentDone -> Inventory -> Reserved -> Shipping
 Orchestration: Orchestrator -> "charge" -> Payment; -> "reserve" -> Inventory; -> "ship" ...
```

Handling failures in these multi-step flows (compensating actions) is the **saga** pattern,
covered in chapter 13.

> **Common mistake:** Turning every interaction into an event because it feels decoupled. If a
> service needs an answer to continue, that is a request/response call or a command, and hiding it
> behind events only makes the dependency harder to see.

## 11.12 Queue vs Kafka: choosing

| Need | Lean towards |
|---|---|
| Distribute background jobs to workers (emails, image resizing) | Queue (SQS/RabbitMQ) |
| Per-message acks, per-message retries/delays, priority | Queue |
| Many independent consumers of the same events | Kafka (log) |
| Replay history, rebuild a read model, reprocess after a bug | Kafka |
| Very high throughput event streams (clicks, logs, CDC) | Kafka |
| Strict per-key ordering at scale | Kafka (key → partition) |
| Stream processing (windows, joins, aggregations) | Kafka + stream engine |
| Small team, simple job queue, fully managed, no partition planning | Queue (e.g. SQS) |
| Parallelism beyond a fixed count without re-partitioning | Queue (just add workers) |

| | Queue (RabbitMQ / SQS) | Kafka |
|---|---|---|
| Model | Message deleted once acked | Log retained; offsets per group |
| Consumer scaling | Add workers freely | Up to number of partitions per group |
| Ordering | Best-effort or per group (FIFO) | Per partition |
| Replay | No | Yes |
| Per-message retry/delay | Built in (DLQ, delays) | Build it (retry topics) |
| Throughput | Moderate to high | Very high |
| Operational effort | Low (managed) to moderate | Moderate to high (managed offerings help) |

> **Say it like this:** "For background jobs I'd use a simple queue: per-message acks and retries
> are built in. For the order event stream I'd use Kafka, because several services consume it,
> we need per-order ordering, and we want to replay it to rebuild read models."

## 11.13 Recap

- Async messaging buys decoupling, spike buffering, failure isolation, retries, and fan-out;
  it costs eventual consistency and new failure modes.
- Queue: one consumer per message, deleted on ack. Pub/sub: copy to all subscribers. Log: kept
  for retention, each group tracks its offset, replay is possible.
- Queues redeliver unacked messages (visibility timeout, connection loss) → at-least-once.
- Kafka: topic → partitions → offsets; segments deleted by retention, not by consumption.
- Key → partition via hash; ordering only within a partition; do not change partition counts on
  keyed topics casually.
- A consumer group assigns each partition to one consumer; max parallelism = partitions;
  rebalances cause pauses and duplicates.
- Durability: replication factor 3, min.insync.replicas 2, acks=all; committed = in all ISR.
- Log compaction keeps the latest value per key; tombstones delete.
- Exactly-once in Kafka covers Kafka-to-Kafka pipelines (idempotent producer + transactions);
  external side effects need idempotent consumers.
- Handle poison messages with bounded retries, retry topics, and a monitored DLQ; watch consumer
  lag, especially in time.
- Use the transactional outbox (+ CDC relay) to avoid dual writes between DB and broker.
- Events state facts; commands request actions; event sourcing and CQRS are powerful but not
  defaults.

## 11.14 Check yourself

1. A topic has 6 partitions and your consumer group has 10 consumers. How many are doing work,
   and how would you get more parallelism?

<details><summary>Answer</summary>

Six; each partition goes to exactly one consumer in the group, so 4 sit idle. To go further,
increase the partition count (planning for the key-to-partition remapping on keyed topics) or
speed up each consumer, e.g. by processing a partition's records in parallel by key while
committing offsets carefully.

</details>

2. You need all events for one account processed in order. What do you do on the producer and
   consumer side?

<details><summary>Answer</summary>

Use the account ID as the record key so all its events land in the same partition; use the
idempotent producer so retries do not reorder; consume each partition sequentially, or dispatch
by key so one account is never processed concurrently; do not change the partition count. Also
consider a version check in the consumer to ignore out-of-order or duplicate events.

</details>

3. Explain acks=all with replication factor 3 and min.insync.replicas=2. What happens when two
   brokers holding a partition are down?

<details><summary>Answer</summary>

The leader acknowledges a write only after all in-sync replicas have it, and refuses writes if
fewer than 2 replicas are in sync. So each acknowledged write is on at least 2 brokers. With two
of the three replicas down, the ISR has 1 member, which is below the minimum, so producers get
errors (writes stop) rather than writing data that would exist on a single broker.

</details>

4. Your consumer charges a card and then commits the offset. Kafka is configured for
   exactly-once. Can a customer be charged twice?

<details><summary>Answer</summary>

Yes. Kafka's exactly-once covers reads and writes within Kafka (transactions with offset
commits). If the consumer crashes after charging but before the offset commit, the message is
redelivered and the charge repeats. Fix: an idempotency key for the charge (e.g. the event ID)
passed to the payment provider, and/or a dedupe record written atomically with the charge state.

</details>

5. What is consumer lag, and which pattern of lag suggests a poison message?

<details><summary>Answer</summary>

Lag is the log end offset minus the group's committed offset, per partition (better also tracked
as the age of the oldest unprocessed message). Healthy lag is small and stable. A poison message
shows as one partition's committed offset frozen while its lag grows steadily, often with
repeated errors for the same offset, while other partitions are fine.

</details>

6. Why does the transactional outbox still require idempotent consumers?

<details><summary>Answer</summary>

The relay may publish an outbox row and crash before recording that it was sent (or the broker
ack may be lost), so it publishes the same event again on restart. The outbox guarantees the
event is not lost and is consistent with the DB write, but delivery is at-least-once, so consumers
dedupe by event ID.

</details>

7. When would you choose a queue like SQS over Kafka?

<details><summary>Answer</summary>

For job distribution with one consumer type: per-message acks, built-in retries, delays and DLQs,
scaling by adding workers without partition planning, low operational effort, and no need for
replay or multiple independent consumer groups.

</details>

8. What is the difference between event notification and event-carried state transfer?

<details><summary>Answer</summary>

Event notification sends a thin event (just IDs); consumers call back for details, keeping data
fresh but adding coupling and load on the source. Event-carried state transfer includes the
needed data in the event, so consumers keep local copies and do not depend on the source at read
time, at the cost of larger events, duplicated data, and stricter schema evolution.

</details>
