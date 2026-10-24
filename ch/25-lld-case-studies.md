# 25 — LLD case studies

This chapter applies the method from chapter 24 to the seven problems that come up most often in
LLD and machine coding rounds. Each one follows the same arc: requirements, classes, class
diagram, key code, extensions, and the follow-up questions you should expect. Every design here
has a complete, runnable Java 21 program in `code/`, and every snippet is copied from it.

| Case | File | What it teaches |
|---|---|---|
| Parking lot | `ParkingLot.java` | Strategy for allocation and pricing, atomic check-then-act |
| Elevator system | `ElevatorSystem.java` | LOOK scheduling, car selection strategy, simulation |
| Movie ticket booking | `MovieBooking.java` | Seat holds with expiry, all-or-nothing booking, concurrency tests |
| Splitwise | `Splitwise.java` | Split strategies, integer money, debt simplification with heaps |
| LRU cache | `LruCache.java` | Hash map + doubly linked list, O(1), generics |
| Tic-tac-toe | `TicTacToe.java` | Game modeling, O(1) win check |
| Logging framework | `LoggingFramework.java` | Levels, appenders, chain of responsibility, singleton |

## 25.1 The template every case follows

```text
  Requirements      functional (what it does) + non-functional (concurrency, extensibility)
  Classes           entities / value objects / services / strategies, with one-line jobs
  Class diagram     5-12 boxes
  Key code          the 2-3 methods where the real logic lives
  Extensions        "what if..." answered by naming the class that changes
  Follow-ups        questions interviewers actually ask, with short answers
```

Read each case asking two questions: **what varies** (that becomes an interface) and **where is
the race** (that becomes a lock or an atomic operation).

---

## 25.2 Parking lot

### Requirements

Clarifying questions and the answers we assume:

- Vehicle types? Motorbike, car, truck.
- Spot types? Small, medium, large. A vehicle fits any spot at least as large as it needs.
- Multiple floors? Yes.
- Multiple entry/exit gates operating at once? Yes, so parking must be thread-safe.
- Pricing? Hourly by vehicle type, every started hour charged, minimum one hour. Must be
  changeable later.
- Payment methods, display boards, reservations? Out of scope (mention as extensions).

Functional: park a vehicle and get a ticket; unpark with the ticket and get the fee; report free
spots. Non-functional: no spot handed out twice; allocation and pricing rules are pluggable.

### Classes

| Class | Kind | Job |
|---|---|---|
| `VehicleType`, `SpotSize` | enums | fixed sets; vehicle type knows its minimum spot size |
| `Vehicle` | value object (record) | plate + type |
| `ParkingSpot` | entity | id, floor, size, current occupant; knows if a vehicle fits |
| `Floor` | value object (record) | floor number + its spots |
| `Ticket` | entity (record here, immutable) | id, vehicle, spot id, entry time |
| `SpotAllocationStrategy` | strategy interface | pick a spot for a vehicle |
| `PricingStrategy` | strategy interface | compute the fee |
| `ParkingLotService` | service | park, unpark, free count; holds the active tickets |

### Class diagram

```text
 +-----------------------------+       +------------------------------+
 | ParkingLotService           |------>| <<interface>>                |
 +-----------------------------+       | SpotAllocationStrategy       |
 | - floors: List<Floor>       |       | + findSpot(floors, v)        |
 | - spotsById: Map            |       +------------------------------+
 | - activeTickets: Map        |                 ^
 | - allocation, pricing       |                 : SmallestFitNearestStrategy
 +-----------------------------+       +------------------------------+
 | + park(v, now): Ticket      |------>| <<interface>> PricingStrategy|
 | + unpark(ticketId, now): fee|       | + fee(type, entry, exit)     |
 | + freeSpotCount()           |       +------------------------------+
 +-----------------------------+                 ^
        | 1                                      : HourlyPricing
        <#>
        | 1..*
 +-------------+  1 <#>--- 1..*  +---------------------+        +-----------+
 | Floor       |---------------->| ParkingSpot         |------->| Vehicle   |
 | number      |                 | id, floor, size     | 0..1   | plate,    |
 +-------------+                 | - occupant          |        | type      |
                                 | + canFit(v)         |        +-----------+
                                 | + assign(v)/release |
                                 +---------------------+
 Ticket: id, vehicle, spotId, entryTime   (created by service, stored in activeTickets)
```

### Key code

The vehicle type carries its own data, so no `switch` is needed anywhere to answer "which spot
size does a car need?":

```java
enum VehicleType {
    MOTORBIKE(SpotSize.SMALL), CAR(SpotSize.MEDIUM), TRUCK(SpotSize.LARGE);

    final SpotSize minSize;
    VehicleType(SpotSize minSize) { this.minSize = minSize; }
}

enum SpotSize { SMALL, MEDIUM, LARGE }      // declared in increasing order
```

The spot guards its own rules:

```java
    boolean canFit(Vehicle v) {
        return size.compareTo(v.type().minSize) >= 0;
    }

    void assign(Vehicle v) {
        if (!isFree()) throw new IllegalStateException(id + " is occupied");
        if (!canFit(v)) throw new IllegalArgumentException(v.type() + " does not fit " + size);
        occupant = v;
    }
```

(Enum `compareTo` compares declaration order, which is why `SpotSize` is declared smallest to
largest.)

Allocation is a strategy:

```java
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
```

A story from writing this demo: the first version tried "lowest floor first, then smallest spot
on that floor". With one large spot on the ground floor, the second car took it (no medium left on
floor 0) and the truck that arrived next had nowhere to go, even though floor 1 had free medium
spots. Changing only the strategy class fixed it; nothing else in the lot changed. That is the
argument for making allocation a strategy.

Pricing is also a strategy:

```java
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
```

`(minutes + 59) / 60` is integer ceiling division, the same trick as `(a + b - 1) / b`.

