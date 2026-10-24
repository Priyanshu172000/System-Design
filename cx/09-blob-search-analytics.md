# 09 — Files, search and analytics data

*Why it matters:* a relational DB is wrong for a 4 GB video, a "search by any word" box, or "clicks per campaign per hour over two years"; each needs a specialized store and a pipeline to feed it.

## Object (blob) storage

**How it works** · A `BLOB` column makes the most expensive storage bloat, slows backups and replication, pushes every download through DB + app, and cannot sit behind a CDN. **Object storage** (S3, GCS, Azure Blob, MinIO, Ceph) stores files of any size cheaply and durably over HTTP.

- **Bucket**: top-level container with name, region, access policy.
- **Key**: object name; looks like a path but the namespace is flat ("folders" = key prefixes).
- **Object**: bytes + metadata. **Immutable**: no in-place byte edits; upload a new version (or better, a new key). **Versioning** keeps old ones.
- Very high durability (redundant across devices and facilities; "eleven nines" advertised).

Standard design: bytes in object storage, the DB row holds only `object_key` + metadata. Cost: two systems, so handle "row exists but upload failed". Tiny blobs (a few KB) in the DB are OK at small scale; object storage is the default for anything user-uploaded.

**Pre-signed URL**: a short-lived (say 15 min) URL signed for one object and one operation, so the client talks to the store directly.

**Example** · Spice Route (`r_77`) uploads a 2 MB biryani photo for dish `m_101`.

```text
1. restaurant app -> restaurant-service: "upload biryani.jpg, 2 MB, image/jpeg"
2. service checks r_77 owns m_101, picks key restaurants/r_77/dishes/m_101/8f3a.jpg,
   returns PUT URL to bucket quickbite-media, expires in 15 min
3. app -> object store: PUT 2 MB (restaurant-service never sees the bytes)
4. app -> service "done" (or a bucket event); service validates size/type, scans
5. menu_items row m_101: photo_key = restaurants/r_77/dishes/m_101/8f3a.jpg; thumbnail job
so: the DB stores a 50-byte key; the 2 MB lives in cheap storage and is served via CDN
```

- Pay: validation happens after the fact; unconfirmed uploads need a cleanup job or lifecycle rule. Private downloads (a delivery-proof photo for Asha) use a pre-signed GET.

> **Black box:** **Multipart upload** splits a big file into parts uploaded in parallel and retried independently, then a "complete" call stitches them (S3: parts >= 5 MB except the last, up to 10,000). **Use when:** large files. **Cost:** orphan parts cost money until a lifecycle rule aborts them. E.g. a 600 MB kitchen video = 6 x 100 MB parts; part 4 fails, only part 4 is resent.

> **Black box:** **Storage classes**: standard/hot (ms, highest storage cost), infrequent access (ms, per-GB retrieval fee), archive/cold (minutes to hours to restore, very cheap). **Lifecycle rules** move objects automatically (30 days -> infrequent, 365 -> archive, delete after 7 years). **Use when:** data cools with age. **Cost:** retrieval fees and delays. E.g. delivery-proof photos: hot for 30 days (disputes), then infrequent, then archive.

- Object storage is a natural **CDN origin**: public assets via CDN with long TTL + versioned keys; private ones via short-lived signed URLs (CDNs have their own). Image variants are made on upload by a job or on the fly by an image service between CDN and bucket.

> **Say it like this:** "Files go to object storage, the database keeps only the key and metadata. Clients upload directly with a pre-signed URL so our servers never proxy bytes, and downloads go through a CDN."

## Full-text search: the inverted index

**How it works** · `LIKE '%biryani%'` cannot use a B+ tree (leading `%`), scans every row, cannot rank, and misses "biryani"/"biryanis", typos. An **inverted index** maps term -> sorted list of doc IDs (**posting list**): (1) **tokenize** into words; (2) an **analyzer** lowercases, drops **stop words**, **stems** (`wings` -> `wing`), optionally adds synonyms and accent folding; (3) build posting lists (doc ID, often positions and counts), sorted by doc ID.

**Example** · Three menu items in `search-service`.

```text
doc 1 "Chicken Biryani with Raita"  -> [chicken, biryani, raita]       ("with" dropped)
doc 2 "Veg Biryani Bowls"           -> [veg, biryani, bowl]
doc 3 "Chicken Wings and Chicken Rolls" -> [chicken, wing, chicken, roll] ("and" dropped)
postings (doc:tf): biryani [1:1, 2:1]   bowl [2:1]   chicken [1:1, 3:2]   raita [1:1]
                   roll [3:1]   veg [2:1]   wing [3:1]
query "Chicken Biryanis" -> same analyzer -> [chicken, biryani]
AND: two pointers over [1,3] and [1,2]: 1=1 emit 1; 3>2 advance -> end  => {1}
OR : union => {1,2,3}, then rank (doc 3 has chicken tf=2)
```

