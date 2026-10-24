# 09 — Files, search and analytics data

A relational database is the right home for orders and users, but it is the wrong home for a
4 GB video, a "search products by any word" box, or "clicks per campaign per hour over two
years". This chapter covers the specialized stores for those jobs: object storage, search
engines, analytical (columnar) stores, and the batch and stream pipelines that feed them.

## 09.1 Object (blob) storage

### The pain

Users upload profile photos, PDFs, and videos. The first instinct is a `BLOB` column in the
database. Problems appear fast:

- Database storage is the most expensive storage you have (fast disks, replicas, backups).
  Images bloat it, and backups and replication get slow.
- Every image download now runs through the database and the app server, using connections,
  memory, and bandwidth that queries need.
- Databases are not built to stream gigabytes; a CDN cannot cache a DB row directly.

### The idea

**Object storage** (also called **blob storage**; "blob" = binary large object) is a service for
storing files of any size cheaply and durably, accessed over HTTP. Examples: Amazon S3, Google
Cloud Storage, Azure Blob Storage, and self-hosted systems like MinIO or Ceph.

```text
  bucket: "photos-prod"
  +----------------------------------------------------------+
  | key: users/42/avatar/8f3a.jpg     -> bytes + metadata     |
  | key: users/42/uploads/2026/doc.pdf -> bytes + metadata    |
  | key: users/77/avatar/1c9e.jpg     -> bytes + metadata     |
  +----------------------------------------------------------+
     PUT /photos-prod/users/42/avatar/8f3a.jpg     (upload)
     GET /photos-prod/users/42/avatar/8f3a.jpg     (download)
```

Key terms:

- **Bucket**: a top-level container with its own name, region, and access policy.
- **Key**: the object's name inside the bucket. It looks like a path (`users/42/avatar.jpg`) but
  the namespace is flat; the slashes are just characters. "Folders" are a UI convention built on
  key prefixes.
- **Object**: the bytes plus metadata (content type, size, custom tags).
- **Immutability**: you cannot edit a byte range in place. To change an object you upload a
  whole new version under the same key (or, better, a new key). Many systems support
  **versioning** to keep old versions.

Durability is very high because each object is stored redundantly across devices and usually
across facilities; providers advertise figures like "eleven nines" of annual durability.

### Database + object store together

The standard design: the file lives in object storage; the database stores only metadata and the
key.

```text
  photos table (SQL)
  +----+---------+-----------------------------+-------+----------+
  | id | user_id | object_key                  | bytes | uploaded |
  +----+---------+-----------------------------+-------+----------+
  | 91 | 42      | users/42/avatar/8f3a.jpg    | 81234 | 2026-... |
  +----+---------+-----------------------------+-------+----------+
           |
           +---> object store holds the actual bytes
```

| Store files in DB | Store files in object storage |
|---|---|
| One system, transactional with the row | Two systems; must handle "row exists but upload failed" |
| Expensive storage, slow backups | Cheap per GB, scales to petabytes |
| App + DB on the download path | Clients download directly or via CDN |
| OK for tiny blobs (a few KB thumbnails) at small scale | Default for anything user-uploaded |

### Pre-signed URLs

If every upload passes through your app server, the server spends its bandwidth and memory
proxying bytes. Instead, the app hands the client a **pre-signed URL**: a URL for one specific
object and operation (PUT or GET), signed with the app's credentials, valid for a short time
(say 15 minutes). The client talks to the object store directly.

```text
 1. Client -> App:   "I want to upload avatar.jpg (80 KB, image/jpeg)"
 2. App checks auth, picks key users/42/avatar/8f3a.jpg,
    returns pre-signed PUT URL (expires in 15 min)
 3. Client -> Object store: PUT <pre-signed URL> with the bytes
 4. Client -> App:   "upload done"  (or the store sends an event notification)
 5. App records metadata row, maybe triggers thumbnail job
```

- Gain: app servers never touch file bytes; uploads scale with the object store.
- Pay: you must validate after the fact (size, type, malware scan), because the client uploaded
  directly; handle uploads that were never confirmed (a cleanup job or lifecycle rule).