The service: the critical section is "find a free spot" plus "take it".

```java
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
```

Time is passed in (`Instant now`) instead of read from the system clock inside the method. That
makes pricing testable ("park at 9:00, leave at 11:10, expect 3 hours") without waiting.

Demo output:

```text
parked KA-01-B-1 at F0-S1 ticket T-1
parked KA-01-C-1 at F0-M1 ticket T-2
parked KA-01-C-2 at F1-M1 ticket T-3
parked KA-01-T-1 at F0-L1 ticket T-4
free spots: 1
car3 at F1-M2
car4 rejected: no spot for CAR
car1 paid 15000 paise for 2h10m
car4 now at F0-M1
double exit rejected: unknown or closed ticket T-2
```

### Concurrency

One `synchronized` service is correct and simple, and a parking lot's throughput (a few cars per
second per gate) is far below what a single lock can handle. If it were not, options are: a lock
per floor or per spot size with a re-check under the lock, or a `ConcurrentLinkedQueue` of free
spots per size where `poll()` atomically hands a spot to exactly one caller.

### Extensions

| Extension | Change |
|---|---|
| EV charging spots | New spot attribute (features set); new allocation strategy that prefers them for EVs |
| Weekend or per-30-minute pricing | New `PricingStrategy`; could be a decorator over the base (surcharge) |
| Display board per floor | Observer: spots publish "freed/occupied" events; boards subscribe |
| Payment methods | `PaymentProcessor` interface with adapters; `unpark` returns an amount to charge |
| Reservations | Spot gains a `RESERVED` state with expiry (same idea as movie seat holds below) |
| Lost ticket | Lookup by plate: add `Map<plate, ticketId>`; fee policy for lost tickets |

### Follow-up questions

- **Why is `Ticket` a record if it is an entity?** It has identity (id) but nothing about it
  changes after creation here, so immutability is free. If payment status were tracked on it, it
  would become a class with state.
- **Why not a singleton `ParkingLot`?** Nothing requires exactly one; tests want several lots.
  Create one in `main` and inject it.
- **How would you find a free spot in O(1)?** Keep a free-spot queue (or a `TreeSet` ordered by
  floor) per spot size; allocation polls from the smallest size that fits.

---

## 25.3 Elevator system

### Requirements

- A building with N floors and M elevator cars.
- **Hall calls**: a person on a floor presses UP or DOWN.
- **Car calls**: a person inside a car presses a destination floor.
- Each hall call is assigned to one car by a dispatching rule (pluggable).
- Each car serves its stops in an efficient order (pluggable scheduling).
- Out of scope: weight limits, door timing, emergency mode (extensions).

### Scheduling: FCFS, SCAN, LOOK

The scheduling problem is the same as disk-arm scheduling:

| Algorithm | Rule | Trade-off |
|---|---|---|
| FCFS (first come, first served) | Serve requests in arrival order | Simple, fair in order, but zig-zags and wastes travel |
| SSTF (shortest seek first) | Always go to the nearest request | Less travel, but far floors can starve |
| SCAN ("elevator algorithm") | Sweep in one direction to the last floor of the building, then reverse | No starvation; travels to the end even if nobody is there |
| LOOK | Like SCAN but reverse at the last **request** in the current direction | Same fairness, less wasted travel; what real elevators approximate |

```text
  Requests at 2, 5, 8; car at 4 moving UP; building has floors 0..9

  SCAN:  4 -> 5 -> 8 -> 9 (end) -> 2        travel = 1 + 3 + 1 + 7 = 12
  LOOK:  4 -> 5 -> 8 -> 2                   travel = 1 + 3 + 6     = 10
```

### Classes

| Class | Job |
|---|---|
| `Direction` | UP, DOWN, IDLE |
| `ElevatorCar` | current floor, direction, two sorted sets of stops (up-stops, down-stops); `step()` moves one floor using LOOK |
| `CarSelectionStrategy` | picks which car answers a hall call |
| `NearestSuitableCarStrategy` | cost = distance, plus a penalty if the car is moving away or in the other direction |
| `ElevatorController` | receives calls, delegates to the strategy and the cars, advances time |

States of a car (a simple state machine; an enum is enough here):

```text
            stop requested above            no stops left
   IDLE -------------------------> UP -------------------> IDLE
     |                              |  no stops ahead,
     | stop requested below         |  stops behind
     v                              v
    DOWN <------------------------- (reverse = the LOOK turn)
```

### Class diagram

```text
 +---------------------------+  uses  +--------------------------------+
 | ElevatorController        |------->| <<interface>>                  |
 +---------------------------+        | CarSelectionStrategy           |
 | - cars: List<ElevatorCar> |        | + select(cars, floor, dir)     |
 | + hallCall(floor, dir)    |        +--------------------------------+
 | + carCall(carId, floor)   |                   ^
 | + tick()                  |                   : NearestSuitableCarStrategy
 +---------------------------+
          | 1
          <#>
          | 1..*
 +------------------------------+
 | ElevatorCar                  |
 | - floor, dir                 |
 | - upStops: TreeSet<Integer>  |
 | - downStops: TreeSet<Integer>|
 | + addHallStop(f, dir)        |
 | + addCarStop(f)              |
 | + step(): String             |
 +------------------------------+
```

### Key code

Why two `TreeSet`s? A person on floor 5 who pressed DOWN should not be picked up by a car passing
5 on its way up; they would ride up first. So stops are split by the direction in which they
should be served. A `TreeSet` gives `ceiling`/`floor` in O(log n): "the next stop at or above
me" is one call.

```java
    // LOOK: next stop in the current direction; if none, the furthest
    // opposite-direction call that still lies ahead; else nothing (turn around).
    private Integer nextTarget() {
        if (dir == Direction.UP) {
            Integer a = upStops.ceiling(floor);
            if (a != null) return a;
            if (!downStops.isEmpty() && downStops.last() >= floor) return downStops.last();
        } else if (dir == Direction.DOWN) {
            Integer a = downStops.floor(floor);
            if (a != null) return a;
            if (!upStops.isEmpty() && upStops.first() <= floor) return upStops.first();
        }
        return null;
    }
```

