# 23 — Design patterns that actually come up

A **design pattern** is a named, reusable shape of classes that solves a recurring design
problem. The value is mostly the name: saying "put a decorator around the client" communicates
a whole structure in four words. This chapter covers the patterns that show up in LLD rounds and
real codebases, each with the problem it solves, its structure, compact Java, where you have
already seen it, and when not to use it.

Code: `code/CreationalPatterns.java`, `code/StructuralPatterns.java`,
`code/BehavioralPatterns.java`. Every snippet below is taken from those files and runs on Java 21.

## 23.1 How to think about patterns

Patterns are grouped by what they help with:

| Group | Concern | Patterns in this chapter |
|---|---|---|
| **Creational** | How objects get created | Singleton, Simple Factory, Factory Method, Abstract Factory, Builder |
| **Structural** | How objects are composed and wrapped | Adapter, Decorator, Facade, Proxy, Composite |
| **Behavioral** | How objects divide work and communicate | Strategy, Observer, Command, State, Template Method, Chain of Responsibility, Iterator |

Three rules keep patterns useful:

1. **Start from the pain, not the pattern.** "Pricing rules keep changing" leads to Strategy. "I
   want to use Strategy" leads to over-engineering.
2. **Most patterns are interfaces plus composition.** Once chapter 22 feels natural, most
   patterns are small variations of "hold an interface, delegate to it".
3. **Name it when you use it.** In an interview, "I'll use the State pattern here because the
   machine's behavior depends on its state" earns credit; silently writing it earns less.

> **Say it like this:** "I'm not adding patterns for their own sake. Pricing is the part that
> varies, so it goes behind a strategy interface; everything else stays plain classes."

---

## 23.2 Creational patterns

### Singleton

**Problem.** Some things should exist exactly once per process: a configuration registry, an id
generator, a connection pool. If every caller does `new`, you get many copies with inconsistent
state, or wasted resources.

**Structure.**

```text
   +---------------------------+
   | IdGenerator               |
   +---------------------------+
   | - IdGenerator()           |   private constructor: nobody else can call new
   | + getInstance(): self     |   static access point
   | + nextId(): long          |
   +---------------------------+
```

**Option 1: enum singleton.** The simplest correct form. The JVM guarantees exactly one instance
per class loader, initialization is thread-safe, and it cannot be duplicated through reflection
or serialization.

```java
enum AppConfig {
    INSTANCE;

    private final Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
    String get(String key) { return values.get(key); }
    void set(String key, String value) { values.put(key, value); }
}
```

**Option 2: initialization-on-demand holder (lazy and thread-safe).** A nested class is not
loaded until it is first used. Class initialization in the JVM is guaranteed to run once and be
visible to all threads, so this gives lazy creation without any explicit locking.

```java
final class IdGenerator {
    private final AtomicLong next = new AtomicLong(1);

    private IdGenerator() {}                     // nobody else can construct

    private static class Holder {                // loaded only on first getInstance()
        static final IdGenerator INSTANCE = new IdGenerator();
    }

    static IdGenerator getInstance() { return Holder.INSTANCE; }

    long nextId() { return next.getAndIncrement(); }
}
```

**Option 3: double-checked locking.** You will be asked about it. It only works if the field is
`volatile`:

```java
final class ConnectionRegistry {
    private static volatile ConnectionRegistry instance;

    private ConnectionRegistry() {}

    static ConnectionRegistry getInstance() {
        ConnectionRegistry local = instance;
        if (local == null) {                          // 1st check, no lock
            synchronized (ConnectionRegistry.class) {
                local = instance;
                if (local == null) {                  // 2nd check, under lock
                    local = new ConnectionRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }
}
```

Why `volatile`: without it, the compiler or CPU may reorder "write the reference into `instance`"
before "finish running the constructor". Another thread doing the unlocked first check could
then see a non-null reference to a half-constructed object. `volatile` forbids that reordering
and guarantees visibility (chapter 26 explains the memory model side).

**Pitfalls of Singleton.**

| Pitfall | Why it hurts |
|---|---|
| Hidden global state | Any code can call `getInstance()`; dependencies are invisible in constructors |
| Hard to test | You cannot swap it for a fake; state leaks between tests |
| Naive lazy init is not thread-safe | `if (instance == null) instance = new X();` can create two instances |
| Reflection / serialization | Can create a second instance of a class-based singleton (enum is immune) |
| One per class loader, not per machine | Application servers with several class loaders get several "singletons"; a cluster of servers has one per process |

**Real-world use.** `Runtime.getRuntime()`; logging configuration; in DI frameworks the default
"singleton scope" means "one instance managed by the container", which gives you the single
instance without the static access point.

**When NOT to use.** When the "only one" assumption is a convenience rather than a rule. Prefer
creating one instance in `main` (or in the container) and injecting it. You keep the single
instance and get testability back.

