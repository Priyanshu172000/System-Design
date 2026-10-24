# 04 — Numbers every engineer should know & back-of-envelope estimation

Design choices depend on scale: 50 requests per second fits on one small server, 500,000 does
not. Estimation is how you find out which world you are in, in two minutes, with round numbers.
This chapter gives you the reference numbers, the conversion tricks, and three fully worked
estimates, plus percentiles — the language of latency.

## 04.1 Why estimate at all

### The problem

Without numbers, design discussions drift: one person wants sharding, another says one database is
fine, and nobody can settle it. Estimation turns opinions into arithmetic:

- "100 million new rows a year at 1 KB each = 100 GB a year" → one database is fine for years.
- "20 billion timeline inserts a day" → no single database can take that; we need a different
  design.

### The goal is order of magnitude, not precision

You want to know whether something is 10, 1,000, or 100,000 per second — not whether it is 1,150
or 1,170. Being within a factor of 2–3 is good enough, because architectural decisions change at
factors of 10. Round aggressively and keep moving.

> **Say it like this:** "I just want the order of magnitude, so I'll round heavily. If this comes
> out at hundreds of writes per second, one database primary handles it; if it's hundreds of
> thousands, we need to partition."

## 04.2 Latency numbers

These are rough, modern orders of magnitude (hardware varies; the ratios are what matter).

| Operation | Roughly | Lesson |
|---|---|---|
| CPU L1 cache reference | ~1 ns | the CPU is extremely fast when data is close |
| Branch mispredict | ~3–5 ns | — |
| CPU L2 cache reference | ~4 ns | — |
| Uncontended mutex lock/unlock | ~20 ns | locks are cheap uncontended, expensive under contention |
| Main memory (RAM) reference | ~100 ns | ~100x slower than L1; cache-friendly data layouts matter |
| Compress 1 KB with a fast compressor | ~2 µs | compression is cheap compared to network/disk; often worth it |
| Send 1 KB over a 10 Gbit/s network | ~1 µs | the wire is fast; the round trip is what costs |
| Read 1 MB sequentially from RAM | ~tens of µs | memory bandwidth is huge |
| Random 4 KB read from an SSD (NVMe) | ~20–100 µs | hundreds of times slower than RAM; why databases cache pages in memory |
| Round trip within the same data center | ~0.5 ms (often less) | one network hop costs as much as thousands of RAM reads; avoid chatty calls |
| Read 1 MB sequentially from an SSD | ~0.3–1 ms | SSDs love sequential reads |
| Round trip between availability zones | ~1–2 ms | cheap enough for synchronous replication |
| HDD disk seek | ~5–10 ms | spinning disks are terrible at random access; sequential layouts (logs) matter |
| Read 1 MB sequentially from an HDD | ~5–10 ms | HDDs are fine for sequential bulk (backups, archives) |
| Round trip within a continent | ~20–80 ms | users far from your servers feel it |
| Round trip across the world (e.g. US ↔ Europe ↔ Asia) | ~100–300 ms | why CDNs and multi-region exist; can't beat the speed of light |

### Visualizing the gaps

If an L1 reference took **1 second**, then:

```text
 L1 cache            1 s
 RAM                 ~2 minutes
 SSD random read     ~1 day
 Same-DC round trip  ~6 days
 HDD seek            ~4 months
 Cross-world RTT     ~5 years
```

### Lessons to carry into every design

1. **RAM is hundreds of times faster than SSD for random access, and the fastest work is work
   you skip.** Keep hot data in RAM and precompute what you can (caching, chapter 08).
2. **Network round trips dominate.** A request that makes 20 sequential calls to other services at
   0.5 ms each has spent 10 ms just on hops, before any work. Batch, parallelize, or avoid them.
3. **Sequential beats random**, especially on HDDs and still meaningfully on SSDs. That is why
   databases write append-only logs (chapter 06) and why Kafka is fast (chapter 11).
4. **Distance is physics.** Light in fiber covers roughly 200 km per millisecond. A
   10,000 km path costs at least ~50 ms one way, ~100 ms round trip, no matter the hardware.
