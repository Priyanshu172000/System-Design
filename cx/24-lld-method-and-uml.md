# 24 — The LLD method and UML you need

*Why it matters:* a repeatable method turns a one-line problem into working, extensible code in an hour, and a light notation lets you draw and explain it.

## What the LLD round grades

**How it works** · Also called the **object-oriented design round** or **machine coding round**
(the latter usually means running code, often in 90 minutes). Signals, roughly by weight:

| Signal | Done well |
|---|---|
| Requirement handling | focused questions, written scope, explicit out-of-scope |
| Modeling | classes map to real concepts, one responsibility each, sensible entities vs value objects |
| Extensibility | what will change sits behind interfaces; a new requirement is a new class |
| Correctness | main flows work; invalid input and edge cases handled |
| Code quality | readable names, small methods, encapsulated state, no god class |
| Concurrency awareness | you can point at the race (two users, one seat) and fix it |
| Communication | trade-offs explained as you go; good answers to "what if..." |

**Example** · "prefer rooms with video?" at minute 50. Weak: "an `if (needsVideo)` in `book()`".
Strong: "a new `RoomSelectionStrategy`; `Room` gains features; `book` unchanged". So: *where* the
change lands is graded; pattern count, UML perfection, frameworks are not. Running code wins.

## The 10-step method, walked on one problem

**How it works** · (1) clarify and scope, (2) use cases, (3) nouns -> classes, verbs -> methods,
(4) classify, (5) relationships, (6) what varies -> interfaces, (7) class diagram, (8) core code:
model -> interfaces -> in-memory impls -> service, (9) demo main, (10) extensions and
concurrency. Steps 1–7 take roughly a quarter of the time. Each step produces a concrete
artifact. Problem: **"Design a meeting room booking system."**

**Example** · Step 1, ask only design-changing questions: who books, do admins add rooms? does
the user pick the room or do we, for N people? recurring? cancellation, by whom? many buildings?
concurrent users? persistence? Then write the scope down, visibly:

```text
IN SCOPE                                       OUT OF SCOPE (mention, don't build)
- rooms with capacity                          - recurring bookings
- book: attendees + time slot, system picks    - calendar invites / notifications
  the room (smallest that fits)                - multiple buildings
- cancel own booking                           - authentication
- no double booking, even under concurrency    - persistence (in-memory repos)
```

**Example** · Steps 2–3, use cases (each becomes a service method), then underline nouns/verbs:

```text
Employee: book a room for N people in a time slot | cancel own booking | (view own)
Admin:    add a room
"An EMPLOYEE BOOKS a ROOM for ATTENDEES in a TIME SLOT; the SYSTEM PICKS the smallest
 free room that fits. An employee can CANCEL their BOOKING."
Nouns: Employee, Room, Attendees, TimeSlot, Booking, System   Verbs: book, pick, cancel
Filter: System = BookingService | Attendees = an int | Employee = String userId (auth is
        out; promote to a class only if it gains behavior) -> Room, TimeSlot, Booking
```

**Example** · Steps 4–6, classify, relate, put what varies behind interfaces:

| Candidate | Kind | Relationship / why |
|---|---|---|
| `Booking` | **Entity** (identity + lifecycle) | CONFIRMED -> CANCELLED; `Booking * --- 1 Room` via `roomId` (like a foreign key) |
| `Room` | Entity | id, name, capacity |
| `TimeSlot` | **Value object** (immutable, self-validating) | `Booking 1 <#> 1 TimeSlot` (composition) |
| `BookingService` | **Service** (collaborators, no business data) | holds 1 of each below, injected |
| `RoomRepository`, `BookingRepository` | **Repository** | varies: in-memory now, DB later |
| `RoomSelectionStrategy` | **Strategy** | varies: smallest fit today, "near my desk" later |

Behavior goes where the data is. Overlap is the "merge intervals" check (half-open intervals
overlap iff each starts before the other ends); `Booking.cancelBy` owns "owner only, not twice":

```java
record TimeSlot(LocalDateTime start, LocalDateTime end) {          // value object
    TimeSlot {
        if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
    }
    // Half-open intervals [start, end): 9-10 and 10-11 do not overlap.
    boolean overlaps(TimeSlot o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }
}
```

So 09:00–10:00 vs 09:30–10:30: 09:00 < 10:30 and 09:30 < 10:00, overlap; vs 10:00–11:00:
10:00 < 10:00 is false, no overlap.

**Example** · Step 7, the class diagram (5–12 boxes; more means out-of-scope modeling):

```text
 BookingService ---------------------> <<interface>> RoomSelectionStrategy
  - rooms, bookings, strategy            + choose(rooms, n): Optional<Room>
  + book(user, n, slot): Booking                  ^ - - (realization)
  + cancel(bookingId, user)              SmallestFittingRoomStrategy
      |                 |
      v                 v                * stores
 <<interface>>     <<interface>>      -----------> Booking (id, roomId, userId, status)
 RoomRepository    BookingRepository               + cancelBy(user), blocks(room, slot)
      | * stores --> Room (id, name, capacity)     <#> 1 TimeSlot(start, end) +overlaps()
```

