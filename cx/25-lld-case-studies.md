# 25 — LLD case studies

*Why it matters:* these seven problems are most of what LLD and machine coding rounds ask; each one drills the same two questions, what varies and where the race is.

## The template every case follows

**How it works** · Requirements (functional + non-functional) -> classes with one-line jobs ->
class diagram (5–12 boxes) -> the 2–3 methods with the real logic -> extensions ("which class
changes") -> follow-ups. Ask: **what varies** (an interface) and **where is the race** (a lock or
atomic op). Each design is a runnable Java 21 file in `code/`, named under each heading.

**Example** · the two questions, before any code. Parking: varies = allocation + pricing; race =
two gates, one spot (one lock over find + take). Elevator: car selection; per-car lock. Booking:
pricing; per-show lock. Splitwise: split type; per-group lock. LRU: eviction; one lock (`get`
mutates). Logging: appender/formatter; volatile config.

## Parking lot

**How it works** · `ParkingLot.java`. *Scope:* motorbike/car/truck; small/medium/large spots, a
vehicle fits any spot at least its size; many floors and gates at once (thread-safe); hourly
pricing by type, every started hour, minimum one. Out: payments, display boards, reservations.
Classes: `VehicleType` (carries `minSize`), `SpotSize` (smallest first); records `Vehicle`,
`Floor`, `Ticket`; `ParkingSpot` (`canFit`, `assign` rejects occupied/too small, `release`);
`ParkingLotService` (park, unpark, free count).
**Varies:** allocation (`SmallestFitNearestStrategy`: smallest fitting size, then lowest floor)
and pricing (`HourlyPricing`, `Math.max(1, (minutes + 59) / 60)` = integer ceil). `canFit` is
`size.compareTo(v.type().minSize) >= 0` (enum declaration order).

```java
    // synchronized: "find a free spot" + "take it" is one atomic step (else two gates share a spot)
    synchronized Ticket park(Vehicle v, Instant now) {
        ParkingSpot spot = allocation.findSpot(floors, v)
                .orElseThrow(() -> new LotFullException("no spot for " + v.type()));
        spot.assign(v);
        Ticket t = new Ticket("T-" + ticketSeq.getAndIncrement(), v, spot.id(), now);
        activeTickets.put(t.id(), t);
        return t;
    }
```

**Example** · floor 0 = F0-S1 (small), F0-M1 (medium), F0-L1 (large); floor 1 = F1-M1, F1-M2;
car rate 5000 paise/h; all arrive at 09:00 (real run of `ParkingLot.java`):

```text
bike KA-01-B-1   -> F0-S1  T-1   smallest size that fits
car  KA-01-C-1   -> F0-M1  T-2   medium, lowest floor
car  KA-01-C-2   -> F1-M1  T-3   F0-L1 is free and nearer, but medium < large wins
truck KA-01-T-1  -> F0-L1  T-4   the large spot was saved for it; free spots: 1
car3 -> F1-M2;  car4 -> rejected "no spot for CAR"
11:10 unpark T-2: 130 min -> (130+59)/60 = 3 h x 5000 = 15000 paise (Rs 150); F0-M1 freed
car4 -> F0-M1;  unpark T-2 again -> "unknown or closed ticket T-2" (double exit fails)
```

So: under the first demo's "lowest floor first", C-2 took F0-L1 and stranded the truck; swapping
only the strategy fixed it. Passing `now` in makes the fee testable. One lock suffices (a few
cars/s per gate); else lock per floor/size with re-check, or a `ConcurrentLinkedQueue` of free
spots per size (`poll()` hands a spot to one caller). Locking only `assign` fails: two gates
pick the same spot and one gets "occupied" though others are free.

- *Extensions:* EV spots (features + strategy), weekend pricing (strategy or surcharge decorator),
  boards (Observer), payments (adapters), reservations (`RESERVED` + expiry), lost ticket
  (`Map<plate, ticketId>`). *Follow-ups:* Ticket is a record (immutable after creation); no
  singleton lot (tests want several); O(1) free spot = queue/`TreeSet` per size.

## Elevator system

