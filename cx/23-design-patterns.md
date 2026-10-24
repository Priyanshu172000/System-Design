# 23 — Design patterns that actually come up

*Why it matters:* a pattern's name carries a whole structure ("put a decorator around the client"),
and LLD interviewers give credit for naming the right one at the right moment.

## How to think about patterns

**How it works** · A **design pattern** is a named, reusable class shape for a recurring problem:
**creational** (creating objects), **structural** (composing/wrapping), **behavioral** (splitting
work, communicating). Rules: start from the pain, not the pattern; most patterns are "hold an
interface, delegate to it"; name it when you use it.

**Example** · "QuickBite gets a new surge/discount rule every month" -> Strategy; "status checks
are copy-pasted into every method" -> State; "I want to use Strategy somewhere" -> over-engineering.

> **Say it like this:** "I'm not adding patterns for their own sake. Pricing is the part that
> varies, so it goes behind a strategy interface; everything else stays plain classes."

## Creational patterns

**How it works** · Each centralizes or controls `new` (run `java CreationalPatterns.java`).

**Singleton** · *QuickBite:* one order-id generator per process, so two threads never hand out the
same id. *Structure:* private constructor + static access point. Simplest: `enum AppConfig {
INSTANCE; ... }` (JVM guarantees one, thread-safe, reflection/serialization-proof). Lazy: holder
idiom, or double-checked locking (DCL), which needs `volatile`:
```java
private static class Holder { static final IdGenerator INSTANCE = new IdGenerator(); }
static IdGenerator getInstance() { return Holder.INSTANCE; }   // class init runs once
private static volatile ConnectionRegistry instance;           // DCL variant
// ... if (local == null) { synchronized (ConnectionRegistry.class) {
//         local = instance; if (local == null) { create; instance = local; } } }
```
**Example** · Why `volatile` (the reference write may be reordered before the constructor ends; ch 26):
```text
T1: lock; allocate; instance = ref     (reordered: fields not yet written)
T2: instance != null -> skips the lock -> uses a half-built object
volatile: T2 sees ref only after the constructor's writes. Holder demo: ids 1, 2
```
*Pitfalls:* hidden global state, hard to test (deps invisible, no fake, state leaks between tests);
naive `if (instance == null) instance = new X();` can create two, so can reflection/serialization
(enum is immune); one per class loader (a cluster has one per process). *Use:* `Runtime.getRuntime()`,
logging config; DI "singleton scope" = one instance, no static access point. *Not when:* "only
one" is convenience, not a rule; create one in `main` and inject it.

**Simple Factory / Factory Method** · *QuickBite:* `notification-service` picks SMS, email or push
from the user's preference. A **simple factory** (not catalogued, but the one you write most) keeps
the `switch` in one place; an exhaustive enum `switch` won't compile until a new `WHATSAPP` is handled:
```java
return switch (type) {            // NotificationFactory.create: only place knowing the classes
    case EMAIL -> new EmailChannel();
    case SMS -> new SmsChannel();
    case PUSH -> new PushChannel();
};
```
**Example** · `NotificationFactory.create(SMS)` sends "SMS to U-1: Your OTP is 4821"; login code sees only the interface.
**Factory Method:** a superclass algorithm leaves product creation to an override. *Scenario:*
delivery planning that differs only in the vehicle (`Logistics.createTransport()`: `RoadLogistics`
gives `Truck`, `SeaLogistics` `Ship`). *Use:* `List.of`, `Iterable.iterator()`. *Not when:* one impl.

**Builder** · *QuickBite:* an Order, or `order-service`'s HTTP call to `payment-service`, has few
required and many optional fields; `new HttpCall(url, "POST", null, 1500, null, true, false)` is
unreadable, setters leave it mutable and half-valid.
```java
Builder method(String m) { this.method = m; return this; }     // return this = fluent chaining
HttpCall build() {                             // validate the whole object once
    if (timeoutMs <= 0) throw new IllegalStateException("timeout must be > 0");
    if (method.equals("GET") && body != null)
        throw new IllegalStateException("GET must not have a body");
    return new HttpCall(this);                 // private ctor copies into final fields
}
```
**Example** · `HttpCall.builder(url).method("POST").timeoutMs(1500).body("{...}").build()` gives
an immutable call; `GET` + a body fails in `build()`: "GET must not have a body". *Use:*
`HttpRequest.newBuilder()`, `StringBuilder`. *Not when:* 2-3 fields (use a constructor or record).

