# 07 — Replication and sharding

*Why it matters:* every large system eventually needs copies of its data (replication) and splits of its data (sharding), and both bring anomalies you must be able to name and fix.

```text
Replication: same data, many copies       Sharding: different data, many machines
  [A B C]  [A B C]  [A B C]                  [A]      [B]      [C]
  more availability, more reads              more write capacity, more storage
```

## Why replicate

**How it works** · **Replication** keeps copies on several **replicas** for (1) availability/durability when a machine dies, (2) read scaling (10:1 or 100:1 read:write is common), (3) latency (a replica near users avoids a roughly 100+ ms cross-ocean trip). The designs differ in who accepts writes: one leader, many leaders, or none.

**Example** · QuickBite at the 8–9 pm peak: ~120 orders/s, and menu views run ~100:1, so ~12,000 menu reads/s. One leader takes the order writes; 3 read replicas split the menu reads (~4,000/s each). At 8:30 pm the leader's disk fails: a replica already has the data and takes over. So: replicas buy reads and survival; the hard part is keeping them in sync.

## Single-leader replication

**How it works** · One **leader** (primary) takes all writes and streams its ordered change log (usually from the WAL, ch 05) to **followers** (read replicas), which apply it in the same order. One place decides write order, so no conflicts. Default in PostgreSQL, MySQL, MongoDB replica sets.

**Example** · `order-service` commits `o_90311`; leader in zone a, semi-sync follower in zone b, async followers in zone c:

```text
1. leader writes o_90311 to its WAL
2. sends to zone b, waits for "received" ack   (~1-2 ms cross-zone round trip)
3. returns OK to order-service; Asha sees "Order placed"
4. zone c followers apply it a few ms later
5. leader dies right after step 3:
   semi-sync: zone b has o_90311 -> promote it, nothing lost
   async-only (skip step 2): followers may lack o_90311 -> an acknowledged order is gone
```

| Mode | Leader returns OK when | Gain | Pay |
|---|---|---|---|
| **Synchronous** | Followers confirmed | No acknowledged write lost on leader death | Waits for slowest required follower; stalls if it is down |
| **Asynchronous** | Leader alone committed | Fast; slow followers don't matter | Leader death **loses** recent acknowledged writes |
| **Semi-synchronous** | At least one (or N configured) follower acked receipt | Each acked write on two machines | Some latency; some fall back to async on timeout |

Common setup: one sync/semi-sync follower, the rest async. MySQL semi-sync waits for the replica to *receive* (relay log), not apply. PostgreSQL `synchronous_standby_names` / `synchronous_commit` choose how many standbys and at which stage (received, flushed, applied).

## Replication lag and its anomalies

**How it works** · **Replication lag** is usually milliseconds, but seconds or minutes under load, long replica queries or network trouble. Async replicas are **eventually consistent**, and lag shows up as three anomalies.

**Example** · Read-your-writes, and its fix by log position:

```text
t=0.0s  Asha changes delivery address; leader commits at log position 5120; OK
t=0.2s  profile read goes to replica B (applied up to 5090) -> OLD address
        "my edit vanished!"
fix:    app remembers 5120 for u_1842 (cookie / session)
        replica B at 5090 < 5120 -> skip; replica A at 5130 >= 5120 -> serve
        no replica caught up -> read from the leader
```

| Anomaly | QuickBite example | Fix |
|---|---|---|
| **Read-your-writes** violated | Above: own address edit "vanishes" | Read user-editable data from the leader; route a user's reads to the leader for ~10–30 s after a write; or the log-position check above |
| **Monotonic reads** violated | Order page shows PICKED_UP (replica A, lag 0.1 s), refresh shows PREPARING (replica B, lag 5 s): time goes backwards | Pin each user/session to one replica (hash user id); re-pin on failure |
| **Consistent prefix** violated | Spice Route's reply appears before Asha's review it answers (different partitions, different lag) | Keep causally related writes in one partition, or track causal dependencies |

> **Common mistake:** adding read replicas without classifying reads. "Must be fresh" (after the user's own write, wallet balance before payment) goes to the leader; "may be stale" (menus, search, others' reviews) goes to replicas.

## Failover and split brain

**How it works** · **Failover**: (1) **detect** the leader is dead via missed heartbeats over a timeout (say 10–30 s); a slow or partitioned leader looks the same as a dead one; (2) **choose** a new leader, ideally the most up-to-date follower, a consensus problem (ch 10); (3) **reconfigure** clients and followers; a returning old leader must become a follower. **Split brain** = two nodes both accept writes as leader and histories diverge.

