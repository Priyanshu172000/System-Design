import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

// Chapter 26 - Thread-safe LRU cache. In an LRU, get() MUTATES the recency
// order, so reads need the same exclusive lock as writes (a read-write lock
// does not help). Built on LinkedHashMap in access order.
// Run: java ThreadSafeLru.java
public class ThreadSafeLru<K, V> {
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, V> map;

    public ThreadSafeLru(int capacity) {
        // accessOrder = true: every get() moves the entry to the end (most recent)
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;           // evict least recently used
            }
        };
    }

    public V get(K key) {
        lock.lock();
        try { return map.get(key); } finally { lock.unlock(); }
    }

    public void put(K key, V value) {
        lock.lock();
        try { map.put(key, value); } finally { lock.unlock(); }
    }

    // Atomic "get or load": the loader runs under the lock, so a key is loaded once.
    // Fine for cheap loaders; for slow loaders see the chapter's note on per-key futures.
    public V computeIfAbsent(K key, java.util.function.Function<K, V> loader) {
        lock.lock();
        try { return map.computeIfAbsent(key, loader); } finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }

    public static void main(String[] args) throws Exception {
        int capacity = 100;
        ThreadSafeLru<Integer, Integer> cache = new ThreadSafeLru<>(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int seed = t;
            fs.add(pool.submit(() -> {
                Random rnd = new Random(seed);
                for (int i = 0; i < 200_000; i++) {
                    int k = rnd.nextInt(300);
                    switch (rnd.nextInt(3)) {
                        case 0 -> cache.put(k, k * 2);
                        case 1 -> {
                            Integer v = cache.get(k);
                            if (v != null && v != k * 2) throw new AssertionError("corrupt value");
                        }
                        default -> cache.computeIfAbsent(k, key -> key * 2);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();              // rethrows any AssertionError
        pool.shutdown();
        if (cache.size() > capacity) throw new AssertionError("over capacity");
        System.out.println("1,600,000 concurrent ops: size=" + cache.size() + " (cap " + capacity
                + "), every value consistent");
    }
}
