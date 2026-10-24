# 05 — Relational databases, deeply

Almost every system you will ever design has a relational database somewhere in it, and in most
design conversations "we store it in Postgres/MySQL" is the default answer. This chapter explains
what actually happens inside one: how data is laid out, how indexes make queries fast, what a
transaction really guarantees, and where the sharp edges are. After it, words like "covering
index", "repeatable read" and "SELECT ... FOR UPDATE" should feel concrete.

## 05.1 Why a database at all?

Imagine storing users in a plain file, one JSON object per line. It works for a weekend project.
Then real requirements arrive:

| Need | What goes wrong with a plain file |
|---|---|
| Find user 8,412,331 quickly | You scan the whole file: O(N) per lookup |
| Two requests update the same user at once | Both read, both write, one update silently vanishes |
| Server crashes halfway through a write | File is half-written and corrupt |
| "Move $50 from A to B" | Debit written, crash, credit never written: money disappears |
| Ask a new question ("users who ordered twice last week") | You write custom code for each question |
| 200 app servers read and write at once | You need a server process to coordinate access anyway |

A **database management system (DBMS)** is the program that solves all of these once, well:

- **Fast lookup** via indexes (05.4).
- **Concurrency control** so simultaneous users do not corrupt each other (05.8).
- **Crash safety** via a write-ahead log (05.9).
- **Transactions** so a group of changes happens all-or-nothing (05.6).
- **A query language** (SQL) so new questions do not need new code.

A **relational database** (PostgreSQL, MySQL, SQL Server, Oracle, SQLite) stores data as
tables with a fixed structure and lets you combine tables with joins. It is the default choice
for most systems because it gives you all of the above out of the box.

## 05.2 Tables, rows, columns, keys, joins

### The vocabulary

- A **table** (also called a **relation**) is a named collection of rows with the same shape,
  e.g. `users`.
- A **column** is a named, typed field: `email VARCHAR(255)`, `created_at TIMESTAMP`.
- A **row** (or **tuple**, **record**) is one entry: one user.
- The **schema** is the declared structure: tables, columns, types, constraints. The database
  enforces it on every write.
- A **primary key (PK)** is a column (or set of columns) whose value uniquely identifies a row.
  It can never be NULL and never repeats. Usually an auto-increment integer or a UUID.
- A **foreign key (FK)** is a column whose value must match a primary key in another table. The
  database rejects an order pointing at a user that does not exist. This is **referential
  integrity**.
- A **constraint** is any rule the database enforces: `NOT NULL`, `UNIQUE`, `CHECK (price >= 0)`,
  PK, FK.

```sql
CREATE TABLE users (
  id         BIGSERIAL PRIMARY KEY,          -- auto-increment 64-bit id (PostgreSQL)
  email      VARCHAR(255) NOT NULL UNIQUE,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id),   -- foreign key
  status     TEXT NOT NULL CHECK (status IN ('pending','paid','shipped','cancelled')),
  total_cents BIGINT NOT NULL CHECK (total_cents >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Note `total_cents BIGINT`: money is stored as integer cents (or `NUMERIC`), never as floating
point, because `0.1 + 0.2` is not exactly `0.3` in binary floating point.

### Joins

A **join** combines rows of two tables where a condition matches. Small data:

```text
users                     orders
id | name                 id  | user_id | total_cents
---+------                ----+---------+------------
 1 | Asha                 100 |    1    |   2500
 2 | Ben                  101 |    1    |    900
 3 | Chen                 102 |    2    |   4000
```

```sql
-- INNER JOIN: only rows that match on both sides
SELECT u.name, o.id, o.total_cents
FROM users u
JOIN orders o ON o.user_id = u.id;
-- Asha 100 2500 | Asha 101 900 | Ben 102 4000      (Chen has no orders -> absent)

-- LEFT JOIN: every user, with NULLs where there is no matching order
SELECT u.name, COUNT(o.id) AS order_count
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
GROUP BY u.name;
-- Asha 2 | Ben 1 | Chen 0
```

How does the database execute a join? Three classic algorithms, all familiar from DSA:

| Algorithm | Idea | Good when |
|---|---|---|
| **Nested loop join** | For each row of A, look up matches in B (ideally via an index on B) | A is small and B has an index on the join column |
| **Hash join** | Build a hash map of the smaller side on the join key, probe it with the other side | Large inputs, equality joins, no useful index |
| **Merge join** | Both sides sorted on the join key; walk them together like merging two sorted arrays | Inputs already sorted (e.g. from an index) |

The **query planner** (05.5) picks among them based on table statistics.

## 05.3 Normalization and denormalization

### The problem normalization solves

Suppose you store orders in one wide table:

```text
order_id | user_email      | user_name | product_name | product_price | qty
---------+-----------------+-----------+--------------+---------------+----
   100   | asha@x.com      | Asha      | Mug          |     900       |  2
   100   | asha@x.com      | Asha      | Pen          |     150       |  1
   101   | asha@x.com      | Asha S.   | Mug          |     950       |  1
```

Problems (called **anomalies**):

- **Update anomaly:** Asha changes her name. You must update every row; miss one and the data
  disagrees with itself (row 3 already says "Asha S.").
- **Insert anomaly:** you cannot record a new product until someone orders it.
- **Delete anomaly:** delete the last order of a product and you lose the product's price.

**Normalization** means splitting data so that each fact is stored in exactly one place.

### The normal forms, intuitively

- **1NF (first normal form):** each cell holds one atomic value; no lists inside a cell, no
  repeating column groups like `item1, item2, item3`. Fix: one row per item.
- **2NF:** in 1NF, and every non-key column depends on the *whole* key. In a table keyed by
  `(order_id, product_id)`, `product_name` depends only on `product_id`, which is part of the
  key. Fix: move product facts to a `products` table.
- **3NF:** in 2NF, and non-key columns depend on the key *directly*, not via another non-key
  column. If `orders` has `user_id` and also `user_email`, then email depends on user_id, not on
  the order. Fix: keep only `user_id` in orders; email lives in `users`.

A popular mnemonic: every non-key column depends on "the key, the whole key, and nothing but
the key."

```text
Normalized (3NF):

