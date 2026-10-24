# 26 — Concurrency for LLD

*Why it matters:* most LLD problems hide "what if two users do this at once?", and you need the Java tools, the failure modes, and the words to say where locking goes.

## Why it shows up, and the vocabulary

**How it works** · One thread per request, all sharing objects: anything **shared and mutable**
is a bug risk (seats, spots, tokens, cache lists, ids, stock). Nearly all bugs are **check-then-act**
or **read-modify-write** with another thread changing state between the steps.

**Example** · QuickBite: `r_77` ("Spice Route") has 1 biryani left; two orders arrive together:

```text
T1 o_90311: check stock 1 >= 1 -> yes      T2 o_90312: check stock 1 >= 1 -> yes
T1: stock = 0, confirm                     T2: stock = -1, confirm   -> oversold
```

Terms: **critical section** (code two threads must not run at once), **atomicity** (never seen
half-done), **thread-safe** (correct without caller locking), **visibility** (unsynchronized
threads may read a stale value forever), **ordering** (compilers/CPUs reorder). **JMM happens-
before**: unlock -> later lock; `volatile` write -> later read; `start()` -> thread -> `join()`.

## Race condition: the lost update

**How it works** · `count++` is read, add, write; a **race condition** depends on timing.

**Example** · QuickBite's orders-today `count = 41`; two orders finish at once:

```text
T1: read 41                    T2: read 41
T1: add -> 42                  T2: add -> 42
T1: write 42                   T2: write 42
count = 42   two increments, one effect: a lost update
synchronized: T2 blocks on the monitor until T1 releases, then reads 42, writes 43
```

At scale: 4 threads x 1M increments gave roughly 3.57M (varies); `synchronized`, `AtomicInteger`,
`LongAdder` all give 4,000,000. Unsynchronized non-volatile `long`/`double` reads may even tear.

## Locks: synchronized, ReentrantLock, ReadWriteLock

**How it works** · Every object has an intrinsic lock (**monitor**): a `synchronized` method locks
`this`, `static synchronized` the Class, `synchronized (obj) {}` locks `obj`. It gives **mutual
exclusion**, **visibility** (the next acquirer sees writes made before release), **reentrancy**,
auto-release on exceptions; no timeout, interrupt or fairness; never do slow I/O inside.
**ReentrantLock** adds `tryLock()`/`tryLock(time, unit)`, `lockInterruptibly()`, fairness (`new
ReentrantLock(true)`, slower), `Condition`s, lock/unlock across methods; release is manual
(**common mistake**: no `finally { unlock(); }` -> one exception, locked forever).

**Example** · `Inventory.reserve(qty)` = `tryLock(100 ms)`, check `stock >= qty`, subtract,
`unlock()` in `finally`. Stock 3; T1 `reserve(2)` and T2 `reserve(2)` at once:

```text
t=0ms  T1 tryLock -> acquired             T2 tryLock(100 ms) -> waiting
t=1ms  T1 stock 3 >= 2 -> stock = 1, unlock in finally
t=1ms                                     T2 acquires; stock 1 < 2 -> false
if T1 held it 150 ms instead: T2 gives up at t=100ms -> false, never stuck forever
```

**ReadWriteLock**: many readers **or** one writer (config read constantly, written hourly: R1, R2
share it, writer W waits for both). Costlier per acquisition, helps only when reads dominate and do
real work; can delay writers; useless when reads mutate (LRU). Often simpler: CHM or `volatile` map.

## Atomics and the CAS loop

**How it works** · **Compare-and-set (CAS)** is a CPU instruction: "set to `new` only if it still
holds `expected`; report success", atomic without a lock. `AtomicInteger`/`AtomicLong` (counters,
ids), `LongAdder` (hot counters; `sum()` not a snapshot), `AtomicReference` build on it. Custom
updates use a **CAS loop**, e.g. `updateMax`: `while (true) { int cur = max.get(); if (candidate
<= cur) return; if (max.compareAndSet(cur, candidate)) return; }`.

