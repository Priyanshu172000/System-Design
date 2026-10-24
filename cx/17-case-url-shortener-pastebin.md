# 17 — Case study: URL shortener and Pastebin
*Why it matters:* the "hello world" of system design: small enough to finish, yet it covers unique
ID generation, read-heavy caching, redirect semantics, analytics pipelines and abuse prevention.

## The problem and requirements
**How it works** · A **URL shortener** maps a long URL to `https://sho.rt/aZ3kQ9x`; visiting it
**redirects** the browser. `aZ3kQ9x` is the **short code** (key, slug).

**Example** · one link's life, which is exactly the feature list:
1. A shop's API key creates `sho.rt/aZ3kQ9x` for `https://shop.example.com/sale?utm=email`,
   expiring 2027-01-01 (shorten + expiry); another call asks for `sho.rt/my-launch` (alias).
2. 40,000 people click it from an email; each gets a redirect in a few ms (redirect).
3. The shop opens stats: clicks by day, country, referrer (analytics, minutes behind is fine).
4. On 2027-01-02 a click gets `410 Gone`; the code is never handed to anyone else.

So: 1 write, tens of thousands of reads; never lost or repointed.

| Functional (in scope) | Non-functional |
|---|---|
| **Shorten** a long URL | **Read-heavy**, assume 10:1 (real services often more skewed) |
| **Redirect** short -> long | Low redirect latency, p99 under ~50 ms at the server |
| Optional **custom alias** (`sho.rt/my-launch`) | **99.99%** for redirects (failure breaks every shared link); creation can be a bit lower |
| Optional **expiry** | **Durable**: a link is never lost or repointed |
| **Analytics**: clicks by day, country, referrer | **Unique** codes; ideally **unpredictable** (no enumeration) |
| Parked: accounts beyond an API key, editing destinations, previews, QR codes | Read-your-writes for the creator; analytics may lag minutes |

## Estimation and code length
**How it works** · Assume 100 M new links/day, 10:1 reads, 5-year retention, ~500 B/record.

```text
Writes   100M/day  ~ 1,160/s avg  -> ~3,000/s peak
Reads    1B/day    ~ 11,600/s avg -> ~30,000/s peak
Records  100M * 365 * 5 ~ 182.5 billion
Storage  ~ 91 TB (x3 replicas ~ 275 TB); 50 GB/day
Bandwidth ~ 6 MB/s (trivial)
Cache    20% of links get 80% of clicks; ~100M clicked/day
         -> 20M hot * 500 B ~ 10 GB (fits one node)
```

Conclusions: 3,000 writes/s fits one SQL primary, but **91 TB does not fit one machine**, so
shard or use a natively partitioned store; 30,000 reads/s means a cache is worth it.

**Example** · picking the code length for 182.5 B codes in **base62** (`0–9a–zA–Z`, URL-safe):

| Length | Codes | Verdict |
|---|---|---|
| 6 | 62^6 ~ 56.8 B | Too few: full after ~1.5 years |
| 7 | 62^7 ~ 3.52 trillion | 182.5 B / 3.52 T ~ 5% used: a random guess hits a real link ~1 in 20 |
| 8 | 62^8 ~ 218 trillion | Plenty, one char longer than needed |

So: 7 characters.

## API and data model
**How it works** · Endpoints:

```http
POST /v1/links  (Bearer api-key, optional Idempotency-Key)
  { "long_url": "...", "custom_alias": "my-launch", "expires_at": "2027-01-01T00:00:00Z" }
  -> 201 { code, short_url, long_url, expires_at }
  -> 409 alias taken | 400 invalid/blocked | 429 over rate limit
GET /{code}       -> 302 Location: long_url | 404 unknown | 410 expired/disabled
GET /v1/links/{code}/stats?from=...&to=...   -> 200 { total_clicks, by_day, by_country }
DELETE /v1/links/{code} -> 204 (owner only, soft delete)
```

The redirect lives on the bare domain, not under `/v1/`, because the short URL is what people
share. Table `links(code PK, long_url, owner_id, created_at, expires_at, status)`; click events go
to the analytics store, not the main DB.

**Example** · one row and the queries that touch it:

```text
links:    aZ3kQ9x | https://shop.example.com/sale?utm=email | k_314 | 2027-01-01 | active
by_owner: k_314 | 2026-10-10 | aZ3kQ9x       (second table, "list my links")
A1 every redirect   GET links[aZ3kQ9x]            point read by key
A2 create           PUT links[aZ3kQ9x] IF ABSENT  conditional insert
A3 dashboard        scan by_owner[k_314]          low volume
A4 stats            clicks aggregated elsewhere   async
```

So: A1/A2 are pure key-value on `code`, no joins.

| Option | Pros | Cons |
|---|---|---|
| Sharded SQL, shard key `code` | Familiar; unique constraint per shard; owner index easy | You run sharding (ch. 07) |
| KV / wide-column (DynamoDB, Cassandra) | Partitions natively, scales storage and throughput | Insert-if-absent needs a conditional write (Cassandra LWT is slower); owner lookup needs a second table/index |

Reasonable answer: KV store partitioned by `code` + the owner table. **Dedup** of identical long
URLs needs a URL-hash index and users want separate analytics: no global dedup, maybe per owner.

## Deep dive: generating the short code
**How it works** · Needs: unique, 7 chars, fast, works across many servers, ideally not guessable.
Strong default: **B**, counter + base62. A single counter (DB sequence, Redis `INCR`) is a SPOF,
a bottleneck, must be durable (a reset re-issues codes) and is predictable; the fixes below.

**Example** · base62 is "write the number in base 62"
(`do { s = ALPHABET[n % 62] + s; n /= 62 } while n > 0`), ALPHABET = `0-9a-zA-Z`:

```text
n = 1,000,000,000 = 16,129,032*62 + 16   -> 'g'
    16,129,032    =    260,145*62 + 42   -> 'G'
       260,145    =      4,195*62 + 55   -> 'T'
         4,195    =         67*62 + 41   -> 'F'
            67    =          1*62 +  5   -> '5'
             1    =          0*62 +  1   -> '1'     read upward: "15FTGg"
n = 1,000,000,001                        -> "15FTGh"  (neighbours look alike: guessable)
```

Making it distributed: **range allocation**. A coordinator (DB row or ZooKeeper/etcd, atomic
`next = next + 100000`) gives server A `[4,500,000 .. 4,599,999]`, server B the next block; codes
come from memory; a crash wastes the rest of a block (fine, we have trillions). Making it
unguessable: a **bijective scramble**, e.g. multiply by a large odd constant mod 2^41 (2^41 ~ 2.2 T
< 62^7, so every output fits 7 chars): 4,500,000 -> `wQtMxq0`, 4,500,001 -> `xi0dHMF`. Unique
(bijection), but consecutive codes look unrelated and no longer leak volume.

The other options:
- **A. Hash + prefix:** MD5/SHA-256 of the URL, base62, take 7 chars. 7 chars hold only about 41.7
  bits, so prefixes collide: with 100 B stored, about 100e9 / 3.52e12 = 2.8% per insert. Loop:
  conditional insert; if taken by the same URL return it (free dedup); else rehash with a salt
  counter and retry. A **Bloom filter** of existing codes skips most DB checks but the
  conditional insert is still needed for correctness.
- **C. Key generation service (KGS):** pre-generates random unique codes into `unused_keys`;
  servers fetch batches of ~1,000 that are atomically marked taken. Must be replicated, and
  servers keep a key buffer to ride out a KGS blip; keys held by a crashed server are lost (ok).
- **D. Snowflake ID** (ch. 10): 41 bits ms timestamp + 10 machine + 12 sequence, generated locally.
  2^63 is about 9.2 x 10^18; 62^10 (8.4 x 10^17) too small, 62^11 (5.2 x 10^19) enough, so **11
  chars**. Shortening it with a custom epoch and fewer bits is range allocation with extra steps.

| Criterion | A. Hash + retry | B. Counter, ranges | C. KGS | D. Snowflake |
|---|---|---|---|---|
| Uniqueness | Check-and-retry | Guaranteed | Guaranteed if hand-out atomic | Guaranteed |
| Length | 7 | 6–7 | 7 | ~11 |
| Coordination | None, but DB check per write | Rare range calls | Batch calls | Machine-ID assignment |
| Predictable? | Deterministic per URL | Yes unless scrambled | No | Partially (leaks time) |
| Write cost | Conditional write + retries | Plain insert | Plain insert | Plain insert |
| Ops complexity | Low | Low–medium | Medium (another HA service) | Low; needs clocks not going back |

