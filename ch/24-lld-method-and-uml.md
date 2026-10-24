# 24 — The LLD method and UML you need

Knowing OOP and patterns is not enough to do well in a low-level design round. You also need a
repeatable method that turns a one-line problem into working, extensible code within an hour,
and a light notation to draw and explain it. This chapter gives you both, with a small
end-to-end example (meeting room booking) that you can run.

Code: `code/MeetingRoomBooking.java` (the method applied end to end) and
`code/ModelingChoices.java` (enum vs polymorphism, anemic vs rich model).

## 24.1 What the LLD round grades

The round goes by several names: **LLD round**, **object-oriented design round**, or **machine
coding round** (the last usually means you must produce running code, often in 90 minutes). The
problem is a small system: a parking lot, an elevator, a splitwise, a cache, a booking system.

What interviewers look for, roughly in order of weight:

| Signal | What it looks like when done well |
|---|---|
| Requirement handling | You asked focused questions, wrote down scope, and said what is out of scope |
| Modeling | Classes map to real concepts; each has a clear responsibility; entities vs value objects are sensible |
| Extensibility | The parts that will change sit behind interfaces; a new requirement is a new class |
| Correctness | The code works for the main flows and handles invalid input and edge cases |
| Code quality | Readable names, small methods, encapsulated state, no god class |
| Concurrency awareness | You can point at the race (two users, one seat) and show the fix |
| Communication | You explain trade-offs as you go and respond well to "what if..." |

What it does **not** grade: how many patterns you used, UML perfection, or framework knowledge.
Running code with a clear structure beats a beautiful diagram with no code.

> **Say it like this:** "I'll spend a few minutes on requirements and the class model, then
> write the core flows, and keep the parts that are likely to change behind interfaces."

## 24.2 The method at a glance

```text
  1  Clarify & scope            questions, write down in/out of scope
  2  Use cases                  actors + what they do
  3  Nouns -> classes,          first-cut list of candidates
     verbs -> methods
  4  Classify                   entities / value objects / services / repositories
  5  Relationships              who owns, who references, multiplicities
  6  What varies -> interfaces  strategies, states, adapters
  7  Class diagram              ASCII is fine; 5-12 boxes
  8  Core code                  model -> interfaces -> service -> main
  9  Demo main                  run the happy path and 1-2 failure paths
 10  Extensions & concurrency   "what if" answers, where locks go
```

Steps 1–7 should take roughly a quarter of the time. The rest is code and discussion. We will
walk through all ten on one example.

**The example problem:** "Design a meeting room booking system."

## 24.3 Step 1: clarify and scope

The problem statement is deliberately vague. Ask questions that change the design, not
questions you could answer yourself.

Good questions for the example:

- Who books: any employee? Do we need admins who add rooms? (actors)
- Does a booking specify a room, or do we pick one for the given number of attendees?
  (selection logic)
- Can bookings be recurring? (big scope change; usually out)
- Is cancellation needed? By whom? (lifecycle)
- Single office or many buildings? (model depth)
- Many users booking at once? (concurrency)
- In-memory is fine, or do we need persistence? (repositories)

Then write the scope down, visibly:

```text
IN SCOPE                                       OUT OF SCOPE (mention, don't build)
- rooms with capacity                          - recurring bookings
- book: attendees + time slot, system picks    - calendar invites / notifications
  the room (smallest that fits)                - multiple buildings
- cancel own booking                           - authentication
- no double booking, even under concurrency    - persistence (in-memory repos)
```

> **Common mistake:** Skipping this and starting to code. Ten minutes later the interviewer asks
> "what about cancellations?" and the model has no lifecycle. Two minutes of questions saves
> twenty minutes of rework.

## 24.4 Step 2: use cases

A **use case** is one goal an **actor** (a user role or external system) achieves with the
system. List them as verb phrases:

```text
  Employee:  book a room for N people in a time slot
             cancel own booking
             (view own bookings)
  Admin:     add a room
```

Each use case usually becomes one public method on a service. Pick the 2–3 core ones to
implement fully; the rest get signatures or a mention.

## 24.5 Step 3: nouns to classes, verbs to methods

Read the requirements and underline nouns and verbs.