**Example** · `max = 10`; T1 calls `updateMax(15)`, T2 `updateMax(12)`:

```text
T1: cur = 10                      T2: cur = 10
                                  T2: CAS(10 -> 12) succeeds, max = 12
T1: CAS(10 -> 15) fails (max is 12 now) -> retry
T1: cur = 12; 15 > 12; CAS(12 -> 15) succeeds, max = 15   (no lock, nothing lost)
```

Several fields: CAS an `AtomicReference` to an **immutable** `State(balance, version)` built fresh
each try. Costs: retries burn CPU under contention; one reference only; **ABA** (A -> B -> A fools a
CAS) is avoided by fresh immutable objects or `AtomicStampedReference`.

## ConcurrentHashMap compound operations

**How it works** · **ConcurrentHashMap** (CHM) has no global lock (reads mostly lock-free, writes
lock a small part). Each call is atomic, but `get` then `put` is still a race. Use per-key
compound ops: `putIfAbsent`; `computeIfAbsent(k, f)` (`f` at most once per key); `compute(k, f)`
(replace with `f(k, old)`); `merge(k, v, f)` (insert or `f(old, v)`).

**Example** · page-view counts, `"/menu/r_77" -> 7`, two views at once:

```text
get + put:  T1 get 7     T2 get 7     T1 put 8     T2 put 8     -> 8 (one view lost)
merge:      T1 merge(k, 1, Integer::sum) locks the bin, 7 -> 8
            T2 merge waits for that bin, then 8 -> 9            -> 9 (correct)
```

In `byUser.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(page)` the `.add` is
outside the map's protection (list must be thread-safe). Keep functions short, never touch the same
map inside. No `null` keys/values; `size()` and iteration are **weakly consistent**.

## volatile: visibility, not atomicity

**How it works** · `volatile` gives **visibility** and **ordering** (writes before a volatile write
are seen by a reader that sees it; why double-checked locking works), **not** atomicity: fine for
one writer of a flag/reference; updates from the current value need an atomic or a lock.

**Example** · a worker runs `while (running) loops++;`; main calls `stop()` at t=1 s:

```text
plain boolean:    main writes false; the JIT kept the field in a register -> loops forever
volatile boolean: each iteration reads memory -> the worker sees false and exits at ~1 s
volatile int count; count++ by T1 and T2 at 41 -> still 42 (same trace as the lost update)
```

## Deadlock: four conditions and lock ordering

**How it works** · Threads wait for each other forever; it needs all four **Coffman conditions**.
Break one: **mutual exclusion** (lock-free), **hold and wait** (take all at once / one coarser
lock), **no preemption** (`tryLock` timeout, release, retry), **circular wait** (**global lock
ordering**: `transferOrdered` locks the lower account id first).

**Example** · accounts a (id 1) and b (id 2), 1000 each; T1 sends a -> b 100, T2 b -> a 200:

```text
unordered (lock from, then to):
T1: lock(a) ok, sleep 50 ms           T2: lock(b) ok, sleep 50 ms
T1: lock(b) ... waits for T2          T2: lock(a) ... waits for T1   -> cycle, forever
ordered (lower id first):
T1: lock(a) ok                        T2: lock(a) ... waits (b stays free)
T1: lock(b), a=900 b=1100, unlock     T2: lock(a), lock(b), a=1100 b=900, unlock
```

DSA bridge: deadlock = a cycle in the **waits-for graph**; a global order points every edge
"upward" (a topological order). Demo: `ThreadMXBean` finds 2 stuck threads (`jstack` in prod).
**Livelock**: lockstep back-off and retry (randomize). **Starvation**: never wins (fair locks help).

## Executors, CompletableFuture, virtual threads

> **Black box:** a **thread pool** (`ExecutorService`) = bounded workers + task queue; `submit`
> gives a **`Future<T>`** (`get()` blocks); e.g. 4 workers, 8 jobs `n * n` -> 204. **Use
> when:** ~cores for CPU, more for I/O. **Cost:** `shutdown()`; `newFixedThreadPool` queue is
> unbounded: bound it and reject.