Strong answer: **ranges + bijective scramble + base62 to 7 chars**, or KGS if unpredictability is a
hard requirement. Keep the primary key on `code` as a safety net either way.

> **Say it like this:** "I'd allocate numeric ranges to each app server so generation is a local
> increment with no collisions, scramble the number reversibly so codes aren't guessable, and
> base62-encode to seven characters. The DB's primary key on the code stays as a safety net."

## Deep dive: 301 vs 302
**How it works** · The server returns a status plus a `Location` header; the browser follows it.

| Code | Meaning | Cached by default? | Method |
|---|---|---|---|
| **301** Moved Permanently | Permanent | Yes, possibly for a long time | May turn POST into GET (historically) |
| **302** Found | Temporary | No, unless headers say so | May turn POST into GET (historically) |
| 307 / 308 | Temporary / permanent | No / yes | Preserved |

**Example** · a reader clicks `sho.rt/aZ3kQ9x` on Monday and again on Thursday; on Wednesday the
link is disabled for malware.

```text
            with 301                                with 302 + no-cache headers
 Mon  GET /aZ3kQ9x -> 301, browser caches it       GET /aZ3kQ9x -> 302, nothing cached
      click counted: 1                             click counted: 1
 Wed  link disabled, cache key deleted             link disabled, cache key deleted
 Thu  browser jumps straight to shop.example.com   GET /aZ3kQ9x -> warning page
      server sees nothing: no count, no block      click counted: 2, user protected
```

So: clicks are GETs, so the real trade-off is **caching vs analytics**: 301 saves repeat requests
but hides clicks and keeps killed links alive in browsers. Choose **302** with `Cache-Control:
private, max-age=0`; some use 301 where analytics is off. Your cache makes the extra requests cheap.

## Deep dive: caching hot links
**How it works** · Clicks are very skewed (a viral link gets millions an hour, most get none), so
**cache-aside** (ch. 08) works well: Redis in front of the links DB, LRU eviction; the ~10 GB hot
set fits a small replicated Redis cluster.

**Example** · `aZ3kQ9x` goes viral: 50,000 clicks/min spread over 20 redirect servers.

```text
 no local cache      every click -> GET aZ3kQ9x on ONE Redis shard: 50,000 reads/min
 in-process, 5 s TTL each server refreshes 60/5 = 12 times/min
                     20 servers x 12 = 240 Redis reads/min (~200x less on that shard)
 key expires         200 requests miss at once -> coalescing: 1 DB read/server, not 200
 bot scans zzzz001   DB says absent -> cache "not found" 60 s -> rescans skip the DB
```

So: Redis absorbs the long tail; the in-process layer absorbs the **hot key**; coalescing (or
refresh-ahead) stops the **stampede**; **negative caching** stops scanners.
- **Invalidation:** data almost never changes; delete the key on disable/delete; modest TTLs
  (~24 h) self-heal a missed delete. **Expiry:** keep `expires_at` in the value or set TTL to
  `min(24h, expires_at - now)`.
- **Edge caching** of 302s at a CDN for seconds to minutes: then clicks come from CDN logs and a
  disable takes up to the CDN TTL.

## Deep dive: analytics pipeline
**How it works** · Counting must never slow or fail the redirect, so it is fire-and-forget:
redirect service -> async, batched ~100 ms -> Kafka `clicks` -> stream processor (**pre-aggregate**
per code per minute; IP -> country, UA -> device) -> OLAP / TSDB behind the stats API; raw events
go to cheap object storage for reprocessing.

**Example** · one click, 20:41:07, from a phone in Pune:
1. Redirect server appends `{code: aZ3kQ9x, ts: 20:41:07, ip, ua, referrer: mail}` to a local
   buffer; the 302 has already gone out.
2. Within ~100 ms the batch goes to Kafka, keyed by `aZ3kQ9x` (same partition as its other clicks).
3. Stream processor: IP -> IN, UA -> mobile; row `(aZ3kQ9x, 20:41, IN, mobile)` goes 36 -> 37;
   the visitor joins that day's **HyperLogLog** (uniques in a few KB); raw event archived.

So: the redirect never waits; stats are seconds to minutes behind.
- Kafka down: buffer briefly, then **drop and count drops**; losing analytics is acceptable,
  failing redirects is not (an explicit priority).
- A viral link makes a hot partition: key by `code + random suffix` and merge downstream.
- At-least-once delivery can double-count a few clicks after failures; fine for marketing, say so.

