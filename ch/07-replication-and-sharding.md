# 07 — Replication and sharding: scaling data

One database machine eventually runs out of something: it fails, it cannot serve all the reads,
it cannot absorb all the writes, or the data no longer fits. The two answers are **replication**
(keep copies of the same data on several machines) and **sharding** (split the data so each
machine holds a part). Nearly every large system uses both. This chapter explains each from
scratch, the anomalies they introduce, and the vocabulary you need to discuss them confidently.

```text
Replication: same data, many copies          Sharding: different data, many machines

  +-------+   +-------+   +-------+            +--------+  +--------+  +--------+
  | A B C |   | A B C |   | A B C |            | A      |  | B      |  | C      |
  +-------+   +-------+   +-------+            +--------+  +--------+  +--------+
  more availability, more read capacity        more write capacity, more storage
```

## 07.1 Why replicate

**Replication** means keeping a copy of the same data on multiple machines, called **replicas**.
Reasons:

1. **Availability / durability.** If one machine dies (and machines do: disks fail, kernels
   panic, data centers lose power), another has the data and can take over.
2. **Read scaling.** Spread read traffic over many copies. A read-heavy app (most are; 10:1 or
   100:1 read:write is common) can add replicas to add read capacity.
3. **Latency.** Put a replica near users (a replica in Europe for European users) so reads avoid
   a cross-ocean round trip of roughly 100+ ms.

The hard part is **keeping copies in sync while data changes**. Every replication design is an
answer to "who accepts writes, and how do other copies learn about them?" There are three
answers: one leader, many leaders, or no leader.

## 07.2 Single-leader (leader-follower) replication

### How it works

One replica is the **leader** (also called primary or master). All writes go to it. The other
replicas are **followers** (secondaries, read replicas). The leader sends its stream of changes
(usually derived from its write-ahead log, chapter 05) to followers, which apply them in the same
order.

```text
                 writes
   clients  ---------------->  +-----------+
                               |  LEADER   |
                               +-----+-----+
                       replication   |   log stream (in order)
                  +------------------+------------------+
                  v                  v                  v
            +-----------+      +-----------+      +-----------+
            | follower 1|      | follower 2|      | follower 3|
            +-----------+      +-----------+      +-----------+
   clients  ---- reads may go to any follower (or the leader) ----
```

This is the default for PostgreSQL, MySQL, MongoDB replica sets, and many others. It is simple
because there is one place where write order is decided, so there are no write conflicts.

### Synchronous, asynchronous, semi-synchronous

When the leader commits a write, does it wait for followers?

```text
Synchronous                         Asynchronous
client  leader   follower           client  leader   follower
  |--w-->|                            |--w-->|
  |      |--repl-->|                  |      |--repl--> (in flight)
  |      |<--ack---|                  |<-OK--|
  |<-OK--|                            |      |   follower applies later
```

| Mode | Leader returns OK when | Gain | Pay |
|---|---|---|---|
| **Synchronous** | Followers confirmed they have the write | No acknowledged write is lost if the leader dies | Every write waits for the slowest required follower; if that follower is down, writes stall |
| **Asynchronous** | The leader alone has committed it | Fast writes; followers being slow or down doesn't affect writes | If the leader dies, recent writes not yet replicated are **lost**, even though clients were told OK |
| **Semi-synchronous** | At least one follower (or a configured count) acknowledged receiving it | A good middle: at least two machines have each acknowledged write | Some added latency; if no follower acks within a timeout, some implementations fall back to async |

In practice: fully synchronous to *all* followers is rare (one slow node would block everything).
A common setup is one synchronous (or semi-sync) follower plus the rest asynchronous. MySQL's
semi-sync replication waits for a replica to acknowledge it *received* (wrote to its relay log)
the change, not that it applied it. PostgreSQL's `synchronous_standby_names` and
`synchronous_commit` settings choose how many standbys must confirm and at what stage (received,
flushed, or applied).

> **Say it like this:** "With async replication, a leader crash can lose the last few
> acknowledged writes. I'd use one semi-synchronous replica so every acknowledged write exists on
> two machines, and keep the other replicas async for reads."

## 07.3 Replication lag and the anomalies it causes

**Replication lag** is how far behind a follower is: usually milliseconds, but seconds or minutes
under heavy load, long-running queries on the replica, or network trouble. Async read replicas
are **eventually consistent**: if writes stop, they catch up, but at any instant they may be
stale. Lag produces three classic user-visible anomalies.

### 1. Read-your-writes violation

```text
user          leader           follower (lagging)
 |--update bio-->|
 |<----OK--------|
 |---------------------- read profile --->|
 |<--------------------- OLD bio ---------|     "my edit vanished!"
```

**Read-your-writes consistency** (read-after-write) guarantees a user always sees their own
writes. Fixes:
- Read data the user can edit (their own profile) from the leader; others' profiles from
  followers.
- For a short window after the user writes (e.g. 10–30 seconds, longer than typical lag), route
  that user's reads to the leader.
- Remember the log position (or timestamp) of the user's last write; only read from a follower
  that has replayed at least that far, otherwise use the leader or wait.

