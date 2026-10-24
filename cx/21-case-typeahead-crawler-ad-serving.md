# 21 — Case study: typeahead, web crawler, ad serving

*Why it matters:* typeahead = read-only, latency-bound, built offline; crawler = a polite endless
fetch loop; ad serving = indexes, KV, ML, streaming and consistency trade-offs in ~100 ms.

## Typeahead: requirements and numbers

**How it works** · the server work is trivial; the network and caching decide everything.
- **Functional:** top 5-10 completions per prefix by popularity + recency, trending in minutes, filtered.
- **Non-functional:** < 100 ms perceived, server p99 ~10-20 ms, degradable, stale is fine.

| Quantity (1B searches/day, ~4 requests each after debounce, top ~100M queries) | Value |
|---|---|
| Requests | 4B/day, ~46k/s avg, ~140k/s peak (browser + CDN absorb much) |
| Distinct prefixes | ~500M (shared prefixes keep it far below 100M x 20) |
| Size | top-10 IDs ~100-150 B per prefix; ~60 GB + ~3 GB strings: fits in RAM |

API: `GET /v1/suggest?q=how%20to%20ma&locale=en-US&limit=8` + `Cache-Control: max-age=300`: tiny,
identical for everyone in a locale, so **CDN- and browser-cacheable** (personalized = separate call).

**Example** · 500M prefixes x ~120 B = ~60 GB: one big box holds it, so replicate and cache.

## Trie with top-k per node

**How it works** · a **trie** node = a prefix, reached in O(|p|). A subtree DFS per keystroke is
too slow (millions of queries under `"a"`), so **store top-k at every node**, built post-order:
best k of its own query and its children's lists (heap merge), O(nodes x k log k). Store **query
IDs**, cap k and prefix length. **Flatten** to a KV map `prefix -> [ids]`: trie = **build-time**,
flat map = **serve-time**.

**Example** · the user types "bir" (en-IN).

```text
offline: "biry" [biryani near me 3.1M, biryani recipe 1.9M]; "birl" [birla 0.9M];
         "birt" [birthday wishes 4.0M, birthday cake 1.1M] -> "bir" = best 3 =
         [birthday wishes, biryani near me, biryani recipe]
online:  b, i, r typed within 100 ms -> debounce sends only q=bir
  1 browser cache miss -> CDN edge (bir, en-IN) miss -> suggest svc
  2 map["bir"] -> [id_412, id_88, id_903] -> strings, < 1 ms
  3 200 + max-age=300 -> CDN + browser keep it 5 min; the next "bir" anywhere = edge hit
```
So: one lookup per keystroke, however many queries start with "bir".

## Typeahead: build, serve, trending

**How it works** · online: browser (debounce, cache) -> CDN edge -> suggest svc x N (RAM replica).
Offline: logs -> Kafka -> lake -> daily snapshot, + streaming trending overlay and denylist.
**Daily batch:** normalize -> filter (blocklist, PII, too few **distinct users**: privacy) ->
**time decay** (sum of count_d x 0.9^age_d) -> top ~100M -> trie -> top-k -> flatten -> snapshot,
**swapped atomically**. **Latency:** debounce ~50-150 ms, edge ~10-40, origin ~20-60, lookup < 1:
win by being close and not asking. **Replicate** while it fits, else hash the full prefix (first
letters skew). **Trending:** a stream job flags sliding-window jumps and ships an overlay every few
minutes: `merge(base, overlay)`. **Denylist** hot-reloads; personalization is a separate call.

**Example** · "biryani recipe" at 1.0M today + 1.0M yesterday = 1.0 + 0.9 = 1.9M; a 5M spike a
year ago = 5M x 0.9^365 = ~0. "bird flu" at 40x its usual rate: the overlay lifts it for "b" ...
"bird" in minutes. **Failures:** hot prefixes -> caches, replicas; bad snapshot -> validate, keep
the old; harmful suggestion -> denylist. **Pitch:** top-k trie, flattened, decayed, swapped, cached.

