# 26 — Concurrency for LLD

Most LLD problems hide one question: "what happens when two users do this at the same time?" Two
people booking the last seat, two gates handing out the same parking spot, two requests both
reading a rate limit of "one left". This chapter gives you the Java tools to answer that question
correctly, the failure modes to watch for, and the words to explain where you would add locking
and why.

Code: `LostUpdateDemo.java`, `LocksDemo.java`, `DeadlockDemo.java`, `ExecutorsDemo.java`,
`ProducerConsumer.java`, `TokenBucketRateLimiter.java`, `ThreadSafeLru.java`, all in `code/`.

## 26.1 Why concurrency shows up in LLD

A backend service handles many requests at once, usually one thread (or task) per request, all
sharing the same in-memory objects. Any object that is shared and mutable is a potential bug.

| LLD problem | Shared mutable state | The race |
|---|---|---|
| Movie / seat booking | seat status per show | two users hold the same seat |
| Parking lot | free spots | two gates assign one spot |
| Rate limiter | tokens per user | two requests both take "the last token" |
| Cache | map + recency list | concurrent `get`/`put` corrupt the list |
| ID generator | the counter | two callers get the same id |
| Inventory / wallet | stock count, balance | two purchases both pass the "enough left?" check |

Almost all of these are the same bug shape: **check-then-act** (check a condition, then act on
it) or **read-modify-write** (read a value, compute, write back), where another thread changes
the state between the two steps.

> **Say it like this:** "The shared state here is the seat map. The dangerous sequence is check
> availability then mark held; I'll make that one atomic step."

## 26.2 The vocabulary in two minutes

- A **thread** is an independent flow of execution inside a process. Threads of one process share
  the same heap memory, which is both why they are useful and why they are dangerous.
- A **critical section** is a piece of code that touches shared state and must not be run by two
  threads at the same time.
- **Atomicity**: an operation is atomic if other threads can only see it as not started or fully
  done, never half-done.
- **Visibility**: whether a write by one thread is guaranteed to be seen by another. Without
  synchronization, a thread may keep reading a stale value from a CPU cache or a register
  forever.
- **Ordering**: compilers and CPUs reorder instructions for speed. Without synchronization,
  another thread may observe writes in a different order than the program text.
- The **Java Memory Model (JMM)** defines **happens-before** rules: if action A happens-before
  B, then B sees A's effects. Unlocking a lock happens-before a later lock of the same lock;
  writing a `volatile` field happens-before a later read of it; `Thread.start()` happens-before
  the started thread's actions; a thread's actions happen-before another thread returns from
  `join()` on it.
- **Thread-safe**: a class is thread-safe if it behaves correctly when used from many threads
  without extra synchronization by callers.

## 26.3 Race conditions: the lost update

**Problem.** `count++` looks like one operation but is three: read `count`, add one, write
`count`. Two threads can interleave:

```text
   count = 41
   Thread A: read 41                         Thread B: read 41
   Thread A: compute 42                      Thread B: compute 42
   Thread A: write 42                        Thread B: write 42
   count = 42      <-- two increments, one effect: a "lost update"
```

The demo runs four threads, each incrementing a million times:

```java
    // Broken: count++ is read-modify-write, three steps, not one.
    static class UnsafeCounter {
        private int count = 0;
        void increment() { count++; }
        int get() { return count; }
    }

    // Fix 1: a lock makes read-modify-write one indivisible step.
    static class SyncCounter {
        private int count = 0;
        synchronized void increment() { count++; }
        synchronized int get() { return count; }
    }
```

Typical output (the unsafe number changes every run, and is almost never correct):

```text
unsafe       : 3,565,695 (expected 4,000,000, lost 434,305)
synchronized : 4,000,000
AtomicInteger: 4,000,000
LongAdder    : 4,000,000
```

A **race condition** is a bug whose outcome depends on the timing of threads. They are nasty
because they pass tests on a quiet laptop and fail under production load.

> **Common mistake:** "It's just a counter, an off-by-a-few doesn't matter." The same pattern on
> a balance or a seat count is money or a double-sold seat. And reading a plain `long` field
> written by another thread without synchronization is not even guaranteed to be untorn: the JMM
> allows non-volatile `long` and `double` writes to be split into two 32-bit halves.

## 26.4 synchronized

Every Java object has an intrinsic lock (a **monitor**). `synchronized` acquires it:

```java
synchronized void increment() { count++; }          // locks "this"
static synchronized void f() { ... }                // locks the Class object
synchronized (lockObject) { ... }                   // locks lockObject for this block
```

(The first line is from `LostUpdateDemo`; the other two show the syntax.)

What it gives you:

1. **Mutual exclusion**: only one thread at a time runs code synchronized on the same object.
2. **Visibility**: everything a thread wrote before releasing the lock is visible to the next
   thread that acquires it.
3. **Reentrancy**: a thread already holding the lock can enter another `synchronized` method on
   the same object without deadlocking itself.

The lock is released automatically when the block exits, even by an exception.

**Trade-off.** Simple and hard to misuse. But: no timeout (a thread waits forever), no way to
interrupt a waiting thread, no fairness, and a coarse lock (on the whole object) serializes
everything. Keep synchronized blocks short and never do slow I/O (network, payment calls) while
holding a lock.

## 26.5 ReentrantLock

`java.util.concurrent.locks.ReentrantLock` is an explicit lock object with more control:

```java
class Inventory {
    private final ReentrantLock lock = new ReentrantLock();
    private int stock;
    Inventory(int stock) { this.stock = stock; }

    boolean reserve(int qty) throws InterruptedException {
        if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) return false;   // bounded wait
        try {
            if (stock < qty) return false;
            stock -= qty;
            return true;
        } finally {
            lock.unlock();                     // always in finally
        }
    }
```

| Feature | `synchronized` | `ReentrantLock` |
|---|---|---|
| Release | automatic | manual, must be in `finally` |
| Try without blocking / with timeout | no | `tryLock()`, `tryLock(time, unit)` |
| Interruptible wait | no | `lockInterruptibly()` |
| Fairness option | no | `new ReentrantLock(true)` (longest waiter goes first; lower throughput) |
| Multiple wait conditions | one (`wait`/`notify`) | many `Condition`s via `newCondition()` |
| Lock across methods (lock in one, unlock in another) | no (block-scoped) | yes (careful) |

**When to use it.** When you need `tryLock` (to avoid deadlock or give up under contention), a
timeout, or several conditions. Otherwise `synchronized` is simpler.

> **Common mistake:** Calling `lock.lock()` and then doing work without `try { } finally {
> lock.unlock(); }`. One exception and the lock is held forever; every other thread blocks.

## 26.6 ReadWriteLock

**Problem.** A configuration map is read thousands of times per second and updated once an hour.
A plain lock makes readers wait for each other for no reason.

**Idea.** A **read-write lock** allows many concurrent readers **or** one writer:

```java
class ConfigStore {
    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, String> values = new HashMap<>();

    String get(String key) {
        rw.readLock().lock();
        try { return values.get(key); } finally { rw.readLock().unlock(); }
    }

    void put(String key, String value) {
        rw.writeLock().lock();
        try { values.put(key, value); } finally { rw.writeLock().unlock(); }
    }
}
```

**Trade-off.** A read-write lock costs more per acquisition than a plain lock, so it only helps
when reads dominate **and** the read work under the lock is non-trivial. Writers can be delayed by
a steady stream of readers. And it is useless when "reads" mutate state, which is exactly the LRU
case in 26.17. For a read-mostly map, `ConcurrentHashMap` (26.8) or swapping an immutable map
through a `volatile` reference is often simpler and faster.

## 26.7 Atomic classes and compare-and-set

**Compare-and-set (CAS)** is a CPU instruction: "set this memory location to `new` only if it
currently holds `expected`; tell me whether it worked". It is atomic without a lock. The
`java.util.concurrent.atomic` classes (`AtomicInteger`, `AtomicLong`, `AtomicReference`,
`LongAdder`) are built on it.

Simple operations are one call: `counter.incrementAndGet()`, `id.getAndIncrement()`. For anything
custom, write a **CAS loop**: read, compute, try to swap, retry if another thread got there first.

```java
    // Classic CAS loop: read, compute, try to swap; if someone beat us, retry.
    static void updateMax(AtomicInteger max, int candidate) {
        while (true) {
            int cur = max.get();
            if (candidate <= cur) return;
            if (max.compareAndSet(cur, candidate)) return;
        }
    }
```

The same loop over an `AtomicReference` to an **immutable** state object updates several fields
atomically, which is optimistic concurrency at the object level (26.18):

```java
    void deposit(long amount) {
        while (true) {
            State cur = state.get();                                    // read version
            State next = new State(cur.balance() + amount, cur.version() + 1);
            if (state.compareAndSet(cur, next)) return;                 // commit if unchanged
            retries.incrementAndGet();                                  // conflict: retry
        }
    }
```

| Tool | Best for | Note |
|---|---|---|
| `AtomicInteger`/`AtomicLong` | counters, id generators, single-value flags | lock-free, cheap when contention is moderate |
| `LongAdder` | very hot counters (metrics) | spreads increments over cells; `sum()` is not an atomic snapshot |
| `AtomicReference<ImmutableState>` | several fields that must change together | retry loop; keep the compute step cheap and side-effect free |

