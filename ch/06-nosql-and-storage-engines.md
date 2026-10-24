# 06 — NoSQL families and how storage engines work

"Should we use SQL or NoSQL?" comes up in nearly every design discussion, and it is usually
asked badly. This chapter replaces the slogan with specifics: what each NoSQL family is good at,
how you model data for one, and what is happening on disk (B-trees vs LSM trees) that makes some
databases write-fast and others read-fast. After it you can justify a storage choice by access
pattern instead of by fashion.

## 06.1 Why NoSQL emerged

Relational databases (chapter 05) were designed for one powerful machine. Around the late 2000s,
large web companies hit three kinds of pain:

1. **Scale-out.** Data and write volume grew beyond one machine. Splitting a relational database
   across machines by hand (sharding, chapter 07) breaks joins, multi-row transactions and
   foreign keys, and needs lots of custom tooling. Systems designed from day one to spread data
   across many cheap machines, add nodes without downtime and survive node failures became
   attractive.
2. **Flexible schema.** Products whose records vary a lot (a product catalog where a TV has
   different attributes than a shirt), or that change shape weekly, found rigid schemas and
   slow `ALTER TABLE` on huge tables painful.
3. **Specific access patterns.** Many workloads only ever do "get by key" or "append an event,
   read the latest 50 for this user". A general-purpose relational engine pays for features
   (joins, ad-hoc queries, strict transactions across everything) these workloads do not use.

**NoSQL** ("not only SQL") is an umbrella name for databases that drop some relational features
(usually joins, flexible ad-hoc queries, or multi-row transactions across the whole dataset) in
exchange for one of: easier horizontal scaling, flexible data shapes, or a data model matched to
a specific access pattern. It is not one technology; the families below differ from each other
as much as they differ from SQL.

> **Say it like this:** "NoSQL isn't a single thing. I pick a family by access pattern: key-value
> for lookups by id, wide-column for huge write-heavy partitioned data read by key ranges,
> document for self-contained aggregates, graph for many-hop relationships."

## 06.2 The families

### Key-value stores

**Model:** a giant distributed hash map: `key -> value`. The database usually does not look
inside the value.

**Operations:** `GET key`, `PUT key value`, `DELETE key`, sometimes with a TTL (time-to-live,
auto-expiry) and atomic operations like increment.

**Examples:** Redis (in-memory, with rich value types: strings, hashes, lists, sorted sets,
streams), Memcached (in-memory cache only), DynamoDB-style stores (durable, partitioned,
managed), Aerospike, RocksDB (an embedded key-value library that other databases build on).

**Serves:** sessions, caches, feature flags, shopping carts, rate-limit counters, user
profiles fetched by id, leaderboards (Redis sorted sets).

**Trade-off:** extremely fast and easy to scale (hash the key to pick a machine), but you can
only ask questions by key. "All carts containing product 5" is impossible without a separate
index you maintain.

DynamoDB-style stores extend pure key-value with a **partition key + sort key**: items with the
same partition key are stored together, sorted by the sort key, so you can query
"`user#7`, sort key between `order#2026-01` and `order#2026-02`". That moves them toward the
wide-column family.

Redis persistence note: Redis keeps data in memory and can persist it via periodic snapshots
(RDB) and/or an append-only file (AOF); depending on configuration you can lose the last
moments of writes on a crash. Treat it as a cache or a fast store with a known durability
window, not as the system of record for money.

### Document stores

**Model:** each record is a **document**, a self-describing nested structure (JSON-like; MongoDB
stores a binary form called BSON). Documents live in **collections**. Documents in one
collection can have different fields.

```json
{
  "_id": "order_98123",
  "user_id": 7,
  "status": "paid",
  "shipping": { "city": "Pune", "pin": "411001" },
  "items": [
    { "product_id": 5, "name": "Mug", "qty": 2, "unit_price_cents": 900 },
    { "product_id": 9, "name": "Pen", "qty": 1, "unit_price_cents": 450 }
  ],
  "created_at": "2026-05-01T10:00:00Z"
}
```