5. **Compression is usually worth it** across networks: microseconds of CPU to save milliseconds of
   transfer.

> **Say it like this:** "A same-data-center round trip is about half a millisecond, a random SSD
> read is tens of microseconds and a RAM read is about a hundred nanoseconds — so the big wins are
> fewer network hops and keeping hot data in memory."

## 04.3 Data size units and powers of two

| Power of 2 | Exact | Approximate | Name |
|---|---|---|---|
| 2^10 | 1,024 | ~10^3 (thousand) | 1 KB (kilobyte) |
| 2^20 | 1,048,576 | ~10^6 (million) | 1 MB |
| 2^30 | | ~10^9 (billion) | 1 GB |
| 2^40 | | ~10^12 (trillion) | 1 TB |
| 2^50 | | ~10^15 | 1 PB |

For estimation, treat 1 KB = 1,000 bytes, 1 MB = 10^6 bytes, etc. The 2.4% (KB) to 12.6% (PB)
error does not matter.

Other useful powers:

- 2^8 = 256 (a byte has 256 values)
- 2^16 = 65,536 (port numbers, a 16-bit counter)
- 2^31 ≈ 2.1 billion (max signed 32-bit int — a classic auto-increment ID overflow point)
- 2^32 ≈ 4.3 billion (IPv4 addresses)
- 2^63 ≈ 9.2 x 10^18 (max signed 64-bit int — effectively never overflows for IDs)

### Typical sizes of things

| Thing | Roughly |
|---|---|
| a boolean / a byte flag | 1 B |
| an int / a long / a timestamp | 4 B / 8 B / 8 B |
| a UUID | 16 B binary, 36 B as text |
| a short text post (a few hundred chars) + metadata | ~0.5–1 KB |
| a typical database row | ~100 B – 1 KB |
| a JSON API response for one item | ~1–5 KB |
| a web page's HTML | ~50–100 KB |
| a compressed photo from a phone | ~1–3 MB (thumbnails ~10–50 KB) |
| one minute of compressed HD video | ~50–100 MB |

### Bits vs bytes

Network speeds are quoted in **bits** per second (Gbit/s, written Gbps), storage and file sizes in
**bytes**. 1 byte = 8 bits. A 1 Gbit/s link moves at most ~125 MB/s.

> **Common mistake:** Saying "we serve 10 GB/s, so a 10 Gbps network card is enough". 10 GB/s is
> 80 Gbit/s — eight times more. Always convert explicitly.

## 04.4 Time conversions

| Period | Seconds | Estimation shortcut |
|---|---|---|
| 1 minute | 60 | |
| 1 hour | 3,600 | ~3.6 x 10^3 |
| 1 day | 86,400 | **~10^5** (round up; the 15% error is fine) |
| 1 month (30 days) | ~2.6 million | ~2.5 x 10^6 |
| 1 year | ~31.5 million | ~3 x 10^7 |

The single most useful conversion:

```text
 1 million requests per day  =  10^6 / 86,400  ≈  11.6 req/s  ≈  12 QPS
                                (with the 10^5 shortcut: 10 QPS)

 So:   1M/day   ≈ 12 QPS
      10M/day   ≈ 120 QPS
     100M/day   ≈ 1,200 QPS
       1B/day   ≈ 12,000 QPS
```

**QPS** = queries (requests) per second. Also written RPS (requests per second) or TPS
(transactions per second).

Two more handy ones:

- 1 request per second, sustained, = 86,400 per day ≈ ~2.6 million per month ≈ ~30 million per
  year.
- Writes per second x seconds per year = rows per year. 100 writes/s → ~3 billion rows/year.

## 04.5 Average vs peak

Traffic is not flat. People use apps in the evening, not at 4 a.m.; sales events and breaking
news create spikes.

```text
 QPS
  ^                          peak ~ 2-3x average
  |                    ___
  |                  _/   \_
  |  average  ......../.......\.............................
  |         _/                 \__
  |   _____/                      \_____
  +--------------------------------------------------> time of day
     0h        6h       12h       18h      24h
```