> **Common mistake:** Making a service a singleton because "we only need one". Needing one is
> not the same as it being wrong to have two. Inject a single instance instead.

### Simple Factory and Factory Method

**Problem.** `new ConcreteClass()` scattered across the codebase couples every caller to every
concrete class. Adding a channel or changing which class is used means editing all callers.

**Simple Factory** (not one of the original catalogued patterns, but the one you will write most
often): one method that knows the concrete classes and returns an interface.

```text
   Client ----> NotificationFactory.create(type) ----> <<interface>> NotificationChannel
                                                          ^       ^        ^
                                                          |       |        |
                                                     EmailChannel SmsChannel PushChannel
```

```java
final class NotificationFactory {
    private NotificationFactory() {}

    // The only place that knows the concrete classes.
    static NotificationChannel create(ChannelType type) {
        return switch (type) {
            case EMAIL -> new EmailChannel();
            case SMS -> new SmsChannel();
            case PUSH -> new PushChannel();
        };
    }
}
```

The `switch` still exists, but it exists **once**. A `switch` expression over an enum must cover
every constant (or have a `default`), so adding `WHATSAPP` to the enum makes this line fail to
compile until it is handled.

**Factory Method** (the catalogued pattern): a superclass defines an algorithm that needs a
product, and leaves the creation of that product to a method subclasses override.

```text
   +------------------------------+            +-----------------+
   | Logistics (abstract)         |  creates   | <<interface>>   |
   | # createTransport(): Transport ---------->| Transport       |
   | + planDelivery(cargo)        |            +-----------------+
   +------------------------------+               ^          ^
        ^                 ^                       |          |
   RoadLogistics     SeaLogistics               Truck       Ship
   (returns Truck)   (returns Ship)
```

```java
abstract class Logistics {
    // The factory method: subclasses decide which Transport to create.
    protected abstract Transport createTransport();

    // Shared logic that uses the product without knowing its class.
    public void planDelivery(String cargo) {
        Transport t = createTransport();
        System.out.println("  plan: " + t.deliver(cargo));
    }
}

class RoadLogistics extends Logistics {
    protected Transport createTransport() { return new Truck(); }
}

class SeaLogistics extends Logistics {
    protected Transport createTransport() { return new Ship(); }
}
```

**Real-world use.** Simple factories: `List.of(...)`, `Executors.newFixedThreadPool(n)`,
`Path.of(...)`. Factory method: `Iterable.iterator()`; each collection returns its own iterator
class while code using it only sees `Iterator`.

**When NOT to use.** When there is one implementation and no realistic second one, `new` is
fine. Factory Method in particular needs a subclass per product, which is heavy; a simple
factory or passing a `Supplier<Transport>` is often enough.

### Abstract Factory (brief)

**Problem.** You need to create **families** of related objects that must match: dark-theme
button with dark-theme checkbox; a cloud provider's storage client with the same provider's
queue client. Mixing families is a bug.

```text
   <<interface>> UiFactory               DarkTheme  -> dark button,  dark checkbox
     + button(): Button                  LightTheme -> light button, light checkbox
     + checkbox(): Checkbox
```

```java
interface UiFactory {
    Button button();
    Checkbox checkbox();
}

class DarkTheme implements UiFactory {
    public Button button() { return () -> "[dark button]"; }
    public Checkbox checkbox() { return () -> "[dark checkbox]"; }
}
```

(`Button` and `Checkbox` are one-method interfaces, so a lambda can implement them.)

The client receives one `UiFactory` and can never accidentally mix themes.

**Real-world use.** `DocumentBuilderFactory` in the XML APIs; UI toolkits with look-and-feel
families; test vs production families of infrastructure clients.

**When NOT to use.** When you have only one family, or products are independent. It is the
heaviest creational pattern; it rarely appears in a 60-minute LLD round beyond a mention.

### Builder

**Problem.** An object has a few required fields and many optional ones. Constructors with eight
parameters are unreadable (`new HttpCall(url, "POST", null, 1500, null, true, false)`: which
`null` is which?), and offering every combination of constructors explodes. Setting fields one by
one with setters leaves the object mutable and possibly invalid in between.

```text
   HttpCall.builder(url) -> Builder -method()-> Builder -header()-> ... -build()-> HttpCall
                            (mutable, temporary)            validates once    (immutable)
```

```java
    static final class Builder {
        private final String url;                      // required
        private String method = "GET";                 // optional, with defaults
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int timeoutMs = 1000;
        private String body;

        private Builder(String url) { this.url = Objects.requireNonNull(url); }

        Builder method(String m) { this.method = m; return this; }
        Builder header(String k, String v) { headers.put(k, v); return this; }
        Builder timeoutMs(int t) { this.timeoutMs = t; return this; }
        Builder body(String b) { this.body = b; return this; }

        HttpCall build() {                             // validate the whole object once
            if (timeoutMs <= 0) throw new IllegalStateException("timeout must be > 0");
            if (method.equals("GET") && body != null)
                throw new IllegalStateException("GET must not have a body");
            return new HttpCall(this);
        }
    }
```