- Downloads of private files work the same way with a pre-signed GET.

### Multipart upload

Uploading a 5 GB file in one HTTP request is fragile: one network blip near the end and you start
over. **Multipart upload** splits the file into parts uploaded independently (and in parallel),
then a final "complete" call stitches them into one object. For S3, parts are at least 5 MB
(except the last), up to 10,000 parts.

```text
 initiate -> uploadId
 part 1 (100 MB) ---\
 part 2 (100 MB) ----+--> parallel, each retried independently
 part 3 (100 MB) ---/
 complete(uploadId, [part ETags]) -> one object
```

Gain: parallelism, resumability, retries per part. Pay: incomplete uploads leave orphan parts
that cost money until a lifecycle rule aborts them.

### Storage classes

Object stores offer tiers trading storage price against access price and speed:

| Class (generic names) | Storage cost | Access | Use for |
|---|---|---|---|
| Standard / hot | Highest | Milliseconds, cheap reads | Active user content |
| Infrequent access | Lower | Milliseconds, per-GB retrieval fee | Backups, older content read occasionally |
| Archive / cold | Very low | Minutes to hours to restore | Compliance archives, old logs |

**Lifecycle rules** move objects between classes automatically (e.g. "after 30 days to
infrequent, after 365 days to archive, delete after 7 years").

### Object storage behind a CDN

Object storage is a natural CDN origin (chapter 08):

```text
 User -> CDN edge --miss--> Object store bucket (origin)
             |
             +--hit--> served from edge
```

Public assets (product images) are served through the CDN with long cache lifetimes and
versioned keys. Private assets use short-lived signed URLs (CDNs support their own signed URLs or
cookies too). Image variants (thumbnails, sizes) are either generated on upload by a background
job or on the fly by an image service sitting between CDN and bucket.

> **Say it like this:** "Files go to object storage, the database keeps only the key and
> metadata. Clients upload directly with a pre-signed URL so our servers never proxy bytes, and
> downloads go through a CDN."

## 09.2 Full-text search

### The pain

A store has 50 million products. A user types `red running shoes`. With SQL:

```sql
SELECT * FROM products WHERE description LIKE '%running%';
```

A leading `%` cannot use a B+ tree index (chapter 05), so this scans every row. It also cannot
rank results by relevance, does not match "run" with "running", and does not handle "shoe" vs
"shoes" or typos.

### The inverted index, built from scratch

A normal (forward) index maps document → words. An **inverted index** maps word → list of
documents containing it. It is the core of every search engine.

Three documents:

```text
 doc 1: "Red running shoes for men"
 doc 2: "Blue running jacket"
 doc 3: "Red shoes, red laces"
```

**Step 1 — tokenize**: split text into tokens (words).

```text
 doc 1: [Red, running, shoes, for, men]
 doc 3: [Red, shoes, red, laces]
```

**Step 2 — normalize** (this pipeline is called the **analyzer**):

- lowercase: `Red` → `red`
- remove **stop words** (very common words): `for` dropped
- **stemming** / lemmatization: `running` → `run`, `shoes` → `shoe`, `laces` → `lace`
- optionally synonyms (`sneaker` ↔ `shoe`) and accent folding

**Step 3 — build posting lists**: for each term, the list of document IDs (and often positions
and counts) where it appears. Lists are kept sorted by doc ID.

```text
 term     posting list (docId: term frequency)
 ------   -----------------------------------
 blue  -> [2:1]
 jacket-> [2:1]
 lace  -> [3:1]
 men   -> [1:1]
 red   -> [1:1, 3:2]
 run   -> [1:1, 2:1]
 shoe  -> [1:1, 3:1]
```

**Querying**: the query `red running shoes` goes through the *same* analyzer → `[red, run,
shoe]`. Fetch the three posting lists.

- AND query: intersect the sorted lists → `{1}`. (DSA bridge: intersecting sorted lists is the
  two-pointer merge; skip pointers let you jump ahead in long lists.)
- OR query: union → `{1, 2, 3}`, then rank.

> **Common mistake:** Indexing with one analyzer and querying with another. If documents are
> stemmed but queries are not, "running" will never match the stored term "run".

### Ranking: TF-IDF and BM25 intuition

With OR semantics many documents match; you need to order them. Two intuitions:

- **Term frequency (TF)**: a document that mentions "red" twice is probably more about red than
  one that mentions it once.
- **Inverse document frequency (IDF)**: rare terms are more informative. "shoe" in a shoe store
  appears everywhere and says little; "waterproof" is rare and says a lot.
  `idf(t) = log(N / df(t))` where N = number of documents and df = documents containing t.

**TF-IDF** scores a document as the sum over query terms of `tf × idf`.

**BM25** is the standard modern refinement (the default in Lucene-based engines):

- **TF saturation**: the 10th occurrence of a word adds much less than the 2nd. Score grows with
  tf but levels off (controlled by a parameter `k1`, roughly 1.2).
- **Length normalization**: a match in a 10-word title counts more than one in a 5,000-word
  page (controlled by `b`, roughly 0.75).

Real search ranking then layers on business signals: popularity, recency, personalization,
boosting matches in the title over the body, and learned ranking models.

### Elasticsearch-style architecture

**Elasticsearch** and **OpenSearch** (built on the **Lucene** library) are the common
distributed search engines. Their structure:

- **Index**: a collection of documents (like a table), e.g. `products`.
- **Document**: a JSON object with fields.
- **Shard**: an index is split into N **primary shards**; each is a full Lucene index over a
  subset of documents. A document goes to `hash(routing key, default the doc ID) mod
  number_of_primary_shards`. Because of the mod, the primary shard count is effectively fixed at
  creation; changing it requires a split/shrink operation or reindexing.
- **Replica**: each primary has copies on other nodes, for failover and to serve more reads.

```text
 Index "products": 3 primary shards, 1 replica each

  Node 1            Node 2            Node 3
  +--------+        +--------+        +--------+
  | P0     |        | P1     |        | P2     |
  | R2     |        | R0     |        | R1     |
  +--------+        +--------+        +--------+

 Query: coordinator node sends query to one copy of each shard (scatter),
        each returns its top-k (doc IDs + scores), coordinator merges to global top-k
        (gather), then fetches the full docs for the winners.
```

**Near-real-time (NRT) search**: Lucene writes new documents into an in-memory buffer and
periodically creates a new immutable **segment** (a mini inverted index) that becomes
searchable. This **refresh** happens every 1 second by default, so a newly indexed document is
typically searchable after about a second, not instantly. A **translog** (write-ahead log)
protects writes not yet flushed to disk. Small segments are merged in the background, like the
compaction of an LSM tree (chapter 06).

| Search engine gains | Search engine costs |
|---|---|
| Fast full-text queries, relevance ranking, fuzzy matching, facets/aggregations | Not a system of record: weaker transactional guarantees, no multi-document transactions |
| Scales horizontally by shards | Near-real-time, not real-time; data is a copy that can drift |
| Rich analyzers per language | Reindexing large indices is slow; mapping mistakes are costly |

> **Say it like this:** "Postgres stays the source of truth. Elasticsearch is a derived read
> model for search: we feed it from the database's change stream, and we accept it's a second
> or two behind."

### Keeping search in sync with the database

The data lives in the database; search needs a copy. The naive approach is a **dual write**: the
app writes to the DB, then writes to the search index.

```text
 App --1. INSERT--> DB          (succeeds)
 App --2. index ---> Search     (fails: timeout / crash between 1 and 2)
 -> DB and search disagree forever; no one notices.
```

The **dual-write problem**: when you write to two systems without a shared transaction, any
failure or reordering between the writes leaves them inconsistent. Retrying does not fully solve
it: the app may crash after step 1, and two concurrent updates may reach the index in the wrong
order.

The robust fix is **change data capture (CDC)**: read the database's own commit log (MySQL
binlog, Postgres write-ahead log) and turn every committed change into an event, typically
published to Kafka (chapter 11), which an indexer consumes.