- Estimate **average QPS** from daily totals.
- Multiply by a **peak factor**: roughly **2–3x** for normal daily cycles; much more (5–10x+) for
  flash sales, launches, live events. State your assumption.
- **Design capacity for peak**, plus headroom (commonly aim to run at no more than ~50–70% of
  capacity at peak), so a failed instance or an unexpected bump does not tip you over.

> **Common mistake:** Sizing servers for average load. At peak you are 2–3x over, which is exactly
> when users care most.

## 04.6 Read/write ratio

Most products read far more than they write: a post is written once and read thousands of times;
a product page is viewed far more than it is edited.

| System | Typical read:write |
|---|---|
| social feed, news site | 100:1 or more |
| URL shortener | ~100:1 |
| e-commerce catalog | very read-heavy |
| chat / messaging | closer to 1:1 (every message written once, read by a few) |
| logging, metrics, IoT ingestion | write-heavy (writes >> reads) |

Why it matters:

- **Read-heavy** → caching, read replicas, CDNs, precomputed views (chapters 07, 08).
- **Write-heavy** → append-friendly storage (log-structured engines, chapter 06), partitioning,
  batching, queues to absorb bursts.

Always state the ratio explicitly: it decides which tools you reach for.

## 04.7 Rule-of-thumb capacities

These are **rough orders of magnitude** for typical modern hardware and simple workloads. Real
numbers vary by 10x depending on request complexity, payload size, hardware, and configuration.
Use them to decide "one box or a hundred", never as promises.

| Component | Roughly, per instance | Depends heavily on |
|---|---|---|
| Stateless app server (simple JSON API calls) | ~1,000s to ~10,000 req/s | work per request, language/runtime, I/O waits |
| Relational DB primary — simple indexed point reads | ~10,000s of reads/s (much more if the working set is in memory) | query shape, indexes, RAM vs disk |
| Relational DB primary — writes | ~several thousand writes/s on decent hardware (higher with batching) | durability settings, indexes per table, row size, disk |
| In-memory key-value cache (Redis-like) | ~100,000 ops/s per node (more with pipelining) | value size, command type |
| Kafka-like log, per partition | ~tens of MB/s (often ~10–50 MB/s) | message size, batching, replication settings |
| Single SSD | ~100,000s of random IOPS, ~1–3+ GB/s sequential | device, queue depth |
| Network card | 10–25 Gbit/s common (~1–3 GB/s) | instance type |
| Concurrent open connections per server (event loop) | ~10,000s to ~100,000s+ | memory, tuning |
| One server's RAM | ~64–512 GB typical, up to several TB | instance type |

How to use them: "We need 40,000 cache reads per second. One cache node handles roughly 100,000
ops/s, so a single node would do, but I'd run a small replicated cluster for availability."

> **Say it like this:** "As a rough rule, one app server handles a few thousand simple requests a
> second and one relational primary a few thousand writes a second. Our peak is 3,000 writes per
> second, so that's at the edge of one primary — I'd plan for batching or partitioning before
> growth doubles it."

## 04.8 The estimation toolkit

Every estimate follows the same steps. Write each assumption down so others can challenge it.

### Step 1: users and actions

```text
 DAU (daily active users)  x  actions per user per day  =  actions per day
```

**DAU** = daily active users; **MAU** = monthly active users. DAU is often roughly 20–50% of MAU
for consumer apps (an assumption — state it).

### Step 2: QPS

```text
 average QPS = actions per day / 10^5           (86,400 rounded)
 peak QPS    = average QPS x peak factor (2-3x, state it)
```

Do this separately for reads and writes.

### Step 3: storage

```text
 storage per day  = new items per day x size per item
 storage per year = per day x 365   (~ x 400 for quick math)
 total            = per year x years retained x replication factor (often 3)
```

Count metadata (rows in a database) and blobs (files in object storage) separately; they live in
different systems and differ by orders of magnitude.

### Step 4: bandwidth

```text
 ingress (upload)  = write QPS x size of each write
 egress (download) = read QPS  x size of each response
 convert bytes/s -> bits/s (x 8) when comparing with network links
```