**Examples:** MongoDB, Couchbase, Firestore-style stores. PostgreSQL's `JSONB` column type gives
you much of this inside a relational database.

**Serves:** data that is naturally an **aggregate** read and written as one unit: an order with
its items, a product with variable attributes, a user profile with settings, CMS content.

**Strengths:** one read fetches the whole aggregate (good locality, no join); secondary indexes on
any field, including nested ones; flexible shape.

**Weaknesses:** many-to-many relationships are awkward (either duplicate data or do "joins" in
application code or with limited server-side lookups). Updating duplicated data (the product
name copied into every order) is your problem. Documents have size limits (MongoDB: 16 MB), so
unbounded arrays (all comments ever on a post) inside one document are a design bug.

Transactions: modern document databases support multi-document transactions (MongoDB since 4.0
on replica sets, 4.2 across shards), but they are costlier than single-document writes, which
are atomic. Good document design keeps most writes inside one document.

**Embed vs reference** is the core modeling decision:

| Embed (nest inside the parent) when | Reference (store an id) when |
|---|---|
| Child is read with the parent nearly always | Child is read independently |
| Child belongs to one parent only | Child is shared by many parents (many-to-many) |
| Number of children is bounded and small | Children grow without bound |
| Child changes rarely or only with the parent | Child changes often on its own |

### Wide-column stores

**Model:** tables with rows and columns, but organized for distribution. Each row has a
**partition key** that decides which machines store it, and rows within a partition are sorted
by a **clustering key**. Think: a distributed `map<partition_key, sorted_map<clustering_key, row>>`.

**Examples:** Apache Cassandra, ScyllaDB, HBase, Google Bigtable. (The term "wide-column" is used
loosely; Cassandra's modern model is a partitioned table with typed columns, while Bigtable/HBase
rows can have very many sparse columns grouped into column families. The modeling idea below is
shared.)

**Serves:** very high write throughput, huge datasets spread over many nodes, queries that are
known in advance and always include the partition key: messaging, activity feeds, IoT readings,
event logs, time-ordered history per entity.

**Strengths:** linear scale-out (add nodes, get proportionally more throughput, roughly), fast
writes (LSM storage, 06.4), and Cassandra-style designs have no single leader, so they stay
writable through node failures (chapter 07, leaderless replication).

**Weaknesses:** no joins; very limited ad-hoc queries; you must design one table per query
pattern (06.3); consistency is tunable and often eventual; lightweight transactions exist but
are costly.

### Graph databases

**Model:** **nodes** (entities) and **edges** (relationships), both with properties.

```text
(Asha) -[:FOLLOWS]-> (Ben) -[:FOLLOWS]-> (Chen)
   \                                      ^
    \--[:WORKS_AT]-> (Acme) <-[:WORKS_AT]-/
```

**Examples:** Neo4j, Amazon Neptune-style services, JanusGraph.

**Serves:** queries that traverse relationships several hops deep with variable depth: "friends
of friends who work at Acme", fraud rings (accounts sharing devices and cards), recommendation
paths, network topology, permission inheritance.

**Why not SQL?** Each hop in SQL is a join; a variable-depth traversal is a recursive query
whose cost can explode. Native graph databases store adjacency so that following an edge is
roughly a pointer hop: a BFS/DFS over the stored graph, which you already know.

**Weaknesses:** harder to shard (cutting a graph across machines makes traversals cross the
network, a graph-partitioning problem with no perfect answer), and poor fit for bulk aggregate
analytics. Many teams store graph-ish data in SQL until the traversals genuinely hurt.

### Time-series databases

**Model:** series of `(timestamp, value)` points identified by a metric name and **tags**
(labels), e.g. `cpu_usage{host="web-12", region="us-east"}`.

**Examples:** Prometheus (monitoring), InfluxDB, TimescaleDB (a PostgreSQL extension).