Usage reads like a sentence:

```java
        HttpCall call = HttpCall.builder("https://api.example.com/orders")
                .method("POST")
                .header("Content-Type", "application/json")
                .timeoutMs(1500)
                .body("{\"sku\":\"A1\"}")
                .build();
```

Each builder method returns `this`, which is what allows the chained calls (a **fluent
interface**). The target class has a private constructor that takes the builder and copies its
fields into `final` fields, so the built object is immutable. Cross-field rules ("GET has no
body") are checked once in `build()`.

**Real-world use.** `HttpRequest.newBuilder()` in `java.net.http`, `Thread.ofVirtual().name("w-",
0).start(task)` in Java 21, `StringBuilder` (a builder for strings), `Stream.builder()`.

**When NOT to use.** Objects with two or three required fields: a constructor or a record is
clearer. Builders also add a second class to keep in sync with the first.

---

## 23.3 Structural patterns

### Adapter

**Problem.** You have code written against interface A, and a class (often third-party or legacy,
which you cannot change) that offers the same capability through incompatible interface B.

```text
   OrderService ---> <<interface>> PaymentGateway
                          ^
                          | implements
                  LegacyPaymentAdapter ----holds----> LegacyXmlPaymentClient
                    charge(id, paise)                  makePayment(xml): String
                    -> builds XML, parses reply
```

```java
class LegacyPaymentAdapter implements PaymentGateway {
    private final LegacyXmlPaymentClient legacy;
    LegacyPaymentAdapter(LegacyXmlPaymentClient legacy) { this.legacy = legacy; }

    public boolean charge(String orderId, long paise) {
        String xml = "<pay><order>" + orderId + "</order><amount>" + paise + "</amount></pay>";
        return legacy.makePayment(xml).contains("OK");    // translate both ways
    }
}
```

**Real-world use.** `Arrays.asList(array)` adapts an array to `List`; `InputStreamReader` adapts a
byte stream to a character reader. In LLD: every external provider (payment, SMS, maps) sits
behind your own interface with one adapter per provider. That is also how you satisfy the
dependency inversion principle from chapter 22.

**When NOT to use.** When you control both sides; change one of them instead. And keep adapters
thin: business logic inside an adapter gets duplicated across adapters.

### Decorator

**Problem.** You want to add behavior (caching, logging, retries, metrics, compression) to an
object without changing its class, and you want to combine these behaviors freely. Subclassing
for every combination explodes (`CachingLoggingPriceService`, `LoggingRetryingPriceService`, ...).

**Idea.** A decorator implements the same interface as the object it wraps, does its extra work,
and delegates to the wrapped object. Because the outside looks the same, decorators stack.

```text
   client --> LoggingPriceService --> CachingPriceService --> DbPriceService
              (implements             (implements             (implements
               PriceService)           PriceService)           PriceService)
```

```java
class CachingPriceService implements PriceService {
    private final PriceService inner;
    private final Map<String, Long> cache = new HashMap<>();
    CachingPriceService(PriceService inner) { this.inner = inner; }

    public long priceOf(String sku) {
        return cache.computeIfAbsent(sku, inner::priceOf);   // add behavior, then delegate
    }
}

class LoggingPriceService implements PriceService {
    private final PriceService inner;
    LoggingPriceService(PriceService inner) { this.inner = inner; }

    public long priceOf(String sku) {
        long start = System.nanoTime();
        long p = inner.priceOf(sku);
        System.out.printf("  [log] priceOf(%s)=%d in %d us%n", sku, p,
                (System.nanoTime() - start) / 1_000);
        return p;
    }
}
```

```java
        PriceService prices = new LoggingPriceService(
                new CachingPriceService(new DbPriceService()));
```

The demo calls `priceOf("SKU-1")` twice: the database is hit once, the log shows both calls.
Order matters: logging outside the cache measures cache hits too; swap them to log only misses.

**Real-world use.** `new BufferedInputStream(new FileInputStream(f))`;
`Collections.unmodifiableList(list)`; `Collections.synchronizedMap(map)`; HTTP client
interceptors for retry and auth.

**When NOT to use.** When the behavior is needed everywhere and never varies (just put it in the
class), or when the stack gets deep enough that debugging "which layer did this" becomes hard.
Decorators also cannot see each other's internals, so features that must coordinate (a retry
that must invalidate a cache) may need a different design.

### Facade

**Problem.** A client must call several subsystems in the right order with the right error
handling: reserve inventory, charge payment, release inventory if payment fails, schedule
shipping. Every client repeats this choreography, and each copy drifts.

