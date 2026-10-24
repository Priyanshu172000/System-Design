import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

// Chapter 26 - Deadlock: two transfers lock two accounts in opposite order.
// Part 1 deadlocks (detected with ThreadMXBean; the stuck threads are daemons
// so the JVM can still exit). Part 2 fixes it with a global lock order.
// Run: java DeadlockDemo.java
public class DeadlockDemo {
    static class Account {
        final int id;
        long balance;
        Account(int id, long balance) { this.id = id; this.balance = balance; }
    }

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

    public static void main(String[] args) throws Exception {
        Account a = new Account(1, 1_000);
        Account b = new Account(2, 1_000);

        Thread t1 = daemon(() -> transferUnordered(a, b, 100), "t1 a->b");
        Thread t2 = daemon(() -> transferUnordered(b, a, 200), "t2 b->a");
        t1.start();
        t2.start();
        t1.join(1000);
        t2.join(1000);

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] stuck = mx.findDeadlockedThreads();
        System.out.println("unordered locking: deadlocked threads = " + (stuck == null ? 0 : stuck.length));
        if (stuck != null) {
            for (var info : mx.getThreadInfo(stuck)) {
                System.out.println("  " + info.getThreadName() + " waits for lock held by "
                        + info.getLockOwnerName());
            }
        }

        Account c = new Account(3, 1_000);
        Account d = new Account(4, 1_000);
        Thread t3 = daemon(() -> transferOrdered(c, d, 100), "t3 c->d");
        Thread t4 = daemon(() -> transferOrdered(d, c, 200), "t4 d->c");
        t3.start();
        t4.start();
        t3.join(1000);
        t4.join(1000);
        System.out.println("ordered locking: both finished = " + (!t3.isAlive() && !t4.isAlive())
                + ", balances c=" + c.balance + " d=" + d.balance);
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);                      // lets the JVM exit despite the stuck pair
        return t;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
