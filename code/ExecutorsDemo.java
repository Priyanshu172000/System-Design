import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

// Chapter 26 - Thread pools (ExecutorService), Futures, CompletableFuture
// composition, and Java 21 virtual threads.
// Run: java ExecutorsDemo.java
public class ExecutorsDemo {
    public static void main(String[] args) throws Exception {
        // ---- Fixed pool: bounded number of threads, tasks queue up ----
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

            // ---- CompletableFuture: run two calls in parallel, combine, handle failure ----
            long t0 = System.nanoTime();
            CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> slow("user:42", 100), pool);
            CompletableFuture<String> cart = CompletableFuture.supplyAsync(() -> slow("cart:3 items", 100), pool);
            String page = user.thenCombine(cart, (u, c) -> u + " | " + c)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
            System.out.printf("CompletableFuture: '%s' in ~%d ms (parallel, not 200)%n",
                    page, (System.nanoTime() - t0) / 1_000_000);

            String withFallback = CompletableFuture
                    .supplyAsync(() -> { if (true) throw new IllegalStateException("recs down"); return "recs"; }, pool)
                    .exceptionally(ex -> "default recommendations")
                    .join();
            System.out.println("exceptionally: " + withFallback);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // ---- Virtual threads: one cheap thread per blocking task ----
        long t1 = System.nanoTime();
        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 10_000).forEach(i -> vts.submit(() -> {
                Thread.sleep(Duration.ofMillis(200));      // blocking call, e.g. an HTTP request
                return i;
            }));
        }                                                   // close() waits for all tasks
        System.out.printf("virtual threads: 10,000 tasks that each block 200 ms finished in ~%d ms%n",
                (System.nanoTime() - t1) / 1_000_000);
    }

    static String slow(String value, long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return value;
    }
}