### Step 5: memory for cache (the 80/20 rule)

The **80/20 rule** (Pareto principle): a small fraction of items gets most of the traffic. A common
estimation shortcut: "about 20% of the data serves about 80% of requests, so cache the hot 20%".

```text
 cache memory ≈ 20% x (daily unique items read) x size per item
```

Then divide by usable RAM per cache node (say ~50–100 GB) to get node count, and add replicas.

### Step 6: number of servers

```text
 servers = peak QPS / QPS per server  -> then add headroom (e.g. run at <= 50-70%)
           and at least N+1 or N+2 for failures, spread across zones
```

### Rounding tactics

- Round inputs to 1, 2, or 5 times a power of ten: 86,400 → 10^5; 365 → 400 (or 3.65 → ~4 for
  quick multiplication); 2.6M s/month → 2.5 x 10^6.
- Multiply mantissas and add exponents: 2 x 10^8 x 5 x 10^2 = 10 x 10^10 = 10^11.
- Keep a running sanity check: does the answer feel plausible against the table in section 04.7?

## 04.9 Worked estimate 1: a Twitter-like feed

**Assumptions** (state each one):

- 200 million DAU.
- Each user posts 0.5 posts/day on average (most post nothing, a few post a lot).
- Each user loads their home feed 10 times/day; each load returns 20 posts.
- A post: ~300 bytes of text + ~200 bytes metadata (IDs, timestamps, counters) ≈ **500 bytes**.
- 10% of posts carry an image, ~200 KB after compression.
- Average follower count: 200.
- Peak factor: 3x.

**Writes (new posts)**

```text
 200M users x 0.5 = 100M posts/day
 100M / 10^5 s    = 1,000 posts/s average   (exact: ~1,160)
 peak (x3)        ≈ 3,000 posts/s
```

That is borderline for one relational primary, but partitionable and fine for a distributed store.

**Reads (feed loads)**

```text
 200M x 10 = 2B feed loads/day
 2 x 10^9 / 10^5 = 20,000 feed loads/s average   (exact: ~23,000)
 peak (x3) ≈ 60,000-70,000 feed loads/s
 each load returns 20 posts -> ~1.2M+ post reads/s at peak
```

Read:write ≈ 20:1 in requests, ~400:1 in posts read vs written. Strongly read-heavy: feeds must be
served from caches/precomputed timelines, not assembled by querying a database each time.

**The fan-out insight**

If we precompute each user's timeline ("fan-out on write": when you post, push the post ID into
every follower's timeline):

```text
 100M posts/day x 200 followers = 2 x 10^10 timeline inserts/day
 2 x 10^10 / 10^5 s = 200,000 inserts/s average, ~600,000/s at peak
```

That is heavy but feasible as small writes into an in-memory store spread over many nodes. A user
with 50 million followers, though, would cause 50 million inserts for one post — which is why
real designs treat celebrity accounts differently (fan-out on read for them). This is exactly the
kind of insight estimation reveals; chapter 17+ case studies explore it.

**Storage**

```text
 Text:  100M posts x 500 B = 5 x 10^10 B = 50 GB/day
        x 400 days ≈ 20 TB/year (x3 replication ≈ 60 TB/year)
 Media: 10M images x 200 KB = 2 x 10^12 B = 2 TB/day
        ≈ 700-800 TB/year  -> object storage, petabytes within a couple of years
```

Media dominates storage by ~40x. Text fits a partitioned database; media goes to object storage +
CDN.

**Bandwidth (egress)**

```text
 Feed responses: 23,000/s x 20 posts x 500 B ≈ 230 MB/s avg ≈ ~2 Gbit/s  (x3 at peak)
 Images: if each feed load shows ~2 images at 200 KB:
         23,000 x 2 x 200 KB ≈ 9 GB/s ≈ ~75 Gbit/s average
```

Image egress is huge → served by a CDN, not your app servers.

**Cache for timelines**

```text
 Store last 800 post IDs per active user: 800 x 8 B ≈ 6.4 KB per user
 200M DAU x 6.4 KB ≈ 1.3 TB
 At ~64 GB usable per cache node -> ~20 nodes, ~40 with one replica each
```