"An **employee** **books** a **room** for a number of **attendees** in a **time slot**; the
**system** **picks** the smallest free room that fits. An employee can **cancel** their
**booking**."

| Nouns (candidate classes) | Verbs (candidate methods) |
|---|---|
| Employee/User, Room, Attendees, TimeSlot, Booking, System | book, pick (choose room), cancel |

Then filter:

- "System" is not a class; it is the service you are writing (`BookingService`).
- "Attendees" is just a number here, not a class.
- "Employee" is only an id for now (authentication is out of scope), so `String userId` is
  enough. Promote it to a class only if it gains behavior.

The output is a short list: `Room`, `TimeSlot`, `Booking`, `BookingService`, plus whatever the
next steps add.

## 24.6 Step 4: entities, value objects, services, repositories

Classify each candidate. This decides how it is written.

| Kind | Definition | In the example |
|---|---|---|
| **Entity** | Has identity and usually a lifecycle | `Booking` (CONFIRMED -> CANCELLED), `Room` |
| **Value object** | Immutable, equal by value, validates itself | `TimeSlot` |
| **Service** | Stateless(ish) coordinator of a use case; holds collaborators, not business data | `BookingService` |
| **Repository** | Stores and finds entities; hides the storage | `RoomRepository`, `BookingRepository` |
| **Strategy / policy** | A swappable rule | `RoomSelectionStrategy` |

Put behavior where the data is. "Does this time slot overlap that one?" belongs on `TimeSlot`:

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

DSA bridge: this is the interval-overlap check you know from "merge intervals": two half-open
intervals overlap if and only if each starts before the other ends.

"Can this booking be cancelled by this person?" belongs on `Booking`:

```java
    void cancelBy(String requester) {
        if (!requester.equals(userId)) throw new IllegalStateException("only the owner can cancel");
        if (status == BookingStatus.CANCELLED) throw new IllegalStateException("already cancelled");
        status = BookingStatus.CANCELLED;
    }
```

## 24.7 Step 5: relationships and multiplicities

For each pair of classes, ask: does one hold the other? Does one create the other? How many?

```text
  BookingService  1 ----> 1  RoomRepository           association (injected)
  BookingService  1 ----> 1  BookingRepository        association (injected)
  BookingService  1 ----> 1  RoomSelectionStrategy    association (injected)
  Booking         * ----> 1  Room                     by id (roomId), not by object
  Booking         1 <#>-> 1  TimeSlot                 composition (value inside entity)
```

A **multiplicity** says how many objects are on each end: `1`, `0..1`, `*` (many), `1..*`.

Note the choice "Booking refers to the room by id". Referencing other entities by id, rather than
holding the object, keeps entities small and mirrors how a database would store them (a foreign
key). Holding the object is fine when you always need it together.

## 24.8 Step 6: identify what varies, put it behind interfaces

Ask: "Which of these rules would a product manager change next quarter?" For the example:

- How a room is picked (smallest fit today; maybe "nearest to the user's desk" or "prefer rooms
  with a video setup" later) → `RoomSelectionStrategy`.
- Where data lives (in-memory now, a database later) → repository interfaces.

```java
interface RoomSelectionStrategy {
    Optional<Room> choose(List<Room> freeRoomsThatFit, int attendees);
}

class SmallestFittingRoomStrategy implements RoomSelectionStrategy {
    public Optional<Room> choose(List<Room> candidates, int attendees) {
        return candidates.stream().min(Comparator.comparingInt(Room::capacity));
    }
}
```

Everything else stays concrete. Not every class needs an interface (see over-patterning in
24.18).

## 24.9 Step 7: the class diagram

Draw it before coding; it is your plan and the interviewer's map. ASCII is fine.

```text
 +---------------------------------+        +------------------------------+
 | BookingService                  |        | <<interface>>                |
 +---------------------------------+  uses  | RoomSelectionStrategy        |
 | - rooms: RoomRepository         |------->+------------------------------+
 | - bookings: BookingRepository   |        | + choose(rooms, n): Optional |
 | - strategy: RoomSelectionStrat. |        +------------------------------+
 +---------------------------------+                       ^
 | + book(user, n, slot): Booking  |                       :  (realization)
 | + cancel(bookingId, user)       |        +------------------------------+
 +---------------------------------+        | SmallestFittingRoomStrategy  |
        |                    |              +------------------------------+
        v                    v
 +------------------+  +---------------------+         +--------------------+
 | <<interface>>    |  | <<interface>>       |         | Booking            |
 | RoomRepository   |  | BookingRepository   |-------->+--------------------+
 +------------------+  +---------------------+ stores  | - id, roomId,      |
 | + save(Room)     |  | + save(Booking)     |   *     |   userId           |
 | + findAll()      |  | + findById(id)      |         | - status           |
 +------------------+  | + findAll()         |         +--------------------+
        |              +---------------------+         | + cancelBy(user)   |
        | stores *                                     | + blocks(room,slot)|
        v                                              +--------------------+
 +------------------+                                       <#> 1
 | Room             |                                        |
 | - id, name,      |                                  +-------------+
 |   capacity       |                                  | TimeSlot    |
 +------------------+                                  | start, end  |
                                                       | +overlaps() |
                                                       +-------------+
```

Notation is explained in 24.13. Aim for 5–12 boxes; more than that in an interview usually means
you are modeling things out of scope.

## 24.10 Step 8: write the core code

Write in this order, because each layer only depends on the ones before it:

1. **Model**: enums, value objects, entities, with their own rules and validation.
2. **Interfaces**: repositories, strategies.
3. **Simple implementations**: in-memory repositories, one strategy.
4. **Service**: one method per core use case.

The service orchestrates; it does not contain rules that belong to the model:

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
        Booking b = new Booking("B-" + seq.getAndIncrement(), room.id(), userId, slot);
        bookings.save(b);
        return b;
    }
```

Dependencies arrive through the constructor (chapter 22), so the strategy and storage can be
swapped without touching this method.

## 24.11 Step 9: a demo main

A short `main` that runs the happy path and one or two failure paths proves the design works and
gives the interviewer something to read:

```java
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
```

Output:

```text
b1 -> B-1[R-3 09:00-10:00 CONFIRMED]
b2 -> B-2[R-2 09:30-10:30 CONFIRMED]  (R-3 busy, next smallest fit)
b3 rejected: no room for 5 people
after cancel, b3 -> B-3[R-3 09:00-10:00 CONFIRMED]
```

## 24.12 Step 10: extensions and concurrency

Prepare short answers to likely follow-ups. The point is to show which class changes:

| Follow-up | Answer in terms of the design |
|---|---|
| "Prefer rooms with video equipment" | New `RoomSelectionStrategy`; `Room` gains a set of features (value object) |
| "Recurring bookings" | A `Recurrence` value object expands into several `TimeSlot`s; book all-or-nothing |
| "Store in a database" | New repository implementations; service unchanged |
| "Notify attendees" | Service publishes a `BookingConfirmed` event; a notifier observes it (Observer) |
| "Scale the check" | Per-room index of bookings sorted by start time (a `TreeMap`), so overlap is O(log n) |
| "Many concurrent users" | See below |

**Concurrency.** The race is check-then-act: two requests both see R-3 free, then both save. The
demo makes `book` and `cancel` `synchronized`, which is correct and simple but serializes all
bookings. Finer options: a lock per room (lock only the chosen room, then re-check), or, with a
database, a unique constraint or conditional update so the database rejects the second writer
(chapter 26 and chapter 05).

> **Say it like this:** "The critical section is 'check the room is free, then save the
> booking'. I've made that atomic with a lock on the service; if contention mattered I'd lock
> per room, and with a database I'd let a constraint or a conditional update catch the race."

## 24.13 UML class diagram notation in ASCII

**UML** (Unified Modeling Language) is a standard set of diagram notations. You need only a small
part of it, and in interviews an ASCII approximation is accepted.

### A class box

```text
 +------------------------------+
 | <<interface>> / <<abstract>> |   optional stereotype
 | ClassName                    |   name (italic in real UML if abstract)
 +------------------------------+
 | - id: String                 |   fields:  visibility name: Type
 | - status: Status             |
 | # retries: int               |
 | ~ cache: Map<String, Room>   |
 | + MAX: int  {static}         |   static members are underlined in real UML
 +------------------------------+
 | + book(n: int): Booking      |   methods: visibility name(params): ReturnType
 | - validate(): void           |
 | # step(): boolean {abstract} |
 +------------------------------+
