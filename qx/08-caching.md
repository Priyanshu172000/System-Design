# 08 — Caching: quick notes

A **cache** keeps a copy of a popular answer in a fast place (usually memory) so you don't redo
the work. It makes reads faster and, more importantly, takes load off the database.

## 1. Why cache: hit ratio

- **Hit:** the answer was in the cache. **Miss:** it wasn't, so you go to the database.
- **Hit ratio** = hits / all reads. Only misses reach the database.
- *e.g.* ~12,000 menu reads/s at peak, cache 0.5 ms, database 10 ms:

| Hit ratio | Average time | Reads reaching the DB |
|---|---|---|
| 0% | 10.5 ms | 12,000/s |
| 95% | 1.0 ms | 600/s |
| 99% | 0.6 ms | 120/s |

- Going from 95% to 99% saves just 0.4 ms but cuts DB load **5x**.
- The danger: if Redis restarts at 8 pm, the DB built for 120/s suddenly gets 12,000/s. At
  scale the cache is not "just an optimization".
- Good to cache: read often, a few items get most reads, a little staleness is OK, costly to
  compute. *e.g.* menus.

## 2. Where caches live

A request passes several layers. Each is faster than the one below, and each is one more
possibly-stale copy.

| Layer | QuickBite example | Catch |
|---|---|---|
| **Browser** | Dish photos, no network on repeat | You can't clear it remotely |
| **CDN** (servers near users) | Photo from a Bengaluru server, ~20 ms | Clearing it is slow |
| **In-process** (app memory) | Menu in one restaurant-service instance, ~100 ns | 50 instances = 50 copies to update |
| **Distributed** (Redis) | `GET menu:r_77`, ~0.5 ms, shared | Extra hop; can fail |
| **DB buffer pool** | Postgres pages in memory, ~1–5 ms | Little control |

## 3. Caching patterns

```text
read:  v = cache.get(k); if miss: v = db.query(); cache.set(k, v, ttl=300s)
write: db.update(...); cache.delete(k)        # next read reloads
```

- **Cache-aside** (the default): the app checks the cache, loads from the DB on a miss, and
  deletes the key on write. *e.g.* biryani ₹250 → ₹280: DB updated, `DEL menu:r_77`, next read
  loads 280.
- **Read-through:** the same, but the cache library loads from the DB itself.
- **Write-through:** write cache and DB together before saying OK. Reads stay fresh; every
  write costs two writes.
- **Write-behind:** write the cache, say OK, write the DB later in a batch. Very fast, but data
  is lost if the cache dies first. *e.g.* fine for view counts, **never** for `o_90311`.
- **Write-around:** write only the DB. *e.g.* logs and bulk imports don't fill the cache.

## 4. TTL: expiry time

- **TTL** (time to live): the entry deletes itself after this time. Put one on **every** key.
- It limits damage when invalidation has a bug. *e.g.* code forgets to delete `menu:r_77`;
  with TTL 300 s, Asha sees the old ₹250 for at most 5 minutes.
- Short TTL is fresher but adds DB load. *e.g.* 50,000 menus: TTL 300 s ≈ 167 reloads/s, TTL
  30 s ≈ 1,670/s.
- Rule: TTL = how stale the business can stand if everything else fails.

## 5. Invalidation races

Cache and DB are two copies changed one after the other, so another request can slip in
between.

- **Delete, don't update.** Two price edits (260, then 280) whose cache updates arrive out of
  order leave 260 cached. Two deletes in any order leave nothing, and the next read loads 280.
- **Slow reader race:** even delete has a gap.

```text
R: miss, reads price 250 from DB
W: updates DB to 280, deletes key (nothing there yet)
R: (slow) writes 250 into cache  -> stale until TTL
```

- Fixes: TTL (limits it), delete again after ~1 s, write only if the version is newer, or
  delete from the DB's change log (**CDC**, catches every write path).
- **Derived keys:** if the restaurant name is copied into many keys, a rename must find them
  all. Prefer small objects keyed by ID.

## 6. Eviction: what to throw out when full