**How it works** · `ElevatorSystem.java`. *Scope:* N floors, M cars; **hall calls** (floor +
UP/DOWN) assigned to one car by a pluggable dispatcher; **car calls** (destination inside);
pluggable stop scheduling. Out: weight, door timing, emergency mode. Like disk-arm scheduling:
**FCFS** (arrival order, zig-zags), **SSTF** (nearest first, far floors starve), **SCAN** (sweep
to the building's end, reverse; wasted travel), **LOOK** (reverse at the last **request**; same
fairness, less travel; what real elevators approximate). E.g. requests 2, 5, 8, car at 4 going
UP, floors 0..9: SCAN 4 -> 5 -> 8 -> 9 -> 2 travels 12; LOOK 4 -> 5 -> 8 -> 2 travels 10.

Classes: `Direction` (enum: IDLE -> UP/DOWN, reverse when nothing ahead), `ElevatorCar` (floor,
dir, `upStops`/`downStops` as `TreeSet<Integer>`, `step()`), `NearestSuitableCarStrategy` (cost =
distance, + `2 * floors` if moving away or the other way), `ElevatorController` (calls, `tick()`).
**Two sorted sets**: a DOWN caller at 5 must not board a car going up; `ceiling`/`floor` = O(log n).

```java
    private Integer nextTarget() {          // LOOK
        if (dir == Direction.UP) {
            Integer a = upStops.ceiling(floor);
            if (a != null) return a;
            if (!downStops.isEmpty() && downStops.last() >= floor) return downStops.last();
        } // ... DOWN mirrors: downStops.floor(floor), else upStops.first() if <= floor
        return null;                         // nothing ahead: caller reverses direction
    }
```

**Example** · one car idle at floor 1; hall calls 3 UP, 7 DOWN, 5 UP; at t=3 someone presses
4 DOWN; the rider who boards at 7 presses 2 (verified run of the real classes):

```text
start  up={3,5} down={7}   IDLE -> nearest stop 3 is above -> UP
t=2  @3 UP OPEN     ceiling(2)=3            (t=3: 4 DOWN added, down={4,7})
t=3  @4 UP          passing 4: only upStops served, so 4 DOWN does NOT board
t=4  @5 UP OPEN     ceiling(4)=5
t=6  @7 UP OPEN     up empty; down.last()=7 >= 6 -> furthest opposite call ahead
t=7  @6 DOWN        button 2 -> down={2,4}; at 7 nothing above -> reverse (LOOK turn)
t=9  @4 DOWN OPEN   down.floor(7)=4: served on the way down, as its rider wanted
t=11 @2 DOWN OPEN   then idle.   Visiting order: 3, 5, 7, 4, 2
```

`step()`: no work -> IDLE; from IDLE head to the nearest stop; no target -> reverse; move one
floor; at the target serve both sets (`upStops.remove(floor) | downStops.remove(floor)`: `|` so
both run), passing through only the same-direction set. Ticks keep it testable without threads.
Real concurrency: lock per car, or a per-car `BlockingQueue` drained by its thread; the strategy
only reads positions, so stale reads just give a worse assignment.

- *Extensions:* express cars (served floors), peak mode (strategy + lobby parking), weight limit
  (skip hall calls when full), doors (State), replay (Command). *Follow-ups:* LOOK never starves
  (one round trip at most); the controller re-dispatches a broken car's hall calls.

## Movie ticket booking

**How it works** · `MovieBooking.java`. *Scope:* a **show** = movie + screen + time with its own
seat map; seats are **held** (say 10 minutes) during payment; success -> **booking**, failure or
expiry -> free; multi-seat is all-or-nothing; never sell a seat twice. Out: search, cities.
AVAILABLE --hold--> HELD(holdId, expiresAt) --confirm ok--> BOOKED, or back to free. **Lazy
expiry:** no timer; an expired hold is treated as free when checked (no scheduler races; a
cleanup job may tidy). Classes: `Movie`, `Seat`, `Screen` (records), `SeatType`, `Show` (seat
states + **the lock**), `ShowSeat` (status, holdId, holdExpiresAt), `Hold`, `Booking`,
`BookingService`, injected `LongSupplier` clock. `isFree(now)` = `AVAILABLE || (HELD && expired)`:

```java
        show.lock.lock();
        try {
            for (String id : seatIds) {                       // 1) check all
                ShowSeat s = show.seats.get(id);
                if (s == null || !s.isFree(now)) throw new SeatUnavailableException(id + " not available");
            }
            // 2) then take all: status = HELD, holdId, holdExpiresAt = now + holdMillis; return hold
        } finally {
            show.lock.unlock();
        }
```

**Example** · U-1 and U-2 both tap seat C7 of the 7 pm show; 10-minute holds (verified run):

```text
T1 U-1 holdSeats([C7]) at 18:40:00      T2 U-2 holdSeats([C7]) at 18:40:00
lock acquired; C7 AVAILABLE             lock.lock() ... waits
C7 -> HELD H-2, expires 18:50:00; unlock
                                        lock acquired; C7 HELD, not expired
                                        -> SeatUnavailableException "C7 not available"
U-1's payment stalls. 18:51:00 U-2 retries: isFree = HELD && 18:50 <= 18:51 -> H-3
18:51:05 U-1 confirm(H-2, paid): re-check sees holdId H-3 -> HoldExpiredException
U-2 confirm(H-3, paid) -> Booking B-4 [C7], 25000 paise. C7 sold once; U-1 refunded
```

Check-all-then-take-all under one lock = no partial holds to roll back. `confirm(holdId, paid)`
runs after payment (**never pay under the lock**: seconds-long, the show blocks), re-locks and
**re-validates** each seat (HELD, our `holdId`, not expired); failed payment -> release, else
BOOKED. **Per-show lock**: shows never contend, few hundred seats, microseconds held; per-seat
locks need sorted acquisition to avoid deadlock. Tests: 50 racers on A1+A2 behind a
`CountDownLatch(1)` gate -> 1 winner; fake clock past expiry -> released; 400 users on random
pairs, ~20% failed payments -> no seat sold twice.

```sql
-- Many app servers: a Java lock covers one process; the database checks atomically
UPDATE show_seat SET status = 'HELD', hold_id = ?, hold_expires_at = ?
 WHERE show_id = ? AND seat_id IN (?, ?)
   AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= NOW()));
-- rows < seats -> roll back; or SELECT..FOR UPDATE first; or TTL holds in a KV store
```

- *Extensions:* dynamic pricing (strategy per show), "no single gaps" (`SeatSelectionValidator`),
  notifications (Observer), cancel/refund (state machine), waitlist. *Follow-ups:* a synchronized
  service makes all shows wait; paid but expired -> refund (hold longer than payment timeout).

> **Say it like this:** "Seats are held all-or-nothing under the show's lock with lazy expiry; pay
> outside the lock, confirm re-validates. Across servers, the check becomes a conditional UPDATE."

## Splitwise

**How it works** · `Splitwise.java`. *Scope:* expenses paid by one, shared by several; splits
**equal**, **exact**, **percent** (more later); net per user; **simplify debts**. Out: groups,
currencies, history edits. Money is `long` paise (doubles cannot promise shares sum exactly).
Classes: `User`, `Expense` (stores shares), `SplitStrategy` (**varies**) -> `EqualSplit`/
`ExactSplit`/`PercentSplit` (each validates input), `Settlement`, `ExpenseService`. An expense:
payer's net += amount, each participant's net -= share; nets sum to 0. `EqualSplit` gives
`total / n` each and the `total % n` leftover paise to the first few. **Settle-up, greedy with
two max-heaps:** biggest debtor pays biggest creditor `min(credit, debt)`; push back remainders.

```java
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            long[] c = creditors.poll();
            long[] d = debtors.poll();
            long pay = Math.min(c[1], d[1]);
            out.add(new Settlement(ids.get((int) d[0]), ids.get((int) c[0]), pay));
            if (c[1] > pay) creditors.add(new long[]{c[0], c[1] - pay});
            if (d[1] > pay) debtors.add(new long[]{d[0], d[1] - pay});
        }
```

**Example** · friends A, B, C; nets in rupees after each expense (verified run):

