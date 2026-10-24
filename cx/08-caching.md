# 08 — Caching

*Why it matters:* almost every design touches caching (where, how long, how to keep it fresh, what breaks), and at scale the cache becomes load-bearing.

## Why cache: latency, offload, hit ratio

**How it works** · Popular reads repeat the same work and return the same answer; store it once in a fast place (usually memory). Two wins: **lower latency** and **offload** (often bigger: a DB at 90% CPU can drop to 20%).

Rough times: in-process hash map ~100 ns; Redis/Memcached same DC ~0.2–1 ms; DB query with data in DB memory ~1–5 ms; DB hitting disk or doing joins/sorts ~5–100+ ms; cross-continent to origin ~100–200 ms.

**Hit ratio** `h` = hits / (hits + misses). Check cache first, DB on a miss: `latency = T_cache + (1 - h) * T_db`.

**Example** · QuickBite menu reads at the dinner peak: 120 orders/s x 100 views per order = 12,000 `GET menu:r_77`-style reads/s; `T_cache` = 0.5 ms, `T_db` = 10 ms.

| h | Effective latency | Reads reaching the DB |
|---|---|---|
| 0% | 0.5 + 1.00 x 10 = 10.5 ms | 12,000/s |
| 80% | 0.5 + 0.20 x 10 = 2.5 ms | 2,400/s |
| 95% | 0.5 + 0.05 x 10 = 1.0 ms | 600/s |
| 99% | 0.5 + 0.01 x 10 = 0.6 ms | 120/s |

So: 95% -> 99% saves only 0.4 ms but cuts DB load **5x** (600 -> 120/s). The **miss rate** is what the DB feels.

- The reverse is the danger: a Redis restart at 8 pm drops `h` from 99% to ~0%, and the DB sized for 120/s gets 12,000/s (100x) in seconds. "The cache is just an optimization" is false at scale.
- Cacheable data: **read-heavy**, **skewed access** (80/20), **tolerates slight staleness**, **expensive to compute**.

> **Say it like this:** "The cache buys latency, but the bigger win is offload: at 99% hit ratio the DB sees 1% of reads. So the cache is load-bearing and we need a plan for a cold or failed cache."

## Where caches live

**How it works** · A request may pass several layers: browser -> CDN edge -> reverse proxy -> in-process -> distributed cache -> DB buffer pool -> disk.

**Example** · Asha (`u_1842`) opens Spice Route (`r_77`) in Bengaluru.

```text
1. dish photos: browser cache hit (max-age=1y)            ~0 ms, no network
2. new photo: CDN edge in Bengaluru hit                     ~20 ms
3. GET /restaurants/r_77/menu -> api-gateway -> restaurant-service
4. in-process LRU in that instance: miss                    ~100 ns
5. Redis GET menu:r_77: hit                                 ~0.5 ms
   (on a Redis miss: Postgres, rows in buffer pool          ~1-5 ms)
so: each layer is cheaper than the one below, and each one is another stale copy
```

| Layer | Stores | Gain | Pay |
|---|---|---|---|
| Browser | HTTP responses | No network on repeat | Cannot purge; rely on expiry + versioned URLs |
| CDN | Static files, some API responses | Near user, absorbs huge traffic | Invalidation slow-ish, costs money; shareable content only |
| Reverse proxy (Nginx/Varnish) | Full responses | Shields app servers | Coarse; personalized pages hard |
| **In-process** (local) | Objects in app memory | ~100 ns | Per-instance copies -> inconsistency; heap-limited; lost on restart |
| **Distributed** (Redis/Memcached) | Key-value | Shared, large, ~1 ms | Extra hop, another system, can fail |
| DB buffer pool | Disk pages | Automatic | Little control; still pays parse/execute |

- In-process cache on 50 `restaurant-service` instances: a price change must reach all 50, or each serves the old price until its TTL.

## Caching patterns

**How it works** · A pattern answers: who loads the cache on a read, and what happens on a write.

