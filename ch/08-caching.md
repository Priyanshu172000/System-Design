# 08 — Caching

A cache keeps a copy of data somewhere faster or closer than its real home, so most reads skip
the slow trip. Almost every real system has several layers of caching, and almost every design
conversation touches on it: where to cache, how long to keep data, how to keep it fresh, and what
breaks when the cache misbehaves. This chapter gives you the vocabulary and the failure modes.

## 08.1 Why cache at all

### The pain

Suppose a product page needs the product row, its price, and its review summary. Each lookup goes
to a relational database (chapter 05). Under light load a query takes roughly 1–5 ms. Then
traffic grows:

- The same popular products are read thousands of times per second.
- Every read runs the same query, parses the same SQL, touches the same pages on disk or in
  memory, and returns the same answer.
- The database CPU climbs, queries queue up, and latency for *everyone* gets worse, including
  the writes that actually need the database.

Most of that work is wasted: the answer did not change between the first read and the
ten-thousandth.

### The idea

Store the answer once in a fast place (usually memory), and serve repeated reads from there.
Rough latency numbers (chapter 04 has the full table):

| Where the data is | Rough time to get it |
|---|---|
| Value already in the process's memory (a hash map) | ~100 ns |
| Redis / Memcached in the same data center (network round trip) | ~0.2–1 ms |
| Database query, data in DB memory | ~1–5 ms |
| Database query that hits disk or does real work (joins, sorts) | ~5–100+ ms |
| Cross-continent request to an origin server | ~100–200 ms |

A cache gives you two separate wins:

1. **Lower latency** — a read served from memory is faster than a read that does real work.
2. **Offload** — every read the cache serves is a read the database does not do. This is often
   the bigger win: a database that was at 90% CPU can drop to 20%, which also makes writes
   faster and gives you headroom.

### Hit ratio and effective latency

A **cache hit** is a read the cache could answer. A **cache miss** is a read it could not, so the
request fell through to the slower source. The **hit ratio** `h` is hits / (hits + misses).

For the common pattern where you check the cache first and go to the database on a miss:

```text
effective latency = h * T_cache + (1 - h) * (T_cache + T_db)
                  = T_cache + (1 - h) * T_db
```

Worked example: `T_cache = 0.5 ms`, `T_db = 10 ms`.

| Hit ratio h | Effective latency | DB load (fraction of reads that reach DB) |
|---|---|---|
| 0% | 10.5 ms | 100% |
| 80% | 0.5 + 0.2 × 10 = 2.5 ms | 20% |
| 95% | 0.5 + 0.05 × 10 = 1.0 ms | 5% |
| 99% | 0.5 + 0.01 × 10 = 0.6 ms | 1% |

Two lessons hide in this table:

- Going from 95% to 99% looks small but cuts database load by **5x** (5% → 1%). The miss rate,
  not the hit rate, is what the database feels.
- If the hit ratio drops suddenly (cache restart, a deploy that changes key names), the database
  load can jump 5–20x in seconds. A database sized for the cached load will fall over. This is
  why "the cache is just an optimization" is false at scale: it becomes load-bearing.

> **Say it like this:** "The cache buys us latency, but the bigger win is offload: at a 99% hit
> ratio the database only sees 1% of reads. That also means the cache is load-bearing, so we need
> a plan for a cold or failed cache."

### What makes data cacheable

- **Read-heavy**: read many times per write (product pages, profiles, config).
- **Skewed access**: a small set of keys gets most traffic (the "80/20" shape). Caching the hot
  20% captures most reads with modest memory.
- **Tolerates slight staleness**: a few seconds of old data is acceptable.
- **Expensive to compute**: an aggregation or a rendered page that took 200 ms to build.

## 08.2 Where caches live

Caches exist at every layer between the user and the disk. A request may pass through several.