Plus a cache for the post bodies themselves (hot recent posts).

**App servers**

```text
 Peak ~70,000 feed requests/s; assume ~2,000 req/s per server (each fans out to caches)
 -> 35 servers at 100% -> ~70 servers at 50% utilization, spread over 3 zones
```

**Summary to say out loud**: "Roughly 1,000 writes/s and 20,000+ feed reads/s on average, 3x at
peak; 50 GB/day of text and 2 TB/day of images; strongly read-heavy, so precomputed timelines in a
~1–2 TB cache cluster, posts in a partitioned store, media in object storage behind a CDN. Fan-out
on write is ~200k inserts/s, but celebrities break it, so they need a hybrid."

## 04.10 Worked estimate 2: a URL shortener

**Assumptions**:

- 100 million new short URLs per month.
- Read:write = 100:1 (each link is clicked many times).
- Each record: short code (7 B), long URL (~100–200 B), owner ID, timestamps, counters → round
  to **500 bytes** with overhead.
- Keep links for 5 years.
- Peak factor: 3x.

**Writes**

```text
 100M / month / 2.5 x 10^6 s per month = 40 writes/s average
 peak ≈ 120 writes/s
```

Tiny. One database primary handles this easily.

**Reads (redirects)**

```text
 40 x 100 = 4,000 redirects/s average
 peak ≈ 12,000 redirects/s
```

**Storage**

```text
 per month: 100M x 500 B = 50 GB
 per year:  ~600 GB
 5 years:   ~3 TB  (x3 replication ≈ 9 TB raw)
 total links over 5 years: 100M x 12 x 5 = 6 x 10^9 = 6 billion
```

3 TB fits on one large database server, but you might partition by short code for headroom and
throughput. Either is defensible — say so.

**How long must the short code be?**

Using base62 (a–z, A–Z, 0–9):

```text
 62^6 ≈ 5.7 x 10^10  (57 billion)
 62^7 ≈ 3.5 x 10^12  (3.5 trillion)
 We need 6 billion. 6 chars works (57B > 6B) but is ~10% full after 5 years,
 which makes random-code collisions frequent and leaves little room for growth.
 7 chars: 6B / 3.5T ≈ 0.2% full -> random codes rarely collide. Choose 7.
```

**Cache**

```text
 Daily redirects: 4,000/s x 10^5 s ≈ 4 x 10^8 = 400M per day (exact ~350M)
 Cache the hot 20%: 0.2 x 400M x 500 B = 4 x 10^10 B = 40 GB
```

(This counts 20% of daily requests as an upper bound on hot items; many requests repeat the same
links, so unique hot items are fewer.) 40 GB fits in one cache node's memory; run two or three for
availability. With a high cache hit rate, the database sees only a few hundred reads/s.

**Bandwidth**

```text
 Redirect response ~500 B (a 301/302 with Location header):
 12,000/s x 500 B = 6 MB/s at peak  -> trivial
```

**Servers**

```text
 12,000 peak / ~4,000 req/s per server (very light requests) = 3
 -> run ~6 across zones for headroom and failures
```

**Summary**: "About 40 writes/s and 4,000 reads/s average, ~12,000 at peak. 6 billion links over
five years, ~3 TB — 7-character base62 codes. The hot set fits in a ~40 GB cache, so the database
barely sees reads. A handful of stateless servers suffices; the interesting problems are code
generation and avoiding collisions, not scale."

## 04.11 Worked estimate 3: a photo-sharing app

**Assumptions**:

- 100 million DAU.
- 0.2 photo uploads per user per day (20% of users upload one photo daily, on average).
- Average original photo after client-side compression: 2 MB.
- We also store resized versions (thumbnail + medium + large) adding ~50%: **3 MB per photo
  total**.
- Each user views 50 photos/day, mostly at feed size ~200 KB.
- Metadata per photo (owner, caption, location, timestamps, storage keys): ~1 KB.
- Peak factor: 3x.

**Uploads**