```text
 App --write--> DB --commit log--> CDC connector --> Kafka topic "products.changes"
                                                          |
                                                          v
                                                  Indexer consumer --> Search index
```

- Every committed change is captured, in commit order per row, including changes from scripts
  and other services that forgot to "also update search".
- The indexer can be rebuilt from scratch by replaying.
- Pay: more moving parts, a small lag, and the indexer must be idempotent (chapter 11).

The transactional outbox (chapter 11) is the alternative when you want to publish domain events
rather than raw row changes.

## 09.3 OLTP vs OLAP

Two very different workloads hit data systems:

- **OLTP (online transaction processing)**: the app's day-to-day reads and writes. "Get order
  9123", "insert a payment", "update this cart".
- **OLAP (online analytical processing)**: questions over large amounts of history. "Revenue by
  country by week for the last two years", "average session length by device".

| | OLTP | OLAP |
|---|---|---|
| Typical query | Read/write a few rows by key | Scan and aggregate millions to billions of rows |
| Columns touched | Most columns of a few rows | A few columns of many rows |
| Writes | Many small, concurrent | Bulk loads, append-mostly |
| Latency goal | Milliseconds | Seconds (sometimes sub-second for dashboards) |
| Users | The application, many concurrent | Analysts, dashboards, ML jobs |
| Storage layout | Row-oriented | Column-oriented |
| Examples | PostgreSQL, MySQL | BigQuery, Snowflake, Redshift, ClickHouse, Druid |

