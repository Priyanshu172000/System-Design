# 11 — Queues, streams and Kafka: quick notes

Most designs have a "queue" or "Kafka" box in the middle. This chapter covers what that box
really promises (order, delivery, durability) and what you still build yourself.

## 1. Why go asynchronous

- **Synchronous** = the caller waits for every call to finish. **Asynchronous** = do the
  essential work now and leave a note for others to handle later.
- A **broker** is the server that stores those notes (**messages**) and hands them out, like a
  post office.
- *e.g.* Asha places `o_90311`. order-service charges payment (300 ms), then **publishes**
  "OrderPlaced" (~5 ms) and replies. Asha waits ~305 ms, not 1.2 s+. SMS, analytics and loyalty
  read the event when they can.
- What you gain:
  - **Decoupling:** the sender doesn't know who listens. A new listener needs no code change.
  - **Buffering:** a burst waits in the broker.
  - **Isolation:** SMS provider down for 10 min? Messages wait; orders keep working.
  - **Fan-out:** one event, many readers.
- Costs: things happen a bit later (loyalty points appear late), harder debugging, duplicates.
- Don't use it when the caller needs the answer to continue. *e.g.* "Is the payment approved?"

## 2. Queue vs pub/sub vs log

| | Queue | Pub/sub | Log (Kafka) |
|---|---|---|---|
| Who gets a message | One worker | Every subscriber | Every group |
| After reading | Deleted | Deleted | **Kept** (e.g. 7 days) |
| Replay old messages | No | No | Yes |
| *e.g.* | Photo-resize jobs split among workers | "r_77 closed early" to push + email | `order-events` read by notification and analytics |
| Tools | RabbitMQ, SQS | SNS, Redis pub/sub | Kafka, Pulsar, Kinesis |

## 3. How a queue hands out work

- A worker takes a message, does the work, then sends an **ack** ("done, delete it").
- If the worker dies before the ack, the message comes back for another worker. So queues are
  **at-least-once**: nothing lost, but duplicates happen.
- **Visibility timeout** (SQS): a taken message is hidden for 30 s by default. *e.g.* a photo
  takes 45 s, so at 30 s a second worker also gets it and makes the same thumbnail twice. Set
  the timeout longer than the slowest job.
- **FIFO queue:** keeps order per group of messages, and drops repeats sent within a short window.

## 4. Kafka: topics, partitions, offsets, keys

- **Kafka** is a durable, append-only log that handles roughly hundreds of thousands to millions
  of messages/s.
- A **topic** is a named stream, like `order-events`. It is split into **partitions**, which are
  separate append-only lists.
- An **offset** is a message's position inside one partition (812, 813, ...).
- **Key:** the producer picks the partition with `hash(key) mod N`. Same key, same partition.

```text
key o_90311: PLACED -> P4:812   ACCEPTED -> P4:813   PREPARING -> P4:814
key o_90400: PLACED -> P1:530   (no order between P1 and P4)
```

- **Order is guaranteed only inside a partition.** So key by the thing that must stay in order
  (order id, account id).
- Reading doesn't delete. **Retention** deletes old data by time or size (default 7 days).
- Don't add partitions to a keyed topic later: `mod N` changes and per-key order breaks.

## 5. Consumer groups

- A **consumer group** is a team of readers sharing one name. Each partition goes to exactly one
  reader in the team.
- So **max parallelism = number of partitions.** *e.g.* 6 partitions, 8 readers: 2 sit idle.
- Each group saves its own **committed offset** per partition: "done up to here". Group
  "analytics" can be at 640 while "notification" is at 812.
- **Rebalance:** partitions get reshuffled when a reader joins or dies. Work done but not yet
  committed gets redone, so expect duplicates.

## 6. Durability: replicas and acks

- Each partition is copied to several brokers (**replication factor**, usually 3). One is the
  **leader**; the others copy it.
- **ISR (in-sync replicas):** the copies that are caught up.
- Producer **acks**: `0` don't wait (may lose); `1` leader only (lost if it dies first);
  `all` every in-sync copy has it.