```text
 100M x 0.2 = 20M photos/day
 20M / 10^5 = 200 uploads/s average  (exact ~230)
 peak ≈ 600-700 uploads/s
```

**Upload bandwidth**

```text
 230/s x 2 MB = 460 MB/s ≈ 3.7 Gbit/s average
 peak ≈ 1.4 GB/s ≈ 11 Gbit/s
```

Significant: uploads should go straight to object storage (via pre-signed upload URLs, chapter 09)
rather than streaming through app servers.

**Storage**

```text
 Blobs:    20M x 3 MB = 6 x 10^13 B = 60 TB/day
           x 365 ≈ 22 PB/year (before the storage system's own redundancy)
 Metadata: 20M x 1 KB = 20 GB/day ≈ 7 TB/year -> partitioned database within ~2 years
```

Petabytes per year: only object storage makes sense, with tiering (older, rarely viewed photos to
cheaper storage classes). This is the dominant cost of the system.

**Views and egress**

```text
 100M x 50 = 5B views/day
 5 x 10^9 / 10^5 = 50,000 views/s average (exact ~58,000)
 peak ≈ 150,000-175,000 views/s
 egress: 58,000 x 200 KB ≈ 11.6 GB/s ≈ ~90 Gbit/s average, ~280 Gbit/s at peak
```

That can only be served by a CDN. Your origin should see only CDN misses.

**Metadata reads**

Each feed load (say 10 photos per load → 500M loads/day ≈ 5,000–6,000/s, ~17k at peak) reads
metadata for 10 photos. Cache recent and popular photo metadata; the database handles the rest
with replicas.

**Cache sizing for metadata**

```text
 Hot set: photos from the last ~week + popular ones.
 7 days x 20M = 140M photos x 1 KB = 140 GB -> a few cache nodes
```

**Summary**: "About 200 uploads/s and 50,000+ photo views/s on average; 60 TB of new blobs per day,
~22 PB per year, so object storage with tiering is the core. Egress around 100 Gbit/s means a CDN
is mandatory. Uploads go directly to object storage, metadata in a partitioned database with a
~150 GB cache for the recent hot set."

## 04.12 Presenting an estimate in a conversation

How you present matters as much as the numbers.

1. **Ask or state the inputs first**: "Let me assume 100M DAU and 0.2 uploads per user per day —
   does that sound right?" This invites correction early.
2. **Write assumptions where everyone can see them** (whiteboard, doc), then calculate.
3. **Round aggressively and say so**: "A day is about 10^5 seconds."
4. **Separate reads from writes, metadata from blobs, average from peak.**
5. **Sanity-check against known capacities**: "40 writes/s is nothing for one database."
6. **Draw the conclusion** — the reason you estimated: "So storage, not QPS, is the hard part;
   that's what the design should focus on."
7. **Do not over-invest**: two to five minutes in an interview; enough to choose the architecture.

> **Say it like this:** "I'll assume 100 million daily users and round a day to 10^5 seconds.
> That gives about 1,000 writes per second on average and maybe 3,000 at peak, which tells me the
> write path needs partitioning eventually but the real pressure is the much larger read load, so
> caching is the priority."

> **Common mistake:** Producing precise-looking numbers ("11,574 QPS") and then not using them.
> The estimate exists to drive a decision; always end with "which means...".

## 04.13 Percentiles: why averages lie

### The problem

"Average latency is 50 ms" sounds healthy. But latency distributions have long tails: most
requests are fast, a few are very slow (GC pauses, cache misses, a slow disk, a lock wait, a
retry). The average hides them.

Example: 100 requests, 98 take 10 ms, 2 take 2,000 ms.

```text
 average = (98 x 10 + 2 x 2000) / 100 = (980 + 4000) / 100 = 49.8 ms   "looks fine"
 median  = 10 ms
 p99     = 2,000 ms   -> 1 in 50 users waited 2 seconds
```

The average (49.8 ms) describes **nobody's** actual experience: no request took ~50 ms.

### Percentiles

The **pN** latency (Nth **percentile**) is the value below which N% of requests fall.

