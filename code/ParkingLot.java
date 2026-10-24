import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

// Chapter 25 - Parking lot: multi-floor lot, spot sizes, pluggable spot
// allocation and pricing strategies, tickets, thread-safe park/unpark.
// Run: java ParkingLot.java
public class ParkingLot {
    public static void main(String[] args) {
        ParkingLotService lot = new ParkingLotService(
                List.of(
                        new Floor(0, List.of(
                                new ParkingSpot("F0-S1", 0, SpotSize.SMALL),
                                new ParkingSpot("F0-M1", 0, SpotSize.MEDIUM),
                                new ParkingSpot("F0-L1", 0, SpotSize.LARGE))),
                        new Floor(1, List.of(
                                new ParkingSpot("F1-M1", 1, SpotSize.MEDIUM),
                                new ParkingSpot("F1-M2", 1, SpotSize.MEDIUM)))),
                new SmallestFitNearestStrategy(),
                new HourlyPricing(Map.of(
                        VehicleType.MOTORBIKE, 2_000L,
                        VehicleType.CAR, 5_000L,
                        VehicleType.TRUCK, 10_000L)));

        Instant t0 = Instant.parse("2026-10-10T09:00:00Z");
        Ticket bike = lot.park(new Vehicle("KA-01-B-1", VehicleType.MOTORBIKE), t0);
        Ticket car1 = lot.park(new Vehicle("KA-01-C-1", VehicleType.CAR), t0);
        Ticket car2 = lot.park(new Vehicle("KA-01-C-2", VehicleType.CAR), t0);
        Ticket truck = lot.park(new Vehicle("KA-01-T-1", VehicleType.TRUCK), t0);
        for (Ticket t : List.of(bike, car1, car2, truck)) {
            System.out.println("parked " + t.vehicle().plate() + " at " + t.spotId() + " ticket " + t.id());
        }
        System.out.println("free spots: " + lot.freeSpotCount());

        Ticket car3 = lot.park(new Vehicle("KA-01-C-3", VehicleType.CAR), t0);
        System.out.println("car3 at " + car3.spotId());
        try {
            lot.park(new Vehicle("KA-01-C-4", VehicleType.CAR), t0);
        } catch (LotFullException e) {
            System.out.println("car4 rejected: " + e.getMessage());
        }

        long fee = lot.unpark(car1.id(), t0.plus(Duration.ofMinutes(130)));   // 2h10m -> 3 hours
        System.out.println("car1 paid " + fee + " paise for 2h10m");
        Ticket car4 = lot.park(new Vehicle("KA-01-C-4", VehicleType.CAR), t0.plusSeconds(8_000));
        System.out.println("car4 now at " + car4.spotId());

        try {
            lot.unpark(car1.id(), t0.plusSeconds(9_000));
        } catch (IllegalArgumentException e) {
            System.out.println("double exit rejected: " + e.getMessage());
        }
    }
}

// ---------- Model ----------
enum VehicleType {
    MOTORBIKE(SpotSize.SMALL), CAR(SpotSize.MEDIUM), TRUCK(SpotSize.LARGE);

    final SpotSize minSize;
    VehicleType(SpotSize minSize) { this.minSize = minSize; }
}

enum SpotSize { SMALL, MEDIUM, LARGE }      // declared in increasing order

record Vehicle(String plate, VehicleType type) {}

class ParkingSpot {
    private final String id;
    private final int floor;
    private final SpotSize size;
    private Vehicle occupant;                 // null = free

    ParkingSpot(String id, int floor, SpotSize size) {
        this.id = id;
        this.floor = floor;
        this.size = size;
    }

    boolean isFree() { return occupant == null; }

    boolean canFit(Vehicle v) {
        return size.compareTo(v.type().minSize) >= 0;
    }

    void assign(Vehicle v) {
        if (!isFree()) throw new IllegalStateException(id + " is occupied");
        if (!canFit(v)) throw new IllegalArgumentException(v.type() + " does not fit " + size);
        occupant = v;
    }

    void release() { occupant = null; }

    String id() { return id; }
    int floor() { return floor; }
    SpotSize size() { return size; }
}

record Floor(int number, List<ParkingSpot> spots) {}

record Ticket(String id, Vehicle vehicle, String spotId, Instant entryTime) {}

class LotFullException extends RuntimeException {
    LotFullException(String msg) { super(msg); }
}

// ---------- Strategies (what varies) ----------
interface SpotAllocationStrategy {
    Optional<ParkingSpot> findSpot(List<Floor> floors, Vehicle v);
}

// Smallest spot that fits first (keeps big spots for big vehicles), then lowest floor.
class SmallestFitNearestStrategy implements SpotAllocationStrategy {
    public Optional<ParkingSpot> findSpot(List<Floor> floors, Vehicle v) {
        return floors.stream()
                .flatMap(f -> f.spots().stream())
                .filter(ParkingSpot::isFree)
                .filter(s -> s.canFit(v))
                .min(Comparator.comparing(ParkingSpot::size)
                        .thenComparingInt(ParkingSpot::floor));
    }
}

interface PricingStrategy {
    long fee(VehicleType type, Instant entry, Instant exit);
}

// Every started hour is charged (ceil), minimum one hour.
class HourlyPricing implements PricingStrategy {
    private final Map<VehicleType, Long> ratePerHourPaise;
    HourlyPricing(Map<VehicleType, Long> rates) { this.ratePerHourPaise = Map.copyOf(rates); }

    public long fee(VehicleType type, Instant entry, Instant exit) {
        long minutes = Duration.between(entry, exit).toMinutes();
        long hours = Math.max(1, (minutes + 59) / 60);
        return hours * ratePerHourPaise.get(type);
    }
}

// ---------- Service ----------
class ParkingLotService {
    private final List<Floor> floors;
    private final Map<String, ParkingSpot> spotsById = new HashMap<>();
    private final Map<String, Ticket> activeTickets = new HashMap<>();
    private final SpotAllocationStrategy allocation;
    private final PricingStrategy pricing;
    private final AtomicLong ticketSeq = new AtomicLong(1);

    ParkingLotService(List<Floor> floors, SpotAllocationStrategy allocation, PricingStrategy pricing) {
        this.floors = List.copyOf(floors);
        this.allocation = allocation;
        this.pricing = pricing;
        for (Floor f : floors) for (ParkingSpot s : f.spots()) spotsById.put(s.id(), s);
    }

    // synchronized: "find a free spot" and "take it" must be one atomic step,
    // otherwise two gates could hand out the same spot.
    synchronized Ticket park(Vehicle v, Instant now) {
        ParkingSpot spot = allocation.findSpot(floors, v)
                .orElseThrow(() -> new LotFullException("no spot for " + v.type()));
        spot.assign(v);
        Ticket t = new Ticket("T-" + ticketSeq.getAndIncrement(), v, spot.id(), now);
        activeTickets.put(t.id(), t);
        return t;
    }

    synchronized long unpark(String ticketId, Instant now) {
        Ticket t = activeTickets.remove(ticketId);
        if (t == null) throw new IllegalArgumentException("unknown or closed ticket " + ticketId);
        spotsById.get(t.spotId()).release();
        return pricing.fee(t.vehicle().type(), t.entryTime(), now);
    }

    synchronized long freeSpotCount() {
        return spotsById.values().stream().filter(ParkingSpot::isFree).count();
    }
}