Running heavy analytics on the production OLTP database is a classic mistake: one big scan
evicts hot pages from the buffer pool and slows every user request. Analytics gets its own copy.

## 09.4 Row vs columnar storage

A table of ad clicks:

```text
 click_id | ts                  | campaign | country | cost
 1        | 2026-10-01 10:00:01 | c7       | IN      | 0.12
 2        | 2026-10-01 10:00:02 | c3       | US      | 0.40
 3        | 2026-10-01 10:00:02 | c7       | IN      | 0.12
```

**Row storage** puts each row's values together on disk:

```text
 [1|10:00:01|c7|IN|0.12] [2|10:00:02|c3|US|0.40] [3|10:00:02|c7|IN|0.12] ...
```

**Columnar storage** puts each column's values together:

```text
 click_id: [1, 2, 3, ...]
 ts:       [10:00:01, 10:00:02, 10:00:02, ...]
 campaign: [c7, c3, c7, ...]
 country:  [IN, US, IN, ...]
 cost:     [0.12, 0.40, 0.12, ...]
```

Query: `SELECT country, SUM(cost) FROM clicks GROUP BY country`.

Why columnar wins for this:

1. **Column pruning**: read only `country` and `cost`. With 50 columns, that is roughly 4% of the
   bytes a row store would read.
2. **Compression**: values in one column are the same type and often repetitive. `country` has
   ~200 distinct values across billions of rows → **dictionary encoding** (store small integer
   codes) plus **run-length encoding** when sorted (`IN ×50000`). Compression ratios of roughly
   5–10x are common, so even less I/O.
3. **Vectorized execution**: the CPU processes tight arrays of one type in batches, which suits
   CPU caches and SIMD instructions.
4. **Skipping**: files store min/max per block; a filter `ts >= yesterday` skips blocks whose max
   is older.

Why row storage wins for OLTP: fetching or updating one whole row touches one place instead of 50
column files; columnar stores are slow at single-row updates and usually batch or append.

Common columnar file formats: **Parquet** and **ORC**, both widely used in data lakes.

> **Say it like this:** "Analytics queries read a few columns across billions of rows, so a
> columnar store reads only those columns, compresses them 5–10x, and scans them vectorized.
> That's why we don't run these dashboards on the OLTP database."

## 09.5 Data warehouse, data lake, lakehouse