**Abstract Factory** · *QuickBite:* the dark theme must never show a light checkbox. `UiFactory {
button(); checkbox(); }`, one impl per family (`DarkTheme` gives "[dark button] [dark checkbox]").
*Use / not:* `DocumentBuilderFactory`; heaviest creational, rarely beyond a mention in a round.

## Structural patterns

**How it works** · Wrap or combine objects behind an interface (run `java StructuralPatterns.java`).

**Decorator** · *QuickBite:* logging + latency metrics (or caching, retries) around `payment-service`
calls, in any combo, without editing the client. *Structure:* same interface, extra work, delegate;
so they stack (subclass per combo explodes). Verified code, on a price lookup:
```java
class CachingPriceService implements PriceService {
    private final PriceService inner;
    // ...
    public long priceOf(String sku) {
        return cache.computeIfAbsent(sku, inner::priceOf);   // add behavior, then delegate
    }
}
PriceService prices = new LoggingPriceService(new CachingPriceService(new DbPriceService()));
```
**Example** · `priceOf("SKU-1")` twice; DB hit once, logged twice:
```text
call 1: Logging -> Caching (miss) -> Db "[db] lookup SKU-1"  -> [log] 19900 in 280 us
call 2: Logging -> Caching (hit)                             -> [log] 19900 in 2 us
```
Order matters (swap them to log only misses). *Use:* `BufferedInputStream`, HTTP interceptors.
*Not when:* always needed (put it in the class), too deep, or layers must coordinate.

| Pattern | QuickBite scenario (problem) | Structure | Use / when not |
|---|---|---|---|
| **Adapter** | Wrap a third-party payment SDK that speaks XML; our code expects `PaymentGateway` | `LegacyPaymentAdapter implements PaymentGateway`, wraps the XML client, translates both ways | `Arrays.asList`, `InputStreamReader`, one per external provider (how DIP is met) / you control both sides; keep it thin |
| **Facade** | "Place order" = reserve items, charge, release on failure, dispatch; every caller repeats it | `OrderFacade.placeOrder()` hides order and rollback over Inventory, Payments, Shipping | SLF4J, SDKs, service layers; vs Adapter: invents a *simpler* interface, not an *expected* one / clients need fine control; avoid a god object |
| **Proxy** | Only admins may open a restaurant's revenue report | Decorator's shape, different intent (controls access vs adds behavior): `AccessControlledReports` checks role, delegates | Protection, **virtual** (lazy), **remote** (RPC stub), caching; `java.lang.reflect.Proxy` / rule belongs in subject; hidden network calls surprise callers |
| **Composite** | Menu = categories holding items and sub-categories; "price of the whole combo" | Leaf and container share an interface (`FsNode`); `size()` sums children (post-order) | UI trees, file systems, org charts / very different leaf ops (leaf `add()` violates ISP) |

**Example** · One call each, values from the demo:
```text
Adapter   charge("O-1", 49900) -> legacy XML "<pay>...49900...</pay>" -> "OK" -> true
Facade    placeOrder("SKU-1", 2, "U-9"): reserve 2xSKU-1 -> charge U-9 39800 -> ship
          (if charge fails: release 2xSKU-1, return false)
Proxy     generate("admin", "revenue-q3") -> report;  "guest" -> SecurityException
Composite root{ src{Main 1200, Util 800}, README 300 }.size() = 2000 + 300 = 2300
```

## Behavioral patterns

**How it works** · How objects split work and talk (run `java BehavioralPatterns.java`).

**Strategy** · *QuickBite:* the delivery fee is normal most of the day and surge at the 8-9 pm
dinner peak. *Structure:* context holds an interface; swap at runtime. (Verified code, ride naming:)
```java
interface FareStrategy { long fare(int km); }              // paise
class RideBooking {
    private FareStrategy fareStrategy;
    void setFareStrategy(FareStrategy s) { this.fareStrategy = s; }
    long quote(int km) { return fareStrategy.fare(km); }
}
// NormalFare, SurgeFare(base, multiplier); or a lambda: booking.setFareStrategy(km -> 5_000);
```
**Example** · `NormalFare` = 3,000 + 1,200/km; 10 km = 15,000 paise. At 8 pm `setFareStrategy(new
SurgeFare(new NormalFare(), 1.5))`: 22,500. Flat lambda: 5,000. `quote()` never changed. *Use:*
`Comparator`, pricing, backoff. *Not when:* one algorithm, or choosing needs its internals.

