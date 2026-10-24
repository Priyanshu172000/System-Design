# 06 — NoSQL families and storage engines

*Why it matters:* "SQL or NoSQL?" comes up in nearly every design; you should answer by access pattern and on-disk trade-offs, not by fashion.

## Why NoSQL emerged

**How it works** · Around the late 2000s relational databases (built for one big machine) hurt in three ways: **scale-out** (hand-sharding breaks joins, multi-row transactions, FKs), **flexible schema** (records whose attributes vary; slow `ALTER TABLE` on huge tables), and **narrow access patterns** that need no joins or global transactions. **NoSQL** ("not only SQL") drops some relational features in exchange for easier horizontal scaling, flexible shapes, or a model fitted to one access pattern.

**Example** · QuickBite on one PostgreSQL primary meets all three pains:

1. **Scale-out:** rider pings at ~25,000 writes/s at peak, growing with every new city.
2. **Flexible schema:** a pizza has `size, crust`, a biryani has `spice_level, serves`, a combo has `items[]`; one fixed `menu_items` schema fits none well.
3. **Narrow pattern:** pings are only ever "append ping for rd_5521" and "latest pings for rd_5521"; no joins, no cross-rider transactions.

So: NoSQL is several families, as different from each other as from SQL; each wins on one of these pains.

## The families

**How it works** · Pick by access pattern.

| Family | Model / main query | Use when | Examples | Main weakness |
|---|---|---|---|---|
| **Key-value** | Distributed hash map; `GET/PUT/DELETE`, TTL, atomic incr | Sessions, caches, carts, flags, rate-limit counters, profiles by id, leaderboards | Redis, Memcached, DynamoDB-style, Aerospike, RocksDB (embedded) | Only key access ("all carts with item 5" needs your own index) |
| **Document** | Nested JSON-like docs (MongoDB: BSON) in collections; by id or indexed (nested) fields | Aggregates read/written whole: order + items, variable-attribute products, CMS | MongoDB, Couchbase, Firestore-style; PostgreSQL `JSONB` | Many-to-many, duplicated data, 16 MB doc limit (MongoDB) |
| **Wide-column** | `map<partition_key, sorted_map<clustering_key, row>>`; partition key + clustering range | Huge write-heavy data with known queries: messaging, feeds, IoT, event logs | Cassandra, ScyllaDB, HBase, Bigtable | No joins, little ad-hoc querying, one table per query, often eventual consistency |
| **Graph** | Nodes + edges with properties; multi-hop traversal | Friends-of-friends, fraud rings, recommendations, permission inheritance | Neo4j, Neptune-style, JanusGraph | Hard to shard, poor for bulk analytics |
| **Time-series** | `(series, timestamp, value)` with **tags**; range + aggregate | Metrics, monitoring, sensors, ticks | Prometheus, InfluxDB, TimescaleDB | High-cardinality tags (user id as tag = millions of series) |
| **Search** | **Inverted index** (word → docs); full text, relevance, facets | Search boxes, log search (ch 09) | Elasticsearch/OpenSearch | Not a system of record |
| **Vector** | **Embeddings**; approximate k-nearest-neighbors | Semantic search, recommendations, LLM retrieval, near-duplicate images | Milvus, Qdrant, Weaviate, Pinecone-style, `pgvector` | Approximate; another store to sync |

**Example** · QuickBite, one family per access pattern:

| Need | Family | Key / query |
|---|---|---|
| Session, OTP attempt counter | Key-value (Redis, TTL) | `GET sess:9f3a`, `INCR otp:u_1842` (expires in 10 min) |
| Spice Route's menu, varied dish attributes | Document | `menus` doc `r_77` with nested `items[]` |
| Rider ping history | Wide-column / time-series | partition `(rd_5521, day)`, rows sorted by time |
| Accounts sharing cards and devices | Graph | 3-hop traversal from `u_1842` |
| `rider_speed{city="BLR"}` dashboards | Time-series | avg per minute, last 6 h |
| "biryani near me" | Search | inverted index `biryani → [r_77, r_12, ...]` |
| "dishes like this one" | Vector | k nearest embeddings to biryani's vector |

Family notes:

- **Key-value:** DynamoDB-style **partition key + sort key** keeps items sorted within a partition: pk `user#u_1842`, sk between `order#2026-10-01` and `order#2026-10-31` returns Asha's October orders in one query (edging toward wide-column). Redis persists via RDB snapshots and/or AOF and can lose the last moments of writes: a cache or fast store with a known durability window, not the system of record for money.
- **Document:** single-document writes are atomic; multi-document transactions exist (MongoDB since 4.0 on replica sets, 4.2 across shards) but cost more, so keep most writes in one document. Order `o_90311` as a doc embeds its `items` (2 x Biryani 18000, 1 x Raita 9000) and `delivery_address`, but references `restaurant_id: "r_77"` and `user_id: "u_1842"`, with a frozen snapshot of name and unit price per item:

| Embed when | Reference (store an id) when |
|---|---|
| Read with parent nearly always | Read independently |
| Owned by one parent | Shared by many (many-to-many) |
| Bounded, small count | Grows without bound (all reviews of a restaurant) |
| Changes rarely / with parent | Changes often on its own |

- **Wide-column:** linear scale-out (roughly), fast LSM writes, Cassandra-style is leaderless so it stays writable through node failures (ch 07); lightweight transactions exist but are costly. "Wide-column" is loose: Cassandra has typed partitioned tables, Bigtable/HBase have sparse columns in column families.
- **Graph:** each SQL hop is a join and variable depth is a recursive query that can explode; native graphs store adjacency so an edge hop is roughly a pointer hop (BFS/DFS). Many teams keep graph-ish data in SQL until traversals hurt.
- **Time-series:** data in time chunks (drop old data = delete files), delta compression of regular timestamps (pings every 4 s store "+4" each), built-in **retention** and **downsampling** (10 s data for a week, 5 min averages for a year).

> **Black box:** Vector **ANN indexes**: **HNSW** (layered proximity graph walked greedily toward the query) or **IVF** (cluster vectors, search the nearest clusters). **Use when:** exact kNN over millions of high-dimensional vectors is too slow, e.g. "similar dishes" over 50,000 restaurants' menus. **Cost:** a little recall for orders-of-magnitude speed.

## Query-first modeling for wide-column stores

**How it works** · SQL: model entities, query anything, add indexes. Wide-column: model the *queries*; each table answers one query with a single partition read, so three access patterns may mean three tables of the same data, all written by the app. Rules (Cassandra-style): (1) every query gives the **full partition key** with equality (otherwise it would ask every node); (2) inside a partition, filter/sort only by **clustering columns** in declared order (leftmost prefix again); (3) keep partitions bounded, roughly under 100 MB and a few hundred thousand rows.

**Example** · Q1 "latest 50 pings of rider rd_5521, scroll back":

```sql
CREATE TABLE pings_by_rider (rider_id text, day int, ts timestamp, lat double, lng double,
  PRIMARY KEY ((rider_id, day), ts)) WITH CLUSTERING ORDER BY (ts DESC);
```

```text
partition (rd_5521, 20261010): [00:02:00, 00:01:56, ... 00:00:04]   30 rows, newest first
partition (rd_5521, 20261009): [23:59:56, 23:59:52, ...]
read latest 50 at 00:02: today's partition gives 30 -> read 20 more from 20261009
older page: WHERE rider_id='rd_5521' AND day=20261009 AND ts < 23:58:40 LIMIT 50
bound: 1 ping / 4 s = at most 21,600 rows per partition per day
without day: 10 h shifts = 9,000 pings/day -> ~3.3 M rows/year in ONE partition
```

