# 22 — OOP and SOLID

*Why it matters:* LLD rounds and code reviews are argued in this vocabulary ("violates open/closed",
"inject it through the constructor"); knowing the reason behind each word lets you defend a design.

## What LLD is

**How it works** · **Low-level design (LLD)** is the code inside one component: classes, fields,
methods, interfaces, who owns or calls whom. Any code works once; LLD exists for **change** (new
requirements, people, concurrency): good structure keeps the cost of the next change roughly flat.

**Example** · QuickBite's `payment-service` must add UPI next to cards.

| | Badly structured | Well structured |
|---|---|---|
| Where card logic lives | `if (method == CARD)` in checkout, refunds, reports | Behind a `PaymentGateway` interface |
| Change needed for UPI | Edit 3 files, retest all of them | Add `UpiAdapter implements PaymentGateway` + 1 wiring line |

So: a new class instead of surgery. The **LLD / machine coding round** (ch 24) checks exactly that.

> **Say it like this:** "LLD is about making the next change cheap. I want each new requirement
> to be a new class or a small local edit, not surgery across the codebase."

## Classes, objects and the four pillars

**How it works** · A **class** is a blueprint (**fields** + **methods**); an **object** (instance)
is one concrete thing with its own fields; a **constructor** makes it valid from the first moment.
The design reason: **group data with the rules that protect it**, so the rules live in one place.

| Pillar | QuickBite scenario | What it is | Cost |
|---|---|---|---|
| **Encapsulation** | Wallet balance must never go negative | Hide state; change only via methods (`deposit`, `withdraw`); free to change representation | Small ceremony |
| **Abstraction** | Checkout charges "a gateway", not a card network's XML client | Show what, hide how (an **interface** = signatures only); enables swapping and fakes | Extra type; a one-impl interface that never gets a second is noise |
| **Inheritance** | `FlakySmsNotifier extends RetryingNotifier` reuses the retry loop | `extends`: reuse and **override** ("is-a") | Fragile base class, fixed at compile time, one superclass only |
| **Polymorphism** | `channel.send()` runs SMS or push code, chosen by the real object | Behavior picked at runtime (**dynamic dispatch**); kills scattered `if (type == ...)` | Behavior spread over files; for a small fixed type set a `switch` over a **sealed** type can be clearer |

**Example** · `code/OopBasicsDemo.java`:
1. `deposit(50_000)`, `withdraw(20_000)`: 30,000 paise. `withdraw(1_000_000)` throws
   "insufficient funds"; balance still 30,000. The rule lives in one method.
2. `for (Shape s : List.of(new Circle(1), new Rectangle(2, 3))) total += s.area();` gives
   3.14 + 6 = 9.14 with no type check; a `Triangle` would be one new class, loop unchanged.

- **Access modifiers:** `private` (this class), package-private (same package), `protected`
  (package + subclasses: hooks), `public` (the API). Money as `long` paise (or `BigDecimal`),
  never `double` (binary floating point can't hold 0.1). A **record** (`record Circle(double
  radius)`) is a final data class: fields, constructor, accessors, `equals`/`hashCode`/`toString`.

> **Common mistake:** `private long balance;` plus `public void setBalance(long b)`. Private in
> name only. Expose meaningful operations (`deposit`, `withdraw`), not raw setters.

## Interfaces vs abstract classes

**How it works** · Both let callers code against a general type; they differ in what they hold.

| | Interface | Abstract class |
|---|---|---|
| State / constructors | No (only `static final` constants) | Yes |
| Method bodies | `default`, `static`, `private` | Any |
| How many per class / expresses | Implement many / "can do" | Extend one / "is a kind of" |

- **Interface** is the default: capabilities of unrelated classes (`Comparable`, `PaymentGateway`),
  lightest contract for swapping and test fakes.
- **Abstract class** when subclasses share state and an algorithm and only steps vary; make the
  algorithm `final` (**Template Method**, ch 23). Combo: interface for the contract + optional
  abstract base. No shared state? Compose a helper.

**Example** · QuickBite's OTP SMS times out on the first try. `RetryingNotifier` holds
`maxAttempts = 3`; its `final send()` loops over `abstract trySend(...)`:
```text
send("+91-98xxxx", "OTP 1234")   loop lives once, in the base class
  trySend attempt 1 -> false (timeout);  attempt 2 -> true;  attempt 3 never runs
