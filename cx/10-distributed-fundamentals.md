# 10 — Distributed systems fundamentals

*Why it matters:* once data lives on several machines, "did that write happen?", "which came first?" and "who is leader?" get hard, and these are the words senior engineers use when they argue about correctness.

## Why distributed is hard: fallacies and failure modes

**How it works** · Locally a call returns or the process crashes. Across machines a request can be lost, delayed, duplicated, or answered after the caller gave up, and the caller cannot tell which.

- **Eight fallacies** -> consequence: network is reliable -> timeouts, retries, idempotency; latency is zero (same DC ~0.5 ms, cross-region ~50–200 ms) -> avoid chatty calls, batch, cache; bandwidth is infinite -> compress, paginate, move compute to data; network is secure -> TLS, service auth; topology doesn't change -> service discovery, no hard-coded IPs; one administrator -> contracts, versioned APIs; transport cost is zero -> efficient formats, locality; network is homogeneous -> standard protocols, compatibility tests.
- **Failure modes**: **crash (fail-stop)** (stops for good); **crash-recovery** (restarts, maybe with lost in-memory state); **omission** (some messages not sent/received); **network partition** (both sides alive, each thinks the other is dead); **gray failure** (up, passes health checks, but slow or failing some requests: bad disk, GC, noisy neighbor); **Byzantine** (lies or sends conflicting messages).
- **Gray failure** is often worst: a dead node is removed fast, but a `payment-service` pod with a dying disk answering 10% of requests in 5 s keeps getting traffic.

> **Black box:** **Byzantine faults** (arbitrary or malicious nodes). **Use when:** blockchains, some safety-critical systems. **Cost:** much heavier protocols; typical datacenter designs assume non-Byzantine nodes.

**Timeouts** · A timeout is a guess: too short -> false "dead", retries, duplicate work, cascading failovers; too long -> slow detection. After a timeout you **do not know whether the operation happened**, so retries need **idempotency**.

**Example** · `order-service` asks `payment-service` to charge ₹450 for `o_90311`, timeout 2 s, and hears nothing. Possible truths:

```text
1. request lost on the way                   -> not charged
2. payment-service crashed before charging   -> not charged
3. charged, crashed before replying          -> charged
4. charged, reply lost                       -> charged
5. charged, reply arrives at 3 s (just slow) -> charged
so: from order-service all five look like silence; a blind retry double-charges in 3-5
```

## CAP, stated precisely, and PACELC

**How it works** · In a replicated system, **when a network partition happens**, you choose between **Consistency** (here = **linearizability**: every read sees the latest completed write, as if one copy) and **Availability** (every request to a non-failing node gets a non-error response, maybe stale). **P is not a choice**: partitions happen.

**Example** · Dish stock for Spice Route's biryani on 3 replicas: A, B in zone 1, C in zone 2.

```text
20:00:00  link between zones fails: {A, B} | {C}
20:00:05  r_77 writes stock=0 ("sold out") -> A, B ack (majority) -> success
20:00:07  Asha's read lands on C, which still has stock=3
   CP: C cannot reach a majority -> 503 / wait     (consistent, not available)
   AP: C answers stock=3 -> she orders a sold-out dish  (available, not linearizable)
20:02:00  link heals; C learns stock=0 (AP: the order must be rejected/refunded)
so: the choice exists only during the partition, and only for the cut-off side;
    A and B kept serving reads and writes the whole time
```

- **CP** shape: minority side refuses until healed (etcd, ZooKeeper, majority-write DBs). **AP** shape: both sides serve, reconcile later (LWW, merges); Dynamo-style like Cassandra at low consistency levels.
- Misreadings: "pick 2 of 3" (P is mandatory); "C = ACID C" (ACID C = invariants hold); "A = 99.99% uptime" (it is a strict per-request property; a CP system can be highly available in practice, as A and B show); "the DB is CP, period" (often tunable per operation); "CAP is about latency" (that is PACELC).