users(id PK, email, name)
products(id PK, name, price_cents)
orders(id PK, user_id FK->users, status, created_at)
order_items(order_id FK->orders, product_id FK->products, qty, unit_price_cents,
            PK(order_id, product_id))
```

Notice `order_items.unit_price_cents`. This is not a normalization violation: the price *at the
time of purchase* is a different fact from the product's *current* price. Deciding what a fact
really is matters more than reciting normal forms.

### Denormalization: the trade-off

Normalized data needs joins to read. At high read volume, you sometimes **denormalize**:
deliberately store a copy of data to avoid joins or expensive aggregation.

Examples:
- Store `order_count` on `users` instead of `COUNT(*)` every time.
- Store `author_name` on each `comment` row so a comment list needs no join.

| | Normalized | Denormalized |
|---|---|---|
| Writes | Simple, one place to update | Must update every copy (or accept staleness) |
| Reads | Joins, sometimes slow | Fast, fewer joins |
| Consistency | Guaranteed by structure | Your code must keep copies in sync |
| Storage | Minimal | Duplicated |

> **Say it like this:** "I'd start normalized so there is one source of truth, then denormalize
> specific hot read paths once I know they are hot, and keep the copies in sync in the same
> transaction or accept a known staleness window."

> **Common mistake:** denormalizing up front "for performance" before measuring. A join on
> indexed columns over a few thousand rows is fast. Denormalize the measured hot path, not
> everything.

## 05.4 Indexes: how lookups become fast

### The problem

Without an index, `SELECT * FROM users WHERE email = 'asha@x.com'` must read every row: a
**full table scan** (also called a **sequential scan**). With 50 million users that is reading
gigabytes from disk for one row.

An **index** is a separate sorted data structure that maps column values to row locations. It
is exactly like the index at the back of a book: sorted words, each pointing to page numbers.

### Pages: the unit of disk I/O

Databases do not read single rows from disk. They read fixed-size **pages** (also called
**blocks**): 8 KB in PostgreSQL, 16 KB by default in MySQL's InnoDB engine. A table is a pile of
pages; an index is a tree of pages. Performance is mostly about "how many pages did this query
touch, and were they already in memory?" The in-memory cache of pages is called the **buffer
pool** (InnoDB) or **shared buffers** (PostgreSQL).

### The B+ tree

Almost every relational index is a **B+ tree**: a balanced search tree where each node is one
page and has *many* children (hundreds), not two.

```text
                         [ root page ]
                   |  40  |  80  |  120  |              keys are separators
                  /       |      |        \
     [ internal ]   [ internal ]   [ internal ]   [ internal ]
     | 10 | 25 |    | 50 | 65 |    | 90 |105|     |130|150|
      /   |   \       ...             ...            ...
 [leaf]  [leaf]  [leaf] <-> [leaf] <-> [leaf] <-> [leaf] ...   leaves form a linked list
  1..9   10..24  25..39     40..49     50..64     ...
  key -> row location (or the row itself, see clustered index)
```

Properties:
- All data entries live in the **leaves**; internal nodes only hold separator keys to guide the
  search.
- The leaves are linked left to right, so after finding the start of a range you just walk.
- The tree stays balanced on insert/delete by splitting and merging pages, so every lookup is
  the same depth.

**DSA bridge:** a point lookup is a binary search inside each page on the way down. A range
query `WHERE created_at BETWEEN x AND y` is `lower_bound(x)` followed by walking the leaf list
until you pass `y`.

### Why only 3–4 levels for millions of rows

**Fanout** is how many children a node has. A 16 KB page holding keys of ~8 bytes plus child
pointers of ~6–8 bytes plus some per-entry overhead fits very roughly several hundred to about a
thousand entries. Take a conservative fanout of 500:

```text
level 1 (root):        1 page                ->           500 children
level 2:             500 pages               ->       250,000 children
level 3:         250,000 pages (leaves)      ->  each leaf holds ~hundreds of entries
                                                 => roughly 25-100 million rows