```text
CACHE-ASIDE READ                         CACHE-ASIDE WRITE
v = cache.get(k); if v: return v         db.update(id, fields)
v = db.query(...)                        cache.delete(k)   # next read reloads
cache.set(k, v, ttl=300s); return v
```

**Example** · Spice Route raises biryani from ₹250 to ₹280; then Asha opens the menu.

| Pattern | On the price write | Asha's next read |
|---|---|---|
| **Cache-aside** | DB = 280, `DEL menu:r_77` | Miss -> DB 280 -> `SET` -> 280 |
| **Read-through** | Same as cache-aside (paired with invalidation) | Cache's own loader fetches 280 |
| **Write-through** | Cache = 280 and DB = 280, both before the ack | Hit, 280 |
| **Write-behind** | Cache = 280, ack; DB written ~5 s later in a batch | Hit, 280 (DB still 250 meanwhile) |
| **Write-around** | DB = 280 only (key deleted or left to expire) | Miss -> DB 280 |

| Pattern | Gain | Pay | Use when |
|---|---|---|---|
| **Cache-aside** (lazy) | Simple; cache down = reads fall to DB; only hot data cached | First read slow; stale races | Default for read-heavy |
| **Read-through** | Cleaner app code (same logic, the cache owns it) | Cache must know how to load | In-process libs with a loader callback |
| **Write-through** | Fresh reads after writes | Two writes per write; caches unread data | Data read soon after write |
| **Write-behind** (write-back) | Very fast writes, absorbs bursts | **Data loss** if cache dies before flush | Counters, metrics |
| **Write-around** | Cache not polluted | Fresh writes miss on first read | Logs, bulk imports |

> **Common mistake:** Write-behind for money or orders. If `o_90311` is acked from Redis and the node dies before the 5 s flush, Asha was told "order placed" for an order that no longer exists anywhere. Fine for a restaurant view counter, never for orders.

## TTL

**How it works** · A **TTL** expires an entry; the next read reloads. Every entry should have one: it bounds staleness even if invalidation has a bug, and frees memory of unread keys.

**Example** · A new code path forgets to delete `menu:r_77` after the price change. With TTL = 300 s, Asha sees ₹250 for at most 5 minutes, then a miss reloads ₹280. Cost: if all 50,000 menus are read, each reloads once per 300 s, roughly 50,000 / 300 ≈ 167 DB reads/s. TTL 30 s -> fresher, but ≈ 1,670/s.

Short TTL (seconds) = fresher, more DB load (prices, stock, open/closed). Long TTL (hours) = higher hit ratio, staler if invalidation misses (config, reference data, versioned content). Rule: TTL = how stale the business can tolerate if every other invalidation fails.

## Invalidation and its races

**How it works** · Cache and DB are two copies updated non-atomically; another request can sneak in between steps.

**Example** · Race 1, *update* the cache after the DB write (two price edits, ₹260 then ₹280):

```text
t=0 ms  A: DB price=260
t=1 ms  B: DB price=280
t=2 ms  B: SET menu:r_77 {biryani:280}
t=5 ms  A: SET menu:r_77 {biryani:260}   (A's network was slow)
final:  DB 280, cache 260 -> stale until TTL
```

So: **delete, do not update**. Two `DEL`s in any order leave "no entry", and the next read loads 280. Deletes are idempotent and order-independent.

**Example** · Race 2, delete-on-write with a slow reader (the classic cache-aside race):

```text
t=0 ms  R: GET menu:r_77 -> miss
t=1 ms  R: SELECT price -> 250                    (old value in R's hands)
t=2 ms  W: UPDATE price=280; COMMIT
t=3 ms  W: DEL menu:r_77                          (deletes nothing: key absent)
t=9 ms  R: SET menu:r_77 {biryani:250} ttl=300    (GC pause / slow network)
final:  DB 280, cache 250 for up to 300 s
```

So: it needs R's DB read *before* the write and R's `SET` *after* the delete. Rare, but at 12,000 reads/s it happens daily.