**Example** · Three nodes, leader N1 holds **fencing token** 33:

```text
t=0    network splits {N1 | N2, N3}; some app pods can still reach N1
t=15s  N2, N3 miss heartbeats; N2 asks for votes: {N2, N3} = 2 of 3, a majority
       -> N2 is leader with token 34
       N1 alone is 1 of 3: it can never win a majority, so it should step down
t=16s  N1 has not noticed yet and sends a write with token 33 to storage
       storage has already seen 34 -> rejects 33. No divergent history.
```

So, prevention: **quorum-based election** (two majorities of one fixed set overlap, so one leader per term, Raft-style); **fencing** (fencing tokens as above, revoking storage access, or **STONITH**: power off the other node); a leader that loses the majority **steps down**. **Manual** failover is slower but avoids false alarms; **automatic** is faster but must be designed carefully. Risks: with async replication the new leader lacks the last writes, usually discarded when the old leader returns (bad if other systems acted on them, e.g. an order id already in a cache or an email); a timeout too short causes needless failovers under load spikes, too long means longer downtime.

> **Say it like this:** "Automatic failover needs majority-based election so only one leader is chosen, and fencing so a deposed leader still alive can't keep writing. With async replication, failover can lose the last acknowledged writes."

## Multi-leader replication

> **Black box:** a leader per region (or per offline device, or per collaborative editor) accepts writes locally and replicates async to the others. **Use when:** multi-region writes with local latency and region-outage survival, offline clients, collaborative editing. **Cost:** concurrent **write conflicts** must be resolved, e.g. Asha's offline phone adds Raita to her cart at t=100 while her laptop adds Naan at t=101. Not worth it for HA inside one data center.

Conflict resolution, from most to least practical, on that cart:

- **Avoid:** route each record's writes to its "home" leader (user's home region), so the conflict never forms.
- **Last-write-wins (LWW):** highest timestamp wins: cart = {Naan}; Raita **silently discarded**, and clocks are never perfectly in sync (ch 10). Fine for "last seen", bad for counters or carts.
- **App merge:** keep both "siblings", let app or user merge: cart = {Raita, Naan} (or a "conflicting copy" file).
- **CRDTs:** data types whose concurrent updates merge automatically and converge in any order: an add/remove set gives {Raita, Naan}; a grow-only counter with region counts 5 and 3 reads 8; sequence CRDTs for text. **Operational transformation** is the older text-editing cousin.

Versus single-leader: low (local) write latency for distant users and writes during a region outage without failover, but conflicts to resolve and high complexity (single-leader: no conflicts, low complexity).

## Leaderless replication and quorums

**How it works** · No leader (Dynamo paper; Cassandra, ScyllaDB, Riak). The client or a coordinator sends each write and read to several replicas in parallel. **N** = replicas per key, **W** = acks needed for a write, **R** = replies needed for a read. If **W + R > N**, every read set overlaps every successful write set (pigeonhole), and the reader picks the newest version.

**Example** · Key `cart:u_1842`, N = 3 on replicas A, B, C, all at v1 = {Biryani}:

```text
W=2, R=2  write v2 = {Biryani, Raita}: A ack, B ack -> 2 acks, OK. C slow, still v1
          read pairs: {A,B} -> v2,v2   {A,C} -> v2,v1   {B,C} -> v2,v1
          every pair contains A or B -> newest is always v2
          read of {B,C} also does read repair: C <- v2
W=1, R=1  write v2 acked by A only (1 + 1 = 2, not > 3)
          read asks C alone -> v1: stale read
so: overlap puts a fresh copy in every read set; version numbers pick it
```

With N=3: **W=2, R=2** balanced, tolerates 1 node down for reads and writes; **W=3, R=1** fast reads, any replica down fails writes; **W=1, R=3** fast writes, any replica down fails reads; **W=1, R=1** fastest and most available, but W+R is not > N, so reads can be stale.

Cassandra calls these **consistency levels** (`ONE`, `QUORUM` = 2 of 3, `ALL`, `LOCAL_QUORUM`), chosen per query. Caveat: **W + R > N is not linearizability**. Concurrent writes, sloppy quorums, LWW with imperfect clocks, or a write that reached fewer than W nodes (reported failed, not rolled back) can still surprise readers; quorums make stale reads unlikely, not impossible.

