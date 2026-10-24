import java.util.*;

// Chapter 25 - Generic LRU cache with O(1) get/put:
// HashMap<K, Node> for lookup + doubly linked list for recency order.
// Run: java LruCache.java
public class LruCache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head = new Node<>(null, null);   // sentinel: most recent side
    private final Node<K, V> tail = new Node<>(null, null);   // sentinel: least recent side

    public LruCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        head.next = tail;
        tail.prev = head;
    }

    public Optional<V> get(K key) {
        Node<K, V> n = map.get(key);
        if (n == null) return Optional.empty();
        moveToFront(n);                        // a read counts as a use
        return Optional.of(n.value);
    }

    public void put(K key, V value) {
        Node<K, V> n = map.get(key);
        if (n != null) {                       // update in place, mark as recent
            n.value = value;
            moveToFront(n);
            return;
        }
        if (map.size() == capacity) {          // evict the least recently used
            Node<K, V> lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);
        }
        Node<K, V> fresh = new Node<>(key, value);
        addAfterHead(fresh);
        map.put(key, fresh);
    }

    public int size() { return map.size(); }

    // Keys from most to least recently used (for debugging/tests).
    public List<K> keysMostRecentFirst() {
        List<K> out = new ArrayList<>();
        for (Node<K, V> n = head.next; n != tail; n = n.next) out.add(n.key);
        return out;
    }

    private void moveToFront(Node<K, V> n) {
        unlink(n);
        addAfterHead(n);
    }

    private void unlink(Node<K, V> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void addAfterHead(Node<K, V> n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }

    // ---------- demo + checks ----------
    public static void main(String[] args) {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        System.out.println("order: " + cache.keysMostRecentFirst());   // [c, b, a]
        cache.get("a");                                                 // a becomes most recent
        cache.put("d", 4);                                              // evicts b
        System.out.println("after get(a), put(d): " + cache.keysMostRecentFirst());
        check(cache.get("b").isEmpty(), "b evicted");
        check(cache.get("a").orElseThrow() == 1, "a kept");
        cache.put("c", 30);                                             // update existing
        check(cache.get("c").orElseThrow() == 30, "c updated");
        check(cache.size() == 3, "size stays at capacity");
        System.out.println("final order: " + cache.keysMostRecentFirst());

        // Randomized check against a simple O(n) reference model.
        Random rnd = new Random(7);
        LruCache<Integer, Integer> fast = new LruCache<>(5);
        LinkedHashMap<Integer, Integer> ref = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) {
                return size() > 5;
            }
        };
        for (int i = 0; i < 100_000; i++) {
            int k = rnd.nextInt(12);
            if (rnd.nextBoolean()) {
                fast.put(k, i);
                ref.put(k, i);
            } else {
                Integer expected = ref.get(k);
                check(Objects.equals(fast.get(k).orElse(null), expected), "get mismatch at " + i);
            }
        }
        System.out.println("100000 random ops matched the LinkedHashMap reference model");
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
    }
}
