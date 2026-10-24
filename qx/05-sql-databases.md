# 05 — Relational databases: quick notes

A relational database (MySQL, PostgreSQL) stores data in tables and keeps it **correct** even
when thousands of people write at once. It's the default choice for most apps.

## 1. Tables and keys

- A **table** is like a spreadsheet: `orders` has one **row** per order and one **column** per
  field (order_id, user_id, amount, status).
- **Primary key:** the unique id of each row. *e.g.* `order_id = 90311`.
- **Foreign key:** a column pointing to another table's row. *e.g.* `orders.user_id = u_1842`
  points to Asha in `users`.
- **Join:** combine tables in one query. *e.g.* "Asha's orders with restaurant names" =
  `orders JOIN restaurants`.

## 2. Normalize vs denormalize

- **Normalize:** store each fact **once**. The restaurant's name lives only in `restaurants`,
  not copied into every order. If Spice Route renames itself, you change one row.
- **Denormalize:** copy data on purpose so reads skip a join. *e.g.* keep `restaurant_name`
  inside `orders` so "My orders" loads with one query. The cost: copies can go out of sync.
- Rule: normalize by default, and denormalize a hot read path when it's measured to be slow.

## 3. Indexes: how lookups become fast

Without an index, `WHERE user_id = 'u_1842'` reads **every row** (100 M rows means slow). An
**index** is a sorted structure (a **B+ tree**) that finds rows in about 3 steps.

```text
root  ->  "ids 1–200,000 are under child 0"
level 2 -> "ids 90,001–90,400 are in leaf 225"
leaf  ->  90311 found -> row location          (like floor -> shelf -> book)
```

- **Clustered index:** the rows themselves are stored in primary-key order (one per table).
- **Secondary index:** an extra index (on user_id, say) that points back to the row, which
  costs one extra lookup per row.
- **Composite index** `(user_id, created_at)` is sorted by user first, then by time. It helps
  `WHERE user_id = ?` and `WHERE user_id = ? ORDER BY created_at`, but **not**
  `WHERE created_at = ?` alone (the **leftmost-prefix rule**, like a phone book sorted by
  surname, then first name).
- **Covering index:** the index holds every column the query needs, so the table is never read.
- **Cost:** every index makes **every write** slower, because each one must be updated.

## 4. Transactions and ACID

A **transaction** is a group of steps that happen **all together or not at all**.

*e.g.* Asha pays ₹450: (1) wallet −450, (2) order status → PAID. If the server crashes
between the two, neither change is kept.

| Letter | Means | QuickBite example |
|---|---|---|
| **A**tomic | All or nothing | Never "money gone but order unpaid" |
| **C**onsistent | Rules always hold | Wallet balance can never go below 0 |
| **I**solated | Parallel transactions don't mess each other up | Two orders for the last biryani don't both succeed |
| **D**urable | Once committed, it survives a crash | "Order placed" stays placed after a power cut |

## 5. Isolation levels: what can go wrong in parallel

When two transactions run at the same time, these bugs can happen:

| Problem | QuickBite example |
|---|---|
| **Dirty read** | You read a price someone changed but then cancelled (never really existed) |
| **Non-repeatable read** | You read status = PLACED, read again in the same transaction, and get ACCEPTED |
| **Phantom** | You count Asha's orders today (3), count again, and get 4 (a new row appeared) |
| **Lost update** | 10 biryanis left; two orders both read 10 and both write 9. Two sold, count says 9 |
| **Write skew** | Rule "≥ 1 rider on shift"; two riders both see 2 on shift and both go on break, leaving 0 |

**Isolation levels** go from weak and fast to strict and slower: read uncommitted → **read
committed** (the common default) → **repeatable read** → **serializable** (behaves as if one at a
time). A stricter level prevents more of these bugs.

## 6. Locking: stopping the lost update

- **Pessimistic lock:** lock the row first, so others wait.
  `SELECT portions ... FOR UPDATE` → read 10 → write 9 → commit. The second order waits, then
  reads 9 and writes 8. Use it when conflicts are common.
- **Optimistic lock:** don't lock; check that nobody changed the row in between.
  `UPDATE ... SET price = 200, version = 8 WHERE id = 5 AND version = 7`. If 0 rows changed,
  someone else got there first, so re-read and retry. Use it when conflicts are rare.
- **MVCC:** the database keeps old versions of rows, so **readers never block writers**. Each
  reader sees a consistent snapshot.

## 7. Durability, connections, N+1

- **Write-ahead log (WAL):** before changing data, the database appends the change to a log
  file on disk. After a crash it replays the log. That's how "D" works.
- **Connection pool:** opening a DB connection is slow, so each app keeps ~10–20 open and
  reuses them. *e.g.* 200 pods × 50 connections = 10,000 connections would overload the DB.
- **N+1 problem:** 1 query for Asha's 50 orders, then 50 more queries for each restaurant
  name, means 51 round trips. Fix: one `JOIN`, or one `WHERE id IN (...)`.

## 8. When to choose SQL

Pick SQL when you need **transactions, joins and strict correctness** (orders, payments, users)
and writes fit on one machine (thousands/s). QuickBite orders at ~120 writes/s at peak are an
easy fit. Rider GPS pings at ~25,000 writes/s, with no joins needed, are a poor fit (ch 06).

## Remember these 5

1. An index is a sorted B+ tree: about 3 steps to find a row, but every index slows writes.
2. Composite index: the leftmost column must be in the query.
3. A transaction is all or nothing; ACID is the guarantee.
4. Lost update: fix with `FOR UPDATE` (pessimistic) or a version column (optimistic).
5. SQL is the default; leave it only for a clear reason.