**Example** · Step 8, the service orchestrates; rules stay in the model; collaborators injected:

```java
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
        // ... create Booking, bookings.save(b), return b
    }
```

**Example** · Step 9, demo main: R-1 (cap 4), R-2 (12), R-3 (6); 5 people each (real output):

```text
b1 U-1 09:00-10:00 -> B-1[R-3 09:00-10:00 CONFIRMED]   R-1 too small; R-3 smallest fit
b2 U-2 09:30-10:30 -> B-2[R-2 09:30-10:30 CONFIRMED]   R-3 busy, next smallest fit
b3 U-3 09:00-10:00 -> rejected: no room for 5 people   R-3 and R-2 both blocked
cancel(B-1, U-1);  b3 again -> B-3[R-3 09:00-10:00 CONFIRMED]
```

**Example** · Step 10, the race that `synchronized` prevents, then the follow-ups:

```text
T1 book(U-1, 5, 9-10)                   T2 book(U-3, 5, 9-10)
findAll: R-3 free                       findAll: R-3 free (T1 not saved yet)
save B-1 on R-3                         save B-2 on R-3      -> double booking
```

| Follow-up | Answer in terms of the design |
|---|---|
| Prefer rooms with video | new `RoomSelectionStrategy`; `Room` gains a features set |
| Recurring bookings | `Recurrence` value object expands into `TimeSlot`s; book all-or-nothing |
| Store in a database / notify | new repository impls / service publishes `BookingConfirmed`, a notifier observes |
| Scale the check | per-room `TreeMap` of bookings by start time: overlap in O(log n) |
| Many concurrent users | `synchronized` serializes all bookings; finer: lock per room and re-check; with a DB, a unique constraint or conditional update (ch 26, 05) |

> **Say it like this:** "The critical section is 'check free, then save'. It's atomic under a
> lock on the service; per-room locks if contention matters; with a DB, a constraint catches it."

## UML cheat sheet (class, sequence, state)

**How it works** · **UML** is a standard notation; you need a small part, and ASCII is accepted.
Class box: name (optional `<<interface>>`/`<<abstract>>`), fields `- id: String`, methods
`+ book(n: int): Booking`. Visibility `+` public, `-` private, `#` protected, `~` package;
static is underlined in real UML (write `{static}`).

```text
  Dependency    A - - - - - > B    A uses B briefly (parameter, local)
  Association   A ----------> B    A holds a reference (field)
  Aggregation   A <>--------- B    A has Bs; B can live alone   (hollow diamond at owner)
  Composition   A <#>-------- B    A owns B; B dies with A      (filled diamond at owner)
  Inheritance   A ---------|> B    A extends B                  (triangle at parent)
  Realization   A - - - - -|> B    A implements interface B
  Multiplicity: 1, 0..1, *, 1..*   Booking * --- 1 Room = "many bookings, one room each"
```

**Example** · three parking-lot sentences as arrows: "a lot creates and owns its floors" ->
`ParkingLot 1 <#>---- 1..* Floor`; "a ticket refers to a vehicle" -> `Ticket * ----> 1 Vehicle`;
"`HourlyPricing` implements `PricingStrategy`" -> `HourlyPricing - - -|> PricingStrategy`. So:
diamond on the **whole**, triangle at the **parent/interface**; say your ASCII convention.

**Example** · a **sequence diagram** (for "walk me through..." or when call order matters) and a
**state diagram** (any lifecycle; tells you enum vs State pattern) for the same booking:

```text
 User        BookingService     BookingRepo   RoomRepo   Strategy
  |-book(U-1,5)->|--[lock]         |            |           |
  |              |--findAll()----->|  <-- bookings          |
  |              |--findAll()-------------------->| <-- rooms|
  |              |--choose(free, 5)----------------------->| <-- R-3
  |<-- B-1 ------|--save(B-1)----->|--[unlock]   alt [no room]: <-- NoRoomAvailable end
 [start] --book()--> CONFIRMED --cancelBy(owner)--> CANCELLED --> [end]
                        +--(meeting time passes)--> COMPLETED --> [end]
 rejected: cancelBy(non-owner) stays CONFIRMED; cancelBy() on CANCELLED (each a test)
```

## Packages, layers and naming

**How it works** · Packages `model/` (entities, value objects, enums), `repository/`,
`strategy/`, `service/`, `exception/`, `Main`. Dependencies point inward: `Main -> service ->
model`; service also depends on repository/strategy **interfaces**; model depends on nothing.
Layers add files: in 60 minutes use one file with section comments ("these would be packages").
Naming: singular domain nouns (`ParkingSpot`, not `SpotObj`), roles for interfaces
(`PricingStrategy`, not `IPricing`), impls say what differs (`HourlyPricing`, not `PricingImpl`),
intent verbs, question booleans (`isFree`, `canFit`), exceptions say what went wrong
(`SeatUnavailableException`). `Manager`, `Helper`, `Util`, `Processor` signal low cohesion.