- Standard safe setup: **RF 3, min.insync.replicas 2, acks=all**. Every confirmed write sits on
  at least 2 brokers. *e.g.* if 2 of 3 brokers die, writes are refused instead of risked.
- **Log compaction:** keep only the latest message per key. *e.g.* `(r_77, open)` then
  `(r_77, closed)` compacts to `(r_77, closed)`. Use it for "current state" topics.

## 7. Delivery guarantees

| Type | Reader does | Risk | Use for |
|---|---|---|---|
| At-most-once | Commit, then process | Crash skips messages | Metrics where a gap is OK |
| At-least-once | Process, then commit | Crash repeats messages | Default for business events |
| Exactly-once (Kafka) | Kafka transactions | Only covers Kafka to Kafka | Stream jobs |

- *e.g.* loyalty-service processes 41 and 42, then crashes before committing. The new reader
  starts at 41 again, so Asha could get points twice.
- Kafka's exactly-once can't undo an SMS or a card charge. Anything outside Kafka needs an
  **idempotent consumer**: one where handling a message twice has the same effect as once.

## 8. Building an idempotent consumer

- Every event carries an **event id** set by the producer (`evt-7f1c`).
- Option 1: a **dedupe table**. Insert the event id in the same DB transaction as the work. The
  second time, the insert fails, so skip it.
- Option 2: write absolute values. *e.g.* "set points = 550", not "add 50"; or an upsert by
  order id.
- Option 3: a **version check**: `UPDATE ... WHERE version < :v`. It also ignores late, out-of-order events.

## 9. Ordering, poison messages, lag

- Per-order ordering needs: key by order id; the producer must not reorder on retry; process one
  key at a time; never change the partition count.
- A **poison message** fails every time. *e.g.* an order for a deleted restaurant. Retrying
  forever freezes the whole partition behind it.
- Fix: retry topics with growing delays (1 min, then 10 min), then a **DLQ (dead letter
  queue)**: a parking spot for failed messages. Someone must watch it and replay them.
- **Consumer lag** = newest offset − committed offset. *e.g.* 600 events/s at peak; P4 lag of
  2,500 messages means about 25 s behind, so Asha's "order accepted" push arrives 25 s late.
- Watch lag **in seconds**, per partition. Lag that grows past retention means lost data.

## 10. The dual-write problem and the outbox

- Saving the order and publishing the event are two separate writes. A crash between them leaves
  an order with no event, or an event for no order.
- **Transactional outbox:** write the event into an `outbox` table in the **same DB
  transaction** as the order. A **relay** later reads the table and publishes to Kafka.
- The relay may publish twice, so consumers dedupe on `event_id`.
- Relay styles: poll the table, or **CDC** (change data capture: tail the DB's change log).

## 11. Event-driven design

- **Command** = "please do X" (`ChargePayment`): one handler, which can say no.
  **Event** = "X happened" (`OrderPlaced`): a fact, read by many.
- **Thin event** (just `o_90311`; readers call back for details) vs **fat event** (carries
  items, ₹450, address, so readers keep their own copy).
- **Choreography:** each service reacts to events; no boss. **Orchestration:** order-service
  directs each step. Undoing a failed multi-step flow is a **saga** (ch 13).
- **Event sourcing** (store the event list as the truth) and **CQRS** (separate write and read
  models) are for audit-heavy domains, not defaults.

## 12. Queue or Kafka?

- **Queue** for background jobs: built-in retries, add workers freely. *e.g.* dish-photo resizing on SQS.
- **Kafka** for many readers, replay, high volume, per-key order. *e.g.* `order-events`, and
  rider pings at ~25,000/s keyed by `rider_id`.

## Remember these 5

1. Go async for work the caller doesn't need to wait for.
2. Kafka orders only within a partition; key by the entity that needs order.
3. Partitions = max parallel readers per group.
4. Delivery is at-least-once in practice, so consumers must dedupe on event id.
5. Use an outbox to save data and publish its event safely.
