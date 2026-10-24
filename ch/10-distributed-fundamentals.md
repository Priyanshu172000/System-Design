# 10 — Distributed systems fundamentals

As soon as data lives on more than one machine, simple questions become hard: did that write
happen? Which update came first? Who is the leader? This chapter gives you the core ideas that
every replicated database, queue, and coordination service is built on: failure models, CAP and
consistency models, clocks, consensus (Raft), locks with fencing, exactly-once, and ID generation.
These are the words senior engineers use when they argue about correctness.

## 10.1 Why distributed is hard

On one machine, a function call either returns or the whole process crashes. Between machines,
a request can be lost, delayed, duplicated, or answered after the caller gave up, and the caller
cannot tell which.

### The eight fallacies of distributed computing

A well-known list of false assumptions engineers make when they first build networked systems:

| Fallacy | Reality | Design consequence |
|---|---|---|
| The network is reliable | Packets drop, connections reset, switches fail | Timeouts, retries, idempotency |
| Latency is zero | Same-DC round trip ~0.5 ms, cross-region ~50–200 ms | Avoid chatty calls; batch; cache |
| Bandwidth is infinite | Links saturate, cross-region transfer costs money | Compress, paginate, move compute to data |
| The network is secure | Traffic can be read or forged | TLS, auth between services (chapter 15) |
| Topology doesn't change | Nodes come and go, IPs change, autoscaling | Service discovery, no hard-coded IPs |
| There is one administrator | Many teams, vendors, cloud providers | Contracts, versioned APIs, defensive defaults |
| Transport cost is zero | Serialization CPU, egress fees | Efficient formats, locality |
| The network is homogeneous | Mixed hardware, OSes, protocols | Standard protocols, compatibility testing |

## 10.2 Failure modes

| Failure | What happens | Example |
|---|---|---|
| **Crash (fail-stop)** | Node stops and does nothing more | Process killed, machine power loss |
| **Crash-recovery** | Node stops, later restarts, possibly with lost in-memory state | Restart after OOM |
| **Omission** | Some messages are not sent or not received | Full queue drops packets, firewall rule |
| **Network partition** | The network splits; nodes on each side work but cannot reach the other side | A switch fails between two racks or regions |
| **Slow node / gray failure** | Node is up and passes health checks but is very slow or fails some requests | Disk degrading, GC pauses, noisy neighbor, one bad NIC |
| **Byzantine** | Node behaves arbitrarily or maliciously (lies, sends conflicting messages) | Compromised node, corrupted memory. Mostly relevant to blockchains and some safety-critical systems; typical datacenter designs assume non-Byzantine nodes |

```text
 Partition:
   +-------- side A --------+     X     +-------- side B --------+
   | node1   node2   client1|  (link    | node3   client2        |
   +------------------------+   down)   +------------------------+
   Both sides are alive. Each side thinks the other is dead.
```

**Gray failure** is often the worst in practice: a node that is fully dead is quickly removed by
health checks, but a node that answers 10% of requests in 5 seconds keeps receiving traffic and
drags everything down (chapter 13 covers timeouts and circuit breakers).

### Timeouts: you cannot tell slow from dead

You sent a request and got no reply. Possible reasons:

```text
 Client ---- request ----X                       (request lost)
 Client ---- request ----> Server crashed before processing
 Client ---- request ----> Server processed it, crashed before replying
 Client ---- request ----> Server processed it, reply lost      X---- reply
 Client ---- request ----> Server is just slow; reply will come in 3 s
```

From the client's side all five look identical: silence. The only tool is a **timeout**, and
every timeout is a guess:

- Too short → you declare healthy-but-slow nodes dead, retry, and cause duplicate work or
  needless failovers (which add load and can cascade).
- Too long → users wait, and real failures are detected late.

Critical consequence: after a timeout you do **not** know whether the operation happened. That
is why retries require **idempotency** (10.9).

In a conversation: a timeout does not tell you the request failed, only that you did not hear
back. The operation may have happened, so the retry has to be idempotent.

## 10.3 The CAP theorem, stated precisely