- **Data warehouse**: a managed analytical database with a defined schema, SQL, and columnar
  storage. Data is cleaned and modeled before loading. Examples: Snowflake, BigQuery, Redshift.
- **Data lake**: raw files (JSON, CSV, Parquet, images, logs) dumped in cheap object storage,
  structured later by whoever reads them ("schema on read"). Engines like Spark, Trino/Presto,
  or Hive query the files.
- **Lakehouse**: a lake plus an **open table format** (Apache Iceberg, Delta Lake, Apache Hudi)
  that adds table features on top of the files: schema, ACID commits, snapshots ("time travel"),
  and efficient updates/deletes. Warehouse-like behavior at lake cost.

| | Warehouse | Lake | Lakehouse |
|---|---|---|---|
| Storage | Managed, proprietary-ish | Object storage, open files | Object storage, open files + table metadata |
| Schema | On write (strict) | On read (loose) | On write, evolvable |
| Data types | Structured | Anything | Mostly structured/semi-structured |
| Transactions | Yes | No (just files) | Yes (via table format) |
| Cost | Higher | Lowest | Low |
| Risk | Lock-in, cost at scale | "Data swamp": nobody knows what is in it | Newer, more to operate |

### ETL vs ELT

- **ETL (extract, transform, load)**: pull from sources, transform in a separate processing
  layer, load the clean result into the warehouse. Older pattern, from when warehouse compute
  was expensive.
- **ELT (extract, load, transform)**: load raw data into the warehouse/lakehouse first, then
  transform with SQL inside it. Common today because warehouse compute is elastic, and keeping
  the raw data lets you re-run transformations when logic changes.

## 09.6 Batch processing