```text
  User's device
  +-------------------+
  | Browser cache     |  HTTP responses, images, JS/CSS (controlled by Cache-Control)
  +---------+---------+
            |
            v
  +-------------------+
  | CDN edge          |  copies of static files / cacheable API responses near the user
  +---------+---------+
            |
            v
  +-------------------+
  | Reverse proxy     |  e.g. Nginx/Varnish in front of app servers, caches whole responses
  +---------+---------+
            |
            v
  +-------------------+
  | App server        |
  |  in-process cache |  hash map / LRU inside the service's memory (fastest, per instance)
  +---------+---------+
            |
            v
  +-------------------+
  | Distributed cache |  Redis / Memcached cluster shared by all app servers
  +---------+---------+
            |
            v
  +-------------------+
  | Database          |
  |  buffer pool      |  DB keeps hot pages in RAM so it rarely touches disk
  +---------+---------+
            |
            v
          Disk
```

| Layer | What it stores | Gain | Pay / limits |
|---|---|---|---|
| Browser | HTTP responses | Zero network for repeat visits | You cannot purge a user's browser; rely on expiry and versioned URLs |
| CDN | Static files, some API responses | Short distance to user, absorbs huge traffic | Invalidation is slow-ish and costs money; only for shareable content |
| Reverse proxy | Full HTTP responses | Protects app servers from repeated identical requests | Coarse-grained; personalized pages hard to cache |
| In-process | Objects in app memory | ~100 ns, no network | Each instance has its own copy → inconsistency across instances; limited by heap; lost on restart |
| Distributed cache | Key-value data | Shared by all instances, large, ~1 ms | Extra network hop, another system to run, can fail |
| DB buffer pool | Disk pages | Automatic, transparent | You do not control it much; still pays query parsing and execution cost |

An **in-process cache** (also called a **local cache**) lives in the memory of a single server
process. A **distributed cache** is a separate cluster of servers (Redis, Memcached) that every
app server talks to over the network.

> **Common mistake:** Adding an in-process cache to a service with 50 instances and assuming
> invalidation is easy. Each instance has its own copy; an update has to reach all 50, or you
> accept that they each serve old data until their TTL expires.

## 08.3 Caching patterns

A **caching pattern** answers two questions: who loads data into the cache on a read, and what
happens to the cache on a write.

### Cache-aside (lazy loading)

The application code talks to both the cache and the database. The cache does not know the
database exists. This is the most common pattern.

```text
READ                                         WRITE
 App ---- GET key ----> Cache                 App ---- UPDATE ----> DB
  |   <--- hit: value --                       |
  |   <--- miss --------                       +---- DELETE key ---> Cache
  |
  +---- SELECT ---------> DB
  |   <--- row ---------
  +---- SET key, TTL ---> Cache
  |
  return value
```

```text
function get_product(id):
    v = cache.get("product:" + id)
    if v != null: return v
    v = db.query("SELECT ... WHERE id = ?", id)
    cache.set("product:" + id, v, ttl = 300s)
    return v

function update_product(id, fields):
    db.update(id, fields)
    cache.delete("product:" + id)     # next read reloads fresh data
```

### Read-through

Same read flow, but the cache library or service does the loading. The app only talks to the
cache; on a miss the cache calls a loader function (or the database) itself.

```text
 App ---- GET key ----> Cache ---- miss: load ----> DB
     <--- value -------       <--- row ------------
```

The logic is identical to cache-aside; the difference is *who owns the code*. It is common with
in-process caching libraries that take a "loader" callback.

### Write-through

Every write goes to the cache and, synchronously, to the database. The write returns only after
both succeed.

```text
 App ---- WRITE ----> Cache ---- WRITE (sync) ----> DB
     <--- ok -------------------- ok --------------
```

### Write-behind (write-back)

The write goes to the cache and returns immediately. The cache flushes changes to the database
later, often in batches.

```text
 App ---- WRITE ----> Cache   (ack immediately)
                        |
                        +--- later, batched ---> DB
```

### Write-around

Writes go straight to the database and skip the cache. The cache only fills on reads. Usually
paired with cache-aside reads.

```text
 App ---- WRITE ----> DB          (cache untouched, or the key is deleted)
 App ---- READ  ----> Cache --miss--> DB --> fill cache
```

### Comparing the patterns