> **Black box:** convergence. **Read repair** writes the newest value back to stale replicas a read touched (good for hot keys). **Anti-entropy** compares replicas via **Merkle trees** (hashes over key ranges; descend only into mismatched subtrees). **Hinted handoff** + **sloppy quorum**: e.g. home replicas A, B, C with B and C down; stand-in D stores the write "for C" and counts toward W = 2, then hands it to C on return. **Cost:** a read from B and C before handoff misses it: W + R > N no longer guarantees overlap with the home replicas.

## Why shard, and sharding strategies

**How it works** · Replication copies everything, so it cannot help when data is too big (40 TB on one machine), writes are too many (every replica applies every write), or the working set exceeds RAM. **Sharding** (**partitioning**) splits rows into **shards**, each on its own machine or replica set; each record lives in exactly one. (Not the same as table partitioning on one server, which only eases pruning and dropping old data.) The **shard key** decides placement.

**Example** · Rider pings at ~25,000 writes/s: adding replicas doesn't help, since each replica still applies all 25,000/s. Split by `rider_id` over 8 shards → ~3,125 writes/s each. How the key picks the shard, per strategy:

```text
range on created_at  [Sep 17-23]->s1 ... [Oct 8-14]->s4: all writes today hit s4
hash, mod 4          hash(u_1842) = 1,000,003 -> mod 4 = 3 -> s3
                     hash(u_1843) = 7,410,226 -> mod 4 = 2 -> s2   (neighbors scatter)
directory            chain_42 (300 outlets) -> s3 by lookup; everyone else hashed
geo                  (if QuickBite expanded) EU users -> EU shards, data stays in the EU
```

| Strategy | Even spread | Range queries on key | Use |
|---|---|---|---|
| **Range** (contiguous key ranges, auto-split as they grow) | Hot-spot risk (timestamp key = all writes on the newest range) | Excellent | Sorted / time-ordered data, HBase, Bigtable, distributed SQL |
| **Hash** (`hash(key)` picks shard) | Good, even for sequential keys | Scatter to all shards | User-keyed data, KV stores |
| **Directory** (lookup table) | As good as you manage | Depends | Multi-tenant SaaS; extra (cached) lookup, critical dependency |
| **Geo** | Uneven | Within a region | Latency, **data residency** laws; movers need handling |

Cassandra-style compromise: hash the partition key across nodes, sort by clustering key inside the partition (ch 06).

## Choosing a shard key

**How it works** · Want **high cardinality**, **even data and load**, and **query locality** (frequent queries include the key; data updated together co-locates so single-shard transactions work).

**Example** · QuickBite `orders` over 16 shards, by `user_id` vs `restaurant_id`:

```text
                     "Asha's orders"   "r_77 live board"   checkout (order + wallet)
shard by user_id     1 shard           all 16 shards       1 shard (co-located)
shard by restaurant  all 16 shards     1 shard             2 shards (wallet is per user)
```

So: `user_id` wins (most queries, single-shard checkout); the restaurant board gets a second copy keyed by `restaurant_id` (a global index, below), and a restaurant key would also pile a popular chain's dinner rush onto one shard.

Other verdicts: `order_id` when the main query is "my orders": weak (fans out); `created_at`: bad for writes (newest range is a hot spot); `city` / `country`: bad (few values, ~200 countries, huge skew); `tenant_id`: good with care (huge tenants need their own shard, directory); `conversation_id` for chat: good (reads are per conversation); `status`: terrible ("active" holds most rows).

## Hot spots and the celebrity problem

