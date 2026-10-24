import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

// Chapter 26 - Race condition: count++ from many threads loses updates.
// Fixes: synchronized, AtomicInteger, LongAdder.
// Run: java LostUpdateDemo.java
public class LostUpdateDemo {
    static final int THREADS = 4;
    static final int PER_THREAD = 1_000_000;

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

    public static void main(String[] args) throws InterruptedException {
        int expected = THREADS * PER_THREAD;

        UnsafeCounter unsafe = new UnsafeCounter();
        run(unsafe::increment);
        System.out.printf("unsafe       : %,d (expected %,d, lost %,d)%n",
                unsafe.get(), expected, expected - unsafe.get());

        SyncCounter sync = new SyncCounter();
        run(sync::increment);
        System.out.printf("synchronized : %,d%n", sync.get());

        AtomicInteger atomic = new AtomicInteger();            // Fix 2: hardware CAS
        run(atomic::incrementAndGet);
        System.out.printf("AtomicInteger: %,d%n", atomic.get());

        LongAdder adder = new LongAdder();                     // Fix 3: striped counter
        run(adder::increment);
        System.out.printf("LongAdder    : %,d%n", adder.sum());
    }

    static void run(Runnable op) throws InterruptedException {
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < PER_THREAD; j++) op.run();
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();      // join also makes the threads' writes visible
    }
}
