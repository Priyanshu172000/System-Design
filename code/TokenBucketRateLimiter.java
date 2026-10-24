import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

// Chapter 26 - Thread-safe in-memory rate limiter (token bucket, one bucket
// per key). Buckets live in a ConcurrentHashMap; each bucket guards its own
// refill-and-take with its own lock, so different keys never contend.
// Run: java TokenBucketRateLimiter.java
public class TokenBucketRateLimiter {
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;

    TokenBucketRateLimiter(long capacity, double refillPerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
    }

    boolean allow(String key) {
        // computeIfAbsent is atomic: two first requests for a key share one bucket
        TokenBucket b = buckets.computeIfAbsent(key,
                k -> new TokenBucket(capacity, refillPerSecond, nanoClock.getAsLong()));
        return b.tryAcquire(nanoClock.getAsLong());
    }

    static final class TokenBucket {
        private final long capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;

        TokenBucket(long capacity, double refillPerSecond, long now) {
            this.capacity = capacity;
            this.refillPerNano = refillPerSecond / 1_000_000_000.0;
            this.tokens = capacity;                 // start full: allows an initial burst
            this.lastRefill = now;
        }

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
    }

    // ---------- demo + checks ----------
    public static void main(String[] args) throws Exception {
        AtomicLong fakeNanos = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5.0, fakeNanos::get);

        int allowed = burst(limiter, "user-1", 50);
        check(allowed == 10, "burst should allow exactly capacity, got " + allowed);
        System.out.println("50 concurrent requests at t=0s   -> allowed " + allowed + " (capacity 10)");

        fakeNanos.addAndGet(1_000_000_000L);         // one second later: +5 tokens
        allowed = burst(limiter, "user-1", 20);
        check(allowed == 5, "after 1s should allow 5, got " + allowed);
        System.out.println("20 concurrent requests at t=1s   -> allowed " + allowed + " (refill 5/s)");

        fakeNanos.addAndGet(60_000_000_000L);        // long idle: capped at capacity, not 300
        allowed = burst(limiter, "user-1", 50);
        check(allowed == 10, "refill must be capped at capacity, got " + allowed);
        System.out.println("50 concurrent requests at t=61s  -> allowed " + allowed + " (capped)");

        allowed = burst(limiter, "user-2", 50);
        check(allowed == 10, "other keys are independent, got " + allowed);
        System.out.println("50 concurrent requests for user-2 -> allowed " + allowed + " (own bucket)");
        System.out.println("All rate limiter checks passed");
    }

    static int burst(TokenBucketRateLimiter limiter, String key, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                gate.await();
                if (limiter.allow(key)) ok.incrementAndGet();
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        return ok.get();
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
    }
}