**Example** · spotting upside-down layering and weak names in a review:

```text
model/Booking.java:  import service.BookingService;    <- model depends on service: invert
service/BookingManager.process(req)                   <- "Manager" + "process" say nothing
rename -> BookingService.book(userId, n, slot); Booking.cancelBy(user); isFree(slot)
```

## Common mistakes

**How it works** · Most LLD mistakes put rules in the wrong place or add structure nothing needs.

| Mistake | Symptom | Fix |
|---|---|---|
| Skipping scope | 10 min in: "what about cancellations?" and no lifecycle | 2 minutes of questions saves 20 of rework |
| **God class** | `BookingManager` holds every map and rule; untestable | rules to entities (`spot.canFit`), swappable rules to strategies, thin service |
| **Anemic domain model**, getters/setters for everything | entities are field bags; encapsulation gone | rich entity guards itself; getter when needed, setter almost never; DTOs at API boundaries are fine anemic |
| Over-patterning | 5 patterns in a parking lot | each pattern must answer "what change does this make cheap?" |
| Enum + switches everywhere | `switch (type)` for fee, size, name in 4 classes | data -> enum fields; growing behavior -> classes |

**Example** · ticket T-1 is closed, then outside code meddles (output of `ModelingChoices.java`):
anemic `t.setStatus("OPEN")` -> status OPEN, nothing stopped it; rich `r.close("fixed in v2")`
then `r.close("again")` -> refused: T-1 already closed. Enum vs polymorphism: ask what changes
more often, the **set of variants** or the **set of operations**.

| Situation | Choose | Example |
|---|---|---|
| Fixed set, only data differs | enum with fields | `SpotSize(maxWheels)`: SMALL 2, MEDIUM 4, LARGE 6 |
| Fixed set, small behavior per value | enum with constant-specific methods | `Operation.ADD.apply(7, 3)` = 10 |
| Fixed set of types with different data; new operations often | sealed interface + records + exhaustive `switch` | `Payment` = `Card`/`Upi`/`Wallet` |
| Open set, new variants often | interface + classes (Strategy) | `PricingStrategy`, `SplitStrategy` |

```java
sealed interface Payment permits Card, Upi, Wallet {}
record Card(String last4, long amountPaise) implements Payment {}
// ...
    static long fee(Payment p) {
        return switch (p) {                       // compiler checks every subtype is handled
            case Card c -> c.amountPaise() * 2 / 100;          // 2%
            case Upi u -> 0;
            case Wallet w -> Math.min(w.amountPaise() / 100, 1_000);
        };
    }
```

**Example** · `Card("4111", 50_000)` -> 1000 paise; `Upi("a@bank", 20_000)` -> 0;
`Wallet(5_000)` -> 50. Add `Crypto` to `permits` and every such switch stops compiling until it
handles `Crypto`. So: a **sealed interface** makes "forgot a case" a compile error.

## Talking through it and the time plan

**How it works** · Narrate decisions, not keystrokes: **what, why, trade-off**. When challenged,
restate the trade-off and offer the alternative instead of defending. When unsure, say what you
would check and how you would decide. **60 min:** 0-5 scope, 5-15 use cases/classes/diagram,
15-45 core code (2-3 use cases), 45-55 demo/extensions/concurrency, then Q&A. **90 min:** 0-10
scope, 10-20 model + diagram, 20-70 code in layers (compile/run every ~15 min), 70-80 demo and
edge cases, 80-90 extensions and Q&A.

**Example** · "why not just a map in the service?" -> "Simpler and fine at this size; I chose
the repository so persistence can change independently. Happy to inline it." Minute 20 of 60
with a perfect diagram and no code: freeze it; `TimeSlot`, `Room`, `Booking`, one repo, `book()`
and a 3-line main by ~35; `cancel` by ~45; describe the rest against the diagram. Rules:
**compile early and often** (three running features beat six that do not compile); **happy path
first**; **leave hooks, not implementations** (interface + one impl + comment).

## In one minute

- Graded: scope, modeling, extensibility, correctness, code quality, concurrency, communication.
- Method: scope, use cases, nouns/verbs, classify, relate, what varies, diagram, code, demo, extend.
- Rules live on the object owning the data; refer to entities by id; interfaces only for change.
- UML: `+ - # ~`; diamond at owner (hollow/filled), triangle at parent; sequence/state diagrams.
- Model depends on nothing. Avoid god classes, anemic models, reflexive setters, over-patterning.
- Enum for fixed data, sealed + switch for fixed types, interface for open variant sets.

## Go deeper

Full chapter 24: sections 24.10–24.12 (core code, demo, concurrency), 24.13–24.15 (UML notation), 24.18 (common mistakes, enum vs polymorphism); demos `MeetingRoomBooking.java`, `ModelingChoices.java` in `code/`.