```

## Composition over inheritance

**How it works** · **Composition** = hold a reference and delegate ("has-a") instead of extending.

**Example** · Count every element ever added, by extending `HashSet` and overriding `add` + `addAll`:
```text
bad.addAll([a, b, c])
  our addAll:      addCount += 3                 -> 3
  super.addAll:    calls add(a), add(b), add(c)  (inherited detail)
  dynamic dispatch lands in OUR add, 3 times     -> 6   (expected 3)
```
It silently depended on a parent detail (**fragile base class problem**). Wrap a `Set` instead:

```java
class CountingSet<E> {
    private final Set<E> inner;           // has-a, not is-a
    // ...
    boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return inner.addAll(c);           // calls inner.add, never our add -> reports 3
    }
}
```

Other signs inheritance was wrong: **nonsense inherited methods** (`Stack extends Vector` exposes
`insertElementAt`, breaking LIFO); **combinatorial explosion** (`LoggingRetryingSmsNotifier`;
compose `new Logging(new Retrying(new Sms()))` = Decorator); **behavior changing at runtime**
(QuickBite's delivery fee switches to surge at 8 pm: a `FareStrategy` field can be swapped, a
superclass can't). Inheritance is fine for a true, stable "is-a" in your own code where the parent
was designed for extension (Template Method).

## Relationships between classes

**How it works** · The useful question is **lifetime**: delete the parent, do children go too? That
is composition; it drives cascading deletes and who creates the child (association vs aggregation
is blurry; teams disagree).

| Relationship | QuickBite scenario | Code tell-tale |
|---|---|---|
| **Dependency** | Receipt printer uses a formatter for one call | B only as param/local/return (`print(Order, Formatter)`) |
| **Association** | Order points at the customer who placed it | Field set from outside (`Order -> Customer`) |
| **Aggregation** | A delivery zone lists riders who exist without it | Collection filled from outside (`Team` of `Player`s) |
| **Composition** | Order lines exist only inside their order | Creates parts itself (`order.addLine(sku, qty, price)`) |
| **Inheritance / Realization** | `PlainFormatter implements Formatter` | `extends` / `implements` |

**Example** · `order.addLine("SKU-1", 2, 300); order.addLine("SKU-2", 1, 500);` then
`order.total()` is 2 x 300 + 500 = 1,100. Delete order O-1: its 2 lines go (composition); customer
C-1 stays (association); `team.add(p1)` took a player that already existed (aggregation).

## Cohesion and coupling

**How it works** · **Cohesion**: how strongly a class's contents belong together (one job; a
`Utils` with dates, tax and email is low). **Coupling**: how much a class depends on another's
details (reaching into fields, concrete types = tight; small interface = loose). Goal: high
cohesion inside, low coupling between.

**Example** · QuickBite swaps its SMS provider; a change ripples backward along "depends on" edges:
```text
tight: OrderService, RiderService, AuthService --new--> VendorSmsClient   3 classes edited
loose: OrderService, RiderService, AuthService --> Notifier <-- VendorSmsAdapter
       only the adapter changes; blast radius = 1 class