```java
    // Advance one time unit: move one floor (or open doors). Returns a short status.
    String step() {
        if (!hasWork()) {
            dir = Direction.IDLE;
            return "car" + id + "@" + floor + " idle";
        }
        if (dir == Direction.IDLE) {
            int nearest = nearestStop();
            dir = nearest >= floor ? Direction.UP : Direction.DOWN;
        }
        Integer target = nextTarget();
        if (target == null) {                       // nothing ahead: reverse (the "LOOK" turn)
            dir = dir.opposite();
            target = nextTarget();
        }
        if (target != floor) floor += target > floor ? 1 : -1;

        boolean open;
        if (floor == target) {                      // destination: serve everyone waiting here
            open = upStops.remove(floor) | downStops.remove(floor);
        } else {                                    // passing through: serve same-direction only
            open = (dir == Direction.UP ? upStops : downStops).remove(floor);
        }
        return "car" + id + "@" + floor + " " + dir + (open ? " OPEN" : "");
    }
```

Note the non-short-circuit `|` in `upStops.remove(floor) | downStops.remove(floor)`: both removes
must run, so `||` (which would skip the second) is wrong here.

Car selection:

```java
    private int cost(ElevatorCar c, int floor, Direction wanted) {
        int dist = Math.abs(c.floor() - floor);
        if (c.direction() == Direction.IDLE) return dist;
        boolean approaching = (c.direction() == Direction.UP && floor >= c.floor())
                || (c.direction() == Direction.DOWN && floor <= c.floor());
        if (approaching && c.direction() == wanted) return dist;
        return dist + 2 * floors;
    }
```

The simulation advances in discrete ticks (one floor per tick), which keeps the design testable
without threads or real time. Output (two cars at floors 0 and 8; calls at 3 UP and 6 DOWN):

```text
     hall call 3 UP -> car1
     hall call 6 DOWN -> car2
t=1   car1@1 UP         car2@7 DOWN
t=2   car1@2 UP         car2@6 DOWN OPEN
t=3   car1@3 UP OPEN    car2@6 idle
     car1 button 9
...
t=10  car1@9 UP OPEN    car2@0 DOWN OPEN
```

### Concurrency

In a real system, buttons are pressed from many threads while each car runs its own loop. Make
each car's stop sets safe (a lock per car, or have the controller post requests into a
per-car `BlockingQueue` that the car's thread drains). The selection strategy only reads car
positions, so slightly stale reads are acceptable: the worst case is a slightly worse assignment.

### Extensions

| Extension | Change |
|---|---|
| Express elevators serving floors 20+ only | Car gains a set of served floors; strategy filters on it |
| Peak-hour mode (morning: park idle cars at lobby) | New `CarSelectionStrategy` and an idle-parking policy |
| Weight limit | Car tracks load; when full it skips hall calls (serves only car calls) |
| Door open/close timing, maintenance mode | Explicit car states (State pattern) since behavior per state grows |
| Requests as objects for logging/replay | Command pattern: `HallCallRequest`, `CarCallRequest` |

### Follow-up questions

- **Why does a car passing floor 5 going up not stop for a DOWN request at 5?** The rider wants
  to go down; picking them up now means riding up first. LOOK serves them on the way back down.
- **How does LOOK avoid starvation?** It finishes the sweep in the current direction before
  reversing, so a request behind the car waits at most one round trip.
- **What if a hall call's assigned car breaks down?** The controller re-dispatches that car's
  pending hall calls through the strategy; this is why hall calls are tracked by the controller,
  not only inside the car.

---

## 25.4 Movie ticket booking (BookMyShow-like)

### Requirements

- Movies play in screens; a **show** is one movie on one screen at one time.
- Each show has its own seat map (seat A1 for the 7 pm show is a different thing from A1 at
  10 pm).
- A user selects seats and the system **holds** them for a limited time (say 10 minutes) while
  the user pays.
- On successful payment the hold becomes a **booking**; on failure or expiry, the seats become
  available again.
- Booking several seats is all-or-nothing.
- **Many users** may try to book the same seats at the same moment; a seat must never be sold
  twice.
- Out of scope: search, discovery, cities, reviews.

### The seat state machine

```text
                 hold (all requested seats free)
   AVAILABLE --------------------------------------> HELD (holdId, expiresAt)
       ^                                               |    |
       |   payment failed / hold expired (lazy check)  |    | confirm (hold still valid
       +-----------------------------------------------+    |          + payment ok)
                                                            v
                                                          BOOKED
```

**Lazy expiry**: there is no background timer that flips HELD back to AVAILABLE. Instead, a seat
whose hold has expired is simply **treated as free** whenever someone checks. This avoids a
scheduler and its races, and is a common approach. (A periodic cleanup job can still run to tidy
state, but correctness does not depend on it.)

### Classes

| Class | Kind | Job |
|---|---|---|
| `Movie`, `Seat`, `Screen` | value objects (records) | catalog data |
| `SeatType` | enum | REGULAR, PREMIUM (price differs) |
| `Show` | entity | movie + screen + time + per-show seat states + **the lock** |
| `ShowSeat` | entity (inside Show) | one seat's status for one show |
| `Hold` | value object | holdId, user, seats, expiry |
| `Booking` | entity | confirmed purchase |
| `BookingService` | service | holdSeats, confirm |
| `LongSupplier` clock | dependency | injected time source so expiry is testable |

### Class diagram