> **Black box:** **`CompletableFuture`** composes async calls (`supplyAsync`, `thenApply`,
> `thenCompose`, `thenCombine`, `allOf`, `exceptionally`, `orTimeout`, `join`). **Use when:**
> two 100 ms calls in parallel take ~100 ms, not 200. **Cost:** pass your own executor (the
> common `ForkJoinPool` is sized for CPU; blocking I/O starves it).

> **Black box:** a **virtual thread** (Java 21) unmounts from its carrier while blocked: 10,000
> tasks sleeping 200 ms take roughly 200–300 ms (4 platform threads: ~500 s). **Use when:**
> blocking I/O, one per task, never pooled. **Cost:** no CPU speedup, races remain; Java 21 `synchronized`
> **pins** the carrier (prefer `ReentrantLock`); cap downstreams with a `Semaphore`.

## Producer-consumer with BlockingQueue

**How it works** · A **bounded `BlockingQueue`**: `put()` blocks when full (**back-pressure**),
`take()` when empty; after producers finish, one **poison pill** per consumer (`if (job == POISON)
return;`). An in-process message queue (so is an executor); when full: block, drop, or reject.

**Example** · `ArrayBlockingQueue<>(5)`, producers P0 and P1, consumers C0–C2, 40 jobs:

```text
P0 put j1..j5   queue [j1 j2 j3 j4 j5] full;  P0 put j6 -> blocks (back-pressure)
C0 take -> j1   room again -> P0 wakes, j6 enqueued
... main joins P0, P1, puts POISON x3; C0, C1, C2 each take one -> return
"processed 40 jobs, distinct ids 40"
```

## Thread-safe singleton

**How it works** · Lazy `if (instance == null)` can create two. Safe: `synchronized getInstance()`,
double-checked locking with `volatile`, **holder idiom**, **enum**; its state still needs atomics.

**Example** · `IdGenerator.getInstance()` called by T1 and T2 on first use:

```text
lazy:   T1 sees null, creates X@1         T2 sees null, creates X@2  -> duplicate ids
holder: T1 triggers Holder class init     T2 waits on the JVM's class-init lock
        both get X@1; its AtomicLong hands out 1 and 2 (never 1 and 1)
```

## Thread-safe rate limiter (token bucket)

**How it works** · Each user bursts up to `capacity`, refilled at `r`/s computed lazily from
elapsed time; a request takes one token or is rejected. `allow(key)` = `buckets.computeIfAbsent(key, k ->
new TokenBucket(...)).tryAcquire(now)`; `synchronized tryAcquire`: `tokens = Math.min(capacity,
tokens + (now - lastRefill) * refillPerNano); lastRefill = now;` then take one if `tokens >= 1`.

**Example** · OTP limiter for `u_1842`, capacity 10, refill 5/s: both races, then the real test:

```text
plain get/put:     T1 get -> null   T2 get -> null   both put a full bucket -> 20 OTPs
unsynchronized:    T1 reads 1.0 token   T2 reads 1.0   both -> 0, both allowed
test (fake clock): t=0     50 concurrent -> 10 allowed   (bucket starts full)
                   t=1 s   20 concurrent -> 5 allowed    (0 + 1 s x 5/s)
                   t=61 s  50 -> 10 (min(10, 0 + 60 x 5));  user-2: 50 -> 10 (own bucket)
```

Lock **per bucket**: users never contend. Alternatives: CAS over immutable `(tokens, lastRefill)`;
evict idle buckets. Across servers it limits per server only (global: KV store + script).

## Thread-safe LRU cache

**How it works** · **The trap:** LRU `get` **mutates**, so a read-write lock lets readers corrupt
the list, and CHM alone fails (list and map change together). Correct: one lock around every op
on an access-order `LinkedHashMap` (demo: 1.6M ops, 8 threads).