level 4:                                         => roughly 10+ billion rows
```

The depth is `log_fanout(N)`. With fanout 500, `log_500(100,000,000) ≈ 2.96`. So:

- A lookup in a 100-million-row table touches roughly 3–4 pages.
- The root and upper levels are tiny and almost always cached in memory, so in practice a
  lookup usually costs 0–1 actual disk reads.

Compare a binary search tree: `log_2(100,000,000) ≈ 27` levels, each potentially a separate disk
read. High fanout is the entire reason B+ trees exist: disk I/O is slow, so make each read do
as much branching as possible.

### Clustered vs secondary indexes

- A **clustered index** stores the table rows themselves in the index's leaves, in key order.
  The table *is* the index. There can be only one per table because rows can only be physically
  ordered one way.
- A **secondary index** (non-clustered) stores the indexed column plus a pointer back to the row.

The two big engines differ here, and it is worth knowing:

| | MySQL InnoDB | PostgreSQL |
|---|---|---|
| Table storage | Clustered on the primary key: rows live in the PK B+ tree | A **heap**: rows in no particular order |
| Secondary index leaf stores | The indexed value + the row's **primary key** | The indexed value + a physical row pointer (a **TID**: page number, slot) |
| Lookup via secondary index | Search secondary index → get PK → search PK tree (two tree walks) | Search index → jump straight to heap page |
| Consequence | Keep the PK small (it is copied into every secondary index); sequential PKs insert at the end of the tree and avoid random page splits | Updates that move a row may need index updates; PostgreSQL has optimizations (HOT updates) to avoid this when no indexed column changes |

InnoDB's two-step lookup is why "random UUIDv4 as primary key" hurts in MySQL: inserts land on
random leaf pages all over the tree, causing page splits and poor cache use. Time-ordered IDs
(auto-increment, or time-ordered UUID variants such as UUIDv7) insert near the right edge.

### Composite indexes and the leftmost-prefix rule

A **composite index** (multi-column index) is sorted by the first column, then by the second
within equal first values, and so on, exactly like sorting tuples:

```sql
CREATE INDEX idx_orders_user_status_created ON orders (user_id, status, created_at);
```

```text
(user_id, status, created_at)
(7, 'paid',    2026-01-02)
(7, 'paid',    2026-03-15)
(7, 'pending', 2026-02-01)
(8, 'paid',    2026-01-09)
(8, 'shipped', 2026-01-04)
...
```

**Leftmost-prefix rule:** the index can be used to *seek* for conditions on a leftmost prefix of
its columns.

| Query condition | Can it seek with this index? |
|---|---|
| `user_id = 7` | Yes (prefix: user_id) |
| `user_id = 7 AND status = 'paid'` | Yes |
| `user_id = 7 AND status = 'paid' AND created_at > '2026-02-01'` | Yes, all three |
| `user_id = 7 AND created_at > '2026-02-01'` | Seeks on user_id only; created_at is filtered while scanning user 7's entries |
| `status = 'paid'` | Generally no seek (status is not the leftmost column) |
| `user_id = 7 ORDER BY status, created_at` | Yes, and the sort is free: rows come out already ordered |
| `user_id > 5 AND status = 'paid'` | Seeks on the user_id range; status cannot narrow the seek after a range on an earlier column |

Two rules of thumb for column order:
1. Columns compared with **equality** first, then the column used for a **range** or
   **ORDER BY** last. A range on a column "ends" the useful seek prefix.
2. Among equality columns, order by what your queries actually share.

Some engines can partially use a non-leading column (for example MySQL 8's "skip scan" when the
leading column has very few distinct values), but design as if the rule is strict.

**DSA bridge:** a composite index is a sorted array of tuples. You can binary search for
`(7, 'paid', *)` because those entries are contiguous. You cannot binary search for
`(*, 'paid', *)`: those entries are scattered.

### Covering indexes

If every column the query needs is already in the index, the database never has to visit the
table row. That is a **covering index**, and the plan is called an **index-only scan**.

```sql
-- Query: list a user's recent order ids and totals
SELECT id, total_cents FROM orders
WHERE user_id = 7 ORDER BY created_at DESC LIMIT 20;

-- Covering index (PostgreSQL syntax: INCLUDE adds payload columns not used for ordering)
CREATE INDEX idx_orders_user_created
  ON orders (user_id, created_at DESC) INCLUDE (total_cents);
```

In InnoDB, every secondary index already contains the primary key, so `id` comes "for free";
MySQL has no `INCLUDE`, so you would append `total_cents` as a trailing key column. (PostgreSQL
index-only scans also need the visibility map to show the pages are all-visible, which is
usually true for tables that are regularly vacuumed.)

### The cost of indexes

Indexes are not free:

- **Every write updates every index.** A table with 6 indexes does roughly 7 B+ tree updates
  per insert. Write throughput drops and write latency rises.
- **Storage.** Indexes can be as large as the table itself.
- **Memory pressure.** Indexes compete with data for the buffer pool.
- **Planner confusion.** Too many overlapping indexes make plan choice harder.

> **Say it like this:** "Indexes trade write cost and storage for read speed. I add an index for
> each important query pattern, choose column order by equality-then-range, and drop indexes
> nobody uses."

### When an index is NOT used

| Situation | Why |
|---|---|
| `WHERE LOWER(email) = 'a@x.com'` with an index on `email` | The index is sorted by `email`, not `LOWER(email)`. Fix: an **expression index** on `LOWER(email)` |
| `WHERE name LIKE '%son'` | Leading wildcard: no sorted prefix to seek. `LIKE 'son%'` can use the index (subject to collation) |
| `WHERE phone = 5551234` when `phone` is a string column | Implicit type conversion may apply a function to the column, disabling the seek |
| Condition on a non-leftmost column of a composite index | Leftmost-prefix rule |
| Low selectivity, e.g. `WHERE is_active = true` matching 90% of rows | Reading 90% of rows through random index lookups is slower than one sequential scan; the planner chooses the scan, correctly |
| Tiny table | A scan of a few pages beats an index walk |
| `WHERE a = 1 OR b = 2` | Needs both indexes and a merge (some planners do it, e.g. bitmap OR); otherwise a scan |
| Stale statistics | Planner misestimates row counts; run `ANALYZE` |

**Selectivity** is the fraction of rows a condition matches. Indexes pay off for selective
conditions (a small fraction of rows). The planner decides using statistics it keeps about each
column's value distribution.

### EXPLAIN: asking the database what it will do

`EXPLAIN` shows the plan without running the query; `EXPLAIN ANALYZE` (PostgreSQL) runs it and
shows real timings and row counts.

```sql
EXPLAIN ANALYZE
SELECT id, total_cents FROM orders WHERE user_id = 7 ORDER BY created_at DESC LIMIT 20;
```

```text
Limit  (cost=0.56..45.10 rows=20) (actual time=0.031..0.060 rows=20 loops=1)
  ->  Index Only Scan using idx_orders_user_created on orders
        (cost=0.56..410.22 rows=184) (actual time=0.030..0.055 rows=20 loops=1)
        Index Cond: (user_id = 7)
        Heap Fetches: 0
Planning Time: 0.120 ms
Execution Time: 0.081 ms
```

What to look for:
- **Seq Scan** on a big table for a selective query: missing or unusable index.
- **Index Scan / Index Only Scan**: good for selective queries.
- **Estimated rows vs actual rows** wildly different: stale statistics or correlated columns.
- An explicit **Sort** node on a large input when an index could have provided the order.

In MySQL, `EXPLAIN` shows a row per table with `type` (`ALL` = full scan, `ref`/`range`/`const`
= index use), `key` (index chosen), `rows` (estimate), and `Extra` (`Using index` = covering,
`Using filesort` = explicit sort).

## 05.5 How a query executes

```text
 SQL text
    |
    v
