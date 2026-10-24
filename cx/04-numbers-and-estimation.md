# 04 — Numbers and back-of-envelope estimation

*Why it matters:* 50 req/s fits on one small server and 500,000 does not; a two-minute estimate with round numbers tells you which world you are in and settles design arguments.

## Why estimate at all

**How it works** · Without numbers, "we need sharding" vs "one DB is fine" can't be settled. Estimation turns opinions into arithmetic. Aim for the **order of magnitude**, not precision: within 2–3x is fine because architecture changes at factors of 10. Round aggressively and keep moving.

**Example** · Someone says "QuickBite's orders table needs sharding now."
1. 2 M orders/day x ~1 KB per order (row + items + payment) = 2 GB/day.
2. x 400 days ≈ 800 GB/year; writes ≈ 23/s average, ~120/s at peak.
3. One relational primary handles several thousand writes/s and a few TB.

So: one primary plus replicas is fine for years; the argument is over. Compare "20 billion timeline inserts/day": no single DB can take that.

## Latency numbers

**How it works** · Rough modern orders of magnitude; the ratios are what matter.

| Operation | Roughly | Lesson |
|---|---|---|
| L1 cache reference | ~1 ns | CPU is very fast when data is close |
| Uncontended mutex lock/unlock | ~20 ns | cheap uncontended, expensive under contention |
| RAM reference | ~100 ns | ~100x L1; cache-friendly layouts matter |
| Compress 1 KB (fast compressor) | ~2 µs | cheap vs network/disk, often worth it |
| Send 1 KB over 10 Gbit/s | ~1 µs | the wire is fast; round trips cost |
| Random 4 KB SSD (NVMe) read | ~20–100 µs | hundreds of times slower than RAM: DBs cache pages |
| Same-DC round trip | ~0.5 ms | one hop = thousands of RAM reads; avoid chatty calls |
| Read 1 MB sequentially from SSD | ~0.3–1 ms | SSDs love sequential |
| Round trip between AZs | ~1–2 ms | cheap enough for synchronous replication |
| HDD seek / 1 MB sequential from HDD | ~5–10 ms / ~5–10 ms | terrible at random, fine for sequential bulk |
| Round trip within a continent | ~20–80 ms | far users feel it |
| Round trip across the world | ~100–300 ms | why CDNs and multi-region exist |

(The full chapter also lists branch mispredict ~3–5 ns, L2 ~4 ns, 1 MB sequential from RAM ~tens of µs.) Scale L1 to 1 second and RAM is ~2 minutes, SSD random read ~1 day, same-DC round trip ~6 days, HDD seek ~4 months, cross-world RTT ~5 years.

**Example** · Where Asha's menu load for `r_77` spends time:
1. Menu in the cache's RAM: ~100 ns to read, plus one same-DC hop ~0.5 ms. Same menu from an SSD-backed DB page miss: ~100 µs more.
2. A careless handler making 20 sequential service calls: 20 x 0.5 ms = 10 ms of pure hops before any work. Batch or parallelize.
3. Bengaluru -> Mumbai region, ~1,000 km of fiber at ~200 km per ms: at least ~5 ms one way, ~10 ms round trip. A US region ~13,000 km away: at least ~65 ms one way, ~130 ms round trip, per round trip, forever.

So: (1) keep hot data in RAM and skip work by precomputing (chapter 08); (2) **network round trips dominate**; (3) **sequential beats random** (append-only logs in databases, Kafka; chapters 06, 11); (4) **distance is physics**; (5) compression over networks is usually worth it.

> **Say it like this:** "A same-data-center round trip is about half a millisecond, a random SSD read tens of microseconds, a RAM read about a hundred nanoseconds, so the big wins are fewer network hops and keeping hot data in memory."

## Data sizes and powers of two

**How it works** · 2^10 ≈ 10^3 (KB), 2^20 ≈ 10^6 (MB), 2^30 ≈ 10^9 (GB), 2^40 ≈ 10^12 (TB), 2^50 ≈ 10^15 (PB); treat 1 KB = 1,000 B, the 2.4%–12.6% error doesn't matter. Also: 2^8 = 256; 2^16 = 65,536 (ports); 2^31 ≈ 2.1 billion (max signed 32-bit, classic auto-increment overflow); 2^32 ≈ 4.3 billion (IPv4); 2^63 ≈ 9.2 x 10^18 (64-bit IDs effectively never overflow).

