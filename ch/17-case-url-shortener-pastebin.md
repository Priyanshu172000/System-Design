# 17 — Case study: URL shortener and Pastebin

The URL shortener is the "hello world" of system design: small enough to finish in one
session, but it touches unique ID generation, read-heavy caching, HTTP redirect semantics,
analytics pipelines and abuse prevention. Pastebin is its close cousin with one big twist: the
payload is large, so it belongs in object storage. We apply the full method from chapter 16.

## 17.1 The problem

A **URL shortener** turns a long URL like

```text
https://www.example.com/articles/2026/10/how-to-design-things?utm_source=newsletter&id=88
```

into a short one like `https://sho.rt/aZ3kQ9x`. Visiting the short URL **redirects** the
browser to the long URL. The short part (`aZ3kQ9x`) is the **short code** (also called the
key or slug).

## 17.2 Step 1 — Functional requirements

Core (in scope):

1. **Shorten:** given a long URL, return a short URL.
2. **Redirect:** visiting the short URL sends the user to the long URL.
3. **Custom alias:** optionally, the user picks the code (`sho.rt/my-launch`).
4. **Expiry:** optionally, links expire after a date.
5. **Analytics:** click counts per link (by day, country, referrer).

Out of scope (parked): user accounts beyond an API key, editing the destination of an existing
link, link previews, QR codes.

## 17.3 Step 2 — Non-functional requirements

- **Read-heavy:** links are created once and clicked many times. Assume a 10:1 read:write
  ratio (many real services are even more skewed).
- **Low redirect latency:** a redirect adds a hop before the real page loads, so p99 should be
  small, say under ~50 ms at the server.
- **High availability for redirects:** if redirects fail, every link ever shared is broken.
  Target 99.99% for the redirect path. Creating links can tolerate slightly lower
  availability.
- **Durability:** a created link must never be lost or point somewhere else.
- **Uniqueness:** two different long URLs must never get the same code.
- **Unpredictability (nice to have):** codes should not be trivially guessable, so people
  cannot enumerate all links.
- **Consistency:** after creation, the link should work right away for the creator
  (read-your-writes). Analytics can lag by minutes (eventual).

## 17.4 Step 3 — Estimation

Assumptions: 100 million new links per day; 10:1 reads; keep links 5 years; ~500 bytes per
record (long URL up to ~2 KB but average a few hundred bytes, plus code, timestamps, owner).

```text
Writes:   100M / day  ≈ 100M / 86,400  ≈ 1,160 /s avg   -> ~3,000 /s peak (x2–3)
Reads:    1B / day    ≈ 11,600 /s avg                    -> ~30,000 /s peak
Records:  100M * 365 * 5 ≈ 182.5 billion links over 5 years
Storage:  182.5B * 500 B ≈ 91 TB (before replication; x3 replicas ≈ 275 TB)
Per day:  100M * 500 B = 50 GB/day
Bandwidth (reads): 11,600/s * ~500 B ≈ 6 MB/s of payload — trivial
Cache:    if ~20% of links get ~80% of clicks, and ~100M distinct links are clicked/day,
          caching 20M hot links * 500 B ≈ 10 GB -> fits in RAM on one node
```

Conclusions:

- ~3,000 writes/sec peak could fit on a single SQL primary, but **91 TB over 5 years does
  not fit comfortably on one machine** → plan for sharding (or a store that partitions
  natively).
- ~30,000 reads/sec at peak → a cache in front is clearly worth it; a 10 GB hot set is cheap.
- **Key space:** we need at least 182.5 billion distinct codes. Next section shows 7
  base62 characters is enough.

### Code length math

**Base62** uses the characters `0–9`, `a–z`, `A–Z` (62 symbols), all URL-safe.

| Length | 62^length | Enough for 182.5B? |
|---|---|---|
| 6 | 62^6 ≈ 56.8 billion | No |
| 7 | 62^7 ≈ 3.52 trillion | Yes (~5% used after 5 years) |
| 8 | 62^8 ≈ 218 trillion | Yes, lots of headroom |

Seven characters gives about 3.5 trillion codes. A sparse space (only ~5% used) also helps
unpredictability: a random guess hits a real link only about 1 time in 20, and much less in
earlier years.

