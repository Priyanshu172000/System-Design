import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// Chapter 26 - Producer-consumer with a bounded BlockingQueue.
// put() blocks when full (back-pressure), take() blocks when empty.
// Poison pills tell consumers to stop.
// Run: java ProducerConsumer.java
public class ProducerConsumer {
    record Job(int id) {}

    static final Job POISON = new Job(-1);

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<Job> queue = new ArrayBlockingQueue<>(5);   // bounded on purpose
        int producers = 2, consumers = 3, jobsPerProducer = 20;
        AtomicInteger processed = new AtomicInteger();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();

        List<Thread> producerThreads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int base = p * 1000;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < jobsPerProducer; i++) queue.put(new Job(base + i));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "producer-" + p);
            producerThreads.add(t);
            t.start();
        }

        List<Thread> consumerThreads = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Job job = queue.take();
                        if (job == POISON) return;          // clean shutdown signal
                        Thread.sleep(5);                     // simulate work
                        seen.add(job.id());
                        processed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-" + c);
            consumerThreads.add(t);
            t.start();
        }

        for (Thread t : producerThreads) t.join();           // all real jobs enqueued
        for (int c = 0; c < consumers; c++) queue.put(POISON); // one pill per consumer
        for (Thread t : consumerThreads) t.join();

        System.out.println("processed " + processed.get() + " jobs, distinct ids " + seen.size()
                + ", queue empty: " + queue.isEmpty());
    }
}