**Trade-off.** Under heavy contention CAS loops retry a lot and burn CPU, where a lock would let
threads sleep. CAS also only protects **one** reference; two separate atomics updated one after
the other are not atomic together. A subtle issue, the **ABA problem**: a value changes A → B → A
and a CAS expecting A succeeds although things changed in between. With immutable state objects
compared by reference (as above) a new object is created each time, so the same reference does
not come back while it is still in use; `AtomicStampedReference` adds a version stamp when it
matters.

## 26.8 ConcurrentHashMap

`ConcurrentHashMap` (CHM) is a thread-safe hash map that allows many concurrent readers and
writers without one global lock (reads are mostly lock-free; writes lock only a small part of the
table).

**Problem it solves beyond "thread-safe map".** Even with a thread-safe map, this is still a race:

```java
// Sketch of the bug (not in the demo): check-then-act across two calls
Integer old = map.get(page);
map.put(page, old == null ? 1 : old + 1);    // another thread may have updated in between
```

Each call is atomic; the pair is not. CHM offers atomic **compound operations** that do the whole
read-modify-write for one key:

```java
                counts.merge(page, 1, Integer::sum);       // atomic increment per key
                byUser.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(page);
```

| Method | Atomic meaning |
|---|---|
| `putIfAbsent(k, v)` | insert only if absent |
| `computeIfAbsent(k, f)` | if absent, compute with `f` and insert; `f` runs at most once per key |
| `compute(k, f)` | replace the value with `f(k, old)` |
| `merge(k, v, f)` | insert `v` if absent, else replace with `f(old, v)` |

Note in the snippet above: `computeIfAbsent` atomically creates the per-user list, but the
`.add(page)` after it runs outside the map's protection, so the list itself must be thread-safe
(`CopyOnWriteArrayList` here).

Rules:

- The function passed to `compute*` and `merge` runs while part of the map is locked: keep it
  short, and never modify the same map from inside it.
- CHM does not allow `null` keys or values (so `get` returning `null` always means "absent").
- `size()` and iteration are **weakly consistent**: they reflect some state during the call, not
  a frozen snapshot.

## 26.9 volatile: visibility, not atomicity

**Problem.** One thread sets `running = false`; the worker thread looping on `while (running)`
may never see it, because without synchronization the JIT compiler is allowed to read the field
once and keep it in a register.

```java
class Worker implements Runnable {
    private volatile boolean running = true;   // without volatile the loop may never see false
    private long loops = 0;

    public void run() {
        while (running) loops++;
    }

    void stop() { running = false; }
    long loops() { return loops; }
}
```

A `volatile` field guarantees:

- **Visibility**: every read sees the latest write by any thread.
- **Ordering**: writes before a volatile write cannot be reordered after it, so a reader that sees
  the volatile write also sees everything written before it (this is what makes double-checked
  locking work, chapter 23).

It does **not** make compound actions atomic. `volatile int count; count++` still loses updates.

| Need | Use |
|---|---|
| One thread writes a flag/reference, others read it | `volatile` |
| Several threads increment / update based on the current value | atomic class or lock |
| Several fields must change together | lock, or CAS on an immutable state object |

> **Common mistake:** Marking a counter `volatile` and thinking it is now thread-safe. `volatile`
> fixes "I can't see your write", not "we overwrote each other's write".

## 26.10 Deadlock

A **deadlock** is when threads wait for each other forever. Classic case: transferring between two
accounts by locking `from` then `to`. Thread 1 transfers a→b, thread 2 transfers b→a:

```text
   t1: lock(a) ........ wants lock(b) --- waits for t2 ---+
                                                          |  cycle: nobody can proceed
   t2: lock(b) ........ wants lock(a) --- waits for t1 ---+
```

```java
    // BROKEN: lock order depends on argument order.
    static void transferUnordered(Account from, Account to, long amt) {
        synchronized (from) {
            sleep(50);                          // widen the window so the demo is reliable
            synchronized (to) {
                from.balance -= amt;
                to.balance += amt;
            }
        }
    }
```

### The four conditions (Coffman conditions)

A deadlock needs **all four**; break any one and deadlock is impossible:

| Condition | Meaning | How to break it |
|---|---|---|
| Mutual exclusion | resources can be held by only one thread | use lock-free structures where possible |
| Hold and wait | a thread holds one lock while waiting for another | acquire all locks at once, or use a single coarser lock |
| No preemption | locks cannot be taken away | `tryLock` with timeout: give up, release, retry |
| Circular wait | a cycle in the waits-for graph | **global lock ordering** |

