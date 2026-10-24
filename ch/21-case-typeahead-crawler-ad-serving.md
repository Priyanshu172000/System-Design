# 21 — Case study: typeahead, web crawler and ad serving

Three systems with very different shapes. **Typeahead** (autocomplete) is a read-only, latency
obsessed service built from offline data. A **web crawler** is a giant, polite, never-ending
fetch loop. **Ad serving** is a real-time decision system: within roughly 100 ms it must pick the
best ad out of millions, set the right price, and record everything for billing. Ad serving gets
the most depth because it combines almost every idea in this course: indexes, caches, low-latency
key-value stores, ML serving, streaming, and deliberate consistency trade-offs. All numbers are
**assumptions**, stated so the arithmetic can drive decisions.

---

# Part A — Typeahead / autocomplete

## 21.1 Requirements

### Functional

- As the user types in a search box, show the **top 5–10 suggested completions** for the prefix.
- Suggestions are ranked by popularity (with recency), not alphabetically.
- **Trending** queries (a breaking news event) appear within minutes, not days.
- Offensive, illegal or private-looking suggestions are **filtered**.

Out of scope: spelling correction, full personalization (mentioned as an extension).

### Non-functional

| Requirement | Target (assumption) |
|---|---|
| Perceived latency | < 100 ms from keystroke to suggestions on screen |
| Server latency | p99 < ~10–20 ms (the network round trip eats most of the budget) |
| Availability | High, but degradable: if suggestions fail, search still works |
| Freshness | Base suggestions daily; trending within ~5–15 minutes |
| Consistency | None needed; slightly stale suggestions are fine |

## 21.2 Estimation

Assumptions: 1B searches/day; after client-side debouncing, about 4 suggestion requests per
search; we keep the most popular ~100M distinct queries.

```text
Requests:   1B × 4 = 4B/day → 4B / 86,400 ≈ 46,000 req/s avg → peak ~3× ≈ 140,000 req/s
            (browser + CDN caching absorbs a large share of short, popular prefixes)
Prefixes:   assume ~500M distinct prefixes (prefixes up to ~20–30 chars of 100M queries;
            shared prefixes keep this far below 100M × 20)
Per prefix: top-10 suggestion IDs × 8 B = 80 B + key/overhead ≈ ~100–150 B
Table size: 500M × ~120 B ≈ 60 GB, plus query strings 100M × ~30 B ≈ 3 GB
            → tens of GB: fits in RAM on a few large servers
```

## 21.3 API

```http
GET /v1/suggest?q=how%20to%20ma&locale=en-US&limit=8
→ 200
Cache-Control: public, max-age=300
{ "prefix": "how to ma",
  "suggestions": ["how to make pancakes", "how to make money", "how to make slime"] }
```

Requests are tiny `GET`s with no user-specific data, which is what makes them cacheable by CDNs
and browsers. (Personalized suggestions would need a separate, uncached call.)

## 21.4 Data model: the trie with top-k per node (DSA bridge)

A **trie** (prefix tree) stores strings character by character; every node represents a prefix.
Finding the node for prefix `p` takes O(|p|) steps.

The naive query, "walk to the node, then DFS the whole subtree to find the 10 most popular
completions", is far too slow for short prefixes: the subtree under `"a"` holds millions of
queries. The fix: **store the top-k completions at every node**, precomputed.

```text
                    (root)
                   /      \
                 "t"      "h"
                 top: [twitter 9M, translate 8M, ...]
                 /
              "tr"   top: [translate 8M, train tickets 2M, trump 1.9M, ...]
               /
            "tra"    top: [translate 8M, train tickets 2M, traffic 1M, ...]

Query "tra" → walk 3 nodes → return the stored list: O(|prefix|), any subtree size.
```

Building the lists is a post-order traversal: each node's list = the k best among its own
terminal query (if any) and its children's lists, a small heap merge per node, O(nodes × k log k)
in total.

Trade-off: memory. Popular queries are referenced from many nodes ("translate" sits in the lists
of `t`, `tr`, `tra`, ...). Bound it by storing **query IDs** instead of strings, capping k, and
capping prefix length (very long prefixes are rare and can return nothing).

**Flattening**: the online path only needs "prefix → list", so serving systems often store a
plain key-value table built from the trie:

```text
key: "tra"   value: [id_translate, id_train_tickets, id_traffic, ...]
key: "trai"  value: [id_train_tickets, id_trainline, ...]
```

The trie is the **build-time** structure; the flattened map is the **serve-time** structure.

> **Common mistake:** Walking a trie and running a DFS at request time "because a trie is the
> autocomplete data structure". Precompute top-k per node offline so a request does O(prefix
> length) work, and in practice flatten it into a key-value table.

## 21.5 High-level architecture

```text
 ONLINE:   Browser (debounce, cache) ──► CDN edge ──miss──► Suggest service (RAM map)
                                                                  ▲          ▲
 OFFLINE:  query logs ──► Kafka ──► data lake ──► daily batch ────┘          │
                            │                     build (snapshot)           │
                            └──► stream job (trending overlay, minutes) ─────┘
```

## 21.6 Deep dive: building it offline from query logs

```text
Daily batch job (Spark/MapReduce style) over the data lake:
1. normalize: lowercase, trim, collapse spaces, unicode normalize
2. filter:    blocklist (offensive/legal), PII patterns (emails, phone numbers),
              queries typed by too few distinct users (rare queries can be personal)
3. score:     weighted count with time decay, e.g. Σ over days of count_d × 0.9^(age_d)
4. keep the top ~100M queries
5. build trie → top-k per prefix → flatten → write snapshot files
Servers load the new snapshot beside the old one, switch atomically, free the old one
(blue/green swap; a half-loaded table is never served).
```

**Time decay** stops last year's spike dominating forever. The **distinct-users threshold** is a
privacy measure: one person's query (with their name or address) must never be suggested to all.

## 21.7 Deep dive: latency budget, caching and sharding

```text
keystroke → client debounce (~50–150 ms; waits for a pause, cancels stale requests)
   network RTT to nearest edge     ~10–40 ms
   CDN hit → done                  (most short prefixes)
   CDN miss → origin RTT           ~20–60 ms
   server lookup                   < 1 ms (in-memory hash map)
```