**State** · *QuickBite:* order lifecycle PLACED → ACCEPTED → PREPARING → PICKED_UP → DELIVERED
(or CANCELLED) without `switch (status)` everywhere. *Structure:* class per state; the context
delegates (`void pay() { transition(state.pay(this), "pay"); }`); the state returns the next. Demo:
```java
class PaidState implements OrderState {
    public OrderState pay(OrderContext o) { o.reject("pay"); return this; }
    public OrderState ship(OrderContext o) { return new ShippedState(); }
    public OrderState cancel(OrderContext o) { System.out.println("  refund issued"); return new CancelledState(); }
    // ...
}
```
**Example** · `O-9`: pay CREATED -> PAID; ship PAID -> SHIPPED; cancel: "cannot cancel in state
SHIPPED" (that rule lives only in `ShippedState.cancel`). `O-10`: cancel -> CANCELLED; pay: rejected.
**vs Strategy:** client picks a strategy; states drive their own transitions. *Use:* vending machine,
elevator. *Not when:* few simple states; an enum + `Map<State, Set<State>>` of moves is shorter.

**Observer** · *QuickBite:* when order `o_90311` is DELIVERED, SMS, push, email and loyalty points
all react; direct calls couple `order-service` to all of them. *Structure:* subject notifies a list
of listeners it knows only by interface.
```java
private final List<OrderListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
void orderPlaced(String orderId) {
    for (OrderListener l : listeners) {
        try { l.onOrderPlaced(orderId); }
        catch (RuntimeException e) { /* one bad listener must not stop the rest */ }
    }
}
```
**Example** · subscribe(sms, push, email); event fires: sms ok, push throws (caught), email still
sent; loyalty = one more `subscribe`. `CopyOnWriteArrayList`: (un)subscribe mid-loop safely. *Use:*
UI listeners; broker pub/sub across services. *Not when:* slow listeners, or must survive crashes.

**Chain of Responsibility** · *QuickBite:* every call into `api-gateway` passes auth, rate limit
(OTP abuse), validation; any may reject. *Structure:* each handler checks, then stops or passes on.
```java
String handle(Request r) {
    String rejection = check(r);
    if (rejection != null) return rejection;          // stop the chain ("401", "429")
    return next == null ? "200 OK" : next.handle(r);  // or pass it on
}
```
**Example** · Chain Auth -> RateLimit(2) -> Validation:
```text
a  token ok, rate used 1/2, payload ok   -> 200 OK
b  no token                              -> 401 (rate limit never consulted)
"" token ok, rate used 2/2, empty        -> 400 Bad Request
c  token ok, rate used 3 > 2             -> 429 Too Many Requests
```
Classic: first handler able to handle does. Pipeline (filters, middleware, ch 25 logger): all look,
any may stop. *Use:* middleware, ATM denominations. *Not when:* short fixed sequence of calls.

| Pattern | QuickBite scenario (problem) | Structure | Use / when not |
|---|---|---|---|
| **Command** | Restaurant owner edits a menu description and wants undo | `Command { execute(); undo(); }`; `Editor` pushes onto a `Deque`, undo pops; redo = second stack | Executors' `Runnable`, job queues, WAL replay / no undo or queue (a lambda is enough) |
| **Template Method** | Import partner menus from CSV and pipe-delimited feeds: same read-validate-save skeleton | `final importData()` calls `abstract parse()` + **hook** `isValid()` (default) | `AbstractList`, servlet `doGet` / skeleton must change, or many steps vary; prefer Strategy for runtime swap |
| **Iterator** | Walk all orders page by page without exposing storage | `Iterable` returns `Iterator`; BST in-order with a stack: O(h) memory, amortized O(1) | Collections, DB cursors, paginated clients / rarely hand-written |

**Example** · Values from the demo:
```text
Command   run(Append "Hello"), run(Append ", world") -> "Hello, world"; undo -> "Hello"
Template  Csv 2 rows -> 2/2; Pipe "1|pen|2|" -> (1,pen),(2,"") -> hook drops blank: 1/2
Iterator  50(30(20,40),70(60,80)): stack [50,30,20] -> 20 30 40 50 60 70 80; depth <= h
```

## Which pattern for which smell

**How it works** · Name the smell, then reach for the pattern.