**Serves:** metrics, monitoring, sensor data, financial ticks. Workload: huge append-only write
volume, reads over time ranges with aggregation ("average per minute over the last 6 hours"),
old data downsampled or deleted.

**Tricks they use:** data organized by time chunks so dropping old data means deleting whole
files; compression that exploits regularity (timestamps arriving at fixed intervals compress to
almost nothing when you store differences between successive values); built-in **retention**
and **downsampling** (keep 10-second data for a week, 5-minute averages for a year).

**Watch out for cardinality:** each unique combination of tags is a separate series. Putting a
user id or request id in a tag creates millions of series and can overwhelm the database.

### Search engines

Elasticsearch/OpenSearch-style engines build an **inverted index** (word → list of documents) for
full-text search, relevance ranking, fuzzy matching, and faceted filtering. They are usually a
secondary store fed from the system of record. Covered in chapter 09.

### Vector databases (briefly)

Machine-learning models turn text, images or users into **embeddings**: vectors of a few hundred
to a few thousand numbers where similar items have nearby vectors. A **vector database** answers
"find the k nearest vectors to this one" fast.

Exact k-nearest-neighbor search over millions of high-dimensional vectors is too slow, so they
use **approximate nearest neighbor (ANN)** indexes, such as **HNSW** (a layered proximity graph you
greedily walk toward the query) or **IVF** (cluster the vectors, search only the nearest
clusters). You trade a little recall (sometimes missing a true neighbor) for orders-of-magnitude
speed.

**Examples:** Milvus, Qdrant, Weaviate, Pinecone-style services, and the `pgvector` extension for
PostgreSQL. **Serves:** semantic search, recommendations, retrieval for LLM applications,
deduplication of similar images.

### Summary table

| Family | Data shape | Main query | Typical uses | Main weakness |
|---|---|---|---|---|
| Key-value | key → opaque value | get/put by key | cache, sessions, counters | only key access |
| Document | nested JSON-like docs | by id, by indexed fields | catalogs, profiles, aggregates | many-to-many, duplication |
| Wide-column | partition → sorted rows | by partition key + clustering range | messages, feeds, events, IoT | queries must be designed up front |
| Graph | nodes + edges | multi-hop traversal | social, fraud, recommendations | sharding, bulk analytics |
| Time-series | (series, time, value) | range + aggregate over time | metrics, sensors | high-cardinality tags |
| Search | documents + inverted index | full text, relevance, facets | search boxes, log search | not a system of record |
| Vector | embeddings | approximate nearest neighbors | semantic search, RAG | approximate; another store to sync |

## 06.3 Query-first modeling for wide-column stores

### The mindset shift

In SQL you model the *entities* (normalize), then write any query you like, and add indexes.

In a wide-column store you model the *queries*. Each table is built to answer one query with a
single partition read. If you have three access patterns, you may have three tables holding the
same data in three layouts, and your application writes to all of them. Storage is cheap;
cross-node scatter-gather reads are not.

Rules (Cassandra-style):
1. Every query must specify the **full partition key** with equality. Without it, the database
   would have to ask every node.
2. Within a partition, you can filter and sort only by the **clustering columns**, in their
   declared order (a leftmost-prefix rule again, like composite indexes in chapter 05).
3. Keep partitions bounded. A common guideline for Cassandra is to keep a partition below
   roughly 100 MB and a few hundred thousand rows; very large partitions cause slow reads,
   compaction pain and hot nodes.

### Worked example: chat messages

**Access patterns:**
- Q1: show the latest 50 messages of a conversation; scroll back for older ones.
- Q2: list a user's conversations, most recently active first.

**First attempt (Q1):**