```
So: decouple boundaries that change independently (payment, SMS); accept coupling for the rest.

## SOLID

**How it works** · Five principles, each a reaction to a specific pain (run `java SolidDemo.java`).

**S — Single Responsibility.** One reason to change (one axis, usually one group asking).
*Scenario:* QuickBite's invoice: finance changes GST, design changes the layout, ops moves storage.

```java
// Violates SRP: tax rules, layout and storage all change for different reasons.
class InvoiceManager {
    void process(List<Long> itemsPaise) {
        // ... subtotal ...
        long total = subtotal + subtotal * 18 / 100;          // tax rule
        String text = "INVOICE | total=" + total;             // presentation
        System.out.println("  [disk] " + text);               // persistence
    }
}
```

**Example** · Items 10,000 + 25,000 = 35,000 paise; 18% tax = 6,300; prints `INVOICE | total=41300`.
Fixed (`TaxCalculator`, `InvoiceRenderer`, `InvoiceStore` composed by `InvoiceService`): a GST
change edits only `TaxCalculator`, same output. Split by reason to change, not line count.

**O — Open/Closed.** Open for extension, closed for modification: add behavior by adding code.
*Scenario:* QuickBite marketing adds a new promo every festival.

```java
// Violates OCP: every new discount edits this method (and retests all of it).
switch (type) {
    case "FESTIVAL": return amountPaise * 10 / 100;
    case "BULK":     return qty >= 10 ? amountPaise * 5 / 100 : 0;
    default:         return 0;
}
```

**Example** · Fix: `interface DiscountRule { long discountOn(long amountPaise, int qty); }`, a class
per discount, `Checkout` sums a `List<DiscountRule>` (capped at the amount). Catering order of
120,000 paise, 12 items: `FestivalDiscount` 10% = 12,000 + `BulkDiscount(10)` 5% = 6,000 = 18,000
(the `switch` applies one type: 12,000). A "first order" promo = one new class. Extract the
interface when variant two or three arrives, not up front.

**L — Liskov Substitution.** A subtype works wherever the parent is expected: same inputs accepted,
same guarantees and invariants kept. *Scenario:* a `CashOnDelivery` payment whose `refund()` throws
breaks every caller written for `Payment`. The classic, in code:

```java
class MutableSquare extends MutableRectangle {
    @Override void setWidth(int w) { width = w; height = w; }   // surprise!
    @Override void setHeight(int h) { width = h; height = h; }
}
```

**Example** · `resizeWorks(r)`: `setWidth(5); setHeight(4); return area() == 20;`. Rectangle: 20,
true. Square: both sides become 4, area 16, false. Fix: no inheritance; independent immutable
`Shape`s. Other smells: throwing `UnsupportedOperationException`, stricter inputs, silent no-ops,
`instanceof` checks. Hierarchies follow behavior, not real-world taxonomy.

**I — Interface Segregation.** Clients should not depend on methods they don't use.
*Scenario:* one fat `PartnerApp` interface would force the rider app to stub "accept order" and
the restaurant tablet to stub "report location".

**Example** · `interface MultiFunctionDevice { print; scan; fax }` forces `SimplePrinterBad` to
throw "cannot fax". Fix: `Printer`, `DocumentScanner`, `FaxMachine`; `SimplePrinter` implements
one, `OfficeMachine` all three. Don't go one-method-per-interface.

**D — Dependency Inversion.** Policy and details both depend on an abstraction owned (shaped) by
the high-level code. *Scenario:* `order-service` must not be welded to one card network's client.

**Example** · `OrderServiceBad` holds `new CardGatewayClient()` (hard-wired, untestable without
the real network). Fix: `OrderService(PaymentGateway, Notifier)` via constructor; the client becomes
an adapter implementing `PaymentGateway`, so the detail's arrow points up to the abstraction (the
"inversion"). Skip for stable pure deps (`List`, `Math`); use for I/O or anything replaceable.

| Principle | One line | Smell |
|---|---|---|
| S | One reason to change | `...Manager` doing five things |
| O | Extend by adding code | Growing `switch` on a type in many places |
| L | Subtypes keep the parent's promises | `instanceof` checks, throwing overrides |
| I | Small client-specific interfaces | Empty or throwing implementations |
| D | Depend on abstractions you own | `new ConcreteClient()` in business logic |

## Other principles, DI, immutability, entities

**How it works** · Each is one habit with one trade-off.

| Principle | QuickBite scenario | Meaning / watch out |
|---|---|---|
| **DRY** | GST rate typed in 3 files; one is missed on update | One home per piece of *knowledge*; look-alike code that changes for different reasons is not a duplicate |
| **KISS** | Two delivery-fee types | `HashMap` over a plugin framework; a simple design you finish beats a clever one you don't |
| **YAGNI** | "Maybe drones later" extension points | Don't build for requirements that don't exist; vs OCP: is the change likely and costly later? |
| **Law of Demeter** | Rider app needs the drop city | Not `order.customer().address().city()`; add `order.shippingCity()`; chains over records/builders are fine |
| **Tell, don't ask** | Paying from the wallet | `wallet.withdraw(amt)`, not read balance then `setBalance` |
| **Program to an interface** | `PaymentGateway gateway`, `List<Order> orders` | Concrete class chosen in one place |
| **Dependency injection** | `new OrderService(gateway, notifier)` in `main` | Constructor (preferred: required, `final`, visible) / setter (optional, half-built risk) / field via reflection (hides deps) |
| **Immutability** | `Money` shared across threads | `final` fields, no setters, copy mutable fields (`List.copyOf`); lock-free sharing if safely published (ch 26), safe map keys; each "change" allocates |
| **Value object** | ₹450, an address, a time slot | No id, equal by values, immutable; record + **compact constructor** `Money { if (paise < 0) throw ...; }` beats **primitive obsession** (raw `long`) |
| **Entity** | Order `o_90311`, user `u_1842` | Stable id and lifecycle; `equals`/`hashCode` on id only, always overridden together |

**Example** · DI makes the "card declined" test trivial, no network, microseconds:
```text
service = new OrderService(new FakePaymentGateway(false), new RecordingNotifier())
service.placeOrder("O-43", "U-1", 5_000)  -> false
notifier.sent == ["U-1: payment failed for O-43"]
```
**Fake** = working simple version, **stub** = canned answers, **mock** = also verifies calls (all
are **test doubles**). A **DI container** only automates wiring; a `main` calling constructors is DI.

**Example** · `Money.ofRupees(10).equals(Money.ofRupees(10))` is true; `a.plus(b)` returns a new
`Rs 20.00`, `a` stays `Rs 10.00`. Customer C-7 (`old@x.com`, Delhi) equals C-7 (`new@x.com`, Goa):
same id. Repositories store entities by id; value objects are their vocabulary (few entities, many
values). `Address` becomes an entity if the business gives it identity (saved "Home").

> **Common mistake:** `record Cart(List<Item> items)` is not immutable: the field is final, the
> list isn't. Copy in the compact constructor: `items = List.copyOf(items);`.

> **Say it like this:** "Booking is an entity, it has an id and a lifecycle. Seat number, money,
> and time slot are value objects: immutable, compared by value, and they validate themselves."

## In one minute

- LLD is the structure inside a component; its job is to keep the next change cheap (UPI = one new class).
- Encapsulation puts rules next to data; expose operations, not setters.
- Abstraction + polymorphism replace scattered type switches with an interface and a class per variant.
- Interfaces describe capabilities (default); abstract classes share state and a `final` algorithm.
- Prefer composition: inheritance couples you to parent internals (counting set says 6, not 3).
- Composition vs aggregation is about lifetime; aim for high cohesion, low coupling.
- SOLID: one reason to change; extend by adding; subtypes keep promises; small interfaces;
  depend on abstractions you own, injected through the constructor so tests pass fakes.
- Value objects are immutable and equal by value; entities have identity and a lifecycle.

## Go deeper

Full chapter 22: sections 22.8 (composition over inheritance), 22.11 (SOLID with code), 22.12 (DI and fakes), 22.14 (entity vs value object)
