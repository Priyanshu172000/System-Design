import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.*;

// Chapter 26 - Toolbox: ReentrantLock (tryLock with timeout), ReadWriteLock,
// compare-and-set loops, ConcurrentHashMap (computeIfAbsent, merge),
// volatile for visibility, and optimistic versioning on an object.
// Run: java LocksDemo.java
public class LocksDemo {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);

        // ---- ReentrantLock with tryLock: give up instead of waiting forever ----
        Inventory inv = new Inventory(5);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) fs.add(pool.submit(() -> inv.reserve(1)));
        int ok = 0;
        for (Future<Boolean> f : fs) if (f.get()) ok++;
        System.out.println("ReentrantLock: reserved " + ok + " of 20 requests, stock left " + inv.stock());

        // ---- ReadWriteLock: many readers at once, writers exclusive ----
        ConfigStore cfg = new ConfigStore();
        cfg.put("timeoutMs", "1000");
        List<Callable<String>> readers = new ArrayList<>();
        for (int i = 0; i < 50; i++) readers.add(() -> cfg.get("timeoutMs"));
        pool.submit(() -> cfg.put("timeoutMs", "2000"));
        Set<String> seen = new TreeSet<>();
        for (Future<String> f : pool.invokeAll(readers)) seen.add(f.get());
        System.out.println("ReadWriteLock: readers saw " + seen + " (old or new, never garbage)");

        // ---- CAS loop: lock-free "update max" ----
        AtomicInteger maxLatency = new AtomicInteger(0);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            int sample = (i * 7919) % 1000;
            tasks.add(() -> { updateMax(maxLatency, sample); return null; });
        }
        pool.invokeAll(tasks);
        System.out.println("CAS loop: max latency = " + maxLatency.get());

        // ---- ConcurrentHashMap: computeIfAbsent and merge are atomic per key ----
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, List<String>> byUser = new ConcurrentHashMap<>();
        List<Callable<Void>> clicks = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            String page = "page-" + (i % 3);
            String user = "user-" + (i % 4);
            clicks.add(() -> {
                counts.merge(page, 1, Integer::sum);       // atomic increment per key
                byUser.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(page);
                return null;
            });
        }
        pool.invokeAll(clicks);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int listed = byUser.values().stream().mapToInt(List::size).sum();
        System.out.println("ConcurrentHashMap: " + new TreeMap<>(counts) + " total=" + total
                + ", per-user lists hold " + listed);

        // ---- volatile: a stop flag other threads are guaranteed to see ----
        Worker w = new Worker();
        Thread t = new Thread(w);
        t.start();
        Thread.sleep(50);
        w.stop();
        t.join(1000);
        System.out.println("volatile: worker stopped = " + !t.isAlive() + " after " + w.loops() + " loops");

        // ---- Optimistic versioning on an object (no lock held while computing) ----
        VersionedAccount acct = new VersionedAccount(0);
        List<Callable<Void>> deposits = new ArrayList<>();
        for (int i = 0; i < 1000; i++) deposits.add(() -> { acct.deposit(10); return null; });
        pool.invokeAll(deposits);
        System.out.println("Optimistic CAS: balance=" + acct.balance() + " version=" + acct.version()
                + " retries=" + acct.retries());

        pool.shutdown();
    }

    // Classic CAS loop: read, compute, try to swap; if someone beat us, retry.
    static void updateMax(AtomicInteger max, int candidate) {
        while (true) {
            int cur = max.get();
            if (candidate <= cur) return;
            if (max.compareAndSet(cur, candidate)) return;
        }
    }
}

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

    int stock() {
        lock.lock();
        try { return stock; } finally { lock.unlock(); }
    }
}

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

class Worker implements Runnable {
    private volatile boolean running = true;   // without volatile the loop may never see false
    private long loops = 0;

    public void run() {
        while (running) loops++;
    }

    void stop() { running = false; }
    long loops() { return loops; }
}

class VersionedAccount {
    record State(long balance, long version) {}

    private final AtomicReference<State> state;
    private final AtomicInteger retries = new AtomicInteger();

    VersionedAccount(long balance) { state = new AtomicReference<>(new State(balance, 0)); }

    void deposit(long amount) {
        while (true) {
            State cur = state.get();                                    // read version
            State next = new State(cur.balance() + amount, cur.version() + 1);
            if (state.compareAndSet(cur, next)) return;                 // commit if unchanged
            retries.incrementAndGet();                                  // conflict: retry
        }
    }

    long balance() { return state.get().balance(); }
    long version() { return state.get().version(); }
    int retries() { return retries.get(); }
}