| Thing | Roughly |
|---|---|
| boolean / int / long / timestamp | 1 B / 4 B / 8 B / 8 B |
| UUID | 16 B binary, 36 B text |
| short text post + metadata | ~0.5–1 KB |
| database row | ~100 B – 1 KB |
| JSON response for one item | ~1–5 KB |
| web page HTML | ~50–100 KB |
| compressed phone photo | ~1–3 MB (thumbnail ~10–50 KB) |
| 1 minute of compressed HD video | ~50–100 MB |

**Example** · Sizing one QuickBite order:

```text
 orders row: order_id, user_id, restaurant_id, rider_id (4 x 8 B), status (1 B),
             amount (8 B), created_at, updated_at (2 x 8 B)       ≈ 60 B of fixed fields
             + delivery address, notes                            ≈ 200 B
 order_items: ~3 rows x ~100 B                                    ≈ 300 B
 payments row                                                     ≈ 200 B
 + row and index overhead -> round to ~1 KB per order
 2 M orders/day: if order_id were a signed 32-bit int, 2.1 billion runs out in ~3 years
```

So: round each piece, add overhead, then round the total; use 64-bit IDs. **Bits vs bytes**: network speeds are in bits/s, sizes in bytes; 1 Gbit/s ≈ 125 MB/s max.

> **Common mistake:** "We serve 10 GB/s, so a 10 Gbps card is enough." 10 GB/s = 80 Gbit/s, eight times more. Always convert explicitly.

## Time conversions

**How it works** · A day is about 10^5 seconds; that one shortcut does most of the work.

| Period | Seconds | Shortcut |
|---|---|---|
| 1 hour | 3,600 | ~3.6 x 10^3 |
| 1 day | 86,400 | **~10^5** |
| 1 month (30 days) | ~2.6 million | ~2.5 x 10^6 |
| 1 year | ~31.5 million | ~3 x 10^7 |

```text
 1M requests/day ≈ 12 QPS   (10^6 / 86,400 ≈ 11.6; with 10^5 shortcut: 10)
 10M/day ≈ 120 QPS   100M/day ≈ 1,200 QPS   1B/day ≈ 12,000 QPS
 1 req/s sustained = 86,400/day ≈ 2.6M/month ≈ 30M/year
 100 writes/s x 3 x 10^7 s/year ≈ 3 billion rows/year
```

**Example** · QuickBite's 2 M orders/day = 2 x 10^6 / 86,400 ≈ 23 orders/s (shortcut: 20/s). The other way round: rider pings at 25,000/s, if sustained all day, would be 25,000 x 86,400 ≈ 2.2 billion/day. So: convert in both directions without a calculator. **QPS** = queries per second (also RPS, TPS).

## Average vs peak, read/write ratio

**How it works** · Traffic follows daily cycles and spikes. Compute **average QPS** from daily totals, multiply by a **peak factor**: roughly **2–3x** for normal cycles, 5–10x+ for flash sales, launches, live events (state it). **Design for peak** plus headroom (run at no more than ~50–70% of capacity at peak) so one failed instance or a bump doesn't tip you over.

