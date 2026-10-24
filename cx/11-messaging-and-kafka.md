# 11 — Queues, streams and event-driven systems

*Why it matters:* most designs have a "Kafka" or "queue" box in the middle, and you need to know what it really guarantees (ordering, delivery, durability) and what you still build yourself (idempotent consumers, retries, outbox).

## Why go asynchronous

**How it works** · A synchronous call chain has latency = sum of calls, fails when any one fails, and must change for every new consumer. Instead: do essential work synchronously, then publish an event to a **broker**; others consume in their own time (ms to minutes).

**Example** · Asha taps "Place order" for `o_90311`.

```text
SYNC:  order-service -> payment (300 ms) -> notification SMS (800 ms)
                     -> analytics (down for deploy: error?) -> loyalty -> recommendations
       ~1.2 s+ and the order fails if analytics is down
ASYNC: order-service -> payment (300 ms) -> publish OrderPlaced (~5 ms) -> reply
       notification, analytics, loyalty consume from Kafka "order-events" when they can
so: ~305 ms for Asha; analytics catches up after its deploy; new consumers need no change
```

- Benefits: **decoupling** (producer does not know consumers; add them freely); **buffering spikes** / **load leveling** (a 50k msgs/s burst waits in the broker); **failure isolation** (SMS provider down 10 min -> messages wait, orders work); **retries** later without the user waiting; **fan-out** (one event, many consumers).
- Costs: eventual consistency (loyalty points appear a bit later), harder debugging (needs tracing), duplicates/reordering/poison messages/lag, one more critical system.
- Do not use when the caller needs the result to continue (is the restaurant open? is payment approved?), or one fast reliable consumer suffices.

> **Common mistake:** Turning every interaction into an event because it feels decoupled. If a service needs an answer to continue, that is a request/response call or a command; events only hide the dependency.

## Queue vs pub/sub vs log

**How it works** · Three models behind "messaging".

**Example** · Three QuickBite needs, one per model.

```text
QUEUE:   resize jobs [j1 j2 j3 j4] -> worker A gets j1,j3; B gets j2,j4 (deleted on ack)
PUB/SUB: "r_77 closed early" -> app-push sub gets it; email sub gets it (each a copy)
LOG:     order-events [e1 e2 e3 e4 e5 ...] kept 7 days
              ^ group "notification" at e2   ^ group "analytics" at e5 (can rewind)
```

| | Queue | Pub/sub | Log |
|---|---|---|---|
| Who gets it | One consumer | Every subscriber | Every group; one consumer per partition in a group |
| After consume | Deleted | Deleted per subscriber | Kept until retention |
| Replay | No | No | Yes |
| Ordering | Best-effort (strict in FIFO) | Varies | Strict per partition |
| Tools | RabbitMQ, SQS, ActiveMQ | SNS, Redis pub/sub, RabbitMQ fanout | Kafka, Pulsar, Kinesis, Redis Streams |

A log is both: a queue within a group (work split), pub/sub across groups.

## Point-to-point queue semantics

**How it works** · Worker receives m (in-flight/unacked), processes, **acks** -> broker deletes. Crash before ack: RabbitMQ requeues on connection close; SQS makes m visible again after the **visibility timeout** (30 s default). Another worker reprocesses it: a duplicate if processing had finished. So queues are **at-least-once**.

**Example** · SQS photo-resize queue, visibility timeout 30 s, a 40 MB photo takes 45 s.

```text
t=0   worker A receives j7 (hidden from others for 30 s)
t=30  timeout expires, j7 visible again -> worker B receives j7
t=45  A finishes, writes the thumbnail, acks j7
t=75  B finishes, writes the same thumbnail again
so: a slow but healthy worker caused a duplicate; set timeout > worst case or extend it
```

- **Nack/reject** -> requeue or dead-letter. **Prefetch** (RabbitMQ) caps unacked messages per consumer.
- Standard SQS: best-effort order; FIFO: order per **message group ID** + short-window dedupe. Competing workers can still finish out of order (B finishes m2 before A finishes m1).