+---------+    syntax tree     +----------+   logical plan   +-----------+
| Parser  | -----------------> | Rewriter | ---------------> |  Planner  |
| checks  |                    | expands  |                  | (optimizer)|
| grammar |                    | views,   |                  | picks join |
| & names |                    | rules    |                  | order, join|
+---------+                    +----------+                  | algorithm, |
                                                             | indexes by |
                                                             | estimated  |
                                                             | cost       |
                                                             +-----+-----+
                                                                   | physical plan
                                                                   v
                         +-----------+   pages    +-------------------+
            rows  <----- | Executor  | <--------> | Buffer pool / disk|
                         | runs plan |            +-------------------+
                         | operators |
                         +-----------+
```

1. **Parser:** turns text into a syntax tree; checks the tables and columns exist.
2. **Rewriter:** expands views and applies rewrite rules (PostgreSQL has an explicit rewriter
   stage; other engines fold this into planning).
3. **Planner / optimizer:** enumerates possible plans (which index, which join order, which join
   algorithm) and estimates the cost of each using statistics: row counts, value distributions,
   number of distinct values. Picks the cheapest. This is **cost-based optimization**.
4. **Executor:** runs the plan as a tree of operators (scan, filter, join, sort, aggregate,
   limit). Many engines pull rows through the tree one at a time: each operator asks its child
   for the next row (the "iterator" or "Volcano" model).

**Prepared statements** let the client send the query once with placeholders (`WHERE id = $1`)
and reuse the parsed (and sometimes planned) form. They also prevent **SQL injection**, because
parameters are never parsed as SQL (see chapter 15).

## 05.6 Transactions and ACID

A **transaction** is a group of operations that the database treats as one unit.

```sql
BEGIN;
UPDATE accounts SET balance = balance - 50 WHERE id = 'A';
UPDATE accounts SET balance = balance + 50 WHERE id = 'B';
COMMIT;      -- or ROLLBACK; to undo everything since BEGIN
```

**ACID** names four guarantees. Using the transfer of $50 from A to B:

- **Atomicity** — all or nothing. If the server crashes after the debit but before the credit,
  the debit is undone on recovery. No state where A lost $50 and B did not gain it is ever
  visible. ("Abortability" would be a more accurate name: the transaction can be aborted and all
  its effects disappear.)
- **Consistency** — the transaction moves the database from one valid state to another, where
  "valid" means your invariants hold: constraints like `CHECK (balance >= 0)`, foreign keys, and
  application rules like "total money is conserved". Note that the database only enforces the
  constraints you declared; the rest is your application's job. The C is mostly a property of
  how you use A, I and D.
- **Isolation** — concurrent transactions do not see each other's half-done work. If someone
  sums all balances while the transfer runs, they see either the before state or the after
  state, not A debited and B not yet credited. How strong this is depends on the isolation level
  (05.7).
- **Durability** — once `COMMIT` returns success, the transfer survives a crash or power loss.
  This comes from the write-ahead log being flushed to stable storage before the commit is
  acknowledged (05.9). (Durability against losing the whole machine needs replication, see
  chapter 07.)

> **Say it like this:** "ACID means the transfer either fully happens or not at all, leaves the
> constraints true, isn't observed half-done by other transactions, and once committed survives a
> crash. The I is the one with knobs: isolation levels trade correctness for concurrency."

> **Common mistake:** confusing ACID Consistency with the "C" in CAP (chapter 10). ACID
> consistency is about invariants holding; CAP consistency is about all replicas agreeing on the
> latest value (linearizability). Same word, different ideas.

## 05.7 Isolation levels and anomalies

Perfect isolation (every transaction behaves as if it ran alone, one after another) is called
**serializability**. It costs throughput, so databases offer weaker levels that allow some
**anomalies** (wrong-looking results under concurrency).

### The anomalies, each with a tiny example

- **Dirty read:** T2 reads data T1 wrote but has not committed. T1 rolls back; T2 acted on data
  that never existed.
- **Non-repeatable read (fuzzy read):** T1 reads row X = 100. T2 updates X to 50 and commits. T1
  reads X again and gets 50. Same query, same transaction, different answer.
- **Phantom read:** T1 runs `SELECT COUNT(*) FROM orders WHERE user_id = 7` and gets 3. T2
  inserts a new order for user 7 and commits. T1 runs the same query and gets 4. A new row
  appeared that matches the condition. (Non-repeatable read is about an existing row changing;
  phantom is about the *set* of matching rows changing.)
- **Lost update:** two transactions read-modify-write the same value. Both read stock = 10, both
  compute 10 - 1 = 9, both write 9. Two items were sold but stock fell by only 1.
- **Write skew:** two transactions read the same data, each makes a decision based on it, and
  each writes a *different* row, so neither overwrites the other, but together they break an
  invariant. Classic example: a hospital requires at least one doctor on call. Doctors A and B are
  both on call. Each transaction checks "are at least 2 on call?" (yes), then sets its own
  doctor off call. Both commit; zero doctors are on call.

```text
Write skew timeline:

T1 (doctor A)                            T2 (doctor B)
SELECT count(*) on_call  -> 2            SELECT count(*) on_call -> 2
UPDATE doctors SET on_call=false         UPDATE doctors SET on_call=false
  WHERE name='A'                           WHERE name='B'
COMMIT                                   COMMIT
                     result: 0 doctors on call (invariant broken)