> **Say it like this:** "With base62, seven characters gives about 3.5 trillion codes. We need
> around 180 billion over five years, so seven characters is enough with plenty of headroom."

## 17.5 Step 4 — API

```http
POST /v1/links
Authorization: Bearer <api-key>
Idempotency-Key: 3b1f...                (optional; safe retries)
{ "long_url": "https://www.example.com/...", "custom_alias": "my-launch",
  "expires_at": "2027-01-01T00:00:00Z" }

-> 201 Created
{ "code": "my-launch", "short_url": "https://sho.rt/my-launch",
  "long_url": "...", "expires_at": "..." }
-> 409 Conflict          if custom_alias already taken
-> 400 Bad Request       if long_url invalid / blocked
-> 429 Too Many Requests if over rate limit

GET /{code}
-> 302 Found   Location: https://www.example.com/...   (or 301, see 17.8)
-> 404 Not Found         unknown code
-> 410 Gone              expired or disabled

GET /v1/links/{code}/stats?from=2026-10-01&to=2026-10-07
-> 200 { "total_clicks": 1234, "by_day": [...], "by_country": {...} }

DELETE /v1/links/{code}  -> 204   (owner only; soft-delete)
```

Note the redirect endpoint is on the bare domain (`sho.rt/{code}`), not under `/v1/`, because
the short URL is what users see and share.

## 17.6 Step 5 — Data model and database choice

```text
links
  code          VARCHAR(16)  PRIMARY KEY      -- the short code
  long_url      TEXT         NOT NULL
  owner_id      BIGINT       NULL
  created_at    TIMESTAMP    NOT NULL
  expires_at    TIMESTAMP    NULL
  status        TINYINT      -- active / disabled / deleted

click_events (in the analytics store, not the main DB)
  code, ts, country, referrer, user_agent_class, ...
```

Access patterns:

| # | Pattern | Frequency |
|---|---|---|
| A1 | Get `long_url` by `code` | Very high (every redirect) |
| A2 | Insert new link, fail if `code` exists | Moderate |
| A3 | List links by owner | Low |
| A4 | Aggregate clicks by code and time | Analytics, async |

A1 and A2 are pure key-value operations on `code`. No joins, no multi-row transactions. That
leaves two reasonable choices:

| Option | Pros | Cons |
|---|---|---|
| Sharded relational DB (shard key = `code`) | Familiar, unique constraint per shard, secondary index on owner easy | You operate sharding yourself (chapter 07) |
| Key-value / wide-column store (DynamoDB, Cassandra) | Partitions by key natively, scales storage and throughput horizontally | "Insert only if absent" needs a conditional write (DynamoDB condition expression, Cassandra lightweight transaction, which is slower); owner lookup needs a secondary index or separate table |

Either is defensible. A reasonable answer: "A key-value store partitioned by `code`, because
the main access pattern is a point lookup and we need ~91 TB over time. For A3, a second table
keyed by `owner_id`."

Should we deduplicate — return the same code if the same long URL is shortened twice? It
saves space but requires an index on `long_url` (or its hash), and different users may want
separate links for separate analytics. Common answer: no global dedup; optionally dedup per
owner.

## 17.7 Step 6 (deep dive) — Generating the short code

This is the crux of the problem. Requirements for a code generator: unique, short (7 chars),
fast, works across many app servers, ideally not guessable. Four options.

### Option A — Hash the long URL, take a prefix

Compute a hash (MD5 = 128 bits, SHA-256 = 256 bits) of the long URL, base62-encode it, take
the first 7 characters.

```text
long_url --MD5--> 9e107d9d372bb6826bd81d3542a419d6 (128 bits)
         --base62--> "4Fk2pQx9..." --take 7--> "4Fk2pQx"
```

Problem: 7 base62 characters carry only about 41.7 bits (log2 of 62^7), so we throw away most
of the hash. Different URLs *will* collide on the 7-char prefix. How often? Each new insert
collides with probability roughly (links already stored) / 62^7. With 100 billion links
stored, that is ~100e9 / 3.52e12 ≈ 2.8% per insert — not rare.

Collision handling:

```text
code = prefix7(base62(hash(long_url)))
loop:
    INSERT code -> long_url  IF NOT EXISTS        (conditional write)
    if success: return code
    if existing row has same long_url: return code (dedup for free)
    code = prefix7(base62(hash(long_url + salt_counter++)))   # rehash and retry
```

| Gains | Costs |
|---|---|
| Stateless; any server can compute it | Each insert needs a check-and-insert; retries grow as the table fills |
| Same URL → same code (free dedup, if wanted) | Codes are deterministic: anyone can compute the code for a known URL |
| | Conditional writes are slower on some stores |

A **Bloom filter** (chapter 06) of existing codes can skip most DB checks for codes that are
definitely new, but you still need the conditional insert for correctness.

### Option B — Counter + base62

Keep a global counter. Each new link gets the next integer; base62-encode it.

```text
counter: 1000000000  -> base62 -> "15FTGg"
counter: 1000000001  -> base62 -> "15FTGh"
```

Base62 encoding is just "write the number in base 62":

```text
encode(n):
    s = ""
    do { s = ALPHABET[n % 62] + s; n = n / 62 } while n > 0
    return s
```

No collisions — every integer is unique, so every code is unique. The problem is the counter
itself: a single counter (one DB sequence, one Redis `INCR`) is a single point of failure and
a throughput bottleneck, and it must be durable (if it resets, you re-issue old codes).

Fix: **range allocation**. A coordinator (a DB row, or ZooKeeper/etcd) hands each app server a
block of, say, 100,000 numbers. The server issues codes from its block in memory and asks for
a new block when it runs out.

```text
 [Range coordinator]  next_free = 4,700,000
        |  "give me a block"            (atomic: UPDATE ... SET next = next + 100000)
        +---> server A: [4,500,000 .. 4,599,999]
        +---> server B: [4,600,000 .. 4,699,999]
```

If a server crashes, the unused part of its block is wasted — that is fine (we have trillions).

| Gains | Costs |
|---|---|
| Guaranteed unique, no check-and-insert needed | Sequential → **predictable**: anyone can enumerate links by incrementing |
| Very fast (in-memory increment) | Needs a durable coordinator for ranges |
| Short codes early on | Codes leak volume (competitors can estimate links/day) |

Fixing predictability: apply a **bijective scramble** before encoding — a reversible
permutation of the number space, such as multiplying by a large odd constant modulo 2^k, or a
small format-preserving cipher keyed by a secret. Because it is a bijection, uniqueness is
preserved, but consecutive inputs produce unrelated-looking codes.

### Option C — Pre-generated key service (KGS)

A separate **key generation service** creates random unique codes ahead of time and stores
them in an "unused keys" table. App servers fetch batches of keys and use them.

```text
               offline: generate random 7-char codes, insert into unused_keys (unique)
 +------------------+
 | Key Gen Service  |----- batch of 1,000 keys -----> app server (keeps in memory)
 |  unused_keys     |      (moved to used_keys / marked taken atomically)
 |  used_keys       |
 +------------------+
```

| Gains | Costs |
|---|---|
| Random-looking, unpredictable codes | Another service to run, replicate and make highly available |
| No collision check at request time | Must make sure two servers never get the same key (atomic hand-out) |
| Fast: key is already in memory | Keys held by a crashed server are lost (acceptable) |
| | Storage for the pre-generated pool (7 bytes × billions is fine) |

The KGS itself must not be a single point of failure: run it replicated, and have app servers
hold a buffer of keys so a brief KGS outage does not block link creation.

### Option D — Snowflake-style ID → base62

Use a **Snowflake ID** (chapter 10): a 64-bit integer = 41 bits of millisecond timestamp +
10 bits of machine ID + 12 bits of per-millisecond sequence. Each server generates IDs
locally with no coordination (beyond assigning machine IDs).

Length math: 2^63 ≈ 9.2 × 10^18. 62^10 ≈ 8.4 × 10^17 (too small), 62^11 ≈ 5.2 × 10^19
(enough). So a full 64-bit Snowflake ID needs **11 base62 characters**, not 7.