- **LRU** (least recently used) evicts what was touched longest ago. **LFU** (least
  frequently used) evicts the least-used.
- **LRU in O(1):** hash map (key → node) + doubly linked list (newest at head, victim at
  tail). *e.g.* capacity 3 holding r_1, r_77, r_2 (r_2 oldest); `put r_9` evicts r_2.
- LRU weakness: one big scan flushes the hot items. *e.g.* a nightly job reading all 50,000
  menus once. LFU keeps the hot ones.
- Redis approximates LRU/LFU by sampling a few keys.

## 7. When caches hurt

| Problem | QuickBite example | Fix |
|---|---|---|
| **Stampede** (many rebuild one expired key) | `top_restaurants:blr` expires; 5,000 requests run the 200 ms query | **Single flight** (one load per server), a lock so 1 rebuilds, or serve stale while 1 refreshes |
| **Penetration** (keys that don't exist) | Scraper asks for fake `r_9384721`; every request hits the DB | Cache "not found" for 60 s; **Bloom filter** of real ids |
| **Avalanche** (many keys expire together) | All 50,000 menus loaded at 7:55 pm with TTL 1 h expire at 8:55 pm | **Jitter:** TTL ± 10%, ~69 reloads/s instead of 50,000 at once |
| **Hot key** (one key overloads one shard) | Promo: 40,000 reads/s on `menu:r_77` | 1–5 s local cache per server; split into `menu:r_77#0..#9` |

## 8. Redis and Memcached

- **Redis:** in-memory store with value types: string, hash, list, set, **sorted set**, stream.
- **Sorted set** (keeps members sorted by score) makes leaderboards easy. *e.g.*
  `ZINCRBY top_dishes:blr 1 "r_77:biryani"`, then `ZREVRANGE ... 0 2` gives the top 3 dishes.
- **Single-threaded:** commands run one at a time, so each is atomic. *e.g.* 1,000 parallel
  increments never lose one.
- One slow command (`KEYS *`) blocks everyone. Use `SCAN` instead.
- **Persistence (black box):** snapshots and/or an append log; can still lose recent writes.
  Not the main store for money.
- **Redis Cluster (black box):** keys split into 16,384 slots spread over nodes. Use **hash
  tags** to keep related keys together. *e.g.* `{u_1842}:cart` and `{u_1842}:addr` share a slot.
- **Memcached:** strings only, no persistence. Pick it for a dead-simple cache.

## 9. CDNs

- A **CDN** has **edge** servers near users; a miss goes back to the **origin** (your server).
  *e.g.* the first Bengaluru user waits ~200 ms; the next 10,000 get ~20 ms.
- **Pull CDN** fetches on the first miss (most sites). **Push CDN**: you upload ahead (big
  video releases).
- **Cache key** is about the URL. Strip tracking params like `?utm_source=sms`, or one image
  is stored many times.
- **Cache-Control** header tells caches what to do: `max-age` (fresh for N s), `private`
  (browser only), `no-store` (never store). Note: `no-cache` means "check first", not "don't
  cache".
- **Versioned URLs** beat purging. *e.g.* new photo = new name `biryani.c07e.jpg`, cached
  forever.

## 10. What not to cache

- Cache for **display**, read the database for **decisions**. *e.g.* the menu shows "₹280,
  available" from Redis; at "Place order", order-service re-checks the DB.
- Don't cache: balances during payment, stock at reservation, personal data under a shared
  key, secrets, data used once.
- **Read-your-writes:** after Asha edits her address, delete `user:u_1842` and read her data
  from the DB for a few seconds.
- A slow query from a missing index needs the index, not a cache.

## Remember these 5

1. The big win is DB offload: at 99% hits the DB sees 1% of reads, so the cache is load-bearing.
2. Default: cache-aside, delete on write, TTL on every key.
3. Write-behind is fast but can lose data; never use it for orders or money.
4. Stampede → single flight; penetration → cache "not found"; avalanche → jitter; hot key → local cache.
5. Cache for display, read the source of truth for decisions.