```

### The four standard levels

The SQL standard defines four levels in terms of the first three anomalies. Lost update and
write skew are not in the standard's definitions, but they matter in practice, so the table
includes them for the two most common engines.

| Level | Dirty read | Non-repeatable read | Phantom | Lost update (read-modify-write) | Write skew |
|---|---|---|---|---|---|
| **Read uncommitted** (standard) | Possible | Possible | Possible | Possible | Possible |
| **Read committed** (standard; default in PostgreSQL, Oracle, SQL Server) | Prevented | Possible | Possible | Possible | Possible |
| **Repeatable read** (as the standard defines it) | Prevented | Prevented | Possible | (not defined) | (not defined) |
| PostgreSQL **Repeatable read** (snapshot isolation) | Prevented | Prevented | Prevented for reads (snapshot) | Prevented: the second writer gets a serialization error and must retry | **Possible** |
| MySQL InnoDB **Repeatable read** (default in MySQL) | Prevented | Prevented for plain SELECTs (snapshot) | Plain SELECTs: not seen (snapshot). Locking reads use next-key locks to block inserts into the scanned range | **Possible** (no automatic detection) | Possible |
| **Serializable** | Prevented | Prevented | Prevented | Prevented | Prevented |

Careful notes that make this table correct:

- **PostgreSQL** treats `READ UNCOMMITTED` as `READ COMMITTED`: it never shows dirty reads.
- **PostgreSQL Repeatable Read** is **snapshot isolation**: the transaction sees a consistent
  snapshot taken at its first statement. It is stronger than the standard requires (no
  phantoms in what it reads) but it is still not serializable: write skew is possible. If two
  transactions update the same row, the later one fails with "could not serialize access due to
  concurrent update", which stops lost updates.
- **PostgreSQL Serializable** uses **Serializable Snapshot Isolation (SSI)**: snapshot isolation
  plus tracking of read/write dependencies; it aborts a transaction when a dangerous pattern
  appears. Your code must retry aborted transactions.
- **MySQL InnoDB Repeatable Read** gives plain `SELECT`s a consistent snapshot, but `UPDATE`,
  `DELETE` and locking reads (`SELECT ... FOR UPDATE` / `FOR SHARE`) operate on the **latest
  committed** rows, not the snapshot. So a transaction can see one value with `SELECT` and
  update a different current value. It does not detect lost updates for an application-side
  read-modify-write. **Next-key locks** (lock on an index record plus the gap before it) block
  other transactions from inserting into a range a locking read scanned, which prevents
  phantoms for locking reads.
- **MySQL Serializable** works mainly by locking: plain `SELECT`s become locking shared reads
  (when autocommit is off), so conflicting transactions block or deadlock rather than being
  aborted afterwards.
- An **atomic update** like `UPDATE products SET stock = stock - 1 WHERE id = 5` is safe from
  lost updates at every level in both engines, because the row is locked and the newest value
  is used. Lost update comes from reading into application code, computing, and writing back.

> **Say it like this:** "Read committed only stops dirty reads. Postgres repeatable read is
> really snapshot isolation, which still allows write skew, so for invariants that span rows I
> either lock the rows I read with FOR UPDATE, add a constraint, or use serializable and retry on
> serialization failures."

> **Common mistake:** assuming "repeatable read" means the same thing in every database. It does
> not. Always name the engine.

### Fixing write skew in practice

- Use `SERIALIZABLE` and retry on failure.
- Lock the rows the decision depends on: `SELECT ... FROM doctors WHERE shift = 1 FOR UPDATE`.
  Both transactions now contend on the same rows, so they serialize.
- **Materialize the conflict**: when the problem is "no row exists yet" (e.g. two people booking
  the same meeting room slot), create rows to lock, such as one row per room per time slot, or
  enforce it with a unique constraint (`UNIQUE(room_id, slot)`), which the database checks
  atomically.

## 05.8 Concurrency control: pessimistic, optimistic, MVCC

### Pessimistic locking

**Pessimistic locking** assumes conflicts are likely, so it locks first and works second.

```sql
BEGIN;
SELECT stock FROM products WHERE id = 5 FOR UPDATE;   -- row lock; other FOR UPDATE / UPDATE
                                                      -- on this row now wait
-- application checks stock >= qty
UPDATE products SET stock = stock - 2 WHERE id = 5;
COMMIT;                                               -- lock released
```

- **Gain:** simple, correct, no retries.
- **Pay:** waiting. Long transactions holding locks kill throughput. Risk of **deadlock**: T1
  locks row A then wants B; T2 locks B then wants A. The database detects the cycle (a cycle in
  the "waits-for" graph, a graph problem you know) and aborts one of them. Mitigation: always
  lock rows in a consistent order (e.g. by ascending id), keep transactions short.
- **Variants:** `FOR SHARE` (others can read-lock too, but not write); `NOWAIT` (fail
  immediately instead of waiting); `SKIP LOCKED` (skip rows someone else has locked, the classic
  trick for a job queue table where many workers each grab a different pending job).

### Optimistic locking

**Optimistic locking** assumes conflicts are rare: read without locking, then at write time
check nothing changed. A common approach is a `version` column:

```sql
-- read
SELECT id, stock, version FROM products WHERE id = 5;      -- stock=10, version=7

-- write, only if nobody changed it since we read
UPDATE products
SET stock = 8, version = version + 1
WHERE id = 5 AND version = 7;
-- rows affected = 1  -> success
-- rows affected = 0  -> someone else won; re-read and retry (or report a conflict)
```

- **Gain:** no locks held while the user or app thinks; great for low-contention data and for
  long "edit form" flows that span multiple HTTP requests (where you cannot hold a database lock).
- **Pay:** under high contention, many retries waste work. A hot row (one popular product during
  a flash sale) is better served by an atomic `UPDATE ... SET stock = stock - 2 WHERE stock >= 2`
  or by pessimistic locking.

| | Pessimistic | Optimistic |
|---|---|---|
| Assumes | Conflicts common | Conflicts rare |
| Mechanism | Lock rows before reading for update | Version check at write time |
| On conflict | Wait (or deadlock abort) | Retry |
| Spans multiple requests | No (locks must be short) | Yes |

HTTP has the same idea built in: `ETag` + `If-Match` headers (chapter 02).

### MVCC intuition

If readers took locks, a long report query would block every writer. **MVCC (multi-version
concurrency control)** avoids this by keeping multiple versions of a row:

```text
row id=5   version created by txn 100: stock=10   (visible to snapshots older than txn 205)
           version created by txn 205: stock=8    (visible to snapshots that see txn 205
                                                   as committed)