## Kafka: topics, partitions, offsets, keys

**How it works** · **Kafka** is a distributed, replicated, append-only log; roughly hundreds of thousands to millions of msgs/s per cluster. A **topic** splits into **partitions**, each an ordered append-only sequence; each record (key, value, timestamp, headers) has an **offset** per partition. No global order across partitions. (A partition is an append-only array; consumers keep their own index.) Producer: **with a key**, `partition = hash(key) mod N`; **no key**, spread (newer clients fill a batch per partition, then switch).

**Example** · Topic `order-events`, 6 partitions, key = `order_id` (partition numbers illustrative).

```text
o_90311 PLACED     hash("o_90311") mod 6 = 4  -> P4 offset 812
o_90400 PLACED     hash("o_90400") mod 6 = 1  -> P1 offset 530
o_90311 ACCEPTED   same key -> P4 offset 813
o_90311 PREPARING  same key -> P4 offset 814
P4: [... 812 PLACED | 813 ACCEPTED | 814 PREPARING]  <- appends only at the end
so: o_90311's events stay in order; no order between P4:813 and P1:530
```

- **Ordering only within a partition.** Key by the entity whose events must be ordered (order ID, account ID); a hot key makes a hot partition (like shard skew).
- **Segments & retention**: partitions are segment files (e.g. 1 GB); only the active one gets appends; old ones deleted whole by time (default 7 days) or size. **Consuming does not delete; retention does.** Fast because of sequential writes/reads, OS page cache, batching + compression.

> **Common mistake:** Adding partitions to a keyed topic in production: going 6 -> 8 changes `hash mod N`, so `o_90311`'s next event may land in P2 while older ones sit in P4, and per-key order breaks. Size partition count with headroom up front.

## Consumer groups and rebalancing

**How it works** · A **consumer group** shares a group ID; each partition goes to **exactly one consumer in the group**. Each group has a **committed offset** per partition (in `__consumer_offsets`) = the next offset to read; read, process, commit; a new owner resumes from the last commit.

**Example** · `order-events` (6 partitions), two independent groups.

```text
group "notification", 2 consumers:  C1 <- P0,P1,P2   C2 <- P3,P4,P5
  add C3 -> rebalance:              C1 <- P0,P1      C2 <- P2,P3     C3 <- P4,P5
  8 consumers:                      6 own one partition each, 2 sit IDLE
group "analytics", 1 consumer:      A1 <- P0..P5, its own offsets (P4 at 640, not 812)
crash on P4 (2-consumer layout): committed 812; C2 processes 812, 813 (pushes sent)
  C2 dies before committing 814 -> C1 <- P0..P5, resumes P4 at 812 -> 2 pushes resent
so: parallelism per group = partitions; uncommitted work is redone after a rebalance
```

- **Max parallelism per group = partition count** (a capacity decision).
- **Rebalance** when a consumer joins, leaves, misses heartbeats, or exceeds the max poll interval. Eager rebalance stops everyone; **cooperative (incremental)** moves only what must move. Bound per-batch work or move it off the poll loop.

## Kafka durability: replication, ISR, acks

> **Black box:** Each partition has a **replication factor** (commonly 3): one **leader** (takes writes, usually reads) and followers. **ISR** = leader + caught-up followers (e.g. within 30 s). A record is committed once all ISR have it; the **high watermark** marks committed offsets consumers can see; new leaders come from the ISR (unclean election, off by default, trades loss for availability). Producer **acks**: `0` (no wait, may lose), `1` (leader only, lost if leader dies first), `all` (all ISR). **min.insync.replicas** rejects `acks=all` writes when ISR is too small. **Use when:** standard durable setup = **RF 3, min.insync.replicas 2, acks=all**: every acked write on >= 2 brokers, one broker can be down. **Cost:** slightly higher latency; with two down, writes are refused (consistency over availability). E.g. P4 on brokers 1, 2, 3: broker 3 dies -> ISR {1, 2}, writes continue; broker 2 dies too -> ISR {1} < 2 -> producer gets `NotEnoughReplicas`.

