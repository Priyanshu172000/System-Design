# 22 — OOP and SOLID: the foundation of LLD

High-level design (chapters 16–21) decides which boxes exist and how they talk. Low-level design
decides what the code inside one box looks like: which classes, which methods, who knows about
whom. This chapter gives you the vocabulary and the reasons behind it, so that when someone says
"that violates open/closed" or "inject it through the constructor", you know exactly what they
mean and why it matters.

All code here is Java 21 and runs. The full programs are `code/OopBasicsDemo.java` and
`code/SolidDemo.java`.

## 22.1 What LLD is and why it exists

**Low-level design (LLD)** is the design of the code inside one component: the classes, their
fields and methods, the interfaces between them, and the relationships (who owns whom, who calls
whom). It sits between "the architecture diagram" and "the actual lines of code".

The problem it solves is not "making code work". Almost any tangle of code can be made to work
once. The problem is **change**:

- Requirements change: "add UPI payments", "parking now charges per 30 minutes", "support a
  second elevator bank".
- People change: the code you write will be read and modified by others for years.
- Scale changes: a single-threaded prototype now serves many concurrent users.

```text
   Cost of a change
        ^
        |                                   badly structured code
        |                               ..''
        |                          ..'''
        |                    ..''''
        |             ..'''''               well structured code
        |     ..''''' _______________________________________
        |..'''_______/
        +-------------------------------------------------> time / number of changes
```

Well-designed code keeps the cost of the next change roughly flat. Badly designed code makes
every change touch more files, break more tests, and scare more people. LLD is the set of habits
that keeps that curve flat.

> **Say it like this:** "LLD is about making the next change cheap. I want each new requirement
> to be a new class or a small local edit, not surgery across the codebase."

In interviews the **machine coding** or **LLD round** gives you a problem (parking lot, elevator,
splitwise) and checks whether your code is organized around the parts that will change. Chapter
24 covers that round in detail. This chapter is the foundation it stands on.

## 22.2 Classes and objects

A **class** is a blueprint: it declares what data a thing holds (**fields**) and what it can do
(**methods**). An **object** (or **instance**) is one concrete thing built from that blueprint,
with its own copy of the fields.

```text
   class BankAccount                       objects (instances) in memory
   +---------------------+                 +----------------------+
   | - id: String        |   new ...  -->  | id = "ACC-1"         |
   | - balancePaise: long|                 | balancePaise = 30000 |
   +---------------------+                 +----------------------+
   | + deposit(long)     |                 +----------------------+
   | + withdraw(long)    |   new ...  -->  | id = "ACC-2"         |
   | + balance(): long   |                 | balancePaise = 0     |
   +---------------------+                 +----------------------+
```

The design reason for classes is **grouping data with the rules that protect it**. A bank
account balance is not just a number; it is a number with rules (never negative, only changes by
deposit or withdrawal). Putting the rules next to the data means there is exactly one place
where those rules live.

A **constructor** is the special method that runs when an object is created (`new
BankAccount("ACC-1")`). Its job is to leave the object in a valid state from the first moment.

## 22.3 Encapsulation

**Encapsulation** means hiding an object's internal state and forcing all changes to go through
its methods.

**Problem first.** Suppose `balance` were a public field. Then any code anywhere could write
`account.balance = -500`. The rule "never negative" would have to be re-checked in every place
that touches the balance, and one forgotten check is a bug. Worse, if you later want to log
every balance change, you have to find every write in the whole codebase.

**The idea.** Make the field `private` and expose only meaningful operations:

```java
class BankAccount {
    private final String id;
    private long balancePaise;            // hidden: nobody outside can set it

    BankAccount(String id) {
        this.id = Objects.requireNonNull(id);
    }

    void deposit(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("deposit must be positive");
        balancePaise += paise;
    }

    void withdraw(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("withdraw must be positive");
        if (paise > balancePaise) throw new IllegalStateException("insufficient funds");
        balancePaise -= paise;
    }

    long balance() { return balancePaise; }
    String id() { return id; }
}
```

Java's **access modifiers** control who can see a member:

| Modifier | Visible to | Typical use |
|---|---|---|
| `private` | only this class | fields, helper methods |
| (none) "package-private" | classes in the same package | collaborators inside one module |
| `protected` | same package + subclasses | hooks meant for subclasses |
| `public` | everyone | the API of the class |

Note that money is stored as `long` paise, not `double` rupees. Binary floating point cannot
represent 0.1 exactly, so sums drift. Integer minor units (or `BigDecimal`) are the usual choice.