**PACELC**: if **P**artition, **A** or **C**; **E**lse, **L**atency or **C**onsistency. The "else" is felt daily: with no partition, a stock write that waits for a replica in another region pays ~50–200 ms; acking from the local replica is fast but readers elsewhere may see stale stock.

| Shape | P | E |
|---|---|---|
| Dynamo-style (Cassandra, DynamoDB default reads) | A | L |
| Consensus / single leader with sync replication | C | C |
| Single leader, async replicas, replica reads | depends | L |

> **Say it like this:** "CAP only bites during a partition: a replica either refuses to answer, staying consistent, or answers with possibly stale data, staying available. For stock reservation I'd choose consistency; for the menu catalog, availability."

## Consistency models ladder

**How it works** · A **consistency model** is a contract on what a read may return. Stronger = easier to program, more coordination.

```text
strongest  Linearizable  - one copy, respects real time
           Sequential    - one global order matching each client's order, not real time
           Causal        - cause before effect for everyone
           Session       - read-your-writes, monotonic reads (per client; in causal)
weakest    Eventual      - replicas converge if writes stop
```

**Example** · Linearizable: `rider-service` writes `o_90311` status = PICKED_UP, from t=10 to t=20 ms.

```text
read A  t=11..12  overlaps the write             -> PREPARING (legal)
read B  t=12..13  overlaps the write             -> PICKED_UP (also legal)
read C  t=14..15  still overlaps, but starts after B ended -> must be PICKED_UP
read D  t=21..22  starts after the write ended   -> must be PICKED_UP
so: one instant per op; once anyone sees the new value, no later read may see the old
```

Needed for leader election, locks, uniqueness (a coupon code redeemed once), balances. **Sequential**: one total order for all, consistent per client, but may lag real time across clients.

**Example** · Weaker guarantees, one QuickBite violation each:

| Model | Violation Asha could see | Fix / cost |
|---|---|---|
| **Causal** | Order chat shows restaurant's "Sure, no onions" before her "Please, no onions" | Track dependencies (e.g. vector clocks) |
| **Read-your-writes** | Saves new address, refresh hits a lagging replica, old address shown | Read from leader briefly after a write, or only from replicas caught up to the write's log position |
| **Monotonic reads** | Status shows PREPARING, then ACCEPTED on the next refresh (lagging replica) | Pin a user to one replica (hash user ID) |
| **Eventual** | Restaurant rating count 1,204 here, 1,201 there, converging | Cheapest; likes, counters, caches |

**Quorums recap**: N replicas, write waits for W acks, read asks R. If **W + R > N**, read and write sets overlap. E.g. N=3, W=2, R=2: write v7 -> {A, B}; read -> {B, C} returns B:v7, C:v6, newest wins = v7. Not full linearizability alone (concurrent writes, partial failures, sloppy quorums); majority quorums also underpin consensus.

## Clocks and ordering

**How it works** · Quartz clocks drift (roughly tens of ppm, seconds per day); **NTP** corrects to roughly ms in a good DC, tens of ms over the internet. Problems: **skew**, **jumps** (NTP steps clocks; use a **monotonic clock** for durations), leap seconds, VM pauses.

**Example** · Node A (`restaurant-service`) runs 30 ms ahead of node B (`rider-service`).

```text
real order: A writes first, B writes after reading A's value
A: o_90311 status=PREPARING  at A-local 20:10:00.050
B: o_90311 status=PICKED_UP  at B-local 20:10:00.040   (really ~10 ms later)
last-write-wins by timestamp: 050 > 040 -> keeps PREPARING
so: the newer write is silently lost; wall clocks cannot order cross-machine events
```

**Lamport clock**: per-node counter; increment before each event/send; attach to messages; on receive `counter = max(local, received) + 1`.

**Example** · Three nodes.