```text
dinner  A paid 1000.00, EQUAL A,B,C: shares 333.34 / 333.33 / 333.33
        nets A +666.66  B -333.33  C -333.33
cab     B paid 600.00, EXACT A 100, B 200, C 300
        nets A +566.66  B  +66.67  C -633.33
drinks  C paid 300.00, PERCENT A 50%, B 50% (150 each)
        nets A +416.66  B  -83.33  C -333.33      (sum = 0)
settle  heaps: creditors [A 416.66]  debtors [C 333.33, B 83.33]
        C pays A 333.33 -> A left 83.33;  B pays A 83.33 -> all zero.  2 transfers = n-1
```

Each step fully settles at least one person and the last settles itself (nets sum to zero): at
most **n-1 transfers**, O(n log n). Not always optimal: the minimum is n minus the max number of
disjoint zero-sum groups, NP-hard (contains subset sum); bitmask DP for small n. E.g. A -3, B +3,
C +2, D -4, E +2: optimal 3 ({A,B}, {C,D,E}), greedy 4 (D pays B 3 first, breaking {A,B}).

- *Extensions:* share-based split (new strategy only), groups, pairwise view (`Map<pair, Long>`),
  edit/delete (reverse stored shares), currencies (`Money`), `addExpense` synchronized per group.
- *Follow-ups:* store shares, since recomputing with a new rounding rule rewrites history;
  `ExactSplit` validates because rules are split-specific, the service only needs sum = total.

## LRU cache

**How it works** · `LruCache.java`. *Scope:* fixed capacity, generic `get`/`put` in **O(1)**; a
full `put` of a new key evicts the least recently used; `get` counts as a use. Map = O(1)
lookup, doubly linked list = O(1) move/remove given the node; map stores key -> node.

```java
    public void put(K key, V value) {
        Node<K, V> n = map.get(key);
        if (n != null) { n.value = value; moveToFront(n); return; }   // update, mark recent
        if (map.size() == capacity) {          // evict the least recently used
            Node<K, V> lru = tail.prev; unlink(lru); map.remove(lru.key);
        }
        Node<K, V> fresh = new Node<>(key, value);
        addAfterHead(fresh);
        map.put(key, fresh);
    }
    private void unlink(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; }
```

**Example** · capacity 3; most recent first, between sentinels (verified run):

```text
put(a,1)     head <-> a <-> tail
put(b,2)     head <-> b <-> a <-> tail
put(c,3)     head <-> c <-> b <-> a <-> tail          full
get(a) -> 1  head <-> a <-> c <-> b <-> tail          a moved to front
put(d,4)     head <-> d <-> a <-> c <-> tail          evict tail.prev = b, map.remove("b")
get(b)       miss (Optional.empty), list unchanged
put(c,30)    head <-> c <-> d <-> a <-> tail          update in place + move, no eviction
put(e,5)     head <-> e <-> c <-> d <-> tail          evict a
```

- **Sentinels**: no empty/end special cases. Node stores its **key** so eviction can remove the
  map entry; **doubly** linked since unlinking needs the predecessor. `get` returns `Optional<V>`.
- `LinkedHashMap(16, 0.75f, true)` + `removeEldestEntry` is the library LRU: mention it, then
  implement map + list (the demo checks 100k random ops against it). Per-entry overhead (entry +
  node with two pointers) can exceed small values, so production caches approximate LRU.
- *Extensions:* thread safety (one lock on get **and** put; ch 26), TTL (expired = miss), LFU
  (key -> node, freq -> list, `minFreq`, O(1)), `EvictionPolicy<K>` (Strategy), hit/miss metrics
  (Decorator), loading `get(key, loader)`.

## Tic-tac-toe (and Snake and Ladder)

**How it works** · `TicTacToe.java`. *Scope:* N x N, X and O alternate; reject off-board or taken
cells without advancing the turn; win (row, column, diagonal) or draw (full). Classes: `Symbol`,
`GameStatus`, `Player`, `Move` (values), `Board` (validates and places), `Game` (turns, counters).
**O(1) win check:** X adds +1, O -1 to its row, column, diagonal(s); |counter| = N only if one
player filled the line (`if (r == c) diag += d; if (r + c == n - 1) antiDiag += d;`); else DRAW
when `movesMade == n * n`. `board.place` throws before any state change (invalid move = no change).

