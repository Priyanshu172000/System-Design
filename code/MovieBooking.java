import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

// Chapter 25 - Movie ticket booking: shows, per-show seat state, temporary
// seat holds with expiry, all-or-nothing booking, and concurrency tests.
// Run: java MovieBooking.java
public class MovieBooking {
    public static void main(String[] args) throws Exception {
        FakeClock clock = new FakeClock(1_000_000);
        BookingService svc = new BookingService(clock, 10 * 60 * 1000);   // 10-minute holds

        Movie movie = new Movie("M-1", "Space Saga", 169);
        List<Seat> seats = new ArrayList<>();
        for (char row : "ABCD".toCharArray())
            for (int n = 1; n <= 10; n++)
                seats.add(new Seat(row + "" + n, row == 'A' ? SeatType.PREMIUM : SeatType.REGULAR));
        Screen screen = new Screen("SCR-1", "Audi 1", seats);
        Show show = svc.createShow(movie, screen, LocalDateTime.of(2026, 10, 10, 19, 0),
                Map.of(SeatType.PREMIUM, 40_000L, SeatType.REGULAR, 25_000L));

        // ---- Test 1: 50 users race for the same two seats; exactly one may win ----
        int racers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            String user = "U-" + i;
            fs.add(pool.submit(() -> {
                startGate.await();                       // all threads start together
                try {
                    Hold h = svc.holdSeats(show.id(), user, List.of("A1", "A2"));
                    svc.confirm(h.id(), true);
                    winners.incrementAndGet();
                } catch (SeatUnavailableException ignored) {
                }
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : fs) f.get();
        check(winners.get() == 1, "exactly one winner, got " + winners.get());
        System.out.println("Test 1 passed: 50 racers for A1+A2 -> 1 booking");

        // ---- Test 2: an expired hold frees the seat; the late confirm fails ----
        Hold slow = svc.holdSeats(show.id(), "U-slow", List.of("B1"));
        try {
            svc.holdSeats(show.id(), "U-fast", List.of("B1"));
            throw new AssertionError("B1 should be held");
        } catch (SeatUnavailableException expected) {
        }
        clock.advance(10 * 60 * 1000 + 1);              // hold expires
        Hold fast = svc.holdSeats(show.id(), "U-fast", List.of("B1"));
        try {
            svc.confirm(slow.id(), true);
            throw new AssertionError("expired hold must not confirm");
        } catch (HoldExpiredException expected) {
        }
        Booking b = svc.confirm(fast.id(), true);
        System.out.println("Test 2 passed: expired hold released; " + b);

        // ---- Test 3: 400 users grab random overlapping pairs; no seat sold twice ----
        List<String> pool3 = new ArrayList<>();
        for (char row : "CD".toCharArray()) for (int n = 1; n <= 10; n++) pool3.add(row + "" + n);
        List<Future<Booking>> results = new ArrayList<>();
        CountDownLatch gate3 = new CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            String user = "R-" + i;
            Random rnd = new Random(i);
            String s1 = pool3.get(rnd.nextInt(pool3.size()));
            String s2 = pool3.get(rnd.nextInt(pool3.size()));
            List<String> want = s1.equals(s2) ? List.of(s1) : List.of(s1, s2);
            results.add(pool.submit(() -> {
                gate3.await();
                try {
                    Hold h = svc.holdSeats(show.id(), user, want);
                    return svc.confirm(h.id(), rnd.nextInt(5) != 0);  // ~20% payments fail
                } catch (SeatUnavailableException | PaymentFailedException e) {
                    return null;
                }
            }));
        }
        gate3.countDown();
        Set<String> sold = new HashSet<>();
        int bookings = 0;
        for (Future<Booking> f : results) {
            Booking bk = f.get();
            if (bk == null) continue;
            bookings++;
            for (String s : bk.seatIds()) check(sold.add(s), "seat sold twice: " + s);
        }
        pool.shutdown();
        long bookedInShow = show.countBooked() - 3;      // minus A1, A2, B1 from tests 1-2
        check(bookedInShow == sold.size(), "show state matches bookings");
        System.out.println("Test 3 passed: " + bookings + " bookings, " + sold.size()
                + "/20 seats sold, no seat sold twice");
        System.out.println("Seats left for show: " + show.availableSeatIds(clock.getAsLong()).size());
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
    }
}

// ---------- Model ----------
record Movie(String id, String title, int durationMinutes) {}

enum SeatType { REGULAR, PREMIUM }

record Seat(String id, SeatType type) {}

record Screen(String id, String name, List<Seat> seats) {}

enum SeatStatus { AVAILABLE, HELD, BOOKED }

class ShowSeat {                          // the seat's state for ONE show
    final Seat seat;
    SeatStatus status = SeatStatus.AVAILABLE;
    String holdId;
    long holdExpiresAt;

    ShowSeat(Seat seat) { this.seat = seat; }