**Trade-off.** You gain one place to enforce rules and the freedom to change the internal
representation later. You pay a little ceremony (methods instead of field access). Encapsulation
is almost never wrong; the mistake is the opposite: adding a public getter and setter for every
field, which re-exposes everything and gives you the ceremony without the protection (see 22.12,
tell-don't-ask).

> **Common mistake:** Writing `private long balance;` and then `public void setBalance(long b)`.
> The field is private in name only. Expose operations that carry meaning (`deposit`,
> `withdraw`), not raw setters.

## 22.4 Abstraction

**Abstraction** means showing what something does and hiding how it does it. Callers depend on
a simple description ("it can compute its area", "it can send a message") instead of on the
details.

**Problem first.** A checkout page needs to charge a customer. If it calls a specific card
network's HTTP client directly, then the checkout code knows about URLs, retry codes, and XML
formats. Adding a second payment provider means editing checkout.

**The idea.** Describe the capability as an **interface**, a type that lists method signatures
with no implementation details:

```java
interface Shape {
    double area();
}
```

Anyone who holds a `Shape` can ask for its area without knowing if it is a circle or a
rectangle. Abstraction is what makes the other principles possible: you cannot swap an
implementation unless callers depend on an abstraction.

**Trade-off.** Each abstraction is one more type to name and read. An interface with exactly one
implementation that will never get a second one is mostly noise (see YAGNI in 22.12). Introduce
an abstraction where variation is real or likely, or where you need a seam for testing.

## 22.5 Inheritance

**Inheritance** lets one class (the **subclass**) take on the fields and methods of another (the
**superclass**) with `extends`, and then add or **override** (replace) behavior. It models an
"is-a" relationship: a `SavingsAccount` is a `BankAccount`.

The design reason is **reuse of shared code and a shared type**. The abstract class in 22.7 is a
good example: retry logic written once in the superclass, one small varying step in each
subclass.

Inheritance has real costs, covered in 22.8:

- The subclass depends on the superclass's internal behavior, not just its public contract.
  This is called the **fragile base class problem**: a harmless change in the parent breaks a
  child.
- It is fixed at compile time. An object cannot change its parent at runtime.
- Java allows only one superclass, so you spend that one slot carefully.

## 22.6 Polymorphism

**Polymorphism** ("many forms") means one call site works with many types, and the right
behavior is picked at runtime based on the actual object. In Java this happens through
overridden methods: the method that runs is chosen by the object's real class (**dynamic
dispatch**).

```java
record Circle(double radius) implements Shape {
    public double area() { return Math.PI * radius * radius; }
}

record Rectangle(double width, double height) implements Shape {
    public double area() { return width * height; }
}
```

```java
List<Shape> shapes = List.of(new Circle(1), new Rectangle(2, 3));
double total = 0;
for (Shape s : shapes) total += s.area();   // no if/else on the type
```

A **record** is a compact Java class for data: `record Circle(double radius)` declares a final
class with a private final field `radius`, a constructor, an accessor `radius()`, and `equals`,
`hashCode`, and `toString` based on the fields.

**Problem it removes.** Without polymorphism you write `if (shape is circle) ... else if (shape
is rectangle) ...` in every place that cares about shapes. Adding a triangle means finding and
editing all those places. With polymorphism, adding a triangle is one new class; the loop above
never changes.

> **Say it like this:** "Instead of a switch on the type in five places, I put the varying
> behavior behind an interface. Adding a new type is then one new class, and the callers don't
> change."

**Trade-off.** Behavior is spread across classes, so to understand "all the ways area is
computed" you open several files. When the set of types is small and fixed, and the operations
keep growing, a `switch` over a **sealed** type (a type that lists all its permitted subtypes)
can be the clearer choice. More on that choice in chapter 24.

## 22.7 Interfaces vs abstract classes

Both let you write code against a general type. They differ in what they can hold.

| | Interface | Abstract class |
|---|---|---|
| Instance fields (state) | No (only `static final` constants) | Yes |
| Constructors | No | Yes |
| Method bodies | `default`, `static`, and `private` methods allowed | Any |
| How many can a class use | Implement many | Extend only one |
| Relationship it expresses | "can do" (a capability) | "is a kind of" (shared identity + code) |

**Use an interface when** you are describing a capability that unrelated classes might have
(`Comparable`, `PaymentGateway`, `Shape`), or when you want the lightest possible contract for
swapping implementations and writing fakes in tests. This is the default choice.

**Use an abstract class when** several subclasses share state and an algorithm, and only some
steps vary. The class can hold the shared fields and make the algorithm `final` so subclasses
cannot break it:

```java
abstract class RetryingNotifier {
    private final int maxAttempts;               // shared state

    protected RetryingNotifier(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    // shared algorithm, written once, cannot be overridden
    public final boolean send(String to, String msg) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (trySend(to, msg, attempt)) return true;
        }
        return false;
    }

    protected abstract boolean trySend(String to, String msg, int attempt);
}

class FlakySmsNotifier extends RetryingNotifier {
    FlakySmsNotifier() { super(3); }

    @Override
    protected boolean trySend(String to, String msg, int attempt) {
        System.out.println("  SMS attempt " + attempt + " to " + to);
        return attempt == 2;                     // pretend the first try timed out
    }
}
```

This shape (fixed skeleton, varying steps) is the **Template Method** pattern from chapter 23.