```text
A: (1) send m1 -----------------------------+
B: (1) local  (2) local  (3) recv m1: max(2,1)+1 = 3  (4) send m2 --+
C: (1) local ........................................ (5) recv m2: max(1,4)+1 = 5
so: send(m1)=1 < recv=3 < send(m2)=4 < C's recv=5, cause always before effect
```

If a **happened before** b then `L(a) < L(b)`, but not the reverse: B's local event (2) and A's send (1) are concurrent despite 1 < 2. Tie-break by node ID for a total order all nodes agree on.

> **Black box:** **Vector clocks** keep one counter per node; if V <= W everywhere, V happened before W, otherwise **concurrent**. E.g. cart on A `[1,0]` {book} vs B `[0,1]` {pen}: neither dominates -> merge {book, pen}. **HLC** (hybrid logical clock) keeps timestamps near wall time while respecting causality; used by some distributed SQL DBs (a related approach waits out bounded clock uncertainty from special hardware). **Use when:** detecting conflicts / time-ordered transactions. **Cost:** vectors grow with the number of writers.

> **Common mistake:** Ordering events from different servers by `created_at`. With skew a reply can look older than its message. Use a single sequencer, a log offset, or logical clocks.

## Consensus and Raft

**How it works** · Nodes must **agree** on a value despite crashes and delays: **agreement** (no two decide differently), **validity** (value was proposed), **termination** (non-crashed nodes decide while a majority can talk). Agreeing on a **replicated log** of commands gives **state machine replication**. In theory no deterministic algorithm guarantees consensus in a fully async system with one crash; Paxos, Raft, ZAB stay always **safe** and make progress when timing is reasonable.

**Raft**: roles **follower**, **candidate**, **leader**; time split into numbered **terms** (a logical clock): older-term messages are rejected, seeing a higher term makes you a follower. **Election**: followers expect heartbeats; each has a **randomized timeout** (e.g. 150–300 ms); on timeout: term+1, candidate, vote for self, RequestVote(term, lastLogIndex, lastLogTerm). Each server votes **at most once per term**, only for a candidate whose log is at least as up-to-date (last term, then last index: the **election restriction**). Majority wins => **at most one leader per term**; split vote -> retry with new random timeouts. **Replication**: leader appends `(index, term, cmd)`, sends AppendEntries with `prevLogIndex/prevLogTerm`; a follower without a match rejects, the leader backs up and resends, the follower overwrites conflicting uncommitted entries (**log matching**). **Commit**: once on a majority (and from the current term; earlier entries commit with it).

**Example** · 5-node etcd holding QuickBite's shard map. Leader S1 (term 4) dies.

```text
log terms: S1 (down) [1,1,3,4]  S2 [1,1,3,4]  S3 [1,1,3,4]  S4 [1,1,3]  S5 [1,1,3]
idx 4 is on S1,S2,S3 = 3 of 5 -> committed in term 4
timeouts: S3 180 ms, S4 210, S2 260, S5 290 -> S3 fires first
S3: term 5, votes self, RequestVote(5, lastIdx=4, lastTerm=4)
S2: same log -> yes   S4: S3's last term 4 > its 3 -> yes   -> 3 of 5: S3 leads term 5
(had S4 fired first: S2, S3 refuse, last term 4 > 3; S4 + S5 = 2 < 3, cannot win)
so: one vote per term + majority = one leader; the election restriction keeps idx 4
```

**Example** · Commit in term 5: client writes `shard7 -> order-db-3`.

```text
S3 appends idx 5 (term 5); AppendEntries(prevIdx=4, prevTerm=4, [5])
S2: has idx 4 term 4 -> match -> stores idx 5            copies: S3, S2 = 2
S4: no idx 4 -> reject; S3 backs up: (prevIdx=3, prevTerm=3, [4,5]) -> stores 4, 5
                                                          copies: S3, S2, S4 = 3 of 5
S3 marks idx 5 committed, applies, replies OK; followers apply on the next heartbeat
S5 catches up the same way later (not needed for the commit)
so: a commit waits for the fastest majority; S1 returns in term 4, sees 5, steps down
```