```text
              +---------------------+
   client --> | OrderFacade         |
              |  placeOrder(...)    |
              +---------------------+
                 |        |       |
                 v        v       v
            Inventory  Payments  Shipping
```

```java
    // One simple call hides the order of steps and the rollback rule.
    boolean placeOrder(String sku, int qty, String userId) {
        if (!inventory.reserve(sku, qty)) return false;
        if (!payments.charge(userId, qty * 19_900L)) {
            inventory.release(sku, qty);
            return false;
        }
        shipping.schedule(sku, userId);
        return true;
    }
```

**Real-world use.** SLF4J ("Simple Logging Facade for Java") puts one simple API in front of
several logging libraries; SDK clients that wrap dozens of HTTP calls; the "service layer" in most
backend code is a facade over repositories and clients.

**Adapter vs Facade.** An adapter makes one existing interface look like another **expected**
interface. A facade invents a new, **simpler** interface over many objects.

**When NOT to use.** When clients genuinely need fine-grained control; a facade can then become a
"god object" that knows everything (chapter 24). Keep the subsystems usable directly.

### Proxy

**Problem.** You want to control access to an object (check permissions, load it lazily, call it
over the network, rate limit it) without the client knowing anything changed.

**Structure.** Same shape as Decorator: implements the same interface and holds the real object.
The difference is **intent**: a proxy controls access to the real subject, a decorator adds
responsibilities.

```text
   client --> <<interface>> ReportService
                 ^                 ^
                 |                 |
   AccessControlledReports ---> RealReportService
   (checks role, then delegates)
```

```java
class AccessControlledReports implements ReportService {
    private final ReportService real;
    AccessControlledReports(ReportService real) { this.real = real; }

    public String generate(String user, String reportId) {
        if (!user.equals("admin")) throw new SecurityException(user + " may not read " + reportId);
        return real.generate(user, reportId);       // same interface, controlled access
    }
}
```

Kinds of proxy:

| Kind | What it controls |
|---|---|
| Protection proxy | who may call (the example) |
| Virtual proxy | creating an expensive object only when first used (lazy loading) |
| Remote proxy | a local object that forwards calls over the network (RPC client stubs) |
| Caching proxy | answers from a cache in front of the real subject |

**Real-world use.** `java.lang.reflect.Proxy` creates proxies at runtime; frameworks use such
proxies to wrap methods in transactions or to lazily load related entities from a database.

**When NOT to use.** When the access rule belongs in the subject's own logic, or when the proxy's
hidden behavior (a network call that looks like a local call) will surprise callers about latency
and failures.

### Composite

**Problem.** You have a tree of part-whole objects (folders and files, menu groups and items,
expression nodes) and clients keep writing `if (node is folder) recurse else use file`.

**Idea.** Leaves and containers implement the same interface; a container implements each
operation by delegating to its children. The client treats one object and a whole tree the same
way.

```text
   <<interface>> FsNode  { size(); print(indent); }
        ^                       ^
        |                       |
     FileNode              Directory  ----children----> FsNode (0..*)
```

```java
class Directory implements FsNode {
    private final String name;
    private final List<FsNode> children = new ArrayList<>();
    Directory(String name) { this.name = name; }

    void add(FsNode node) { children.add(node); }

    public long size() {                    // recursion over the tree, same call for leaf and branch
        long total = 0;
        for (FsNode c : children) total += c.size();
        return total;
    }
```

DSA bridge: `size()` is a post-order traversal; each container's answer is the sum of its
subtrees. The pattern is "a tree whose nodes share an interface".

**Real-world use.** UI component trees (containers hold components), file systems, org charts,
arithmetic expression trees, permission groups that contain users and other groups.

**When NOT to use.** When leaves and containers have very different operations; forcing them
into one interface leads to leaves with meaningless `add()` methods (an ISP violation).

---

## 23.4 Behavioral patterns

### Strategy

**Problem.** One step of your logic has several interchangeable versions (fare calculation, spot
allocation, eviction policy, split type) and you want to pick or swap the version without editing
the code that uses it.

```text
   RideBooking  ----has-a---->  <<interface>> FareStrategy
     quote(km)                      fare(km)
                                    ^        ^
                                    |        |
                              NormalFare   SurgeFare(base, multiplier)
```

```java
interface FareStrategy {
    long fare(int km);                       // paise
}

class NormalFare implements FareStrategy {
    public long fare(int km) { return 3_000 + km * 1_200L; }
}

class SurgeFare implements FareStrategy {
    private final FareStrategy base;
    private final double multiplier;
    SurgeFare(FareStrategy base, double multiplier) { this.base = base; this.multiplier = multiplier; }
    public long fare(int km) { return Math.round(base.fare(km) * multiplier); }
}

class RideBooking {
    private FareStrategy fareStrategy;
    RideBooking(FareStrategy s) { this.fareStrategy = s; }
    void setFareStrategy(FareStrategy s) { this.fareStrategy = s; }
    long quote(int km) { return fareStrategy.fare(km); }
}
```