The server work is almost free; the budget goes to the network, so the wins are **being close**
and **not asking at all**: the browser caches responses for minutes (`Cache-Control: max-age`),
and since a prefix's answer is identical for everyone in a locale, the CDN serves the hot 1–3
character prefixes with a very high hit ratio.

**Sharding**: tens of GB fit on one large machine, so the simplest design **replicates** the full
table on every node (no fan-out). If it outgrows one machine:

- **Range partitioning by first letter** is skewed (far more queries start with "s" than "x").
- **Hash partitioning on the full prefix string** balances well, because the serving structure is
  a flat `prefix → list` map: every lookup is one key on one shard.

> **Say it like this:** "Typeahead is read-only and latency-bound. A daily batch job turns query
> logs into a prefix to top-k table, the online path is one in-memory lookup, and because the
> answer is the same for everyone in a locale it caches well in the browser and at the CDN."

## 21.8 Deep dive: trending, filtering and personalization

**Trending** cannot wait for the daily batch:

```text
query events ──► Kafka ──► stream job: per-query counts in sliding windows (last 1 h vs
                           the usual rate for that hour)
                           → queries with a big jump and enough volume = "trending set"
                           → recompute top-k only for the prefixes of those queries
                           → small overlay table pushed to servers every few minutes
Serve: result = merge(base[prefix], overlay[prefix]) by score, take top k
```

**Filtering** happens at **build time** (blocklists, PII, privacy threshold) and at **serve time**
via a hot-reloadable denylist, so urgent removals take effect in minutes without a rebuild.

**Personalization** (extension): blend in the user's recent queries in a separate uncached call
or on the client, keeping the global path cacheable.

## 21.9 Bottlenecks, failures and trade-offs (typeahead)

| Issue | Mitigation |
|---|---|
| Popular short prefixes are hot keys | CDN and browser caching; full replication of the table |
| Bad snapshot (bug in build) | Validate size and sample queries before swap; keep previous snapshot for rollback |
| Harmful suggestion goes viral | Serve-time denylist, hot reload |

## 21.10 Final architecture and how to present it in 5 minutes (typeahead)

```text
 Browser (debounce, cache) ──► CDN edge (prefix+locale, TTL minutes)
                                     │ miss
                                     ▼
                     Suggest service × N (full replica of prefix → top-k map)
                        ▲ daily snapshot   ▲ trending overlay   ▲ denylist (hot reload)
                        │                  │
 Data lake ──► batch build (normalize, filter, decay, trie, top-k, flatten, validate)
 Kafka query logs ──► stream job (sliding-window counts → trending prefixes)
```

1. **Scope and numbers:** top-k by popularity + recency, < 100 ms perceived, ~140k req/s peak,
   tens of GB of prefix → top-k data.
2. **Structure:** trie with top-k per node built offline, flattened to a key-value map.
3. **Build and serve:** logs → normalize, filter (privacy threshold), decayed counts → snapshot
   swap; replicate the map; browser + CDN caching; debounce.
4. **Freshness:** streaming trending overlay; serve-time denylist.

---

# Part B — Web crawler

## 21.11 Requirements

### Functional

- Starting from **seed URLs**, download pages, extract links, keep discovering new pages.
- Store page content for downstream indexing (search, chapter 09).
- **Recrawl** pages to keep content fresh, more often for pages that change often.

### Non-functional

| Requirement | Target (assumption) |
|---|---|
| Scale | 1B pages per month |
| **Politeness** | Never overload a website; obey robots.txt |
| Robustness | Survive malformed HTML, slow servers, crawler traps, machine failures |
| Freshness | Important pages recrawled within hours to days |

## 21.12 Estimation

```text
Fetch rate:   1B pages / (30 × 86,400 s) ≈ 385 pages/s avg → design for ~1,000 pages/s
Page size:    ~100 KB of HTML on average
Bandwidth:    1,000 × 100 KB = 100 MB/s ≈ 0.8 Gbit/s; storage 1B × 100 KB = 100 TB/month raw
Concurrency:  a fetch takes ~1 s end to end (DNS, connect, TLS, download); Little's law
              (ch 04): in flight = 1,000/s × 1 s = 1,000 open fetches
              → a handful of machines with async I/O, not thousands of threads
Links:        ~50 links per page → 50,000 discovered URLs/s to dedupe
Seen URLs:    after months of crawling, 10B+ distinct URLs known
```

## 21.13 API and data model

A crawler has no public API; its interfaces are internal:

```text
frontier.add(url, priority)          frontier.next() → url whose host may be fetched now
fetch(url) → status, headers, body   store(url, content, content_hash)
```

| Data | Store | Why |
|---|---|---|
| Frontier (URLs waiting) | Disk-backed queues per node, in-memory heads | Billions of entries, FIFO-ish access |
| URL metadata (`url_hash → last_fetch, status, content_hash, change_rate, next_due`) | Wide-column / KV, keyed by URL hash | Huge, point lookups, recrawl scans by due time |
| Seen-URL set | Bloom filter in RAM + exact hash store | 50k checks/s against 10B URLs |
| Page content | Object storage, large append-only files of many pages | Cheap, batch-friendly for indexing |
| robots.txt and DNS caches | In-memory per node with TTL | Read on every fetch |

## 21.14 High-level architecture

```text
 Seeds ──► URL frontier ──► Fetchers (async HTTP) ──► Parser / link extractor
            ▲  (priority +      │     ▲                      │           │
            │   politeness)     │     └ DNS cache,           │ links     │ content
            │                   │       robots cache         ▼           ▼
            │                   │                   Normalize + URL   Content dedupe
            │                   │                   filter + seen?    (hash, SimHash)
            │                   │                         │                 │ new
            └──────── new URLs ─┼─────────────────────────┘                 ▼
                                │                                     Page store
 Recrawl scheduler ◄─── URL metadata DB ◄──────── fetch results ──────┘ (object storage)
```

## 21.15 Deep dive: the URL frontier (priority + politeness)

The **frontier** is the set of URLs waiting to be fetched. It has two jobs that pull in different
directions: **priority** (important or fast-changing pages first) and **politeness** (never hit
one host too often: one connection per host, with a delay between requests).