So: a query touches 2 short lists, not every row; long lists add **skip pointers** to jump ahead.

> **Common mistake:** Indexing with one analyzer and querying with another: if docs are stemmed and queries are not, "biryanis" never matches stored "biryani".

**Ranking** · **TF**: more mentions in a doc = more relevant. **IDF**: rare terms are more informative, `idf(t) = log(N / df(t))`. **TF-IDF** = sum of `tf x idf` over query terms. E.g. over 1 M dishes, "chicken" in 200,000 gives ln(5) ≈ 1.6; "raita" in 2,000 gives ln(500) ≈ 6.2, so a raita match counts ~4x more. **BM25** (Lucene default) adds **TF saturation** (`k1` ~ 1.2: the 10th "chicken" adds little) and **length normalization** (`b` ~ 0.75: a match in a 3-word dish name beats one in a 300-word description). Real ranking layers popularity, recency, personalization, field boosts, learned models.

## Elasticsearch-style engines

> **Black box:** **Elasticsearch/OpenSearch** (on **Lucene**): an **index** of JSON **documents** split into N **primary shards** by `hash(routing key, default doc ID) mod shards` (so shard count is effectively fixed; changing it needs split/shrink or reindex), each with **replicas**. **Use when:** full-text, relevance, fuzzy, facets. **Cost:** not a system of record (no multi-doc transactions), a copy that can drift, slow reindexing, costly mapping mistakes.

**How it works** · Queries **scatter** to one copy of each shard; each returns its top-k; the coordinator merges (**gather**) and fetches the winning docs. Writes are **near-real-time**: docs go to a buffer and become searchable at the next **refresh** (~1 s default) as an immutable **segment**; a **translog** protects unflushed writes; segments merge in the background like LSM compaction.

**Example** · Index `dishes`, 3 primary shards + 1 replica each, query "biryani near me" for top 10.

```text
1. coordinator -> P0, R1, P2 (one copy of each shard)
2. each shard returns its own top 10 (doc id + score)            -> 30 candidates
3. coordinator merges by score -> global top 10, fetches those 10 docs
4. 20:00:00.2 new dish indexed into buffer; 20:00:01 refresh -> new segment -> searchable
so: reads scale with shards/replicas; a new dish shows up ~1 s later, not instantly
```

## Keeping derived stores in sync: dual write vs CDC

**How it works** · **Dual write** (app writes DB, then search) breaks when the app crashes between steps or two updates reach the index out of order; retries do not fix it. **CDC (change data capture)** reads the DB's own commit log (MySQL binlog, Postgres WAL) and publishes each committed change, typically to Kafka, for an indexer.

**Example** · Spice Route renames dish `m_101` twice: "Biryani" -> "Hyderabadi Biryani" -> "Dum Biryani".

```text
dual write: restaurant-service: UPDATE name='Hyderabadi..' OK; crash before index call
            -> DB "Hyderabadi Biryani", search "Biryani" forever, no one notices
            or two requests: DB order v1,v2 but index calls land v2,v1 -> search shows v1
CDC:        WAL: LSN 811 name='Hyderabadi..'   LSN 812 name='Dum Biryani'
            connector -> Kafka "menu.changes" key m_101 (one partition, LSN order)
            indexer applies 811 then 812 (idempotent upsert by m_101) -> "Dum Biryani"
```

- Captures every committed change in commit order per row, even from scripts that "forgot search"; the index can be rebuilt by replay.
- Pay: more moving parts, small lag, indexer must be idempotent. The **transactional outbox** (chapter 11) is the alternative for domain events rather than raw rows.

> **Say it like this:** "Postgres stays the source of truth. Elasticsearch is a derived read model fed from the database's change stream, and we accept it's a second or two behind."

## OLTP vs OLAP, row vs columnar

**How it works** · **OLTP**: many small concurrent reads/writes of most columns of a few rows, ms latency, row-oriented (Postgres, MySQL). **OLAP**: scan and aggregate a few columns of millions-billions of rows, bulk append loads, seconds latency, column-oriented (BigQuery, Snowflake, Redshift, ClickHouse, Druid). Heavy analytics on the OLTP DB evicts hot pages and slows every user; analytics gets its own copy.

```text
row:      [o_90311|u_1842|r_77|BLR|450|...] [o_90312|u_77|r_12|DEL|220|...] ...
columnar: city: [BLR, DEL, BLR, ...]   amount: [450, 220, 310, ...]  (one file per column)
```