> **Black box:** **Log compaction** keeps at least the latest record per key; a null-value **tombstone** deletes a key (and is removed later). **Use when:** changelog topics of current state (latest profile per user, CDC snapshots, stream state backups); a new consumer rebuilds state from the start. **Cost:** not a history of all changes. E.g. `(r_77,open) (r_12,open) (r_77,closed) (r_12,null)` compacts to `(r_77,closed)`.

## Delivery semantics

**How it works** · Where the consumer commits relative to processing decides the semantics.

| Semantics | Consumer pattern | Gain | Pay | Use |
|---|---|---|---|---|
| **At-most-once** | Commit, then process | No duplicates | Crash after commit skips message | Metrics where a gap is OK |
| **At-least-once** | Process, then commit | No loss | Crash before commit reprocesses (also producer retries) | Default for business events |
| **Exactly-once** (Kafka EOS) | Transactions | No loss/dupes inside Kafka | Overhead, config, Kafka-only | Stream jobs, aggregations |

**Example** · `loyalty-service` reads P4, committed offset 41, crashes mid-batch.

```text
at-most-once:  commit(43) -> CRASH mid-41 -> new owner starts at 43: 41, 42 lost
at-least-once: process 41 -> process 42 -> CRASH before commit(43) -> new owner at 41:
               41 and 42 processed again (points added twice unless idempotent)
```

- **Idempotent producer** (default in recent clients): producer ID + per-partition sequence number, so retried sends are not written twice and order survives retries.
- **Transactions**: write several partitions **and** commit consumer offsets atomically; consumers use `isolation.level=read_committed`. Exactly-once for **Kafka -> process -> Kafka**.
- Not covered: external side effects (card charge, SMS, Postgres write). A crash between side effect and offset commit repeats it. **End-to-end exactly-once needs idempotent consumers** (or output + offset in one external transaction).

> **Say it like this:** "Kafka's exactly-once covers Kafka-to-Kafka processing. Anything with an external side effect is at-least-once, so the consumer is idempotent: it dedupes on the event ID in the same transaction as its write."

## Designing an idempotent consumer

**How it works** · Each event carries a producer-set **event ID** (retries keep it). Three options:

1. **Dedupe table in the same transaction**: crash leaves both or neither; purge rows after a window longer than any redelivery.
2. **Naturally idempotent ops**: upsert by key (`ON CONFLICT (order_id) DO UPDATE SET status='PAID'`), absolute values ("set points = 550") not increments, **version checks** that also guard reordering: `UPDATE accounts SET balance=:b, version=:v WHERE id=:id AND version < :v`.
3. **Store the offset with the output** in the same DB transaction; on restart read it from the DB; offsets <= stored are duplicates.

**Example** · `OrderDelivered` for `o_90311` (event `evt-7f1c`) is redelivered after a rebalance; Asha earns 50 points.

```sql
BEGIN;  -- 1st delivery: insert succeeds, points 500 -> 550, COMMIT, commit offset
  INSERT INTO processed_events (event_id) VALUES ('evt-7f1c');  -- PK on event_id
  UPDATE loyalty SET points = points + 50 WHERE user_id = 'u_1842';
COMMIT;
-- 2nd delivery: INSERT hits duplicate key -> ROLLBACK, commit offset, skip: still 550
```

## Ordering in practice

**How it works** · Per-entity order needs all four: (1) **key by entity ID**; (2) producer must not reorder on retry (idempotent producer; otherwise multiple in-flight requests can reorder); (3) consume a partition sequentially, or dispatch by `hash(key)` to threads so one key is never concurrent, committing only up to the **lowest offset still in progress**; (4) do not re-partition mid-stream. Global order needs a single partition, capping throughput at one partition + one consumer; usually per-entity order is enough.