## Crawler: requirements, numbers, architecture

**How it works** · politeness, not throughput, is the hard constraint.
- **Functional:** from seeds fetch pages, extract links, store content for indexing, recrawl.
- **Non-functional:** 1B pages/month, never overload a site, obey robots.txt, survive traps.

| Quantity | Value |
|---|---|
| Fetch rate | ~385 pages/s avg; design for ~1,000/s |
| Bandwidth / storage | ~100 KB/page -> ~100 MB/s (~0.8 Gbit/s); ~100 TB/month raw |
| Concurrency (Little's law, ~1 s per fetch) | ~1,000 in flight: a few async-I/O machines |
| Links | ~50/page -> ~50k URLs/s to check against 10B+ seen |

Internal API: `frontier.add(url, priority)`, `next()`, `fetch`, `store`. Data: disk-backed
frontier; URL metadata (`url_hash -> last_fetch, content_hash, change_rate, next_due`) in KV;
Bloom + exact hashes; pages in object storage; robots/DNS caches. Path: frontier -> fetchers ->
parser -> normalize -> seen? -> content dedupe -> store, new URLs loop back; recrawl scheduler.

**Example** · follow one link that node 3 finds on news.site.

```text
1 raw: HTTP://Example.COM:80/a/./b/../c?utm_source=x&b=2&a=1#top
2 normalize -> http://example.com/a/c?a=1&b=2
3 hash("example.com") on the ring -> node 7 owns it -> batched forward
4 node 7 Bloom: "definitely not seen" -> add; priority 2 -> front queue F2
5 moved to back queue B14 (only example.com); heap holds (10:00:05.0, B14)
6 10:00:05.0 B14 is the heap min; robots cache: /a/ allowed, Crawl-delay 5
7 DNS from local cache; GET -> 200, 84 KB in 0.9 s; B14 back at 05.9 + 5 = 10.9
8 new hash, SimHash far from known pages -> store; 50 links -> step 1; next_due +2 d
```

## URL frontier: priority and politeness

**How it works** · **priority** (important/fast-changing first) vs **politeness** (one connection
per host, a delay between hits). Mercator: **front queues** by priority (biased pick) feed **back
queues, one host each**; a **min-heap of (next_allowed_time, back queue)**: pop min, wait, fetch
one URL, re-insert at `now + delay(host)`, refill empty back queues; O(log m). Delay is adaptive
(multiple of last fetch time, >= `Crawl-delay`, backoff on 429/503); the frontier lives on disk.

**Example** · heap [(05.0 example.com), (05.2 news.site), (06.0 blog.org)]: example.com back at 10.9;
news.site fetches 0.8 s, delay 2 x 0.8 -> back at 07.6; blog.org at 06.0; a 503 doubles a delay.

## Fetching, dedupe, recrawl, traps, distribution

> **Black box:** **robots.txt** (RFC 9309) lists disallowed paths (`Crawl-delay` is non-standard
> but common). **Use when:** before any host; cache ~1 day, check every URL. **Cost:** one fetch
> per host; plus a local caching **DNS** resolver with async prefetch at 1,000 fetches/s.

**How it works** · async fetchers with timeouts, size/redirect limits, honest User-Agent;
recrawl via **conditional GET** (`If-Modified-Since` -> `304`). **Normalize URLs** (step 2 above).
**Bloom filter** seen test ("definitely not" / "probably"): a false positive skips ~1% of new URLs
(or confirm with exact hashes, 80 GB). **Content dedupe:** exact hash; near-dups by 64-bit
**SimHash** (Hamming ~3 in a published study). **Recrawl:** shorter interval if the hash changed.
**Traps:** calendars (caps, depth), session IDs (strip), repeating paths (max length), soft 404s
(fingerprint), link farms (quality score). **Distribution:** `hash(host)` ring makes politeness
**local**; dead node's hosts move to neighbors, recovered from frontier checkpoints.

**Example** · Bloom: m/n = -ln(p)/(ln 2)^2 = 4.6 / 0.48 = ~9.6 bits/URL at p = 1%; 10B URLs =
96 Gbit = ~12 GB. SimHash: 64 bits = 4 blocks of 16; prints differing in <= 3 bits share at least
one whole block (pigeonhole), so 4 tables sorted by each block find all candidates.
**Pitch:** 1,000 fetches/s, 12 GB Bloom -> front/back queues + heap -> robots/DNS, Bloom, SimHash -> ring.

## Ad serving: vocabulary, requirements, numbers

**How it works** · serving is approximate and fast *now*; money is made exact *later*.

| Term | Meaning |
|---|---|
| Campaign / creative | has a **budget** ($500/day), schedule, goal / the thing shown + landing URL |
| CPM, CPC, CPA | price per 1,000 impressions / per click / per conversion |
| pCTR, pCVR, **eCPM** | predicted click rate / conversion rate after click / expected revenue per 1,000 |
| Frequency cap, **pacing** | max views per user per window / spreading budget over the day |

- **Functional:** best eligible ad + tracking URLs within targeting, budget, caps; auction; billing events; A/B.
- **Non-functional:** ~100 ms (internal p99 50-80), 500k req/s, billing exact, overspend ~1-2%.

| Quantity (500k req/s peak, 200k avg, 10M ads, 70% fill, CTR ~0.5%) | Value |
|---|---|
| Impressions / clicks | ~140k/s (~12B/day) / ~60M/day (~700/s) |
| Event logs | ~1 KB each -> roughly 15-30 TB/day |
| Scoring | heavy model on all 1,000 = 500M inferences/s, unaffordable -> heavy on top ~100 only |
| Features | ~500k batched multi-gets/s: one user read + one ~100-ad read per request |
| Ad index / servers | ~10 GB, fits RAM / ~500 at ~1k req/s each, x1.5-2 headroom, multi-region |

**Example** · 200k x 0.7 = 140k imps/s = ~12B/day; x 0.5% = 60M clicks/day = ~700/s. Heavy model
on 100 not 1,000: 500k x 100 = 50M inferences/s, still a large batched fleet.

API: `POST /v1/ad-request {request_id, slot{size, floor_cpm}, context, device, geo, user
(pseudonymous), timeout_ms}` -> `200 {ad_id, creative_url, click_url, impression_url}` or `204`.
Tracking URLs carry an **encrypted, signed blob** (request, ad, campaign, price, experiments):
attribution needs no lookup, and nobody can forge events or change the price.

## Ad serving: data and architecture

**How it works** · SQL is the campaign truth; serving never touches it: a change stream builds an
**in-memory index on every ad server**; user features + cap counters live in an online KV
(Aerospike/Redis/Cassandra style, ~1 TB, ms p99); events -> Kafka -> **idempotent ledger**, OLAP.

```text
Advertiser UI -> Campaign DB (SQL) -> change stream -> in-memory index + kill list (all)
Publisher -> LB -> Ad server [1]..[6] <-> feature store KV, model servers, pacing state
Beacons/clicks -> collectors -> Kafka -> dedupe/join/IVT -> budget svc | ledger | OLAP
Data lake (raw events + logged features) -> daily reconciliation, model training
```

**Example** · cmp_A is paused at 12:00:00: SQL -> change stream -> kill list on ~500 servers in
seconds; the filter checks the kill list before the index catches up, so spend stops quickly.

> **Common mistake:** "query a DB of ads with a WHERE clause". At 500k req/s and 100 ms the
> index must be in memory; only small per-user state comes from a fast KV store.

## The ad request flow, one request end to end

**How it works** · a funnel, 10M ads -> 1, each stage with a **deadline**: parse ~2 ms, [1] user
+ context ~5-10 (ONE batched KV read), [2] **targeting index** ~5 (~2,000 left), [3] filters ~2-5
(~1,000), [4a] light rank ~5 (~100), [4b] heavy pCTR/pCVR ~10-20 (one batched call), [5] auction
< 1, [6] respond + async log ~1-2. Total ~35-55 ms, leaving margin inside ~100 ms.

**Example** · request `r_7c1e`: slot `pub42_top` 300x250, floor $1.20 CPM, US/CA, Android,
user `u_hash_91a`, page keywords [football, transfer]; t0 = arrival.

```text
t0+2   parse: bucket 417 (in no test -> production model); deadline t0+80
t0+9   [1] one KV multi-get: segments {sports, cars}; fcap {cmp_9: 3 of 3 today}
t0+14  [2] targeting index: 10,000,000 ads -> 2,140 candidates
t0+18  [3] paused/schedule -310, pacing throttle -420, blocklist/brand -150,
           fcap cmp_9 -60, cannot beat floor -200 -> 1,000 eligible
t0+23  [4a] light rank (historical CTR) -> top 100
t0+40  [4b] heavy model, one batched call on 100 -> pCTR -> eCPM (top 3 below)
t0+41  [5] auction: C wins, pays $0.41 per click (second price)
t0+43  [6] 200 {ad_C, creative_url, impression_url, click_url}; both URLs carry a signed
           blob {r_7c1e, pub42_top, cmp_C, $0.41/click}; features + scores logged async
```

| Ad | Bid | pCTR | eCPM = bid x pCTR x 1000 |
|---|---|---|---|
| A | CPC $2.00 | 1.0% | 2.00 x 0.010 x 1000 = **$20.00** |
| B | CPM $15.00 | - | **$15.00** |
| C | CPC $0.50 | 5.0% | 0.50 x 0.050 x 1000 = **$25.00** |

```text
t0+0.6 s  ad renders -> GET imp.ads.example/i?d=<blob> -> collector verifies, decrypts,
          writes Kafka impressions (key r_7c1e), 204; event id r_7c1e:pub42_top
t0+0.9 s  SDK retries the beacon -> same event id -> dropped by stream dedupe
          stream: fcap:{u_hash_91a}[cmp_C] += 1; impression logged, no charge (CPC)
t0+14 s   click -> clk.ads.example/c?d=<blob> -> Kafka clicks -> 302 to landing page
          join click -> impression -> request ok; IVT ok (13 s after render, normal IP)
          ledger upsert keyed by click event id: cmp_C spend += $0.41
t0+19 s   5 s spend aggregate -> budget svc -> cmp_C pacing knob updated
nightly   reconciliation over raw events; a late fraud verdict -> retroactive credit
```
So: 10M -> 2,140 -> 1,000 -> 100 -> 1 in ~43 ms; the price is fixed in the signed blob, money is
counted once, from events. Slow model -> light ranking; feature timeout -> defaults: on time wins.

## Retrieval: the targeting index

**How it works** · each ad has a boolean **targeting expression** (`geo IN {US,CA} AND
device=mobile AND interest IN {sports}`); evaluating 10M is O(ads). An **inverted index** (docs =
ads, terms = attribute values) plus a **wildcard list** per attribute (`geo=*`): intersect, per
attribute, (matches u wildcard), drop NOT matches. Union = k-way merge; intersection = walk from
the **smallest** list, galloping through larger ones.

**Example** · request {geo=US, device=mobile, interests [sports, cars], keyword football}:

```text
G = geo=US [1,2,7,9,15]      u geo=*      [3,11]     = [1,2,3,7,9,11,15]
D = device=mobile [1,9,15]   u device=*   [2,3,7,11] = [1,2,3,7,9,11,15]
I = interest=sports [1,40]   u interest=* [2,3,7]    = [1,2,3,7,40]
K = keyword=football [3,22]  u keyword=*  [1,2,7]    = [1,2,3,7,22]
I n K = [1,2,3,7] (start small) -> n G -> n D = [1,2,3,7]; ad 3's NOT geo=FR passes
Without wildcard lists: D = [1,9,15], I = [1,40], K = [3,22] -> empty: the classic bug
```

ORs of ANDs need **boolean expression indexing** (mention only). Updates stream in + snapshot
reloads. **Replicate, don't shard** while it fits: scatter-gather lets the slowest shard set latency.

## Filtering and scoring (pCTR x bid)

**How it works** · filters drop what **must not** show, cheapest first: status, schedule,
approval, blocklists, brand safety (memory); floor; **pacing** (cached knob); **frequency cap**
(`fcap:{user}` -> `campaign -> (count, window)`, TTL; read in the user lookup, **incremented on the
impression event**: many served ads never render). **eCPM** puts bids on one scale: CPM `bid`;
CPC `bid x pCTR x 1000`; CPA `bid x pCTR x pCVR x 1000`; plus a **quality term** (hidden ads).

**Example** · in `r_7c1e`, C wins with the *lowest* per-click bid: **bid x probability**. Caps are
approximate: two requests 200 ms apart both read `cmp_9: 2 of 3` before either impression lands,
so the user sees cmp_9 four times. **Calibration**: price multiplies the prediction; if A's pCTR
were over-estimated as 1.2% (true 1.0%), A's eCPM = $24 and C pays 24 / 50 + 0.01 = $0.49, not
$0.41 (~20% off). Monitor per segment; fix with isotonic or Platt scaling.

## The auction

**How it works** · winner and **price**. **Second price** (one slot): pay the least that still
wins = runner-up's eCPM (+ increment) in your bid unit, or the **floor** (reserve) if no rival is
above it; truthful bidding is optimal (**Vickrey**). Multi-slot **GSP** (pay to stay above the next
slot) is common in search but **not** truthful in general. **First price**: pay your bid; simpler,
transparent; much of programmatic RTB switched around 2019, and buyers now **shade** bids.

**Example** · same three bids (A $20, B $15, C $25 eCPM), floor $1.20 CPM:

| Rule | Winner | C pays per click | Revenue per 1,000 imps (price x pCTR x 1000) |
|---|---|---|---|
| Second price | C | 20 / (0.05 x 1000) + $0.01 = **$0.41** | $20.50 |
| Second price, A and B absent | C | floor: 1.20 / 50 = **$0.024** | $1.20 |
| First price, true bids | C | its bid, **$0.50** | $25.00 |
| First price, all shade 15% (illustrative) | C ($21.25 vs A $17, B $12.75) | **$0.425** | $21.25 |

C pays only on a click (CPC). With shading, first price lands near second price; the work moves
to buyers. The auction is trivial (sort ~100 numbers); scoring and charging are the hard parts.

> **Say it like this:** "I rank by eCPM, bid times predicted click or conversion probability, so
> CPC, CPA and CPM compete on one scale. Second price charges just enough to beat the runner-up,
> converted to the bid unit; first price charges the bid and buyers shade."

## Budget pacing and bounded overspend

**How it works** · unpaced, a $1,000/day campaign wins everything from midnight, gone by 2 AM.
**Pacing** follows a **target spend curve**: a PID-style controller sets a **throttle** p (drop if
`random() > p`) or a **bid multiplier** (< 1 when ahead). A strongly consistent decrement adds a
round trip and a **hot row per campaign**, so budgets are **eventually consistent**, bounded by
short refreshes, **budget leases** (local slices shrinking with the remaining budget: a token
bucket shared out to workers), p near zero at the end, and policy (overspend usually absorbed).

**Example** · at 10:00 cmp_C has spent $500 vs a $400 target -> p drops 0.8 -> 0.6, so a draw of
0.73 now skips the auction. Overspend ~= spend rate x staleness: $10/s x 10 s = ~$100, 1% of a
$10,000 budget. Near exhaustion slices shrink from $5 to $0.01: 500 servers x $0.01 = ~$5 worst
case. Config, budgets, caps: eventual; ledger: exact after dedupe + reconciliation.

## Tracking pipeline: impressions, clicks, billing

**How it works** · money comes from events. **Collectors** (dumb, fast) verify, decrypt, write to
Kafka (key request_id), answer (click -> `302`). The **stream processor** dedupes by event ID
(keyed state, ~24 h TTL), joins click -> impression -> request, filters **invalid traffic**, and
aggregates spend -> budget service, **billing ledger** (**idempotent upserts by event ID**), OLAP:
at-least-once + idempotent = "exactly-once billing". **Served is not shown:** bill on render. IVT
= rules (bot UAs, DC IPs), rates, batch ML with **retroactive credits**; **reconciliation** invoices.

**Example** · the stream job writes `click:r_7c1e` to the ledger, crashes before committing its
offset, replays the click: the upsert rewrites the same row, cmp_C is charged $0.41 once. A click
80 ms after render from a data-center IP is dropped by IVT rules. Pipeline lag = budget staleness.

## Feature store, model serving, experiments

> **Black box:** **Feature store** = offline (history, point-in-time joins) + online KV (p99 ~1-5
> ms, one batched read for user + context, one for ~100 ads). **Model serving** = in-process or GPU
> fleet, retrain hourly/daily, **shadow-score**, A/B ramp, hot-swap. **Use when:** ML at serve time.
> **Cost:** **training-serving skew** (fix: **feature logging**); cold start needs **exploration**.

**How it works** · **A/B:** `bucket = hash(user_id + layer_salt) mod 1000` keeps a user in one arm
with no storage; **layers** (different salts) run overlapping experiments, exclusive within a
layer; events carry experiment IDs; watch revenue per 1,000 requests, CTR, advertiser ROI, p99.

**Example** · ranking layer: 0-49 -> v42, 50-99 -> control; `u_hash_91a` = 417 (no test), 23 in the
auction layer. **Ad trap:** shared **budgets**: treatment drains cmp_C by 18:00, control looks worse,
lift inflated. Fix: **budget-split** ($500 per arm at 50/50) or randomize by campaign/advertiser.

## Ad serving: failures, trade-offs, 5-minute pitch

**How it works** · slow model -> deadline, light fallback; feature spike -> hedged reads, defaults;
index lag -> kill list; pipeline lag -> pacer slows; duplicates -> idempotent ledger; fraud -> IVT;
outage -> active-active; hot campaign -> leases. Trade-offs: latency vs accuracy, budget accuracy
vs latency, real-time vs exact, explore/exploit.

**Example** · `r_7c1e`'s heavy call is still pending at t0+60: cancel, use light scores, answer at t0+63.

**5-minute pitch:** (1) ~100 ms, 500k req/s, 10 GB index in RAM, two-stage scoring; (2) funnel ->
signed URLs; (3) eCPM, second vs first price, floor, calibration; (4) Kafka, dedupe, idempotent
ledger, reconciliation, leases; (5) feature logging, shadowing, budget-split A/B.

## In one minute

- Typeahead: offline **top-k per trie node** -> cached RAM map. Crawler: **front + per-host back
  queues + time min-heap**; Bloom (~9.6 bits/URL) + SimHash; partition by host.
- Ads: **retrieve -> filter -> light rank -> heavy score -> auction**, deadlines, index in RAM.
- **eCPM = bid x pCTR (x pCVR) x 1000**; second price = runner-up eCPM back in bid units.
- Budgets/caps are **eventual**: overspend ~= spend rate x staleness, bounded by **leases**.
- Billing = Kafka + dedupe + **idempotent ledger** + reconciliation; budget-split A/B.

## Go deeper

Full chapter 21: sections 21.27 (request flow), 21.31 (auction), 21.32 (pacing), 21.15 (frontier)