```text
Nodes  Majority  Tolerates          2f+1 nodes tolerate f failures; any two majorities
  3       2         1               share a node. Partition 3|2: the 3-side elects and
  4       3         1  <- no gain   commits; the 2-side cannot commit; an old leader there
  5       3         2               steps down on seeing a higher term (CP behavior).
```

Committed entries are never lost: a new leader needed a majority, majorities overlap, and voters refuse candidates missing them. Costs: every write waits for a majority round trip, one leader caps throughput, more nodes = slower commits; so clusters are small (3–7) and hold small, critical data.

> **Black box:** Coordination services: **etcd** (Raft), **ZooKeeper** (ZAB), **Consul** (Raft); Kafka's KRaft uses a Raft quorum. **Use when:** config with watches, leader election (create a key; it vanishes when the session dies), service discovery with leases, locks, shard ownership metadata. **Cost:** small data and modest write rates only, not a general DB.

> **Say it like this:** "Raft elects one leader per term by majority vote, the leader replicates log entries, and an entry is committed once a majority has it. Five nodes survive two failures, and a minority partition can't commit, so no split brain."

## Distributed locks and fencing tokens

**How it works** · Cross-machine mutual exclusion needs a lock service with **leases** (expire unless renewed), or a crashed holder locks forever. A lease alone is unsafe: a paused client cannot notice time passed between "lease valid?" and "write". A **fencing token** increases per grant (Raft log index, ZooKeeper zxid); clients send it on every write and **the storage rejects** tokens lower than the highest seen.

**Example** · Only one worker may run the nightly restaurant-payout job; etcd lease = 10 s.

```text
t=0   W1 acquires lease -> token 33; starts computing payouts
t=1   W1 enters a 15 s GC pause
t=10  lease expires
t=11  W2 acquires -> token 34; writes payouts(token 34)  storage: max_token = 34
t=16  W1 wakes, still believes it holds the lock, writes payouts(token 33)
      without fencing: second payout run -> Spice Route paid twice
      with fencing:    33 < 34 -> REJECTED
so: safety lives in the resource that checks tokens, not in the client's belief
```

| Approach | Safe vs pauses? |
|---|---|
| Single Redis `SET NX PX` | No (async failover can lose it too); fine for efficiency |
| Consensus lock without fencing | No |
| Consensus lock + storage-checked fencing token | Yes (needs conditional write support) |
| No lock: conditional write `UPDATE ... WHERE version = 7` | Yes, often simpler |

## Two generals, idempotency, exactly-once

> **Black box:** **Two generals problem**: messengers may be captured, so any finite ack-of-ack exchange leaves the last sender unsure. **Use when:** explaining why exactly-once delivery over a network is impossible. **Cost:** systems settle for at-least-once + idempotency, or majority consensus with explicit commit rules.

**How it works** · **Idempotent** = doing it twice equals once (`SET status = 'DELIVERED'` yes, `wallet = wallet + 50` no). Timeouts force retries, retries make duplicates.

| Delivery | Meaning | How |
|---|---|---|
| At-most-once | May lose, never duplicate | No retry |
| At-least-once | Never lose, may duplicate | Retry until acked |
| Exactly-once *effect* | Takes effect once | At-least-once + receiver dedupe/idempotency |

**Example** · The `o_90311` charge from the timeout example, with an **idempotency key**.

```text
POST /payments  Idempotency-Key: 7f1c-o_90311  amount=450
   payment-service: key new -> charge ₹450 -> store 7f1c -> {p_3301, SUCCESS}
   reply lost; order-service times out at 2 s
POST /payments  Idempotency-Key: 7f1c-o_90311  amount=450   (retry, same key)
   payment-service: key seen -> return {p_3301, SUCCESS}, no second charge
so: at-least-once delivery + a dedupe table = exactly-once effect
```