A one-method strategy interface is a **functional interface**, so a lambda works too:
`booking.setFareStrategy(km -> 5_000);`.

**Real-world use.** `Comparator` passed to `List.sort`; `RejectedExecutionHandler` in thread
pools; pricing rules, routing algorithms, retry/backoff policies.

**When NOT to use.** When there is one algorithm and no realistic second one. Also, if the caller
must know a lot about each strategy's internals to choose it, the abstraction is leaking.

> **Say it like this:** "Spot allocation is a strategy: nearest-first today, maybe
> EV-spots-first later. The lot holds a `SpotAllocationStrategy` and doesn't care which one."

### Observer

**Problem.** When an order is placed, email, analytics, inventory, and loyalty all need to react.
If `OrderService` calls each one directly, it depends on all of them, and adding a reaction means
editing it.

**Idea.** The **subject** keeps a list of **observers** (listeners) that registered interest and
notifies them all when the event happens. The subject knows only the listener interface.

```text
   OrderEvents (subject)                       <<interface>> OrderListener
     - listeners: List<OrderListener> ------->   onOrderPlaced(orderId)
     + subscribe(l)                                 ^            ^
     + orderPlaced(id) -> notify each               |            |
                                              EmailListener  AnalyticsListener
```

```java
class OrderEvents {
    private final List<OrderListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    void subscribe(OrderListener l) { listeners.add(l); }
    void unsubscribe(OrderListener l) { listeners.remove(l); }

    void orderPlaced(String orderId) {
        for (OrderListener l : listeners) {
            try {
                l.onOrderPlaced(orderId);
            } catch (RuntimeException e) {       // one bad listener must not stop the rest
                System.out.println("  listener failed: " + e.getMessage());
            }
        }
    }
}
```

Two details worth saying out loud: `CopyOnWriteArrayList` lets listeners subscribe or unsubscribe
while a notification loop is running without a `ConcurrentModificationException` (each write
copies the array, fine because subscriptions are rare and notifications frequent); and the
try/catch isolates a failing listener.

**Real-world use.** UI event listeners; `PropertyChangeListener`; in distributed systems the same
idea becomes pub/sub through a message broker (chapters on queues), where observers are other
services.

**When NOT to use.** In-process observers run synchronously on the publisher's thread by default,
so a slow listener slows the publisher. Long chains of events triggering events are hard to
debug. If reactions must survive crashes or run in other services, use a durable queue instead
of in-memory observers.

> **Common mistake:** Forgetting to unsubscribe. The subject's list keeps a strong reference to
> each listener, so a listener that should have died stays in memory (a leak) and keeps reacting.

### Command

**Problem.** You want to treat an action as an object: queue it, log it, retry it, or undo it.
With plain method calls the action disappears once called.

```text
   Editor --history (stack)--> <<interface>> Command { execute(); undo(); }
                                       ^
                                       |
                                AppendCommand(editor, text)
```

```java
interface Command {
    void execute();
    void undo();
}

class Editor {
    private final StringBuilder text = new StringBuilder();
    private final Deque<Command> history = new ArrayDeque<>();

    void run(Command c) { c.execute(); history.push(c); }
    void undo() { if (!history.isEmpty()) history.pop().undo(); }

    void append(String s) { text.append(s); }
    void deleteLast(int n) { text.setLength(text.length() - n); }
    String text() { return text.toString(); }
}

class AppendCommand implements Command {
    private final Editor editor;
    private final String s;
    AppendCommand(Editor editor, String s) { this.editor = editor; this.s = s; }
    public void execute() { editor.append(s); }
    public void undo() { editor.deleteLast(s.length()); }
}
```

DSA bridge: undo is a stack of commands; redo is a second stack you push onto when undoing and
clear on any new command.

**Real-world use.** `Runnable`/`Callable` submitted to an executor are commands; job queues;
elevator requests; database write-ahead logs record commands so they can be replayed.

**When NOT to use.** For simple direct calls with no need to queue, log, or undo. A class per
action adds up quickly; a lambda (`Runnable`) is often enough when undo is not needed.

### State

**Problem.** An object behaves differently depending on its state, and every method is a
`switch (status)` with rules about what is allowed. Adding a state means editing every method.

**Idea.** Each state is a class implementing the same interface. The context object delegates to
its current state, and the state decides the next state.

```text
          pay            ship
  CREATED -----> PAID ---------> SHIPPED
     |             |
     | cancel      | cancel (refund)
     v             v
   CANCELLED <-----+
```