```

| Symbol | Visibility | Java |
|---|---|---|
| `+` | public | `public` |
| `-` | private | `private` |
| `#` | protected | `protected` |
| `~` | package | no modifier |

### Relationship arrows

```text
  Dependency       A - - - - - - > B     A uses B briefly (parameter, local)
  Association      A ------------> B     A holds a reference to B (field)
  Aggregation      A <>----------- B     A has B's; B can live alone   (hollow diamond at A)
  Composition      A <#>---------- B     A owns B; B dies with A       (filled diamond at A)
  Inheritance      A -----------|> B     A extends B                   (hollow triangle at B)
  Realization      A - - - - - -|> B     A implements interface B      (dashed + triangle)
```

The diamond sits on the **whole** (owner) side; the triangle points at the **parent** or
**interface**. In ASCII, `<>` for hollow and `<#>` (or `<*>`) for filled diamond are common
conventions; say which you use.

### Multiplicity

Written near each end of a line:

```text
  ParkingLot 1 <#>-------- 1..* Floor 1 <#>-------- 1..* ParkingSpot
  Booking    * ----------- 1    Room
  Order      1 <#>-------- 1..* OrderLine
```

Read `Booking * --- 1 Room` as "many bookings refer to one room; each booking has exactly one
room".

## 24.14 Sequence diagrams in ASCII

A **sequence diagram** shows one use case over time: participants across the top, time flowing
down, arrows for calls and returns. Use it when the interviewer asks "walk me through what
happens when..." or when the order of calls matters (locking, payment).

```text
 User        BookingService     BookingRepo    RoomRepo     RoomSelectionStrategy
  |  book(U-1,5,slot)  |              |            |                  |
  |------------------->|              |            |                  |
  |                    |--[lock acquired]          |                  |
  |                    | findAll()    |            |                  |
  |                    |------------->|            |                  |
  |                    |<-- bookings -|            |                  |
  |                    | findAll()                 |                  |
  |                    |-------------------------->|                  |
  |                    |<--------- rooms ----------|                  |
  |                    | filter: fits && not blocked                  |
  |                    | choose(free, 5)                              |
  |                    |--------------------------------------------->|
  |                    |<------------------ R-3 ----------------------|
  |                    | save(new Booking)                            |
  |                    |------------->|                               |
  |                    |--[lock released]                             |
  |<--- Booking B-1 ---|              |                               |
```

Conventions: solid arrow = call, `<---` = return, `[ ... ]` = a condition or note. You can mark
an alternative path with a labeled block:

```text
  | alt [no free room]                                  |
  |   |<-- NoRoomAvailableException --|                 |
  | end                                                 |
```

## 24.15 State diagrams

A **state diagram** shows the states an entity can be in and the events that move it between
states. Draw one whenever an entity has a lifecycle (booking, order, elevator, vending machine);
it directly tells you whether you need an enum or the State pattern (chapter 23).

```text
                 book()                 cancelBy(owner)
   [start] ---------------> CONFIRMED -----------------> CANCELLED ---> [end]
                               |
                               | (meeting time passes)
                               v
                           COMPLETED  ---> [end]

   Invalid: cancelBy(non-owner) -> rejected, stays CONFIRMED
            cancelBy() on CANCELLED -> rejected
```

For each state, ask: which events are allowed, what does each event do, which state comes next?
Every "rejected" line becomes a check (and a test) in code.

## 24.16 Package and layer structure

Even in a single-file interview solution, organize code as if it had packages. In a real project:

```text
 com.example.booking
 ├── model/          entities, value objects, enums   Booking, Room, TimeSlot
 ├── repository/     storage interfaces + impls       RoomRepository, InMemoryRoomRepo...
 ├── strategy/       swappable policies               RoomSelectionStrategy, ...
 ├── service/        use-case orchestration           BookingService
 ├── exception/      domain exceptions                NoRoomAvailableException
 └── Main.java       wiring + demo
```