```text
 +---------------------------+          +------------------+
 | BookingService            |          | <<LongSupplier>> |
 +---------------------------+  uses    | clock (millis)   |
 | - shows: Map<id, Show>    |--------->+------------------+
 | - holds: Map<id, Hold>    |
 | + holdSeats(show,user,ids)|          +----------------------------+
 | + confirm(holdId, paid)   |--------->| Show                       |
 +---------------------------+   *      | id, movie, screen, start   |
             |                          | lock: ReentrantLock        |
             | creates                  | seats: Map<id, ShowSeat>   |
             v                          +----------------------------+
 +------------------+  +-----------+          | 1 <#>
 | Hold             |  | Booking   |          | 1..*
 | id, user, seats, |  | id, user, |    +---------------------------+
 | expiresAt        |  | seats,    |    | ShowSeat                  |
 +------------------+  | amount    |    | seat, status, holdId,     |
                       +-----------+    | holdExpiresAt             |
                                        | + isFree(now)             |
                                        +---------------------------+
```

### Key code

The seat knows if it is free, including lazy expiry:

```java
    boolean isFree(long now) {
        return status == SeatStatus.AVAILABLE
                || (status == SeatStatus.HELD && holdExpiresAt <= now);   // lazy expiry
    }
```

Holding is **check all, then take all**, inside the show's lock:

```java
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
```

Two-phase "check all then take all" under one lock is what makes multi-seat booking
all-or-nothing: no partial holds to roll back.

Confirming re-validates that the hold is still ours. Between hold and confirm the user was paying
(outside any lock, because payment can take seconds), and the hold may have expired and been
taken by someone else:

```java
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
```

Why a lock **per show**: bookings for different shows never contend, while all seat changes
within one show are serialized. A show has a few hundred seats and bookings take microseconds
under the lock, so this is far from a bottleneck.

> **Common mistake:** Holding the lock while calling the payment gateway. Payment takes seconds;
> every other user of that show would block behind it. Hold briefly, release, pay, then confirm
> briefly under the lock again with a re-check.

### The concurrency tests

The program tests the design instead of hoping:

```java
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
```

A `CountDownLatch(1)` is a start gate: every task blocks on `await()` until `countDown()` releases
them all at once, maximizing the chance of a real collision. Test 2 advances the fake clock past
the hold to check expiry, and test 3 has 400 users grabbing random overlapping pairs (with about
20% failed payments) and asserts that no seat appears in two bookings:

```text
Test 1 passed: 50 racers for A1+A2 -> 1 booking
Test 2 passed: expired hold released; Booking[id=B-6, showId=SH-1, userId=U-fast,
  seatIds=[B1], amountPaise=25000]       (one line in the real output)
Test 3 passed: 10 bookings, 19/20 seats sold, no seat sold twice
Seats left for show: 18
```

(The counts in test 3 vary slightly between runs because thread timing varies; the invariant
"no seat sold twice" holds every time.)

### At the database level

In a real deployment the seat state lives in a database shared by many app servers, so a
Java lock is not enough (it only protects one process). Common approaches (chapter 05 covers
isolation and locking in depth):

```sql
-- Pessimistic: lock the rows, check, then update, in one transaction
SELECT seat_id, status, hold_expires_at FROM show_seat
 WHERE show_id = ? AND seat_id IN (?, ?) FOR UPDATE;
-- ...verify all free in application code, then:
UPDATE show_seat SET status = 'HELD', hold_id = ?, hold_expires_at = ?
 WHERE show_id = ? AND seat_id IN (?, ?);

-- Optimistic / conditional: let the WHERE clause do the check atomically
UPDATE show_seat SET status = 'HELD', hold_id = ?, hold_expires_at = ?
 WHERE show_id = ? AND seat_id IN (?, ?)
   AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= NOW()));
-- if rows_affected < seats requested -> roll back the transaction, report conflict
```

Some systems keep holds in a fast key-value store with a TTL (a "set if not exists with expiry"
per seat) and write only confirmed bookings to the database.

### Extensions

| Extension | Change |
|---|---|
| Dynamic pricing (weekend, demand) | `PricingStrategy` per show instead of a fixed price map |
| Seat-type rules ("no single gaps") | A `SeatSelectionValidator` checked inside `holdSeats` |
| Notifications on booking | Observer on a `BookingConfirmed` event |
| Cancellation and refund | Booking gains a state machine (CONFIRMED → CANCELLED), seats return to AVAILABLE |
| Waitlist | Queue per show; when seats free up, notify the next users |

### Follow-up questions

- **Why not `synchronized` on the whole service?** It works, but bookings for all shows would
  wait on each other. A lock per show keeps unrelated shows independent.
- **Why not a lock per seat?** Multi-seat requests would need several locks, which invites
  deadlock unless you always lock seats in sorted order (chapter 26). A per-show lock avoids that
  and is cheap enough.
- **What if the user's payment succeeds but the hold expired?** `confirm` fails; the system must
  refund. Real systems avoid this window by holding a little longer than the payment timeout, and
  treat "paid but not confirmed" as a reconciliation case.

> **Say it like this:** "Seats are per show. I hold them all-or-nothing under the show's lock,
> with an expiry checked lazily. Payment happens outside the lock, and confirm re-validates the
> hold. In a multi-server setup the same check moves into a conditional UPDATE in the database."

---

## 25.5 Splitwise

### Requirements

- Users, and expenses paid by one user and shared among several.
- Split types: **equal**, **exact amounts**, **percentages**. New types may come later.
- Show each user's net balance.
- **Simplify debts**: produce a short list of payments that settles everyone.
- Out of scope: groups, currencies, recurring expenses, history editing.

### Money

All amounts are `long` paise. Equal-splitting 1000.00 among three gives 333.34, 333.33, 333.33;
the extra paise must go somewhere, deterministically, and the shares must add up exactly to the
total. Doubles would make "shares add up exactly" unreliable.

### Classes