```

- Each transaction (or each statement, under read committed) reads from a **snapshot**: it sees
  only versions committed before the snapshot was taken.
- Writers create a new version instead of destroying the old one (PostgreSQL writes a new row
  version into the table; InnoDB updates the row in place but keeps the previous version in its
  undo log), so **readers never block writers and writers never block readers**. Writers still
  block other writers on the same row.
- Old versions must be cleaned up once no snapshot can see them: PostgreSQL's **VACUUM**
  removes dead row versions from the table; InnoDB keeps old versions in **undo logs** and a
  background **purge** removes them.
- Cost: long-running transactions keep old versions alive, causing table bloat (PostgreSQL) or
  undo growth (InnoDB). This is one reason not to leave transactions open.

## 05.9 Write-ahead log and durability

### The problem

Data pages live in memory (the buffer pool) and are written to disk later, because writing a
16 KB page to a random disk location on every tiny change would be slow. But if the machine
crashes, any change still only in memory is lost.

### The idea

Before modifying a data page, append a description of the change to a sequential file, the
**write-ahead log (WAL)** (InnoDB calls it the **redo log**). On `COMMIT`, the database
**fsyncs** the log (forces it to stable storage) and only then reports success.

```text
COMMIT arrives
   |
   v
1. append "txn 205: page 9133, slot 4: stock 10 -> 8; COMMIT" to WAL buffer
2. fsync WAL to disk           <-- the durability point; sequential write, fast
3. return "COMMIT OK" to client
   ...
later: background checkpoint writes the dirty data page 9133 to its real location

CRASH RECOVERY:
   read WAL from last checkpoint -> redo every committed change not yet in data pages
   -> undo changes of transactions that never committed
```

Why it is fast: the WAL is **append-only and sequential**, which disks (even SSDs) handle far
better than scattered random writes. Data pages are flushed lazily and in batches.

Extra details worth knowing:
- **Group commit:** many concurrent transactions' commit records are flushed in one fsync,
  amortizing its cost.
- **Durability knobs:** MySQL's `innodb_flush_log_at_trx_commit=1` (fsync every commit, fully
  durable) vs `2` or `0` (flush roughly once per second, faster, can lose up to about a second
  of commits on a crash). PostgreSQL's `synchronous_commit=off` is a similar trade.
- **The WAL is also the replication stream:** replicas replay the leader's log (chapter 07), and
  change-data-capture tools read it to publish changes to other systems (chapter 11).
- **Checkpoints** periodically flush dirty pages so recovery does not need to replay the whole
  log from the beginning of time.

> **Say it like this:** "Commit is durable once the write-ahead log record is fsynced; the data
> pages are written later. After a crash the database replays the log, so a sequential append is
> all you pay on the commit path."

## 05.10 Connection pooling

### The problem

Opening a database connection is expensive: TCP handshake, often TLS, authentication, and
server-side setup. PostgreSQL starts a separate operating-system process per connection, each
using several MB of memory. MySQL uses a thread per connection. A database that is happy with
100–300 active connections may struggle badly with 5,000.

Now picture 200 app servers, each opening 50 connections: 10,000 connections. The database
spends its time context switching and managing memory instead of running queries.

### The idea

A **connection pool** keeps a fixed set of open connections and lends them out:

```text
  request threads                pool (size 20)                database
  T1 --borrow--> [conn 1] ------------------------------------> |
  T2 --borrow--> [conn 2] ------------------------------------> |
  T3 --wait-----                                                 |
  ...            [conn 3..20 idle or busy]                       |
  T1 --return--> [conn 1] back to pool, ready for T3
```

- **In-app pool:** a library inside each app process (common examples: HikariCP for the JVM,
  the pool built into most database drivers).
- **External pooler / proxy:** a separate process between apps and database (e.g. PgBouncer
  for PostgreSQL, ProxySQL for MySQL) that multiplexes thousands of client connections onto a
  few hundred server connections. In **transaction pooling** mode a server connection is
  assigned only for the duration of a transaction; session-level features (session variables,
  some prepared-statement behavior, advisory locks held across transactions) need care in this
  mode.

Sizing: more connections is not more throughput. A database server only has so many CPU cores
and disks; beyond that, extra concurrent queries just queue inside it and contend for locks. A
common starting heuristic from the pooling community is roughly `2 × CPU cores` (plus a little
for disk waits) of active connections on the database side, then tune by measuring. Total
connections across all app instances must fit under the database's limit.

> **Common mistake:** raising the pool size when queries are slow. If the database is saturated,
> a bigger pool makes every query slower. Find the slow query or add capacity instead.

## 05.11 Worked example: an e-commerce schema

### Requirements (the queries drive the indexes)

1. Log in by email.
2. Show a product page; list products in a category, sorted by price.
3. Place an order with several items; decrement stock without overselling.
4. "My orders" page: a user's orders, newest first, paginated.
5. Order detail: items with product names.
6. Ops dashboard: pending orders older than 1 hour.

### Schema

```sql
CREATE TABLE users (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email       TEXT NOT NULL,
  name        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email));                 -- Q1