| Gains | Costs |
|---|---|
| Fully decentralized, very fast, unique | Codes are longer (~11 chars) |
| IDs are roughly time-ordered (nice for storage) | Predictable-ish (timestamp prefix), leaks creation time |
| | Depends on clocks not going backwards (chapter 10) |

You can shorten it with a custom epoch and fewer bits, but then you are effectively doing
range allocation with extra steps.

### Comparison

| Criterion | A. Hash + retry | B. Counter + base62 (ranges) | C. Key gen service | D. Snowflake → base62 |
|---|---|---|---|---|
| Uniqueness | Via check-and-retry | Guaranteed | Guaranteed (if hand-out is atomic) | Guaranteed |
| Length | 7 | 6–7 | 7 | ~11 |
| Coordination | None (but DB check per write) | Range coordinator (rare calls) | KGS (batch calls) | Machine-ID assignment only |
| Predictable? | Deterministic per URL | Yes, unless scrambled | No | Partially |
| Write cost | Conditional write, retries | Plain insert | Plain insert | Plain insert |
| Ops complexity | Low | Low–medium | Medium | Low |

A common interview-strong answer: **counter with range allocation, scrambled by a bijection,
base62-encoded to 7 characters** — or a KGS if unpredictability is a hard requirement.
Either way, still put a uniqueness constraint on `code` in the DB as a safety net.

> **Say it like this:** "I'd allocate numeric ranges to each app server so code generation is
> a local increment with no collisions, run the number through a reversible scramble so codes
> aren't guessable, and base62-encode it to seven characters. The DB's primary key on the code
> stays as a safety net."

## 17.8 Deep dive — Redirect: 301 vs 302

HTTP gives several redirect status codes. The response carries a `Location` header and the
browser follows it.

| Code | Meaning | Browser caching | Method on redirect |
|---|---|---|---|
| **301 Moved Permanently** | The resource has permanently moved | Cacheable by default; browsers may cache it for a long time and skip your server next time | May change POST to GET (historically) |
| **302 Found** | Temporarily at another URL | Not cached unless cache headers say so | May change POST to GET (historically) |
| 307 Temporary Redirect | Like 302 but method must not change | Not cached by default | Preserved |
| 308 Permanent Redirect | Like 301 but method must not change | Cacheable by default | Preserved |

For a shortener, clicks are GETs, so the method nuance rarely matters. The real trade-off is
**caching vs analytics**:

- **301:** after the first visit, the browser may go straight to the long URL without asking
  your server. Less load and faster for repeat visitors — but you **do not see those clicks**,
  and if you later disable a link (malware), cached browsers keep following it.
- **302:** every click reaches your server. You count every click and can disable a link at
  any time. Cost: every click costs you a request.

Since analytics is a requirement and link disabling is important for abuse handling, choose
**302** (with `Cache-Control: private, max-age=0` or similar to discourage caching). Some
services use 301 for links where analytics is off.

> **Say it like this:** "I'd return 302, not 301. A 301 can be cached by the browser so repeat
> clicks never reach us — great for load, but we'd lose analytics and the ability to kill a
> malicious link. Our cache makes the extra requests cheap anyway."

## 17.9 Deep dive — Caching hot links

Click traffic is very skewed: a link shared by a big account can get millions of clicks in an
hour, while most links get almost none. That makes caching very effective.

Redirect read path (cache-aside, chapter 08):

```text
 browser --GET /aZ3kQ9x--> [LB] --> [Redirect service]
                                        | 1. GET code in cache
                                        v
                                   [Redis cluster]  -- hit --> 302 Location: long_url
                                        | miss
                                        v
                                   [Links DB] -- 2. read row
                                        |
                                   3. SET code -> long_url (TTL ~24h) in cache
                                   4. 302 to long_url (or 404 / 410)
```

Details that matter:

- **Eviction:** LRU — hot links stay, cold ones fall out. With ~10 GB of hot data, a small
  Redis cluster (with replicas) is plenty.
- **Negative caching:** cache "code not found" for a short time (e.g. 60 s) so bots scanning
  random codes do not hammer the DB.
- **Invalidation:** link data almost never changes; on disable/delete, delete the cache key
  (and keep TTLs modest so a missed delete self-heals).
