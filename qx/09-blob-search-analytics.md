# 09 — Files, search and analytics: quick notes

A relational database is the wrong tool for three jobs: storing big files, "search by any
word", and big reports over years of data. Each gets its own kind of store, fed from the main
database.

## 1. Object storage for files

- **Object storage** (S3, GCS) keeps files of any size cheaply and safely, over HTTP.
- **Bucket:** a top-level container. **Key:** the file's name, like
  `restaurants/r_77/dishes/m_101/8f3a.jpg`. It looks like a folder path, but it's just a name.
- **Object:** the bytes plus a little metadata. Objects are not edited in place; you upload a
  new one.
- The database row stores only the key. *e.g.* `menu_items.photo_key` is ~50 bytes; the 2 MB
  photo lives in the bucket.
- **Pre-signed URL:** a link valid for ~15 minutes, for one file and one action. The app
  uploads straight to the bucket, so restaurant-service never touches the bytes.
- Clean up uploads that never got confirmed, with a scheduled job or an auto-delete rule.
- **Multipart upload (black box):** split a big file into parts sent in parallel. *e.g.* a
  600 MB video = 6 parts of 100 MB; if part 4 fails, resend only part 4.
- **Storage classes (black box):** hot (fast, costly), infrequent, archive (cheap, hours to
  restore). **Lifecycle rules** move files as they age. *e.g.* delivery-proof photos: hot for
  30 days, then archive.
- Put a **CDN** in front for public files; use a pre-signed GET for private ones.

## 2. Full-text search: the inverted index

`LIKE '%biryani%'` scans every row, can't rank, and misses "biryanis". An **inverted index**
maps each word to the sorted list of documents containing it (a **posting list**), like the
index at the back of a book.

- **Tokenize:** split text into words.
- **Analyzer:** lowercase, drop filler words ("with", "and"), cut words to their root
  (**stemming**: `wings` → `wing`).
- Use the **same analyzer** for indexing and queries, or "biryanis" never matches "biryani".

```text
doc 1 "Chicken Biryani with Raita"   doc 2 "Veg Biryani Bowls"
biryani -> [1, 2]    chicken -> [1, 3]    raita -> [1]
query "Chicken Biryanis" -> [chicken, biryani]
AND = two-pointer walk over [1,3] and [1,2] -> {1}
```

- **Ranking:** **TF** (term frequency): more mentions in a doc means more relevant. **IDF**:
  rare words count more. *e.g.* over 1 M dishes, "raita" (in 2,000) counts ~4x more than
  "chicken" (in 200,000).
- **BM25** (the default): the 10th "chicken" adds little, and a match in a short dish name
  beats one in a long description.

## 3. Elasticsearch-style engines

- **Elasticsearch (black box):** stores JSON **documents** in an **index** split into a fixed
  number of **shards**, each with replicas. Use it for search, fuzzy match and filters. It is
  **not** the main copy of your data.
- **Query = scatter-gather:** ask one copy of each shard for its top 10, then merge. *e.g.* 3
  shards return 30 candidates; the coordinator keeps the best 10.
- **Near-real-time:** a new dish becomes searchable after the next **refresh**, ~1 s later.

## 4. Keeping search in sync: CDC, not dual write

- **Dual write:** the app writes the DB, then the search index. It breaks if the app crashes in
  between, or two updates arrive out of order. *e.g.* DB says "Dum Biryani", search says
  "Hyderabadi Biryani" forever.
- **CDC** (change data capture): read the database's own commit log and publish each change to
  Kafka; an indexer applies them in order. *e.g.* change 811 then 812 → index ends at
  "Dum Biryani".
- CDC catches every write, even from scripts that "forgot search", and lets you rebuild the
  index by replaying. Cost: more parts, a second or two of lag.

## 5. OLTP vs OLAP: row vs column storage

- **OLTP** (live app traffic): many tiny reads and writes of whole rows, in milliseconds.
  *e.g.* fetch `o_90311`. Row storage (Postgres).
- **OLAP** (analytics): scan a few columns of millions of rows, in seconds. *e.g.* revenue by
  city over two years. Column storage (BigQuery, Snowflake, ClickHouse).