```java
interface OrderState {
    OrderState pay(OrderContext o);
    OrderState ship(OrderContext o);
    OrderState cancel(OrderContext o);
    String name();
}

class PaidState implements OrderState {
    public OrderState pay(OrderContext o) { o.reject("pay"); return this; }
    public OrderState ship(OrderContext o) { return new ShippedState(); }
    public OrderState cancel(OrderContext o) { System.out.println("  refund issued"); return new CancelledState(); }
    public String name() { return "PAID"; }
}
```

The context just forwards:

```java
    void pay() { transition(state.pay(this), "pay"); }
    void ship() { transition(state.ship(this), "ship"); }
    void cancel() { transition(state.cancel(this), "cancel"); }
```

The rule "you cannot cancel once shipped" now lives in exactly one place: `ShippedState.cancel`.

**Strategy vs State.** Same class shape. With Strategy the client picks the algorithm and
strategies do not know each other. With State the states themselves trigger transitions, and the
set of states forms a state machine.

**Real-world use.** Vending machines, order and payment lifecycles, TCP connection states,
elevator movement, document approval workflows.

**When NOT to use.** With two or three states and simple rules, an enum plus a transition table
(`Map<State, Set<State>>` of allowed moves) is shorter and easier to see at a glance. Use State
classes when each state has substantial, different behavior.

> **Say it like this:** "The vending machine's response to 'insert coin' depends on its state,
> so I'll model states as classes. Each state handles every event and returns the next state,
> which kills the switch statements."

### Template Method

**Problem.** Several classes run the same algorithm skeleton (read, parse, validate, save) and
differ in one or two steps. Copy-pasting the skeleton means bug fixes must be repeated in every
copy.

```text
   DataImporter (abstract)
     + importData(raw)   final: parse -> validate each -> save   (the template)
     # parse(raw)        abstract                                (varying step)
     # isValid(row)      hook with default                       (optional override)
        ^             ^
   CsvImporter    PipeImporter
```

```java
abstract class DataImporter {
    // The skeleton is fixed (final); subclasses fill in the varying steps.
    public final void importData(String raw) {
        List<String[]> rows = parse(raw);
        int saved = 0;
        for (String[] row : rows) {
            if (isValid(row)) saved++;              // pretend save
        }
        System.out.println("  " + getClass().getSimpleName() + ": saved " + saved + "/" + rows.size());
    }

    protected abstract List<String[]> parse(String raw);

    protected boolean isValid(String[] row) {        // hook with a default
        return row.length == 2 && !row[1].isBlank();
    }
}
```

A **hook** is a step with a default implementation that subclasses may override.

**Real-world use.** `AbstractList` (implement `get` and `size`, inherit the rest); servlet base
classes whose `service` method dispatches to `doGet`/`doPost`; test frameworks' setup/run/teardown
lifecycle.

**Template Method vs Strategy.** Template Method varies steps through inheritance (fixed at
compile time); Strategy varies them through composition (swappable at runtime). Prefer Strategy
when the varying part is independent; Template Method when the steps share a lot of state with
the skeleton.

**When NOT to use.** When subclasses need to change the skeleton itself, or when you would need
several independent varying steps (combinations of subclasses explode).

### Chain of Responsibility

**Problem.** A request must pass through a sequence of checks or handlers (authentication, rate
limit, validation), and any of them may stop it. Nested `if`s in one method grow without bound,
and reordering or adding a check means editing that method.

```text
  request --> AuthHandler --> RateLimitHandler --> ValidationHandler --> "200 OK"
                  |                  |                    |
                  v                  v                    v
             "401" stop          "429" stop           "400" stop
```

```java
abstract class Handler {
    private Handler next;

    static Handler link(Handler first, Handler... rest) {
        Handler cur = first;
        for (Handler h : rest) { cur.next = h; cur = h; }
        return first;
    }

    String handle(Request r) {
        String rejection = check(r);
        if (rejection != null) return rejection;          // stop the chain
        return next == null ? "200 OK" : next.handle(r);  // or pass it on
    }

    protected abstract String check(Request r);           // null = "fine, continue"
}
```

There are two flavors. In the classic one, the first handler that **can** handle a request does
so and the chain stops. In the "pipeline" flavor (servlet filters, middleware, the logging
framework in chapter 25), every handler gets a look and may act, and any may stop the chain.

**Real-world use.** Servlet filters and web middleware; logging handlers; exception handlers
searched from most specific to most general; an ATM dispensing 2000s, then 500s, then 100s.

**When NOT to use.** When the order of checks is fixed and short, a plain sequence of calls is
clearer. Long chains make it hard to answer "why was this request rejected?", so log which handler
stopped it.

### Iterator (brief)

**Problem.** Clients need to walk through a collection without knowing its internal structure (an
array, a linked list, a tree), and you want several independent walks at once.