- **Expiry:** store `expires_at` in the cached value and check it on every redirect, or set the
  cache TTL to `min(24h, expires_at - now)`.
- **Hot key protection:** a single viral code goes to one cache shard. Add a small
  **in-process cache** (a few seconds TTL) in each redirect server, so a viral link costs
  one cache read per server per few seconds, not one per click.
- **Cache stampede:** when a hot key expires, many requests miss at once. Use request
  coalescing (one fetch per key per server) or refresh-ahead before expiry.
- **Edge caching:** you can also cache 302 responses at a CDN for a short time (seconds to
  minutes) — but then the CDN must report clicks (via logs) for analytics, and disabling a link
  takes up to the CDN TTL.

## 17.10 Deep dive — Analytics pipeline

Counting a click must never slow down the redirect. So the redirect service does not write to
a database synchronously; it emits an event and moves on.

```text
 [Redirect service] --(async, fire-and-forget, batched)--> [Kafka topic: clicks]
                                                                 |
                       +-----------------------------------------+-------------+
                       v                                                       v
            [Stream processor]                                       [Raw archive]
      (aggregate per code per minute,                              (object storage,
       enrich: IP -> country, UA -> device)                         for reprocessing)
                       |
                       v
            [OLAP store / time-series DB]  <---- GET /v1/links/{code}/stats
```

Design choices:

- **Async with a local buffer:** the redirect server batches events and sends to Kafka every
  ~100 ms. If Kafka is unavailable, buffer briefly then drop (and count drops) — analytics
  loss is acceptable; redirect failure is not. That is an explicit priority decision.
- **Partition by `code`** in Kafka so all clicks for a link land in one partition, making
  per-link aggregation simple. (Very viral links can create a hot partition; a common fix is
  to partition by `code + random suffix` and merge downstream.)
- **Pre-aggregation:** store counts per (code, minute) rather than every raw click in the
  serving store; keep raw events in cheap object storage.
- **Unique visitors:** use **HyperLogLog** per code per day for approximate distinct counts
  in a few KB each.
- **Exactness:** at-least-once delivery may double-count a small fraction of clicks after
  failures. For marketing analytics this is usually fine; say so explicitly.

## 17.11 Custom aliases

The user requests `sho.rt/my-launch`.

- **Validation:** allowed characters (e.g. `[A-Za-z0-9_-]`), length limits (e.g. 4–30), a
  reserved-word list (`api`, `admin`, `login`, `help`), and a profanity/brand-impersonation
  check.
- **Uniqueness:** a conditional insert (`INSERT ... IF NOT EXISTS` / unique constraint). If it
  fails → `409 Conflict`. No pre-check-then-insert, because two users could race between
  the check and the insert; the atomic insert is the only safe check.
- **Namespace clash with generated codes:** a custom alias of 7 alphanumeric characters
  could equal a code the generator issues later. Options: make custom aliases occupy a
  different shape (e.g. must contain `-` or be a different length), or have generated codes
  also use a conditional insert and skip taken values. The first is simpler.

## 17.12 Expiry and cleanup

- **On read (lazy):** the redirect checks `expires_at`; if past, return `410 Gone` (or 404).
  This is always correct regardless of whether cleanup has run.
- **Background cleanup:** a periodic job scans for expired links and deletes them or moves
  them to cold storage. Run it at low priority, in small batches, to avoid hurting live
  traffic. With a key-value store that supports native TTL (DynamoDB TTL, Cassandra TTL),
  the store deletes them for you — note native TTL deletion is typically delayed, so you still
  need the read-time check.
- **Code reuse:** do **not** reuse expired codes, or at least not for a long time — old
  printed QR codes, emails and bookmarks would silently point to someone else's new link.
  With 3.5 trillion codes, there is no need to recycle.

## 17.13 Abuse and rate limiting

A shortener is attractive to spammers and phishers (it hides the real destination).

- **Rate limit creation** per API key / per IP (token bucket at the gateway, chapter 12 and
  18), e.g. 100 links/min per key, much lower for anonymous users.
- **URL safety checks** at creation: block known-malicious domains (a reputation list),
  optionally scan asynchronously and disable later.