Dependency direction should point inward:

```text
   Main  -->  service  -->  model
                |  \
                |   +--> repository (interfaces)  <-- InMemory... (implementations)
                +------> strategy   (interfaces)  <-- concrete strategies
   model depends on nothing else.
```

If `model` imports from `service`, the layering is upside down.

**Trade-off.** Layers add files and indirection. For a 60-minute round, one file with clear
section comments (`// ---------- model ----------`) shows the same structure at lower cost; say
"in a real codebase these would be packages".

## 24.17 Naming

Names are the cheapest documentation and interviewers notice them.

| Kind | Convention | Good | Weak |
|---|---|---|---|
| Classes | Noun, singular, domain term | `Booking`, `ParkingSpot` | `BookingData`, `SpotObj` |
| Interfaces | Capability or role | `PricingStrategy`, `RoomRepository` | `IPricing`, `PricingInterface` |
| Implementations | What makes it different | `HourlyPricing`, `InMemoryRoomRepository` | `PricingImpl` |
| Methods | Verb phrase, intent | `book`, `cancelBy`, `findFreeSpot` | `process`, `handle`, `doIt` |
| Booleans | Question form | `isFree`, `canFit`, `hasExpired` | `flag`, `status2` |
| Exceptions | What went wrong | `SeatUnavailableException` | `BookingException2` |

`Manager`, `Helper`, `Util`, and `Processor` in a class name are warning signs of low cohesion:
the name says "does stuff".

## 24.18 Common mistakes

### The god class

One class (`ParkingLotSystem`, `BookingManager`) holds every map, every rule, and every method.
It is untestable piece by piece and every change touches it.

**Fix:** move rules to the entities that own the data (`spot.canFit(vehicle)`,
`booking.cancelBy(user)`), move swappable rules to strategies, and keep the service as a thin
coordinator.

### Anemic models everywhere

An **anemic domain model** is one where entities are just fields with getters and setters, and
all logic lives in services. Nothing stops invalid states:

```java
// 4a. Anemic: a bag of getters/setters; rules live elsewhere (or nowhere).
class AnemicTicket {
    private String status;
    private String resolution;
    String getStatus() { return status; }
    void setStatus(String s) { status = s; }
    void setResolution(String r) { resolution = r; }
}
```

Any code can call `setStatus("OPEN")` on a closed ticket. The rich version guards itself:

```java
    void close(String resolution) {
        if (status == Status.CLOSED) throw new IllegalStateException(id + " already closed");
        if (resolution == null || resolution.isBlank()) throw new IllegalArgumentException("need a resolution");
        this.status = Status.CLOSED;
        this.resolution = resolution;
    }
```

**Trade-off.** Pure data carriers are right for some things: request/response objects at API
boundaries (often called **DTOs**, data transfer objects) and records passed between layers.
The mistake is making the core domain anemic.

### Getters and setters for everything

Generating a getter and setter per field by reflex destroys encapsulation (chapter 22). Add a
getter when someone outside genuinely needs to read the value; add a setter almost never; add a
meaningful operation instead.

### Over-patterning

Five patterns in a parking lot (abstract factory for vehicles, builder for a two-field ticket,
singleton lot, visitor for fees...) signals pattern-matching rather than design. Each pattern
must answer "what change does this make cheap?"

### Enums vs polymorphism: choosing correctly

Both "an enum" and "an interface with classes" model a set of variants. Choose by asking what
changes more often: **the set of variants** or **the set of operations**, and how much behavior
differs.

| Situation | Choose | Example |
|---|---|---|
| Fixed set, differences are only data | Enum with fields | `SpotSize(maxWheels)` |
| Fixed set, small behavior per value | Enum with constant-specific methods | `Operation.ADD.apply(a, b)` |
| Fixed set of types with different data; new operations added often | Sealed interface + records + exhaustive `switch` | `Payment` = `Card` / `Upi` / `Wallet` |
| Open set; new variants added often, possibly by others | Interface + classes (Strategy) | `PricingStrategy`, `SplitStrategy` |