## Custom aliases, expiry, abuse
**How it works** · **Aliases:** validate charset (`[A-Za-z0-9_-]`), length (e.g. 4–30), reserved
words (`api`, `admin`, `login`, `help`), profanity/brand impersonation; uniqueness only via an
atomic conditional insert. **Expiry:** check `expires_at` on every read, clean up in the
background. **Abuse:** rate limits, reputation checks, a disable path.

**Example** · three edge cases:
1. Two creators ask for `my-launch` within 1 ms. Check-then-insert would let both see "absent";
   instead both send `INSERT ... IF NOT EXISTS`: one gets 201, the other `409 Conflict`.
2. A link with `expires_at` 2027-01-01 is clicked on 2027-01-02 before cleanup has run: the read
   check returns 410 anyway; the low-priority job deletes the row next week.
3. Key `k_314` creates its 101st link in one minute: the gateway's token bucket (100/min per key)
   answers 429.

So: correctness lives in atomic writes and read-time checks; background jobs only tidy up.
- **Alias vs generated codes:** a 7-char alphanumeric alias could clash with a future generated
  code: give aliases a different shape (contain `-` or a different length; simplest) or make
  generated inserts conditional and skip taken values.
- **Expiry:** native TTL (DynamoDB, Cassandra) works but deletes late, so keep the read check.
  **Never recycle codes**: old QR codes, emails and bookmarks would silently point to someone
  else's link, and with 3.5 trillion codes there is no need.
- **Abuse** (shorteners hide destinations, attracting spam and phishing): creation limits are far
  lower for anonymous users; reputation-list check at creation plus async scans; `status=disabled`
  + cache invalidation shows a warning page (a key reason for 302); non-sequential codes plus rate
  limits on 404s per IP resist enumeration; refuse long URLs pointing back to the shortener (loops).

## Final architecture, one request end to end, scaling path
**How it works** · Gateway (TLS, API-key auth, rate limit) in front of two stateless fleets:
create service (local ranges or KGS batches) and redirect service (in-process LRU -> Redis ->
links DB, KV or sharded SQL by code, replicated x3); clicks flow async to Kafka -> stream
processor -> OLAP/TSDB + raw log; a CDN may cache 302s briefly; a cleanup job removes expired links.

**Example** · follow one link from creation to its first two clicks:
1. `POST /v1/links` with key `k_314`: gateway checks the key, token bucket has 63 of 100 left.
2. Create server A takes the next number from its block, 4,500,001; scramble -> `xi0dHMF`.
3. `PUT links[xi0dHMF] IF ABSENT` on the shard owning that code, acked by a sync replica -> 201
   `sho.rt/xi0dHMF`.
4. First click: DNS -> CDN (nothing cached) -> gateway -> redirect server 7: in-process miss,
   Redis miss -> DB point read -> `SET xi0dHMF <long_url> EX 86400` -> 302 + `Cache-Control:
   private, max-age=0`; click event buffered.
5. Second click lands on server 12: in-process miss, Redis hit (~1 ms) -> 302. Clicks 3..N on
   servers 7 and 12 within 5 s never leave the process.
6. ~100 ms later both events are in Kafka; the per-minute row for `xi0dHMF` shows 2.

So: the write path pays for uniqueness and durability once; the read path is mostly memory.

| Stage | Scale | Design |
|---|---|---|
| V0 | Thousands/day | One app server, one SQL DB, auto-increment ID -> base62 |
| V1 | Millions/day | Stateless servers + LB, Redis, read replicas, Kafka for clicks |
| V2 | 100 M/day, ~90 TB horizon | Range allocation; shard by `code` or a KV store; Redis cluster |
| V3 | Global | Redirect + cache per region, links replicated across regions, CDN for viral links |

Links are immutable after creation, the best case for async cross-region replication. Only care
point: a creator's first click in another region may miss, so fall back to the home region on a
local miss (or route that first redirect home).

## Failure handling
**How it works** · Walk each box: detect, fail over, what is lost.

**Example** · why step 3 waited for a sync replica: with async replication `xi0dHMF` is acked at
20:00:00.000, the primary dies at .040 before shipping it, a replica is promoted without it, and
every printed copy now 404s. A sync-replica (or quorum) ack closes that window for a few ms.