- **Disable path:** a `status=disabled` flag plus cache invalidation; redirects to disabled
  links show a warning page. This is a key reason to prefer 302 over 301.
- **Enumeration resistance:** non-sequential codes (scrambled counter or KGS) plus rate limits
  on 404s per IP.
- **Redirect-loop prevention:** refuse long URLs that point back to the shortener's own
  domain.

## 17.14 Final architecture

```text
                                  +-----------+
                                  |    DNS    |
                                  +-----------+
                                        |
  +---------+                    +------v-------+
  | Browser |---GET /{code}----->|  CDN / edge  |  (optional short-TTL caching of 302s)
  |  / API  |                    +------+-------+
  | client  |---POST /v1/links---+      |
  +---------+                    v      v
                          +----------------------+
                          | LB + API gateway     |  TLS, auth (API key), rate limiting
                          +----------------------+
                             |                 |
                  +----------v-----+     +-----v-------------+
                  | Create service |     | Redirect service  |  stateless, x N,
                  |  x N           |     |  x N (local LRU)  |  in-process hot cache
                  +----------------+     +-------------------+
                   |  |    |                 |        |
       ranges /    |  |    | write           | read   | click events (async, batched)
       key batches |  |    v                 v        v
          +--------v-+|  +----------------------+  +-----------------+
          | Range    ||  |   Redis cluster      |  |  Kafka: clicks  |
          | coord. / ||  |  (code -> long_url)  |  +--------+--------+
          | KGS      ||  +----------+-----------+           |
          +----------+|             | miss                  v
                      v             v               +------------------+
                 +-------------------------+        | Stream processor |
                 | Links DB (KV store or   |        +--------+---------+
                 | sharded SQL, by code,   |                 |
                 | replicated x3)          |        +--------v---------+     +----------+
                 +-------------------------+        | OLAP / TSDB      |     | Raw log  |
                          ^                         | (stats API reads)|     | (object  |
                          |                         +------------------+     | storage) |
                 +--------+---------+                                        +----------+
                 | Cleanup job      |  expired links, batch, low priority
                 +------------------+
```

Write path: client → gateway (auth, rate limit) → create service → get code from local range
→ conditional insert into links DB → optionally warm the cache → 201.

Read path: browser → (CDN) → gateway → redirect service → in-process cache → Redis → DB on miss
→ 302 → emit click event asynchronously.

## 17.15 Scaling path

Show how the design grows rather than starting at the end state:

| Stage | Scale | Design |
|---|---|---|
| V0 | Thousands of links/day | One app server + one SQL DB with auto-increment ID → base62. Done. |
| V1 | Millions/day | Stateless app servers behind LB; Redis cache; DB replicas for reads; Kafka for clicks. |
| V2 | 100M/day, ~90 TB horizon | Range allocation for codes; shard the links DB by `code` (or move to a KV store); Redis cluster. |
| V3 | Global | Redirect service + cache replicas in multiple regions; links DB replicated across regions (writes in a home region or a multi-region KV store); CDN edge for viral links. |

Why redirects globalize easily: links are written once and almost never change, so
asynchronously replicating them to other regions is safe. The only subtle case is
read-your-writes for the creator right after creation in another region — route the
creator's first redirect to the home region, or fall back to the home region on a local miss.

> **Say it like this:** "Links are immutable after creation, which is the best case for
> replication: we can copy them to every region asynchronously and serve redirects locally.
> The only care point is a miss right after creation, where we fall back to the home region."

## 17.16 Failure handling

| Failure | Effect | Handling |
|---|---|---|
| Redirect server dies | Requests to it fail briefly | LB health checks remove it; stateless, others take over |
| Redis node dies | Cache misses → DB load spike | Redis replica promoted; in-process caches absorb hot links; DB sized for some miss traffic; request coalescing |
| Links DB replica dies | Fewer read replicas | Others serve; replace replica |
| Links DB primary/shard leader dies | Creates fail for that shard; redirects still served from cache and replicas | Failover to a replica; with async replication, a few most-recent links may be lost → which is why the creator should not receive success until the write is durably replicated (sync to at least one replica, or quorum write) |
| Range coordinator / KGS down | New servers cannot get ranges | Servers hold unused ranges/key buffers; coordinator is replicated (consensus store) |
| Kafka down | Click events cannot be sent | Buffer briefly, then drop and count; redirects unaffected |
| Stream processor lag | Stats delayed | Acceptable (eventual); scale consumers |
| Region down | Redirects in that region fail | DNS / global LB fails over to another region that has replicated links |

