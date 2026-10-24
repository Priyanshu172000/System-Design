# 07 — Replication and sharding: quick notes

**Replication** keeps copies of the same data on several machines. **Sharding** splits the data
so each machine holds a different part. Big systems do both.

## 1. Why replicate

- A **replica** is a full copy of the database on another machine.
- Survive a dead machine. *e.g.* the main database's disk fails at 8:30 pm; a replica already
  has every order and takes over.
- Spread reads. *e.g.* ~12,000 menu reads/s at peak over 3 replicas ≈ 4,000/s each.
- Be near users. *e.g.* a nearby copy avoids a 100+ ms trip across an ocean.

## 2. Single leader: one machine takes writes

- The **leader** takes all writes and streams its change log to **followers** (read-only
  copies), which apply changes in the same order. One writer means no conflicts.
- **Synchronous:** the leader says OK only after a follower confirms. Nothing acknowledged is
  lost, but a slow follower slows every write.
- **Asynchronous:** the leader says OK right away. Fast, but if it dies, the last
  acknowledged writes may be gone. *e.g.* Asha sees "Order placed", yet `o_90311` vanishes.
- **Semi-synchronous** (the usual setup): wait for **one** follower, the rest are async.
  *e.g.* `o_90311` is on two machines before Asha sees OK.

## 3. Replication lag: reading old data

**Replication lag** is how far a follower is behind: usually milliseconds, sometimes seconds.

| Problem | QuickBite example | Fix |
|---|---|---|
| **Read-your-writes** broken | Asha edits her address; the next read hits a lagging replica and shows the old one | Read your own edits from the leader for ~10–30 s, or only use replicas that caught up |
| **Monotonic reads** broken | Order shows PICKED_UP, refresh shows PREPARING (time goes backwards) | Pin each user to one replica |
| **Consistent prefix** broken | Spice Route's reply appears before Asha's review | Keep related writes in one partition |

- Sort reads before adding replicas. *e.g.* wallet balance before payment → leader; menus →
  replicas (a little stale is fine).

## 4. Failover and split brain

- **Failover:** detect the leader is dead (no heartbeat for ~10–30 s), choose the most
  up-to-date follower, point everyone at it.
- **Split brain:** two machines both think they are leader and both accept writes.
- **Majority vote** prevents it: with 3 nodes, only a group of 2 can elect a leader. *e.g.*
  network splits {N1 | N2, N3}; N2 wins 2 of 3; N1 alone can't win and must step down.
- **Fencing token:** a number that grows with each new leader; storage rejects old numbers.
  *e.g.* N2 gets token 34; N1's late write with token 33 is rejected.
- With async replication, failover can still lose the last writes.

## 5. Multi-leader and leaderless

- **Multi-leader (black box):** one leader per region or device, each taking local writes.
  Use it for multi-region writes or offline apps. Cost: **write conflicts**. *e.g.* Asha's
  offline phone adds Raita, her laptop adds Naan.
- Conflict fixes: send each user's writes to one home region (avoid it); **last-write-wins**
  (cart = {Naan}, Raita silently lost); merge in app code (both); **CRDTs**, data types that
  merge themselves (cart = {Raita, Naan}).
- **Leaderless** (Cassandra): the client writes to and reads from several replicas at once.
  **N** = copies, **W** = write acks needed, **R** = read replies needed.
- If **W + R > N**, every read set overlaps the latest write set (pigeonhole), and the newest
  version wins. *e.g.* N=3, W=2, R=2: write lands on A and B; any 2 you read include A or B.
- W=1, R=1 is fastest but can read stale data. W + R > N still isn't perfect: it makes stale
  reads unlikely, not impossible.
- Copies catch up via **read repair** (fix stale copies seen on a read), background
  comparison (**Merkle trees**: hashes over key ranges), and **hinted handoff** (a stand-in
  node keeps the write for a down node).

## 6. Sharding: splitting the data