DSA bridge: draw a directed **waits-for graph** (edge T1 → T2 if T1 waits for a lock T2 holds).
Deadlock = a cycle. A global lock order (always acquire locks in increasing id) makes every edge
point "upward" in that order, so a cycle is impossible, exactly like a topological order forbids
cycles.

### The fix: lock ordering

```java
    // FIXED: always lock the lower id first, whatever the direction.
    static void transferOrdered(Account from, Account to, long amt) {
        Account first = from.id < to.id ? from : to;
        Account second = from.id < to.id ? to : from;
        synchronized (first) {
            sleep(50);
            synchronized (second) {
                from.balance -= amt;
                to.balance += amt;
            }
        }
    }
```

The demo runs both versions and uses the JVM's own deadlock detector to prove the first one is
stuck:

```text
unordered locking: deadlocked threads = 2
  t1 a->b waits for lock held by t2 b->a
  t2 b->a waits for lock held by t1 a->b
ordered locking: both finished = true, balances c=1100 d=900
```

(The stuck threads are daemon threads, so the JVM can still exit. In production you would find
them with a thread dump: `jstack <pid>` prints "Found one Java-level deadlock".)

Related failure modes: **livelock** (threads keep reacting to each other, for example both
backing off and retrying in lockstep, so nobody makes progress; fix with randomized backoff) and
**starvation** (a thread never gets the lock because others keep winning; fair locks help).

> **Say it like this:** "Anywhere I take two locks, I take them in a global order, by id. That
> removes circular wait, so deadlock can't happen. Where ordering isn't possible I'd use tryLock
> with a timeout and back off."

## 26.11 Thread pools and ExecutorService

**Problem.** Creating a new platform thread per task is expensive (each has a large stack and is
an OS thread), and unbounded thread creation under load exhausts memory. You also want to reuse
threads and control how many tasks run at once.

**Idea.** A **thread pool** keeps a fixed (or bounded) set of worker threads and a queue of tasks.
`ExecutorService` is the interface:

```java
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                int n = i;
                jobs.add(() -> n * n);
            }
            int sum = 0;
            for (Future<Integer> f : pool.invokeAll(jobs)) sum += f.get();
            System.out.println("fixed pool: sum of squares 1..8 = " + sum);
```

```text
   submit(task) --> [ task queue ] --> worker 1
                                   --> worker 2      results come back as Future<T>
                                   --> worker 3
                                   --> worker 4
```