## 17.17 Pastebin: the delta

**Pastebin** lets users paste text (code, logs) and get a short link to view it. Same skeleton
(short codes, redirects become "views", expiry, rate limits), with these differences.

### Requirements delta

- Content is the payload: up to, say, **10 MB** per paste (limit enforced at the gateway and
  the API), average ~10 KB.
- Pastes are usually immutable once created.
- Expiry is common (e.g. "burn after 1 day", "never").
- Optional: syntax highlighting, private/unlisted pastes, "burn after reading".

### Estimation delta

Assume 10 million new pastes/day, average 10 KB, read:write 5:1.

```text
Writes:   10M / day ≈ 115 /s avg
Storage:  10M * 10 KB = 100 GB/day ≈ 36 TB/year of content
Metadata: 10M * ~300 B = 3 GB/day — tiny by comparison
Reads:    50M / day ≈ 580 /s avg, but each read may move 10 KB -> 10 MB
```

Conclusion: request rates are modest; **storage volume and object size dominate**.

### Design delta: separate content from metadata

```text
 client --POST /v1/pastes (body up to 10 MB)--> [Paste service]
                                                  |  1. generate code (as in 17.7)
                          2. PUT object           |           3. INSERT metadata row
                             pastes/{code}        |              (only after PUT succeeds)
                     +----------------------------+-----------------------+
                     v                                                    v
             [Object storage]                                      [Metadata DB]
          (S3-like, durable, cheap)       code, object_key, size, language, owner, created_at,
                                          expires_at, visibility, burn_after_read

 client --GET /{code}--> [Paste service] --metadata (cached)--> check expiry/visibility
                               |
                               +--> content: CDN in front of object storage
                                     (or a short-lived pre-signed URL for private pastes)
```

Why split:

- **Databases are bad at large blobs:** big rows bloat pages, buffer pools, backups and
  replication. Object storage is built for large immutable objects, very durable, and
  much cheaper per GB (chapter 09).
- **Metadata stays small and fast** to query, cache and index.
- **CDN-friendly:** immutable paste content can be cached at the edge with long TTLs.

Ordering matters: write the object first, then the metadata row. If the service crashes in
between, you get an **orphan object** (harmless, cleaned up by a periodic job comparing objects
to metadata) instead of a metadata row pointing to missing content (a broken paste).

For large uploads, let the client upload directly to object storage using a **pre-signed
URL** (a time-limited URL granting permission to upload one object), so 10 MB bodies do not
pass through your app servers.

Other deltas:

- **Size limits:** enforce at the gateway (reject bodies > 10 MB early) and in the pre-signed
  URL policy (content-length range).
- **Small pastes:** under a few KB you *could* store content inline in the DB to save an extra
  hop; a common approach is a threshold ("inline if < 4 KB"). This adds two code paths, so
  only do it if it measurably helps.
- **Compression:** text compresses well (often several-fold); compress before storing.
- **Expiry:** object storage **lifecycle rules** can delete objects after N days; still check
  `expires_at` on read.
- **Burn after reading:** needs an atomic "read and delete" on the metadata (a conditional
  update `status: active → burned`) so two simultaneous viewers cannot both see it; content
  must not be served from a CDN cache for such pastes.
- **Private pastes:** don't cache publicly; serve through pre-signed URLs with short expiry or
  through the app with auth checks.

## 17.18 Recap

- Clarify the features (shorten, redirect, alias, expiry, analytics) and note the very high
  read:write ratio.
- 100M links/day ≈ 1,200 writes/s, 1B redirects/day ≈ 12k reads/s, ~90 TB over 5 years.
- 62^7 ≈ 3.5 trillion → 7 base62 chars suffice; a 64-bit Snowflake needs 11.
- Code generation: hash+retry (collisions, deterministic), counter with ranges (fast, unique,
  needs scrambling), key generation service (random, extra service), Snowflake (decentralized,
  longer).