```java
// 2. Fixed set, small behavior per value -> enum with an abstract method.
enum Operation {
    ADD("+") { int apply(int a, int b) { return a + b; } },
    SUB("-") { int apply(int a, int b) { return a - b; } },
    MUL("*") { int apply(int a, int b) { return a * b; } };

    final String symbol;
    Operation(String symbol) { this.symbol = symbol; }
    abstract int apply(int a, int b);
}
```

```java
// 3. Fixed set of types with different data -> sealed interface of records.
sealed interface Payment permits Card, Upi, Wallet {}
record Card(String last4, long amountPaise) implements Payment {}
record Upi(String vpa, long amountPaise) implements Payment {}
record Wallet(long amountPaise) implements Payment {}
```

```java
    // A switch over a sealed type: the compiler checks every subtype is handled.
    static long fee(Payment p) {
        return switch (p) {
            case Card c -> c.amountPaise() * 2 / 100;          // 2%
            case Upi u -> 0;
            case Wallet w -> Math.min(w.amountPaise() / 100, 1_000);
        };
    }
```

A **sealed interface** lists exactly which types may implement it (`permits`). Because the
compiler knows the full list, a `switch` with type patterns (`case Card c ->`) must cover every
case; adding a fourth payment type makes every such switch fail to compile until handled. That
turns "I forgot a case" from a runtime bug into a compile error.

The rule of thumb: an open set of variants that grows (pricing rules, split types) → interface;
a closed set where you keep adding operations (fee, refund rules, reporting) → sealed + switch;
pure data → enum.

> **Common mistake:** An enum `VehicleType` plus `switch (type)` for fee, spot size, display
> name, and toll in four different classes. When the differences are just data, put them as
> fields on the enum. When they are real behavior that grows, move to classes.

## 24.19 How to talk through your design

Narrate decisions, not keystrokes. A useful pattern for each decision: **what, why, trade-off**.

- "I'm making `TimeSlot` a value object with `overlaps` on it, so the overlap rule lives in one
  place and is easy to test."
- "Room selection is behind a strategy because that's the rule most likely to change; storage is
  behind repositories so we can move to a database without touching the service."
- "I'm referencing rooms by id from `Booking`, like a foreign key, to keep entities small."
- "This method is synchronized because check-then-save must be atomic. It serializes bookings;
  if that's a bottleneck I'd go to per-room locks."

When challenged ("why not just use a map in the service?"), do not defend reflexively. Restate
the trade-off and offer the alternative: "That's simpler and fine for this size; I chose the
repository so persistence can change independently. Happy to inline it if we want less code."

When you do not know: say what you would check and how you would decide.

> **Say it like this:** "Let me make sure I have the scope: in scope is X and Y, out of scope is
> Z. I'll start with the model, then the booking flow, and I'll flag where concurrency matters
> as we go."

## 24.20 Time plan

### 60-minute LLD round (design + partial code)

```text
 0 ----- 5 ------------ 15 -------------------------------- 45 ---------- 55 --- 60
 | scope |  use cases,  |  core code: model, interfaces,   |  demo/walk-  | Q&A  |
 |  Qs   |  classes,    |  service for 2-3 main use cases  |  through,    |      |
 |       |  diagram     |                                  |  extensions, |      |
 |       |              |                                  |  concurrency |      |
```

### 90-minute machine coding round (running code expected)

```text
 0 ---- 10 -------- 20 ------------------------------------------ 70 ------ 80 ---- 90
 | scope | model +  |  code in layers: model -> interfaces ->     | demo    | ext. |
 |  Qs   | diagram  |  in-memory impls -> service -> main         | main,   | Q&A  |
 |       |          |  (compile/run every ~15 min)                | edge    |      |
 |       |          |                                             | cases   |      |
```

Tips that apply to both:

- **Compile early, compile often.** A program that runs with three features beats one that does
  not compile with six.
- **Happy path first**, then validation and edge cases, then extensions.
- **Leave hooks, not implementations,** for out-of-scope items: an interface with one
  implementation and a comment is enough to show where a feature would go.
- If time runs short, stop coding and **describe** the remaining pieces against your diagram.

## 24.21 Running the demos

```text
cd ~/learn-system-design/code
java MeetingRoomBooking.java   # the ten-step method applied end to end
java ModelingChoices.java      # enum vs enum-with-behavior vs sealed vs interface;
                               # anemic vs rich model
```