So: the partition key `(rider_id, day)` is what you always look up by, and the **bucket** (`day`) keeps it bounded; size buckets by volume (the full chapter's chat example is the same shape: `((conversation_id, bucket), message_id)` with a **timeuuid** `message_id`, monthly buckets, daily for busy channels).

**Example** · Q2 "Spice Route's live order board, most recently changed first" needs its own table, `PRIMARY KEY ((restaurant_id), last_update, order_id)`. Because `last_update` is in the key, o_90311 moving PLACED → ACCEPTED deletes row `(r_77, 20:14, o_90311)` and inserts `(r_77, 20:16, o_90311)`. Alternative: keep it unsorted and sort in the app (a restaurant has tens of live orders). One ping also writes `pings_by_order` for "where is my order": separate partitions, not atomic, so design for convergence (retries, idempotent writes keyed by `(rider_id, ts)`) or use batch features within their documented guarantees.

> **Common mistake:** a low-cardinality partition key like `city` or `status`. Everything lands on a handful of nodes while the rest of the cluster idles.

## Storage engines: B-tree vs LSM tree

**How it works** · A **storage engine** stores and retrieves bytes on disk. **B-tree** engines (PostgreSQL, InnoDB, Oracle, SQL Server, MongoDB's WiredTiger by default, LMDB) update in place: WAL append, descend 3–4 mostly cached pages, modify the page, write the whole 8–16 KB page back later. **LSM trees** (Cassandra, ScyllaDB, HBase, Bigtable, RocksDB, LevelDB, TiKV, MyRocks, CockroachDB's RocksDB-inspired engine, many time-series DBs) never modify in place: append to a WAL, insert into a sorted in-memory **memtable**, flush it (at tens of MB) as an immutable sorted **SSTable**, merge files later by **compaction**.

**Example** · B-tree: Spice Route changes the biryani price. WAL append, then the leaf page holding biryani is modified in the buffer pool and later the whole 16 KB page is rewritten in place for a ~100-byte change (random I/O, sometimes a page split).

**Example** · LSM, same store, keys of r_77's menu:

```text
put biryani=18000   WAL append; memtable {biryani:18000}
put raita=9000      memtable {biryani:18000, raita:9000}
memtable full       flush -> SST-1 [biryani:18000, raita:9000]; drop that WAL segment
put biryani=20000   memtable {biryani:20000}
delete raita        memtable {biryani:20000, raita:TOMBSTONE}
flush               SST-2 [biryani:20000, raita:TOMBSTONE]       (L0, newest first)
get raita   memtable miss; SST-2 bloom "maybe" -> TOMBSTONE -> not found (SST-1 unread)
get kebab   memtable miss; SST-2 bloom "no"; SST-1 bloom "no" -> not found, 0 disk reads
compact SST-1+SST-2 (k-way merge, newest wins) -> SST-3 [biryani:20000]
```

So: every write is a sequential append; reads check newest to oldest and the first hit wins; compaction drops the old 18000, raita and its tombstone.

- **SSTable:** immutable sorted file plus a sparse block index (read: find block, binary search). Easy to cache, back up, read concurrently. Levels: L0 files may overlap; L1, L2, ... are bigger, older, non-overlapping (leveled).
- **Tombstone:** a delete marker shadowing older values until compaction removes both. Many deletes (queue-like tables in Cassandra) slow reads.
- **Bloom filter:** per SSTable, k hash functions set k bits per key; a lookup checks all k. **No false negatives, some false positives** (~10 bits/key gives ~1%). Helps point lookups, not range scans.

**Example** · Bloom filter with k = 3: biryani sets bits {5, 17, 60}, raita sets {3, 42, 60}. Lookup kebab hashes to {3, 9, 42}: bit 9 is 0 → **definitely not** here, skip the file. Lookup paneer hashes to {5, 42, 60}: all 1 (set by the two other keys) → "maybe" → read the block, not there: a **false positive**, costing one wasted read. Bits are never cleared, so a present key is never reported absent.

> **Black box:** **Compaction strategies**: **size-tiered** merges similar-size SSTables (lower write amplification, more files per read, needs free-disk headroom); **leveled** keeps each level ~10x the previous with non-overlapping files (about one file per level per read, better space, more rewriting). **Use when:** tuning for read-heavy (leveled) vs write-heavy (size-tiered). **Cost:** read latency can spike during heavy compaction.

**Amplification:** write (disk bytes written / app bytes, e.g. the 100-byte price change costing a 16 KB page = ~160x), read (files or reads per lookup), space (disk used / logical size).

| | B-tree | LSM tree |
|---|---|---|
| Write pattern | Random page writes + WAL | Sequential appends (WAL, flush, compaction) |
| Write throughput | Good | Typically higher, especially write-heavy random keys |
| Write amplification | Whole page per small change | Rewritten at each compaction (leveled: ~10x per level) |
| Point read | One descent, predictable | Memtable + several SSTables; bloom filters keep it cheap |
| Range scan | Walk leaves: excellent | Merge across files: good, more work |
| Space / deletes | Half-empty pages after splits; delete in place | Old versions, tombstones until compaction; size-tiered headroom |
| Compression | Per page, weaker | Immutable sorted files compress very well |

You can reduce any two of read, write, space amplification at the expense of the third (the **RUM conjecture**). "LSM is faster" is a myth: usually faster for write-heavy, slower or less predictable for read-heavy.

> **Say it like this:** "B-trees update in place, so reads are predictable but writes are random page rewrites. LSM trees buffer in memory and append sorted files, so writes are sequential and fast, paying later in compaction and multi-file reads, which bloom filters mostly hide."

## SQL vs NoSQL: deciding

**How it works** · Default to SQL unless something forces otherwise.

| Requirement | Leans SQL | Leans NoSQL |
|---|---|---|
| Multi-row/table transactions | Yes | Possible in some, awkward |
| Joins, ad-hoc questions | Yes | Graph for deep traversals |
| Fits one primary (very roughly a few TB) | Yes | No need |
| Writes beyond one primary | Sharded / distributed SQL | Wide-column, key-value |
| Only key lookups at massive QPS | Works, overkill | Key-value |
| Whole aggregates, variable shape | `JSONB` can work | Document |
| Many-hop relationships | Recursive SQL at small scale | Graph |
| Append-heavy timestamped, retention | Partitioned tables / TimescaleDB | Time-series, wide-column |
| Always writable through node/region failure | Harder (single leader) | Leaderless wide-column / KV |
| Full-text search | Basic | Search engine (secondary) |
| Team familiarity, ops maturity | Usually yes | Depends |

Checklist, in order: (1) top access patterns with QPS and latency (cannot list them → you need SQL's flexibility), (2) what must be atomic, (3) size and rate in 1–3 years (ch 04), (4) consistency the user needs, (5) data shape, (6) availability through failures, (7) what the team can operate.

**Example** · The checklist on two QuickBite workloads:

| Question | Orders + payments | Rider pings |
|---|---|---|
| 1. Access patterns | By order id, by user, by restaurant; new reports later | Append; latest per rider; per order while delivering |
| 2. Atomic | Order + items + payment status together | Nothing across pings |
| 3. Rate | ~120 writes/s at peak | ~25,000 writes/s at peak |
| 4. Consistency | Exact (money) | A few seconds stale is fine |
| 5/6. Shape, availability | Relational; failover acceptable | Time-ordered; keep accepting pings through a node loss |
| Verdict | PostgreSQL | Wide-column (or time-series) with TTL |

Myths: "NoSQL is faster" (only for its designed pattern; a get by key is sub-ms in PostgreSQL too; often means skipped guarantees), "no schema" (it moved into app code: **schema-on-read** vs **schema-on-write**), "no transactions" (many have scoped ones), "SQL doesn't scale", "MongoDB because JSON" (`JSONB` exists), "eventual = wrong forever" (replicas converge once writes stop; ask how long the window is).

## NewSQL / distributed SQL

**How it works** · **Distributed SQL** (Spanner, CockroachDB, YugabyteDB, TiDB) offers SQL and ACID while splitting data automatically. Any node accepts SQL; below it, a sorted key-value layer splits the keyspace into **ranges** (tens to hundreds of MB, auto-split), each replicated as a **Raft/Paxos** group across ~3 nodes. A write commits when a majority agrees.

**Example** · Order 90311 falls in range `orders/[90000..95000)`, replicated on nodes 2, 4, 5 (leader node 2):

1. Node 2 appends the write and sends it to 4 and 5; node 5 is down.
2. Node 4 acks: 2 of 3 is a majority → committed, client gets OK. No data lost, no failover needed.
3. Asha's wallet row lives in another range (nodes 1, 3, 5): debit + order is a cross-range transaction, a two-phase-commit variant over both Raft groups, slower than a single-range write.

So: scale-out and survival of a minority failure, paid with a majority round trip on every commit.

> **Black box:** cross-range transactions use a two-phase-commit variant over consensus groups; Spanner orders transactions globally with **TrueTime** (GPS/atomic clocks, bounded uncertainty), others use hybrid logical clocks; CockroachDB defaults to serializable. **Use when:** relational + transactions clearly beyond one primary, or multi-region strongly consistent writes. **Cost:** majority round trip per commit (cross-region: tens to 100+ ms), pricier cross-range txns, more ops complexity; not a default for small apps.

## Polyglot persistence

**How it works** · Several databases, each for its access pattern, with one **system of record** feeding derived stores via change data capture / events (ch 11).

**Example** · Spice Route changes biryani from 18000 to 20000:

```text
1. restaurant-service commits to PostgreSQL           (system of record)
2. CDC event "menu_item 5 price=20000" -> Kafka
3. Redis menu cache key for r_77 invalidated           (next read reloads 20000)
4. search-service reindexes the dish in Elasticsearch  (until then search shows 18000)
5. warehouse load picks it up later                    (analytics)
```

So: each store fits its job, but derived stores lag the source, and if Elasticsearch is corrupted you rebuild it from PostgreSQL. Pay: more systems to run and secure. Add a store only when a real access pattern demands it.

## In one minute

- NoSQL is several families born of scale-out, flexible shapes and narrow access patterns; pick by access pattern.
- Key-value for lookups by id; document for self-contained aggregates (embed bounded owned children, reference shared/unbounded); graph for many hops; time-series for metrics (watch tag cardinality); search and vector as secondary stores.
- Wide-column: partition key = what you always look up by; clustering key = order inside; one table per query; bucket by time.
- B-tree: in-place pages, predictable reads, random writes. LSM: WAL + memtable → SSTables → compaction; bloom filters skip files; tombstones mark deletes.
- Read, write and space amplification trade off; no engine wins everywhere.
- Default to SQL; distributed SQL buys scale-out transactions with consensus latency; polyglot needs one system of record.

## Go deeper

Full chapter 06: sections 06.3 (query-first wide-column modeling), 06.4 (B-tree vs LSM storage engines), 06.5 (SQL vs NoSQL decision).