CREATE TABLE products (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  category_id  BIGINT NOT NULL,
  name         TEXT NOT NULL,
  price_cents  BIGINT NOT NULL CHECK (price_cents >= 0),
  stock        INT    NOT NULL CHECK (stock >= 0),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_products_category_price ON products (category_id, price_cents);  -- Q2

CREATE TABLE orders (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  status       TEXT   NOT NULL,
  total_cents  BIGINT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC, id DESC); -- Q4
CREATE INDEX ix_orders_pending_created ON orders (created_at)
  WHERE status = 'pending';                                                      -- Q6

CREATE TABLE order_items (
  order_id          BIGINT NOT NULL REFERENCES orders(id),
  product_id        BIGINT NOT NULL REFERENCES products(id),
  qty               INT    NOT NULL CHECK (qty > 0),
  unit_price_cents  BIGINT NOT NULL,
  PRIMARY KEY (order_id, product_id)                                             -- Q5
);
CREATE INDEX ix_order_items_product ON order_items (product_id);
```

### Why each index

| Index | Serves | Reasoning |
|---|---|---|
| `UNIQUE (LOWER(email))` | Q1 login | Enforces one account per email case-insensitively *and* makes the login lookup a single seek. The expression must match the query: `WHERE LOWER(email) = LOWER($1)` |
| `(category_id, price_cents)` | Q2 | Equality on category, then sorted by price: the planner reads one contiguous slice in order, no sort step, and `LIMIT 20` stops early |
| `(user_id, created_at DESC, id DESC)` | Q4 | Equality on user, then the order we display. `id` breaks ties so pagination is stable |
| Partial index on `created_at WHERE status='pending'` | Q6 | A **partial index** only contains rows matching its condition. Pending orders are a small fraction, so the index is tiny and cheap to maintain (PostgreSQL feature) |
| PK `(order_id, product_id)` | Q5 | All items of one order are contiguous (leftmost prefix = order_id). Also prevents duplicate lines for one product |
| `order_items (product_id)` | FK checks, "who bought X" | PostgreSQL does not auto-index foreign key columns; without this, deleting or updating a product scans `order_items`. (InnoDB creates an index for FKs automatically if none exists) |

Deliberately **not** indexed: `orders.status` alone (low selectivity, few values) and
`products.name` for search (a B+ tree cannot do "contains these words"; use a search engine,
chapter 09).

### Pagination: keyset, not OFFSET

```sql
-- OFFSET pagination: page 500 makes the database walk and discard 10,000 rows
SELECT id, total_cents, created_at FROM orders
WHERE user_id = 7 ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 10000;

-- Keyset (cursor) pagination: seek directly past the last row seen
SELECT id, total_cents, created_at FROM orders
WHERE user_id = 7
  AND (created_at, id) < ('2026-05-01 10:00:00+00', 98123)   -- last row of previous page
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Keyset pagination is a `lower_bound` into the index: cost is the same for page 1 and page 500.

### Placing an order safely (Q3)

```sql
BEGIN;
-- Decrement stock atomically; the WHERE clause refuses to oversell.
UPDATE products SET stock = stock - 2 WHERE id = 5 AND stock >= 2;   -- expect 1 row
UPDATE products SET stock = stock - 1 WHERE id = 9 AND stock >= 1;   -- expect 1 row
-- if any UPDATE affected 0 rows: ROLLBACK and tell the user "out of stock"

INSERT INTO orders (user_id, status, total_cents)
VALUES (7, 'pending', 2250) RETURNING id;
INSERT INTO order_items (order_id, product_id, qty, unit_price_cents)
VALUES (:order_id, 5, 2, 900), (:order_id, 9, 1, 450);
COMMIT;
```

Details that matter:
- Updating products in **ascending id order** in every transaction avoids deadlocks between two
  orders that share products.
- The guarded `UPDATE ... WHERE stock >= n` is atomic, so no lost update even at read committed,
  and the `CHECK (stock >= 0)` constraint is a second line of defense.
- Prices should be read from the database inside the transaction, never trusted from the
  client.

## 05.12 The N+1 query problem

### The problem

Show 50 orders with their user's name. Naive code, often generated by an **ORM**
(object-relational mapper, a library that maps rows to objects and writes SQL for you):

```text
orders = query("SELECT * FROM orders ORDER BY created_at DESC LIMIT 50")    -- 1 query
for order in orders:
    order.user = query("SELECT * FROM users WHERE id = ?", order.user_id)   -- 50 queries
```

That is **1 + N** queries. Each is fast (an index lookup, maybe 0.2 ms on the server), but each
is a network round trip, roughly 0.5–1 ms inside a data center. 51 round trips cost tens of
milliseconds of pure waiting; nested relations multiply it (orders → items → products).

### Fixes

```sql
-- 1. One join
SELECT o.*, u.name FROM orders o JOIN users u ON u.id = o.user_id
ORDER BY o.created_at DESC LIMIT 50;

-- 2. Batch load: 2 queries total, regardless of N
SELECT * FROM orders ORDER BY created_at DESC LIMIT 50;
SELECT id, name FROM users WHERE id IN (7, 12, 31, ...);   -- the distinct user_ids
```

ORMs call these "eager loading" or "prefetch". The batch approach is also the standard pattern
for GraphQL resolvers ("data loader", chapter 02).

> **Common mistake:** finding N+1 only in production. Log the query count per request in
> development and alert when one request issues more than a few dozen queries.

## 05.13 When SQL is the right choice

Default to a relational database unless you have a specific reason not to. The strong reasons
*for* SQL:

1. **Transactions across multiple rows and tables.** Money, inventory, bookings, anything where
   partial updates are bugs.
2. **Integrity enforced by the database:** unique, foreign key, check constraints. Bugs in one
   service cannot corrupt the data.
3. **Flexible queries.** You do not know every future question; SQL answers new ones with joins
   and aggregates without remodeling data.
4. **Mature tooling and knowledge:** backups, point-in-time recovery, replication, monitoring,
   and every engineer can read SQL.
5. **It scales further than people think.** A single well-indexed primary on modern hardware
   handles very roughly thousands to tens of thousands of writes per second and, with read
   replicas and caching, far more reads. Many large products ran on one primary for years.

When to look elsewhere (chapter 06): write volume or data size beyond what one primary can take
and you do not want to shard yourself; access patterns that are purely key-value at huge scale;
highly variable document shapes; graph traversals many hops deep; full-text search; analytics
over billions of rows (a columnar warehouse, chapter 09).

> **Say it like this:** "I'd start with Postgres: the data is relational, we need transactions
> for orders and stock, and one primary with read replicas covers our estimated load. If writes
> outgrow a single node, the next steps are sharding by user_id or a distributed SQL database."

## 05.14 Misconceptions

- **"Joins are slow."** Joins on indexed keys are fast. Joins over millions of unindexed rows
  are slow. The problem is missing indexes or huge intermediate results, not joins.
- **"More indexes = faster."** Faster reads for the queries they serve; slower writes always.
- **"Primary key = clustered" everywhere.** True in InnoDB, not in PostgreSQL (heap tables).
- **"Transactions make everything correct."** Only at sufficient isolation. Read committed
  allows lost updates and write skew.
- **"SELECT then UPDATE in a transaction is safe."** Not at read committed, and not even at
  InnoDB repeatable read; use an atomic UPDATE, FOR UPDATE, or a version check.
- **"SQL doesn't scale."** It scales vertically far, horizontally with replicas, and further with
  sharding or distributed SQL (chapters 06–07).

## 05.15 Recap

- A database gives you indexes, concurrency control, crash safety, transactions, and a query
  language, which you would otherwise rebuild badly.
- Normalize so each fact lives once; denormalize measured hot read paths and own the sync cost.
- Indexes are B+ trees of pages with high fanout, so depth is 3–4 even for hundreds of millions
  of rows; point lookup = descend, range = lower_bound + walk leaves.
- InnoDB clusters rows by PK and secondary indexes store the PK; PostgreSQL uses heap tables and
  row pointers.
- Composite indexes follow the leftmost-prefix rule; put equality columns first, range/sort last;
  covering indexes avoid touching the table.
- Indexes cost write throughput and storage; functions on columns, leading wildcards, type
  mismatches and low selectivity prevent their use. Verify with EXPLAIN.
- ACID: all-or-nothing, invariants hold, isolation (tunable), committed means durable via WAL
  fsync.
- Read committed allows non-repeatable reads, phantoms, lost updates and write skew; PostgreSQL
  RR is snapshot isolation (write skew possible); InnoDB RR does not detect lost updates;
  serializable prevents all but needs retries or causes blocking.
- Pessimistic = lock then act (FOR UPDATE); optimistic = version check and retry; MVCC lets
  readers and writers not block each other.
- Pool connections; size pools small; fix N+1 with joins or batch loads; use keyset pagination.

## 05.16 Check yourself

1. A table has 200 million rows. Roughly how many B+ tree levels does a lookup traverse, and why
   is it usually only 0–1 disk reads?

<details><summary>Answer</summary>

About 3–4 levels, because fanout is in the hundreds: `log_500(2×10^8) ≈ 3.1`. The root and
internal levels are small and hot, so they are almost always in the buffer pool; usually only
the leaf (and, for a secondary index in InnoDB or a heap fetch in PostgreSQL, the row page)
might need a disk read.

</details>

2. You have an index on `(country, city, created_at)`. Which of these can seek with it:
   (a) `city = 'Pune'`, (b) `country = 'IN' AND created_at > X`, (c) `country = 'IN' AND city =
   'Pune' ORDER BY created_at`?

<details><summary>Answer</summary>

(a) No, city is not the leftmost column. (b) Partially: it seeks on `country = 'IN'` and then
filters `created_at` while scanning, because `city` is skipped. (c) Yes, fully: two equality
columns form the prefix and the rows come out already sorted by `created_at`, so no sort step.

</details>

3. Two transactions at PostgreSQL Repeatable Read each check "at least 2 doctors on call" and set
   a different doctor off call. What happens and how do you prevent it?

<details><summary>Answer</summary>

Both commit and zero doctors are on call: write skew. Snapshot isolation does not prevent it
because they write different rows. Fixes: run at SERIALIZABLE (SSI aborts one; retry it), or
`SELECT ... FOR UPDATE` the on-call rows so the transactions serialize, or restructure the data
so the invariant is enforced by a constraint.

</details>

4. Why is `UPDATE products SET stock = stock - 1 WHERE id = 5 AND stock >= 1` safe from lost
   updates at read committed, while reading stock into the app and writing `stock = 9` is not?

<details><summary>Answer</summary>

The single UPDATE locks the row and computes from the latest committed value; a concurrent
UPDATE waits and then re-evaluates against the new value. The app-side version reads a value,
releases nothing, and later writes an absolute number computed from a value that may already be
stale, overwriting the other transaction's change.

</details>

5. What exactly makes a COMMIT durable, and why does that not require writing data pages?

<details><summary>Answer</summary>

The commit's write-ahead log records are fsynced to stable storage before success is returned.
After a crash, recovery replays the log to redo committed changes that had not reached the data
pages. The log append is sequential and can be batched (group commit), so it is much cheaper
than writing scattered data pages synchronously.

</details>

6. Your app is slow, the database CPU is at 100%, and someone proposes raising the pool size from
   20 to 200 per instance. What do you say?

<details><summary>Answer</summary>

That makes it worse: more concurrent queries on a saturated database means more contention and
context switching, and every query gets slower. Find the expensive queries (EXPLAIN, slow query
log), add the missing index or fix N+1 patterns, cache hot reads, or add read replicas.

</details>

7. When would you choose optimistic locking over `SELECT ... FOR UPDATE`?

<details><summary>Answer</summary>

When conflicts are rare and especially when the read and the write happen in different requests
(an edit form opened now and saved minutes later), where you cannot hold a database lock.
Pessimistic locking is better for short, high-contention transactions where retries would waste
lots of work.

</details>

8. Why is `OFFSET 100000` pagination slow, and what replaces it?

<details><summary>Answer</summary>

The database must produce and discard the first 100,000 rows of the ordered result, so cost
grows with the page number. Keyset pagination remembers the sort key of the last row seen and
asks for rows after it (`WHERE (created_at, id) < (...)`), which is a direct index seek with
constant cost per page.

</details>