A common combination: an interface for the contract (`Notifier`) plus an optional abstract base
class that implements it with helpful shared code. Callers depend on the interface; implementers
may extend the base class if it helps.

> **Common mistake:** Reaching for an abstract class just to share two helper methods. If there
> is no shared state and no fixed algorithm, prefer an interface plus a small helper class you
> compose in.

## 22.8 Composition over inheritance

**Composition** means building a class by holding references to other objects and delegating to
them ("has-a"), instead of extending them ("is-a").

### Where inheritance goes wrong: a concrete example

Requirement: a set that counts how many elements were ever added. The tempting solution is to
extend `HashSet` and override both add methods:

```java
class InheritedCountingSet<E> extends HashSet<E> {
    private int addCount = 0;

    @Override
    public boolean add(E e) {
        addCount++;
        return super.add(e);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return super.addAll(c);   // HashSet's addAll calls add() for each element!
    }

    int addCount() { return addCount; }
}
```

Adding three elements with `addAll` reports **6**, not 3. The `addAll` that `HashSet` inherits
works by calling `add` for each element, and because of dynamic dispatch that call lands in our
overridden `add`, which counts again. Our subclass silently depended on how the parent happens
to implement `addAll` (a detail `HashSet` inherits from `AbstractCollection` and never promises to
keep), so a correct-looking subclass is wrong, and could break differently if the parent changed.
This is the fragile base class problem in a few lines.

The composition version wraps a set instead of becoming one:

```java
class CountingSet<E> {
    private final Set<E> inner;           // has-a, not is-a
    private int addCount = 0;

    CountingSet(Set<E> inner) { this.inner = inner; }

    boolean add(E e) {
        addCount++;
        return inner.add(e);
    }

    boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return inner.addAll(c);           // calls inner.add, never our add
    }

    boolean contains(Object o) { return inner.contains(o); }
    int size() { return inner.size(); }
    int addCount() { return addCount; }
}
```

This reports 3. Running `java OopBasicsDemo.java` prints both numbers side by side.

Other signs inheritance was the wrong tool:

- **Inherited methods that make no sense.** `java.util.Stack` extends `Vector`, so a stack
  exposes `insertElementAt(obj, index)`, which lets callers break the last-in-first-out rule.
- **Combinatorial explosion.** `EmailNotifier`, `SmsNotifier`, `RetryingEmailNotifier`,
  `RetryingSmsNotifier`, `LoggingRetryingSmsNotifier`... Every new feature multiplies classes.
  With composition you wrap: `new Logging(new Retrying(new Sms()))` (the Decorator pattern,
  chapter 23).
- **Behavior must change at runtime.** A ride's fare rule switches to surge pricing during peak
  hours. A field holding a `FareStrategy` can be swapped; a superclass cannot.

**When inheritance is fine:** a true, stable "is-a" relationship inside code you control, where
the parent was designed for extension (documented hooks, `final` on what must not change).
Template Method is the classic legitimate use.

> **Say it like this:** "I'd rather compose than inherit here. Inheritance couples me to the
> parent's internals and fixes the choice at compile time; holding the behavior in a field lets
> me swap it and test it in isolation."

## 22.9 Relationships between classes

When you draw a class diagram (chapter 24) you label how classes relate. Each relationship has a
recognizable shape in code.

```text
 Relationship   Meaning                              Lifetime         UML arrow (ch 24)
 ------------   ----------------------------------   --------------   ------------------
 Dependency     A uses B briefly (param/local)       none             A - - - -> B
 Association    A holds a reference to B             independent      A ---------> B
 Aggregation    A has B's as parts, B can live alone independent      A <>-------- B
 Composition    A owns B; creates it, B dies with A  B dies with A    A <#>------- B
 Inheritance    A is a kind of B (extends)           -                A ---------|> B
 Realization    A implements interface B             -                A - - - - -|> B
```

How each looks in the demo code:

```java
class Team {                              // aggregation: holds references it did not create
    private final String name;
    private final List<Player> players = new ArrayList<>();
    Team(String name) { this.name = name; }
    void add(Player p) { players.add(p); }
}
```

A `Player` exists before and after the team; deleting the team does not delete players.

```java
class Order {
    private final String id;
    private final Customer customer;                     // association
    private final List<OrderLine> lines = new ArrayList<>(); // composition

    Order(String id, Customer customer) {
        this.id = id;
        this.customer = customer;
    }

    // The order creates its lines; they never exist outside it.
    void addLine(String sku, int qty, long unitPaise) {
        lines.add(new OrderLine(sku, qty, unitPaise));
    }
```

`Order -> Customer` is an **association**: the order points at a customer that has its own life.
`Order -> OrderLine` is **composition**: lines are created inside the order and are meaningless
without it. Notice that `addLine` takes the parts of a line, not a ready-made `OrderLine`; that
is how composition shows up in an API.

```java
class ReceiptPrinter {
    // dependency: uses Formatter only for the duration of the call
    void print(Order order, Formatter formatter) {
        System.out.println("  " + formatter.format(order));
    }
}
```