| Class | Kind | Job |
|---|---|---|
| `User` | entity (record) | id, name |
| `Expense` | entity (record) | who paid, how much, each person's share |
| `SplitStrategy` | interface | total + participants → shares that sum to the total |
| `EqualSplit`, `ExactSplit`, `PercentSplit` | strategies | the three split types, each validating its input |
| `Settlement` | value object | from, to, amount |
| `ExpenseService` | service | add users and expenses, keep net balances, simplify debts |

### Class diagram

```text
 +-------------------------------+     uses     +----------------------------------+
 | ExpenseService                |------------->| <<interface>> SplitStrategy      |
 +-------------------------------+              | + split(total, participants):Map |
 | - users: Map<id, User>        |              +----------------------------------+
 | - expenses: List<Expense>     |                 ^            ^             ^
 | - net: Map<id, Long>          |                 :            :             :
 +-------------------------------+            EqualSplit   ExactSplit   PercentSplit
 | + addExpense(...): Expense    |                          (amounts)    (percents)
 | + netBalances()               |
 | + simplifyDebts(): List<...>  |---creates---> Settlement(from, to, paise)
 +-------------------------------+
            | 1 <#> *
         Expense(id, desc, paidBy, amount, shares)
```

### Key code

```java
interface SplitStrategy {
    // Returns each participant's share; shares must add up to totalPaise exactly.
    Map<String, Long> split(long totalPaise, List<String> participants);
}

class EqualSplit implements SplitStrategy {
    public Map<String, Long> split(long totalPaise, List<String> participants) {
        int n = participants.size();
        long base = totalPaise / n;
        long remainder = totalPaise % n;              // leftover paise go to the first few
        Map<String, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) shares.put(participants.get(i), base + (i < remainder ? 1 : 0));
        return shares;
    }
}
```

Each strategy validates its own input. Exact amounts must cover exactly the participants and add
to the total:

```java
    public Map<String, Long> split(long totalPaise, List<String> participants) {
        if (!amounts.keySet().equals(new HashSet<>(participants)))
            throw new IllegalArgumentException("exact amounts must cover exactly the participants");
        long sum = amounts.values().stream().mapToLong(Long::longValue).sum();
        if (sum != totalPaise)
            throw new IllegalArgumentException("exact amounts add to " + sum + ", expected " + totalPaise);
        return new LinkedHashMap<>(amounts);
    }
```

Balances are kept as one **net** number per user (positive = others owe them). Adding an expense:
the payer's net goes up by the full amount, each participant's net goes down by their share. The
sum of all nets is always zero.

```java
        Map<String, Long> shares = strategy.split(amountPaise, participants);  // validates first
        Expense e = new Expense("E-" + seq++, description, paidBy, amountPaise, Map.copyOf(shares));
        expenses.add(e);
        net.merge(paidBy, amountPaise, Long::sum);
        shares.forEach((u, s) -> net.merge(u, -s, Long::sum));
        return e;
```

### Debt simplification: the DSA bridge

Given nets like A: -1708.34, B: -1208.33, C: +591.67, D: +2325.00, produce few payments.

**Greedy with two max-heaps**: repeatedly take the biggest creditor and the biggest debtor, make
the debtor pay `min(credit, debt)`, push back whoever still has a remainder.

```java
    // Greedy: repeatedly match the biggest creditor with the biggest debtor.
    // Each step settles at least one person fully, so at most n-1 transfers.
    // (Finding the true minimum number of transfers is NP-hard in general.)
    List<Settlement> simplifyDebts() {
        PriorityQueue<long[]> creditors = new PriorityQueue<>((x, y) -> Long.compare(y[1], x[1]));
        PriorityQueue<long[]> debtors = new PriorityQueue<>((x, y) -> Long.compare(y[1], x[1]));
        List<String> ids = new ArrayList<>(net.keySet());
        for (int i = 0; i < ids.size(); i++) {
            long b = net.get(ids.get(i));
            if (b > 0) creditors.add(new long[]{i, b});
            else if (b < 0) debtors.add(new long[]{i, -b});
        }
        List<Settlement> out = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            long[] c = creditors.poll();
            long[] d = debtors.poll();
            long pay = Math.min(c[1], d[1]);
            out.add(new Settlement(ids.get((int) d[0]), ids.get((int) c[0]), pay));
            if (c[1] > pay) creditors.add(new long[]{c[0], c[1] - pay});
            if (d[1] > pay) debtors.add(new long[]{d[0], d[1] - pay});
        }
        return out;
    }
```

Why it terminates in at most n-1 transfers: every iteration fully settles at least one of the two
people (the one with the smaller amount), and the last person is settled automatically because
nets sum to zero. Complexity: O(n log n).

Is it optimal? Not always. The true minimum number of transfers equals n minus the maximum number
of disjoint groups whose nets sum to zero; finding that is NP-hard in general (it contains subset
sum). For small n you can do a bitmask DP over subsets; in practice the greedy result is good
enough and easy to explain.

```text
Net balances (+ = is owed, - = owes):
  A: -Rs 1708.34
  B: -Rs 1208.33
  C: Rs 591.67
  D: Rs 2325.00
Simplified settlements:
  A pays D Rs 1708.34
  B pays D Rs 616.66
  B pays C Rs 591.67
Check passed: plan settles everyone in 3 transfers
```

### Extensions

| Extension | Change |
|---|---|
| Share-based split ("2 shares for A, 1 for B") | New `SplitStrategy`; nothing else changes |
| Groups | `Group` entity with members and its own expense list and nets |
| Pairwise "who owes whom" view | Keep `Map<pair, Long>` in addition to nets, updated per expense |
| Edit/delete expense | Apply the reverse of its shares to nets (store shares on the expense, as we do) |
| Multiple currencies | `Money(amount, currency)` value object; balances per currency |
| Concurrency | Make `addExpense` synchronized per group; nets must change atomically together |

### Follow-up questions

