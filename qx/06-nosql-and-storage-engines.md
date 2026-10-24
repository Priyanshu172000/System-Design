# 06 — NoSQL and storage engines: quick notes

NoSQL is not one thing. It is a set of database families, each built for one way of reading and
writing data. This chapter also shows what happens on disk, which explains why some databases
are fast at writes and others at reads.

## 1. Why NoSQL appeared

- **NoSQL** ("not only SQL") means databases that drop some SQL features (joins, broad
  transactions) to gain something else.
- Pain 1, **scale-out** (spreading data over many machines): one SQL server can't take it, and
  splitting SQL by hand breaks joins. *e.g.* rider pings at ~25,000 writes/s at peak.
- Pain 2, **flexible schema** (records with different fields). *e.g.* a pizza has size and
  crust, a biryani has spice level and serves; one fixed table fits neither well.
- Pain 3, **narrow access pattern** (the few ways the data is ever read). *e.g.* pings are only
  "append a ping for `rd_5521`" and "latest pings for `rd_5521`". No joins needed.

## 2. The families: pick by access pattern

| Family | What it is | QuickBite example | Main weakness |
|---|---|---|---|
| **Key-value** | A giant hash map: get/put by key | Session `sess:9f3a`, OTP counter that expires in 10 min | Only lookup by key |
| **Document** | Nested JSON-like records | Spice Route's menu as one doc `r_77` with `items[]` | Many-to-many is awkward |
| **Wide-column** | Data split by a key, rows sorted inside | Rider ping history by `(rd_5521, day)` | Queries must be planned up front |
| **Graph** | Nodes and edges | Accounts sharing cards and phones, 3 hops from `u_1842` | Hard to spread over machines |
| **Time-series** | (series, time, value) points | `rider_speed{city="BLR"}` average per minute | Too many unique tags |
| **Search** | Word → list of documents | "biryani" → `[r_77, r_12, ...]` (ch 09) | Not the main copy of data |
| **Vector** | Finds nearest number-lists (embeddings) | "dishes like this biryani" | Approximate; one more store |

- **Key-value:** some (DynamoDB-style) add a **sort key**, so items under one key stay sorted.
  *e.g.* key `user#u_1842`, sort key between `order#2026-10-01` and `order#2026-10-31` gives
  Asha's October orders in one query.
- **Document:** a write to one document is all-or-nothing. Writes across documents are possible
  but cost more. *e.g.* order `o_90311` as one doc holds its items and address.
- **Embed vs reference:** **embed** (nest inside) small, owned children read with the parent;
  **reference** (store an id) shared or ever-growing data. *e.g.* embed the order's items;
  reference `restaurant_id: "r_77"` (shared by many orders), but freeze the dish name and price.
- **Time-series:** keeps fine data briefly, averages longer. *e.g.* 10 s points for a week,
  5 min averages for a year. Trap: each unique tag combination is a new series, so a
  `user_id` tag creates millions.
- **Vector (black box):** uses **ANN** (approximate nearest neighbor) indexes such as HNSW or IVF.
  Use it when exact search over millions of vectors is too slow. Cost: it sometimes misses a
  true neighbor.

## 3. Wide-column: design tables from queries

In SQL you model things, then ask anything. In a wide-column store (Cassandra) you **model each
query**: one table per query, each answered by reading one partition.

- **Partition key:** decides which machine stores the row. Every query must give all of it.
  *e.g.* `(rider_id, day)`.
- **Clustering key:** the sort order of rows inside a partition. *e.g.* `ts DESC`, newest first.
- **Bucket:** add a time piece (like `day`) to the partition key so no partition grows forever.
  Keep partitions under ~100 MB. *e.g.* 1 ping per 4 s is at most 21,600 rows per rider per
  day; without `day`, one rider builds ~3.3 M rows a year in one partition.

```text
partition (rd_5521, 20261010): [00:02:00, 00:01:56, ...]  newest first
partition (rd_5521, 20261009): [23:59:56, 23:59:52, ...]
latest 50 at 00:02 -> 30 from today, 20 more from yesterday
```

- **Same data, several tables:** the app writes each table itself. *e.g.* one ping also goes to
  `pings_by_order` for "where is my order". These writes are not atomic, so make them safe to
  retry.
- **Mistake:** a partition key with few values, like `city` or `status`. A few machines do all
  the work while the rest sit idle.