### 2. Monotonic reads violation

```text
read #1 -> follower A (up to date)   -> sees comment C
read #2 -> follower B (lagging)      -> comment C missing     "time went backwards"
```

**Monotonic reads** guarantee that once you've seen a value, you won't later see an older one.
Fix: pin each user (or session) to one replica, e.g. choose the replica by hashing the user id;
re-pin if that replica fails.

### 3. Consistent prefix reads violation

If writes A then B are causally related (a question, then its answer) but land on different
partitions replicating at different speeds, a reader might see the answer before the question.
**Consistent prefix reads** guarantee that you see writes in an order that makes sense. Fix:
keep causally related writes in the same partition, or track causal dependencies.

> **Common mistake:** adding read replicas to scale reads without deciding which reads tolerate
> staleness. Classify reads: "must be fresh" (after the user's own write, balances before a
> payment) go to the leader; "may be stale" (feeds, search, other users' profiles) go to
> replicas.

## 07.4 Failover and split brain

### Failover

When the leader dies, a follower must be promoted: **failover**. Steps:

1. **Detect** the leader is dead. Usually by missed heartbeats over a timeout (say 10–30 s).
   There is no perfect detector: a slow or partitioned leader looks the same as a dead one
   (chapter 10).
2. **Choose** a new leader, ideally the most up-to-date follower (least data loss). Choosing
   safely so that everyone agrees on the one new leader is a consensus problem (chapter 10).
3. **Reconfigure**: clients and other followers now send writes to/replicate from the new
   leader; the old leader, if it returns, must become a follower.

Failover can be **manual** (an operator decides; slower but avoids false alarms) or **automatic**
(orchestration tools or a consensus-based coordinator; faster but must be carefully designed).

What can go wrong:
- **Lost writes with async replication.** The new leader lacks the old leader's last writes.
  When the old leader returns, its unreplicated writes conflict with new ones and are usually
  discarded. If other systems already acted on them (e.g. an id used in a cache or sent in an
  email) you get inconsistencies.
- **Timeout too short:** a load spike causes needless failovers, which add more load.
  **Timeout too long:** longer downtime.

### Split brain

**Split brain** is when two nodes both believe they are the leader and both accept writes. Their
data diverges, and merging it later is painful or impossible.

```text
        network partition
   +-----------+   X   +-----------+
   | old leader|   X   | new leader|  <- promoted because it couldn't reach old leader
   | accepting |   X   | accepting |
   |  writes   |   X   |  writes   |
   +-----------+   X   +-----------+
   clients on this side  clients on that side      -> two diverging histories
```

Prevention:
- **Quorum-based election:** a node can only be leader with the votes of a majority of a
  fixed set of nodes. Two majorities of the same set always overlap, so two leaders cannot both
  win the same term. This is what Raft-style consensus gives you (chapter 10).
- **Fencing:** make the old leader unable to do harm. Examples: **fencing tokens** (each new
  leader gets a higher, monotonically increasing number; storage rejects writes carrying an
  older number), revoking the old leader's access to shared storage, or forcibly powering it off
  (**STONITH**: "shoot the other node in the head").
- A leader that loses contact with a majority should **step down** on its own.

> **Say it like this:** "Automatic failover needs two things: majority-based election so only
> one leader can be chosen, and fencing so a deposed leader that's still alive can't keep
> writing. And with async replication, failover can lose the last acknowledged writes."

## 07.5 Multi-leader replication

### When one leader isn't enough

- **Multiple data centers / regions.** With one leader in Virginia, a user in Mumbai pays a
  cross-world round trip on every write, and if Virginia is unreachable, nobody can write. With a
  leader in each region, writes are local and each region keeps working alone.
- **Offline clients.** A notes app on a phone accepts edits offline: each device is effectively
  a leader that syncs later.
- **Collaborative editing.** Several users editing one document simultaneously, each applying
  changes locally first.

```text
   region US                             region EU
  +-----------+  async, both directions  +-----------+
  | leader US | <======================> | leader EU |
  +-----+-----+                          +-----+-----+
        |                                      |
   followers US                           followers EU
```

### The catch: write conflicts

Two leaders can accept conflicting writes to the same data at the same time:

```text
US: title = "B"   (t=100)          EU: title = "C"   (t=101)
            \                        /
             replicate to each other: which wins?
```

Conflict resolution strategies:

- **Avoid conflicts:** route all writes for a given record to the same "home" leader (e.g. a
  user's data is always written in their home region). Most practical multi-leader setups do
  this as much as possible.
- **Last-write-wins (LWW):** attach a timestamp to each write; the highest wins. Simple, and
  widely used, but it **silently discards** the other writes, and clocks on different machines
  are never perfectly synchronized (chapter 10), so the "last" write may not be the real last
  one. Fine for data where losing a concurrent update is acceptable (a "last seen" time), bad
  for anything like a counter or a cart.
- **Merge in application code:** store both versions ("siblings") and let the application or
  user merge them (e.g. union of shopping cart items, a "conflicting copy" file).
- **CRDTs (conflict-free replicated data types):** data structures mathematically designed so
  concurrent updates merge automatically and all replicas converge to the same value regardless
  of the order updates arrive. Examples: a grow-only counter (each replica counts its own
  increments; the value is the sum), an add/remove set, sequence CRDTs for collaborative text.
  Used in collaborative editors and some databases. **Operational transformation** is a related
  older technique for collaborative text editing.

| | Single-leader | Multi-leader |
|---|---|---|
| Write latency for distant users | High (go to the one leader) | Low (local leader) |
| Survives a region outage for writes | Only after failover | Yes, each region keeps writing |
| Conflicts | None (one order) | Must be resolved |
| Complexity | Low | High |

> **Common mistake:** choosing multi-leader for "high availability" inside one data center.
> Within one region, single-leader with fast failover is far simpler. Multi-leader earns its
> complexity mainly across regions or for offline clients.

## 07.6 Leaderless replication (Dynamo-style)

### The idea

No leader at all. The client (or a coordinator node acting for it) sends each write to several
replicas in parallel and each read to several replicas in parallel. The design was popularized
by Amazon's Dynamo paper and is used by Cassandra, ScyllaDB and Riak.

### Quorums: N, W, R

- **N** = number of replicas that store each key (replication factor), e.g. 3.
- **W** = number of replicas that must acknowledge a write for it to succeed.
- **R** = number of replicas that must respond to a read.

If **W + R > N**, the set of replicas that acknowledged the latest write and the set you read
from must **overlap** in at least one replica (pigeonhole principle). So at least one replica in
every read has the latest successful write, and using version numbers (or timestamps) the
reader picks the newest.

```text
N = 3, W = 2, R = 2   (W + R = 4 > 3)

write v2:   replica A [v2]   replica B [v2]   replica C [v1]  (C was slow; W=2 satisfied)
read:       ask any 2, e.g. B and C  -> B says v2, C says v1 -> return v2 (newest)
            any 2 of {A,B,C} include A or B -> always see v2
```

| Setting (N=3) | Behavior |
|---|---|
| W=2, R=2 | Balanced; tolerates 1 node down for both reads and writes |
| W=3, R=1 | Fast reads; a write fails if any replica is down |
| W=1, R=3 | Fast writes; a read fails if any replica is down |
| W=1, R=1 | Fastest, most available; W+R=2 is not > 3, so reads can be stale |

Cassandra calls these **consistency levels**: `ONE`, `QUORUM` (a majority, here 2 of 3), `ALL`,
and per-datacenter variants like `LOCAL_QUORUM`. The client chooses per query.

Important caveat: **W + R > N is not the same as strong (linearizable) consistency.** With
concurrent writes, sloppy quorums (below), LWW conflict resolution using imperfect clocks, or a
write that succeeded on fewer than W nodes (reported as failed but not rolled back), readers can
still see surprising results. Quorums make stale reads unlikely, not impossible.

### Keeping replicas converged

Because writes can miss some replicas, leaderless systems repair them:

- **Read repair:** a read that sees a stale value on some replica writes the newest value back
  to it. Works well for frequently read keys.
- **Anti-entropy:** a background process compares replicas and copies missing data. Comparing
  everything would be expensive, so replicas compare **Merkle trees** (trees of hashes over key
  ranges): if two root hashes match, the ranges are identical; if not, descend only into
  mismatching subtrees. (DSA bridge: a hash tree lets you find differences in O(differences ×
  log n) comparisons instead of comparing every key.)
- **Hinted handoff:** if a replica that should receive a write is down, another node temporarily
  stores the write with a "hint" saying who it belongs to, and hands it over when the replica
  returns. Combined with a **sloppy quorum** (counting those stand-in nodes toward W), this
  keeps writes available during failures, at the price that W + R > N no longer guarantees
  overlap with the "home" replicas.

```text
N=3 home replicas for key k: A, B, C.   W=2.   B and C are down.
strict quorum:  only A can ack            -> write fails
sloppy quorum:  A acks, D (not a home replica) stores the write with hint "for C"
                -> counts as W=2, write succeeds
C comes back:   D -> C "here is your write", D deletes its copy
risk meanwhile: a read with R=2 from B and C (back, before handoff) can miss the write
```

> **Say it like this:** "Leaderless replication writes to W of N replicas and reads from R; when
> W plus R exceeds N the read and write sets overlap, so a read usually sees the latest value.
> Read repair and anti-entropy fix replicas that missed writes, and hinted handoff keeps writes
> flowing while a node is down."

## 07.7 Why shard

Replication copies all the data to every replica. It does not help when:

- **Data too big:** 40 TB will not fit (or will be painfully slow to back up, restore and
  rebuild) on one machine.
- **Writes too many:** every write goes to the leader (in single-leader replication), and every
  replica must apply every write. Adding replicas adds *read* capacity, not write capacity.
- **Working set exceeds memory:** once the hot data no longer fits in RAM, reads hit disk and
  latency rises.

**Sharding** (also called **partitioning**) splits the data into **shards** (partitions), each
holding a subset of the rows, each living on a different machine (or replica set). Each record
belongs to exactly one shard. Write capacity and storage then scale with the number of shards.

Vocabulary: **partition** is the usual word inside databases (Cassandra, Kafka, Elasticsearch
"shards"); **shard** is common when an application splits a relational database. Both mean the
same thing here. (Do not confuse it with table partitioning inside one database server, which
splits a table into pieces on the same machine to make pruning and dropping old data cheap.)

## 07.8 Sharding strategies

The **shard key** (partition key) is the column used to decide which shard a row belongs to.

### Range-based

Assign contiguous key ranges to shards.

```text
user_name:  [A-F] -> shard 1   [G-M] -> shard 2   [N-S] -> shard 3   [T-Z] -> shard 4
```

- **Gain:** range queries on the key are efficient (one or few shards); data stays sorted.
- **Pay:** **hot spots** when writes concentrate on one range. The classic bad case: shard key
  = timestamp. All new writes go to the "latest" range, so one shard takes all the write load
  while the others idle.
- Ranges can be split automatically when they grow (HBase, Bigtable, and distributed SQL
  databases do this).

### Hash-based

Apply a hash function to the key and use the hash to choose the shard: `shard = hash(key) mod
num_shards` in the simplest form (07.12 explains why plain `mod` is a problem when you add
shards).

```text
hash("user_7")   = 0x9A3F... -> shard 2
hash("user_8")   = 0x13C1... -> shard 0     neighbors in key order land far apart
```

- **Gain:** spreads keys (and load) evenly, even when keys are sequential.
- **Pay:** range queries over the key must hit every shard, because adjacent keys are scattered.
- Compromise used by Cassandra-style stores: hash the *partition key* to choose the node, and
  sort rows *within* the partition by a clustering key (chapter 06). You get even spread and
  efficient ranges within one partition.

### Directory / lookup-based

Keep an explicit mapping `key (or key group) -> shard` in a lookup service.

```text
lookup table:   tenant_17 -> shard 3,   tenant_18 -> shard 1,   tenant_19 -> shard 3 ...
```

- **Gain:** total flexibility: move one big tenant to its own shard, rebalance without
  rehashing everything.
- **Pay:** the directory is an extra lookup on every request (so it is heavily cached) and a
  critical dependency that must be highly available and consistent.
- Common in multi-tenant SaaS, where tenants differ wildly in size.

### Geo-based

Shard by geography: EU users' data in EU shards, US users' in US shards.

- **Gain:** low latency for users near their data; can satisfy **data residency** laws that
  require data to stay in a region.
- **Pay:** uneven shard sizes (regions differ in size); users who move or interact across
  regions need special handling.

| Strategy | Even spread | Range queries on key | Rebalancing | Typical use |
|---|---|---|---|---|
| Range | Risk of hot spots | Excellent | Split/merge ranges | Time-ordered or sorted data, distributed SQL |
| Hash | Good | Scatter to all shards | Needs consistent hashing or fixed partitions | User-keyed data, key-value stores |
| Directory | As good as you manage it | Depends | Flexible, per key | Multi-tenant SaaS |
| Geo | Uneven | Within a region | Hard | Latency, data residency |

## 07.9 Choosing a shard key

A good shard key has:

1. **High cardinality:** many distinct values, so data can be spread over many shards. (A
   `country` key caps you at about 200 shards and a very uneven split.)
2. **Even distribution of data and load:** no value that is vastly bigger or busier than others.
3. **Query locality:** the most frequent queries include the shard key, so they hit one shard.
   Data that is queried or updated together shares a shard key (so it lives together, and
   single-shard transactions work).

| Shard key | Verdict | Why |
|---|---|---|
| `user_id` for a social or e-commerce app | Good | High cardinality; most queries are "this user's X"; a user's orders, cart and settings co-locate |
| `order_id` for orders, when the main query is "my orders" | Weak | "My orders" fans out to all shards; prefer `user_id` and co-locate orders with their user |
| `created_at` (timestamp) | Bad for writes | All current writes hit the newest range: a write hot spot |
| `country` | Bad | Low cardinality, huge skew |
| `tenant_id` in SaaS | Good, with care | Natural isolation and locality, but very large tenants need their own shard (directory-based) |
| `conversation_id` for chat messages | Good | All messages of a conversation together; reads are per conversation |
| `status` | Terrible | A few values; one "active" value holds most rows |

> **Say it like this:** "I'd shard by user_id: it's high-cardinality, spreads evenly when
> hashed, and almost every query is scoped to one user, so it hits a single shard and
> single-shard transactions still work."

## 07.10 Hot spots and the celebrity problem

Even with a good key, a single *value* can be hot. A celebrity with 100 million followers posts;
every follower's feed request reads that post, and a sharded "likes" counter for it takes
thousands of writes per second, all on one shard.

Fixes:

- **Key salting (splitting a hot key):** append a small random suffix to spread one logical key
  over several physical keys.

  ```text
  write like:   key = "post_123#" + random(0..9)      -> 10 keys on up to 10 shards
  read count:   sum over "post_123#0" ... "post_123#9"  (10 reads, or keep a cached total)
  ```

  Trade-off: reads now fan out to all salted keys, so only salt keys you know are hot (keep a
  list of hot keys, or detect them dynamically).
- **Caching** hot reads in front of the shard (chapter 08), including caching in the app process
  for extremely hot keys.
- **Aggregating writes:** buffer increments in memory or in a stream and apply them in batches
  (approximate real-time counters are usually fine for likes).
- **Dedicated shard** for a known huge tenant (directory-based sharding).
- **Change the design**, e.g. for feeds, don't push a celebrity's post into 100 M follower feeds;
  pull it at read time (chapter 17 case studies).

## 07.11 Cross-shard queries, joins and secondary indexes

### Cross-shard queries

A query that doesn't include the shard key must ask every shard and merge the results:
**scatter-gather**.

```text
             "top 10 products by sales today"
                       |
       +---------------+---------------+---------------+
       v               v               v               v
   shard 0          shard 1         shard 2         shard 3     each returns its top 10
       \               \               /               /
        +--------------- merge (heap of 40 -> top 10) -+
```

Costs: latency is set by the slowest shard (**tail latency**: with many shards, the chance that
at least one is slow grows quickly); every shard does work for every such query; and
`LIMIT`/`ORDER BY` requires each shard to return enough rows to merge correctly.

### Cross-shard joins and transactions

- **Joins** between tables sharded on different keys either become scatter-gather or are done in
  application code. Avoid by **co-locating**: shard related tables on the same key (orders by
  `user_id`, same as users) so the join is single-shard. Small, rarely changing tables
  (countries, currencies) can be **replicated to every shard**.
- **Transactions** spanning shards need a distributed commit protocol (two-phase commit, chapter
  10) or a saga with compensating actions (chapter 13). Both are slower and more complex than a
  local transaction, which is another reason to choose a shard key that keeps transactional
  data together.

### Secondary indexes on sharded data

Say users are sharded by `user_id`, but you also want "find user by email".

**Local index (document-partitioned):** each shard indexes only its own rows.

```text
shard 0: rows + index(email) of shard 0's users
shard 1: rows + index(email) of shard 1's users
lookup by email -> ask ALL shards (scatter-gather)
```

- Writes stay single-shard (row and its index entry on the same shard).
- Reads by the secondary key must query every shard.

**Global index (term-partitioned):** the index itself is a separately sharded table, partitioned
by the *indexed* value.

```text
email_index sharded by hash(email):
  idx shard 0: a...@x.com -> user 7 (on data shard 2)
  idx shard 1: b...@y.com -> user 91 (on data shard 0)
lookup by email -> 1 index shard -> 1 data shard
```

- Reads by the secondary key hit one index shard, then one data shard.
- Writes touch two shards (data and index), so keeping the index in sync needs a distributed
  transaction or, more commonly, asynchronous updates, meaning the index can briefly lag (e.g.
  DynamoDB global secondary indexes are eventually consistent).

| | Local index | Global index |
|---|---|---|
| Write | 1 shard | Data shard + index shard(s) |
| Read by indexed field | All shards | 1 index shard (+ fetch) |
| Consistency with data | Synchronous (same shard) | Often asynchronous / lagging |

## 07.12 Consistent hashing from scratch

### The problem with `hash(key) mod N`

`shard = hash(key) mod N` spreads keys evenly. But change N and almost every key moves. Going
from N to N+1 shards, a key stays put only when `hash mod N == hash mod (N+1)`, which happens for
only about **1/(N+1)** of keys. From 4 to 5 shards, about 80% of all data must move. For a cache,
that is a near-total miss storm; for a database, a massive migration.

We want: when a node is added or removed, only about **1/N** of keys move, and only to or from
the affected node.

### The ring

1. Pick a large hash space, say 0 to 2^32 - 1, and imagine it bent into a circle (after
   2^32 - 1 comes 0).
2. Hash each **node** (e.g. its name) to a position on the ring.
3. Hash each **key** to a position on the ring.
4. A key belongs to the first node found going **clockwise** from the key's position (its
   **successor**).

```text
Positions on a 0..100 scale for readability (real rings use 0..2^32-1).
The ring "unrolled" into a line; the right end wraps back to 0.

0    10        25   30             55        70         90     95    100=0
|----N1--------k1---N2-------------k2--------N3---------N4-----k3-----|
                ----->              --------->                 ------> wraps to N1

k1 (25) -> next node clockwise is N2 (30)
k2 (55) -> N3 (70)
k3 (95) -> nothing after 95, wrap around -> N1 (10)

Ownership:  N1 owns (90, 10]   N2 owns (10, 30]   N3 owns (30, 70]   N4 owns (70, 90]
```

### DSA bridge: finding the successor is binary search

Keep node positions in a **sorted array**. For a key with hash `h`, find the first node position
`>= h` (`lower_bound`). If none exists (h is past the last node), wrap around to index 0.

```text
positions = [10 (N1), 30 (N2), 70 (N3), 90 (N4)]

lookup(h=25): lower_bound -> index 1 -> N2
lookup(h=55): lower_bound -> index 2 -> N3
lookup(h=95): lower_bound -> index 4 (past end) -> wrap to index 0 -> N1
```

Each lookup is O(log V) where V is the number of positions on the ring. (A balanced BST or a
`TreeMap.ceilingKey` gives the same result and supports inserts and removals.)

### What moves when a node joins

Add N5 at position 50:

```text
before: keys in (30, 70] -> N3
after:  keys in (30, 50] -> N5   (moved from N3)
        keys in (50, 70] -> N3   (unchanged)
all other keys: unchanged
```

Only keys between the new node and its predecessor move, and they all come from one node (the
new node's successor). On removal, the removed node's keys go to its successor. With N nodes,
roughly 1/N of keys move, versus nearly all keys with `mod N`.

### Virtual nodes

With only a few random points on the ring, arcs are very uneven: one node may own 40% of the
ring and another 5%. Also, when a node leaves, its entire load dumps onto one successor.

**Virtual nodes (vnodes):** place each physical node at many positions (e.g. 100–256 points,
by hashing "N1#0", "N1#1", ...).

```text
ring positions: N1#0, N3#7, N2#4, N1#9, N4#2, N2#1, N3#3, N1#5, ...  (interleaved)
```

- Load evens out: each physical node owns many small arcs, and the sum of many random arcs is
  close to the average (law of large numbers).
- When a node leaves, its many arcs are absorbed by many different successors, spreading the
  load. When a node joins, it takes a small slice from many nodes.
- **Heterogeneous hardware:** give a bigger machine more virtual nodes so it takes more data.
- Cost: a bigger sorted array (still tiny: 100 nodes × 256 vnodes = 25,600 entries) and more
  ranges to track during rebalancing.

**Replication on the ring:** to store N copies, a key goes to its successor and the next N-1
*distinct physical* nodes clockwise (skipping vnodes of physical nodes already chosen). This is
the Dynamo/Cassandra approach.

> **Say it like this:** "Consistent hashing puts nodes and keys on a hash ring; a key belongs to
> the next node clockwise, found by binary search over sorted node positions. Adding a node only
> moves the keys in the arc it takes over, about 1/N of the data, and virtual nodes keep the
> arcs even."

## 07.13 Rebalancing approaches

**Rebalancing** is moving data between nodes when nodes are added, removed, or load is uneven.
Goals: even load afterwards, keep serving during the move, move as little data as possible.

1. **Fixed number of partitions.** Create many more partitions than nodes up front (e.g. 1,024
   partitions on 8 nodes, 128 each). A key's partition never changes: `partition = hash(key) mod
   1024`. A new node *steals whole partitions* from existing nodes; only the partition-to-node
   assignment changes.

   ```text
   8 nodes:  node0 [p0..p127]  node1 [p128..p255] ...
   add node8: each existing node hands ~14 partitions to node8 -> ~113-114 each
   keys never rehash; whole partitions are copied, then ownership flips
   ```

   - Gain: simple, predictable, moves the minimum. Used by many systems (e.g. Elasticsearch fixes
     the primary shard count at index creation; Redis Cluster uses 16,384 hash slots;
     Couchbase uses 1,024 vBuckets).
   - Pay: you must pick the count up front. Too few: partitions become huge and you can't spread
     over more nodes than partitions. Too many: per-partition overhead.

2. **Dynamic partitioning.** Partitions (usually key ranges) **split** when they grow past a size
   threshold and can merge when they shrink; splits can move to other nodes. Used by HBase,
   Bigtable, MongoDB (chunks/ranges), and distributed SQL databases. Adapts to data volume
   automatically; an empty database starts with one partition (some systems allow
   **pre-splitting** to avoid that initial hot spot).

3. **Partitions proportional to nodes.** A fixed number of partitions *per node* (e.g.
   Cassandra-style vnodes): adding a node creates new partitions by splitting existing ranges.

Operational advice: fully automatic rebalancing can surprise you (a node that is merely slow is
declared dead, rebalancing starts, adding load, making things worse). Many teams let the system
*propose* moves and have a human or a rate-limited process approve them, and always throttle data
movement so it doesn't starve live traffic.

> **Common mistake:** designing with `hash(key) mod number_of_servers` in application code. The
> day you add a server, nearly every key maps elsewhere. Map keys to a fixed set of logical
> partitions first, then partitions to servers.

## 07.14 Replication and sharding together

Real systems shard the data and replicate each shard.

```text
                           +----------------------+
       app servers ------> | routing layer         |  knows partition -> shard -> leader
                           | (client library, proxy|  (config service / directory)
                           |  or coordinator node) |
                           +----------+-----------+
                                      |
          +---------------------------+----------------------------+
          v                           v                            v
   shard 0 (users p0..p63)     shard 1 (users p64..p127)  ...  shard 15
   +---------+                 +---------+                      +---------+
   | leader  | node A          | leader  | node B               | leader  | node C
   +----+----+                 +----+----+                      +----+----+
        | repl                      | repl                           | repl
   +----v----+ +---------+     +----v----+ +---------+          +----v----+ +---------+
   | follower| | follower|     | follower| | follower|          | follower| | follower|
   | node B  | | node C  |     | node C  | | node A  |          | node A  | | node B  |
   +---------+ +---------+     +---------+ +---------+          +---------+ +---------+
```

- Each node is the leader for some shards and a follower for others, so load and failure risk
  are spread.
- A node failure means: for shards it led, a follower is promoted; for shards it followed,
  another replica is created elsewhere.
- The **routing layer** maps a key to its partition, the partition to its shard, and the shard to
  the current leader. It can be a smart client library, a proxy, or any node that forwards
  requests. The mapping lives in a highly available configuration service (often built on
  consensus, e.g. ZooKeeper/etcd-style, chapter 10) or is gossiped between nodes.

## 07.15 Worked example: sharding users and orders from 1 to 16 shards

### Starting point

- One PostgreSQL primary + 2 async read replicas.
- `users` (80 M rows), `orders` (1.5 B rows), `order_items` (4 B rows). About 6 TB total and
  growing roughly 2 TB/year.
- Peak about 15,000 writes/sec, and the primary is near its write and storage limits; replicas
  are lagging during peaks. Reads are mostly per user ("my orders", "my profile", checkout).

### Step 1: choose the shard key

Queries are scoped to a user, and an order's transaction touches the user's orders and items.
Shard key = **`user_id`**. Orders and order items carry `user_id` (add it to `order_items` too,
denormalized from the order) so that all of one user's data co-locates and checkout stays a
single-shard transaction.

`products` and inventory are a different domain: inventory is shared by all users, so keep it
in its own (unsharded or separately sharded by `product_id`) database. Checkout then touches two
databases; handle that with a reservation step and a saga or outbox pattern (chapters 11 and 13),
not a cross-database transaction.

### Step 2: logical partitions, then physical shards

Don't map users directly to 16 machines. Map them to **256 logical partitions**, then partitions
to physical shards:

```text
partition = hash(user_id) mod 256            (never changes)
shard     = partition_map[partition]         (config; changes when we rebalance)

16 shards:   shard k owns partitions [16k .. 16k+15]
later 32:    move 8 partitions off each shard to a new one; no key rehashing
```

Each physical shard is a primary with replicas. 6 TB / 16 is roughly 375 GB per shard today,
growing to roughly 500 GB in a year, comfortable for one machine; writes per shard are roughly
15,000 / 16 ≈ 940/sec at peak, leaving lots of headroom.

### Step 3: IDs and lookups that don't include user_id

- **Global unique IDs:** auto-increment per shard would collide. Use a globally unique scheme: a
  time-ordered 64-bit ID (timestamp bits + generator/shard bits + sequence bits, a common
  "Snowflake-style" layout) or UUIDv7.
- **"Find order by order_id"** (support tools, payment callbacks): either encode the partition
  in the ID (e.g. some bits of `order_id` hold the user's partition number, so routing needs no
  lookup), or keep a small global `order_id -> user_id` lookup table.
- **"Find user by email"** (login): a global index table `email -> user_id`, itself sharded by
  hash(email) or small enough to keep unsharded.
- **Admin analytics** ("revenue by day across all users"): don't scatter-gather on the OLTP
  shards; stream changes into a data warehouse (chapter 09).

### Step 4: migrate without downtime

```text
1. Provision 16 shards (each primary + replicas). Create schemas.
2. Deploy routing in the app (partition -> shard), but keep reading/writing the old DB.
3. Backfill: copy historical rows partition by partition into their shard.
4. Keep shards current: either dual-write (app writes old DB and new shard) or, more robust,
   stream the old DB's change log (CDC) into the shards.
5. Verify: compare row counts and checksums per partition; shadow-read (read both, compare,
   serve old) and log mismatches.
6. Cut over reads, then writes, a few partitions at a time (behind a config flag), watching
   errors and latency. Keep the ability to roll back.
7. Once all partitions are cut over and stable, stop syncing and retire the old database.
```

### Step 5: what got harder

- Cross-user queries are now scatter-gather or go to the warehouse.
- Schema migrations run on 16 databases (automate them; tolerate partial rollout).
- Backups and restores per shard; a restore must be coordinated if you need a consistent
  cross-shard point in time.
- Hot users (a huge business account) may need their own partition or shard: the directory
  approach handles it because partitions can be reassigned individually.

> **Say it like this:** "I'd shard by user_id into a fixed number of logical partitions, say 256,
> mapped onto 16 physical shards, each with replicas. User-scoped queries and checkout stay on
> one shard, global lookups go through small index tables, analytics move to a warehouse, and
> later growth is just reassigning partitions."

## 07.16 Misconceptions

- **"Replicas give me more write capacity."** In single-leader replication every replica applies
  every write; replicas add read capacity and availability, not write capacity. Sharding adds
  write capacity.
- **"Replication is a backup."** A bad `DELETE` or a corrupting bug replicates instantly to
  every replica. You still need point-in-time backups.
- **"W + R > N means strong consistency."** It makes stale reads unlikely but edge cases
  (concurrent writes, sloppy quorums, clock-based conflict resolution, partial write failures)
  remain.
- **"Consistent hashing balances load."** Plain consistent hashing with one point per node is
  uneven; virtual nodes fix data balance; hot keys still need separate handling.
- **"Shard early."** Sharding is a large, permanent complexity cost. Exhaust bigger hardware,
  query/index fixes, caching and read replicas first, unless estimates clearly show one machine
  can't cope.
- **"Async replication failover is lossless."** It can lose the last acknowledged writes.

## 07.17 Recap

- Replication = copies for availability, read scaling and latency; sharding = splitting for
  write capacity and storage. Large systems do both.
- Single-leader: one write order, no conflicts; sync is safe but slow, async is fast but can lose
  writes on failover, semi-sync is the usual middle.
- Replica lag breaks read-your-writes and monotonic reads; fix by routing fresh reads to the
  leader, tracking log positions, or pinning users to replicas.
- Failover needs majority-based election plus fencing to avoid split brain.
- Multi-leader suits multi-region and offline clients; conflicts are resolved by avoidance, LWW
  (loses data), app merge, or CRDTs.
- Leaderless: N replicas, write W, read R, W + R > N gives overlap; read repair, anti-entropy
  (Merkle trees) and hinted handoff keep replicas converging.
- Shard strategies: range (good scans, hot-spot risk), hash (even, no key scans), directory
  (flexible), geo (latency, residency).
- A good shard key has high cardinality, even load and query locality; salt hot keys.
- Cross-shard queries scatter-gather; local secondary indexes = cheap writes, all-shard reads;
  global indexes = one-shard reads, multi-shard (often async) writes.
- Consistent hashing: ring + binary search for the successor; ~1/N keys move on membership
  change; virtual nodes even out load.
- Rebalance with a fixed number of logical partitions mapped to nodes, or dynamic range splits;
  never `hash mod servers` directly.

## 07.18 Check yourself

1. A user updates their profile and immediately sees the old version. What happened and give two
   fixes.

<details><summary>Answer</summary>

The write went to the leader, the read went to an asynchronous follower that had not yet applied
it (replication lag), violating read-your-writes. Fixes: read the user's own data from the
leader (always, or for a short window after they write); or record the log position of the
user's last write and read only from a replica that has caught up to it.

</details>

2. With N = 3, which (W, R) pairs guarantee the read set overlaps the latest successful write
   set, and what do you trade between them?

<details><summary>Answer</summary>

Any pair with W + R > 3: (2,2), (3,1), (1,3), (3,2), (2,3), (3,3). (2,2) tolerates one replica
down for both reads and writes. (3,1) gives fast reads but any replica down blocks writes. (1,3)
gives fast writes but any replica down blocks reads. Higher values mean more latency and less
availability.

</details>

3. Why does `hash(key) mod N` move most keys when going from 10 to 11 nodes, and how much does
   consistent hashing move?

<details><summary>Answer</summary>

A key stays only if `h mod 10 == h mod 11`, which holds for about 1/11 of keys, so roughly 91%
move. With consistent hashing, only keys in the arc taken over by the new node move, about 1/11
of the data, all from its successors (spread across many nodes if virtual nodes are used).

</details>

4. Describe how to find the node for a key on a consistent hash ring, with its complexity.

<details><summary>Answer</summary>

Keep the ring positions of all (virtual) nodes in a sorted array. Hash the key, `lower_bound`
for the first position >= the hash; if past the end, wrap to index 0. That position's physical
node owns the key. O(log V) for V positions.

</details>

5. You shard a social app by `created_at`. What goes wrong? What would you use instead?

<details><summary>Answer</summary>

Range sharding on a timestamp sends all new writes to the shard holding the latest range: one
hot shard, the rest idle. Use a high-cardinality key that matches the queries, such as hashed
`user_id`, and keep time as a sort key within a user's data.

</details>

6. What is split brain and what two mechanisms prevent it?

<details><summary>Answer</summary>

Two nodes both act as leader and accept writes, so data diverges. Prevent it with majority
(quorum) based leader election, so only one leader can be elected per term, and fencing (e.g.
fencing tokens checked by storage, or cutting the old leader off) so a deposed leader that is
still running cannot write.

</details>

7. Users are sharded by `user_id`; you need login by email. Compare a local and a global
   secondary index.

<details><summary>Answer</summary>

Local: each shard indexes its own users' emails; writes are single-shard, but a login must ask
every shard. Global: a separate table `email -> user_id` sharded by hash(email); a login hits one
index shard then one user shard, but each signup or email change writes to two shards, needing a
distributed transaction or asynchronous index updates that may briefly lag. For login, the global
index is the usual choice.

</details>

8. Why map keys to 256 logical partitions instead of directly to 16 servers?

<details><summary>Answer</summary>

A key's partition never changes, so rebalancing only reassigns whole partitions to servers
(copy partition, flip ownership) without rehashing any key. It also allows moving a hot
partition individually and growing to up to 256 servers without changing the key mapping.

</details>