- Running heavy reports on the live DB slows every user, so analytics gets its own copy.

```text
row:      [o_90311|u_1842|r_77|BLR|450|...] [o_90312|...]
columnar: city: [BLR, DEL, BLR, ...]   amount: [450, 220, 310, ...]
```

- *e.g.* 1.46 B orders × 500 B ≈ 730 GB as rows. Read only 2 of 50 columns → ~29 GB.
  Compress (cities become small numbers) → ~3–6 GB. Skip blocks by date → less still.
- Column storage is slow for single-row updates, so it suits bulk loads. File formats:
  **Parquet**, **ORC**.

## 6. Warehouse, lake, lakehouse

- **Warehouse:** a managed column database of cleaned data (Snowflake, BigQuery). Easy, pricier.
- **Data lake:** raw files in object storage, queried with Spark or Trino. Cheapest; can turn
  into a mess.
- **Lakehouse:** a lake plus a table format (Iceberg, Delta Lake) that adds schemas, safe
  commits and updates.
- **ETL:** clean the data, then load it. **ELT** (common today): load raw, clean with SQL
  inside. *e.g.* refund logic changes → re-run the SQL over raw `order-events`.

## 7. Batch processing

- **Batch:** a job over a fixed input (yesterday's orders); results arrive hours later.
- **MapReduce:** **map** each record to (key, 1), **shuffle** to group by key, **reduce** to sum.
  *e.g.* "biryani dosa biryani" → biryani 2, dosa 1.
- If a machine dies, re-run its task on the same unchanging input and get the same answer.
- **Spark (black box):** multi-step batch jobs, keeping data in memory between steps.

## 8. Stream processing

- **Stream:** never-ending data processed as it arrives, results in seconds (Flink, Kafka
  Streams).
- Since there's no "end", count over **windows**:

| Window | Shape | QuickBite example |
|---|---|---|
| **Tumbling** | Fixed, no overlap | Orders per restaurant per minute |
| **Sliding** | Overlapping | Failed payments in the last 5 min, updated every 10 s |
| **Session** | Ends after a quiet gap | Asha's browsing session (30 min gap) |

- **Event time** (when it happened) differs from **processing time** (when it arrived).
  *e.g.* a rider's phone was offline. Window by event time.
- **Watermark:** "I've probably seen everything up to time T", usually newest time seen minus
  allowed lateness (say 10 s). Once it passes 20:01, the [20:00, 20:01) window is emitted.
  Later events are dropped or used to fix the result.
- State and input position are saved together, so after a crash the job replays and gets the
  same counts.
- **Lambda (black box):** a batch path (exact, hours late) plus a stream path (fast, rough).
  **Kappa:** stream only, replay the log to recompute. *e.g.* live orders/min from the stream;
  restaurant payouts from the nightly batch.

## 9. Worked example and the rest

- Ad clicks at ~50,000/s: Kafka → stream job (drop duplicate click ids, 1-min windows) →
  OLAP store for live dashboards; Kafka → Parquet in the lake → daily batch for exact billing.
- *e.g.* a click retried by its tracker reaches Kafka twice; the stream keeps one copy; the
  batch does the exact dedupe for billing.
- Size: 50k/s × ~200 B ≈ 860 GB/day raw, roughly 90–170 GB/day as Parquet.
- **Time-series DBs (black box):** for metrics (Prometheus). Watch **cardinality**. *e.g.*
  adding a `rider_id` label turns hundreds of series into ~100,000 × cities.
- **Logs:** fully indexed (Elasticsearch) or cheaper, label-indexed in object storage (Loki).

## Remember these 5

1. Files go to object storage; the DB keeps the key; clients upload with pre-signed URLs.
2. Inverted index: word → sorted doc list; AND is a two-pointer intersection.
3. Search is a derived copy fed by CDC, never by dual writes.
4. Analytics reads few columns of many rows, so columnar storage reads ~100x fewer bytes.
5. Streams window by event time and close windows with watermarks; money keeps an exact batch path.