`ReceiptPrinter` has a **dependency** on `Formatter`: it does not store it, it just uses it. And
`PlainFormatter implements Formatter` is **realization**.

| Relationship | Code tell-tale |
|---|---|
| Dependency | type appears only as a method parameter, local variable, or return type |
| Association | field holding a reference passed in from outside |
| Aggregation | field holding a collection of objects passed in from outside |
| Composition | field holding objects the class creates itself and never hands out for keeps |
| Inheritance | `extends` |
| Realization | `implements` |

**Trade-off and honesty.** The line between association and aggregation is blurry and teams
disagree. In an interview, the useful distinction is **lifetime**: "if the parent is deleted, do
the children go too?" That is what composition means, and it drives real decisions (cascading
deletes, who is responsible for creating the child).

## 22.10 Cohesion and coupling

Two words you will hear in every design review.

**Cohesion** is how strongly the contents of one class belong together. A highly cohesive class
does one job; all its fields are used by most of its methods. A class called `Utils` with date
formatting, tax math, and email sending has low cohesion.

**Coupling** is how much one class depends on the details of another. Tight coupling: class A
reaches into B's fields, knows B's concrete type, or breaks when B's internals change. Loose
coupling: A depends on a small interface that B implements.

```text
  Goal: HIGH cohesion inside, LOW coupling between

   +------------------+          +------------------+
   | PricingService   |  uses    | <<interface>>    |
   |  all methods are | -------> | DiscountRule     |
   |  about price     |          +------------------+
   +------------------+                  ^
                                         | implements
                              +----------+-----------+
                              | FestivalDiscount ... |
                              +----------------------+
```

The DSA bridge: think of classes as nodes and "depends on" as directed edges. A change to one
class can only ripple to the classes that depend on it (its incoming edges, then theirs). Low
coupling means a sparse graph with few edges crossing module boundaries, so the set of classes
reachable backward from a change, the blast radius, stays small.

**Trade-off.** Driving coupling to zero means more interfaces and indirection. Aim for loose
coupling across boundaries that change independently (payment provider, storage, notification
channel), and accept direct coupling between classes that always change together.

## 22.11 SOLID

**SOLID** is an acronym for five principles about structuring classes. Each one is a reaction to
a specific pain. For each we show a violating version, the problem, and the fix. All code is in
`code/SolidDemo.java`.

### S — Single Responsibility Principle

**A class should have one reason to change.** "Responsibility" here means "an axis of change",
usually tied to one group of people asking for changes (finance changes tax rules, design changes
the invoice layout, ops changes storage).

Violating:

```java
// Violates SRP: tax rules, layout and storage all change for different reasons.
class InvoiceManager {
    void process(List<Long> itemsPaise) {
        long subtotal = 0;
        for (long p : itemsPaise) subtotal += p;
        long total = subtotal + subtotal * 18 / 100;          // tax rule
        String text = "INVOICE | total=" + total;             // presentation
        System.out.println("  [disk] " + text);               // persistence
    }
}
```

**The problem.** A change to the tax rate requires editing the same class as a change to the
layout. Testing the tax rule requires running the file-writing code. Two developers working on
unrelated features conflict in one file.

Fixed:

```java
class TaxCalculator {
    long totalWithTax(List<Long> itemsPaise) {
        long subtotal = 0;
        for (long p : itemsPaise) subtotal += p;
        return subtotal + subtotal * 18 / 100;
    }
}

class InvoiceRenderer {
    String render(long totalPaise) { return "INVOICE | total=" + totalPaise; }
}

class InvoiceStore {
    void save(String text) { System.out.println("  [disk] " + text); }
}

class InvoiceService {
    private final TaxCalculator tax;
    private final InvoiceRenderer renderer;
    private final InvoiceStore store;

    InvoiceService(TaxCalculator tax, InvoiceRenderer renderer, InvoiceStore store) {
        this.tax = tax;
        this.renderer = renderer;
        this.store = store;
    }

    void process(List<Long> itemsPaise) {
        store.save(renderer.render(tax.totalWithTax(itemsPaise)));
    }
}
```

**Trade-off.** More classes. Taken to the extreme ("one method per class") you get a maze. Split
when the parts really change for different reasons, not by line count.

### O — Open/Closed Principle

**Software should be open for extension but closed for modification.** You add new behavior by
adding new code, not by editing working code.

Violating:

```java
// Violates OCP: every new discount edits this method (and retests all of it).
class DiscountCalculatorBad {
    long discount(String type, long amountPaise, int qty) {
        switch (type) {
            case "FESTIVAL": return amountPaise * 10 / 100;
            case "BULK":     return qty >= 10 ? amountPaise * 5 / 100 : 0;
            default:         return 0;
        }
    }
}
```

**The problem.** Every new discount means reopening this method, risking the existing branches,
and the same `switch` tends to be copied into other places (display, reporting).

Fixed:

```java
// Fixed: new discount = new class. Checkout never changes.
interface DiscountRule {
    long discountOn(long amountPaise, int qty);
}

class FestivalDiscount implements DiscountRule {
    public long discountOn(long amountPaise, int qty) { return amountPaise * 10 / 100; }
}

class BulkDiscount implements DiscountRule {
    private final int minQty;
    BulkDiscount(int minQty) { this.minQty = minQty; }
    public long discountOn(long amountPaise, int qty) {
        return qty >= minQty ? amountPaise * 5 / 100 : 0;
    }
}

class Checkout {
    private final List<DiscountRule> rules;
    Checkout(List<DiscountRule> rules) { this.rules = List.copyOf(rules); }

    long totalDiscount(long amountPaise, int qty) {
        long d = 0;
        for (DiscountRule r : rules) d += r.discountOn(amountPaise, qty);
        return Math.min(d, amountPaise);
    }
}
```

**Trade-off.** You must guess where extension will happen. Making everything "open" up front
produces abstractions nobody uses. A practical rule: the first time, write the simple version;
when the second or third variant arrives, extract the interface.

> **Say it like this:** "Discounts are the thing that keeps changing, so I put them behind a
> `DiscountRule` interface. A new promotion is a new class; checkout stays closed for
> modification."

### L — Liskov Substitution Principle

**Objects of a subtype must be usable wherever the supertype is expected, without surprising the
caller.** A subclass may do more, but it must keep every promise the parent made: accept at
least the same inputs, return results within the same guarantees, and preserve the parent's
invariants.

Violating (the classic):

```java
// Violates LSP: a Square "is a" Rectangle in geometry, but not in behavior.
class MutableRectangle {
    protected int width, height;
    void setWidth(int w) { width = w; }
    void setHeight(int h) { height = h; }
    int area() { return width * height; }
}

class MutableSquare extends MutableRectangle {
    @Override void setWidth(int w) { width = w; height = w; }   // surprise!
    @Override void setHeight(int h) { width = h; height = h; }
}

class LiskovCheck {
    // Written against MutableRectangle; any subclass must keep this true.
    static boolean resizeWorks(MutableRectangle r) {
        r.setWidth(5);
        r.setHeight(4);
        return r.area() == 20;
    }
}
```

`resizeWorks(new MutableRectangle())` is true; `resizeWorks(new MutableSquare())` is false
(area 16). Code written correctly against the parent breaks when handed the child.

**The problem.** Callers start writing `if (r instanceof MutableSquare)`, which defeats
polymorphism and spreads special cases everywhere.

**Fix.** Do not model the relationship as inheritance. Make both independent implementations of
an immutable `Shape` (as in 22.6). Without setters there is no promise for the square to break.

Other common LSP violations: a subclass that throws `UnsupportedOperationException` for an
inherited method; a subclass that requires stricter input (parent accepts any positive amount,
child only accepts multiples of 100); a subclass that silently does nothing.

**Trade-off.** LSP sometimes forces you to give up an "obvious" hierarchy. That is the point: the
hierarchy should follow behavior, not real-world taxonomy.

### I — Interface Segregation Principle

**Clients should not be forced to depend on methods they do not use.** Prefer several small,
role-focused interfaces over one large one.

Violating:

```java
// Violates ISP: a simple printer must implement scan and fax it cannot do.
interface MultiFunctionDevice {
    void print(String doc);
    void scan(String doc);
    void fax(String doc);
}

class SimplePrinterBad implements MultiFunctionDevice {
    public void print(String doc) { System.out.println("  printing " + doc); }
    public void scan(String doc) { throw new UnsupportedOperationException("cannot scan"); }
    public void fax(String doc) { throw new UnsupportedOperationException("cannot fax"); }
}
```

**The problem.** Implementers write fake methods that throw (which is also an LSP violation),
callers cannot tell from the type what really works, and any change to `fax` forces every
implementer and client to recompile and re-test.

Fixed:

```java
// Fixed: small, role-based interfaces; a class implements only what it can do.
interface Printer { void print(String doc); }
interface DocumentScanner { void scan(String doc); }
interface FaxMachine { void fax(String doc); }

class SimplePrinter implements Printer {
    public void print(String doc) { System.out.println("  printing " + doc); }
}

class OfficeMachine implements Printer, DocumentScanner, FaxMachine {
    public void print(String doc) { System.out.println("  printing " + doc); }
    public void scan(String doc) { System.out.println("  scanning " + doc); }
    public void fax(String doc) { System.out.println("  faxing " + doc); }
}
```

**Trade-off.** Too many one-method interfaces scatter a concept. Group methods that are always
used together by the same clients.

### D — Dependency Inversion Principle

**High-level policy should not depend on low-level details; both should depend on
abstractions.** The business rule ("charge, then notify") should not know about the specific
card network client. And the abstraction should be shaped by what the high-level code needs, not
by what the low-level library happens to offer.

Violating:

```java
// Violates DIP: the business rule creates and is welded to a concrete client.
class CardGatewayClient {
    boolean charge(String orderId, long paise) {
        System.out.println("  calling real card network...");
        return true;
    }
}

class OrderServiceBad {
    private final CardGatewayClient gateway = new CardGatewayClient(); // hard-wired
    boolean placeOrder(String orderId, long paise) { return gateway.charge(orderId, paise); }
}
```

**The problem.** You cannot test `OrderServiceBad` without hitting the real card network. You
cannot switch providers without editing it. The important code depends on the unimportant code.

Fixed:

```java
// Fixed: the service owns the abstractions it needs; details plug in.
interface PaymentGateway {
    boolean charge(String orderId, long paise);
}

interface Notifier {
    void send(String userId, String message);
}

class OrderService {
    private final PaymentGateway gateway;
    private final Notifier notifier;

    // Constructor injection: dependencies are explicit, final, and swappable.
    OrderService(PaymentGateway gateway, Notifier notifier) {
        this.gateway = Objects.requireNonNull(gateway);
        this.notifier = Objects.requireNonNull(notifier);
    }

    boolean placeOrder(String orderId, String userId, long paise) {
        boolean ok = gateway.charge(orderId, paise);
        notifier.send(userId, ok ? "order " + orderId + " confirmed"
                                   : "payment failed for " + orderId);
        return ok;
    }
}
```

```text
   BEFORE                         AFTER (dependency arrow "inverted")

   OrderServiceBad                OrderService ----> <<interface>> PaymentGateway
        |                                                    ^
        v                                                    | implements
   CardGatewayClient                           CardGatewayAdapter, FakeGateway
```

The arrow from the detail now points up to the abstraction. That reversal is the "inversion".

**Trade-off.** One more interface and some wiring code. Not worth it for stable, side-effect-free
dependencies like `List` or `Math`; worth it for anything that does I/O, has state you need to
control in tests, or might be replaced.

### SOLID at a glance

| Principle | One line | Smell that signals the violation |
|---|---|---|
| S | One reason to change | Class name ends in Manager/Handler and does five things |
| O | Extend by adding code | Growing `switch` on a type string or enum in many places |
| L | Subtypes keep the parent's promises | `instanceof` checks, `UnsupportedOperationException` overrides |
| I | Small client-specific interfaces | Implementations with empty or throwing methods |
| D | Depend on abstractions you own | `new ConcreteClient()` inside business logic |

## 22.12 Other principles you will hear

### DRY — Don't Repeat Yourself

**Every piece of knowledge should have one authoritative place.** If the tax rate is written in
three files, one will be forgotten during an update. DRY is about knowledge, not about text: two
pieces of code that look the same but change for different reasons are not duplicates, and
merging them couples unrelated things.

### KISS — Keep It Simple

Prefer the simplest design that meets today's requirements. A `HashMap` beats a plugin
framework when there are two cases. In an interview, a clear simple design that you can extend
scores better than a clever one you cannot finish.

### YAGNI — You Aren't Gonna Need It

Do not build features or extension points for requirements that do not exist yet. Each speculative
abstraction costs reading time and maintenance. Balance against OCP by asking: "Is this change
likely, and would it be expensive to add later?" If the answer is no, wait.

### Law of Demeter (principle of least knowledge)

**A method should talk only to its close friends:** its own fields, its parameters, objects it
creates, and itself. It should not reach through a chain of objects.

```java
// Violates: reaches through three objects (sketch; the demo's Order
// deliberately has no customer() accessor so this cannot be written)
String city = order.customer().address().city();
```

The caller now depends on `Order`, `Customer`, and `Address` and their shapes. Ask the object
that has the knowledge instead:

```java
    // Law of Demeter: callers ask the order, not order.customer().address().city()
    String shippingCity() { return customer.address().city(); }
```

**Trade-off.** Followed blindly you get dozens of forwarding methods. Chains over plain data
records or fluent builders (`builder.a().b().build()`) are fine; the concern is chains that
navigate an object graph of behavior-carrying classes.

### Tell, don't ask

Instead of asking an object for its data and making a decision for it, tell it what you want and
let it decide.

```java
// Sketch, not in the demo.
// Ask (logic lives in the caller, rules get duplicated):
if (account.balance() >= amount) account.setBalance(account.balance() - amount);

// Tell (the rule lives with the data):
account.withdraw(amount);
```

`BankAccount.withdraw` in 22.3 is the "tell" version. This is encapsulation applied to method
design.

### Program to an interface, not an implementation

Declare variables, fields, and parameters with the most general type that gives you what you
need: `List<Order> orders = new ArrayList<>()`, `PaymentGateway gateway`. Then the concrete class
can change in one place (where the object is created) without touching users.

### Dependency injection (DI)

**Dependency injection** means an object receives its collaborators from outside instead of
creating them itself. The three forms:

| Form | Example | Notes |
|---|---|---|
| Constructor injection | `new OrderService(gateway, notifier)` | Preferred: dependencies are required, `final`, visible in one place |
| Setter injection | `service.setGateway(g)` | Optional dependencies; object may be half-built |
| Field injection | a framework writes a private field via reflection | Convenient, but hides dependencies and hurts testing |