**Example** · 3 x 3, counters after each move (real demo moves):

```text
X (0,0): rows[0]=1  cols[0]=1  diag=1
O (1,1): rows[1]=-1 cols[1]=-1 diag=0  anti=-1
X (0,1): rows[0]=2  cols[1]=0
O tries (0,1): rejected "cell taken"; no counter changes, still O's turn
O (2,2): rows[2]=-1 cols[2]=-1 diag=-1
X (0,2): rows[0]=3 = n -> X_WON  (cols[2]=0, anti=0)
```

**Snake and Ladder** (sketch, not in code): `Board(size, Map<Integer,Integer> jumps)` (snakes and
ladders are one jump map); `Dice` interface (`RandomDice`, `FixedSequenceDice` for tests);
`Queue<Player>` turns: poll, roll, overshoot = stay (or bounce, clarify; 97 + 6 = 103 > 100:
stay), apply jump, reach `size` = winner, else re-offer. *Follow-ups:* computer player
(`MoveStrategy`: human/random/minimax with alpha-beta); K in a row on N x N (scan 4 directions
from the last move, O(K)); undo (stack of `Move`s, reverse counters).

## Logging framework

**How it works** · `LoggingFramework.java`. *Scope:* DEBUG < INFO < WARN < ERROR with a cheap
global filter; several **appenders** (console, file, remote), each with its own minimum level;
pluggable format; runtime reconfiguration; thread-safe; one logger app-wide. Classes: `LogLevel`
(enum, ordinal = severity, `atLeast`), `LogMessage` (value), `LogFormatter` and `Appender`
(Strategy), `LevelHandler` (Chain of Responsibility: threshold + appender + next), `Logger`
(Singleton via holder idiom + facade). A link appends if `atLeast(threshold)`, then calls `next`.

```java
    private void log(LogLevel level, String msg) {
        if (!level.atLeast(minLevel)) return;            // cheap early exit
        LevelHandler head = chainHead;                   // volatile, read once
        if (head == null) return;
        head.handle(new LogMessage(Instant.now(), level, Thread.currentThread().getName(), msg));
    }
```

**Example** · min DEBUG; chain = console (INFO+) -> error store (ERROR only). Real demo:

```text
debug "cache warmed"            console: below INFO, skip | store: skip   -> dropped
info  "server started on 8080"  console: [INFO] [main] server started... | store: skip
error "payment gateway timeout" console: [ERROR] ...      | store: 1 line (both get it)
setMinLevel(ERROR)              one volatile write; every thread sees it next call
info  "this is filtered..."     early exit at the logger: no LogMessage built
error "disk 95% full"           console + store -> store now has 2 lines
```

- **volatile** config: seen by all threads without locking each call; the local copy = one
  consistent chain per call. Classic chain stops at the first match; logging wants the
  **pipeline** form. `ConsoleAppender.append` is synchronized (lines do not interleave).
- **Async appender**: bounded `BlockingQueue` + one writer thread; when full, block the caller or
  drop (DEBUG/INFO first), say which. The early level check exists because building the string
  costs more (hence format-args or lambda APIs).
- *Extensions:* rotating file appender, JSON formatter, per-package levels (tree of named loggers),
  `AsyncAppender(inner)` decorator, `InMemoryAppender` for tests. Singleton risk: tests share
  global state; provide configure/reset or inject a `Logger`.

## In one minute

- What varies goes behind an interface: allocation, pricing, split type, eviction, appender.
- The race is check-then-act on a shared resource: smallest sensible lock (per lot, per show),
  re-check after any wait, and push it to the database across servers.
- Rules on data owners (`spot.canFit`, `seat.isFree(now)`); pass time in; integer paise. Elevator:
  LOOK reverses at the last request; up/down `TreeSet`s split stops by direction.
- Booking: per-show lock, check-all-then-take-all holds, lazy expiry, payment outside the lock.
- Splitwise: two-heap greedy, at most n-1 transfers; LRU: map + doubly linked list + sentinels.

## Go deeper

Full chapter 25: sections 25.4 (movie booking and concurrency tests), 25.3 (elevator LOOK), 25.5 (Splitwise debt simplification), 25.8 (logging framework).