**Example** · `switch (paymentType)` in checkout, refunds, reporting: Strategy (`PaymentMethod` per
type) + simple factory + adapter per provider; a new type = one class + one factory line.

| Smell in the code | Pattern | Why |
|---|---|---|
| Repeated, growing `switch` on a type or mode | Strategy (or polymorphism) | One class per variant |
| `if (status == X)` in every method | State | Each state's rules in one class |
| `new Concrete...` and creation rules everywhere | Simple Factory / Factory Method | Creation in one place |
| Many-parameter constructor, many optional | Builder | Readable, validated, immutable |
| Third-party API doesn't match your interface | Adapter | Mismatch isolated in one class |
| Logging/caching/retry without editing the class | Decorator | Stackable same-interface wrappers |
| Guard, delay, or remote an object transparently | Proxy | Same interface, controlled access |
| Clients repeat a multi-step choreography | Facade | One simple entry point |
| "Single item vs group" special-casing in a tree | Composite | Uniform leaf/container interface |
| Class calls every interested party after an event | Observer | Publisher knows only a listener interface |
| Undo/redo, action queues, action log | Command | Actions become objects |
| Skeleton copy-pasted with small differences | Template Method (or Strategy) | Skeleton once, steps vary |
| Nested `if`s of checks, each able to reject | Chain of Responsibility | Each check a link, easy to reorder |
| Callers reach into a collection to traverse it | Iterator | Traversal without exposing structure |
| Exactly one shared instance needed | Singleton, or better one injected instance | No hidden globals |

## Patterns in common LLD problems

**How it works** · Each LLD problem has 2-4 places where something varies; one pattern each.

**Example** · QuickBite as an LLD problem: fee and rider matching = Strategy; order lifecycle =
State; "where is my order" and delivered notifications = Observer; payment SDKs = Adapter.

| LLD problem | Patterns | Where |
|---|---|---|
| Parking lot | Strategy, Factory, (Singleton), Observer | Spot allocation + pricing; vehicle/spot factory; free-spots board |
| Elevator | State, Strategy, Command, Observer | Car state; scheduling; requests as commands; displays observe |
| Vending machine | State, Strategy | Idle/HasMoney/Dispensing/OutOfStock; payment method |
| Logging framework | CoR, Singleton, Strategy, Decorator | Level chain; one manager; formatters/appenders; async appender |
| Notification service | Observer, Strategy, Factory, Decorator, Template Method | Subscribers; channel via factory; retry/rate-limit wrappers; send pipeline |
| Movie booking | Strategy, State, Observer | Pricing/payment; seat/booking status; notify on confirm |
| Splitwise | Strategy, Factory | Equal/exact/percent split; factory from input |
| Cache (LRU/LFU) | Strategy, Decorator, Proxy | Eviction policy; stats decorator; caching proxy |
| Rate limiter | Strategy, CoR, Decorator/Proxy | Token bucket vs sliding window; chain link or API proxy |
| Text editor | Command, **Memento** | Undo/redo stacks; Memento = object exports a state snapshot to restore later without exposing internals |
| Chess / tic-tac-toe | Strategy, Factory, State | Human/AI moves; piece factory; game status |
| Ride sharing / delivery | Strategy, Observer, State | Matching and fare; live location; trip lifecycle |
| ATM | State, CoR | Card in / PIN verified / dispensing; denomination chain |
| File system | Composite, Iterator | Shared interface; traversal |
| Multi-provider payments | Adapter, Strategy, Factory | Adapter per provider; routing; factory by name |

> **Common mistake:** Listing five patterns in the first five minutes of an LLD round. Name one
> when the code reaches the problem it solves; interviewers grade fit, not count.

## In one minute

- Start from the pain, then name the pattern; most are "hold an interface, delegate".
- Singleton: enum/holder, DCL needs `volatile`, prefer injecting one; factories centralize `new`.
- Builder gives readable, validated, immutable objects (an Order with many optional fields).
- Adapter converts (payment SDK), Facade simplifies, Decorator adds (logging), Proxy controls.
- Strategy swaps (surge fee); State drives transitions (order lifecycle); Template fixes a skeleton.
- Observer decouples (delivered -> SMS/push/email); Command = action objects; Chain = pluggable checks.
- Every pattern has a "when not": one implementation, two states, or a short fixed sequence.

## Go deeper

Full chapter 23: sections 23.2 (Singleton forms, Builder), 23.4 (State, Observer, Chain of Responsibility), 23.6 (patterns in LLD problems)