| Pattern | Read path | Write path | Gain | Pay | Use when |
|---|---|---|---|---|---|
| Cache-aside | App checks cache, loads DB on miss | App writes DB, deletes key | Simple, cache failure does not break reads (fall back to DB), only hot data cached | First read of each key is slow; stale-data races (08.5) | Default choice for read-heavy data |
| Read-through | Cache loads on miss | Usually paired with write-through or invalidation | Cleaner app code | Cache layer must know how to load data | In-process caches with loader functions |
| Write-through | Cache always warm | Write to cache + DB synchronously | Reads after writes see fresh data; cache rarely stale | Every write pays two writes; caches data that may never be read | Data read soon after being written |
| Write-behind | Cache serves reads | Cache acks, DB updated later | Very fast writes; batches absorb write bursts | **Data loss** if the cache dies before flushing; DB is behind; complex | Counters, metrics, where losing a little is acceptable |
| Write-around | Cache-aside reads | DB only | Cache not polluted with write-once data | Recently written data misses on first read | Logs, bulk imports, data rarely re-read |

> **Say it like this:** "I'd use cache-aside with delete-on-write and a TTL as a safety net. If
> the cache is down, reads just fall through to the database, so the cache is an optimization,
> not a source of truth."

> **Common mistake:** Choosing write-behind for money or orders because "writes are faster". The
> cache becomes the only copy of acknowledged data until the flush. If that node dies, those
> writes are gone, and the user was told they succeeded.

## 08.4 TTL (time to live)

A **TTL** is an expiry time attached to a cache entry. After it passes, the entry is treated as
missing and the next read reloads it.

Why every cache entry should have a TTL:

- It bounds staleness. Even if your invalidation code has a bug, data is at most TTL old.
- It cleans up keys that are no longer read, freeing memory.

Choosing a TTL is a trade-off:

| Short TTL (seconds) | Long TTL (hours) |
|---|---|
| Fresher data | Higher hit ratio, less DB load |
| More misses, more DB load | Staler data if invalidation misses something |
| Good for prices, stock counts, feeds | Good for config, static reference data, rendered content with versioned keys |

A useful rule: TTL = "how stale can the business tolerate this data being if every other
invalidation mechanism fails?"

## 08.5 Invalidation — why it is hard

**Cache invalidation** means removing or updating cached data when the source changes. It is hard
because you now have two copies of the data (cache and database) and the updates to them are not
atomic. Between any two steps, another request can sneak in.

### Race 1: update the cache after writing the DB

Two writers change the same key. The DB writes happen in order A then B, but the cache sets land
in the opposite order because of network delays.

```text
time ->
Writer A:  DB.set(x=1) ...................... cache.set(x=1)
Writer B:           DB.set(x=2) cache.set(x=2)

Final: DB x=2, cache x=1   -> stale until TTL
```

This is why the usual advice is **delete, do not update**: deleting is idempotent and order does
not matter. Two deletes in any order leave the same result (no entry), and the next read loads
the current value.

### Race 2: delete-after-write still has a gap

Even with delete-on-write, cache-aside has a rarer race between a slow reader and a writer:

```text
time ->
Reader R:  cache miss -> DB read (gets x=1) .................... cache.set(x=1)
Writer W:                              DB.set(x=2) -> cache.delete(x)

Final: DB x=2, cache x=1   -> stale until TTL
```

The reader read the old value, the writer updated and deleted, then the reader's late `set`
wrote the old value back. It needs the reader's DB read to start before the write and its cache
set to land after the delete, so it is rare, but at high traffic rare things happen daily.

### Mitigations

| Technique | How it helps | Cost |
|---|---|---|
| TTL on every key | Bounds how long the stale value lives | Does not prevent it |
| Delete instead of update | Removes Race 1 | Next read is a miss |
| Delayed double delete | Delete, then delete again after ~1 s to clear values written by slow readers | Extra work; timing is a guess |
| Versioned values / compare-and-set | Store a version with the value; only `set` if the new version is higher | Needs a version column and atomic set logic (e.g. a Lua script in Redis) |
| CDC-driven invalidation | A process tails the DB's change log (chapter 09, 11) and deletes keys after commit | Extra infrastructure; small delay; but catches every write path, even ones that forget to invalidate |
| Lease / "fill token" | On a miss, the cache gives the reader a token; a delete invalidates the token so the late `set` is rejected | Needs cache support or custom logic |