- **Why store each share on the expense instead of recomputing?** Recomputing with a different
  rounding rule later would silently change history; stored shares make edits and deletions exact.
- **Why does `ExactSplit` validate and not `ExpenseService`?** The rules are specific to the split
  type; the service just requires "shares add up to the total" from every strategy.

---

## 25.6 LRU cache

### Requirements

- Fixed capacity; `get(key)` and `put(key, value)` in **O(1)**.
- When full, `put` of a new key evicts the **least recently used** entry. A `get` counts as a use.
- Generic in key and value types.
- Follow-up: thread safety (chapter 26), other eviction policies.

### Data structure

A hash map gives O(1) lookup but no order. A doubly linked list gives O(1) move-to-front and
remove-from-tail if you already hold the node. Combine them: the map stores key → node, the list
keeps nodes in recency order.

```text
  map: { a -> node(a), c -> node(c), d -> node(d) }

  head <-> [d] <-> [a] <-> [c] <-> tail
  (sentinel)  most recent       least recent  (sentinel)

  get(c):   unlink node(c), insert after head    -> head <-> [c] <-> [d] <-> [a] <-> tail
  put(e):   full -> evict tail.prev (a), remove "a" from map, insert node(e) after head
```

**Sentinels** (dummy head and tail nodes) mean every real node always has non-null neighbors, so
insert and unlink have no special cases for empty lists or end nodes.

### Key code

```java
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
```

```java
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
```

Why the node stores its key: on eviction we have the tail node and must remove its entry from
the map, which needs the key.

`get` returns `Optional<V>` rather than `null`, so "absent" and "present with a null value" cannot
be confused, and callers are pushed to handle the miss.

The demo also cross-checks 100,000 random operations against a reference model built on
`LinkedHashMap` in access order:

```java
        LinkedHashMap<Integer, Integer> ref = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) {
                return size() > 5;
            }
        };
```

`LinkedHashMap` with `accessOrder = true` plus `removeEldestEntry` is a complete LRU in the
standard library. In an interview, mention it, then implement the map + list version, because
that is what is being tested.

### Extensions

| Extension | Change |
|---|---|
| Thread safety | One lock around get and put (get mutates order, so a read lock is not enough); see chapter 26 |
| TTL per entry | Node stores `expiresAt`; `get` treats expired as a miss and removes it |
| LFU eviction | Map key → node, plus map frequency → doubly linked list of nodes, plus `minFreq`; all O(1) |
| Pluggable eviction | `EvictionPolicy<K>` interface with `recordAccess(key)`, `recordInsert(key)`, `evictCandidate()` (Strategy) |
| Metrics | Decorator counting hits and misses |
| Loading cache | `get(key, loader)` computes on miss; see chapter 26 for doing that under concurrency |

### Follow-up questions

- **Why doubly linked and not singly linked?** Unlinking an arbitrary node in O(1) needs its
  predecessor; a singly linked list would need an O(n) search.
- **Memory overhead?** Per entry: a map entry plus a node with key, value, and two pointers. For
  small values the overhead can exceed the data; that is a reason production caches use more
  compact structures and approximate LRU.

---

## 25.7 Tic-tac-toe (and Snake and Ladder)

### Requirements

- N x N board, two players with symbols X and O, alternate turns.
- Reject moves off the board or on a taken cell (the turn does not advance).
- Detect a win (full row, column, or diagonal) or a draw (board full).
- Extensions: N in a row on bigger boards, computer player, undo.

### Classes

| Class | Kind | Job |
|---|---|---|
| `Symbol`, `GameStatus` | enums | X/O; IN_PROGRESS, X_WON, O_WON, DRAW |
| `Player` | value object | name + symbol |
| `Move` | value object | player + position (useful for history and undo) |
| `Board` | entity | the cells; validates and places |
| `Game` | entity/service | turns, status, win detection |

```text
 +---------------------------+ 1 <#>  1 +--------------------+
 | Game                      |---------->| Board              |
 | - players: Player[2]      |           | - cells: Symbol[][]|
 | - turn, movesMade, status |           | + place(r, c, s)   |
 | - rows[], cols[], diag,   |           | + render()         |
 |   antiDiag   (counters)   |           +--------------------+
 | + play(r, c): GameStatus  |---------->  Player(name, symbol)  2
 +---------------------------+---------->  Move(player, r, c)    last move
```

### Key code: O(1) win detection

Checking the whole board after each move is O(N^2) (or O(N) if you only check the affected row,
column, and diagonals). With counters it is O(1): X adds +1 and O adds -1 to its row, column, and
diagonal(s); a line is won when the absolute value of its counter reaches N, because only a line
filled entirely by one player can reach plus or minus N.

```java
        int n = board.size();
        int d = p.symbol() == Symbol.X ? 1 : -1;
        rows[r] += d;
        cols[c] += d;
        if (r == c) diag += d;
        if (r + c == n - 1) antiDiag += d;

        if (Math.abs(rows[r]) == n || Math.abs(cols[c]) == n
                || Math.abs(diag) == n || Math.abs(antiDiag) == n) {
            status = p.symbol() == Symbol.X ? GameStatus.X_WON : GameStatus.O_WON;
        } else if (movesMade == n * n) {
            status = GameStatus.DRAW;
        }
        turn = 1 - turn;
        return status;
```

Validation happens before any state changes, so an invalid move leaves the game exactly as it was:

```java
        Player p = players[turn];
        board.place(r, c, p.symbol());          // throws if invalid; turn does not advance
```

```text
P1(X) at 0,0 -> IN_PROGRESS
P2(O) at 1,1 -> IN_PROGRESS
P1(X) at 0,1 -> IN_PROGRESS
rejected (0,1): cell taken
P2(O) at 2,2 -> IN_PROGRESS
P1(X) at 0,2 -> X_WON
```

### Snake and Ladder (skeleton design)

The same modeling habits apply. Sketch (not in the demo code):