**CAP** says: in a system that replicates data, **when a network partition happens**, you must
choose between:

- **Consistency (C)** — here meaning **linearizability**: every read sees the most recent
  completed write, as if there were a single copy of the data.
- **Availability (A)** — every request received by a non-failing node gets a non-error response
  (without a guarantee it reflects the latest write).

You cannot have both during a partition. **P (partition tolerance)** is not a choice: partitions
happen whether you like it or not.

Why: a partition separates node X from node Y. A client writes to X. Another client reads from Y.

```text
  client1 --write v=2--> [X]   X   [Y] <--read-- client2
                           (partition)
  Y has v=1. Options for Y:
   (C) refuse or wait (error/timeout) -> not available
   (A) answer v=1                      -> not linearizable
```

- A **CP** behavior: the minority side refuses requests (or all requests that need a quorum
  block) until the partition heals. Example shape: systems built on consensus (etcd, ZooKeeper,
  and databases that require a majority for writes).
- An **AP** behavior: both sides keep serving, and they reconcile after the partition heals
  (last-write-wins, merges, conflict resolution). Example shape: Dynamo-style stores like
  Cassandra at low consistency levels.

### Common misreadings

| Misreading | Correction |
|---|---|
| "Pick 2 of 3" | You cannot drop P in a distributed system. The real choice is C vs A *during a partition* |
| "C in CAP = C in ACID" | ACID consistency means "constraints/invariants hold". CAP's C is linearizability. Different ideas |
| "A in CAP = 99.99% uptime" | CAP availability is a strict technical property (every non-failing node answers). A CP system can still be highly available in practice if partitions are rare and the majority side keeps serving |
| "Our DB is CP (or AP), period" | Many systems are tunable per operation (e.g. quorum reads vs single-replica reads) and some provide neither strict property |
| "CAP is about latency" | CAP says nothing about normal operation. PACELC covers that |

> **Say it like this:** "CAP only bites during a partition: then a replica either refuses to
> answer, staying consistent, or answers with possibly stale data, staying available. For the
> inventory reservation I'd choose consistency; for the product catalog, availability."

### PACELC

**PACELC** extends CAP: **if P**artition, choose **A** or **C**; **E**lse (normal operation),
choose **L**atency or **C**onsistency.

The "else" part is the one you feel every day: keeping replicas strongly consistent means waiting
for other replicas (often across zones or regions) on every write or read, which adds latency.

| System shape | Partition | Else | Notes |
|---|---|---|---|
| Dynamo-style (Cassandra, DynamoDB default reads) | A | L | Fast, eventually consistent by default; stronger options cost latency |
| Consensus-based / single-leader with sync replication | C | C | Pays a round trip to a majority on writes |
| Single leader, async replicas, reads from replicas | depends | L | Fast reads, replica lag |

## 10.4 Consistency models

A **consistency model** is a contract: which values a read is allowed to return given the writes
that happened. Stronger models are easier to program against and cost more coordination.

```text
 strongest
   Linearizable      - acts like one copy; respects real time
   Sequential        - one global order, consistent with each client's order (not real time)
   Causal            - cause-before-effect is preserved for everyone
   Session guarantees: read-your-writes, monotonic reads (per client)
   Eventual          - replicas converge if writes stop
 weakest
```

(Session guarantees are not strictly between causal and eventual; they are per-client
guarantees that causal consistency includes.)

### Linearizable

Every operation appears to take effect atomically at a single instant between its start and its
end. Once any read returns the new value, every later read (in real time) must return it too.

```text
 time ->
 Client A: |--- write x=1 ---|
 Client B:      |-- read x --|       may return 0 or 1 (overlaps the write)
 Client C:                       |-- read x --|   must return 1 (started after write ended)
 Client D:            |---- read x ----| returns 1
 Client E:                              |- read x -|  must return 1, because D already saw 1
                                                     and E started after D finished
```

Needed for: leader election, locks, unique constraints (two users must not both get username
"sam"), account balances.

### Sequential (brief)