**Example** · P4 consumer with 2 threads, dispatch by key.

```text
812 o_90311 PLACED   -> thread 0 (slow: calls payment)
813 o_90400 PLACED   -> thread 1, done in 5 ms
814 o_90311 ACCEPTED -> thread 0, queued behind 812 (same key never runs concurrently)
commit point: 812 still in progress -> committed offset stays 812, even though 813 is done
812 done, 814 done -> commit 815
so: per-order order holds; a crash redoes at most 812-814, never skips one
```

## Poison messages, retries, DLQ

**How it works** · A **poison message** always fails (bad payload, edge-case bug, deleted entity). Naive retry blocks the whole partition (**head-of-line blocking**).

**Example** · `OrderPlaced` at P1:530 references a restaurant deleted by an admin; `rider-service` throws every time.

```text
naive:  retry 530 forever -> P1 frozen, lag grows behind it ("Kafka is slow")
better: order-events --fail--> order-events.retry.1m --fail--> .retry.10m --fail--> .DLQ
        P1 moves on to 531; the DLQ alert fires; fix data, replay 530 from the DLQ
```

- **Transient** errors (payment timeout): a few retries with exponential backoff + jitter, in-process then retry topics so the main partition moves. **Permanent** errors (bad data): straight to the DLQ.
- **DLQ** must be monitored, with tooling to inspect, fix and **replay**; an unwatched DLQ is a silent data-loss bin.
- Trade-off: retry topics let later messages for the same key overtake (o_90311 ACCEPTED before its PLACED retry); if order is critical, park the key's later messages or block the partition and alert.

## Backpressure and consumer lag

**How it works** · **Backpressure** slows producers when consumers lag; a log instead absorbs the gap: **consumer lag** = log end offset - committed offset.

**Example** · Dinner peak: 120 orders/s x ~5 events each = 600 events/s over 6 partitions = ~100/s per partition. P4: log end 12,000, group `notification` committed 9,500 -> lag 2,500 messages ≈ 25 s behind: Asha's "order accepted" push arrives 25 s late.

- **Lag in time** (oldest unprocessed is 25 s old) is what matters for SLOs.
- Small steady = healthy; steadily growing = consumers too slow (add consumers up to partitions, speed up, batch writes); jumps and stays = stuck consumer or poison message.
- Lag beyond retention = real data loss. Monitor per partition and group; a hot or stuck partition hides in averages.
- Queues: watch depth and oldest-message age; brokers may block publishers when memory is full.

## Dual writes and the transactional outbox

**How it works** · Save order then publish: crash between = order with no event. Publish then save: insert fails = event for a nonexistent order. This is the **dual-write problem**; distributed transactions across DB and broker are rarely available or worth it. **Outbox**: write the event to an outbox table **in the same DB transaction**; a **relay** publishes it.

**Example** · `order-service` places `o_90311`.

```text
BEGIN; INSERT INTO orders (o_90311, u_1842, r_77, PLACED, 450)
       INSERT INTO outbox (evt-a1, o_90311, OrderPlaced, {...}); COMMIT  -- both/neither
relay (CDC tailing the WAL) sees outbox row evt-a1 -> Kafka order-events key o_90311
relay crashes before recording "evt-a1 sent" -> on restart publishes evt-a1 again
consumers dedupe on event_id evt-a1 -> one SMS, one loyalty update
```

- Relay styles: **polling publisher** (`SELECT` unsent, publish, mark; simple, but polling load, delay, careful ordering) or **CDC** (Debezium-style, tails the commit log; low latency, commit order, extra infrastructure).
- Publishing is **at-least-once**, so consumers dedupe on `event_id`.
- Plain CDC publishes raw row changes (fine for search/cache replication) but couples consumers to your schema; the outbox publishes deliberate, versioned domain events.

> **Say it like this:** "The order and its event go into the same DB transaction via an outbox table, and a CDC relay publishes it to Kafka. Publishing is at-least-once, so consumers dedupe on the event ID."