- A **`Runnable`** is a task with no result; a **`Callable<T>`** returns a `T` and may throw.
- `submit` returns a **`Future<T>`**: a handle to a result that may not exist yet. `get()` blocks
  until it does (and rethrows the task's exception wrapped in `ExecutionException`).
- Always `shutdown()` a pool you created (or use try-with-resources, since `ExecutorService` is
  `AutoCloseable` in Java 21), or its threads keep the JVM alive.

**Sizing, roughly:**

| Work type | Pool size | Why |
|---|---|---|
| CPU-bound (compute) | about the number of cores | more threads just add context switching |
| I/O-bound (waiting on network/DB) | many more than cores | threads mostly wait; or use virtual threads (26.13) |

**Trade-off.** `Executors.newFixedThreadPool` uses an **unbounded** queue: under overload, tasks
pile up in memory instead of being rejected. For services, construct a `ThreadPoolExecutor` with a
bounded queue and a rejection policy, so overload shows up as fast failures (back-pressure)
instead of growing latency and memory.

## 26.12 CompletableFuture basics

**Problem.** A product page needs the user profile and the cart from two services, 100 ms each.
Calling them one after the other takes 200 ms; with plain `Future`s you end up blocking on
`get()` and writing glue code.

**Idea.** `CompletableFuture<T>` is a future you can **compose**: run things asynchronously,
combine results, transform them, and handle failures, without blocking until the very end.

```java
            long t0 = System.nanoTime();
            CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> slow("user:42", 100), pool);
            CompletableFuture<String> cart = CompletableFuture.supplyAsync(() -> slow("cart:3 items", 100), pool);
            String page = user.thenCombine(cart, (u, c) -> u + " | " + c)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
```

```java
            String withFallback = CompletableFuture
                    .supplyAsync(() -> { if (true) throw new IllegalStateException("recs down"); return "recs"; }, pool)
                    .exceptionally(ex -> "default recommendations")
                    .join();
```

(The `if (true)` is only there so the lambda compiles with both a `throw` and a `return`; it
simulates a failing call.)

| Method | Does |
|---|---|
| `supplyAsync(supplier, executor)` | run on a pool, produce a value |
| `thenApply(f)` | transform the result |
| `thenCompose(f)` | chain another async call that depends on the result |
| `thenCombine(other, f)` | wait for two independent futures, combine |
| `allOf(...)` | wait for many |
| `exceptionally(f)` / `handle(f)` | recover from failure |
| `orTimeout(t, unit)` | fail if not done in time |
| `join()` | block and get (unchecked exception on failure) |

Pass your own executor: without one, async stages run on the shared common `ForkJoinPool`, which
is sized for CPU work, and blocking I/O there starves everything else using it.

## 26.13 Virtual threads (Java 21), briefly

A **virtual thread** is a lightweight thread managed by the JVM rather than the OS. When it blocks
on I/O (or `sleep`), the JVM unmounts it from the underlying OS thread (the **carrier**) and runs
another virtual thread there. You can have hundreds of thousands of them.

```java
        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 10_000).forEach(i -> vts.submit(() -> {
                Thread.sleep(Duration.ofMillis(200));      // blocking call, e.g. an HTTP request
                return i;
            }));
        }                                                   // close() waits for all tasks
```

10,000 tasks that each block for 200 ms finish in roughly 200–300 ms total. With a fixed pool of
4 platform threads the same work would take about 10,000 / 4 × 200 ms ≈ 500 seconds.

What to know:

- They make the simple **thread-per-request, blocking-code** style scale for I/O-bound work. They
  do not make CPU-bound work faster.
- Do not pool them; create one per task.
- Locks still matter: virtual threads share memory exactly like platform threads, so every race
  in this chapter still applies.
- In Java 21, a virtual thread that blocks while inside a `synchronized` block **pins** its
  carrier (the carrier cannot run other virtual threads meanwhile). For long blocking operations
  under a lock, prefer `ReentrantLock` on Java 21. (Later JDK releases remove most pinning from
  `synchronized`.)
- Limit concurrency to fragile downstreams with a `Semaphore`, since there is no pool size acting
  as an implicit limit anymore.

## 26.14 Producer-consumer with BlockingQueue

**Problem.** Some threads produce work (incoming orders, log lines, emails to send) and others
process it, at different speeds. You need a hand-off that is thread-safe, makes consumers wait
when there is nothing to do, and makes producers slow down when consumers fall behind.

**Idea.** A **bounded `BlockingQueue`**: `put()` blocks when the queue is full (that is
**back-pressure**: fast producers are slowed to the consumers' pace), `take()` blocks when it is
empty.

```text
  producer-0 --put--> +---------------------+ --take--> consumer-0
  producer-1 --put--> | ArrayBlockingQueue  | --take--> consumer-1
                      |  capacity 5         | --take--> consumer-2
                      +---------------------+
          full: put() waits          empty: take() waits
```

```java
        BlockingQueue<Job> queue = new ArrayBlockingQueue<>(5);   // bounded on purpose
```

```java
                    while (true) {
                        Job job = queue.take();
                        if (job == POISON) return;          // clean shutdown signal
                        Thread.sleep(5);                     // simulate work
                        seen.add(job.id());
                        processed.incrementAndGet();
                    }
```

A **poison pill** is a special item that tells a consumer to stop; put one per consumer after the
producers finish:

```java
        for (Thread t : producerThreads) t.join();           // all real jobs enqueued
        for (int c = 0; c < consumers; c++) queue.put(POISON); // one pill per consumer
        for (Thread t : consumerThreads) t.join();
```

```text
processed 40 jobs, distinct ids 40, queue empty: true
```

This is the in-process version of a message queue (the distributed version is in the messaging
chapter). An `ExecutorService` is itself a producer-consumer: `submit` puts tasks on its queue and
the workers take them.

**Trade-off.** Bounded queues push back on producers (good for stability, but the producer now
blocks); unbounded queues never block producers and can grow until memory runs out. Choose the
bound and decide what happens when full: block, drop, or reject with an error.

## 26.15 Thread-safe singleton

Chapter 23 covers the three correct forms; the concurrency summary:

| Form | Thread-safe? | Why |
|---|---|---|
| `if (instance == null) instance = new X();` | No | two threads can both see null and create two |
| `synchronized` `getInstance()` | Yes | but every call takes the lock |
| Double-checked locking with `volatile` | Yes | `volatile` prevents publishing a half-built object |
| Holder class idiom | Yes | JVM class initialization is thread-safe and lazy |
| Enum | Yes | same guarantee, plus safe against reflection and serialization |

And the singleton's own state must still be thread-safe: the `IdGenerator` singleton uses an
`AtomicLong`, not a plain `long`.

## 26.16 Designing a thread-safe rate limiter (token bucket)

### Requirements

Allow each user at most `capacity` requests in a burst, refilled at `r` tokens per second. Many
request threads call `allow(userId)` concurrently.

### The algorithm

A **token bucket** holds up to `capacity` tokens. Tokens are added continuously at `r` per
second (computed lazily from elapsed time, no timer thread). Each request takes one token; with
none left, the request is rejected.

```text
  tokens
  10 |####                          ####
     |    #                        #
   5 |     #####           ########       <- refills at 5/s, capped at capacity
     |          #         #
   0 |           ########                 <- empty: requests rejected
     +-----------------------------------> time
```

### Where the races are

1. **Bucket creation**: two first requests for a new user both create a bucket; one overwrites the
   other. Fix: `computeIfAbsent`.
2. **Refill-and-take**: two threads read "1 token", both take it. Fix: make refill + check + take
   one atomic step per bucket.

```java
    boolean allow(String key) {
        // computeIfAbsent is atomic: two first requests for a key share one bucket
        TokenBucket b = buckets.computeIfAbsent(key,
                k -> new TokenBucket(capacity, refillPerSecond, nanoClock.getAsLong()));
        return b.tryAcquire(nanoClock.getAsLong());
    }
```

```java
        // Refill and take must be one atomic step, so the method is synchronized.
        synchronized boolean tryAcquire(long now) {
            if (now > lastRefill) {
                tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
                lastRefill = now;
            }
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
```

The lock is **per bucket**, so different users never contend with each other; contention exists
only among concurrent requests of the same user, which is exactly the case that must be
serialized.

The clock is injected (`LongSupplier nanoClock`), so the test controls time and can assert exact
numbers under real concurrency:

```text
50 concurrent requests at t=0s   -> allowed 10 (capacity 10)
20 concurrent requests at t=1s   -> allowed 5 (refill 5/s)
50 concurrent requests at t=61s  -> allowed 10 (capped)
50 concurrent requests for user-2 -> allowed 10 (own bucket)
All rate limiter checks passed
```

**Alternatives and trade-offs.**

- A lock-free version: store `(tokens, lastRefill)` in an immutable object behind an
  `AtomicReference` and update it with a CAS loop (as in 26.7). Faster under low contention,
  retries under high contention.
- Memory: one bucket per user forever is a leak; evict idle buckets (for example, buckets idle
  longer than the time to refill completely are equivalent to new ones and can be dropped).
- Across many servers, an in-memory limiter only limits per server; a global limit needs shared
  state (for example in a fast key-value store with an atomic script), covered in the rate limiter
  HLD chapter.

## 26.17 Designing a thread-safe LRU cache

**The trap.** In an LRU cache, `get` **mutates**: it moves the entry to the front of the recency
list. So a read-write lock does not help (readers would modify the list concurrently), and
`ConcurrentHashMap` alone does not help (the list is a second structure that must change together
with the map). Unsynchronized concurrent access can corrupt the linked list, losing entries or
creating cycles.

**Simple correct version:** one lock around every operation.

```java
    public V get(K key) {
        lock.lock();
        try { return map.get(key); } finally { lock.unlock(); }
    }

    public void put(K key, V value) {
        lock.lock();
        try { map.put(key, value); } finally { lock.unlock(); }
    }
```

Here `map` is a `LinkedHashMap` in access order with `removeEldestEntry`, the library LRU from
chapter 25. The demo runs 1.6 million mixed operations from 8 threads and checks capacity and
value consistency.

**Scaling it (what to say when asked):**

| Approach | Idea | Trade-off |
|---|---|---|
| One lock (above) | serialize all operations | simple, correct; one hot lock under heavy load |
| Striping / sharding | N independent LRU caches, pick by `hash(key) % N`, each with its own lock | N times less contention; eviction is LRU per shard, only approximately global |
| Buffered recency | record accesses into a concurrent buffer, apply them to the list in batches under the lock | `get` rarely blocks; recency is slightly delayed. Used by high-performance caching libraries |
| Approximate LRU | sample a few random keys and evict the oldest of them | no list at all; good enough eviction quality in practice |

**Loading on a miss.** If many threads miss the same key at once, they all load it from the
database (a **cache stampede** for one key). Store a `CompletableFuture<V>` per key in a
`ConcurrentHashMap` via `computeIfAbsent`, so the first thread starts the load and the others wait
on the same future, without holding a cache-wide lock during the slow load.

## 26.18 Optimistic vs pessimistic locking: object level and database level

Both are strategies for the same question: how do concurrent updates to the same data avoid
overwriting each other?

**Pessimistic locking**: assume conflicts are likely; lock first, then read and write; others
wait.

**Optimistic locking**: assume conflicts are rare; read without locking, compute, and at write
time check that nobody changed the data since you read it (by a version number or the old value).
If someone did, retry or report a conflict.

| | Object level (inside one JVM) | Database level (shared by many servers, see chapter 05) |
|---|---|---|
| Pessimistic | `synchronized`, `ReentrantLock` around check-and-update | `SELECT ... FOR UPDATE` inside a transaction, then `UPDATE` |
| Optimistic | CAS loop on `AtomicReference<ImmutableState>` with a version | `UPDATE t SET ..., version = version + 1 WHERE id = ? AND version = ?`; 0 rows updated = conflict |

The optimistic object-level version from `LocksDemo` (1000 concurrent deposits):

```text
Optimistic CAS: balance=10000 version=1000 retries=98
```

The retries are the conflicts detected and resolved. The database form is the same idea:

```sql
-- read
SELECT balance, version FROM account WHERE id = 42;          -- balance=500, version=7
-- write only if nobody else wrote in between
UPDATE account SET balance = 400, version = 8 WHERE id = 42 AND version = 7;
-- rows affected = 1 -> success; 0 -> someone else updated first: re-read and retry
```

**Choosing:**

| Situation | Prefer |
|---|---|
| Low contention, short operations, reads dominate | Optimistic: no waiting, no lock overhead |
| High contention on the same rows/objects (a hot seat, a flash-sale item) | Pessimistic: optimistic would mostly retry |
| The work between read and write is expensive or has side effects | Pessimistic, or make the work idempotent; a retry must be able to redo it |
| State spans several servers | Database-level (or a distributed lock); a Java lock protects one process only |

> **Common mistake:** Using `synchronized` in the service to protect seat booking in a system
> deployed on several servers. Each server has its own lock; two servers can still sell the same
> seat. The check must move to where the shared state lives: a conditional UPDATE, a unique
> constraint, or a row lock in the database.

## 26.19 How to say "this is where I'd add locking, and why"

Interviewers do not expect a lock-free masterpiece. They want to see that you can find the shared
state, name the race, choose a proportionate fix, and know its cost. A four-step script:

1. **Name the shared mutable state.** "The seat statuses of a show are shared by all booking
   requests."
2. **Name the dangerous sequence.** "Check that all seats are free, then mark them held. Two
   requests can both pass the check."
3. **Choose the smallest correct protection.** "I'll make check-and-mark atomic under a lock per
   show, so different shows don't contend."
4. **State the cost and the next step.** "Payment stays outside the lock so we never hold it for
   seconds. With several app servers, this moves into a conditional UPDATE in the database."

> **Say it like this:** "The counter is a read-modify-write, so I'll use an AtomicLong. The seat
> map has a check-then-act across several seats, so it needs a lock, one per show. The config is
> read-mostly, so I'll swap an immutable map through a volatile reference. And anywhere I take two
> locks, I take them in id order."

A quick decision table for the round:

| Shape of the shared state | Tool |
|---|---|
| Single counter / id | `AtomicLong` (`LongAdder` for hot metrics) |
| Map with per-key updates | `ConcurrentHashMap` with `compute`/`merge`/`computeIfAbsent` |
| Several fields or several objects that must change together | a lock (smallest scope that covers them) |
| Read-mostly config | immutable snapshot behind a `volatile` reference, or a read-write lock |
| Hand-off between threads | `BlockingQueue` (bounded) |
| Stop flag / published reference | `volatile` |
| Two locks needed | global lock order, or `tryLock` with timeout |
| State shared across servers | database constraint / conditional update / row lock |

## 26.20 Running the demos

```text
cd ~/learn-system-design/code
java LostUpdateDemo.java          # count++ loses updates; three fixes
java LocksDemo.java               # ReentrantLock, ReadWriteLock, CAS loop,
                                  # ConcurrentHashMap, volatile flag, optimistic CAS
java DeadlockDemo.java            # real deadlock detected, then fixed by lock order
java ExecutorsDemo.java           # fixed pool, CompletableFuture, virtual threads
java ProducerConsumer.java        # bounded BlockingQueue with poison pills
java TokenBucketRateLimiter.java  # per-key token buckets, exact counts under concurrency
java ThreadSafeLru.java           # LRU under 8 threads, 1.6M operations
```

## 26.21 Recap

- Concurrency bugs come from shared mutable state plus check-then-act or read-modify-write
  sequences.
- `count++` is three steps; the lost-update demo loses hundreds of thousands of increments.
- `synchronized` gives mutual exclusion, visibility, and reentrancy; keep critical sections short
  and free of slow I/O.
- `ReentrantLock` adds `tryLock`, timeouts, interruptible waits, and conditions; always unlock in
  `finally`.
- Read-write locks help only for read-heavy workloads whose reads do not mutate.
- Atomics and CAS loops are lock-free and great for single values or immutable state objects;
  they retry under contention.
- `ConcurrentHashMap`'s `compute`/`merge`/`computeIfAbsent` make per-key read-modify-write
  atomic; separate `get` then `put` is still a race.
- `volatile` gives visibility and ordering, not atomicity.
- Deadlock needs all four Coffman conditions; global lock ordering removes circular wait.
- Use pools for bounded concurrency (bounded queues for back-pressure), `CompletableFuture` for
  composing async calls, and virtual threads for many blocking I/O tasks.
- Token bucket: per-key buckets via `computeIfAbsent`, atomic refill-and-take per bucket.
- LRU `get` mutates, so it needs an exclusive lock; scale with sharding or buffered recency.
- Optimistic vs pessimistic applies both in memory (CAS vs locks) and in the database (version
  column vs `SELECT ... FOR UPDATE`); a Java lock never protects state shared across servers.

## 26.22 Check yourself

1. Why is `volatile int count; count++;` still broken, while `volatile boolean running` as a stop
   flag is fine?

<details><summary>Answer</summary>

`count++` is a read-modify-write; `volatile` makes each read and each write visible but does not
make the three steps one atomic unit, so two threads can read the same value and both write
value + 1. The stop flag is written by one thread with a plain assignment that does not depend on
the old value and read by others; the only requirement is visibility, which `volatile` provides.

</details>

2. Two threads call `if (!map.containsKey(k)) map.put(k, new Session())` on a
   `ConcurrentHashMap`. What can go wrong and what is the fix?

<details><summary>Answer</summary>

Both can see the key as absent and both put, so one session silently replaces the other (and one
caller holds an orphaned session). Each call is atomic but the pair is not. Use
`map.computeIfAbsent(k, key -> new Session())`, which checks and inserts atomically and runs the
function at most once per key.

</details>

3. List the four conditions for deadlock and explain how lock ordering prevents it.

<details><summary>Answer</summary>

Mutual exclusion, hold and wait, no preemption, circular wait. All four are needed. With a global
order (for example, always lock the lower account id first), every thread acquires locks in
increasing order, so no thread can hold a higher lock while waiting for a lower one. Any waits-for
chain only goes "upward" in the order and cannot close into a cycle, so circular wait is
impossible.

</details>

4. Why can't you use a read-write lock to make an LRU cache's `get` concurrent?

<details><summary>Answer</summary>

Because `get` modifies the recency order (it moves the entry to the front of the list). Under a
read lock, several `get`s would modify the linked list at the same time and corrupt it. Every
operation that changes shared structure needs exclusive access; alternatives are sharding into
several independently locked caches, or recording accesses in a buffer and applying them in
batches under the lock.

</details>

5. In the token bucket, what two races exist and how is each fixed?

<details><summary>Answer</summary>

(1) Creating a bucket for a new key: two first requests could each create one and one would be
overwritten, giving the user extra tokens. Fixed with `ConcurrentHashMap.computeIfAbsent`. (2)
Refill-check-take on a bucket: two requests could both see one token and both take it. Fixed by
making `tryAcquire` synchronized per bucket (or a CAS loop over an immutable state), so
refill + check + decrement is atomic.

</details>

6. Your booking service uses `synchronized` and runs on three servers behind a load balancer.
   Is double booking possible? What would you change?

<details><summary>Answer</summary>

Yes. Each server's JVM has its own lock, so two requests on different servers can both pass the
check. Move the atomic check to the shared state: a conditional `UPDATE ... WHERE status =
'AVAILABLE'` checking rows affected, a unique constraint on (show, seat) for bookings, a row lock
with `SELECT ... FOR UPDATE` in a transaction, or an atomic set-if-absent with expiry in a shared
key-value store for holds.

</details>

7. When would you choose optimistic over pessimistic locking, and when not?

<details><summary>Answer</summary>

Optimistic when conflicts are rare and operations are short: no lock waiting, good throughput,
conflicts handled by retry. Not when contention on the same item is high (a flash-sale product, a
popular seat), because most attempts would fail and retry, wasting work; and be careful when the
work between read and write has side effects that cannot simply be redone. Then lock
pessimistically.

</details>

8. Why do virtual threads not remove the need for locks, and what Java 21 caveat applies to
   `synchronized`?

<details><summary>Answer</summary>

Virtual threads change how threads are scheduled, not how memory is shared: they still read and
write the same objects concurrently, so every race and the need for atomicity and visibility
remain. In Java 21, a virtual thread that blocks inside a `synchronized` block pins its carrier OS
thread, reducing scalability; for long blocking operations under a lock, prefer `ReentrantLock`.

</details>