| Failure | Handling |
|---|---|
| Redirect server | LB health checks drop it; stateless peers take over |
| Redis node | Replica promoted; in-process caches absorb hot links; DB sized for some misses; coalescing |
| DB replica | Others serve; replace it |
| DB primary / shard leader | Creates fail for that shard, redirects still served from cache/replicas; fail over (sync replica or quorum ack, as above) |
| Range coordinator / KGS | Servers keep unused ranges/key buffers; coordinator replicated on a consensus store |
| Kafka | Buffer, then drop and count; redirects unaffected |
| Stream processor lag | Stats delayed (eventual); scale consumers |
| Region | DNS / global LB fails over to a region with replicated links |

## Pastebin: the delta
**How it works** · Same skeleton (codes, expiry, rate limits; redirects become views), but the
**content is the payload**: up to **10 MB** (enforced at gateway and API), average ~10 KB, usually
immutable, expiry common ("burn after 1 day", "never"), optional syntax highlighting,
private/unlisted, burn-after-reading. At 10 M pastes/day and 5:1 reads: ~115 writes/s, ~580
reads/s, but **100 GB/day, about 36 TB/year** of content vs 3 GB/day metadata. Rates are modest;
**storage volume and object size dominate**, so content goes to object storage, metadata to a DB.

```text
 POST /v1/pastes -> [Paste svc] 1 generate code  2 PUT pastes/{code} -> [Object storage]
                                3 INSERT metadata (only after PUT ok) -> [Metadata DB]
 GET /{code} -> [Paste svc] -> metadata (cached): expiry, visibility
             -> content via CDN over object storage (pre-signed URL for private)
```

**Example** · follow one 2 MB log paste, public, expiring in 1 day:
1. `POST /v1/pastes {size: 2 MB, expires: 1d}`: gateway allows it (< 10 MB); paste service
   generates `pQ7r2Ks` and returns a **pre-signed URL** valid for a few minutes, size-capped.
2. The client compresses (text shrinks several-fold) and PUTs straight to object storage
   `pastes/pQ7r2Ks`; the 2 MB never touches app servers.
3. Upload confirmed -> INSERT `(pQ7r2Ks, object_key, size, expires_at, public)` -> 201. If the
   service crashed between 2 and 3: an **orphan object**, removed by a reconcile job; never a row
   pointing at missing content.
4. Viewer 1: `GET /pQ7r2Ks` -> metadata from cache (not expired, public) -> CDN miss -> object
   storage. Viewers 2..N: CDN hit (immutable, long TTL).
5. Next day: read check says expired -> 410; a **lifecycle rule** deletes the object later.

So: the DB holds ~300 B per paste; bytes live where bytes are cheap and cacheable.
- **Why split:** big rows bloat DB pages, buffer pools, backups and replication; object storage is
  built for large immutable objects, very durable, far cheaper per GB (ch. 09).
- Inline small pastes (e.g. < 4 KB) in the DB only if it measurably helps (two code paths).
- **Burn after reading:** `UPDATE ... SET status='burned' WHERE code='pQ7r2Ks' AND
  status='active'`: two simultaneous viewers, one sees 1 row changed and gets the content, the
  other 0 rows and a 404; never CDN-cached. **Private:** no public caching; short-lived
  pre-signed URLs or app auth checks.

> **Common mistake:** pre-checking "does this code exist?" then inserting. Two servers both see
> "absent" and both insert; one link silently overwrites the other. Let the store decide with an
> atomic conditional insert.

## In one minute
- Features: shorten, redirect, alias, expiry, analytics; strongly read-heavy.
- 100 M links/day is ~1,200 writes/s, 1 B redirects ~12k reads/s, ~90 TB over 5 years.
- 62^7 is about 3.5 trillion, so 7 base62 chars; a 64-bit Snowflake needs 11.
- Codes: counter ranges + bijective scramble (or KGS if unpredictability is a must); DB key as net.
- 302 keeps every click visible and links killable; 301 hides repeat clicks in browser caches.
- Hot links: Redis cache-aside + in-process cache + negative caching + stampede protection.
- Analytics async via Kafka; the redirect never waits on or fails because of it.
- Pastebin: content in object storage + CDN, metadata in DB, object first, pre-signed uploads.

## Go deeper
Full chapter 17: sections 17.7 (code generation), 17.9 (caching hot links), 17.16 (failure handling), 17.17 (Pastebin)