**Example** · `head<->d<->c<->b<->a<->tail`; `get(a)`, `get(c)` share the read lock (prev omitted):

```text
T1: a.next = head.next (d)          T2: c.next = head.next (d)
T1: head.next = a
                                    T2: head.next = c          (overwrites T1)
head -> c -> d -> b -> tail: a is in the map but in no list -> never evicted, size drifts
```

To scale: **sharding** (N LRUs by `hash(key) % N`), **buffered recency** (apply recorded accesses
in batches), **approximate LRU** (sample keys, evict the oldest). **Cache stampede**: 100 threads
miss `menu:r_77` -> 100 DB loads; a `CompletableFuture<V>` per key via `computeIfAbsent` -> 1 load.

## Optimistic vs pessimistic locking

**How it works** · **Pessimistic**: lock first, others wait (`synchronized`/`ReentrantLock`; DB:
`SELECT ... FOR UPDATE` then `UPDATE`). **Optimistic**: read freely, at write check nothing
changed, else retry (CAS on immutable state + version; DB: version column, 0 rows = conflict).

**Example** · account 42: balance 500, version 7; T1 debits 100, T2 debits 50:

```text
optimistic:  T1 read 500 v7                 T2 read 500 v7
  T1: UPDATE SET balance=400, version=8 WHERE id=42 AND version=7   -> 1 row
  T2: UPDATE SET balance=450, version=8 WHERE id=42 AND version=7   -> 0 rows: conflict
  T2: re-read 400 v8 -> UPDATE balance=350, version=9 WHERE version=8 -> 1 row
pessimistic: T1 FOR UPDATE, 400, COMMIT; T2's FOR UPDATE waits, then 400 -> 350
```

Object-level demo: 1000 concurrent deposits of 10 -> balance 10000, version 1000, ~98 retries.
Optimistic for low contention and short work; pessimistic for hot items (popular seat, flash sale)
or side-effecting work between read and write; the DB whenever state spans servers (**common
mistake**: `synchronized` booking on 3 servers still double-sells; each JVM has its own lock).

## How to say where you'd add locking

**How it works** · (1) **Name the shared mutable state**, (2) the **dangerous sequence**, (3) the
**smallest correct protection**, (4) the **cost and next step**. Picks: counter/id -> `AtomicLong`
(`LongAdder` hot); per-key map -> CHM `compute`/`merge`; several fields -> smallest lock;
read-mostly config -> `volatile` immutable snapshot; hand-off -> bounded queue; stop flag -> `volatile`; two
locks -> global order or `tryLock`; across servers -> DB.

**Example** · QuickBite rider assignment: (1) "rider status, e.g. `rd_5521` FREE"; (2) "find a
FREE rider, then assign: `o_90311` and `o_90312` both pick `rd_5521`"; (3) "`order-service` has
many servers, so `UPDATE riders SET status='ASSIGNED', order_id=? WHERE id='rd_5521' AND
status='FREE'`; 0 rows -> next rider"; (4) "cost: a retry under contention".

## In one minute

- Bugs = shared mutable state + check-then-act or read-modify-write; `count++` loses updates.
- `synchronized` for exclusion + visibility; `ReentrantLock` for `tryLock`, unlock in `finally`.
- CAS loops: lock-free, retry under contention. CHM `compute`/`merge`: atomic per key. `volatile`
  is visibility, never atomicity.
- Deadlock needs all four Coffman conditions; global lock ordering kills circular wait.
- Bounded pools/queues give back-pressure; token bucket = atomic refill-and-take per bucket; a
  Java lock never spans servers.

## Go deeper

Full chapter 26: sections 26.7 (atomics and CAS), 26.10 (deadlock), 26.16 (token bucket), 26.18 (optimistic vs pessimistic); demos `LostUpdateDemo`, `LocksDemo`, `DeadlockDemo`, `ExecutorsDemo`, `ProducerConsumer`, `TokenBucketRateLimiter`, `ThreadSafeLru` in `code/`.