> **Say it like this:** "Cache-aside has a known race where a slow reader writes an old value
> back after an invalidation. I'd delete on write, put a TTL on everything to bound the damage,
> and if correctness matters more, drive invalidation from the database change stream or use
> versioned sets."

Another reason invalidation is hard: **derived keys**. If a user's name is cached inside "user
profile", "comment thread page 3", and "search results for X", one update must find every key
that embedded it. Prefer caching small, normalized objects keyed by ID over big composed blobs,
or accept TTL-based staleness for the composed ones.

## 08.6 Eviction policies

Memory is finite. When the cache is full and a new entry arrives, an **eviction policy** decides
which existing entry to throw out.

| Policy | Evicts | Good for | Weakness |
|---|---|---|---|
| **LRU** (least recently used) | Entry not accessed for the longest time | General workloads with recency | A one-time scan of many keys flushes out the real hot set |
| **LFU** (least frequently used) | Entry with fewest accesses | Stable popularity (top products) | Old popular items linger; needs counter decay |
| **FIFO** | Oldest inserted | Simple, cheap | Ignores usage completely |
| **TTL-based** | Expired entries first | Data with natural freshness | Not a full policy alone; still need something when nothing has expired |
| **Random** | A random entry | Very cheap, surprisingly OK | Can evict hot entries |

Real systems approximate these. Redis, for example, offers `allkeys-lru`, `allkeys-lfu`,
`volatile-ttl` and others, and implements LRU/LFU by sampling a few keys and evicting the best
candidate among them rather than keeping an exact global order.

### DSA bridge: LRU in O(1)

An LRU cache needs two operations in O(1): look up by key, and find/remove the least recently
used entry. The classic structure is a **hash map + doubly linked list**:

```text
 hash map: key -> pointer to node

 head (most recent)                                  tail (least recent)
   [k7] <-> [k2] <-> [k9] <-> [k4] <-> [k1]
    ^                                    ^
    get(k9): unlink node, move to head   put when full: remove tail (k1), delete from map
```

- `get(key)`: map lookup → node → unlink and move to head. O(1).
- `put(key)`: if present, update and move to head; else insert at head; if over capacity,
  remove the tail node and its map entry. O(1).
- The doubly linked list is needed so you can unlink a node in O(1) given only a pointer to it.

LFU in O(1) uses a map from frequency to a list of keys plus a "min frequency" pointer.

## 08.7 When caches hurt: stampedes, penetration, avalanches, hot keys

### Thundering herd / cache stampede

A very popular key expires. In the next few milliseconds, 5,000 requests all miss, all query the
database for the same row, and all write it back. The database sees a sudden spike for one
expensive query.

```text
       key "home_feed" expires at t=0
  req1 --miss--+
  req2 --miss--+
  req3 --miss--+---->  DB: 5,000 identical expensive queries at once
   ...         |
  req5000 -----+
```

Fixes:

- **Request coalescing (single flight)**: within one process, if a load for key K is already in
  progress, later requests wait for that same result instead of starting their own. Turns N
  loads into 1 per instance.
- **Distributed lock / lease**: the first misser acquires a short lock (e.g. `SET lock:K NX
  PX 3000` in Redis) and rebuilds; others wait briefly and retry the cache, or serve the slightly
  stale value.
- **Serve stale while revalidating**: keep the old value past its soft expiry; one request
  refreshes it in the background while others get the stale copy.
- **Probabilistic early expiration**: each reader, as expiry approaches, has a growing chance of
  deciding to refresh early. Only one or a few readers refresh, spread out before the real expiry.
  A known formula: refresh if `now - delta * beta * ln(rand()) >= expiry`, where `delta` is how
  long a recompute takes and `beta` ≈ 1 tunes eagerness (`ln(rand())` is negative, so the term
  pushes "now" forward by a random amount).

