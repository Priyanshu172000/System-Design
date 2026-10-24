import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Chapter 24 - The LLD method applied end to end on a small problem:
// meeting room booking. Model (entities + value objects), repository
// interface, strategy for what varies, service, demo main.
// Run: java MeetingRoomBooking.java
public class MeetingRoomBooking {
    public static void main(String[] args) {
        RoomRepository rooms = new InMemoryRoomRepository();
        rooms.save(new Room("R-1", "Focus", 4));
        rooms.save(new Room("R-2", "Board", 12));
        rooms.save(new Room("R-3", "Huddle", 6));
        BookingService svc = new BookingService(rooms, new InMemoryBookingRepository(),
                new SmallestFittingRoomStrategy());

        LocalDateTime nine = LocalDateTime.of(2026, 10, 12, 9, 0);
        TimeSlot nineToTen = new TimeSlot(nine, nine.plusHours(1));
        Booking b1 = svc.book("U-1", 5, nineToTen);
        System.out.println("b1 -> " + b1);
        Booking b2 = svc.book("U-2", 5, new TimeSlot(nine.plusMinutes(30), nine.plusMinutes(90)));
        System.out.println("b2 -> " + b2 + "  (R-3 busy, next smallest fit)");
        try {
            svc.book("U-3", 5, nineToTen);
        } catch (NoRoomAvailableException e) {
            System.out.println("b3 rejected: " + e.getMessage());
        }
        svc.cancel(b1.id(), "U-1");
        Booking b3 = svc.book("U-3", 5, nineToTen);
        System.out.println("after cancel, b3 -> " + b3);
    }
}

// ---------- model ----------
record Room(String id, String name, int capacity) {}             // entity (immutable here)

record TimeSlot(LocalDateTime start, LocalDateTime end) {          // value object
    TimeSlot {
        if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
    }

    // Half-open intervals [start, end): 9-10 and 10-11 do not overlap.
    boolean overlaps(TimeSlot o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }
}

enum BookingStatus { CONFIRMED, CANCELLED }

class Booking {                                                     // entity with a lifecycle
    private final String id;
    private final String roomId;
    private final String userId;
    private final TimeSlot slot;
    private BookingStatus status = BookingStatus.CONFIRMED;

    Booking(String id, String roomId, String userId, TimeSlot slot) {
        this.id = id;
        this.roomId = roomId;
        this.userId = userId;
        this.slot = slot;
    }

    void cancelBy(String requester) {
        if (!requester.equals(userId)) throw new IllegalStateException("only the owner can cancel");
        if (status == BookingStatus.CANCELLED) throw new IllegalStateException("already cancelled");
        status = BookingStatus.CANCELLED;
    }

    boolean blocks(String room, TimeSlot other) {
        return status == BookingStatus.CONFIRMED && roomId.equals(room) && slot.overlaps(other);
    }

    String id() { return id; }

    @Override
    public String toString() { return id + "[" + roomId + " " + slot.start().toLocalTime()
            + "-" + slot.end().toLocalTime() + " " + status + "]"; }
}

class NoRoomAvailableException extends RuntimeException {
    NoRoomAvailableException(String m) { super(m); }
}

// ---------- repositories (storage behind an interface) ----------
interface RoomRepository {
    void save(Room r);
    List<Room> findAll();
}

interface BookingRepository {
    void save(Booking b);
    Optional<Booking> findById(String id);
    List<Booking> findAll();
}

class InMemoryRoomRepository implements RoomRepository {
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    public void save(Room r) { rooms.put(r.id(), r); }
    public List<Room> findAll() { return List.copyOf(rooms.values()); }
}

class InMemoryBookingRepository implements BookingRepository {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    public void save(Booking b) { bookings.put(b.id(), b); }
    public Optional<Booking> findById(String id) { return Optional.ofNullable(bookings.get(id)); }
    public List<Booking> findAll() { return List.copyOf(bookings.values()); }
}

// ---------- what varies: how to choose among free rooms ----------
interface RoomSelectionStrategy {
    Optional<Room> choose(List<Room> freeRoomsThatFit, int attendees);
}

class SmallestFittingRoomStrategy implements RoomSelectionStrategy {
    public Optional<Room> choose(List<Room> candidates, int attendees) {
        return candidates.stream().min(Comparator.comparingInt(Room::capacity));
    }
}

// ---------- service: orchestrates, holds no business state of its own ----------
class BookingService {
    private final RoomRepository rooms;
    private final BookingRepository bookings;
    private final RoomSelectionStrategy strategy;
    private final AtomicLong seq = new AtomicLong(1);

    BookingService(RoomRepository rooms, BookingRepository bookings, RoomSelectionStrategy strategy) {
        this.rooms = rooms;
        this.bookings = bookings;
        this.strategy = strategy;
    }

    // synchronized: "check free" + "save booking" must be atomic, or two
    // requests can both see the room free and both book it.
    synchronized Booking book(String userId, int attendees, TimeSlot slot) {
        List<Booking> existing = bookings.findAll();
        List<Room> free = rooms.findAll().stream()
                .filter(r -> r.capacity() >= attendees)
                .filter(r -> existing.stream().noneMatch(b -> b.blocks(r.id(), slot)))
                .toList();
        Room room = strategy.choose(free, attendees)
                .orElseThrow(() -> new NoRoomAvailableException("no room for " + attendees + " people"));
        Booking b = new Booking("B-" + seq.getAndIncrement(), room.id(), userId, slot);
        bookings.save(b);
        return b;
    }

    synchronized void cancel(String bookingId, String requester) {
        bookings.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("no booking " + bookingId))
                .cancelBy(requester);
    }
}