A classic design (from the Mercator crawler) uses two layers of queues:

```text
  new URLs with priority
          │
          ▼
  FRONT queues (by priority)     F1 (highest)  F2  ...  Fn (lowest)
          │   biased pick: higher-priority queues chosen more often
          ▼
  BACK queues, one host each     B1: example.com   B2: news.site  ...  Bm: blog.org
          │
  MIN-HEAP of (next_allowed_time, back queue)
          │
  Fetcher: pop heap min → wait until its time → take next URL of that host → fetch
           → re-insert host with next_allowed_time = now + delay(host)
           → if that back queue is empty, refill it with URLs of a new host from the front
```

DSA bridge: politeness is a scheduling problem; a min-heap keyed by "earliest time this host may
be contacted again" yields the next fetchable host in O(log m).

The per-host delay is adaptive (a common heuristic: a multiple of the last fetch time, never below
the site's `Crawl-delay`, backed off on 429/503). Billions of URLs mean the frontier lives mostly
on disk with in-memory buffers at each queue head.

> **Say it like this:** "The frontier has front queues for priority and back queues for
> politeness, one host per back queue, plus a min-heap on each host's next allowed fetch time.
> Workers always take the earliest host that may be hit, so overall throughput is high while no
> single site is hammered."

## 21.16 Deep dive: robots.txt, DNS and fetching

**robots.txt** (the Robots Exclusion Protocol, standardized as RFC 9309) lives at
`https://host/robots.txt` and lists paths crawlers may not fetch:

```text
User-agent: *
Disallow: /private/
Crawl-delay: 5        (not in the standard; common, and some crawlers honor it)
```

Fetch it before crawling a host, cache it (about a day), and check every URL against it; if it
returns a server error, skip the host for a while. **DNS** becomes a bottleneck at 1,000
fetches/s (tens of ms per lookup, occasional multi-second timeouts): run a **local caching
resolver** and resolve asynchronously ahead of fetching.

**Fetchers** use async I/O, strict timeouts, a maximum body size and redirect limit, and an
honest `User-Agent`. Recrawls use **conditional GET** (`If-Modified-Since` / `If-None-Match`) so
unchanged pages return `304 Not Modified` with no body.

## 21.17 Deep dive: URL dedupe and content dedupe

**URL normalization** first, so different spellings of one URL collapse:

```text
HTTP://Example.COM:80/a/./b/../c?utm_source=x&b=2&a=1#section
→ http://example.com/a/c?a=1&b=2
  (lowercase scheme/host, drop default port, resolve ./ and ../, drop the fragment,
   drop known tracking params, sort query params)
```

**URL-seen test**: 50,000 discovered URLs/s checked against 10B+ known URLs. A **Bloom filter**
(a bit array plus k hash functions; it answers "definitely not seen" or "probably seen"):

```text
bits per element for false-positive rate p:  m/n = −ln(p) / (ln 2)^2
p = 1%  → 4.6 / 0.48 ≈ 9.6 bits per URL
10B URLs × 9.6 bits ≈ 96 Gbit ≈ 12 GB   (split across crawler nodes)
```

Trade-off: a false positive **skips a URL never crawled** (~1% of new URLs); acceptable since
important pages are linked from many places, or confirm against exact 64-bit hashes (80 GB).

**Content dedupe**: many URLs serve the same page (mirrors, `?ref=` variants, print versions).

- **Exact duplicates**: hash the normalized content and look the hash up.
- **Near duplicates** (same article, different ads): **SimHash** gives a 64-bit fingerprint where
  similar pages differ in few bits; a published web-scale study used a Hamming distance of about
  3. Lookup uses permuted, sorted tables: by pigeonhole, fingerprints differing in ≤ 3 bits agree
  exactly on at least one of 4 blocks of 16 bits.

## 21.18 Deep dive: recrawl, traps and distribution

**Recrawl**: pages change at very different rates (a news homepage every few minutes, an old
blog post never). Track whether the content hash changed at each fetch; shorten the interval when
it did, lengthen it when it did not, within bounds and weighted by importance. Sitemaps
(`<lastmod>`) give hints.

**Crawler traps** (infinite URL spaces):

| Trap | Example | Defense |
|---|---|---|
| Infinite calendars | `/calendar?month=2026-11`, forever | Max pages per host per cycle; depth limit |
| Session IDs in URLs | `?sid=8f3a...`, new on every visit | Strip known session params; content dedupe |
| Repeating paths | `/a/b/a/b/a/b/...` from relative-link bugs | Max URL length; detect repeated segments |
| Soft 404s | "Not found" pages returning 200 | Fingerprint each host's error page |
| Link farms | Millions of generated pages | Host quality score lowers priority |

**Distributed coordination**: partition work **by host** (`hash(host)` on a consistent hash
ring, chapter 07). Each node owns the queues, robots cache and politeness state of its hosts, so
politeness is a **local** decision; links to other nodes' hosts are batched and forwarded. A
membership service (chapter 10) tracks live nodes; a dead node's hosts move to ring neighbors,
which recover its frontier from checkpoints.

## 21.19 Bottlenecks, failures and trade-offs (crawler)

| Issue | Mitigation |
|---|---|
| One huge host (millions of pages) | Politeness caps its rate anyway; spread its URLs over time |
| DNS latency | Local caching resolver, prefetch |
| Node crash | Frontier checkpoints; hosts reassigned via the ring |
| Freshness vs bandwidth | Adaptive recrawl intervals, conditional GET |
| Traps and spam | Limits per host, URL length/depth limits, quality scores |

## 21.20 Final architecture and how to present it in 5 minutes (crawler)

```text
          ┌──────── crawler node k (owns hosts whose hash falls in its range) ─────┐
 seeds ──►│ frontier: front priority queues → per-host back queues → time heap     │
 recrawl─►│    ▼                                                                   │
          │ async fetchers ◄── DNS cache, robots cache                             │
          │    ▼                                                                   │
          │ parser → normalize → seen? (bloom + exact) ──own host──► frontier      │
          │    │                         └──────other host──► forward to its owner │
          │    ▼                                                                   │
          │ content dedupe (hash, SimHash) ──► page store (object storage)         │
          └────────────────────────────────────────────────────────────────────────┘
 URL metadata DB ◄──► recrawl scheduler     membership service (ring)     → indexing
```

1. **Scope and numbers:** 1B pages/month politely; ~400 pages/s (plan 1,000), ~1,000
   concurrent fetches by Little's law, 50k new links/s, 12 GB Bloom filter for 10B URLs.
2. **Frontier:** priority front queues, per-host back queues, min-heap of next allowed time.
3. **Fetch and dedupe:** robots.txt and DNS caches, async I/O, conditional GET; URL
   normalization + Bloom filter; content hashes and SimHash.
4. **Robustness:** trap defenses, host-partitioned nodes on a ring, checkpointed frontier.

---

# Part C — Ad serving

## 21.21 Vocabulary first

| Term | Meaning |
|---|---|
| **Advertiser** / **campaign** | Pays for ads; a campaign has a **budget** (e.g. $500/day), schedule, goal |
| **Ad / creative** | The thing shown (image, text, video) with a landing URL |
| **Targeting** | Who may see the ad (geo, device, language, interests, keywords, ...) |
| **Publisher**, **slot** | The site/app showing ads; a slot is a place on its page |
| **Impression**, **click**, **conversion** | Ad rendered once; user clicked; user then bought/signed up |
| **CPM**, **CPC**, **CPA** | Price per 1,000 impressions; per click; per action (conversion) |
| **CTR**, **pCTR**, **pCVR** | Click-through rate; *predicted* CTR; predicted conversion rate after a click |
| **eCPM** | Expected revenue per 1,000 impressions: puts all bid types on one scale |
| **Frequency cap** | Max times one user sees a campaign in a window (e.g. 3 per day) |
| **Pacing** | Spreading a budget over the day instead of spending it all at 9 AM |

## 21.22 Requirements

### Functional

- Given an ad request (slot, context, user, device, geo), return the best eligible ad with
  **tracking URLs** in time, respecting **targeting**, **budgets**, **frequency caps**, blocklists.
- Rank by ML-predicted expected value; run an **auction** to pick the winner and price.
- Record impressions, clicks and conversions for **billing**, **budgets**, **reporting** and
  **model training**.
- Support **A/B experiments** on models and auction logic.

### Non-functional

| Requirement | Target (assumption) | Note |
|---|---|---|
| Latency | ~100 ms total; internal p99 ~50–80 ms | A late answer loses the auction: zero revenue |
| Throughput | 500k ad requests/s at peak | Follows publisher traffic |
| Availability | Very high | Downtime is directly lost revenue |
| Billing accuracy | Exact and auditable | Achieved asynchronously, reconciled offline |
| Budget enforcement | Small bounded overspend (~1–2%) tolerated | Exactness would cost latency |
| Campaign changes | Take effect within seconds to a minute | A pause must stop spend quickly |

> **Say it like this:** "The serving path is a real-time funnel with a budget of about 100 ms, so every
> lookup is in memory or a single-digit-millisecond key-value read. Money correctness is handled
> asynchronously: the event pipeline and offline reconciliation make billing exact, while budgets
> are enforced approximately with a small, bounded overspend."

## 21.23 Estimation

Assumptions: 500k requests/s peak (200k average), 10M active ads in 500k campaigns, one slot per
request, 70% fill rate (requests that get an ad), CTR ~0.5%.

```text
Impressions:  200k × 0.7 ≈ 140k/s avg → ~12B impressions/day
Clicks:       12B × 0.5% ≈ 60M/day ≈ 700 clicks/s avg
Logs:         ~1 KB per logged request/impression → roughly 15–30 TB/day of raw events
Scoring:      heavy model on 1,000 candidates per request:
              500k × 1,000 = 500M inferences/s → far too expensive
              → two stages: cheap model on ~1,000, heavy model on the top ~100
              → ~50M heavy inferences/s (still a large, batched CPU/GPU fleet)
Features:     one batched user read + one batched read for ~100 ads per request
              → ~500k batched multi-gets/s on the online feature store
Ad index:     10M ads × ~1 KB of targeting + metadata ≈ 10 GB → fits in RAM per server
Servers:      at ~1,000 req/s per server → ~500 at peak, ×1.5–2 headroom, several regions
```

## 21.24 API

```http
POST /v1/ad-request                  (from a publisher page/app, or an ad exchange)
{ "request_id": "r_7c1e",
  "slot":    { "id": "pub42_top", "size": "300x250", "floor_cpm": 1.20 },
  "context": { "url": "https://news.example/sports/...", "lang": "en",
               "keywords": ["football", "transfer"] },
  "device":  { "type": "mobile", "os": "android" },
  "geo":     { "country": "US", "region": "CA" },
  "user":    { "id": "u_hash_91a" },          // pseudonymous, consent-dependent
  "timeout_ms": 100 }
→ 200
{ "ads": [ { "ad_id": "ad_555",
             "creative_url": "https://cdn.ads.example/ad_555.jpg",
             "click_url": "https://clk.ads.example/c?d=<signed-encrypted-blob>",
             "impression_url": "https://imp.ads.example/i?d=<signed-encrypted-blob>" } ] }
→ 204 No Content   (no eligible ad; the publisher shows a fallback)
```

The tracking URLs carry an **encrypted, signed** blob (request ID, ad ID, campaign ID, price,
timestamp, experiment IDs), so events can be attributed later without a lookup and nobody can
forge them or tamper with the price.

## 21.25 Data model

| Data | Store | Why |
|---|---|---|
| Advertisers, campaigns, ads, targeting, bids, budgets | SQL (source of truth) | Relational, edited by humans and APIs, needs transactions |
| Targeting index + ad metadata | **In memory on every ad server**, built from SQL via change stream | 10 GB; every request reads it; no network hop allowed |
| User/ad/context features, frequency-cap counters | Online KV store (Aerospike/Redis/Cassandra style) | ~1 TB, single-digit ms p99, batched reads |
| Budget and pacing state | Budget service (in-memory + durable log), cached on servers | Updated every few seconds from spend events |
| Events (requests, impressions, clicks, conversions) | Kafka → data lake (append-only) | Huge volume, replayable, source for billing |
| Billing ledger | SQL or wide-column with idempotent upserts by event ID | Money: exact and auditable |
| Reporting aggregates | OLAP store (chapter 09) | Slice by advertiser, campaign, hour |

## 21.26 High-level architecture

```text
 Advertiser UI ──► Campaign DB (SQL) ──► change stream ──► index snapshots/updates ──┐
                                                                                      ▼
 Publisher/exchange ──► LB ──► Ad servers (in-memory index) ◄──► Online feature store
                                   │        ▲                ◄──► Model servers
                                   │        └── budget/pacing state ◄── Budget service
                                   └── decision log ──► Kafka                  ▲
                                                          ▲                     │
 Browser/app ── impression/click beacons ──► Collectors ──┘                     │
                                                          │                     │
                                  Stream processing (dedupe, join, fraud) ──────┘
                                       ├──► Billing ledger    ├──► Reporting OLAP
                                       └──► Data lake ──► reconciliation, model training
```

## 21.27 The request flow and latency budget

```text
 Ad request
    ▼  ~2 ms     parse, validate, assign experiments, deadline = now + 80 ms
 [1] User & context lookup     ~5–10 ms   one batched read: user features, segments,
                                          frequency-cap counters; page context (cached)
    ▼
 [2] Retrieval                 ~5 ms      in-memory targeting index: 10M ads → ~2,000
    ▼
 [3] Filtering                 ~2–5 ms    budget/pacing, frequency caps, schedule,
                                          blocklists, approval, floor → ~1,000 eligible
    ▼
 [4a] Light ranking            ~5 ms      cheap model or historical CTR → top ~100
    ▼
 [4b] Heavy scoring            ~10–20 ms  batched ad features + heavy pCTR/pCVR model
    ▼
 [5] Auction                   < 1 ms     rank by eCPM (+ quality), floor, set price
    ▼
 [6] Response + async log      ~1–2 ms    signed tracking URLs; log features and scores
 Total ≈ 35–55 ms typical, leaving margin for network and tail latency inside ~100 ms
```

Rule for the whole path: **every stage has a deadline**. If the heavy model is slow, use the
light ranking; if the feature store times out, use defaults. A decent ad on time beats a perfect
ad late, which earns nothing.

> **Common mistake:** Designing ad serving as "query a database of ads with a WHERE clause". At
> 500k requests/s with a 100 ms budget there is no time for a database round trip per stage. The
> ad index lives in each server's memory, updated by a stream of campaign changes; only small
> per-user state is read from a low-latency key-value store.

## 21.28 Deep dive: candidate retrieval with a targeting index

Each ad has a **targeting expression**, a boolean condition on request attributes:

```text
ad_1: geo IN {US, CA} AND device = mobile AND interest IN {sports}
ad_2: geo = US AND lang = en                         (any device, any interest)
ad_3: keyword IN {football, soccer} AND NOT geo = FR
```

Evaluating 10M expressions per request is O(ads): too slow. Instead build an **inverted index**
(chapter 09): the same idea as search, but the "documents" are ads and the "terms" are
targeting attribute values.

```text
posting lists (sorted ad IDs):
  geo=US           → [1, 2, 7, 9, 15, ...]
  geo=*            → [3, 11, ...]         ← ads with NO geo restriction match any geo
  device=mobile    → [1, 9, 15, ...]
  device=*         → [2, 3, 7, 11, ...]
  interest=sports  → [1, 40, ...]
  interest=*       → [2, 3, 7, ...]
  keyword=football → [3, 22, ...]
  keyword=*        → [1, 2, 7, ...]

Request {geo=US, device=mobile, interests=[sports, cars], keywords=[football]}:
  G = geo=US ∪ geo=*                D = device=mobile ∪ device=*
  I = interest=sports ∪ interest=cars ∪ interest=*
  K = keyword=football ∪ keyword=*
  candidates = G ∩ D ∩ I ∩ K, then drop ads whose NOT clauses match
```

DSA bridge: posting lists are sorted ID arrays, so union is a k-way merge and intersection is a
merge walk. Start from the **smallest** list and gallop (binary search forward) through the
larger ones, so the cost follows the smallest list, not the largest.

What makes it production-grade:

- The **wildcard list** (`attr=*`) handles ads that do not restrict an attribute. Forgetting it is
  a classic bug: untargeted ads never match.
- Arbitrary ORs of ANDs need more machinery ("boolean expression indexing": index each
  conjunction, count how many of its terms the request satisfies). Mention it; do not derive it.
- **Updates**: campaign edits flow through a change stream; each server applies them to its
  in-memory index within seconds and periodically reloads a full snapshot to fix drift. Pauses
  are re-checked in the filter step via a fast-propagated kill list.
- **Replicate, don't shard**, while the index fits in memory (~10 GB). Sharding the ad index
  forces scatter-gather on every request, and the slowest shard sets the latency.

## 21.29 Deep dive: filtering (budget, frequency cap, eligibility)

Filters remove candidates that **must not** be shown. Run cheap filters first.

| Filter | Data | Where it lives |
|---|---|---|
| Active, in schedule (dayparting), creative approved | Ad metadata | In-memory index |
| Advertiser/publisher blocklists, brand safety | Publisher config | In memory |
| Floor: best possible eCPM below the slot floor | Bid, floor | Request + memory |
| **Budget/pacing**: may this campaign spend right now? | Pacing state | Local cache, refreshed every few seconds |
| **Frequency cap**: user saw campaign < N times in window | Per-user counters | Online KV, read once per request |

**Frequency capping** detail: key `fcap:{user_id}` → `campaign_id → (count, window start)` with a
TTL. It is read as part of the user lookup and incremented asynchronously when the **impression
event** arrives (not at serve time, because many served ads never render). Two requests from the
same user inside the event delay can both pass, so caps are approximately enforced. That is the
standard, accepted trade-off.

## 21.30 Deep dive: scoring and eCPM

Advertisers bid in different units. Convert every bid into **expected revenue per 1,000
impressions (eCPM)** so they can be compared:

```text
CPM bid:  eCPM = bid_cpm
CPC bid:  eCPM = bid_cpc × pCTR × 1000
CPA bid:  eCPM = bid_cpa × pCTR × pCVR × 1000

Worked example, one slot, floor $1.20 CPM:
  Ad A: CPC $2.00, pCTR 1.0%  → 2.00 × 0.010 × 1000 = $20.00
  Ad B: CPM $15.00            →                       $15.00
  Ad C: CPC $0.50, pCTR 5.0%  → 0.50 × 0.050 × 1000 = $25.00
  Ranking: C > A > B → C wins although it has the lowest bid per click
```

This is the key idea of ad ranking: **bid × probability**, not bid alone. A high bid on an ad
nobody clicks earns a CPC network nothing and annoys users. Real systems also add a **quality term**
(penalizing ads users hide or report).

The model must be **calibrated**: when it predicts 2% for a group of impressions, about 2% must
click. Ranking only needs the order right, but eCPM and prices multiply the prediction by money:
a model that is 20% over-confident misprices everything by about 20%. Calibration is monitored per
segment and corrected (for example with an isotonic or Platt-scaling layer).

Two-stage scoring keeps cost bounded: a light model (or historical CTR) on ~1,000 candidates, the
heavy model (deep network with embeddings and feature interactions) on the top ~100, in **one
batched inference call** per request.

## 21.31 Deep dive: the auction

The auction decides the winner and **what it pays**.

**Second-price auction** (one slot): the winner pays the least it could have bid and still won,
i.e. the runner-up's eCPM (plus a small increment), converted back into its own bid unit.

```text
C wins with eCPM $25; runner-up A has eCPM $20.
C must keep eCPM ≥ $20:  price_per_click × 0.05 × 1000 ≥ 20  →  $0.40 (+ $0.01) = $0.41
C bid $0.50 per click but pays $0.41. With no rival above the floor, the floor sets it.
```

Why second price: for a single item, bidding your true value is the best strategy (the Vickrey
property), so advertisers need not game their bids. With **multiple slots**, the generalized
version (GSP: each slot pays what it needs to stay above the next one) is widely used in search
ads but is **not** truthful in general.

**First-price auction**: the winner pays its own bid. It is simpler and more transparent, and much
of the programmatic exchange industry switched to it around 2019. Bidders then **shade** (bid
below their value) using models of the likely clearing price, so the complexity moves to buyers.

| | Second-price | First-price |
|---|---|---|
| Winner pays | Runner-up's price + increment | Its own bid |
| Bidder strategy | Bid true value (single slot) | Shade below value |
| Transparency | Buyers must trust the seller's "second price" | What you bid is what you pay |
| Common in | Search and many owned-and-operated ad systems | Programmatic exchanges (RTB) |

The **reserve price (floor)** protects publishers from selling too cheaply. Computationally the
auction is trivial (sort ~100 numbers); the hard parts are before it (scoring) and after it
(charging correctly).

> **Say it like this:** "I rank by eCPM, bid times predicted click or conversion probability, so
> CPC, CPA and CPM bids compete on one scale. In a second-price auction the winner pays just
> enough to beat the runner-up, converted back to its bid unit; in first-price it pays its bid
> and bidders shade. The auction itself is cheap; pCTR calibration is what keeps prices honest."

## 21.32 Deep dive: budget pacing and the consistency trade-off

**Problem 1, spending too fast**: a $1,000/day campaign that matches lots of traffic would win
every auction from midnight and run out by 2 AM, missing the audience later in the day.

**Pacing** makes spend follow a **target curve** through the day (uniform, or shaped like expected
traffic). A **controller** (PID-style is common) runs every few seconds per campaign, compares
actual spend with the target so far, and turns a knob:

- **Throttling**: the campaign joins only a fraction p of eligible auctions; raise p when behind,
  lower it when ahead. Servers cache p; the filter does `if random() > p: drop`.
- **Bid multiplier**: scale bids below 1 when ahead (cheaper wins), back toward 1 when behind.

**Problem 2, overspending**: spend is only known after impression events flow through the
pipeline (seconds later), while hundreds of servers decide in parallel. A strongly consistent
check ("decrement remaining budget in one DB transaction before serving") would add a
cross-network round trip and a **hot row per campaign** to every request: impossible inside the
latency budget at 500k req/s.

So budgets are **eventually consistent with a bounded overspend**:

```text
Overspend risk ≈ spend rate × staleness of the budget view
Example: campaign spending $10/s, view up to 10 s stale → up to ~$100 over;
         on a $10,000 daily budget that is 1%.
```

How to bound it:

- **Short refresh intervals** for the budget view on servers (seconds).
- **Budget leases**: the budget service grants each server a slice ("you may spend $5 of
  campaign X"); the server spends it locally with no coordination and asks for more when low.
  Slices shrink as the remaining budget shrinks, so worst-case overspend ≈ servers × slice size,
  approaching zero near exhaustion. (DSA bridge: a token bucket whose tokens are handed out to
  many workers.)
- **Slow down near the end**: the pacer cuts p sharply as the budget nears zero.
- **Business policy**: overspend is usually not billed to the advertiser (the platform absorbs
  it), which is why it must be small and measured.

| Data | Consistency chosen | Why |
|---|---|---|
| Campaign config, pause | Eventual, seconds | Streamed to in-memory index; kill list for pauses |
| Budget remaining | Eventual, bounded overspend | Strong consistency would blow the latency budget |
| Frequency cap counters | Eventual | Slight over-delivery is acceptable |
| Billing ledger | Exact after dedupe and reconciliation | Money; computed asynchronously |
| Dashboards | Near-real-time estimate, corrected later | "Numbers may change for N hours" |

> **Say it like this:** "I don't put a strongly consistent budget check in the serving path; at
> 500k requests per second it would add a round trip and create hot rows. Servers enforce a
> cached budget view or a leased slice, the pacer keeps spend on a target curve, and worst-case
> overspend is spend rate times staleness, which shrinking leases drive toward zero."

## 21.33 Deep dive: impression and click tracking pipeline

Every money number comes from events. The pipeline must be **durable, deduplicated,
fraud-filtered and reconcilable**.

```text
 Browser/app ── impression beacon / click redirect ──► Edge collectors (stateless)
                                      │ verify signature, decrypt blob, write to Kafka,
                                      │ answer fast (click → 302 to the landing page)
                                      ▼
         Kafka: requests, impressions, clicks, conversions   (keyed by request_id)
             │                                    │                        │
             ▼                                    ▼                        ▼
 Stream processor:                       Raw archive (data lake)    Training data:
 1 dedupe by event_id (keyed state+TTL)      │                       logged features
 2 join click → impression → request         ▼                       + labels
 3 invalid-traffic (fraud) filters       Daily reconciliation:          │
 4 aggregate spend per campaign          recompute from raw, apply      ▼
   every few seconds                     late fraud decisions →     Model training
     │            │            │         final invoices
     ▼            ▼            ▼
 Budget svc   Billing ledger   Reporting OLAP
 (pacing)     (idempotent upserts by event_id)
```

Key points:

- **Collectors are dumb and fast**: verify, write to Kafka, return; no database on this path.
- **Deduplication**: browsers, proxies and SDKs retry and duplicate beacons. Every event carries
  a unique ID (impression ID = request ID + slot). The stream job keeps keyed state of seen IDs
  for a window (e.g. 24 h with TTL) and drops repeats; the ledger uses **idempotent upserts keyed
  by event ID**, so a replay after a crash cannot double-bill. This is how "exactly-once billing"
  is built in practice: at-least-once transport plus idempotent effects (chapter 11).
- **Served is not shown**: bill impressions on render (or viewability), not on server response.
- **Click fraud / invalid traffic (IVT)** filters are layered: rules (bot user agents,
  data-center IPs, blocklisted publishers), rate rules (too many clicks per user/IP/device per
  minute, a click a fraction of a second after render, a click with no matching impression), and
  ML models applied later in batch with **retroactive credits** to advertisers.
- **Real time vs final**: streaming gives fast estimates for pacing and dashboards; the **daily
  reconciliation** over the raw archive is the source of truth for invoices (chapter 09). Its lag
  is the budget staleness of 21.32, so keeping it at seconds keeps overspend small.

## 21.34 Deep dive: feature store and low-latency key-value lookups

Models need **features**: user (interest segments, recent activity, embeddings), ad (historical
CTR, category), context (publisher, slot position, time of day), and crosses of these.

```text
OFFLINE store (data lake/warehouse)        ONLINE store (low-latency KV)
 - full history, for training               - latest value per key, for serving
 - batch computed, point-in-time joins      - p99 ~1–5 ms, batched multi-get
                                            - keys: user_id, ad_id, (publisher, slot), ...
 batch jobs (30-day interests) ──► both      ◄── streaming jobs (clicks in last 10 min)
```

Sizing: 1B users × ~1 KB ≈ 1 TB of user features, so SSD-optimized key-value stores
(Aerospike-style: index in RAM, records on SSD) or Redis/Cassandra-style clusters are common,
with single-digit-millisecond p99, TTLs, cross-zone replicas and defaults on a miss. Per request:
**one** batched read for user + context, **one** for the top ~100 ads, never one per feature.

**Training-serving skew** is the main correctness risk: training features computed one way
(batch, with hindsight) and serving features another way (online, at that moment). A widely used
remedy is **feature logging**: log the exact feature values used at serving time with the request
ID, and train on those logs joined with the click and conversion labels from the pipeline.

## 21.35 Deep dive: model serving and experimentation

**Model serving**:

- **Models and placement**: pCTR/pCVR models, from logistic regression to deep models with large
  embedding tables; run in-process (no network hop) or on a separate fleet (GPUs, independent
  scaling) called once per request with all ~100 candidates batched.
- **Updates**: retrain hourly or daily (ad performance drifts fast); validate offline, then
  **shadow-score** (compute but do not use), then ramp through an A/B test. Servers hot-swap
  versions without restarts and roll back instantly.
- **Fallbacks and cold start**: on a missed deadline use the light model or CTR priors (track the
  fallback rate); new ads rely on advertiser/category features plus a little **exploration**.

**Experimentation (A/B testing)**:

```text
bucket = hash(user_id + layer_salt) mod 1000
layer "ranking model":  buckets 0–49 → model v42 (treatment), 50–99 → control
layer "auction rules":  different salt → independent split, runs at the same time
Config is pushed to servers (no deploy); every logged event carries its experiment IDs.
```

- **Deterministic hashing** keeps a user in the same arm without storing anything.
- **Layers** allow many overlapping experiments: layers hash independently; experiments in one
  layer are mutually exclusive (a design from a well-known published paper).
- **Metrics**: revenue per 1,000 requests, CTR, advertiser ROI, p99 latency, with **guardrails**.
- **Ad-specific trap: shared budgets.** Both arms spend the **same campaign budgets**. If the
  treatment spends faster, control sees exhausted campaigns and looks worse, inflating the lift.
  Remedies: **budget-split** experiments (each campaign's budget divided between arms by traffic
  share) or randomizing by campaign/advertiser instead of by user.

## 21.36 Bottlenecks, failures and trade-offs (ad serving)

| Risk | Impact | Mitigation |
|---|---|---|
| Model serving slow | Timeouts → lost auctions | Per-stage deadlines, light-model fallback, batching |
| Feature store latency spike | Whole request late | Hedged reads, defaults on miss, local hot-key cache |
| Index update lag | Paused ads still served | Kill list, status re-check in filter, lag alerts |
| Event pipeline lag | Stale budget view → overspend | Lag alerts; pacer slows campaigns when data is stale |
| Duplicate events | Double billing | Event IDs, keyed dedupe, idempotent ledger, reconciliation |
| Click fraud | Advertisers billed for bots | Layered IVT filters, retroactive credits |
| Region outage | Lost revenue | Active-active regions, each with full index and feature replicas |
| Hot campaign in budget service | Hot key | Per-server leases, local spend aggregation |

Trade-offs to name: **latency vs accuracy**, **budget accuracy vs latency** (bounded overspend),
**real-time vs exact numbers** (stream estimates, batch invoices), **exploration vs exploitation**.

## 21.37 Final architecture (ad serving)

```text
 CONTROL PLANE
 Advertiser UI/API ──► Campaign DB (SQL) ──► change stream ──► index builder + kill list
                                                                      │ snapshots, updates
 SERVING (per region, ×N servers)                                     ▼
 Publisher / exchange ──► LB ──► Ad server ─ in memory: targeting index, ad metadata,
                                    │                   pacing state, experiment config
                                    ├─ [1] user/context ◄──── online feature store (KV)
                                    ├─ [2] retrieve  [3] filter (budget lease, freq cap)
                                    ├─ [4] light rank → heavy score ◄──── model servers
                                    ├─ [5] auction  [6] response + signed tracking URLs
                                    └─ async decision log ──► Kafka
 EVENTS
 Browser/app ── beacons/clicks ──► edge collectors ──► Kafka
                                                         ▼
                      stream processing: dedupe, join, IVT filters, spend aggregation
                         │                  │                       │
                         ▼                  ▼                       ▼
                 budget/pacing svc    billing ledger         reporting OLAP
                 (leases, p, bid      (idempotent)
                  multipliers) ──► back to ad servers
 OFFLINE
 data lake (raw events + logged features) ──► daily reconciliation → invoices
                                          ──► model training → registry → model servers
 experiment config service ──► all ad servers
```

## 21.38 How to present this in 5 minutes (ad serving)

1. **Scope and numbers (60 s):** best eligible ad within ~100 ms; 500k req/s, 10M ads → ~10 GB
   index replicated in RAM; two-stage scoring because 500M inferences/s is unaffordable.
2. **Funnel (60 s):** user lookup → inverted targeting index (with wildcard lists) → filters →
   light then heavy model → eCPM → auction → signed tracking URLs; a deadline per stage.
3. **Pricing (40 s):** eCPM = bid × pCTR (× pCVR) × 1000; second-price vs first-price; floors;
   calibration.
4. **Money correctness (60 s):** collectors → Kafka → dedupe/join/fraud → budget, ledger, OLAP;
   idempotent ledger + daily reconciliation; pacing controller; bounded overspend via leases.
5. **Supporting systems (40 s):** online/offline feature store with feature logging; model
   shadowing and hot-swap; layered A/B tests with budget-split.

## 21.39 Recap

- Typeahead: precompute **top-k per trie node** offline from decayed, filtered query logs; serve
  a flattened `prefix → list` map from memory, cached in browser and CDN; trending via a
  **streaming overlay**.
- Crawler: the **frontier** combines priority (front queues) and politeness (per-host back
  queues + min-heap of next allowed time); obey **robots.txt**; dedupe URLs with a **Bloom
  filter** (~9.6 bits/URL at 1%) and content with hashes and **SimHash**; partition by host.
- Ad serving is a **funnel** (retrieve → filter → light rank → heavy score → auction), every
  stage with a deadline and fallback; the ad index is **replicated in memory**.
- **eCPM** puts all bid types on one scale; second-price charges the runner-up's eCPM converted
  back to the bid unit; first-price charges the bid and buyers shade.
- **Budgets and frequency caps are eventually consistent**; overspend ≈ spend rate × staleness,
  bounded by pacing and budget leases.
- Events → Kafka → dedupe/join/fraud → budget, ledger, OLAP, plus **daily reconciliation**:
  idempotent writes keyed by event ID make billing exact.
- **Feature logging** prevents training-serving skew; ad A/B tests must handle **shared-budget
  interference** (budget-split or campaign-level randomization).

## 21.40 Check yourself

1. Why store the top-k completions at every trie node instead of searching the subtree at query
   time?

<details><summary>Answer</summary>

For short prefixes the subtree contains millions of queries, so a DFS plus selection per
keystroke is far too slow for a ~100 ms budget at 100k+ requests/s. Precomputing top-k bottom-up
(each node merges its children's lists) makes a query O(prefix length). The cost is memory
(popular queries are referenced from many nodes), bounded by storing IDs, capping k and capping
prefix length.

</details>

2. Describe how the crawler frontier enforces both priority and politeness.

<details><summary>Answer</summary>

URLs enter front queues by priority, and a selector favors higher-priority queues. URLs are routed
to back queues so each back queue holds one host. A min-heap stores, per back queue, the earliest
time its host may be contacted again. A fetcher pops the minimum, waits until that time, fetches
one URL of that host, and re-inserts the host with a new time from an adaptive delay (respecting
Crawl-delay and backing off on 429/503).

</details>

3. Three ads compete for one slot: A is CPC $2.00 with pCTR 1%, B is CPM $15, C is CPC $0.50
   with pCTR 5%. Who wins a second-price auction and what does it pay?

<details><summary>Answer</summary>

eCPMs: A = 2.00 × 0.01 × 1000 = $20; B = $15; C = 0.50 × 0.05 × 1000 = $25. C wins. It must
keep its eCPM at the runner-up's $20, so its price per click is 20 / (0.05 × 1000) = $0.40 plus a
small increment (about $0.41), below its $0.50 bid.

</details>

4. Why not check and decrement the campaign budget in a strongly consistent database on every ad
   request? How do you bound overspend instead?

<details><summary>Answer</summary>

At ~500k requests/s with a ~100 ms budget, a synchronous transaction per request adds latency
and makes each campaign's budget row a hot key; and spend is only known when events arrive anyway. Instead servers use a cached budget view or a leased budget
slice, the pacing controller throttles participation or bids to follow a target curve, and
overspend is bounded by spend rate × staleness (or servers × slice size with shrinking leases).

</details>

5. How does the event pipeline avoid double billing when beacons are retried and consumers
   crash?

<details><summary>Answer</summary>

Every event has a unique ID (e.g. impression ID from request ID and slot) carried in the signed
tracking URL. The stream processor keeps keyed dedupe state for a window and drops repeats, and
the ledger is written with idempotent upserts keyed by event ID, so a replay rewrites the same
rows. Daily reconciliation over the raw archive produces the final numbers. At-least-once
transport plus idempotent effects gives exactly-once billing.

</details>

6. Your new ranking model wins an A/B test by 4% revenue. Why might that be misleading in an ad
   system, and how do you fix the experiment design?

<details><summary>Answer</summary>

Both arms share the same campaign budgets. If the treatment spends budgets faster, it captures
money control would otherwise have spent, and control sees exhausted campaigns, so the measured
gap overstates the true effect (it may even vanish at full rollout). Fix with budget-split
experiments (each campaign's budget divided between arms by traffic share) or randomize by
campaign/advertiser instead of by user, and watch guardrails such as advertiser ROI and latency.

</details>