**How it works** · One *value* can still be hot (a celebrity post read by 100 M followers, its like-counter taking thousands of writes/s on one shard). Fixes: **key salting**, **caching** hot reads (ch 08, even in-process for extreme keys), **aggregating writes** (buffer increments, apply in batches; approximate counts are fine for likes), a **dedicated shard** for a known huge tenant, or **changing the design** (pull a celebrity's post at read time instead of pushing into 100 M feeds, ch 17).

**Example** · A viral promo sends 5,000 increments/s to `views:r_77`, all on one shard:

1. Salt: each write goes to `views:r_77#` + random(0..9): 10 keys on up to 10 shards, ~500 writes/s each.
2. Read: sum `views:r_77#0` ... `#9` (10 reads), or keep a cached total refreshed every few seconds.
3. Salt only known or detected hot keys: every salted read now fans out 10x.

## Cross-shard queries, joins, transactions, secondary indexes

**How it works** · A query without the shard key is **scatter-gather**: every shard works and latency is set by the slowest shard (**tail latency** grows with shard count).

**Example** · "10 most recent orders over ₹2,000, all users", orders sharded by user over 4 shards:

```text
each shard returns its own newest 10 such orders
  (all 10 winners might sit on one shard, so each must send 10, not 10/4)
coordinator merges 40 rows with a heap -> global top 10
shard latencies 4, 5, 6, 48 ms -> query takes 48 ms
```

Joins across differently sharded tables become scatter-gather or app code: **co-locate** related tables on the same key, and **replicate small static tables** (cities, currencies) to every shard. Cross-shard **transactions** need two-phase commit (ch 10) or a saga (ch 13), both slow and complex.

**Example** · Users sharded by `user_id`, login by email on 16 shards. **Local index**: each shard indexes its own emails, so a login asks all 16. **Global index**: `email_index` sharded by hash(email); `asha@x.com` → index shard 5 → `u_1842` → user's shard: 2 lookups, but a signup writes the data shard and the index shard.

| | **Local index** | **Global index** |
|---|---|---|
| Write | 1 shard | Data shard + index shard |
| Read by email | All shards | 1 index shard, then 1 data shard |
| Consistency | Synchronous | Often async, can lag (e.g. DynamoDB GSIs are eventually consistent) |

## Consistent hashing

**How it works** · `hash(key) mod N` spreads evenly, but from N to N+1 a key stays only if `h mod N == h mod (N+1)`, about **1/(N+1)** of keys: 4 → 5 shards moves ~80% (a cache miss storm, a huge migration). The **ring**: map hash space (0..2^32-1) onto a circle; hash each node and each key onto it; a key belongs to the first node **clockwise** (its successor). Lookup = `lower_bound` (or `TreeMap.ceilingKey`) over sorted node positions, wrapping to index 0: O(log V).

**Example** · Ring 0..100 for readability, cache nodes N1=10, N2=30, N3=70, N4=90; menu keys hash to 25, 40, 55, 95:

```text
positions [10,30,70,90]   owns: N1 (90,10]  N2 (10,30]  N3 (30,70]  N4 (70,90]
lookup 25 -> lower_bound idx 1 -> N2      40 -> N3      55 -> N3
lookup 95 -> idx 4, past end -> wrap idx 0 -> N1
add N5 at 50: only (30,50] changes owner, N3 -> N5
  25 N2 stays | 40 N3 -> N5 MOVES | 55 N3 stays | 95 N1 stays      1 of 4 keys moves
same 4 keys with mod 4 -> mod 5: 25: 1->0, 40: 0->0, 55: 3->0, 95: 3->0   3 of 4 move
arcs before: N3 owned 40% of the ring, others 20%  -> uneven
```

So: a new node takes over one arc, about 1/N of the keys, all from its successor; removal hands a node's keys to its successor.

- **Virtual nodes:** each physical node sits at many points (100–256, hashing "N1#0", "N1#1"...). Many small arcs average out (law of large numbers); a leaving node's load spreads over many successors; bigger machines get more vnodes. Cost: a bigger sorted array (100 nodes x 256 = 25,600 entries, still tiny), more ranges to track.
- **Replication on the ring:** a key goes to its successor plus the next N-1 *distinct physical* nodes clockwise (Dynamo/Cassandra): with N=3, key 25 lives on N2, N5, N3 (after N5 joined).

> **Say it like this:** "Consistent hashing puts nodes and keys on a ring; a key belongs to the next node clockwise, found by binary search. Adding a node moves only the arc it takes over, about 1/N of the data, and virtual nodes keep arcs even."

## Rebalancing

**How it works** · Goals: even load, keep serving, move little. Three approaches:

1. **Fixed number of partitions:** many more than nodes; `partition = hash(key) mod 1024` never changes; a new node steals whole partitions. Elasticsearch fixed primary shards, Redis Cluster 16,384 slots, Couchbase 1,024 vBuckets. Pay: pick the count up front (too few = huge partitions and a node cap; too many = overhead).
2. **Dynamic partitioning:** ranges split past a size threshold, merge when small (HBase, Bigtable, MongoDB chunks, distributed SQL); empty DB starts with one partition unless **pre-split**.
3. **Partitions proportional to nodes:** fixed count per node (Cassandra vnodes); a new node splits existing ranges.

**Example** · 1,024 partitions on 8 nodes (128 each; node4 owns p512..p639). `u_1842`: 1,000,003 mod 1024 = 579 → p579 → node4. Add node8: seven nodes hand it 14 partitions and one hands 15, so node8 gets 113, seven nodes keep 114, one keeps 113 (7 x 114 + 113 + 113 = 1,024). If p579 is among node4's 14: copy p579 to node8, then flip ownership; `u_1842` is still p579, nothing rehashes.

Throttle data movement, and prefer system-*proposed* moves approved by a human or rate-limited process: auto-rebalancing can declare a slow node dead and add load. Never put `hash(key) mod servers` in app code; map keys to fixed logical partitions, partitions to servers.

## Replication and sharding together

**How it works** · Shard the data, replicate each shard; each node leads some shards and follows others, spreading load and risk. A **routing layer** (smart client, proxy, or forwarding node) maps key → partition → shard → current leader, using a highly available config service (consensus-based, ZooKeeper/etcd-style, ch 10) or gossip.

**Example** · Shard 0 led by node A (followers B, C), shard 1 led by B (followers C, A), shard 15 led by C (followers A, B). Node B dies:

1. Shard 1 loses its leader → promote its follower on C (or A); the config service records the new leader.
2. Shards 0 and 15 lose a follower → create a new replica on another node.
3. Routers refresh the map; requests for shard 1 now go to C. Other shards never stopped.

## Worked example: users and orders, 1 to 16 shards

**How it works** · A large store (far bigger than QuickBite today): one PostgreSQL primary + 2 async replicas; 80 M users, 1.5 B orders, 4 B order items, ~6 TB growing ~2 TB/year; peak ~15,000 writes/s; primary near limits, replicas lag at peak; reads mostly per user.

1. **Shard key `user_id`.** Add `user_id` to `order_items` (denormalized) so a user's data co-locates and checkout is single-shard. Products/inventory are shared, so keep them in a separate database (unsharded or by `product_id`); checkout across the two uses a reservation step plus saga or outbox (ch 11, 13), not a cross-DB transaction.
2. **256 logical partitions** (`hash(user_id) mod 256`, fixed) mapped by config to 16 shards (shard k owns 16k..16k+15); going to 32 just moves 8 partitions off each shard. 6 TB / 16 ≈ 375 GB per shard now (~500 GB in a year); 15,000 / 16 ≈ 940 writes/s per shard at peak.
3. **IDs and non-user lookups:** globally unique time-ordered 64-bit IDs (Snowflake-style: timestamp + generator/shard + sequence bits) or UUIDv7, since per-shard auto-increment collides; order_id → shard by encoding the partition in the ID or a small `order_id -> user_id` table; login via a global `email -> user_id` index; analytics via a warehouse (ch 09), not scatter-gather.
4. **Migrate without downtime:** provision shards → deploy routing but keep using the old DB → backfill partition by partition → keep current via dual-write or (more robust) CDC → verify counts, checksums, shadow reads → cut over reads then writes a few partitions at a time behind a flag, rollback ready → retire the old DB.
5. **What got harder:** cross-user queries, schema migrations on 16 DBs, per-shard backups (consistent cross-shard point in time needs coordination), hot accounts needing their own partition.

**Example** · Routing "my orders" for `u_1842` after the cut-over:

```text
hash(u_1842) = 1,000,003 -> mod 256 = 67        (256 x 3,906 = 999,936; remainder 67)
partition 67 -> shard 4 (owns p64..p79)          (config map)
shard 4 leader -> SELECT ... WHERE user_id = 'u_1842' ORDER BY created_at DESC LIMIT 20
later 16 -> 32 shards: p67 may move to shard 20, the hash and partition never change
```

Myths to drop: replicas add write capacity (they don't; sharding does), replication is a backup (a bad `DELETE` replicates instantly; keep point-in-time backups), W + R > N is strong consistency, plain consistent hashing balances load (vnodes needed; hot keys still need handling), "shard early" (exhaust bigger hardware, indexes, caching and replicas first), async failover is lossless.

## In one minute

- Replication = copies for availability, reads and latency; sharding = splits for write capacity and storage; big systems do both.
- Single-leader has one write order; async is fast but failover can lose acked writes; semi-sync is the usual middle.
- Lag breaks read-your-writes, monotonic reads and consistent prefix; route fresh reads to the leader, track log positions, pin users.
- Failover needs majority election plus fencing to avoid split brain; multi-leader suits multi-region/offline and needs conflict resolution.
- Leaderless: W + R > N makes read and write sets overlap; read repair, anti-entropy and hinted handoff converge replicas.
- Shard key: high cardinality, even load, query locality; salt or cache hot keys; cross-shard queries scatter-gather.
- Consistent hashing: ring + binary search for the successor, ~1/N keys move, vnodes even it out.
- Map keys to fixed logical partitions, partitions to servers; never `hash mod servers`.

## Go deeper

Full chapter 07: sections 07.3 (replication lag anomalies), 07.6 (leaderless quorums), 07.12 (consistent hashing), 07.15 (worked sharding migration).