- **p50** (median): the typical request. Half are faster, half slower.
- **p95**: 1 in 20 requests is slower than this.
- **p99**: 1 in 100 requests is slower than this.
- **p99.9**: 1 in 1,000.

DSA bridge: sort the latencies; p99 is the element at index ~0.99 x n. In production, systems use
histograms or streaming sketches (approximate quantile structures) since storing and sorting every
sample is too expensive. And note: you cannot average percentiles across servers — the average of
ten servers' p99s is not the fleet's p99; you merge the histograms.

### Why the tail matters

- **Heavy users hit the tail more often.** A page that makes 50 independent requests has about a
  40% chance (1 - 0.99^50) of hitting at least one p99-slow request on every view.
- **The slowest requests are often your most valuable** (the users with the most data, the biggest
  carts).
- **SLOs are written as percentiles**: "p99 < 300 ms" (chapter 14).

### Tail latency amplification with fan-out

Modern requests fan out: one page load calls many services in parallel, or one search query asks
100 index shards and waits for **all** of them. The response is only as fast as the **slowest**
call.

Suppose each backend call is slow (above its own p99) with probability 1% — independently. With a
fan-out of N parallel calls:

```text
 P(no call is slow)        = 0.99^N
 P(at least one is slow)   = 1 - 0.99^N

 N = 1     : 1 - 0.99        = 1%
 N = 10    : 1 - 0.99^10     = 1 - 0.904 ≈ 10%
 N = 100   : 1 - 0.99^100    = 1 - 0.366 ≈ 63%
```

With 100-way fan-out, **63% of user requests** see at least one backend's p99 latency. The
backend's p99 has become the user's *typical* experience. A rare event at the leaf becomes the
common case at the root.

```text
                 user request
                      |
        +------+------+------+-------+ ... +------+
        |      |      |      |       |     |      |
      shard  shard  shard  shard   shard  shard  shard   (100 shards)
       5ms    6ms    5ms   480ms    5ms    7ms    6ms
                             ^
                 the response waits for this one -> ~480 ms
```

How systems fight it (more in chapter 13):

- **Reduce fan-out** where possible (fewer, larger calls; better partitioning).
- **Hedged requests**: if a call has not returned by, say, the p95 time, send a duplicate to
  another replica and use whichever answers first. Costs a few percent extra load, cuts the tail
  sharply.
