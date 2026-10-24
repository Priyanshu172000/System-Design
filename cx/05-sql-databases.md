# 05 — Relational databases, deeply

*Why it matters:* "we store it in Postgres/MySQL" is the default answer in most designs, so you must know what indexes, transactions and isolation levels actually guarantee.

## Why a database, and the vocabulary

**How it works** · A plain file gives O(N) lookups, lost concurrent updates, corruption on crash, half-done transfers and custom code per question. A **DBMS** solves these once: **indexes**, **concurrency control**, **crash safety** (write-ahead log), **transactions** (all-or-nothing) and **SQL**. A **relational database** (PostgreSQL, MySQL, SQL Server, Oracle, SQLite) stores fixed-shape tables and combines them with joins.

Vocabulary: a **table** (relation) holds **rows** (tuples) of typed **columns**; the **schema** (tables, types, constraints) is enforced on every write; a **primary key (PK)** is unique and never NULL; a **foreign key (FK)** must match a PK elsewhere (**referential integrity**); **constraints** include `NOT NULL`, `UNIQUE`, `CHECK (amount >= 0)`, PK, FK.

**Example** · QuickBite `users` and `orders` (money as integer paise, never float: `0.1 + 0.2 != 0.3`).

```text
users: u_1842 Asha | u_2001 Ben | u_3310 Chen
orders: o_90311 u_1842 45000 | o_90312 u_1842 22000 | o_90400 u_2001 61000
INNER JOIN on user_id -> Asha o_90311, Asha o_90312, Ben o_90400   (Chen absent)
LEFT JOIN + COUNT     -> Asha 2, Ben 1, Chen 0                      (Chen kept, NULLs)
```

So: INNER keeps matches only; LEFT keeps every left row. The planner picks the join algorithm from statistics: **nested loop** (for each A row, look up B; A small, B indexed on the join column), **hash join** (hash the smaller side, probe with the other; large equality joins, no useful index), **merge join** (walk two sorted inputs like merging arrays; inputs already sorted, e.g. from an index).

## Normalization and denormalization

**How it works** · **Normalization** = each fact stored in exactly one place.

**Example** · One wide `orders` table repeating restaurant facts:

```text
order_id | user_id | restaurant_name | item     | item_price | qty
o_90311  | u_1842  | Spice Route     | Biryani  | 18000      | 2
o_90311  | u_1842  | Spice Route     | Raita    |  9000      | 1
o_90377  | u_2001  | Spice Route Ltd | Biryani  | 19000      | 1
```