**Example** · OLTP: `SELECT * FROM orders WHERE order_id = 'o_90311'` reads one row, all columns, ~1 ms. OLAP: "revenue by city for two years" = `SELECT city, SUM(amount) FROM orders GROUP BY city` over 2 M/day x 730 days ≈ 1.46 B rows. Say 50 columns, ~500 B/row:

| Step | Bytes read |
|---|---|
| Row store: whole rows | 1.46 B x 500 B ≈ 730 GB |
| **Column pruning**: 2 of 50 columns, roughly 4% | ≈ 29 GB |
| **Compression** 5–10x (**dictionary encoding**: a few hundred cities -> small ints; **run-length encoding** when sorted) | ≈ 3–6 GB |
| **Skipping**: per-block min/max, `created_at >= '2026-01-01'` skips older blocks | less still |

So: columnar reads ~100x fewer bytes, then **vectorized execution** sums tight typed arrays (CPU caches, SIMD). Row storage wins OLTP: one row = one place, while columnar is slow at single-row updates (batches/appends). File formats: **Parquet**, **ORC**.

## Warehouse, lake, lakehouse, ETL vs ELT

> **Black box:** **Warehouse** = managed columnar SQL DB, schema on write, cleaned data (Snowflake, BigQuery, Redshift); higher cost, lock-in. **Lake** = raw files in object storage, schema on read, queried by Spark/Trino/Hive; cheapest, risks a "data swamp". **Lakehouse** = lake + open table format (Iceberg, Delta Lake, Hudi) adding schema, ACID commits, snapshots/time travel, updates/deletes. **Use when:** you want warehouse behavior at lake cost. **Cost:** newer, more to operate.

- **ETL**: transform in a separate layer, then load (from when warehouse compute was expensive). **ELT**: load raw first, transform with SQL inside; common today since compute is elastic and raw data lets you re-run transformations. E.g. QuickBite loads raw `order-events` daily, then a SQL model builds `daily_restaurant_revenue`; when the refund logic changes, re-run the SQL over the raw data.

## Batch processing