## Event-driven architecture

**How it works** · **Command** = "please do X" (`ChargePayment`): imperative, one handler, can be rejected, sender knows receiver. **Event** = "X happened" (`OrderPlaced`): past-tense fact, zero or many consumers, cannot be rejected, producer does not know consumers.

- **Event notification**: thin (`{"type":"OrderPlaced","orderId":"o_90311"}`), consumers call back; fresh data, but callback load and coupling (`order-service` down = consumers stall).
- **Event-carried state transfer**: fat event (items, ₹450, address); consumers keep local copies; autonomy, but bigger events, duplicated data, schema evolution matters (schema registry, backward-compatible changes).

**Example** · The same order flow two ways.

```text
CHOREOGRAPHY: OrderPlaced -> payment-service -> PaymentSucceeded -> restaurant accepts
              -> OrderAccepted -> rider-service assigns rd_5521 -> RiderAssigned
              (no coordinator; "where is o_90311 stuck?" needs tracing across 4 services)
ORCHESTRATION: order-service: ChargePayment ok; RequestAcceptance ok; AssignRider ok
              (one place holds the state; payment fails -> it runs compensations = saga)
```

Choreography is loose but the flow is implicit; orchestration is explicit but central. Failure handling = **saga** (chapter 13).

> **Black box:** **Event sourcing** stores the append-only event list as truth and replays it for state (wallet: Opened, Credited 100, Debited 30, Credited 50 -> 120), often with snapshots. **CQRS** splits a write model from read models (denormalized tables, search index, cache) updated by a projector from events. Independent ideas, often paired. **Use when:** audit-heavy event-shaped domains (ledgers, workflows); read and write sides needing separate scaling. **Cost:** projections to query, eternal versioned schemas, compensating events, eventual consistency of read models, steep learning curve; not for plain CRUD.

## Queue vs Kafka: choosing

**How it works** · Pick by consumption shape.

| Need | Lean towards |
|---|---|
| Background jobs to workers; per-message acks, retries, delays, priority | Queue (SQS/RabbitMQ) |
| Small team, managed, no partition planning; add workers freely | Queue |
| Many independent consumers; replay / rebuild read models | Kafka |
| Very high throughput (clicks, logs, CDC); per-key order at scale | Kafka |
| Stream processing (windows, joins) | Kafka + stream engine |

**Example** · QuickBite: dish-photo resizing -> SQS (one job type, per-job retry, add workers freely); `order-events` -> Kafka (notification, analytics, loyalty all consume; per-order order; replay to rebuild a read model); rider pings at ~25,000 writes/s -> Kafka keyed by `rider_id` (throughput, per-rider order).

Queue: deleted on ack, scale freely, built-in DLQ/delays, low ops. Kafka: retained log, offsets per group, scale up to partitions, replay, retry topics you build, very high throughput, moderate-high ops.

## In one minute

- Async buys decoupling, spike buffering, isolation, retries, fan-out; costs eventual consistency and new failure modes.
- Queue = one consumer, deleted on ack, at-least-once via redelivery; pub/sub = copy to all; log = retained, offsets per group, replay.
- Kafka: topic -> partitions -> offsets; key -> `hash mod N`; order only within a partition; do not change N on keyed topics.
- One consumer per partition per group, so parallelism = partitions; rebalances pause and redo uncommitted work.
- Durable: RF 3, min.insync.replicas 2, acks=all.
- Kafka exactly-once is Kafka-to-Kafka only; external effects need idempotent consumers (dedupe table, upserts, version checks).
- Bounded retries, retry topics, a watched DLQ; track lag in time.
- Outbox + relay fixes dual writes; events are facts, commands are requests; event sourcing and CQRS are not defaults.

## Go deeper

Full chapter 11: sections 11.4 (Kafka deep dive), 11.5 (delivery semantics), 11.6 (idempotent consumer), 11.10 (transactional outbox).