**Why constructor injection enables testing.** Because `OrderService` takes interfaces in its
constructor, a test can pass tiny hand-written fakes:

```java
// Test doubles: tiny classes, no framework.
class FakePaymentGateway implements PaymentGateway {
    final List<String> charges = new ArrayList<>();
    private final boolean approve;
    FakePaymentGateway(boolean approve) { this.approve = approve; }

    public boolean charge(String orderId, long paise) {
        charges.add(orderId + ":" + paise);
        return approve;
    }
}

class RecordingNotifier implements Notifier {
    final List<String> sent = new ArrayList<>();
    public void send(String userId, String message) { sent.add(userId + ": " + message); }
}
```

```java
        FakePaymentGateway declining = new FakePaymentGateway(false);
        RecordingNotifier notifier2 = new RecordingNotifier();
        OrderService service2 = new OrderService(declining, notifier2);
        check(!service2.placeOrder("O-43", "U-1", 5_000), "declined order fails");
        check(notifier2.sent.equals(List.of("U-1: payment failed for O-43")), "failure notified");
```

The test runs in microseconds, needs no network, and can simulate a declined card on demand. A
**test double** is any stand-in object used in tests; a **fake** has a working simplified
implementation, a **stub** returns canned answers, a **mock** also verifies which calls were
made.

A **DI container** (a framework that builds the object graph for you from configuration or
annotations) is just automation of the same idea. You do not need one to do DI; a `main` method
that calls constructors is DI.

> **Say it like this:** "The service takes its gateway and notifier through the constructor as
> interfaces. In production I pass the real adapters, in tests I pass fakes, and the service code
> is identical in both."

> **Common mistake:** Injecting dependencies but then calling `new` on a concrete helper deep
> inside a method "just this once". That one `new` is the part you will not be able to replace
> in a test.

## 22.13 Immutability and value objects

An **immutable** object cannot change after construction: all fields are `final`, there are no
setters, and any field holding a mutable object (like a list) is copied so outside code cannot
change it.

**Why it matters for design:**

- **Thread safety for free.** An object that never changes can be shared between threads with
  no locks (chapter 26), provided it is safely published (for example via a `final` field).
- **Safe as map keys and set members.** A key whose `hashCode` changes after insertion gets
  "lost" inside a `HashMap`.
- **No defensive coding at call sites.** You can pass it anywhere without fearing that someone
  modifies it.
- **Easier reasoning.** Validate once in the constructor; the object is valid forever.

The cost: every "change" allocates a new object. For small objects this is cheap on the JVM;
for large structures that change constantly it can matter.

A **value object** is an immutable object defined entirely by its values, with no identity of
its own. Two `Money(1000)` objects are interchangeable. Records are a natural fit:

```java
record Money(long paise) {
    Money {                                   // compact constructor = validation
        if (paise < 0) throw new IllegalArgumentException("negative money");
    }

    static Money ofRupees(long rupees) { return new Money(rupees * 100); }

    Money plus(Money other) { return new Money(paise + other.paise); } // new object

    @Override
    public String toString() {
        return String.format("Rs %d.%02d", paise / 100, paise % 100);
    }
}
```

A **compact constructor** is the record form `Money { ... }` with no parameter list; it runs
before the fields are assigned and is the place for validation.

Using a value type instead of a raw `long` (sometimes called avoiding **primitive obsession**)
makes illegal states unrepresentable: a `Money` can never be negative, and you cannot
accidentally add a quantity to an amount.

Defensive copying for collections: `List.copyOf(lines)` returns an unmodifiable copy. In the
demo, `Order.lines()` returns `List.copyOf(lines)` so callers cannot add lines behind the order's
back.

> **Common mistake:** Declaring `record Cart(List<Item> items)` and assuming it is immutable.
> The record's field is final, but the list it points to can still be modified by whoever holds
> it. Copy it in the compact constructor: `items = List.copyOf(items);`.

## 22.14 Entity vs value object

| | Entity | Value object |
|---|---|---|
| Identity | Has a stable id; identity matters | No id; defined by its values |
| Equality | Same id = same thing, even if fields differ | Same values = equal |
| Mutability | Usually mutable over its lifecycle | Immutable |
| Examples | Customer, Order, ParkingTicket, Booking | Money, Address, DateRange, SeatNumber, Coordinates |

A customer who changes their email is still the same customer. That is an **entity**, and its
`equals` should compare ids:

```java
    @Override
    public boolean equals(Object o) {
        return o instanceof Customer other && id.equals(other.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
```

`o instanceof Customer other` is a **pattern match**: it checks the type and, if it matches,
binds the cast value to `other` in one step.

Whenever you override `equals`, override `hashCode` consistently, or hash-based collections
break (equal objects must have equal hash codes).

Why the distinction matters in LLD: entities are the things your repositories store and look up
by id, and they usually carry the lifecycle and state transitions. Value objects are the
vocabulary those entities are written in. A good model has a few entities and many small value
objects.

