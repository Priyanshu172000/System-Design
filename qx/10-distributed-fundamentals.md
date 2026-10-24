# 10 — Distributed systems fundamentals: quick notes

Once data lives on several machines, simple questions get hard: did that write happen? Which
update came first? Who is the leader? This chapter gives the core ideas behind every replicated
database, queue and coordination service.

## 1. Why distributed is hard

- Across a network, a request can be **lost, delayed, duplicated**, or answered after you gave
  up, and you can't tell which.
- The **eight fallacies** are false beliefs beginners hold, such as "the network is reliable" and
  "latency is zero". *e.g.* a call inside one data center takes ~0.5 ms; across regions,
  ~50–200 ms. So avoid chatty calls; batch and cache instead.
- Ways a machine (a **node**) can fail:

| Failure | Means | QuickBite example |
|---|---|---|
| **Crash** | Stops for good | A payment-service pod is killed |
| **Crash-recovery** | Restarts, loses what was in memory | Pod restarts after running out of memory |
| **Omission** | Some messages never arrive | A full queue drops packets |
| **Network partition** | Both sides alive, but cut off from each other | The link between two data-center zones fails |
| **Gray failure** | Up and "healthy", but slow or failing some requests | A pod with a dying disk answers 10% of requests in 5 s |
| **Byzantine** | Lies or sends conflicting messages | Matters for blockchains; normal designs ignore it |

- Gray failure is often the worst: a dead node is removed fast, a slow one keeps getting traffic.
- A **timeout** ("give up after 2 s") is only a guess. After it fires, you **don't know** if the
  work happened. *e.g.* order-service asks payment-service to charge ₹450 for `o_90311` and hears
  nothing. Maybe it was never charged; maybe it was charged and only the reply was lost. A blind
  retry could charge Asha twice.

## 2. CAP and PACELC

- A **replica** is a copy of the data on another machine.
- **CAP:** when a network **partition** happens, a cut-off replica must choose:
  - **C (consistency):** refuse to answer rather than give stale data. Here C means
    **linearizable**: every read sees the latest write, as if there were one copy.
  - **A (availability):** answer anyway, maybe with old data.
- *e.g.* Biryani stock lives on replicas A, B (zone 1) and C (zone 2). The zones get cut off.
  Spice Route marks it sold out on A and B. Asha's read hits C, which still says 3 left.
  CP: C returns an error. AP: C says 3, and she orders a dish that's sold out.
- **P is not optional:** partitions just happen. "Pick 2 of 3" is a misreading. CAP's C is also
  not ACID's C (ACID's C means "rules hold").
- **PACELC** adds normal times: **E**lse (no partition), choose **L**atency or **C**onsistency.
  *e.g.* waiting for another region's replica adds ~50–200 ms per stock write. So stock
  reservation picks consistency; the menu catalog can pick availability.

## 3. Consistency models: what a read may return

From strongest (easy to reason about, slower) to weakest (cheap, surprising):

| Model | Promise | What breaks without it |
|---|---|---|
| **Linearizable** | Acts like one copy, in real time | Two people redeem the same one-time coupon |
| **Sequential** | One order everyone agrees on, maybe behind real time | Clients disagree on order |
| **Causal** | Cause shows before effect | Chat shows "Sure, no onions" before Asha's "Please, no onions" |
| **Read-your-writes** | You always see your own writes | Asha saves a new address, refreshes, sees the old one |
| **Monotonic reads** | Time never goes backwards for you | Status shows PREPARING, then ACCEPTED on refresh |
| **Eventual** | Copies agree once writes stop | Rating count 1,204 here, 1,201 there |

- Read-your-writes and monotonic reads are per-user **session** guarantees. Fixes: read from
  the leader right after a write; pin a user to one replica.
- **Quorum:** with N copies, a write waits for W of them to confirm, and a read asks R of them.
  If **W + R > N**, the two groups always share a copy. *e.g.* N=3, W=2, R=2: write goes to
  {A, B}, read asks {B, C}, and B has the new value.

## 4. Clocks and ordering

- Machine clocks **drift**. **NTP** (a time-sync service) corrects them to within a few ms, but
  never perfectly, and it can make the clock jump. Use a **monotonic clock** (one that only moves
  forward) to measure durations.
- So timestamps can't order events across machines. *e.g.* restaurant-service's clock is 30 ms
  ahead. The rider's later "PICKED_UP" gets an earlier timestamp than "PREPARING", so
  "latest timestamp wins" keeps the old status. The newer write is silently lost.