```sql
CREATE TABLE messages_by_conversation (
  conversation_id  uuid,
  message_id       timeuuid,          -- time-based UUID: sortable by time, unique
  sender_id        uuid,
  body             text,
  PRIMARY KEY ((conversation_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

- `(conversation_id)` is the **partition key**: all messages of one conversation live together
  on the same replicas.
- `message_id` is the **clustering key**: rows in the partition are kept sorted newest first.
- Q1 is one partition read: seek to the start and read 50 rows.

```sql
SELECT * FROM messages_by_conversation WHERE conversation_id = ? LIMIT 50;
-- older page: continue below the last message_id seen
SELECT * FROM messages_by_conversation
WHERE conversation_id = ? AND message_id < ? LIMIT 50;
```

**Problem:** a group chat active for years has millions of messages in one partition: unbounded
growth, one hot set of replicas.

**Fix: bucket the partition by time.**

```sql
CREATE TABLE messages_by_conversation (
  conversation_id  uuid,
  bucket           int,               -- e.g. yyyymm, like 202605
  message_id       timeuuid,
  sender_id        uuid,
  body             text,
  PRIMARY KEY ((conversation_id, bucket), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

```text
partition (conv_42, 202605): [msg t=31 May ... msg t=1 May]   newest first
partition (conv_42, 202604): [msg t=30 Apr ... msg t=1 Apr]
partition (conv_42, 202603): ...
```

Reading the latest 50 means: read the current bucket; if it has fewer than 50, read the
previous bucket, and so on. Bucket size is chosen from expected volume (monthly for normal
chats, daily for very busy channels). The partition key is now composite: `(conversation_id,
bucket)`.

**Q2 needs its own table**, because Q1's table is partitioned by conversation, not by user:

```sql
CREATE TABLE conversations_by_user (
  user_id          uuid,
  last_activity    timestamp,
  conversation_id  uuid,
  title            text,
  PRIMARY KEY ((user_id), last_activity, conversation_id)
) WITH CLUSTERING ORDER BY (last_activity DESC, conversation_id ASC);
```

Because `last_activity` is part of the primary key, "updating" it means deleting the old row and
inserting a new one each time a message arrives. That is the price of having the store keep the
list sorted for you. (An alternative is to keep a small unsorted table and sort a user's
conversations in the application, which is fine if users have tens or hundreds of
conversations.)

**Writes:** sending a message writes to `messages_by_conversation` and updates
`conversations_by_user` for every participant. These are separate partitions, so the writes are
not atomic as a group in the way a SQL transaction is; you design for eventual convergence
(retries, idempotent writes using the message id) or use the database's batch features with
their documented guarantees.

> **Say it like this:** "In Cassandra I design tables from queries: the partition key is what I
> always look up by, the clustering key is how I want rows sorted within it, and I bucket by time
> so partitions stay bounded. Duplicating data across query tables is normal."

> **Common mistake:** a partition key with low cardinality, like `country` or `status`.
> Everything lands in a handful of partitions on a handful of nodes; the rest of the cluster is
> idle.

## 06.4 Storage engines: how data sits on disk

A **storage engine** is the part of a database that stores and retrieves bytes on disk. Two
designs dominate, and knowing them explains most "why is X fast at writes / Y fast at reads"
questions.

### B-tree engines: update in place

Covered in detail in chapter 05: data lives in a B+ tree of fixed-size pages. To change a row,
find its page and modify the page in place (after logging the change to the WAL).

```text
write "k=42 -> v'"
   |
   +--> WAL append (sequential)
   +--> find leaf page holding 42 (3-4 page hops, mostly cached)
   +--> modify page in buffer pool; later write the whole page back to its place on disk
```

- Reads: one tree descent. Predictable.
- Writes: changing a 100-byte row eventually rewrites a whole 8–16 KB page, plus the WAL record,
  plus sometimes a page split. Writes go to scattered locations on disk (random I/O).
- Used by: PostgreSQL, MySQL InnoDB, Oracle, SQL Server, and also some NoSQL databases such as
  MongoDB's default engine (WiredTiger uses B-trees by default) and LMDB.

### LSM-tree engines: append, then merge

The **log-structured merge tree (LSM tree)** never modifies data in place. It turns every write
into sequential appends and cleans up later.

```text
                        WRITE PATH
 write(k, v)
    |
    +--> 1. append to WAL (commit log) on disk      (durability)
    +--> 2. insert into MEMTABLE in memory           (sorted structure,
                                                       e.g. skip list / balanced tree)
                  |
                  | memtable reaches threshold (e.g. tens of MB)
                  v
         3. FLUSH: write memtable to disk as an immutable, sorted file = SSTable
            (Sorted String Table); then the matching WAL segment can be discarded

 disk:   L0: [SST 9] [SST 8] [SST 7]          newest, may overlap in key range
         L1: [SST a-f][SST g-m][SST n-z]      sorted runs, non-overlapping (leveled)
         L2: [....... bigger, older ........]

         4. COMPACTION (background): merge SSTables, keep the newest version of each key,
            drop deleted keys and overwritten values, produce new SSTables, delete old ones.
            (A k-way merge of sorted files, exactly the merge step of merge sort.)
```

```text
                        READ PATH  get(k)
 1. check memtable                          -> found? return
 2. check SSTables newest to oldest:
      for each SSTable:
         bloom filter says "definitely not here"?  -> skip file (no disk read)
         else use the SSTable's sparse index to find the block, read it, binary search
 3. first hit wins (newest version); a TOMBSTONE hit means "deleted" -> not found
```

Key pieces:

- **Memtable:** sorted in-memory buffer of recent writes.
- **WAL / commit log:** makes memtable contents durable until flushed.
- **SSTable:** an immutable file of key-value pairs sorted by key, plus an index of block
  boundaries. Immutability makes caching, backup and concurrent reading simple.
- **Tombstone:** a delete is written as a special marker, because you cannot edit old SSTables.
  The tombstone shadows older values until compaction removes both. (Lots of deletes = lots of
  tombstones = slower reads until compaction; a known Cassandra pitfall for queue-like tables.)
- **Compaction:** background merging. Two common strategies:
  - **Size-tiered:** merge SSTables of similar size together. Lower write amplification, but a
    key can be spread across many files (more read amplification) and temporarily needs lots of
    free disk space.
  - **Leveled:** each level is about 10× larger than the previous; within a level, files don't
    overlap. A read checks roughly one file per level. Better reads and space usage, but more
    rewriting (higher write amplification).
- **Bloom filter:** a compact probabilistic set per SSTable. Asking "might key k be in this
  file?" returns either "definitely not" or "maybe". **No false negatives, some false
  positives.** With around 10 bits per key the false-positive rate is about 1%. It saves a disk
  read for most files that don't contain the key. (DSA bridge: k hash functions set k bits; a
  lookup checks whether all k bits are set.) Bloom filters help point lookups, not range scans.

- Used by: Cassandra, ScyllaDB, HBase, Bigtable, RocksDB and LevelDB, and the many systems built
  on RocksDB or engines modeled on it (e.g. TiKV, an optional MySQL engine called MyRocks, and
  CockroachDB's RocksDB-inspired engine). Many time-series engines use LSM-like designs too.

### Amplification: the trade-off in numbers

Three measures compare engines:

- **Write amplification:** bytes actually written to disk ÷ bytes the application wrote.
- **Read amplification:** disk reads (or files checked) per logical read.
- **Space amplification:** disk space used ÷ logical data size.

| | B-tree (update in place) | LSM tree |
|---|---|---|
| Write pattern | Random page writes + WAL | Sequential appends (WAL, flush, compaction) |
| Write throughput | Good | Typically higher, especially for write-heavy, random-key workloads |
| Write amplification | Whole page rewritten for a small change (plus WAL, plus full-page writes after checkpoints in some engines) | Data rewritten at each compaction; leveled compaction can rewrite data ~10× per level crossed |
| Point read | One tree descent, predictable | Memtable + possibly several SSTables; bloom filters keep this cheap |
| Range scan | Walk sorted leaves: excellent | Merge across memtable and several SSTables: good, a bit more work |
| Read latency predictability | High | Can spike during heavy compaction |
| Space amplification | Page fragmentation (half-empty pages after splits) | Obsolete versions and tombstones until compacted; size-tiered needs headroom |
| Deletes | Remove in place | Tombstones linger until compaction |
| Compression | Per page, less effective | Immutable sorted files compress very well |

There is no free lunch: you can reduce any two of read, write and space amplification at the
expense of the third. This is sometimes called the **RUM conjecture** (Read, Update, Memory).

> **Say it like this:** "B-trees update in place, so reads are predictable but writes are random
> page rewrites. LSM trees buffer in memory and append sorted files, which makes writes
> sequential and fast, and they pay later in compaction and in checking multiple files on read,
> which bloom filters mostly hide."

> **Common mistake:** "LSM is faster than B-tree." It is usually faster for write-heavy
> workloads and usually slower or less predictable for read-heavy ones. Benchmarks depend
> heavily on workload and tuning.

## 06.5 SQL vs NoSQL: deciding

### Decision table

| Requirement | Leans SQL | Leans NoSQL (which family) |
|---|---|---|
| Multi-row/multi-table transactions (money, inventory) | Yes | Possible in some, but awkward |
| Relationships, joins, ad-hoc questions | Yes | Graph for deep traversals; others poor |
| Data size fits one primary (very roughly up to a few TB) | Yes | No need |
| Write rate beyond one primary, need easy scale-out | Sharded SQL or distributed SQL | Wide-column, key-value |
| Only key lookups at massive QPS | Works, but overkill | Key-value |
| Aggregates read/written whole, variable shape | JSONB columns can work | Document |
| Many-hop relationship queries | Recursive SQL works at small scale | Graph |
| Append-heavy time-stamped data with retention | Partitioned tables / TimescaleDB | Time-series, wide-column |
| Always-writable across node or region failures | Harder (single leader) | Leaderless wide-column / key-value |
| Full-text search, relevance | Basic support | Search engine (secondary) |
| Team familiarity, tooling, ops maturity | Usually yes | Depends |

### Checklist (ask these in order)

1. **What are the access patterns?** List the top queries with expected QPS and latency. If you
   cannot list them, you need SQL's flexibility.
2. **What must be atomic?** If several entities must change together, prefer a database that
   offers transactions across them.
3. **How big, how fast?** Use chapter 04 estimation: data size in 1–3 years, peak writes/sec,
   peak reads/sec. Does one primary (plus replicas, plus cache) handle it?
4. **What consistency does the user need?** Seeing your own post immediately? Balance exactly
   right? Or is a few seconds of staleness fine?
5. **What's the shape of the data?** Tabular and relational, nested aggregates, a graph, a time
   series?
6. **What are the availability requirements?** Must writes continue through a node or region
   failure?
7. **What does the team know how to operate?** An unfamiliar database is a reliability risk.

If nothing forces a NoSQL choice, use SQL.

> **Say it like this:** "Our core order data is relational and needs transactions, so it goes in
> Postgres. The activity feed is append-heavy, read by user and time, and huge, so a wide-column
> store partitioned by user fits better there."

## 06.6 NewSQL / distributed SQL

### The problem

Teams wanted both: SQL with real transactions *and* automatic horizontal scaling and fault
tolerance, without hand-sharding.

### The idea

**Distributed SQL** (also called **NewSQL**) databases present a SQL interface and ACID
transactions, while internally splitting data across many nodes. Examples: Google Spanner,
CockroachDB, YugabyteDB, TiDB.

A common architecture:

```text
       SQL clients
           |
   +-------v--------+   any node accepts SQL, plans the query,
   |  SQL layer     |   and coordinates distributed transactions
   +-------+--------+
           |
   +-------v---------------------------------------------+
   |  distributed, sorted key-value layer                 |
   |  keyspace split into RANGES (each roughly tens to    |
   |  hundreds of MB, split automatically as they grow)   |
   |                                                      |
   |  range [a..f]  -> replicas on nodes 1,2,3 (Raft group)|
   |  range [g..m]  -> replicas on nodes 2,4,5 (Raft group)|
   |  range [n..z]  -> replicas on nodes 1,3,5 (Raft group)|
   +------------------------------------------------------+
```

- Each range is replicated with a **consensus protocol** (Raft or Paxos, chapter 10), so a write
  is committed when a majority of its replicas agree. A minority of failed nodes does not lose
  data or availability for that range.
- Transactions touching several ranges use a distributed commit protocol (a variant of
  two-phase commit coordinated over consensus groups).
- Spanner is known for **TrueTime**: GPS and atomic-clock-backed time with a bounded uncertainty
  interval, which it uses to order transactions globally (externally consistent). Others use
  hybrid logical clocks and different techniques. CockroachDB defaults to serializable isolation.

### The trade-off

- **Gain:** SQL, transactions, automatic sharding and rebalancing, survives node and even zone
  failures.
- **Pay:** higher write latency than a single-node database, because every commit needs a
  majority round trip (and cross-region replicas mean cross-region latency, tens to over a
  hundred milliseconds). Cross-range transactions cost more. Operationally more complex; some
  SQL features or performance characteristics differ from single-node PostgreSQL/MySQL.
- **When to use:** you need relational + transactions and you are clearly beyond one primary,
  or you need multi-region writes with strong consistency. Not as a default for a small app.

## 06.7 Polyglot persistence

**Polyglot persistence** means using several databases in one system, each for the access pattern
it fits:

```text
                     +--------------------+
   writes  --------> |  PostgreSQL        |  system of record: users, orders, payments
                     +---------+----------+
                               | change data capture / events (chapter 11)
          +--------------------+--------------------+-------------------+
          v                    v                    v                   v
   +-------------+     +---------------+    +--------------+    +--------------+
   | Redis       |     | Elasticsearch |    | Wide-column  |    | Data         |
   | cache,      |     | product       |    | activity     |    | warehouse    |
   | sessions    |     | search        |    | feed/history |    | analytics    |
   +-------------+     +---------------+    +--------------+    +--------------+
```

- **Gain:** each workload gets a suitable engine.
- **Pay:** more systems to run, monitor, back up and secure; data flows between them are
  asynchronous, so derived stores lag behind the source; you need a clear **system of record**
  (the authoritative copy) and a way to rebuild derived stores from it.
- **Rule:** add a store only when a real access pattern demands it. Each new database is a
  long-term operational commitment.

## 06.8 Myths

- **"NoSQL is faster."** Faster at *what*? A key-value get by primary key is fast in Redis and
  fast in PostgreSQL (sub-millisecond on the server when cached). NoSQL systems are often faster
  for the specific pattern they were designed for and much slower or unable to serve others.
  Many "NoSQL is faster" stories really mean "we skipped durability or consistency guarantees".
- **"NoSQL has no schema."** The schema still exists; it moved from the database into your
  application code (**schema-on-read** instead of **schema-on-write**). Every reader must handle
  every historical shape of the data. Many document databases now support schema validation,
  and wide-column stores like Cassandra have declared, typed schemas.
- **"NoSQL doesn't support transactions."** Many do now, with limits (single partition, or
  multi-document with extra cost). Read the documentation for the exact scope.
- **"NoSQL scales, SQL doesn't."** Single-node SQL scales vertically a long way; sharded SQL
  and distributed SQL scale out. NoSQL scale-out is easier only if your queries fit its model.
- **"Use MongoDB because JSON."** PostgreSQL stores and indexes JSON (`JSONB`) well. Choose by
  access pattern and consistency needs, not by the API's data format.
- **"Eventual consistency means data might be wrong forever."** It means replicas converge if
  writes stop; the practical question is how long the window is and what the user sees in it.

## 06.9 Recap

- NoSQL emerged for scale-out, flexible shapes, and narrow access patterns; it is several
  different families, not one thing.
- Key-value: get/put by key, fastest and simplest; no queries by value.
- Document: self-contained aggregates; embed bounded, owned children; reference shared or
  unbounded ones.
- Wide-column: partition key decides placement, clustering key decides order inside the
  partition; one table per query; bucket by time to bound partitions.
- Graph for many-hop traversals; time-series for timestamped metrics with retention (watch tag
  cardinality); search engines and vector databases are usually secondary stores.
- B-tree engines update pages in place: predictable reads, random writes.
- LSM engines: WAL + memtable → flush to immutable SSTables → background compaction; bloom
  filters skip files; tombstones mark deletes.
- Read, write and space amplification trade against each other; pick the engine for the
  workload.
- Default to SQL; move a workload elsewhere when its access pattern or scale clearly demands it.
- Distributed SQL gives SQL + transactions + scale-out via consensus-replicated ranges, paying in
  write latency and complexity.
- Polyglot persistence: one system of record, derived stores fed asynchronously.

## 06.10 Check yourself

1. Why must a Cassandra query include the full partition key?

<details><summary>Answer</summary>

The partition key is hashed to decide which nodes store the data. With it, the coordinator goes
straight to those replicas. Without it, the database would have to ask every node and merge the
results (a cluster-wide scan), which does not scale, so it is refused by default.

</details>

2. You store chat messages with `PRIMARY KEY ((conversation_id), message_id)`. What goes wrong
   over time and how do you fix it?

<details><summary>Answer</summary>

Long-lived, busy conversations grow unbounded partitions: slow reads, compaction pressure and
hot replicas. Add a time bucket to the partition key, `((conversation_id, bucket), message_id)`,
sized by expected volume, and read the newest bucket first, moving to older buckets as the user
scrolls.

</details>

3. Walk through a read in an LSM engine for a key that does not exist.

<details><summary>Answer</summary>

Check the memtable (miss). For each SSTable from newest to oldest, ask its bloom filter; most
answer "definitely not", so no disk read is needed. A false positive costs reading one block
from that file and finding nothing. After all files, return not found. Bloom filters are what
keep this cheap.

</details>

4. Why can a bloom filter answer "definitely not present" but only "maybe present"?

<details><summary>Answer</summary>

Adding a key sets k bit positions. If any of the key's k bits is 0, the key was never added:
definitely absent. If all are 1, they might have been set by other keys: maybe present. Bits are
never cleared, so there are no false negatives, only false positives whose rate depends on bits
per key and number of hash functions.

</details>

5. A workload is 95% writes of sensor readings with random device ids, and reads are recent
   ranges per device. B-tree or LSM, and why?

<details><summary>Answer</summary>

LSM fits: random-key writes become sequential appends to the memtable and SSTables, sustaining
high write throughput; reads by device and recent time range are served from the memtable and
newest SSTables. Accept compaction overhead and somewhat less predictable read latency. (A
time-series database, often LSM-based, is also a natural fit.)

</details>

6. Someone says "let's use NoSQL because it's schemaless, so we'll move faster." What is the
   catch?

<details><summary>Answer</summary>

The schema moves into application code. Every reader must handle every shape ever written, and
nothing stops a bug from writing malformed data. You also usually give up joins and broad
transactions. Flexibility helps for genuinely variable data, but it is not free speed.

</details>

7. When would you choose distributed SQL over a single PostgreSQL primary with replicas?

<details><summary>Answer</summary>

When the data is relational and needs transactions but write volume or data size clearly exceed
one primary, or when you need writes to survive node or zone/region failures with strong
consistency. You accept higher commit latency (consensus round trips) and more operational
complexity. For a workload one primary can handle, a single primary is simpler and faster.

</details>

8. In a document store, should an order embed its line items? Should it embed the full product
   record?

<details><summary>Answer</summary>

Embed the line items: they are owned by the order, bounded in number, and read with it. Do not
embed the full product: products are shared by many orders and change independently. Store the
product id plus a snapshot of the fields that must be frozen at purchase time (name, unit
price).

</details>