```java
    // In-order traversal exposed one element at a time with an explicit stack:
    // callers never see Node, left, or right.
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private final Deque<Node> stack = new ArrayDeque<>();
            { pushLeft(root); }

            private void pushLeft(Node n) {
                while (n != null) { stack.push(n); n = n.left; }
            }

            public boolean hasNext() { return !stack.isEmpty(); }

            public Integer next() {
                if (stack.isEmpty()) throw new NoSuchElementException();
                Node n = stack.pop();
                pushLeft(n.right);
                return n.val;
            }
        };
    }
```

This is the "BST iterator" problem from DSA practice: O(h) memory, amortized O(1) per `next()`.
Implementing `Iterable<Integer>` is what lets callers write `for (int v : tree)`.

**Real-world use.** Every Java collection; database cursors; paginated API clients that fetch the
next page lazily.

**When NOT to use.** You rarely write one from scratch; expose a standard collection or a `Stream`
unless the structure is custom.

---

## 23.5 Which pattern for which smell

| Smell in the code | Pattern to consider | Why |
|---|---|---|
| A `switch` on a type or mode, repeated and growing | Strategy (or plain polymorphism) | One class per variant; callers stop branching |
| `if (status == X)` in every method | State | Each state's rules in one class |
| `new Concrete...` and creation rules scattered everywhere | Simple Factory / Factory Method | Creation logic in one place |
| Constructor with many parameters, many optional | Builder | Readable, validated, immutable result |
| Third-party API does not match your interface | Adapter | Isolate the mismatch in one class |
| Need logging/caching/retry around an object without editing it | Decorator | Stackable wrappers with the same interface |
| Need to guard, delay, or remote an object transparently | Proxy | Same interface, controlled access |
| Clients repeat a multi-step choreography across subsystems | Facade | One simple entry point |
| Clients special-case "single item vs group of items" in a tree | Composite | Uniform interface for leaf and container |
| A class calls every interested party directly after an event | Observer | Publisher depends only on a listener interface |
| Need undo/redo, queues of actions, or an action log | Command | Actions become objects |
| Same algorithm skeleton copy-pasted with small differences | Template Method (or Strategy) | Skeleton once, steps vary |
| Nested `if`s of sequential checks, each able to reject | Chain of Responsibility | Each check is a link; easy to reorder |
| Callers reach into a collection's internals to traverse it | Iterator | Traversal without exposing structure |
| Exactly one shared instance needed | Singleton, or better: one instance injected | Single instance without hidden globals |

## 23.6 Patterns in common LLD problems

| LLD problem | Patterns that fit | Where exactly |
|---|---|---|
| Parking lot | Strategy, Factory, (Singleton), Observer | Strategy for spot allocation and for pricing; factory for vehicles/spots; observer for a "free spots" display board |
| Elevator system | State, Strategy, Command, Observer | Car state (idle/moving/doors open); scheduling and car-selection strategies; requests as commands; floor displays observe cars |
| Vending machine | State, Strategy | States: Idle, HasMoney, Dispensing, OutOfStock; payment method as strategy |
| Logging framework | Chain of Responsibility, Singleton, Strategy, Decorator | Handler chain by level; one logger manager; formatters/appenders as strategies; async or buffered appender as a decorator |
| Notification service | Observer, Strategy, Factory, Decorator, Template Method | Subscribers to events; channel (email/SMS/push) as strategy chosen by a factory; retry and rate limiting as decorators; a fixed send pipeline |
| Movie ticket booking | Strategy, State, Observer | Pricing and payment strategies; booking/seat status machine; notify on confirmation |
| Splitwise | Strategy, Factory | Split type (equal/exact/percent) as strategy; factory builds the right split from input |
| Cache (LRU/LFU) | Strategy, Decorator, Proxy | Eviction policy as strategy; stats as a decorator; a caching proxy in front of a slow store |
| Rate limiter | Strategy, Chain of Responsibility, Decorator/Proxy | Token bucket vs sliding window as strategy; limiter as a link in the request chain or a proxy around the API |
| Text editor | Command, Memento | Undo/redo stacks; snapshots of document state |
| Chess / Tic-tac-toe | Strategy, Factory, State | Player move strategy (human/AI); piece factory; game status |
| Ride sharing / food delivery | Strategy, Observer, State | Matching and fare strategies; live location updates; trip lifecycle |
| ATM | State, Chain of Responsibility | Card inserted / PIN verified / dispensing; denomination chain |
| File system | Composite, Iterator | Files and folders share an interface; traversal |
| Multi-provider payments | Adapter, Strategy, Factory | One adapter per provider; routing strategy; factory by provider name |

(**Memento** is a pattern where an object exports a snapshot of its internal state so it can be
restored later without exposing its internals; it pairs with Command for undo.)

> **Common mistake:** Listing five patterns in the first five minutes of an LLD round. Name a
> pattern when the code reaches the point where it solves a visible problem; interviewers grade
> fit, not count.