- **Lamport clock:** a counter per node instead of a time of day. Add 1 at each event. On
  receiving a message, set `counter = max(mine, theirs) + 1`. If a caused b, then
  `L(a) < L(b)`. The reverse isn't guaranteed: a smaller number doesn't prove a came first.
- **Vector clock:** one counter per node. It can tell "happened before" from "happened at the
  same time". *e.g.* cart `[1,0]` {book} vs `[0,1]` {pen}: neither is newer, so merge both.
- **HLC (hybrid logical clock):** close to wall time but still respects cause and effect. Some
  distributed SQL databases use it.

## 5. Consensus and Raft

- **Consensus:** nodes agree on one value (who is leader, what's next in the log) even when some
  crash. If all agree on a **log** of commands, all end in the same state, like copies of the
  same to-do list applied in the same order.
- **Raft**, the popular algorithm:
  - Roles: **leader**, **follower**, **candidate**. Time is cut into numbered **terms**.
  - Followers expect heartbeats. If silent for a random 150–300 ms, a follower runs for leader.
  - Each node votes **once per term**, and only for a candidate whose log is at least as
    up to date (the **election restriction**). Majority wins, so at most one leader per term.
  - The leader copies each entry to followers. Once a **majority** has it, it's **committed**
    and never lost.

```text
Nodes  Majority  Survives
  3       2      1 down
  4       3      1 down   <- even sizes buy nothing
  5       3      2 down   (2f+1 nodes survive f failures)
```

- In a 3|2 split, only the 3-side can commit. Cost: every write waits for a majority. Keep
  clusters small (3–7) and data small.
- You use it via **etcd / ZooKeeper / Consul**: config, leader election, service discovery,
  locks. Not a general database.

## 6. Distributed locks and fencing tokens

- A cross-machine lock needs a **lease**: it expires unless renewed, so a crashed holder can't
  keep it forever.
- A lease alone is unsafe: a paused holder doesn't notice it expired.
- **Fencing token:** a number that grows with each grant. Storage rejects any write with a lower
  number than it has already seen.

```text
W1 gets lock, token 33 -> 15 s pause (lease was 10 s)
W2 gets lock, token 34 -> writes payouts         storage max = 34
W1 wakes, writes with 33 -> REJECTED (Spice Route not paid twice)
```

- A simple Redis lock is fine to avoid duplicate work, but not for correctness. Often simpler:
  no lock, just `UPDATE ... WHERE version = 7`.

## 7. Idempotency and exactly-once

- **Two generals problem:** over a lossy network, the last messenger never knows if its message
  arrived. So "deliver exactly once" is impossible.
- **Idempotent** = doing it twice equals once. *e.g.* `SET status = 'DELIVERED'` yes;
  `wallet = wallet + 50` no.
- **At-most-once** (no retry, may lose), **at-least-once** (retry, may duplicate), and
  **exactly-once effect** = at-least-once + the receiver ignores duplicates.
- **Idempotency key:** a unique id sent with every retry. *e.g.* on a retry of
  `Idempotency-Key: 7f1c-o_90311`, payment-service returns the stored result, no second charge.

## 8. Unique ID generation

| Approach | Size | Sorted by time? | Catch |
|---|---|---|---|
| UUID v4 (random) | 128 bit | No | Random inserts scatter the index |
| UUID v7 | 128 bit | Roughly | Still big; reveals creation time |
| DB auto-increment | 64 bit | Yes | One DB is a bottleneck |
| Ticket server (hands out ranges of 1,000) | 64 bit | Mostly | Must stay up; gaps |
| **Snowflake** | 64 bit | Roughly | Breaks if the clock goes backwards |

- **Snowflake:** each server builds IDs alone: 1 sign bit + 41 bits milliseconds (~69 years) +
  10 bits machine id (1,024 machines) + 12 bits counter (4,096 per ms ≈ 4.1 M/s per machine).
  *e.g.* order-service worker 37 makes `o_` ids with no coordination, and they fit a `BIGINT`.

## Remember these 5

1. A timeout means "no reply", not "failed"; so every retry must be idempotent.
2. CAP only bites during a partition: refuse (C) or answer stale (A).
3. Never order events across machines by wall-clock timestamps.
4. Raft: one vote per term, majority to elect and commit; 2f+1 nodes survive f failures.
5. Exactly-once is really at-least-once + idempotency keys; locks need fencing tokens.