All clients see all operations in one total order, and each client's own operations appear in
the order it issued them. But that order need not match real time across clients: a read may
return an older value even though another client's write finished earlier in wall-clock time.

### Causal

If operation A could have influenced operation B (B's author saw A first, or they are from the
same client in order), everyone sees A before B. Concurrent, unrelated operations may be seen in
different orders by different clients.

```text
 Alice posts:  "I lost my keys"          (A)
 Alice posts:  "Found them!"             (B, after A)
 Bob replies to B: "Great!"              (C, caused by B)

 Causal consistency guarantees nobody sees "Great!" before "Found them!",
 and nobody sees "Found them!" before "I lost my keys".
 Unrelated posts from Carol may interleave differently for different readers.
```

### Read-your-writes

A client always sees its own writes. Violation:

```text
 User:  POST /profile name="Sam" ---> leader (ok)
 User:  GET  /profile           ---> replica (lagging) -> name="Samuel"  (old!)
 "I just changed it and it didn't save?!"
```

Fixes: read from the leader for a short time after a user writes; or track the write's log
position and only read from replicas that have caught up to it.

### Monotonic reads

A client never sees time go backwards: once it has seen a value, later reads do not return an
older one.

```text
 read #1 -> replica R1 (up to date)  -> comment count 10
 read #2 -> replica R2 (lagging)     -> comment count 7     <- violation
```

Fix: pin a user's reads to the same replica (e.g. by hashing the user ID).

### Eventual

If writes stop, all replicas eventually converge to the same value. No promise about what you
read in the meantime. Cheapest, highest availability; fine for like counts, feeds, caches.

| Model | Guarantee | Cost | Typical use |
|---|---|---|---|
| Linearizable | Single-copy illusion in real time | Coordination round trips; unavailable in minority partition | Locks, leader election, uniqueness, balances |
| Causal | Cause before effect | Track dependencies (e.g. vector clocks) | Comments/replies, collaborative apps |
| Read-your-writes | See own writes | Sticky routing or position tracking | Profile edits, settings |
| Monotonic reads | No going backwards | Sticky replica | Feeds, counters shown to a user |
| Eventual | Converge eventually | Cheapest | Likes, view counts, caches |

> **Say it like this:** "We don't need linearizability for the feed. Read-your-writes and
> monotonic reads per user are enough, which we get by routing a user to the same replica and to
> the leader right after they post."

### Quorums recap

In leaderless replication (chapter 07), with **N** replicas, a write waits for **W** acks and a
read queries **R** replicas. If **W + R > N**, every read set overlaps every write set in at
least one replica, so a read can see the latest acknowledged write (using versions to pick the
newest).

```text
 N=3, W=2, R=2:   write -> {A, B}      read -> {B, C}   overlap = B
```

Caveats: quorums alone do not give full linearizability (concurrent writes, failed partial
writes, and "sloppy quorums" during failures can still produce anomalies; you need extra
mechanisms like read repair and careful versioning). Majority quorums are also the building block
of consensus below.

## 10.5 Clocks and ordering

### Physical clocks drift

Each machine has a quartz clock that drifts (roughly tens of parts per million, i.e. seconds per
day without correction). **NTP (Network Time Protocol)** periodically adjusts clocks from time
servers; accuracy is roughly milliseconds in a good datacenter network and tens of milliseconds
over the internet, and worse when the network is congested.

Problems:

- **Skew**: two machines disagree on the current time by some milliseconds or more.
- **Jumps**: NTP can step a clock backward or forward. Code that computes durations with the
  wall clock can get negative elapsed time. Use a **monotonic clock** for measuring durations.
- Leap seconds, VM pauses, misconfigured hosts.

### Why timestamps can't order events across machines

```text
 Node A clock is 30 ms ahead of Node B.

 real time ->
 Node A: write x=1 at local time 10:00:00.050
 Node B: (after seeing A's write) write x=2 at local time 10:00:00.040

 "Last write wins" by timestamp keeps x=1, even though x=2 really happened later
 and was based on x=1. The newer write is silently lost.
```

So **last-write-wins (LWW)** using wall-clock timestamps can drop data. You need logical ordering.