- **Timeouts and partial results**: for search or feeds, return what came back in time ("results
  from 98 of 100 shards") instead of waiting for stragglers.
- **Fix the tail causes**: GC pauses, noisy neighbors, background compactions, queueing.

> **Say it like this:** "I'd track p50, p95 and p99, not averages. With fan-out the tail
> compounds — if each of 100 shards is slow 1% of the time, about 63% of requests hit at least one
> slow shard — so I'd add hedged requests or return partial results on timeout."

> **Common mistake:** Setting an SLO on average latency, or tuning only p50. Users and fan-out
> systems feel the tail; p99 is usually the number to watch and alert on.

## 04.14 Misconceptions worth clearing up

- **"Estimates must be accurate."** They must be the right order of magnitude and clearly
  assumed. A factor of 2 error rarely changes a design.
- **"Peak = average."** Design for peak (2–3x or more) with headroom.
- **"Bandwidth numbers are in bytes."** Network links are in bits; multiply bytes by 8.
- **"Rule-of-thumb capacities are guarantees."** They are rough; one complex query can turn
  "thousands per second" into "tens per second". Benchmark when it matters.
- **"If the average is fine, users are fine."** The tail is where users suffer, and fan-out makes
  the tail common.
- **"Storage is the database."** Blobs (photos, video) dwarf metadata by orders of magnitude and
  belong in object storage.

## 04.15 Recap

- Estimate for order of magnitude; round aggressively; architecture changes at factors of 10.
- Latency ladder: L1 ~1 ns, RAM ~100 ns, SSD random read ~tens of µs, same-DC round trip ~0.5 ms,
  HDD seek ~5–10 ms, cross-world round trip ~100–300 ms. Fewer hops and in-memory data win.
- 1 day ≈ 10^5 seconds; 1M requests/day ≈ 12 QPS; 1 year ≈ 3 x 10^7 s.
- Design for peak (2–3x average, more for events) with headroom.
- State the read:write ratio; it decides between caching/replicas and write-optimized storage.
- Rough capacities: app server ~thousands req/s, relational primary ~several thousand writes/s,
  in-memory cache node ~100k ops/s, a log partition ~tens of MB/s.
- Estimation steps: users → actions → QPS (avg, peak) → storage (metadata vs blobs) → bandwidth
  (convert to bits) → cache (80/20) → servers (with headroom).
- Always finish with the decision the numbers imply.
- Use percentiles (p50/p95/p99), not averages; averages hide the tail.
- Fan-out amplifies tails: 1 - 0.99^100 ≈ 63% of requests hit a 1%-slow call; mitigate with
  hedging, timeouts and partial results.

## 04.16 Check yourself

1. A service receives 50 million requests per day. What are its average and likely peak QPS?

<details><summary>Answer</summary>

50M / 86,400 ≈ 580 QPS (with the 10^5 shortcut, 500). With a 2–3x peak factor, roughly
1,200–1,700 QPS at peak. Design capacity for the peak plus headroom.

</details>

2. You must store 500 million new rows per year at 2 KB each for 3 years, replicated 3 times. How
   much raw storage is that?

<details><summary>Answer</summary>

500M x 2 KB = 10^12 B = 1 TB per year. Three years = 3 TB. Times 3 replicas = 9 TB raw (plus
index overhead, often substantial). Large but manageable for a partitioned or large single
database setup.

</details>

3. Your service sends 2 GB/s of responses. Is a server with a 10 Gbit/s network card enough?

<details><summary>Answer</summary>

2 GB/s = 16 Gbit/s, more than 10 Gbit/s. One such server is not enough even at 100% link use; you
need several servers (and realistic headroom), or move the heavy content to a CDN.

</details>

4. Why can't you get a short code space for 6 billion URLs with 5 base62 characters, and why
   choose 7 over 6?

<details><summary>Answer</summary>

62^5 ≈ 916 million, less than 6 billion. 62^6 ≈ 57 billion is enough in principle, but it would be
about 10% full, making randomly generated codes collide often and leaving little room for growth.
62^7 ≈ 3.5 trillion keeps occupancy around 0.2%, so collisions are rare.

</details>

5. Each of 100 shards answers within 20 ms 99% of the time, and occasionally takes 1 s. A query
   must wait for all shards. Roughly what fraction of queries take about 1 s? What would you do?

<details><summary>Answer</summary>

P(at least one slow shard) = 1 - 0.99^100 ≈ 63%. So most queries are slow even though each shard
is slow only 1% of the time. Mitigations: hedged requests to replicas after a short delay, return
partial results after a timeout, reduce fan-out, and investigate the cause of the shard-level tail
(GC, compaction, hot spots).

</details>

6. Average latency is 40 ms and the team says "we're fine". Why might users still be unhappy, and
   what would you measure instead?

<details><summary>Answer</summary>

The average can hide a long tail: a small percentage of requests may take seconds while most take
a few ms. Heavy users and pages making many requests hit the tail frequently. Measure p50, p95,
p99 (and p99.9 for critical paths) via histograms, and set SLOs on percentiles.

</details>

7. A cache must hold the hot 20% of 200 million daily-accessed items of 1 KB each. How much memory
   and roughly how many nodes?

<details><summary>Answer</summary>

0.2 x 200M x 1 KB = 40 GB. That fits on one node with ~64 GB usable RAM, but you would run at
least two or three (replicas) for availability, or shard across a few nodes for throughput and
growth.

</details>

8. An estimate shows 100 writes/s and 50,000 reads/s. What does that tell you about the design?

<details><summary>Answer</summary>

Writes are trivial for a single database primary; the system is extremely read-heavy (500:1). Focus
on the read path: caching (high hit rate), read replicas, CDN for cacheable responses, and
precomputed views. Sharding for writes is unnecessary.

</details>