```text
  Board:   size (e.g. 100), Map<Integer, Integer> jumps
           // snake: head -> tail, ladder: bottom -> top
  Dice:    interface { int roll(); }   RandomDice, FixedSequenceDice (for tests)
  Player:  id, position
  Game:    Queue<Player> turnOrder; play():
             p = queue.poll(); next = p.position + dice.roll()
             if next > size: stay (or "bounce back", a rule to clarify)
             next = jumps.getOrDefault(next, next)
             if next == size: winner = p  else queue.offer(p)
```

Design points worth saying: snakes and ladders are the same thing (a jump map), not two class
hierarchies; dice is behind an interface so tests are deterministic; the turn queue makes adding
players trivial.

### Extensions and follow-ups

- **Computer player**: `MoveStrategy` interface (human input, random, minimax). Minimax with
  alpha-beta is the DSA bridge for a 3x3 board.
- **K in a row on an N x N board (like gomoku)**: counters no longer work; after each move scan
  the 4 directions from the last move, counting consecutive same symbols, which is O(K).
- **Undo**: keep a stack of `Move`s; undo clears the cell and reverses the counter updates
  (Command pattern).

---

## 25.8 Logging framework

### Requirements

- Levels: DEBUG < INFO < WARN < ERROR. A global minimum level filters cheaply.
- Several destinations (**appenders**): console, file, remote. Each destination may have its own
  minimum level (for example: console gets INFO+, an error store gets only ERROR).
- Pluggable message format.
- Configurable at runtime; safe to call from many threads.
- One logger accessible across the application.

### Classes

| Class | Pattern | Job |
|---|---|---|
| `LogLevel` | enum | ordered severities with `atLeast` |
| `LogMessage` | value object | time, level, thread, text |
| `LogFormatter` | Strategy | message → string |
| `Appender` | Strategy | where a message goes |
| `LevelHandler` | Chain of Responsibility | threshold + appender + next |
| `Logger` | Singleton (holder idiom) + facade | API (`info`, `error`), global filter, entry to the chain |

### Class diagram

```text
 +-----------------------------+         +------------------------------+
 | Logger  (singleton)         |  head   | LevelHandler                 |
 | - minLevel (volatile)       |-------->| - threshold: LogLevel        |---next---+
 | - chainHead (volatile)      |         | - appender: Appender         |          |
 | + info/warn/error/debug     |         | + handle(m)                  |<---------+
 | + configure(min, handlers..)|         +------------------------------+
 +-----------------------------+                       |
                                                       v
                                         +-----------------------------+
                                         | <<interface>> Appender      |
                                         | + append(LogMessage)        |
                                         +-----------------------------+
                                            ^                      ^
                                   ConsoleAppender           InMemoryAppender
                                   (uses LogFormatter)       (file / remote stand-in)
```

### Key code

The level enum relies on declaration order:

```java
enum LogLevel {
    DEBUG, INFO, WARN, ERROR;                  // ordinal order = severity order

    boolean atLeast(LogLevel other) { return compareTo(other) >= 0; }
}
```

The chain link writes if the message is severe enough, then passes it on (the "pipeline" flavor
of chain of responsibility, because several destinations may want the same message):

```java
    void handle(LogMessage m) {
        if (m.level().atLeast(threshold)) appender.append(m);
        if (next != null) next.handle(m);
    }
```

The logger filters early and reads its configuration through `volatile` fields, so a
reconfiguration from one thread is seen by all logging threads without locking every log call:

```java
    private void log(LogLevel level, String msg) {
        if (!level.atLeast(minLevel)) return;            // cheap early exit
        LevelHandler head = chainHead;
        if (head == null) return;
        head.handle(new LogMessage(Instant.now(), level, Thread.currentThread().getName(), msg));
    }
```

Copying `chainHead` into a local variable means the method uses one consistent chain even if
`configure` swaps it halfway through.

```text
[INFO] [main] server started on 8080
[WARN] [main] slow query 1200 ms
[ERROR] [main] payment gateway timeout
error store holds: [ERROR payment gateway timeout]
[ERROR] [main] disk 95% full
error store now has 2 lines
```

### Concurrency and performance

- `ConsoleAppender.append` is `synchronized` so lines from different threads do not interleave
  mid-line.
- Real frameworks avoid making the caller wait for I/O: an **async appender** puts messages on a
  bounded `BlockingQueue` and one background thread writes them (producer-consumer, chapter 26).
  When the queue is full you must choose: block the caller, or drop messages (usually DEBUG/INFO
  first). Say this choice out loud.
- The early `minLevel` check matters: building the message string costs more than the check.
  That is why real APIs accept a format string with arguments, or a lambda, so the string is only
  built if the level is enabled.

### Extensions and follow-ups

| Extension | Change |
|---|---|
| File appender with rotation by size/date | New `Appender`; rotation policy as a strategy |
| JSON output | New `LogFormatter` |
| Per-package log levels | Logger becomes a tree of named loggers (`com.app.db`) inheriting levels from parents (Composite-like lookup) |
| Async writing | Decorator: `AsyncAppender(inner)` with a queue and worker thread |
| Testing code that logs | Configure an `InMemoryAppender` and assert on its lines |

- **Why is the singleton risky here?** Tests share global state; one test's configuration leaks
  into another. Provide a `configure`/reset method for tests, or inject a `Logger` where possible.
- **Classic vs pipeline chain?** In the classic version the first handler that matches handles the
  message and stops. For logging, the pipeline version is more useful because one message may go
  to several destinations.

---

## 25.9 Running the demos

```text
cd ~/learn-system-design/code
java ParkingLot.java          # park/unpark, lot full, fee, double-exit rejected
java ElevatorSystem.java      # two cars, LOOK scheduling, tick-by-tick output
java MovieBooking.java        # 3 concurrency tests: race, expiry, random overlapping
java Splitwise.java           # 4 expenses, 3 split types, simplified settlements
java LruCache.java            # O(1) LRU + 100k random ops vs reference model
java TicTacToe.java           # invalid move rejected, X wins
java LoggingFramework.java    # levels, handler chain, runtime level change
```