### Lamport clocks

A **Lamport clock** is a counter per node:

1. Before each local event or send, increment the counter.
2. Attach the counter to every message.
3. On receive: `counter = max(local, received) + 1`.

```text
 A:  (1) send m1 ----------------------+
                                       v
 B:        (1) local   (2) local   (3) receive m1: max(2,1)+1 = 3   (4) send m2 --+
                                                                                   v
 C:  (1) local ................................................ (5) receive m2: max(1,4)+1
```

Property: if event a **happened before** b (same node in order, or via a message chain), then
`L(a) < L(b)`. The reverse is not true: `L(a) < L(b)` does not mean a happened before b; they may
be concurrent. Tie-break by node ID to get a total order that every node agrees on.

### Vector clocks

A **vector clock** keeps one counter per node: `[A, B, C]`. A node increments its own entry on
each event; on receive it takes the element-wise max and then increments its own entry.

Comparing two vectors V and W:

- V ≤ W in every position (and different) → V happened before W.
- Neither ≤ the other → the events are **concurrent** (a conflict).

```text
 Shopping cart replicated on A and B, starting at [0,0]:
  A: add "book"   -> [1,0]
  B: add "pen"    -> [0,1]          (B had not seen A's change)
  Compare [1,0] vs [0,1]: neither dominates -> concurrent -> keep both, merge = {book, pen}

  Later A sees B's version, merges: max([1,0],[0,1]) = [1,1], then increments -> [2,1]
  [2,1] dominates [0,1]: no conflict.
```

Gain: detects true concurrency so you can merge instead of losing data. Pay: size grows with the
number of nodes (or clients) that write.

### Hybrid logical clocks (HLC), briefly

An **HLC** combines physical time with a logical counter: timestamps stay close to wall-clock
time (useful for humans and for "read as of time T") but still respect causality like a Lamport
clock when clocks are skewed. Some distributed SQL databases use HLCs for transaction ordering.
A related approach uses tightly bounded clock uncertainty (specialized time hardware) and waits
out the uncertainty before committing.

> **Common mistake:** Ordering events from different servers by their `created_at` timestamps and
> treating that as truth. With clock skew, a reply can appear older than the message it replies
> to. Use a single sequencer, a log offset, or logical clocks when order matters.

## 10.6 Consensus

### The problem

Several nodes must **agree on a value** (or a sequence of values) even if some nodes crash and
messages are delayed: "who is the leader?", "was transaction T committed?", "what is the next
entry in the log?". Requirements:

- **Agreement**: no two nodes decide different values.
- **Validity**: the decided value was proposed by someone.
- **Termination**: non-crashed nodes eventually decide (as long as a majority can communicate).

If you can agree on a **replicated log** of commands, every node can apply the same commands in
the same order and end up in the same state. This is **state machine replication**, and it is
how consensus is used in practice.

A famous theoretical result says no deterministic algorithm can guarantee consensus in a fully
asynchronous system where even one node may crash. Practical algorithms (Paxos, Raft, ZAB) stay
always **safe** (never disagree) and make progress when timing is reasonable, using timeouts to
elect leaders.

### Raft step by step

**Raft** is a consensus algorithm designed to be understandable. A cluster has an odd number of
servers, typically 3 or 5.

**Roles**: each server is a **follower**, **candidate**, or **leader**. Normally there is one
leader and the rest are followers.

**Terms**: time is divided into numbered **terms**. Each term starts with an election. Terms act
as a logical clock: any message with an older term is rejected, and a server that sees a higher
term immediately becomes a follower in that term.

```text
 term:   |   1   |  2  |     3      |  4  | ...
         | elect | elect (split vote, no leader) ...
         | leader A    | leader C   |
```

#### Step 1: leader election

```text
 Followers expect heartbeats (empty AppendEntries) from the leader.
 Each follower has a randomized election timeout (e.g. 150–300 ms).

 S1 (leader) dies.
 S3's timeout fires first:
   S3: term = term + 1, becomes candidate, votes for itself
   S3 -> RequestVote(term=5, lastLogIndex, lastLogTerm) -> S2, S4, S5
   Each server votes for at most one candidate per term, and only if the
   candidate's log is at least as up-to-date as its own.
   S3 gets votes from S2, S4 (+ itself) = 3 of 5 = majority -> leader for term 5
   S3 sends heartbeats; others stay followers.
```