| Mitigation | Helps | Cost |
|---|---|---|
| TTL on every key | Bounds damage (300 s above) | Does not prevent |
| Delayed double delete (again after ~1 s) | Second `DEL` at t≈1 s removes R's late 250 | Timing is a guess |
| Versioned values / compare-and-set | `SET` only if the value's version is higher | Version column + atomic logic (e.g. Redis Lua) |
| **CDC-driven invalidation** | Tails the DB change log, deletes after commit; catches every write path | Extra infra, small delay |
| Lease / fill token | R gets token on the miss; W's `DEL` voids it; R's late `SET` rejected | Needs cache support |

- **Derived keys**: if Spice Route's name is embedded in `menu:r_77`, `search:biryani:blr` and `order_page:o_90311`, a rename must find every key. Prefer small normalized objects keyed by ID, or accept TTL staleness on composed blobs.

## Eviction policies and LRU

**How it works** · When full, an **eviction policy** picks a victim.

| Policy | Evicts | Weakness |
|---|---|---|
| **LRU** | Least recently accessed | One-time scan flushes the hot set |
| **LFU** | Fewest accesses | Old popular items linger; needs counter decay |
| FIFO | Oldest inserted | Ignores usage |
| TTL-based | Expired first | Not a full policy alone |
| Random | Random | May evict hot entries; cheap, surprisingly OK |

Redis offers `allkeys-lru`, `allkeys-lfu`, `volatile-ttl`, etc., approximated by **sampling** a few keys, not a global order. **LRU in O(1)** = hash map (key -> node) + doubly linked list (head = most recent). `get`: lookup, unlink, move to head. `put`: insert at head; over capacity, remove tail and its map entry. Doubly linked so a node unlinks in O(1) from its pointer.

**Example** · Capacity 3, menus as keys (list shown head -> tail).

```text
put r_1, put r_2, put r_77   ->  r_77, r_2, r_1
get r_1                      ->  r_1, r_77, r_2      (r_1 moved to head)
put r_9 (over capacity)      ->  evict tail r_2; map.remove(r_2)
                             ->  r_9, r_1, r_77
so: the victim is the key untouched longest, found in O(1) at the tail
```