## 25.10 Recap

- Every case follows: requirements → classes → diagram → key code → extensions → follow-ups.
- Find **what varies** (allocation, pricing, scheduling, split type, eviction, appender) and put it
  behind an interface; keep everything else concrete.
- Find **where the race is**: check-then-act on a shared resource (spot, seat, room). Make it
  atomic with the smallest sensible lock (per lot, per show) and re-check after any wait.
- Put rules on the objects that own the data: `spot.canFit`, `seat.isFree(now)`,
  `slot.overlaps`, strategy-specific validation inside each split.
- Pass time in (an `Instant` parameter or an injected clock) so expiry and pricing are testable.
- Use integer minor units for money and make rounding explicit.
- Parking lot: allocation and pricing strategies; the floor-first allocation bug shows why.
- Elevator: LOOK reverses at the last request; two sorted sets split stops by direction.
- Movie booking: per-show lock, all-or-nothing holds, lazy expiry, payment outside the lock,
  conditional UPDATE when the state lives in a database.
- Splitwise: net balances sum to zero; greedy two-heap settlement takes at most n-1 transfers;
  the exact minimum is NP-hard.
- LRU: hash map + doubly linked list with sentinels; `LinkedHashMap` is the library shortcut.
- Logging: level enum, appender and formatter strategies, handler chain, volatile config,
  async appender as the performance answer.

## 25.11 Check yourself

1. In the parking lot, why must "find a free spot" and "assign it" be in the same critical
   section? What happens if only `assign` is synchronized?

<details><summary>Answer</summary>

Two gates can both run `findSpot` and receive the same free spot. If only `assign` is
synchronized, the second `assign` will throw "is occupied" (because the spot checks), so the
second driver gets an error even though other spots may be free. The atomic unit is "choose and
take"; it must be under one lock, or allocation must use an operation that atomically hands a
spot to exactly one caller (like polling a concurrent queue of free spots).

</details>

2. Why does LOOK use less travel than SCAN, and why does neither starve requests?

<details><summary>Answer</summary>

SCAN goes to the end of the building before reversing even if no request is there; LOOK reverses
at the last pending request in the current direction, saving the empty travel. Both serve all
requests in the current direction before reversing, so any request is served within at most one
full sweep each way, which bounds waiting time (unlike shortest-seek-first, which can keep
serving nearby requests forever).

</details>

3. In the movie booking design, a user holds seats, pays, and calls `confirm` after the hold
   expired and another user took the seat. Walk through what happens.

<details><summary>Answer</summary>

When the second user called `holdSeats`, `isFree(now)` returned true for the expired hold, so the
seat became HELD with the second user's hold id. When the first user calls `confirm`, the
re-check under the show's lock sees that the seat's `holdId` is no longer theirs (or the expiry
has passed) and throws `HoldExpiredException`. No seat is sold twice; the first user's payment
must be refunded, which in practice is handled by holding slightly longer than the payment timeout
and by reconciliation.

</details>

4. Why does the booking code check every seat first and only then mark them all as held?

<details><summary>Answer</summary>

To make multi-seat holds all-or-nothing without rollback logic. If it marked seats one by one and
the third seat was taken, it would have to undo the first two. Checking all under the lock first
guarantees that, if the check passes, the marking cannot fail, because no other thread can change
those seats while the lock is held.

</details>

5. Splitwise nets are A: -50, B: -50, C: +50, D: +50. What does the greedy produce? Then show a
   case where greedy is not optimal.

<details><summary>Answer</summary>

Greedy pairs the largest debtor with the largest creditor: for example A pays C 50 and B pays D 50
(the exact pairing depends on tie-breaking). Two transfers, which is optimal.

A case where greedy loses: A: -3, B: +3, C: +2, D: -4, E: +2. Optimal plan, 3 transfers: A pays
B 3, D pays C 2, D pays E 2 (it uses the two zero-sum groups {A, B} and {C, D, E}). Greedy
starts with the largest debtor D (4) and the largest creditor B (3): D pays B 3, which breaks
the natural group {A, B}. Then A pays C 2, and the remaining 1 + 1 owed by A and D go to E:
4 transfers. In general the minimum is n minus the maximum number of disjoint zero-sum groups,
and finding those groups is NP-hard. Greedy guarantees at most n-1; for small n an exact
answer needs a search over subsets (bitmask DP).

</details>

6. In the LRU cache, why does each node store its key, and what breaks if you use a singly linked
   list?

<details><summary>Answer</summary>

On eviction you remove the tail node and must also remove its map entry, which requires the key;
storing it on the node makes that O(1). With a singly linked list, unlinking a node from the
middle (on every `get`) needs its predecessor, which takes an O(n) walk, breaking the O(1)
requirement.

</details>

7. How do the tic-tac-toe counters detect a win in O(1), and why can a counter not reach N
   without a real win?

<details><summary>Answer</summary>

Each move adds +1 (X) or -1 (O) to its row counter, column counter, and the diagonal counters it
lies on. A line has exactly N cells and each cell is filled at most once, so the counter can reach
+N only if all N cells are X, and -N only if all are O. Checking the at most four affected
counters after a move is O(1).

</details>

8. Why does the logger copy `chainHead` into a local variable before using it, and why are the
   config fields `volatile`?

<details><summary>Answer</summary>

`volatile` guarantees that when one thread reconfigures the logger, logging threads see the new
min level and chain promptly instead of a stale cached value. Copying `chainHead` into a local
reads the field once, so a single log call works on one consistent chain even if another thread
calls `configure` at the same moment; reading the field twice could see two different chains.

</details>