    boolean isFree(long now) {
        return status == SeatStatus.AVAILABLE
                || (status == SeatStatus.HELD && holdExpiresAt <= now);   // lazy expiry
    }
}

class Show {
    private final String id;
    private final Movie movie;
    private final Screen screen;
    private final LocalDateTime start;
    private final Map<SeatType, Long> pricePaise;
    final Map<String, ShowSeat> seats = new LinkedHashMap<>();
    final ReentrantLock lock = new ReentrantLock();     // guards every ShowSeat of this show

    Show(String id, Movie movie, Screen screen, LocalDateTime start, Map<SeatType, Long> price) {
        this.id = id;
        this.movie = movie;
        this.screen = screen;
        this.start = start;
        this.pricePaise = Map.copyOf(price);
        for (Seat s : screen.seats()) seats.put(s.id(), new ShowSeat(s));
    }

    String id() { return id; }
    long priceOf(Seat s) { return pricePaise.get(s.type()); }

    List<String> availableSeatIds(long now) {
        lock.lock();
        try {
            List<String> out = new ArrayList<>();
            for (ShowSeat s : seats.values()) if (s.isFree(now)) out.add(s.seat.id());
            return out;
        } finally {
            lock.unlock();
        }
    }

    long countBooked() {
        lock.lock();
        try {
            return seats.values().stream().filter(s -> s.status == SeatStatus.BOOKED).count();
        } finally {
            lock.unlock();
        }
    }
}

record Hold(String id, String showId, String userId, List<String> seatIds, long expiresAt) {}

record Booking(String id, String showId, String userId, List<String> seatIds, long amountPaise) {}

class SeatUnavailableException extends RuntimeException {
    SeatUnavailableException(String m) { super(m); }
}

class HoldExpiredException extends RuntimeException {
    HoldExpiredException(String m) { super(m); }
}

class PaymentFailedException extends RuntimeException {
    PaymentFailedException(String m) { super(m); }
}

// ---------- Service ----------
class BookingService {
    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long holdMillis;
    private final AtomicLong seq = new AtomicLong(1);

    BookingService(LongSupplier clock, long holdMillis) {
        this.clock = clock;
        this.holdMillis = holdMillis;
    }

    Show createShow(Movie m, Screen sc, LocalDateTime start, Map<SeatType, Long> price) {
        Show s = new Show("SH-" + seq.getAndIncrement(), m, sc, start, price);
        shows.put(s.id(), s);
        return s;
    }

    // All-or-nothing: either every requested seat becomes HELD by this user, or none.
    Hold holdSeats(String showId, String userId, List<String> seatIds) {
        Show show = shows.get(showId);
        long now = clock.getAsLong();
        show.lock.lock();
        try {
            for (String id : seatIds) {                       // 1) check all
                ShowSeat s = show.seats.get(id);
                if (s == null || !s.isFree(now)) throw new SeatUnavailableException(id + " not available");
            }
            Hold hold = new Hold("H-" + seq.getAndIncrement(), showId, userId,
                    List.copyOf(seatIds), now + holdMillis);
            for (String id : seatIds) {                       // 2) then take all
                ShowSeat s = show.seats.get(id);
                s.status = SeatStatus.HELD;
                s.holdId = hold.id();
                s.holdExpiresAt = hold.expiresAt();
            }
            holds.put(hold.id(), hold);
            return hold;
        } finally {
            show.lock.unlock();
        }
    }

    // Payment happens between hold and confirm (outside any lock); we pass its result in.
    Booking confirm(String holdId, boolean paymentSucceeded) {
        Hold hold = holds.remove(holdId);
        if (hold == null) throw new HoldExpiredException("unknown hold " + holdId);
        Show show = shows.get(hold.showId());
        long now = clock.getAsLong();
        show.lock.lock();
        try {
            for (String id : hold.seatIds()) {
                ShowSeat s = show.seats.get(id);
                // still ours? (an expired hold may have been taken by someone else)
                if (s.status != SeatStatus.HELD || !holdId.equals(s.holdId) || s.holdExpiresAt <= now)
                    throw new HoldExpiredException("hold " + holdId + " expired");
            }
            if (!paymentSucceeded) {
                for (String id : hold.seatIds()) release(show.seats.get(id));
                throw new PaymentFailedException("payment failed for " + holdId);
            }
            long amount = 0;
            for (String id : hold.seatIds()) {
                ShowSeat s = show.seats.get(id);
                s.status = SeatStatus.BOOKED;
                s.holdId = null;
                amount += show.priceOf(s.seat);
            }
            return new Booking("B-" + seq.getAndIncrement(), show.id(), hold.userId(),
                    hold.seatIds(), amount);
        } finally {
            show.lock.unlock();
        }
    }

    private static void release(ShowSeat s) {
        s.status = SeatStatus.AVAILABLE;
        s.holdId = null;
        s.holdExpiresAt = 0;
    }
}

class FakeClock implements LongSupplier {
    private final AtomicLong millis;
    FakeClock(long start) { millis = new AtomicLong(start); }
    public long getAsLong() { return millis.get(); }
    void advance(long ms) { millis.addAndGet(ms); }
}