**How it works** · Jobs over bounded input (yesterday's orders); minutes-hours latency, huge throughput. **MapReduce**: parallel **map**, **shuffle** grouping by key (`hash(key) mod R` to reducers), parallel **reduce**.

**Example** · "Orders per dish yesterday", 2 input splits, 2 reducers.

```text
split1 "biryani dosa biryani" -> (biryani,1)(dosa,1)(biryani,1)
split2 "thali dosa"           -> (thali,1)(dosa,1)
shuffle: reducer A: biryani->[1,1], thali->[1]     reducer B: dosa->[1,1]
reduce:  biryani 2, thali 1, dosa 2
if reducer B's machine dies: re-run B on the same immutable input -> same "dosa 2"
```

So: deterministic tasks over immutable input make "re-run the failed task" the fault tolerance. The shuffle is a distributed group-by (hash partition + sort/hash aggregate).

> **Black box:** **Spark** models a job as a DAG of transformations over partitioned data, keeps intermediates in memory (MapReduce writes to disk each stage), recomputes lost partitions from lineage. **Use when:** multi-step batch jobs, SQL over the lake. **Cost:** results hours old, compute bursts, no real-time reaction.

## Stream processing and windows

**How it works** · Unbounded data processed continuously, results in seconds (Flink, Kafka Streams, Spark Structured Streaming; input usually Kafka). No "end", so aggregate over **windows**:

| Window | Shape | QuickBite use |
|---|---|---|
| **Tumbling** | Fixed, non-overlapping (1 min) | Orders per restaurant per minute ("r_77 is busy") |
| **Sliding / hopping** | Overlapping (1 min every 30 s) | "Failed payments in last 5 min, updated every 10 s" alert |
| **Session** | Per key, closes after inactivity gap (30 min) | Asha's browsing session |

- **Event time** (when it happened) vs **processing time** (when the processor sees it) differ due to network delay, retries, offline phones, post-outage backlog. Window by event time, or delayed events land in the wrong minute.

**Example** · Tumbling 1-min windows for `r_77` on event time, allowed lateness 10 s, so **watermark** = max event time seen - 10 s.

```text
arrive (event time)   max seen   watermark   effect
20:00:58              20:00:58   20:00:48    counts in [20:00,20:01)
20:01:03              20:01:03   20:00:53    counts in [20:01,20:02)
20:00:59              20:01:03   20:00:53    still counts in [20:00,20:01) (out of order)
20:01:15              20:01:15   20:01:05    watermark >= 20:01 -> emit [20:00,20:01) = 2
20:00:40 (rider phone was offline)           late: drop, side output, or update result
```

So: event time puts 20:00:59 in the right minute; the watermark decides when a window is done. Sliding windows: an event at 20:00:45 counts in both [20:00:00, 20:01:00) and [20:00:30, 20:01:30).

> **Black box:** **Watermark** = "I believe I have seen all events with event time <= T", usually max event time seen minus allowed lateness; it lets a window close. **Use when:** event-time windows. **Cost:** generous lateness = complete but slow output; tight = fast but more late events.

- **State** (running counts per key per window) is checkpointed with input offsets, so a crash restores both: checkpoint {r_77: 41, offset 5,000}, crash at offset 5,300, restore 41 and rewind to 5,000, replay 300 events -> same counts. With transactional/idempotent sinks this gives "effectively once" (chapter 11).

## Lambda vs Kappa

> **Black box:** **Lambda** runs a batch layer (exact, hours later) and a speed layer (approximate, seconds) and merges at serving; batch recompute fixes stream bugs, but logic is written twice and must agree. **Kappa** has only a stream path; reprocess by replaying the retained log (long Kafka retention or the lake) through job v2, then switch. **Use when:** Kappa for one codebase; Lambda-flavored when money needs an exact path. **Cost:** Kappa needs long retention and a strong stream engine. Many systems mix both. E.g. live "orders per minute" from the stream; restaurant payouts from the nightly batch.

## Worked example: ad-click analytics

**How it works** · ~50,000 clicks/s peak; dashboard per campaign per minute with < 1 min delay; exact deduped daily billing; a year of ad-hoc queries.

```text
trackers -> Kafka "clicks" (by campaign_id, 7-day retention)
              |-> stream job: dedupe click_id, event-time 1-min tumbling, watermark ~30 s
              |        -> OLAP store (ClickHouse/Druid-like) campaign_minute_agg
              |-> lake sink: Parquet in object storage, dt=/hr= partitions
                       -> daily batch: exact dedupe, billing, fraud, ML; fixes OLAP
```

**Example** · Follow click `k_5521` (campaign c7, event time 10:00:59):

1. Tracker sends it at 10:00:59, times out, retries; Kafka holds two copies, both reach the stream job around 10:01:20.
2. Copy 1: `k_5521` not in the seen-ID state (TTL a bit longer than the retry window) -> keep; copy 2 -> dropped.
3. Max event time seen ≈ 10:01:20, watermark ≈ 10:00:50 < 10:01:00, so window [10:00, 10:01) is still open: the click counts in 10:00, not 10:01.
4. Watermark passes 10:01:00 (events from ~10:01:30 seen) -> the window emits one row `(c7, 10:00, clicks, spend)` to `campaign_minute_agg`.
5. Lake sink writes both raw copies to `dt=.../hr=10`; next day the batch dedupes exactly and is the billing source of truth (Lambda-flavored on purpose).

- **Kafka** buffers spikes, feeds several consumers, allows replay. **Pre-aggregation**: 50k events/s become roughly one row per active campaign per minute.
- Sizing: 50k/s x ~200 B ≈ 10 MB/s ≈ 860 GB/day raw; Parquet 5–10x -> roughly 90–170 GB/day, 30–60 TB/year before replication.

## Time-series and logs

> **Black box:** **Time-series DBs** (Prometheus, InfluxDB, TimescaleDB, VictoriaMetrics) store metric + labels + timestamp, append-only, with delta/XOR compression, **downsampling** (10 s for a week, 5 min for a year) and retention. **Use when:** metrics. **Cost:** **cardinality**: each label combo is a series. E.g. `ping_latency{city}` = a few hundred series; add a `rider_id` label and it becomes ~100,000 x cities, enough to take it down.

- **Logs**: either fully indexed for search (Elasticsearch/OpenSearch) or label-indexed with compressed chunks in object storage (Loki-style: cheaper, slower free-text). Structured JSON, sample debug logs, tiered retention (chapter 14).

## In one minute

- Files live in object storage (flat keys, immutable objects); the DB keeps key + metadata; clients use pre-signed URLs, multipart for big files, lifecycle rules for cost, CDN in front.
- Inverted index: term -> sorted posting list; same analyzer at index and query time; AND = two-pointer list intersection.
- TF-IDF rewards frequent-in-doc, rare-in-corpus terms; BM25 adds saturation and length normalization.
- Search engines are sharded, near-real-time (~1 s) derived copies, fed by CDC, never by dual writes.
- OLAP reads few columns of many rows: columnar wins via pruning, 5–10x compression, vectorization, block skipping (730 GB -> a few GB).
- Warehouse vs lake vs lakehouse; ELT is the modern flow.
- Batch = bounded, re-runnable, hours old; stream = windows on event time with watermarks.
- Lambda = batch + stream; Kappa = stream + replay; money usually keeps an exact batch path.

## Go deeper

Full chapter 09: sections 09.2 (inverted index, ranking, CDC), 09.4 (row vs columnar), 09.7 (stream processing), 09.9 (ad-click pipeline).