So: **update anomaly** (r_77 renames itself, one row still says "Spice Route Ltd": the data disagrees), **insert anomaly** (a new dish cannot be recorded until someone orders it), **delete anomaly** (delete the last Raita order and Raita's price is gone).

Fix by the normal forms: **1NF** atomic cells (no `item1, item2` columns); **2NF** non-key columns depend on the *whole* key (in key `(order_id, item_id)`, `item_name` depends only on `item_id` → move to `menu_items`); **3NF** no dependency through another non-key column (`user_email` in orders → keep only `user_id`). "The key, the whole key, and nothing but the key." Result: `users`, `restaurants`, `menu_items`, `orders`, `order_items(order_id, item_id, qty, unit_price)`. `unit_price` is not a violation: price at order time is a different fact from today's price. **Denormalization** stores copies (`order_count` on users, `restaurant_name` on orders) to skip joins: faster reads, but every copy must be updated (or staleness accepted), your code keeps them in sync, storage is duplicated.

> **Common mistake:** denormalizing up front "for performance". A join on indexed columns is fast. Start normalized, denormalize the *measured* hot path, sync copies in the same transaction or accept a known staleness window.

## Indexes: pages and the B+ tree

**How it works** · Without an index, `WHERE email = ?` is a **full table scan**. An **index** is a separate sorted structure mapping values to row locations. Disk I/O is in **pages** (8 KB PostgreSQL, 16 KB InnoDB); cost = "how many pages, and were they cached" in the **buffer pool** (InnoDB) / **shared buffers** (PostgreSQL). A **B+ tree** node is one page with hundreds of children; internal nodes hold separator keys; data entries live only in **leaves**, which form a linked list; splits/merges keep every lookup the same depth.

**Example** · `orders` PK index, 100 M rows (about 50 days of QuickBite orders), fanout ~500, ~400 entries per leaf, `WHERE id = 90311` (order o_90311):

```text
level 1  root page (cached)       500 children, each covers 200,000 ids
         binary search: 90311 in [1..200000]           -> child 0
level 2  internal page (cached)   500 children, each covers 400 ids
         binary search: 90311 in [90001..90400]        -> leaf 225
level 3  leaf 225 (maybe disk)    binary search -> 90311 -> row location
range id 90311..90330: same leaf, walk right; past 90400 follow the leaf link
```

So: 3 pages touched (1 x 500 x 500 = 250,000 leaves x 400 = 100 M rows), usually 0–1 disk reads because root and level 2 stay cached. Depth = `log_500(N)`; 4 levels ≈ 10+ billion rows. A binary tree would need ~27 levels. Point lookup = binary search per page; range = `lower_bound(x)` then walk leaves. High fanout is the whole point.

## Clustered vs secondary indexes

**How it works** · A **clustered index** stores the rows themselves in its leaves in key order (one per table). A **secondary index** stores the value plus a pointer to the row.

**Example** · `SELECT * FROM orders WHERE user_id = 'u_1842'` via a secondary index on `user_id`:

```text
InnoDB:   idx leaf (u_1842 -> PK 90311) -> walk PK tree to leaf with row 90311  (2 walks)
Postgres: idx leaf (u_1842 -> TID page 7712, slot 4) -> heap page 7712 (1 walk + 1 page)
```

| | MySQL InnoDB | PostgreSQL |
|---|---|---|
| Table storage | Clustered on PK | **Heap**, no order |
| Secondary leaf stores | Value + **primary key** | Value + **TID** (page, slot) |
| Consequence | Keep PK small (copied into every secondary); sequential PKs append at the right edge | Moving a row may update indexes; **HOT updates** avoid it when no indexed column changes |

Random UUIDv4 PKs hurt InnoDB: inserts hit random leaves, causing page splits and poor caching. Use auto-increment or time-ordered IDs (UUIDv7).

## Composite, covering, and when indexes fail

**How it works** · A **composite index** `(user_id, status, created_at)` is a sorted array of tuples; it can *seek* only on a **leftmost prefix**.

**Example** · Entries in index order (status sorts CANCELLED < DELIVERED < PLACED):

```text
(u_1842, CANCELLED, 2026-09-14)
(u_1842, DELIVERED, 2026-09-02)
(u_1842, DELIVERED, 2026-10-03)  <- seek (u_1842, DELIVERED, >2026-10-01) lands here
(u_1842, DELIVERED, 2026-10-08)     read
(u_1842, PLACED,    2026-10-10)  <- prefix (u_1842, DELIVERED) ends: stop
(u_2001, DELIVERED, 2026-10-05)
```

So: the 3-column query reads 2 contiguous entries. `status = 'DELIVERED'` alone is spread across every user (lines 2, 3, 4, 6, ...): generally no seek. Also: `user_id = ? AND created_at > X` seeks on user_id only and filters created_at while scanning (status skipped); `user_id = ? ORDER BY status, created_at` seeks and the sort is free; `user_id > ? AND status = ?` seeks the user_id range only, because a range ends the seek prefix. Order columns **equality first, then range or ORDER BY last**; among equality columns, by what queries share. (MySQL 8 "skip scan" can bend this; design as if strict.)

A **covering index** holds every column the query needs: an **index-only scan**. For "Asha's last 20 orders with amounts", PostgreSQL `ON orders (user_id, created_at DESC) INCLUDE (amount)`; `EXPLAIN ANALYZE` shows `Index Only Scan ... Heap Fetches: 0`. InnoDB has no `INCLUDE`: secondaries already carry the PK, append extra columns as trailing keys. (Postgres also needs the visibility map, usually true if vacuumed.) **Cost:** every write updates every index (6 indexes ≈ 7 B+ tree updates per insert), storage can rival the table, memory pressure, harder plan choice. **Index not used when:** a function wraps the column (`LOWER(email)`; fix with an **expression index**), leading wildcard `LIKE '%son'`, implicit type conversion (`phone = 5551234` on a text column), non-leftmost column, low **selectivity** (90% of rows match, a scan is correctly cheaper), tiny table, `a = 1 OR b = 2` without bitmap OR, stale statistics (run `ANALYZE`). **EXPLAIN** shows the plan; `EXPLAIN ANALYZE` (PostgreSQL) runs it with real timings. Watch for **Seq Scan** on a big table for a selective query, estimated vs actual rows far apart, an explicit **Sort** an index could avoid. MySQL: `type` (`ALL` = scan; `ref`/`range`/`const` = index), `key`, `rows`, `Extra` (`Using index` = covering, `Using filesort` = sort).

> **Say it like this:** "Indexes trade write cost and storage for read speed. I add one per important query pattern, order columns equality-then-range, verify with EXPLAIN, and drop unused ones."

## How a query executes

> **Black box:** SQL text → **parser** (grammar, names) → **rewriter** (views, rules) → **planner/optimizer** (cost-based: picks indexes, join order, join algorithm from statistics) → **executor** (operator tree pulling rows one at a time, the "Volcano" model) ↔ buffer pool. **Use when:** reasoning about why a plan was chosen, e.g. stale stats estimate 10 rows, 2 M come back, nested loop explodes. **Cost:** bad statistics give bad plans. **Prepared statements** send the query once with placeholders (`$1`), reuse the parsed form, and prevent **SQL injection**: parameters are never parsed as SQL.

## Transactions and ACID

**How it works** · A **transaction** (`BEGIN ... COMMIT`/`ROLLBACK`) is one unit.

**Example** · Asha pays ₹450 for `o_90311` from her QuickBite wallet:

`BEGIN;` → `UPDATE wallets SET balance = balance - 45000 WHERE user_id = 'u_1842'` (60000 → 15000) → `INSERT` order 90311 (PLACED, 45000) → `COMMIT;`

1. **Atomicity:** crash after the UPDATE, before COMMIT → recovery undoes it; balance is 60000 again ("abortability").
2. **Consistency:** invariants hold (`CHECK (balance >= 0)`, FK to users). The DB enforces only declared constraints; "wallet debit = order amount" is the app's job. (ACID **C** is not CAP **C**, replicas agreeing on the latest value, ch 10.)
3. **Isolation:** a report summing wallets + orders never sees the debit without the order (strength is tunable, next section).
4. **Durability:** once COMMIT returns, it survives a crash, via WAL fsync. Surviving machine loss needs replication (ch 07).

## Isolation levels and anomalies

**How it works** · **Serializability** = as if transactions ran one at a time. Weaker levels allow **anomalies**.

**Example** · Each anomaly with values:

1. **Dirty read:** T1 sets o_90311 amount 45000 → 50000 (uncommitted); T2 reads 50000; T1 rolls back. T2 used a value that never existed.
2. **Non-repeatable read:** T1 reads o_90311 status = PLACED; T2 sets ACCEPTED, commits; T1 re-reads: ACCEPTED.
3. **Phantom:** T1 `COUNT(*) WHERE user_id='u_1842' AND day=today` → 3; T2 inserts a 4th order, commits; T1 recounts → 4. The *set* of rows changed.
4. **Lost update** (Spice Route has 10 biryani portions left):

```text
t  T1 (order o_90311, qty 1)          T2 (order o_90312, qty 1)
1  SELECT portions -> 10
2                                     SELECT portions -> 10   (T2 snapshot taken here)
3  UPDATE SET portions = 9; COMMIT
4                                     UPDATE SET portions = 9; COMMIT
   read committed / InnoDB RR: stored 9, two sold (should be 8)
   PostgreSQL RR: step 4 fails "could not serialize access", T2 retries -> 8
```

5. **Write skew** (rule: zone KRM keeps at least 1 rider on shift; rd_5521 and rd_6034 are on):

```text
t  T1 (rd_5521 takes a break)          T2 (rd_6034 takes a break)
1  SELECT count(*) on_shift, KRM -> 2
2                                      SELECT count(*) on_shift, KRM -> 2
3  UPDATE ... on_shift=false rd_5521
4                                      UPDATE ... WHERE id='rd_6034'
5  COMMIT                              COMMIT        => 0 riders on shift
so: different rows written, no write-write conflict; snapshot isolation lets both commit
```

| Level | Dirty | Non-repeatable | Phantom | Lost update | Write skew |
|---|---|---|---|---|---|
| **Read uncommitted** | Possible | Possible | Possible | Possible | Possible |
| **Read committed** (default PG, Oracle, SQL Server) | Prevented | Possible | Possible | Possible | Possible |
| **Repeatable read** (standard) | Prevented | Prevented | Possible | not defined | not defined |
| PostgreSQL RR (**snapshot isolation**) | Prevented | Prevented | Prevented for reads | Prevented (2nd writer errors, retry) | **Possible** |
| MySQL InnoDB RR (MySQL default) | Prevented | Prevented for plain SELECT | Plain SELECT: not seen; locking reads use **next-key locks** | **Possible** (not detected) | Possible |
| **Serializable** | Prevented | Prevented | Prevented | Prevented | Prevented |

- PostgreSQL treats read uncommitted as read committed. Its Serializable is **SSI** (snapshot + dependency tracking; aborts dangerous patterns, e.g. one of the two rider transactions; you must retry).
- InnoDB RR: plain SELECTs read a snapshot, but `UPDATE`/`DELETE`/`FOR UPDATE` act on the *latest committed* row. MySQL Serializable works by locking (blocks or deadlocks, rather than aborting later).
- An **atomic update** `SET portions = portions - 1` is safe from lost updates at every level in both engines (row locked, newest value used); lost update comes from read-into-app, compute, write back. "Repeatable read" differs per engine; always name it.

**Fixing write skew:** Serializable + retry; lock the rows the decision reads (`SELECT ... WHERE zone='KRM' AND on_shift FOR UPDATE`, so T2 waits for T1 and then sees 1); or **materialize the conflict** (one row per slot to lock, or `UNIQUE(table_id, slot)` for table bookings).

> **Say it like this:** "Read committed only stops dirty reads. Postgres repeatable read is snapshot isolation, which still allows write skew, so for cross-row invariants I lock with FOR UPDATE, add a constraint, or use serializable and retry."

## Concurrency control: pessimistic, optimistic, MVCC

**How it works** · **Pessimistic locking** locks first; **optimistic locking** checks at write time with a `version` column.

**Example** · Pessimistic, the biryani race done right (read committed):

```text
t  T1                                       T2
1  SELECT portions ... FOR UPDATE -> 10
2                                           SELECT ... FOR UPDATE   (blocks)
3  UPDATE SET portions = 9; COMMIT (unlock)
4                                           unblocks, sees 9; UPDATE to 8; COMMIT
```

**Example** · Optimistic, two Spice Route staff edit the biryani price, both loaded `price=18000, version=7`:

```sql
UPDATE menu_items SET price = 20000, version = version + 1 WHERE id = 5 AND version = 7;
-- staff A: 1 row (version 8). staff B, same statement: 0 rows -> re-read, retry
```

Pessimistic assumes conflicts are common and waits; optimistic assumes they are rare and retries, and unlike locks it can span multiple HTTP requests (edit forms; same idea as `ETag` + `If-Match`). Pessimistic is simple and retry-free but others wait, and **deadlock** is possible: T1 locks item 5 then wants 9 while T2 locks 9 then wants 5; the DB finds the cycle in the waits-for graph and aborts one. Mitigate: lock in ascending id order, keep transactions short. Variants: `FOR SHARE`, `NOWAIT` (fail fast), `SKIP LOCKED` (job-queue table: each worker grabs a different job). A hot row under contention (dinner-peak flash deal) is better served by atomic `UPDATE ... WHERE portions >= 2` or pessimistic locking than by optimistic retries.

> **Black box:** **MVCC** keeps multiple row versions; each txn (or statement under read committed) reads a **snapshot** of versions committed before it, e.g. txn 100 wrote portions=10, txn 205 wrote 8: an older snapshot still reads 10. Readers never block writers and vice versa (writers still block writers on a row). PostgreSQL writes new row versions, cleaned by **VACUUM**; InnoDB updates in place with old versions in **undo logs**, cleaned by **purge**. **Cost:** long transactions keep old versions alive (bloat / undo growth).

## Write-ahead log and durability

**How it works** · Pages change in memory and flush later; to survive a crash, first append the change to a sequential **write-ahead log (WAL)** (InnoDB: **redo log**), **fsync** it on COMMIT, then report success.

**Example** · Committing the portions change for `o_90311`:

```text
1. append "txn 205: page 9133 slot 4: portions 10 -> 9; COMMIT" to WAL buffer
2. fsync WAL                    <- durability point; sequential, fast
3. return COMMIT OK to order-service
4. CRASH before page 9133 was written back
recovery: replay WAL from last checkpoint -> redo txn 205 (committed) on page 9133;
          undo any txn with no COMMIT record
so: commit costs one sequential append; random 16 KB page writes happen lazily, in batches
```

> **Black box:** **Group commit** (one fsync for many commits); durability knobs (`innodb_flush_log_at_trx_commit=1` fsyncs every commit; `0`/`2` flush ~once a second and can lose ~1 s on crash; PostgreSQL `synchronous_commit=off` is similar); **checkpoints** bound replay. **Use when:** trading durability for latency. **Cost:** relaxed knobs can lose the last ~1 s of commits; note the WAL is also the replication stream (ch 07) and the CDC source (ch 11).

## Connection pooling

**How it works** · A connection costs TCP, often TLS, auth, setup; PostgreSQL uses a process per connection (several MB each), MySQL a thread. A DB happy at 100–300 active connections struggles at 5,000. A **connection pool** keeps a fixed set open and lends them out; extra requests wait.

**Example** · At dinner peak `order-service` runs 200 pods x 50 connections = 10,000 connections: the DB spends its time context switching. Instead: each pod pools 5, and PgBouncer (transaction pooling) multiplexes those 1,000 client connections onto ~32 server connections (a 16-core DB x 2). A request borrows a connection for one transaction and returns it; the 33rd concurrent transaction waits briefly in the pooler.

Pools live **in-app** (HikariCP, driver pools) or in an **external pooler** (PgBouncer, ProxySQL); in **transaction pooling**, session features (session variables, some prepared statements, advisory locks) need care. Sizing: start at roughly `2 x CPU cores` active connections on the DB side, then measure; the total across all app instances must fit the DB limit.

> **Common mistake:** raising pool size when queries are slow. A saturated DB gets slower with more concurrent queries; fix the slow query (EXPLAIN, slow log, N+1), cache, or add replicas.

## Worked example: QuickBite order schema

**How it works** · Queries drive indexes. Requirements: login by email, menu listing by price, place an order without overselling portions, "my orders" paginated, order detail, ops view of orders stuck in PLACED. Deliberately not indexed: `status` alone (low selectivity), `menu_items.name` for "biryani" search (a search engine, ch 09).

| Index | Serves | Why |
|---|---|---|
| `users UNIQUE (LOWER(email))` | Login | Case-insensitive uniqueness + one seek; query must use `LOWER(email) = LOWER($1)` |
| `menu_items (restaurant_id, price)` | Menu by price | Equality then order: contiguous slice, no sort, `LIMIT` stops early |
| `orders (user_id, created_at DESC, id DESC)` | My orders | `id` breaks ties for stable pagination |
| **Partial index** `orders (created_at) WHERE status='PLACED'` | Ops view | Only matching rows; tiny, cheap (PostgreSQL) |
| PK `order_items (order_id, item_id)` + `(item_id)` | Order detail; FK checks | Items contiguous, no duplicate lines; PostgreSQL does not auto-index FK columns (InnoDB does) |

**Example** · Keyset pagination, not `OFFSET` (`OFFSET 10000` walks and discards 10,000 rows). Page 1 for Asha ends at `(2026-10-08 20:14, 90311)`; page 2:

```sql
SELECT id, amount, created_at FROM orders
WHERE user_id = 'u_1842' AND (created_at, id) < ('2026-10-08 20:14', 90311)
ORDER BY created_at DESC, id DESC LIMIT 20;   -- one lower_bound, same cost for any page
```

**Example** · Placing `o_90311` (2 x biryani id 5 at 18000 + 1 x raita id 9 at 9000 = 45000), one transaction:

1. `UPDATE menu_items SET portions = portions - 2 WHERE id = 5 AND portions >= 2` → 1 row.
2. `UPDATE ... portions - 1 WHERE id = 9 AND portions >= 1` → 0 rows (raita sold out) → `ROLLBACK`, show "Raita unavailable". Biryani portions are restored by the rollback.
3. If both return 1 row: insert `orders` (amount 45000, prices read from the DB, never the client) and `order_items`, COMMIT. So: ascending id order (5 then 9) avoids deadlocks between orders sharing items; the guarded update is atomic even at read committed; `CHECK (portions >= 0)` is a second defense.

## The N+1 query problem

**How it works** · Loading a list, then each related row in a loop, costs 1 + N round trips (often generated by an **ORM**).

**Example** · Asha's "past orders" screen: `SELECT * FROM orders WHERE user_id='u_1842' LIMIT 50` (1 query), then `SELECT name FROM restaurants WHERE id=?` for each order (50 queries). Each is ~0.2 ms on the server but a round trip is roughly 0.5–1 ms: 51 trips ≈ 25–50 ms of waiting, multiplied by nested relations (orders → items → menu_items). Fix: one **join** (`JOIN restaurants r ON r.id = o.restaurant_id`), or **batch load** (`WHERE id IN ('r_77', 'r_12', ...)`): 2 queries regardless of N. ORMs call it eager loading / prefetch; GraphQL calls it a data loader. Log query count per request in development.

## When SQL is the right choice

**How it works** · Default to relational unless you have a reason not to: multi-row, multi-table transactions (money, inventory, bookings); integrity enforced by the DB; flexible queries for unknown future questions; mature tooling (backups, point-in-time recovery, replication, monitoring); and it scales further than people think (one well-indexed primary handles very roughly thousands to tens of thousands of writes/s, more reads with replicas and caching).

**Example** · QuickBite orders: 2 M/day ≈ 23 writes/s average, ~120/s at peak, needing transactions with payments and portions: one PostgreSQL primary with replicas, easily. Rider pings: ~25,000 writes/s of append-only, key-by-rider data with no joins: at or past one primary's edge and a poor fit, so look elsewhere (ch 06).

Look elsewhere for: writes/size beyond one primary without DIY sharding, pure key-value at huge scale, highly variable document shapes, deep graph traversals, full-text search, analytics over billions of rows (columnar warehouse, ch 09). Myths: "joins are slow" (unindexed ones are), "more indexes = faster" (writes slow), "PK = clustered everywhere" (not in PostgreSQL), "transactions make everything correct" and "SELECT then UPDATE in a txn is safe" (not at read committed or InnoDB RR), "SQL doesn't scale" (vertically far, replicas, sharding, distributed SQL; ch 06–07).

## In one minute

- A DBMS gives indexes, concurrency control, crash safety, transactions and SQL; normalize first, denormalize measured hot paths.
- Indexes are high-fanout B+ trees: 3 pages for 100 M rows, usually 0–1 disk reads; range = lower_bound + walk leaves.
- InnoDB clusters rows by PK (secondaries store the PK, two walks); PostgreSQL uses heaps and TIDs.
- Composite indexes: leftmost prefix, equality first, range/sort last; covering indexes skip the table; verify with EXPLAIN.
- Isolation is the knob: read committed allows lost update and write skew, PG RR is snapshot isolation (write skew possible), InnoDB RR misses lost updates, serializable needs retries or blocks.
- Pessimistic = FOR UPDATE; optimistic = version check + retry; MVCC lets readers and writers not block each other.
- Commit is durable when the WAL record is fsynced; pages flush later. Keep pools small, kill N+1 with joins or batches, paginate with keysets.

## Go deeper

Full chapter 05: sections 05.4 (indexes), 05.7 (isolation levels and anomalies), 05.8 (concurrency control), 05.11 (worked e-commerce schema).