- Keep a uniqueness constraint in the DB as a safety net regardless of generator.
- 302 keeps every click visible and lets you disable links; 301 offloads repeat clicks to the
  browser cache but hides them.
- Hot links: Redis cache-aside + in-process cache + negative caching + stampede protection.
- Analytics is async via Kafka; redirect must never wait for or fail because of analytics.
- Custom aliases use an atomic conditional insert, reserved words and a separate shape.
- Expired links: check on read, clean in background, never recycle codes.
- Pastebin: content in object storage (+CDN), metadata in a DB, write object first, size
  limits, pre-signed uploads.

## 17.19 Check yourself

1. Why is 6 base62 characters not enough for our numbers, and how many characters does a
64-bit ID need?

<details><summary>Answer</summary>

62^6 ≈ 56.8 billion, but we need ~182.5 billion codes over 5 years. 62^7 ≈ 3.52 trillion is
enough. A 64-bit (63 usable bits, ≈ 9.2 × 10^18) Snowflake ID needs 11 characters because
62^10 ≈ 8.4 × 10^17 is too small and 62^11 ≈ 5.2 × 10^19 is enough.

</details>

2. What exactly goes wrong if two app servers check "does code X exist?" and then insert?

<details><summary>Answer</summary>

A race: both check, both see "not exists", both insert. Depending on the store, one overwrites
the other (a link silently points to the wrong URL) or one fails late. The fix is an atomic
conditional insert (unique constraint / `IF NOT EXISTS` / condition expression) so the store
itself decides the winner, and the loser retries with a new code (or returns 409 for aliases).

</details>

3. Counter-based codes are unique and fast. What is their main weakness and how do you fix it?

<details><summary>Answer</summary>

They are sequential and therefore predictable: anyone can enumerate links (privacy risk) and
estimate your volume. Fix: pass the counter through a bijective scramble (a reversible
permutation of the number space, e.g. multiply by an odd constant mod 2^k or a small block
cipher with a secret key) before base62 encoding. Bijectivity keeps uniqueness.

</details>

4. Your product manager wants exact click counts and the ability to instantly disable phishing
links. Which redirect code and why?

<details><summary>Answer</summary>

302 (or 307). A 301 is cacheable by default, so browsers may skip the server on later clicks:
those clicks are invisible to analytics, and a disabled link keeps working for anyone with it
cached. 302 makes every click reach the server, where it is counted and checked against the
link's status.

</details>

5. Kafka is down for 5 minutes. What happens to redirects and analytics in your design?

<details><summary>Answer</summary>

Redirects keep working: event publishing is async with a small local buffer, so the redirect
response never waits on Kafka. Events beyond the buffer are dropped (and the drops are
counted/alerted). Analytics shows a small gap or undercount for that window. This is a
deliberate priority: redirect availability over analytics completeness.

</details>

6. A link is shared by a celebrity and gets 200,000 clicks/sec. What breaks and what helps?

<details><summary>Answer</summary>

All requests for that code hit the same Redis shard (a hot key) and the same Kafka partition.
Helps: an in-process cache in each redirect server (seconds TTL) so the hot key costs one Redis
read per server per few seconds; optionally short-TTL CDN caching of the redirect; for
analytics, spread the hot code's events across partitions with a random suffix and merge
counts downstream, or pre-aggregate counts in the redirect server before sending.

</details>

7. In Pastebin, why write the object before the metadata row?

<details><summary>Answer</summary>

If the process crashes between the two writes, writing the object first leaves an orphan object
with no metadata: invisible to users and cleaned up later by a reconciliation job. The other
order could leave a metadata row pointing at a missing object, which users would see as a
broken paste.

</details>

8. Why not store paste content directly in the database?

<details><summary>Answer</summary>

Pastes can be up to 10 MB and total tens of TB per year. Large rows bloat DB pages, caches,
replication streams and backups, and DB storage is far more expensive per GB than object
storage. Object storage is designed for large immutable blobs, is very durable, and pairs with
a CDN for cheap global reads. The DB keeps only small, queryable metadata.

</details>