**Example** · QuickBite orders average ≈ 23/s but the 8–9 pm dinner peak is ≈ 120/s, a peak factor of about 5x (meal times concentrate demand more than a social app's 2–3x).
1. Menu reads sized on average: 100 x 23 ≈ 2,300 reads/s -> 2 servers at ~2,000 req/s each look "enough".
2. At 8 pm: 100 x 120 = 12,000 reads/s, five times over, exactly when users care most.
3. Sized for peak at <= 60% utilisation: 12,000 / 0.6 = 20,000 req/s of capacity.

So: always ask "what does the busiest hour look like?"

**Read:write ratio**: social feed / news 100:1 or more; URL shortener ~100:1; e-commerce catalog very read-heavy; chat closer to 1:1; logging/metrics/IoT write-heavy. QuickBite has both: menus ~100:1 (cache, replicas, CDN, precomputed views; chapters 07, 08) and rider pings almost pure writes (append-friendly storage, chapter 06; partitioning, batching, queues for bursts). Always state the ratio.

## Rule-of-thumb capacities

**How it works** · Rough orders of magnitude for simple workloads; real numbers vary 10x with request complexity, payload, hardware. Use them for "one box or a hundred", never as promises; one complex query turns thousands/s into tens/s.

| Component (per instance) | Roughly |
|---|---|
| stateless app server, simple JSON API | ~1,000s to ~10,000 req/s |
| relational primary, indexed point reads | ~10,000s reads/s (more if working set in memory) |
| relational primary, writes | ~several thousand/s (higher with batching) |
| in-memory KV cache node | ~100,000 ops/s (more with pipelining) |
| Kafka-like log partition | ~tens of MB/s (often ~10–50 MB/s) |
| single SSD | ~100,000s random IOPS, ~1–3+ GB/s sequential |
| network card | 10–25 Gbit/s common (~1–3 GB/s) |
| open connections per server (event loop) | ~10,000s to ~100,000s+ |
| server RAM | ~64–512 GB typical, up to several TB |

**Example** · Three QuickBite checks against the table:

| Load | Compare | Verdict |
|---|---|---|
| 120 order writes/s | primary: several thousand writes/s | one primary, lots of room |
| 25,000 rider pings/s | primary: several thousand writes/s | too many for one relational primary |
| same 25,000/s as "latest position" | cache node: ~100,000 ops/s | one in-memory node (run replicated) |

So: the numbers point each workload at the right kind of store.

## The estimation toolkit

**How it works** · Same steps every time; write every assumption down.

```text
 1. actions/day   = DAU x actions per user per day      (DAU often ~20-50% of MAU)
 2. avg QPS       = actions/day / 10^5 ; peak = avg x 2-3   (reads and writes separately)
 3. storage/day   = new items x size ; /year ≈ x 400 ; total = x years x replication (often 3)
                    metadata (DB rows) and blobs (object storage) separately
 4. ingress       = write QPS x write size ; egress = read QPS x response size ; x 8 for bits
 5. cache memory  ≈ 20% x daily unique items read x item size   (80/20 rule)
                    / ~50-100 GB usable per node, then add replicas
 6. servers       = peak QPS / QPS per server, run at <= 50-70%, N+1 or N+2, across zones
```

**80/20 rule** (Pareto): about 20% of data serves about 80% of requests, so cache the hot 20%. Rounding: inputs to 1, 2 or 5 x a power of ten (86,400 -> 10^5, 365 -> 400, 2.6M -> 2.5 x 10^6); multiply mantissas, add exponents (2 x 10^8 x 5 x 10^2 = 10^11); sanity-check against the capacity table.

**Example** · Steps 1–2 with rounding: 5 M DAU x 0.4 orders each = 2 x 10^6 orders/day; 2 x 10^6 / 10^5 = 20/s (exact 23); views at 100:1 = 2 x 10^8/day / 10^5 = 2,000/s (exact ~2,300). The full run is next.

## Worked estimate: QuickBite (in full)

**How it works** · Inputs: 20 M registered users, 5 M DAU; 50,000 restaurants; 2 M orders/day (≈ 23/s average, ≈ 120/s at the 8–9 pm peak); menu/restaurant page views ≈ 100:1 to orders; rider pings every 4 s from ~100,000 active riders at peak. Added assumptions (state them): ~1 KB per order; ~100 dishes per restaurant at ~1 KB each; a menu response ~20 KB; ~10 dish thumbnails per menu view at ~30 KB; a ping ~100 B; over a day riders average about a third of peak; an order is live ~40 min.

**Example** · The full estimate, one line per step:

```text
 Orders:  2M/day / 10^5 ≈ 20/s (exact 23), ~120/s peak             -> one primary, easy
          2M x 1 KB = 2 GB/day x 400 ≈ 800 GB/yr (x3 replicas ≈ 2.4 TB/yr)
 Menus:   2M x 100 = 200M views/day ≈ 2,300/s avg, 100 x 120 = 12,000/s peak
          catalog 50,000 x 100 x 1 KB = 5 GB -> the WHOLE catalog fits in one cache node
          JSON egress 12,000 x 20 KB = 240 MB/s ≈ 2 Gbit/s peak
          images 12,000 x 10 x 30 KB = 3.6 GB/s ≈ 29 Gbit/s peak       -> CDN, not app servers
 Pings:   100,000 / 4 s = 25,000 writes/s peak; ~1/3 of that all day ≈ 8,000/s
          ≈ 8,000 x 10^5 = 8 x 10^8 pings/day x 100 B ≈ 80 GB/day (30 days ≈ 2.4 TB)
          latest position only: 100,000 x 100 B = 10 MB of RAM
          as a stream: 25,000 x 100 B = 2.5 MB/s -> fits one log partition's bandwidth
 Tracking: Little's Law 120 orders/s x 2,400 s live ≈ 290,000 live orders
          -> up to ~290k open SSE/WebSocket connections -> a few event-loop servers
 Servers: menu 12,000 / ~2,000 per server = 6 at 100% -> ~12 at 50%, over 3 zones
          rider-service 25,000 / ~5,000 tiny writes per server = 5 -> ~10 at 50%
 Users:   20M x 1 KB = 20 GB -> trivial
```

Reading it: order writes (120/s, < 1 TB/year) are **not** the scale problem; sharding orders by `user_id` vs `restaurant_id` is a later, data-size decision (and a hot restaurant at peak argues against `restaurant_id`). The read path is ~100:1, and the entire 5 GB menu catalog fits in RAM, so a replicated cache serves 12,000/s and the DB sees misses only. Dish images (~29 Gbit/s at peak) go through a CDN. The real write pressure is **rider pings**: 25,000/s is several times one relational primary, so keep the latest position in an in-memory store (10 MB!) and send the history through a log into a time-series store. The 5x dinner peak, not the average, sizes everything.

> **Say it like this:** "About 23 orders a second, 120 at the dinner peak, under a terabyte a year: one primary with replicas. Menus are 100:1 reads, 12,000 a second at peak, but the whole catalog is 5 GB, so it lives in cache and images go via a CDN. The hard part is 25,000 rider pings a second: latest position in memory, history through a log into a time-series store."

## Worked estimates: feed, URL shortener, photo sharing

**How it works** · Same toolkit, compact.

| | Twitter-like feed | URL shortener | Photo-sharing app |
|---|---|---|---|
| Assumptions | 200M DAU, 0.5 posts/user/day, 10 feed loads of 20 posts, 500 B/post, 10% with ~200 KB image, 200 followers, peak 3x | 100M new URLs/month, 100:1 reads, 500 B/record, keep 5 years, peak 3x | 100M DAU, 0.2 uploads/user/day, 2 MB original + resizes = 3 MB/photo, 50 views/user/day at ~200 KB, 1 KB metadata, peak 3x |
| Writes | 100M posts/day = 1,000/s (exact ~1,160), ~3,000/s peak | 100M / 2.5 x 10^6 s = 40/s, ~120/s peak: tiny | 20M photos/day = 200/s (exact ~230), 600–700/s peak |
| Reads | 2B loads/day = 20,000/s (exact ~23,000), 60–70k/s peak; x 20 posts -> ~1.2M+ post reads/s | 4,000 redirects/s, ~12,000 peak | 5B views/day = 50,000/s (exact ~58,000), 150–175k peak |
| Storage | text 50 GB/day ≈ 20 TB/yr (x3 ≈ 60 TB); media 2 TB/day ≈ 700–800 TB/yr | 50 GB/month, ~600 GB/yr, ~3 TB in 5 years (x3 ≈ 9 TB raw); 6 billion links | blobs 60 TB/day ≈ 22 PB/yr; metadata 20 GB/day ≈ 7 TB/yr (partition within ~2 years) |
| Bandwidth | feed JSON 23,000 x 20 x 500 B ≈ 230 MB/s ≈ 2 Gbit/s; images 23,000 x 2 x 200 KB ≈ 9 GB/s ≈ 75 Gbit/s | 12,000 x 500 B = 6 MB/s peak: trivial | upload 460 MB/s ≈ 3.7 Gbit/s (peak ~11); egress ≈ 90 Gbit/s avg, ~280 peak |
| Cache | 800 IDs x 8 B ≈ 6.4 KB/user x 200M ≈ 1.3 TB -> ~20 nodes at 64 GB, ~40 with replicas | ~400M redirects/day (exact ~350M) x 20% x 500 B = 40 GB, one node, run 2–3 | last week: 7 x 20M x 1 KB = 140 GB metadata, a few nodes |
| Servers / other | 70,000 peak / ~2,000 per server = 35 -> ~70 at 50%, 3 zones; fan-out on write 100M x 200 = 2 x 10^10/day = 200,000 inserts/s (~600k peak) | 12,000 / ~4,000 per server = 3 -> ~6 across zones | feed loads ~500M/day ≈ 5–6k/s (~17k peak), 10 photos' metadata each |
| Conclusion | read-heavy (~20:1 requests, ~400:1 posts): precomputed timelines; media ~40x text -> object storage + CDN; a 50M-follower post = 50M inserts, so celebrities get **fan-out on read** (hybrid) | 3 TB fits one big DB, partitioning by short code also defensible; problem is code generation and collisions, not scale | object storage with tiering is the core cost; CDN mandatory; uploads direct to object storage via pre-signed URLs (chapter 09) |

**Example** · Short code length for 6 billion links (base62): 62^5 ≈ 916 million (too few); 62^6 ≈ 57 billion (6B links = ~10% full: frequent random collisions, little growth room); 62^7 ≈ 3.5 trillion (~0.2% full): choose **7**. The 40 GB shortener cache counts 20% of daily *requests*, an upper bound since many requests repeat links. So: the estimate picks the design parameter, not just the box count.

## Presenting an estimate

**How it works** · (1) state or ask the inputs first; (2) write assumptions where everyone sees them; (3) round aggressively and say so; (4) separate reads/writes, metadata/blobs, average/peak; (5) sanity-check against capacities; (6) **draw the conclusion**; (7) spend 2–5 minutes, enough to choose the architecture.

**Example** · "Let me assume 5 M daily users and 2 M orders a day, dinner peak about 5x; does that sound right? A day is about 10^5 seconds, so ~20 orders a second, ~120 at peak. That's nothing for one database, so the order path doesn't need sharding. Menus at 100:1 are 12,000 reads a second, but the catalog is 5 GB, so it's a cache problem. Which means the design effort goes into the menu cache, the CDN and the rider-ping write path." So: every number ends in "which means...". Precise-looking numbers ("11,574 QPS") that drive no decision are wasted.

## Percentiles and fan-out tail amplification

**How it works** · Latency has a long tail (GC pauses, cache misses, slow disk, lock waits, retries) and the average hides it. **pN** = value below which N% of requests fall: **p50** typical, **p95** 1 in 20 slower, **p99** 1 in 100, **p99.9** 1 in 1,000. Conceptually sort and take index ~0.99 x n; production uses histograms or streaming sketches. You **cannot average percentiles** across servers; merge histograms.

**Example** · 100 "Place order" calls: 98 take 10 ms, 2 take 2,000 ms (payment retries).

```text
 average = (98 x 10 + 2 x 2,000) / 100 = (980 + 4,000) / 100 = 49.8 ms   "looks fine"
 p50     = 10 ms
 p99     = 2,000 ms    -> 1 in 50 customers waited 2 s, at checkout
```

So: the average describes nobody's experience. The tail matters: heavy users hit it more, the slowest requests are often the most valuable (biggest carts), SLOs are percentiles ("p99 < 300 ms", chapter 14).

**Fan-out**: if a request waits for **all** of N parallel calls, each independently slow 1% of the time:

```text
 P(at least one slow) = 1 - 0.99^N
 N = 1   -> 1%      N = 10  -> 1 - 0.904 ≈ 10%      N = 100 -> 1 - 0.366 ≈ 63%

 user request -> 100 shards: 5ms 6ms 5ms [480ms] 5ms 7ms ... -> response ≈ 480 ms
```

**Example** · "biryani near me" in `search-service` asks 20 index shards and waits for all: 1 - 0.99^20 ≈ 1 - 0.818 ≈ 18% of searches see some shard's p99. A page making 50 requests has ~40% (1 - 0.99^50) chance of a p99-slow one. So: the leaf's p99 becomes the root's *typical* experience. Fixes (chapter 13): **reduce fan-out** (fewer larger calls, better partitioning); **hedged requests** (if no reply by ~p95, send a duplicate to another replica, take the first; a few % extra load, much shorter tail); **timeouts + partial results** ("98 of 100 shards"); **fix causes** (GC, noisy neighbors, compactions, queueing).

> **Common mistake:** setting an SLO on average latency. Users and fan-out systems feel the tail; p99 is the number to watch and alert on.

## In one minute

- Estimate for order of magnitude; round hard; architecture changes at factors of 10.
- Ladder: L1 ~1 ns, RAM ~100 ns, SSD random ~tens of µs, same-DC RTT ~0.5 ms, HDD seek ~5–10 ms, cross-world ~100–300 ms: fewer hops, data in memory.
- 1 day ≈ 10^5 s, 1M/day ≈ 12 QPS, 1 year ≈ 3 x 10^7 s; network in bits, multiply bytes by 8.
- Design for peak (2–3x normally, ~5x for QuickBite's dinner hour, more for events) with headroom; state the read:write ratio.
- Capacities: app server ~thousands req/s, relational primary ~several thousand writes/s, cache node ~100k ops/s, log partition ~tens of MB/s.
- Toolkit: users -> actions -> QPS -> storage (metadata vs blobs) -> bandwidth -> cache (80/20) -> servers; always end with the decision (QuickBite: orders easy, menus = cache + CDN, rider pings = the write problem).
- Use p50/p95/p99, not averages; fan-out of 100 makes a 1% tail hit ~63% of requests: hedge, time out, return partial results.

## Go deeper

Full chapter 04: sections 04.2 (latency numbers), 04.8 (estimation toolkit), 04.9 (feed estimate), 04.13 (percentiles and fan-out)