A nightly job reading all 50,000 menus once would push every hot menu out (LRU's scan weakness); LFU keeps them. LFU in O(1): map frequency -> list of keys + a min-frequency pointer.

## When caches hurt: stampede, penetration, avalanche, hot keys

**How it works** · Four failure modes, each with a different cause and fix.

**Example** · **Stampede / thundering herd**: `top_restaurants:blr` (a 200 ms query) expires at 8:00:00 pm; 5,000 requests arrive during the 200 ms rebuild, all miss, and the DB runs 5,000 identical expensive queries.

| Fix | Loads of that query |
|---|---|
| None | 5,000 |
| **Request coalescing (single flight)**: later requests in a process wait for the in-flight load | 20 (one per instance, 20 instances) |
| **Distributed lock/lease**: `SET lock:top_restaurants:blr 1 NX PX 3000`; winner rebuilds, others wait or serve stale | 1 |
| **Stale-while-revalidate**: serve old value past soft expiry, one request refreshes in background | 1, nobody waits |

- **Probabilistic early expiration**: refresh if `now - delta * beta * ln(rand()) >= expiry` (`delta` = recompute time, `beta` ~ 1). With `delta` = 0.2 s: `rand()` = 0.01 gives `-ln` = 4.6, so that reader refreshes if within 0.92 s of expiry; `rand()` = 0.5 gives 0.14 s. A few readers refresh early, spread out.

**Example** · **Penetration**: a scraper requests `r_9384721`, `r_5512093`, ... (non-existent). Every one misses Redis, misses the DB, and nothing gets cached, so 100% reach the DB. Fixes: **cache negatives** (`SET menu:r_9384721 NOT_FOUND EX 60`), a **Bloom filter** of the 50,000 real ids in front ("no" is definite, reject; "maybe" proceeds: false positives, no false negatives), input validation, rate limiting.

**Example** · **Avalanche**: a warm-up job at 7:55 pm loads all 50,000 menus with TTL 3,600 s, so all expire at 8:55 pm, mid-peak. With **jittered TTLs** (3,600 s +- 10% = 3,240..3,960 s) expiries spread over 720 s: about 50,000 / 720 ≈ 69 reloads/s instead of 50,000 at once. Also: gradual warm-up, HA cache with replicas, circuit breakers / load shedding (a full cluster restart is an avalanche too).

**Example** · **Hot key**: a promo sends 40,000 reads/s to `menu:r_77`, which lives on one Redis shard; that shard saturates while others idle. Fixes: a short-TTL (1–5 s) **local cache** (50 instances x 1 reload per 2 s = 25 Redis reads/s for that key); **key splitting** (`menu:r_77#0..#9` on different shards, read a random suffix, ~4,000/s each, write all 10); read from shard replicas; detect hot keys via sampling/metrics.

> **Say it like this:** "For the hot item I'd add a short-TTL local cache in each app server so the Redis shard isn't the bottleneck, plus single-flight loading so an expiry doesn't stampede the database."

## Redis and Memcached

**How it works** · **Redis** is an in-memory store with typed values and server-side ops.

| Type | Typical use |
|---|---|
| String | Cached objects, `INCR` counters, locks `SET key val NX PX ttl` |
| Hash | Object fields, update one field |
| List | Simple queues, last-100 (`LPUSH` + `LTRIM`) |
| Set | Tags, likes, intersections (mutual friends) |
| **Sorted set** | **Leaderboards** (`ZINCRBY`, `ZREVRANGE 0 9`), priority queues, time indexes, sliding-window rate limits |
| Stream | Lightweight log with consumer groups |

Also bitmaps, HyperLogLog, geo. Sorted set = hash map + **skip list**: O(log n) update, O(log n + k) range.

**Example** · "Most ordered dishes in Bengaluru today", one sorted set:

```text
ZINCRBY top_dishes:blr:2026-10-10 1 "r_77:biryani"   -> "1843"  (O(log n))
ZINCRBY top_dishes:blr:2026-10-10 1 "r_12:dosa"      -> "1790"
ZREVRANGE top_dishes:blr:2026-10-10 0 2 WITHSCORES
  1) r_77:biryani 1843   2) r_12:dosa 1790   3) r_40:thali 1622
so: 120 orders/s of INCR-style updates, top-N read in O(log n + k), no DB involved
```

**Single-threaded execution**: commands run one at a time (I/O threads aside), so each command is **atomic**: 1,000 concurrent `ZINCRBY`s never lose an update. Multi-step logic uses `MULTI/EXEC` or **Lua**. Roughly 100k+ ops/sec per instance, but one slow command (`KEYS *`, huge `SMEMBERS`, deleting a million-element key) blocks everyone: use `SCAN`, avoid giant values; scale cores by sharding.

> **Black box:** Persistence: **RDB** snapshots (compact, fast restart, lose writes since last snapshot, fork pauses) and **AOF** (logs writes; `everysec` loses ~1 s), or both, or none for a pure cache. **Use when:** you want warm restarts. **Cost:** replication is async, so failover can still lose recent writes; not a durable primary DB.

> **Black box:** **Redis Cluster** splits keys into exactly **16,384 hash slots** (`CRC16(key) mod 16384`), each owned by one primary with replicas; rebalancing moves slots; replica promoted on failure. **Use when:** data or throughput exceeds one node. **Cost:** multi-key ops need same slot (use **hash tags**), fixed buckets not a hash ring. E.g. `menu:r_77` -> slot 10507 (node B of 0–5460 / 5461–10922 / 10923–16383); `{u_1842}:cart` and `{u_1842}:addr` hash only `u_1842`, so both land in slot 8015.

**Memcached**: multi-threaded, strings only, no persistence or replication, client-side sharding. Pick it for a dead-simple string cache; Redis for structures, persistence, replication.

## CDNs

**How it works** · **Edge** servers in **PoPs** near users (routed via DNS or anycast) serve cached copies; misses go to the **origin**.

**Example** · Asha loads `img.quickbite.com/dish/r_77/biryani.a81f.jpg`:

```text
1. DNS/anycast -> Bengaluru edge; edge miss (first request at this PoP)
2. edge -> origin shield -> origin object store (us-east)      ~200 ms
3. response: Cache-Control: public, max-age=31536000, immutable
4. next 10,000 Bengaluru users: edge hit                        ~20 ms each
5. new photo uploaded -> new name biryani.c07e.jpg -> no purge needed
so: the origin served one request; the URL itself carries the version
```

- **Pull CDN** (edge fetches on first miss; first user per edge pays origin latency): most sites and APIs. **Push CDN** (you upload ahead; you manage uploads/deletes): large known files (video releases, game patches).
- **Origin shield** / tiered cache: 200 edges missing at once becomes one origin request.
- **Cache key** ~ URL. `?utm_source=sms` and `?utm_source=push` would be two copies of the same image: strip tracking params. `Vary` adds header dimensions (each multiplies copies). Never cache personalized responses (Asha's order history) under a shared key.

> **Black box:** **Cache-Control**: `max-age` (fresh N s), `s-maxage` (CDN only), `public`/`private` (private = browser only), `no-cache` (store but revalidate), `no-store` (never store), `stale-while-revalidate`, `immutable`. **Use when:** always set it from the origin. **Cost:** `no-cache` does not mean "do not cache". E.g. stale menu page -> `If-None-Match: "v42"` -> `304 Not Modified`, no body.

- Invalidation: **purge** via API (seconds to minutes, rate-limited) or, better, **versioned URLs** (`app.3f9a1c.js`) cached forever with `immutable`; only the HTML gets a short TTL or `no-cache`.

## What not to cache, and consistency

**How it works** · A cache makes reads **eventually consistent**, stale up to TTL. Cache for *display*, read the source of truth for *decisions*.

**Example** · The menu page shows "Biryani ₹280, available" from Redis (fine if 30 s old). When Asha taps "Place order", `order-service` re-reads price and availability from the DB inside the transaction; if the dish sold out 20 s ago, the order is rejected instead of charged. Then Asha changes her address: delete `user:u_1842` on write and route her reads to the DB for a few seconds (**read-your-writes**); other users may see the old value briefly.

- Do not cache: values for correctness decisions (balance on withdrawal, stock at reservation, permissions right after revoke), write-heavy rarely-read data, zero-reuse data, secrets in shared caches, personalized responses under shared keys, huge values, things already fast (a buffer-pool PK lookup).

| Data | Staleness OK | Approach |
|---|---|---|
| Description, bio | Minutes | Cache-aside + TTL |
| Like/view counts | Seconds-minutes, approximate | Cache, write-behind counters |
| Price on page | Seconds | Short TTL, authoritative read at checkout |
| User's own saved settings | Must see own write | Delete on write or route that user to DB briefly (**read-your-writes**) |
| Balance during payment | None | No cache in decision path |

> **Common mistake:** Caching around a query that is slow due to a missing index. Fix the query; the cache only hides it until the first miss storm.

## In one minute

- Cache for latency and, more importantly, DB offload; latency ~ `T_cache + (1-h) * T_db`, and 95% -> 99% hits = 5x less DB load (600 -> 120 reads/s at QuickBite's peak).
- Caches stack: browser, CDN, reverse proxy, in-process, Redis, DB buffer pool.
- Default: cache-aside, delete on write, TTL on everything; write-behind is fast but can lose data.
- Delete beats update; the slow-reader race (read old, writer deletes, late set) remains, fixed by TTL, double delete, versioned sets, leases or CDC.
- LRU = hash map + doubly linked list, O(1).
- Stampede -> single flight/locks/early expiry; penetration -> negative cache/Bloom; avalanche -> jitter; hot key -> local cache/splitting.
- Redis: typed values, single-threaded atomic commands, 16,384 slots; CDNs: versioned URLs over purges.
- Never cache in a correctness-critical decision path or put personal data under shared keys.

## Go deeper

Full chapter 08: sections 08.5 (invalidation races), 08.7 (stampede, penetration, avalanche, hot keys), 08.8 (Redis), 08.9 (CDNs).