### Cache penetration

Requests ask for keys that do not exist in the database at all (a buggy client, or an attacker
looping over random IDs). Every request misses the cache, misses the DB, and nothing is cached,
so every single one hits the database.

Fixes:

- **Cache negatives**: store a "not found" marker with a short TTL (e.g. 30–60 s).
- **Bloom filter** in front: a compact probabilistic set of all existing IDs. If it says "not
  present", the key definitely does not exist, so reject without touching the DB. If it says
  "maybe present", proceed. (A Bloom filter has false positives but no false negatives.)
- Validate inputs (ID format, ranges) and rate limit (chapter 12).

### Cache avalanche

Many keys expire at the same moment, often because they were all loaded together (a warm-up
job, a deploy) with the same TTL. Or the whole cache cluster restarts. The database gets a wall
of misses.

Fixes:

- **Jittered TTLs**: TTL = base ± random(0..10%). Expiries spread out over time.
- Warm the cache gradually before sending full traffic; ramp traffic during deploys.
- Make the cache cluster highly available (replicas) so one node loss is not a total loss.
- Circuit breakers / load shedding so the DB degrades instead of collapsing (chapter 13).

### Hot keys

One key gets a huge share of traffic (a celebrity's profile, a flash-sale item). In a sharded
cache, a key lives on exactly one node, so that node saturates its CPU or network while the rest
sit idle.

Fixes:

- **Local (in-process) cache** for the hottest keys with a short TTL (say 1–5 s). Each app
  server answers from its own memory; the cache node sees a tiny fraction.
- **Key replication / splitting**: store copies under `item:42#0` ... `item:42#9` on different
  nodes; readers pick a random suffix. Writes must update all copies.
- Read from replicas of the cache shard.
- Detect hot keys with sampling/metrics so you can react.

| Problem | Cause | Main fixes |
|---|---|---|
| Stampede | One hot key expires | Coalescing, lock, stale-while-revalidate, early expiry |
| Penetration | Keys that do not exist | Cache negatives, Bloom filter, validation |
| Avalanche | Many keys expire / cache dies | Jittered TTL, warm-up, HA cache |
| Hot key | One key too popular for one node | Local cache, key replication |

> **Say it like this:** "For the hot item I'd add a short-TTL local cache in each app server so
> the Redis shard owning that key isn't the bottleneck, plus single-flight loading so an expiry
> doesn't stampede the database."

## 08.8 Redis overview

**Redis** is an in-memory data store that is the most common choice for a distributed cache. It
is more than a key-value cache: values have types with server-side operations.

### Data structures and what they are for

| Type | What it is | Typical use |
|---|---|---|
| String | Bytes (text, JSON, a number) | Cached objects, counters (`INCR`), flags, simple locks (`SET key val NX PX ttl`) |
| Hash | Map of field → value under one key | An object's fields (`user:42` → name, email); update one field without rewriting all |
| List | Ordered list, push/pop at ends | Simple queues, recent-items lists (`LPUSH` + `LTRIM` to keep last 100) |
| Set | Unordered unique members | Tags, "who liked this", set intersection ("mutual friends") |
| Sorted set | Unique members each with a numeric score, kept ordered by score | **Leaderboards** (`ZINCRBY`, `ZREVRANGE 0 9` for top 10), priority queues, time-ordered indexes (score = timestamp), sliding-window rate limiting |
| Stream | Append-only log of entries with IDs, consumer groups | Lightweight event log / message queue (chapter 11 for the real thing) |

Others exist (bitmaps, HyperLogLog for approximate distinct counts, geospatial indexes).

DSA bridge: a sorted set is implemented with a hash map (member → score) plus a **skip list**
ordered by score, which gives O(log n) insert/update and O(log n + k) range reads — like a
balanced BST you can walk in order.

### Single-threaded command execution

Redis executes commands one at a time on a single main thread (newer versions can use extra
threads for network I/O, but command execution is still serialized). Consequences:

- Each command is **atomic**: `INCR` on a counter from 1,000 clients never loses an update.
- Multi-step logic can be made atomic with `MULTI/EXEC` transactions or a **Lua script**, which
  runs without interleaving.
- No lock overhead, so simple commands are very fast (on the order of 100k+ ops/sec per instance,
  roughly).
- But one slow command (`KEYS *`, a huge `SMEMBERS`, deleting a million-element key) blocks
  *everyone*. Use `SCAN` for iteration and avoid giant values.
- To use more cores, run more Redis instances (sharding).

### Persistence

Redis keeps data in RAM, but can persist it:

| Mode | How it works | Gain | Pay |
|---|---|---|---|
| **RDB** (snapshot) | Periodically forks and writes a point-in-time snapshot file | Compact, fast restarts, good backups | Lose writes since the last snapshot on crash; fork can pause on large datasets |
| **AOF** (append-only file) | Logs every write command; fsync policy `always`, `everysec` (common), or `no` | With `everysec`, lose at most ~1 s of writes | Larger file, needs periodic rewrite/compaction |
| Both | Combine | Durable and fast restarts | More I/O |
| None | Pure cache | Fastest | Restart = empty cache |

For a pure cache, persistence is optional (the DB is the source of truth). Even with AOF, Redis
replication is asynchronous, so a failover can lose recent writes. Do not treat Redis as a
durable primary database unless you understand that.

### Redis Cluster and hash slots

**Redis Cluster** shards data across several primary nodes, each with replicas.

```text
  key "user:42"  -> CRC16(key) mod 16384 = slot 15880  (-> Primary C)

  slots 0..5460      slots 5461..10922     slots 10923..16383
  +-----------+      +-----------+         +-----------+
  | Primary A |      | Primary B |         | Primary C |
  +-----+-----+      +-----+-----+         +-----+-----+
        |                  |                     |
   Replica A'         Replica B'            Replica C'
```

- There are exactly **16,384 hash slots**. Each key maps to a slot; each slot is owned by one
  primary. Moving slots between nodes is how the cluster rebalances.
- This is a fixed-number-of-buckets scheme rather than a hash ring; compare with consistent
  hashing in chapter 07.
- Multi-key operations (`MGET`, transactions) only work if all keys are in the same slot. **Hash
  tags** force that: in `{user:42}:cart` and `{user:42}:profile`, only `user:42` is hashed.
- If a primary fails, a replica is promoted.

**Memcached** is the other classic option: multi-threaded, strings only, no persistence, no
built-in replication, with sharding done by the client. Choose it for a dead-simple, very fast
string cache; choose Redis when you want data structures, persistence, or replication.

## 08.9 CDNs in depth

A **CDN (content delivery network)** is a large set of servers (**edge** servers, grouped into
**PoPs** — points of presence) spread across many cities. Users are routed to a nearby edge
(via DNS or anycast, chapter 01). The edge serves cached copies of content; the real source is
the **origin** (your servers or object storage).

```text
 User (Mumbai) --20 ms--> Edge PoP (Mumbai) --hit--> response
                               |
                               | miss
                               v
                        Origin (us-east) ~200 ms away
                               |
                     edge stores copy, serves next users locally
```

### Pull vs push

| | Pull CDN | Push CDN |
|---|---|---|
| How content gets to edge | Edge fetches from origin on first request (a miss), then caches | You upload content to the CDN ahead of time |
| Gain | Zero setup per file; only popular content occupies edge space | No first-request miss; full control of what is there |
| Pay | First user per edge pays the origin latency; origin must handle misses | You manage uploads and deletes; storage for everything |
| Use when | Most websites and APIs | Large, known files (video releases, game patches) |

Many CDNs place a middle layer (**origin shield** / tiered cache) between edges and origin so
that 200 edges missing at once become one request to origin.

### Cache keys

The **cache key** decides which requests share a cached response. By default it is roughly the
URL (host + path + query string). Pitfalls:

- Tracking query params (`?utm_source=...`) create many keys for the same content → low hit
  ratio. Configure the CDN to ignore them.
- Responses that vary by header (language, device) need the header in the key (the `Vary`
  response header tells caches this), but each extra dimension multiplies the number of copies.
- Never let a personalized response (with a user's data) be cached under a shared key. That leaks
  one user's data to another.

### Cache-Control headers

The origin tells browsers and CDNs what to do with HTTP headers.

```http
Cache-Control: public, max-age=31536000, immutable
```

| Directive | Meaning |
|---|---|
| `max-age=N` | Fresh for N seconds (browser and shared caches) |
| `s-maxage=N` | Overrides max-age for shared caches (CDN, proxies) only |
| `public` | Any cache may store it |
| `private` | Only the user's browser may store it (not the CDN) — use for personalized data |
| `no-cache` | May store, but must **revalidate** with the origin before each use (not "do not cache") |
| `no-store` | Must not store at all — use for sensitive data |
| `stale-while-revalidate=N` | May serve stale for N s while fetching a fresh copy in the background |
| `immutable` | The content at this URL will never change |

**Revalidation**: the response carries an `ETag` (a version fingerprint) or `Last-Modified`.
When the cached copy is stale, the cache asks `If-None-Match: "<etag>"`; if unchanged, the
origin answers `304 Not Modified` with no body, which is cheap.

### Invalidation at the CDN

- **Purge**: call the CDN's API to remove a URL or a tag/prefix from all edges. It takes from
  seconds to minutes to propagate, depending on the provider, and may be rate-limited.
- **Versioned URLs (cache busting)**: put a content hash in the file name, e.g.
  `app.3f9a1c.js`. A new deploy produces a new URL, so there is nothing to invalidate. Cache
  these forever with `immutable`. The HTML that references them gets a short TTL or `no-cache`.

> **Say it like this:** "Static assets get content-hashed file names and a one-year immutable
> cache, so deploys never need a purge. The HTML gets a short TTL, and anything personalized is
> `private` so the CDN never stores it."

## 08.10 What not to cache

- **Data that must be exactly current for correctness**: account balances when deciding whether
  to allow a withdrawal, inventory at the moment of reserving stock, permission checks right
  after a revoke. Read these from the source of truth (you can still cache them for *display*).
- **Write-heavy data read rarely**: you pay to fill and invalidate, and rarely get a hit.
- **Data with near-zero reuse**: unique per-request results, long-tail keys read once.
- **Secrets and sensitive personal data** in shared caches without encryption and access control.
- **Personalized responses in shared caches** (CDN, reverse proxy) under a non-personal key.
- **Huge values** that evict hundreds of useful small entries (and block single-threaded Redis).
- Things that are already fast: a primary-key lookup that the DB serves from its buffer pool in
  well under a millisecond may not need a separate cache tier at modest load.

> **Common mistake:** Adding a cache to fix a slow query that is slow because of a missing index.
> Fix the query first. A cache hides the problem until the first miss storm.

## 08.11 Consistency expectations

Once you add a cache, reads are **eventually consistent** with the database: they may be stale
for up to the TTL (or until invalidation lands). Be explicit about what the product can
tolerate:

| Data | Acceptable staleness | Approach |
|---|---|---|
| Product description, profile bio | Minutes | Cache-aside + TTL |
| Like counts, view counts | Seconds to minutes, approximate is fine | Cache, write-behind counters |
| Price shown on page | Seconds; re-check at checkout | Short TTL, authoritative read at purchase |
| User's own just-saved settings | Must see own write | Delete on write, or read from DB for that user briefly ("read-your-writes", chapter 10) |
| Balance during payment | None | No cache in the decision path |

A useful trick for **read-your-writes**: after a user updates something, route that user's next
reads (for a few seconds) to the database, or update the cache synchronously in that request
before returning. Other users can tolerate seeing the old value briefly.

In short: the cache makes the read path eventually consistent, bounded by the TTL. That is fine
for display, but a checkout path reads price and stock from the database directly.

## 08.12 Recap

- A cache trades freshness for latency and, more importantly, database offload.
- Effective latency ≈ `T_cache + (1 - h) * T_db`; the miss rate is what the database feels, so
  95% → 99% hit ratio means 5x less DB load.
- Caches live at many layers: browser, CDN, reverse proxy, in-process, distributed (Redis),
  DB buffer pool.
- Cache-aside with delete-on-write and a TTL is the default; write-through keeps the cache warm;
  write-behind is fast but can lose data.
- Invalidation is hard because cache and DB updates are not atomic; delete instead of update,
  always set a TTL, and consider CDC or versioned writes for stricter needs.
- LRU = hash map + doubly linked list, O(1) get/put.
- Stampede → coalescing/locks/early expiry; penetration → negative caching/Bloom filter;
  avalanche → jittered TTLs; hot key → local cache/key replication.
- Redis: typed values (sorted sets for leaderboards), single-threaded atomic commands,
  RDB/AOF persistence, 16,384 hash slots in cluster mode.
- CDNs: pull vs push, cache keys, Cache-Control, versioned URLs instead of purges.
- Do not cache values needed for correctness-critical decisions; do not put personal data under
  shared keys.

## 08.13 Check yourself

1. Your cache has a 90% hit ratio, `T_cache = 1 ms`, `T_db = 20 ms`. What is the effective
   latency, and what happens to DB load if the hit ratio rises to 98%?

<details><summary>Answer</summary>

Effective latency = 1 + 0.10 × 20 = 3 ms. At 98%: 1 + 0.02 × 20 = 1.4 ms. DB load goes from 10%
of reads to 2% of reads, a 5x reduction.

</details>

2. Why is "delete the key on write" preferred over "set the new value on write" in cache-aside?

<details><summary>Answer</summary>

Two concurrent writers can apply their DB writes in one order and their cache sets in the
opposite order, leaving the cache with the older value. Deletes are idempotent and
order-independent: whatever order they land in, the key is gone and the next read loads the
current value from the database.

</details>

3. Describe the remaining race in cache-aside even with delete-on-write, and two ways to reduce
   it.

<details><summary>Answer</summary>

A reader misses, reads the old value from the DB, then stalls. A writer updates the DB and deletes
the key. The reader then sets the old value into the cache, which stays stale until the TTL.
Mitigations: a TTL to bound it, delayed double delete, versioned compare-and-set writes to the
cache, lease/fill tokens that the delete invalidates, or CDC-driven invalidation.

</details>

4. A key for a viral post expires and the database spikes. What is this called and how do you fix
   it?

<details><summary>Answer</summary>

A cache stampede (thundering herd). Fixes: request coalescing so only one load per key runs, a
short distributed lock so only one request rebuilds, serving the stale value while one request
refreshes it, or probabilistic early expiration so a single reader refreshes before the real
expiry.

</details>

5. Attackers request random product IDs that do not exist and the database is overloaded even
   though you have a cache. Why, and what helps?

<details><summary>Answer</summary>

Cache penetration: non-existent keys are never cached, so every request reaches the DB. Cache
"not found" results with a short TTL, put a Bloom filter of valid IDs in front (a "no" is
definite), validate ID formats, and rate limit.

</details>

6. Which Redis data structure would you use for a top-100 game leaderboard and why?

<details><summary>Answer</summary>

A sorted set: member = player ID, score = points. `ZINCRBY` updates a score in O(log n), and
`ZREVRANGE key 0 99 WITHSCORES` reads the top 100 in order. `ZREVRANK` gives a player's rank.

</details>

7. What is the difference between `Cache-Control: no-cache` and `no-store`?

<details><summary>Answer</summary>

`no-cache` allows storing the response but requires revalidation with the origin (e.g. via ETag
and a 304) before each use. `no-store` forbids storing it at all; use it for sensitive data.

</details>

8. Why do content-hashed file names (like `app.3f9a1c.js`) make CDN invalidation unnecessary?

<details><summary>Answer</summary>

Each new version of the file gets a new URL, so the old cached copies are simply never requested
again. The files can be cached forever with `immutable`; only the small HTML page that references
them needs a short TTL.

</details>