## 4. Storage engines: B-tree vs LSM tree

A **storage engine** is the part of a database that puts bytes on disk.

- **B-tree engine** (PostgreSQL, MySQL InnoDB, MongoDB default): **update in place**. Find the
  page and change it. *e.g.* a ~100-byte biryani price change rewrites a whole 16 KB page at a
  random disk spot.
- **LSM tree** (Cassandra, RocksDB, HBase): **never edit in place; append, then merge later**.
  - Write goes to the **WAL** (a log for crash safety) and the **memtable** (a sorted list in
    memory).
  - When the memtable fills (tens of MB), it is written out as an **SSTable**: a sorted file
    that is never changed.
  - **Compaction:** background merging of SSTables, keeping only the newest value per key (the
    merge step of merge sort).
  - Read: check memtable, then files newest to oldest. The first hit wins.
  - **Tombstone:** a delete is written as a "deleted" marker. Compaction removes it later. Many
    deletes slow reads until then.

```text
put biryani=18000, raita=9000 -> flush SST-1
put biryani=20000, delete raita -> flush SST-2 [biryani:20000, raita:TOMBSTONE]
get raita -> SST-2 says TOMBSTONE -> not found
compact -> SST-3 [biryani:20000]
```

- **Bloom filter:** a small bit array per SSTable that answers "definitely not here" or "maybe
  here". No false "not here", about 1% false "maybe" at ~10 bits per key. *e.g.* `get kebab`
  skips every file with zero disk reads. Helps single-key lookups, not range scans.
- **Compaction styles (black box):** **size-tiered** merges similar-size files (better for
  writes); **leveled** keeps each level ~10x bigger with no overlap (better for reads).
- **Amplification:** extra disk work. *e.g.* write amp: a 100-byte change costing 16 KB is
  ~160x. Read and space amp too; you can shrink any two, never all three (**RUM conjecture**).

| | B-tree | LSM tree |
|---|---|---|
| Writes | Random page rewrites | Sequential appends, usually faster |
| Point reads | One descent, predictable | Several files; bloom filters help |
| Range scans | Excellent | Good, more work |
| Deletes | In place | Tombstones until compaction |
| Compression | Weaker | Very good |

"LSM is faster" is a myth: it's faster for write-heavy work, often slower for read-heavy work.

## 5. SQL or NoSQL: how to decide

- Default to SQL. Leave it only when something forces you.
- Ask in order: (1) top queries and their rates, (2) what must change together, (3) size and
  rate in 1–3 years, (4) how fresh data must be, (5) data shape, (6) must writes survive a node
  loss, (7) what the team can run.
- *e.g.* orders and payments: ~120 writes/s, money must be exact → **PostgreSQL**. Rider pings:
  ~25,000 writes/s, a few seconds stale is fine → **wide-column** with auto-expiry.
- Myths: "NoSQL is faster" (only for its one pattern), "no schema" (the schema moved into app
  code), "no transactions" (many have limited ones), "MongoDB because JSON" (PostgreSQL has
  `JSONB`).

## 6. Distributed SQL (NewSQL)

- **Distributed SQL** (Spanner, CockroachDB): SQL and transactions, with data split across
  machines automatically.
- Data is cut into **ranges**. Each range has ~3 copies, and a write commits when a
  **majority** agrees (Raft, ch 10). *e.g.* order 90311's range sits on nodes 2, 4, 5; node 5
  is down, nodes 2 and 4 agree, write succeeds.
- Cost: every commit waits for a majority round trip; transactions across ranges are slower.
  *e.g.* order + Asha's wallet in different ranges. Not a default for small apps.

## 7. Polyglot persistence

- **Polyglot persistence:** several databases, each for one access pattern, fed from one
  **system of record** (the true copy).
- *e.g.* Spice Route changes biryani to ₹200: PostgreSQL commits → change event to Kafka →
  Redis cache cleared → search reindexes (shows the old price briefly) → warehouse loads later.
- Cost: more systems to run, and copies lag. Add a store only when a real need demands it.

## Remember these 5

1. NoSQL is many families; pick one by how the data is read and written.
2. Wide-column: partition key = what you always look up by; bucket by time to stay bounded.
3. B-tree edits pages in place (steady reads); LSM appends and merges later (fast writes).
4. Bloom filter: "definitely not" or "maybe", and it lets reads skip most files.
5. Default to SQL; one system of record, every other store is a copy.