- **Natural idempotency**: upsert by ID, "set status SHIPPED" not "advance status". **Version checks**: `WHERE id = ? AND version = ?`.
- A framework's "exactly-once" covers its own state only; emails, payments, external calls still need idempotency.

## Unique ID generation

**How it works** · Sharded DBs and many services need collision-free IDs generated in many places, ideally time-sortable and 64-bit (`BIGINT` indexes well).

- **UUID v4**: 122 random bits. **UUID v7** (standardized 2024): ms timestamp in high bits, roughly time-ordered.
- **DB auto-increment**: simple, ordered; SPOF and bottleneck; shards need offsets (1,3,5 / 2,4,6).
- **Ticket server**: dedicated DB(s) issuing IDs (odd/even pair), clients fetch ranges (e.g. 1,000); gaps fine.
- **Snowflake-style**: 64 bits built locally, `id = (ts << 22) | (worker << 12) | seq`.

```text
0 | 41 bits ms since custom epoch | 10 bits machine ID | 12 bits sequence
    2^41 ms ≈ 69.7 years            2^10 = 1,024 gens    2^12 = 4,096 per ms per generator
                                                         (4,096 x 1,000 ≈ 4.1 M/s)
```

**Example** · `order-service` worker 37, custom epoch 2024-01-01 00:00 UTC, an order at 20:00:00.123 IST (14:30:00.123 UTC) on 2026-10-10.

```text
ts  = 1,013 days x 86,400,000 + 52,200,123 ms = 87,575,400,123   (37 bits, fits in 41)
id  = (87575400123 << 22) | (37 << 12) | 5   = 367317851037650949
next id same ms: seq 6 -> 367317851037650950;  next ms, seq 0 -> 367317851041845248
decode: id >> 22 = 87575400123   (id >> 12) & 1023 = 37   id & 4095 = 5
clock steps back to ...118 after issuing at ...123 -> wait 5 ms (or refuse), never reuse
so: generated with no coordination, sorts by time, fits a BIGINT
```

- Sequence overflow (4,096 in one ms) -> wait for next ms. Sorts by time only as well as clocks agree. Machine IDs must be unique (config or coordination service).

| Approach | Size | Ordered | Pay |
|---|---|---|---|
| UUID v4 | 128 | No | Random inserts scatter a B+ tree (page splits) |
| UUID v7 | 128 | Roughly | Still 128 bits; leaks creation time |
| Auto-increment | 64 | Yes | Bottleneck, SPOF, guessable |
| Ticket server / ranges | 64 | Mostly | Must be HA; gaps |
| Snowflake | 64 | Roughly | Clock-skew sensitive; leaks timing and volume |

## In one minute

- Networks lose, delay, duplicate; a timeout means "no reply", not "failed", so retries must be idempotent; gray failures are often worse than crashes.
- CAP: during a partition, the cut-off side chooses linearizability (refuse) or availability (serve stale); PACELC adds latency vs consistency in normal times.
- Ladder: linearizable > sequential > causal > session (read-your-writes, monotonic) > eventual; W + R > N gives overlap.
- Wall clocks cannot order cross-machine events; Lamport clocks respect causality, vector clocks detect concurrency.
- Raft: terms, random election timeouts, one vote per term, majority to elect and commit, election restriction; 2f+1 tolerates f.
- etcd/ZooKeeper give consensus as a service; lease locks need storage-checked fencing tokens.
- Exactly-once = at-least-once + idempotent receiver (idempotency key).
- Snowflake: 1 + 41 + 10 + 12 bits, local and sortable; guard against clock rollback.

## Go deeper

Full chapter 10: sections 10.3 (CAP precisely), 10.4 (consistency models), 10.6 (Raft step by step), 10.7 (locks and fencing).