**Batch processing** runs a job over a large, bounded set of input (yesterday's logs, all orders)
and produces output. Latency: minutes to hours. Throughput: huge.

### MapReduce with word count

**MapReduce** is the idea of splitting a big job into a parallel **map** step, a **shuffle**
that groups by key, and a parallel **reduce** step.

```text
 Input split 1: "red shoe red"          Input split 2: "blue shoe"

 MAP (each split, in parallel): emit (word, 1)
   split1 -> (red,1) (shoe,1) (red,1)   split2 -> (blue,1) (shoe,1)

 SHUFFLE: group by key, send each key to one reducer (hash(key) mod R)
   reducer A gets: red -> [1,1]   blue -> [1]
   reducer B gets: shoe -> [1,1]

 REDUCE: sum each list
   red 2, blue 1, shoe 2
```

The framework handles splitting input, scheduling tasks near the data, retrying failed tasks,
and the shuffle. Because tasks are deterministic and inputs are immutable, a failed task can be
simply re-run. That is the key to fault tolerance at scale.

DSA bridge: the shuffle is a distributed group-by — a hash partition followed by a sort or hash
aggregation on each reducer.

### Spark-style processing

Classic MapReduce writes intermediate results to disk between every stage, which is slow for
multi-step jobs. **Spark** represents a job as a graph (DAG) of transformations over partitioned
datasets, keeps intermediate data in memory where possible, and recomputes lost partitions from
their lineage on failure. You write high-level operations (`filter`, `groupBy`, `join`, SQL), and
the engine plans the stages and shuffles.

| Batch gains | Batch costs |
|---|---|
| Simple to reason about: fixed input, deterministic output | Results are hours old |
| Easy to re-run after a bug fix | Big bursts of compute |
| Very high throughput | Not useful for real-time reactions |

## 09.7 Stream processing

**Stream processing** handles unbounded data continuously, event by event or in small batches,
producing results within seconds. Engines: **Apache Flink**, **Kafka Streams**, Spark Structured
Streaming. Input usually comes from Kafka (chapter 11).

### Windows

An unbounded stream has no "end" to aggregate over, so you aggregate over **windows** of time.

```text
 Tumbling (fixed, non-overlapping), size 1 min:
 |--- w1 ---|--- w2 ---|--- w3 ---|
 10:00      10:01      10:02      10:03

 Sliding / hopping, size 1 min, advance 30 s (overlapping; an event is in 2 windows):
 |--- w1 ---|
       |--- w2 ---|
             |--- w3 ---|

 Session (per key; closes after a gap of inactivity, e.g. 30 min):
 user A: x x  x         x x            (gap > 30 min splits sessions)
         [session 1]    [session 2]
```

| Window | Use for |
|---|---|
| Tumbling | "Clicks per campaign per minute" for billing/dashboards |
| Sliding | "Requests in the last 5 minutes, updated every 10 s" for alerting |
| Session | User sessions, engagement analysis |

### Event time vs processing time

- **Event time**: when the event actually happened (timestamp set by the device or the first
  server).
- **Processing time**: when the stream processor sees it.

They differ because of network delays, retries, mobile phones that were offline for an hour, and
backlog after an outage. If you window by processing time, a burst of delayed events after an
outage lands in the wrong minute. For correct analytics, window by event time.

### Watermarks

Using event time raises a question: when is the 10:00–10:01 window *done*? Late events can
always arrive. A **watermark** is the processor's assertion "I believe I have now seen all
events with event time ≤ T". It is usually computed as "max event time seen minus an allowed
lateness" (e.g. 10 s).

```text
 events arrive (event times): 10:00:58  10:01:03  10:00:59  10:01:15 ...
 watermark = max seen - 10 s:                           -> 10:01:05
 window [10:00, 10:01) can now close and emit its result.
 an event for 10:00:40 arriving after that is "late":
    drop it, send it to a side output, or update the result (if allowed lateness permits)
```

The trade-off: a generous lateness → more complete results but later output; a tight one →
faster output but more late events.

> **Say it like this:** "I'd aggregate on event time with tumbling one-minute windows and a
> watermark of, say, 30 seconds of allowed lateness. Late events go to a correction path rather
> than being silently counted in the wrong minute."

### Stream processing state and correctness

Stream jobs keep **state** (running counts per key per window). Engines checkpoint that state
together with the input offsets periodically, so after a crash they restore both and resume
consistently. With transactional or idempotent sinks, this gives "effectively once" results
(chapter 11 explains exactly-once and its limits).

## 09.8 Lambda vs Kappa architecture

**Lambda architecture**: run two paths over the same events.

```text
              +--> batch layer (Spark over the lake, hours later, exact) --+
 events ------+                                                            +--> serving
              +--> speed layer (stream job, seconds later, approximate) ---+    (merge)
```

- Gain: fast approximate numbers now, exact numbers later; batch recomputation fixes stream bugs.
- Pay: the same logic written and maintained twice, in two systems, and the two must agree.

**Kappa architecture**: only a stream path. To reprocess (bug fix, new logic), replay the
retained event log (Kafka with long retention, or the lake) through a new version of the stream
job, then switch over.

```text
 events --> durable log (Kafka) --> stream job v1 --> serving table v1
                              \--> stream job v2 (replay from start) --> table v2 -> switch
```

- Gain: one codebase.
- Pay: needs long retention and a stream engine strong enough for big replays.

Many real systems are a mix: a stream path for freshness plus periodic batch jobs for
reconciliation and heavy ML features.

## 09.9 Worked example: analytics pipeline for ad clicks

**Requirements**: an ad platform records about 50,000 click events/sec at peak. Advertisers want
a dashboard of clicks and spend per campaign per minute with less than a minute of delay. Billing
needs exact, deduplicated totals daily. Analysts want ad-hoc queries over a year of raw data.

```text
  Ad servers / click trackers
        |  click event {click_id, ts, campaign, ad, user, country, cost}
        v
  +--------------------+
  | Kafka "clicks"     |  partitioned by campaign_id (or click_id for even spread)
  | retention 7 days   |
  +----+----------+----+
       |          |
       |          +-------------------------------+
       v                                          v
  +-------------------------+          +----------------------------+
  | Stream job (Flink-like) |          | Lake sink                  |
  | - dedupe by click_id    |          | writes Parquet files to    |
  | - event-time tumbling   |          | object storage, hourly     |
  |   1-min windows         |          | partitions dt=.../hr=...   |
  | - watermark ~30 s       |          +-------------+--------------+
  +-----------+-------------+                        |
              |                                      v
              v                              +------------------------+
  +-------------------------+                | Batch jobs (Spark-like)|
  | OLAP store (ClickHouse/ |                | daily exact dedupe,    |
  | Druid-like), table      |<-- corrections-| billing totals, fraud  |
  | campaign_minute_agg     |                | filtering, ML features |
  +-----------+-------------+                +------------------------+
              |
              v
       Advertiser dashboards (sub-second queries over pre-aggregated rows)
```

Design decisions and why:

1. **Kafka in the middle**: buffers spikes, lets several independent consumers (stream job, lake
   sink, fraud detection) read the same events, and allows replay. See chapter 11.
2. **Deduplicate by `click_id`**: trackers retry, so the same click can arrive twice. The stream
   job keeps recently seen IDs in state (with a TTL a bit longer than the retry window).
3. **Event-time windows + watermark**: a click at 10:00:59 that arrives at 10:01:20 still counts
   in the 10:00 minute.
4. **Pre-aggregation**: 50,000 events/s become roughly (active campaigns × 1) rows per minute.
   Dashboards query small tables quickly.
5. **Raw data to the lake**: columnar Parquet partitioned by date/hour; cheap to keep for a year.
   The daily batch job produces the authoritative billing numbers and corrects the real-time
   aggregates if they drifted (late events, fraud removed). This is Lambda-flavored on purpose:
   money gets the exact path.

Rough sizing (chapter 04 style): 50,000 events/s × ~200 bytes ≈ 10 MB/s ≈ 860 GB/day raw;
Parquet compression of roughly 5–10x brings it to roughly 90–170 GB/day in the lake, around
30–60 TB/year before replication.

> **Say it like this:** "Clicks land in Kafka. A stream job dedupes by click ID and aggregates
> per campaign per minute on event time into an OLAP store for dashboards, while raw events go
> to the lake as Parquet. The daily batch job is the source of truth for billing."

## 09.10 Time-series and log storage, briefly

**Time-series data**: numeric measurements keyed by metric name + labels + timestamp (CPU usage,
request latency, temperature). Patterns: append-only, recent data queried most, queries are
ranges and aggregations over time.

**Time-series databases** (Prometheus, InfluxDB, TimescaleDB, VictoriaMetrics) optimize for this:

- Compress consecutive timestamps and values heavily (delta and XOR encodings).
- **Downsampling / rollups**: keep 10-second resolution for a week, 5-minute resolution for a
  year.
- **Retention**: drop old raw data automatically.
- Watch **cardinality**: each unique label combination is a separate series. Putting a user ID
  in a label creates millions of series and can bring the system down.

**Logs**: semi-structured text events. Typical stacks ship logs from hosts to a store that
indexes them for search (Elasticsearch/OpenSearch) or indexes only labels and keeps compressed
chunks in object storage (Loki-style, cheaper but slower for free-text search). Use structured
(JSON) logs, sample high-volume debug logs, and set retention by tier. Chapter 14 covers
observability in depth.

## 09.11 Recap

- Store files in object storage (buckets + keys, immutable objects); keep only metadata and the
  key in the database.
- Pre-signed URLs let clients upload/download directly; multipart upload makes big files
  resumable and parallel; storage classes and lifecycle rules cut cost.
- An inverted index maps term → sorted posting list; the same analyzer (tokenize, lowercase,
  stem) must run at index and query time.
- TF-IDF rewards frequent-in-doc, rare-in-corpus terms; BM25 adds TF saturation and length
  normalization.
- Elasticsearch-style engines shard an index, replicate shards, and are near-real-time (about 1 s
  refresh); they are derived copies, not the source of truth.
- Dual writes drift; feed derived stores via CDC from the database's commit log.
- OLTP = many small row operations; OLAP = big scans of few columns; columnar storage wins OLAP
  through pruning, compression, and vectorization.
- Warehouse (managed, schema-on-write), lake (raw files), lakehouse (lake + table format with
  ACID); ELT is the common modern flow.
- Batch = bounded input, high throughput, hours of latency; stream = unbounded input, windows,
  event time, watermarks.
- Lambda runs batch + stream paths; Kappa runs stream only and replays the log to reprocess.

## 09.12 Check yourself

1. Why not store user-uploaded images as BLOBs in the main database?

<details><summary>Answer</summary>

Database storage is expensive and its backups and replication slow down as it bloats; every
download consumes DB and app server resources; it is hard to put a CDN in front. Object storage
is cheap, durable, scales to petabytes, can be accessed directly via pre-signed URLs, and works
as a CDN origin. The DB keeps the key and metadata.

</details>

2. Walk through how a pre-signed upload works and what you still must do server-side.

<details><summary>Answer</summary>

The client asks the app to upload; the app authorizes, picks an object key, and returns a
short-lived URL signed for PUT on that key. The client uploads bytes directly to the object
store. Afterwards the app (on client confirmation or a storage event) validates size and type,
scans if needed, records metadata, and triggers processing. A cleanup job or lifecycle rule
removes uploads that were never confirmed.

</details>

3. Build the posting lists for: doc1 "Cats chase mice", doc2 "A cat sleeps". Assume
   lowercasing, stemming, and stop-word removal.

<details><summary>Answer</summary>

After analysis: doc1 → [cat, chase, mouse]; doc2 → [cat, sleep] ("a" removed as a stop word).
Posting lists: cat → [1, 2], chase → [1], mouse → [1], sleep → [2]. (With a simple stemmer
"mice" may stay "mice"; a lemmatizer maps it to "mouse".)

</details>

4. A new product is saved in the DB but does not show up in search for about one second. Is
   this a bug?

<details><summary>Answer</summary>

Not necessarily. Lucene-based engines are near-real-time: new documents become searchable at the
next refresh (default about 1 s), plus any CDC/indexing pipeline lag. If immediate visibility is
required for that user, show the item from the DB directly or force a refresh for that write
(costly at high volume).

</details>

5. Why is the query `SELECT country, SUM(cost) FROM clicks GROUP BY country` much faster in a
   columnar store?

<details><summary>Answer</summary>

It reads only the two needed columns instead of whole rows (column pruning); those columns
compress very well (dictionary and run-length encoding), so far fewer bytes come off disk; and the
engine aggregates tight, typed arrays with vectorized execution. Block min/max statistics can
skip data for filters too.

</details>

6. What is the difference between event time and processing time, and what problem does a
   watermark solve?

<details><summary>Answer</summary>

Event time is when the event happened; processing time is when the processor sees it. Delays
make them differ, so correct aggregation uses event time. Then the processor must decide when a
window is complete even though late events can arrive; a watermark (roughly max event time
seen minus allowed lateness) declares "events up to T have arrived", letting windows close.
Later events are handled as late (dropped, side output, or result updates).

</details>

7. Your app writes to Postgres and then calls Elasticsearch to index. Occasionally the two
   disagree. Why, and what is the fix?

<details><summary>Answer</summary>

It is the dual-write problem: the two writes are not atomic, so a crash, timeout, or reordering
between them leaves them inconsistent. Fix: make the DB the only write, and propagate changes
from its commit log via CDC (or a transactional outbox) to Kafka, with an idempotent indexer
consuming in order per entity.

</details>

8. Lambda or Kappa for a click pipeline where billing must be exact? Justify.

<details><summary>Answer</summary>

A common choice is a Lambda-flavored setup: a stream path for fast dashboards plus a batch path
over raw lake data that produces authoritative, deduplicated billing totals and corrects the
real-time numbers. Kappa can work too if the stream job is exactly-once with idempotent sinks
and the log is retained long enough to replay, but many teams keep a batch reconciliation for
money.

</details>