## 24.22 Recap

- The LLD round grades requirement handling, modeling, extensibility, correctness, code quality,
  concurrency awareness, and communication; not pattern count.
- Method: scope → use cases → nouns/verbs → classify → relationships → what varies → diagram →
  code → demo → extensions.
- Classify candidates as entities, value objects, services, repositories, or strategies; put rules
  on the object that owns the data.
- Put only the parts likely to change behind interfaces.
- UML: `+ - # ~` for visibility; diamonds on the owner side (hollow = aggregation, filled =
  composition); triangles point at the parent or interface; dashed lines for dependency and
  realization.
- Sequence diagrams show call order for one use case; state diagrams show an entity's lifecycle.
- Layering: model depends on nothing; services depend on model and interfaces.
- Avoid god classes, anemic core models, reflexive setters, and over-patterning.
- Enum for fixed data, sealed + switch for fixed types with growing operations, interface for open
  variant sets.
- Budget time: about a quarter on design, most on code, and leave time to discuss extensions.

## 24.23 Check yourself

1. You have 60 minutes. At minute 20 you have a perfect class diagram and no code. What went wrong
   and what do you do now?

<details><summary>Answer</summary>

Too much time went into design before code; the plan budgets roughly 10–15 minutes for it. Now
freeze the diagram, implement the model and the single most important use case end to end with a
demo main, then add the next use case. Describe remaining parts verbally against the diagram if
time runs out.

</details>

2. Classify these for a food delivery app: `Order`, `Money`, `DeliveryAddress`,
   `OrderService`, `RestaurantRepository`, `DeliveryFeePolicy`.

<details><summary>Answer</summary>

`Order`: entity (id, lifecycle). `Money`: value object. `DeliveryAddress`: usually a value object
(unless saved addresses need identity). `OrderService`: service. `RestaurantRepository`:
repository. `DeliveryFeePolicy`: strategy/policy behind an interface.

</details>

3. Draw (in words) the UML arrow for: a `ParkingLot` that creates and owns its `Floor`s; a
   `Ticket` that refers to a `Vehicle`; `HourlyPricing` that implements `PricingStrategy`.

<details><summary>Answer</summary>

`ParkingLot <#>---- Floor` with a filled diamond at `ParkingLot` and multiplicity `1` to `1..*`
(composition). `Ticket ----> Vehicle`, a plain solid association arrow (multiplicity `*` to `1`).
`HourlyPricing - - -|> PricingStrategy`, a dashed line with a hollow triangle at the interface
(realization).

</details>

4. What is an anemic domain model, and when is a data-only class actually the right choice?

<details><summary>Answer</summary>

Entities that are only fields with getters and setters while all rules live in services, so
nothing prevents invalid states and rules get duplicated. Data-only classes are right for
boundary objects: API request/response DTOs, events, and records passed between layers, where
there are no invariants to protect.

</details>

5. Your design has `enum VehicleType` and four `switch (type)` statements in different classes.
   How do you decide what to change it to?

<details><summary>Answer</summary>

If the differences are just data (rate, required spot size), move them into fields on the enum and
delete the switches. If they are behavior and new vehicle types are added often, use an interface
with one class per type. If the set of types is fixed but operations keep being added, use a
sealed interface with records and exhaustive switches, so the compiler flags any switch that misses
a new case.

</details>

6. Why should the model layer not import anything from the service layer?

<details><summary>Answer</summary>

Dependencies should point toward the most stable code. The model expresses core business concepts
and rules; services coordinate use cases and change more often. If the model depends on services,
a change to a use case can break core types, the model cannot be reused or tested alone, and
cycles appear between packages.

</details>

7. In the meeting room example, where exactly is the race condition, and name two ways to fix it?

<details><summary>Answer</summary>

Between "find rooms that are not blocked" and "save the new booking": two threads can both see a
room as free and both save a booking for it. Fixes: make the check-and-save atomic with a lock
(the whole service as in the demo, or per room after choosing it, re-checking under the lock); or,
with a database, rely on a constraint or a conditional insert/update that fails for the second
writer, and retry or report.

</details>