> **Say it like this:** "Booking is an entity, it has an id and a lifecycle. Seat number, money,
> and time slot are value objects: immutable, compared by value, and they validate themselves."

## 22.15 Running the demos

```text
cd ~/learn-system-design/code
java OopBasicsDemo.java     # encapsulation, polymorphism, composition vs inheritance,
                            # relationships, value objects, entities, Law of Demeter
java SolidDemo.java         # each SOLID principle, violating vs fixed, DI with fakes
```

Expected highlights: the inherited counting set reports 6 where 3 was expected while the composed
one reports 3; the square fails the rectangle resize check; the `OrderService` checks pass using
fakes.

## 22.16 Recap

- LLD is the structure inside a component; its purpose is to keep the cost of the next change
  low.
- Encapsulation puts rules next to data; expose meaningful operations, not setters.
- Abstraction plus polymorphism replace type switches scattered through the code with one
  interface and one class per variant.
- Interfaces describe capabilities (the default); abstract classes share state and a fixed
  algorithm.
- Prefer composition: inheritance couples you to the parent's internals (the counting set reports
  6 instead of 3).
- Relationships: dependency, association, aggregation, composition (lifetime-bound), inheritance,
  realization. Lifetime is the question that separates composition from aggregation.
- Aim for high cohesion inside a class and low coupling between classes.
- SOLID: one reason to change; extend by adding code; subtypes keep promises; small interfaces;
  depend on abstractions you own.
- DRY is about knowledge, KISS and YAGNI keep you from over-building, Law of Demeter and
  tell-don't-ask keep logic with its data.
- Constructor injection makes dependencies explicit and lets tests pass fakes.
- Value objects are immutable and equal by value; entities have identity and a lifecycle.

## 22.17 Check yourself

1. A teammate wants to give `BankAccount` a public `setBalance`. What do you lose, and what would
   you offer instead?

<details><summary>Answer</summary>

You lose the single place where balance rules are enforced (never negative, only changes through
real transactions, audit logging). Any caller could set any value. Offer intention-revealing
operations such as `deposit`, `withdraw`, and `applyInterest`, each validating its input. This is
encapsulation plus tell-don't-ask.

</details>

2. Why did `InheritedCountingSet` report 6 for three elements, and why does the composed version
   not have the problem?

<details><summary>Answer</summary>

The inherited `addAll` of `HashSet` adds elements by calling `add` on each one. Dynamic dispatch
routes those calls to the subclass's overridden `add`, which counts each element a second time.
The subclass depended on an implementation detail of its parent. The composed
version holds a separate `Set` and calls `inner.addAll`, which calls `inner.add`, never the
wrapper's own `add`, so each element is counted once.

</details>

3. When would you choose an abstract class over an interface?

<details><summary>Answer</summary>

When subclasses share state (fields) and a common algorithm, and only some steps vary. The
abstract class can hold the fields, provide a constructor, and make the algorithm `final` while
declaring the varying steps `abstract` (Template Method). If there is no shared state or
algorithm, an interface is lighter and lets a class implement many capabilities.

</details>

4. Give two code smells that suggest a Liskov substitution violation.

<details><summary>Answer</summary>

Callers checking `instanceof` for a specific subclass before calling a method; subclasses
overriding a method to throw `UnsupportedOperationException` or do nothing; a subclass that
rejects inputs the parent accepted; a subclass that weakens a guarantee the parent gave (for
example, returning null where the parent never did).

</details>

5. What exactly is "inverted" in the dependency inversion principle?

<details><summary>Answer</summary>

The direction of the source-code dependency. Naively the high-level service depends on the
low-level client. After inversion the high-level code defines (owns) an interface shaped by its
needs, and the low-level detail depends on that interface by implementing it. Both now depend on
the abstraction, and the arrow from the detail points toward the policy.

</details>

6. Why is constructor injection preferred over field injection?

<details><summary>Answer</summary>

Dependencies become explicit in the constructor signature, can be `final`, and the object cannot
exist in a half-built state. Tests can construct the object directly with fakes without any
framework or reflection. Field injection hides dependencies and requires a container (or
reflection) to set them.

</details>

7. Is `Address` an entity or a value object in a food delivery app? Could the answer change?

<details><summary>Answer</summary>

Usually a value object: two addresses with the same street, city, and pin code are
interchangeable, and you replace an address rather than mutate it. It could become an entity if
the business needs identity and lifecycle for it, for example saved addresses that users name,
edit, and that orders reference by id ("Home", "Office"). The model follows the business meaning.

</details>

8. Your record `Cart(List<Item> items)` is shared between threads and occasionally shows items
   that were never added through the cart. What is wrong?

<details><summary>Answer</summary>

The record's field is final, but the list object is mutable and was not copied, so whoever
created the list can still modify it (and `ArrayList` is not thread-safe either). Fix by copying
in the compact constructor with `items = List.copyOf(items);`, which stores an unmodifiable copy.
Then the cart is truly immutable and safe to share.

</details>