## 23.7 Running the demos

```text
cd ~/learn-system-design/code
java CreationalPatterns.java   # singletons, factories, abstract factory, builder
java StructuralPatterns.java   # adapter, decorator, facade, proxy, composite
java BehavioralPatterns.java   # strategy, observer, command, state, template method,
                               # chain of responsibility, iterator (BST in-order)
```

## 23.8 Recap

- A pattern is a named shape that solves a recurring problem; the name is the main value.
- Start from the pain (what varies, what is duplicated, what is coupled), then pick the pattern.
- Singleton: prefer the enum or the holder idiom; double-checked locking needs `volatile`; in
  application code, injecting one instance usually beats a static singleton.
- Factories centralize creation; Builder gives readable, validated, immutable construction.
- Adapter converts an interface; Facade simplifies a subsystem; Decorator adds behavior; Proxy
  controls access. Decorator and Proxy share a structure and differ in intent.
- Composite treats leaves and containers the same; it is a tree with a shared interface.
- Strategy swaps algorithms through composition; State lets states drive transitions; Template
  Method fixes a skeleton through inheritance.
- Observer decouples publishers from reactions; Command turns actions into objects (queue, log,
  undo); Chain of Responsibility makes sequential checks pluggable.
- Every pattern has a "when not to": one implementation, two states, or a fixed short sequence
  rarely need one.

## 23.9 Check yourself

1. Why does double-checked locking need `volatile`, and what is a simpler alternative?

<details><summary>Answer</summary>

Without `volatile`, the write of the reference to the shared field can be reordered before the
constructor's writes complete, so another thread passing the unlocked first check may see a
non-null reference to a partly constructed object. `volatile` forbids that reordering and makes
the write visible to other threads. Simpler alternatives: the enum singleton, or the
initialization-on-demand holder class, both of which rely on the JVM's thread-safe class
initialization.

</details>

2. Decorator and Proxy have the same class structure. How do you tell them apart?

<details><summary>Answer</summary>

By intent. A decorator adds responsibilities (logging, caching, compression) and is usually
composed by the client, often several layers deep. A proxy controls access to the real subject
(permission checks, lazy creation, remote calls) and usually stands in for it transparently, with
the client unaware. A caching wrapper can be described either way; say which intent you mean.

</details>

3. You have a `switch (paymentType)` in checkout, refunds, and reporting. Which pattern(s) and why?

<details><summary>Answer</summary>

Strategy (or plain polymorphism): define a `PaymentMethod` interface with the operations those
switches perform, one class per type. A simple factory maps the input type to the right class in
one place. Adding a type then means one new class and one factory line instead of editing three
switches. If the providers have different external APIs, put an adapter behind each.

</details>

4. When is an enum with a transition table better than the State pattern?

<details><summary>Answer</summary>

When there are few states and the behavior per state is mostly "is this transition allowed?".
A `Map<Status, Set<Status>>` of allowed transitions is short, visible in one place, and easy to
persist. State classes pay off when each state has substantial, different behavior for many
events (a vending machine that computes change, dispenses, and refunds differently by state).

</details>

5. What is the difference between Factory Method and Abstract Factory?

<details><summary>Answer</summary>

Factory Method is one overridable method in a class that uses the product; subclasses decide which
single product to create. Abstract Factory is an object whose interface has several creation
methods for a family of related products that must be used together (dark button with dark
checkbox). Factory Method uses inheritance; Abstract Factory is passed in as a collaborator.

</details>

6. In the Observer demo, why is the listener list a `CopyOnWriteArrayList` and why is each call
   wrapped in try/catch?

<details><summary>Answer</summary>

`CopyOnWriteArrayList` lets listeners be added or removed while the subject is iterating (even
from inside a listener or another thread) without a `ConcurrentModificationException`; it suits
lists that are read often and changed rarely. The try/catch ensures one failing listener does not
prevent the remaining listeners from being notified or crash the publisher.

</details>

7. Name the patterns you would use in a notification service that sends email, SMS, and push,
   retries failures, and lets new event types subscribe.

<details><summary>Answer</summary>

Observer for event subscription (services publish events, notification handlers subscribe);
Strategy for the channel, selected by a simple factory from user preferences; Decorator for retry
and rate limiting around a channel; optionally Template Method for a fixed send pipeline (render
template, check preferences, send, record). Adapters wrap each external provider.

</details>

8. Why is Template Method sometimes called "inheritance done right", and when would you replace it
   with Strategy?

<details><summary>Answer</summary>

The parent is designed for extension: the algorithm is `final`, and the only extension points are
explicit abstract steps and hooks, which avoids the fragile base class problem of overriding
arbitrary methods. Replace it with Strategy when the varying step should change at runtime, when
several independent steps vary (subclass combinations explode), or when you want to test the
step in isolation.

</details>