- **Randomized timeouts** make it unlikely that two candidates start at once. If a vote splits
  and no one gets a majority, the term ends with no leader and new randomized timeouts retry.
- "At most one vote per term" + "majority needed" ⇒ **at most one leader per term**.
- The **up-to-date check** (compare last log term, then last log index) ensures the new leader
  already has every committed entry. This is the **election restriction**.

#### Step 2: log replication

Clients send commands to the leader. The leader appends the command to its log as a new entry
`(index, term, command)` and sends **AppendEntries** to all followers.

```text
 Leader S3 log:   idx: 1   2   3   4   5
                  term:1   1   3   5   5
                       x=1 y=2 x=3 y=4 x=5
 S2:              1   1   3   5   5        (has all)
 S4:              1   1   3   5            (missing 5)
 S5:              1   1   3                (missing 4, 5)
 S1 (down)

 Entry 5 is on S3 and S2. Once S4 also stores it: 3 of 5 = majority -> committed.
```

- Each AppendEntries includes the index and term of the entry just before the new ones
  (`prevLogIndex`, `prevLogTerm`). A follower rejects it if its log does not have a matching
  entry there. The leader then backs up and resends earlier entries until they match, and the
  follower overwrites any conflicting, uncommitted entries. Result: follower logs become
  identical prefixes of the leader's log (the **log matching** property).

#### Step 3: majority commit