Replicas don't add write capacity, because every copy applies every write. **Sharding**
(partitioning) puts each row on exactly one **shard**, chosen by the **shard key**. *e.g.*
~25,000 pings/s split by `rider_id` over 8 shards ≈ 3,125 writes/s each.

| Strategy | How | Good | Bad |
|---|---|---|---|
| **Range** | Key ranges, like dictionary volumes A–F, G–M | Range queries | Time key: all new writes on one shard |
| **Hash** | `hash(key)` picks the shard | Even spread | Range queries hit every shard |
| **Directory** | A lookup table | Flexible (big customer gets own shard) | One more critical lookup |
| **Geo** | By region | Data stays in a country | Uneven load |

- **Good shard key:** many distinct values, even load, and your main queries include it.
- *e.g.* orders by `user_id`: "Asha's orders" and checkout (order + wallet) hit 1 shard. The
  restaurant's live board would hit all 16, so keep a second copy keyed by `restaurant_id`.
- Bad keys: `created_at` (all new writes on one shard), `city` (few values), `status` (most
  rows are "active").

## 7. Hot keys, cross-shard queries, indexes

- **Hot key:** one value gets too much traffic. *e.g.* a viral promo sends 5,000 view
  counts/s to `views:r_77`.
- Fix by **salting**: write to `views:r_77#0`..`#9` (~500/s each), sum the 10 on read. Other
  fixes: cache it, batch the increments.
- **Scatter-gather:** a query without the shard key asks every shard, and waits for the
  slowest. *e.g.* shards answer in 4, 5, 6, 48 ms → query takes 48 ms.
- Cross-shard joins and transactions are slow and complex. Keep related tables on the same key;
  copy small tables (cities) to every shard.
- **Local index:** each shard indexes its own rows; login by email asks all 16 shards.
- **Global index:** a separate table keyed by email, e.g. `asha@x.com → u_1842`. Two lookups,
  but signup writes two places and the index can lag.

## 8. Consistent hashing

- **Problem:** with `hash(key) mod N`, going from 4 to 5 servers moves ~80% of keys.
- **Ring:** place servers and keys on a circle; a key belongs to the next server clockwise.
  Finding it is a binary search over sorted server positions.

```text
servers at 10, 30, 70, 90 on a 0..100 ring
key 40 -> next is 70 (N3)       key 95 -> wraps to 10 (N1)
add N5 at 50: only keys in (30,50] move to N5   (1 of 4 keys, vs 3 of 4 with mod)
```

- Adding a server moves only about 1/N of keys.
- **Virtual nodes:** each server sits at 100–256 points on the ring, so the load evens out.
- Replicas: a key's copies go on the next N distinct servers clockwise.

## 9. Rebalancing and putting it together

- **Fixed logical partitions:** `hash(key) mod 1024` never changes; servers own whole
  partitions. *e.g.* `u_1842` is always p579; adding a server just moves some partitions.
- Other approaches: split ranges as they grow (**dynamic**); a fixed count per node
  (Cassandra).
- Never put `hash(key) mod servers` in app code. Throttle data moves.
- **Shard and replicate:** each shard has its own leader and followers, spread so each node
  leads some and follows others. A **routing layer** maps key → shard → current leader.
- Moving from 1 to 16 shards (big-store example): shard by `user_id`; use 256 fixed
  partitions; use globally unique IDs; copy data over while live and switch a few
  partitions at a time.
- **IDs:** per-shard auto-increment collides. Use time-ordered 64-bit IDs (Snowflake-style) or
  UUIDv7.
- Shard last: try bigger hardware, indexes, caching and replicas first.
- Replication is **not** a backup: a bad `DELETE` is copied everywhere instantly.

## Remember these 5

1. Replicas add reads and survival, not write capacity; sharding adds write capacity.
2. Async replication is fast, but failover can lose acknowledged writes.
3. Split brain is stopped by majority vote plus fencing tokens.
4. Shard key: many values, even load, and in your main queries.
5. Consistent hashing moves ~1/N of keys when a server joins; virtual nodes even out load.