An entry is **committed** once the leader has stored it on a majority of servers (and it is from
the leader's current term; earlier-term entries become committed along with it). Then:

- The leader applies it to its state machine and replies to the client.
- The leader includes its commit index in later messages; followers apply entries up to it.
- A committed entry is never lost: any future leader must have won a majority vote, every
  majority overlaps the majority that stored the entry, and the election restriction refuses
  candidates missing it.

#### Why 2f + 1 nodes tolerate f failures

Raft needs a majority (more than half) to elect a leader and to commit.

```text
 Nodes  Majority  Failures tolerated
   3       2            1
   4       3            1      <- same as 3 nodes: even sizes buy nothing
   5       3            2
   7       4            3
```

With `2f + 1` nodes, the majority is `f + 1`, so `f` nodes can be down and `f + 1` still form a
majority. Any two majorities share at least one node, which is what carries committed data and
votes across elections.

What happens in a partition: with 5 nodes split 3 | 2, the side with 3 can elect a leader and
commit; the side with 2 cannot commit anything (it is unavailable for writes). An old leader
stuck on the minority side cannot commit either, and steps down when it sees a higher term after
the partition heals. That is CP behavior.

Costs: every write waits for a majority round trip; throughput is limited by one leader; more
nodes means more fault tolerance but slower commits. That is why consensus clusters are small
(3–7 nodes) and hold small, critical data.

> **Say it like this:** "Raft elects one leader per term by majority vote, the leader replicates
> log entries, and an entry is committed once a majority has it. With five nodes we survive two
> failures, and a minority partition can't commit, so we never get split brain."

### Where consensus is used

You rarely implement Raft yourself. You use a **coordination service** built on consensus:
**etcd** (Raft), **ZooKeeper** (ZAB, a similar protocol), **Consul** (Raft). Kafka's newer
metadata mode (KRaft) uses a Raft-based quorum.

| Use | How |
|---|---|
| Configuration | Store small config; clients watch for changes |
| Leader election | Candidates try to create a key/node; one succeeds; it disappears if the holder's session dies |
| Service discovery | Instances register with a lease; registrations vanish when the lease expires |
| Distributed locks | Same as leader election, scoped to a resource (with fencing; next section) |
| Cluster membership / metadata | Which node owns which shard |

They are designed for small amounts of data and modest write rates, not as general databases.

## 10.7 Distributed locks and fencing tokens

### The pain

Only one worker should process a given file, or only one node should run the nightly billing job.
On one machine you would use a mutex. Across machines you need a lock service.

A lock service must use **leases**: a lock that expires after, say, 10 seconds unless renewed.
Without expiry, a crashed holder would hold the lock forever.

### Why a lease alone is unsafe

```text
 time ->
 Client 1: acquire lease (expires t=10) ... [long GC pause / stall] ............ write
 Lock svc:                                   lease expires at t=10
 Client 2:                                   acquire lease at t=11 -> write to storage
 Client 1 wakes at t=15, still believes it holds the lock, and writes -> corrupts data
```

Client 1 cannot reliably notice that time passed (a process pause can happen between "check
lease is valid" and "do the write"). Timing assumptions alone cannot fix this.

### Fencing tokens

The lock service returns a **fencing token** with each grant: a number that increases every time
the lock is granted (e.g. Raft log index or a ZooKeeper zxid). Clients send the token with every
write, and the **storage rejects** any write with a token lower than the highest it has seen.

```text
 Client 1: acquire -> token 33 ... pause ...                    write(token 33) -> REJECTED
 Client 2:                      acquire -> token 34 -> write(token 34) -> ok (storage max=34)
```

The key idea: safety is enforced by the resource being protected, not by the client believing
it holds the lock.

| Lock approach | Safe against pauses/expiry? | Notes |
|---|---|---|
| Single Redis `SET key val NX PX` | No (and Redis failover is async, can lose the lock) | Fine for efficiency (avoid duplicate work), not for correctness |
| Consensus-based lock (etcd/ZooKeeper) without fencing | No | Lease still expires during a pause |
| Consensus-based lock + fencing token checked by storage | Yes | Storage must support the check (conditional write) |
| No lock: conditional writes / optimistic concurrency | Yes | Often simpler: `UPDATE ... WHERE version = 7` |

> **Say it like this:** "A lease-based lock can't guarantee mutual exclusion because a paused
> client can wake up after its lease expired. If correctness depends on it, the storage has to
> check a monotonically increasing fencing token, or we skip the lock and use a conditional
> write."

## 10.8 The two generals problem

Two generals on opposite hills must attack at the same time. They communicate by messengers who
may be captured. General A sends "attack at dawn". Did B get it? B sends an acknowledgment. Did
A get the ack? A sends an ack of the ack... Any finite exchange leaves the last sender unsure
whether the last message arrived.

The lesson: **over an unreliable network, two parties cannot be certain they agree**. This is
why:

- "Send exactly once over the network" is impossible as a delivery mechanism.
- Systems settle for at-least-once delivery + idempotent processing, or for consensus among
  majorities with explicit rules about what counts as committed.

## 10.9 Idempotency and the exactly-once illusion

An operation is **idempotent** if doing it twice has the same effect as doing it once. `SET
balance = 100` is idempotent; `balance = balance + 10` is not.

Because timeouts leave you unsure (10.2), you must retry, and retries create duplicates. The
choices:

| Delivery | Meaning | How |
|---|---|---|
| At-most-once | Never duplicated, may be lost | Do not retry |
| At-least-once | Never lost (eventually), may be duplicated | Retry until acknowledged |
| Exactly-once *effect* | Each operation takes effect once | At-least-once + deduplication/idempotency at the receiver |

"Exactly-once" in real systems always means **exactly-once effect**, achieved by making the
receiver idempotent:

- **Idempotency keys**: the client generates a unique key per logical operation (e.g. a UUID
  per checkout attempt) and sends it with every retry. The server stores processed keys and
  returns the original result for a repeat.

```text
 POST /payments  Idempotency-Key: 7f1c... -> key new: charge, store (key -> result)
 (timeout, client retries)
 POST /payments  Idempotency-Key: 7f1c... -> key seen: return stored result, no charge
```

- **Natural idempotency**: upserts by ID, "set status to SHIPPED" instead of "advance status".
- **Version checks**: `UPDATE ... WHERE id = ? AND version = ?`.

Chapter 11 applies this to message consumers.

> **Common mistake:** Believing a queue or framework that advertises "exactly-once" makes your
> side effects (emails, payments, external API calls) happen once. Those guarantees cover the
> framework's own state; anything outside it still needs idempotency.

## 10.10 Unique ID generation

Every order, message, and click needs a unique ID. On one database, `AUTO_INCREMENT` is enough.
With sharded databases and many services, you need IDs generated in many places without
collisions, ideally sortable by time and fitting in 64 bits (a `BIGINT` indexes well).

### Options

**UUID**: 128-bit identifiers. Version 4 is 122 random bits; collisions are practically
impossible. Version 7 (standardized in 2024) puts a millisecond timestamp in the high bits, so
IDs are roughly time-ordered.

**Database auto-increment**: one database hands out IDs. Simple and ordered, but a single point
of failure and a write bottleneck; with sharding, each shard's counters collide unless you
configure offsets (shard 1: 1, 3, 5...; shard 2: 2, 4, 6...).

**Ticket server**: a dedicated database (or two, one handing out odd and one even numbers) whose
only job is to issue IDs. Clients often fetch a **range** (e.g. 1,000 IDs at a time) to cut round
trips. Gaps appear if a client crashes with unused IDs, which is fine.

**Snowflake-style IDs**: each generator builds a 64-bit ID locally from time + machine + counter.

```text
  0 | 41 bits timestamp (ms since custom epoch) | 10 bits machine ID | 12 bits sequence
  ^                                                ^                    ^
  sign bit (always 0, keeps IDs positive)          up to 1024 workers   4096 IDs per ms
                                                                         per worker
  bit:  63 | 62 ................................ 22 | 21 ........... 12 | 11 ......... 0
```

- 41 bits of milliseconds ≈ 2^41 ms ≈ 69 years from the chosen epoch.
- 10 bits → 1,024 generator IDs (often split into datacenter + worker).
- 12 bits → 4,096 IDs per millisecond per generator, about 4 million per second per generator.
  If the sequence overflows within a millisecond, wait for the next millisecond.
- IDs sort by creation time (roughly; across machines, only as accurate as the clocks).
- **Clock going backwards** is the main hazard: a generator must refuse to issue IDs (or wait)
  if its clock moves behind the last timestamp it used, or it could repeat IDs.
- Machine IDs must be unique: assign them via configuration or a coordination service.

| Approach | Size | Ordered? | Coordination | Gain | Pay |
|---|---|---|---|---|---|
| UUID v4 | 128 bit | No | None | Trivial, generate anywhere (even on clients) | Big; random inserts scatter across a B+ tree index (page splits, poor locality) |
| UUID v7 | 128 bit | Roughly by time | None | Generate anywhere, index-friendly | Still 128 bits; leaks creation time |
| DB auto-increment | 64 bit | Yes | Central DB | Simple, compact | Bottleneck, single point of failure, guessable |
| Ticket server / ranges | 64 bit | Mostly | Central service, rare calls | Compact, few round trips with ranges | Must make the server highly available; gaps |
| Snowflake-style | 64 bit | Roughly by time | Only to assign machine IDs | Fast, local, compact, sortable | Clock-skew sensitive; leaks timing and volume |

> **Say it like this:** "I'd use Snowflake-style 64-bit IDs: 41 bits of milliseconds, 10 bits of
> worker ID, 12 bits of sequence. They're generated locally without coordination, sort by time,
> and fit a BIGINT. The one thing to guard against is the clock going backwards."

DSA bridge: bit packing with shifts — `id = (ts << 22) | (worker << 12) | seq`.

## 10.11 Recap

- Networks lose, delay, and duplicate messages; the eight fallacies list what not to assume.
- A timeout cannot distinguish slow from dead, and after one you do not know whether the
  operation happened — so retries need idempotency.
- Gray failures (slow but alive) are often worse than crashes.
- CAP: during a partition, choose linearizability (C) or availability (A); P is not optional.
  PACELC adds the latency-vs-consistency trade-off in normal operation.
- Consistency ladder: linearizable > sequential > causal > session guarantees (read-your-writes,
  monotonic reads) > eventual.
- Wall-clock timestamps cannot reliably order events across machines; Lamport clocks give a
  causality-respecting order, vector clocks detect concurrency.
- Raft: terms, randomized election timeouts, one vote per term, majority to elect and commit,
  election restriction keeps committed entries; 2f+1 nodes tolerate f failures.
- etcd/ZooKeeper provide consensus as a service for config, leader election, discovery, locks.
- Lease-based locks are unsafe alone; fencing tokens checked by the storage make them safe.
- Exactly-once is really at-least-once delivery + idempotent processing.
- Snowflake IDs: 1 + 41 + 10 + 12 bits; local, sortable, compact; guard against clock rollback.

## 10.12 Check yourself

1. State CAP precisely and name two common misreadings.

<details><summary>Answer</summary>

In a replicated system, when a network partition occurs, each request must either be answered
possibly without the latest data (availability) or be refused/delayed to preserve
linearizability (consistency); you cannot have both. Misreadings: "pick any 2 of 3" (partitions
are not optional), "C means ACID consistency" (it means linearizability), "A means high
uptime" (it is a strict per-request property), and "CAP describes normal-operation latency"
(that is PACELC).

</details>

2. A user updates their bio, refreshes, and sees the old bio. Which consistency guarantee is
   violated and how do you fix it?

<details><summary>Answer</summary>

Read-your-writes. The read hit a lagging replica. Fix: route that user's reads to the leader for a
short window after a write, or record the write's log position and read only from replicas that
have caught up to it (or update the cache synchronously for that user).

</details>

3. Why does a 4-node Raft cluster tolerate no more failures than a 3-node one?

<details><summary>Answer</summary>

Raft needs a majority. For 3 nodes the majority is 2, so 1 failure is tolerated. For 4 nodes the
majority is 3, so still only 1 failure is tolerated, while you pay for an extra node and a larger
quorum. Odd sizes (2f+1) are the efficient choice.

</details>

4. In Raft, what stops a node with a stale log from becoming leader and erasing committed
   entries?

<details><summary>Answer</summary>

The election restriction: a server only grants its vote if the candidate's log is at least as
up-to-date as its own (higher last term, or same last term and at least as long). A committed
entry is on a majority, and any winning candidate needs votes from a majority; those majorities
overlap, so at least one voter has the entry and refuses a candidate missing it.

</details>

5. Two events have Lamport timestamps 5 and 9. Can you conclude the first happened before the
   second? What tool would tell you?

<details><summary>Answer</summary>

No. Lamport clocks guarantee that if a happened before b then L(a) < L(b), but not the reverse;
the events may be concurrent. Vector clocks can tell: if one vector is ≤ the other in every
position, it happened before; otherwise they are concurrent.

</details>

6. A worker holds a 10-second lease lock, pauses for 15 seconds in GC, then writes. What goes
   wrong and what fixes it?

<details><summary>Answer</summary>

The lease expired during the pause and another worker acquired the lock and wrote; the first
worker then writes too, believing it still holds the lock, corrupting data. Fix: fencing tokens.
The lock service issues an increasing token per grant; the storage rejects writes with a token
lower than the highest it has seen. Alternatively, use conditional writes instead of a lock.

</details>

7. Lay out a Snowflake ID and compute how many IDs per second one generator can produce.

<details><summary>Answer</summary>

1 sign bit (0), 41 bits of milliseconds since a custom epoch (~69 years), 10 bits of machine ID
(1,024 generators), 12 bits of sequence (4,096 per millisecond). One generator: 4,096 × 1,000 ≈
4.1 million IDs per second.

</details>

8. Why is "exactly-once delivery" over a network impossible, and what do systems do instead?

<details><summary>Answer</summary>

The sender cannot know whether a message or its acknowledgment was lost (the two generals
problem), so it must either risk losing the message (no retry) or risk duplicating it (retry).
Systems use at-least-once delivery with idempotent processing (idempotency keys, dedupe tables,
upserts, version checks) to get an exactly-once effect.

</details>
