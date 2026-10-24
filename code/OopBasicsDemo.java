import java.util.*;

// Chapter 22 - OOP building blocks: encapsulation, abstraction, polymorphism,
// interface vs abstract class, composition over inheritance, relationships,
// value objects vs entities, Law of Demeter, tell-don't-ask.
// Run: java OopBasicsDemo.java
public class OopBasicsDemo {
    public static void main(String[] args) {
        System.out.println("== Encapsulation ==");
        BankAccount acc = new BankAccount("ACC-1");
        acc.deposit(50_000);
        acc.withdraw(20_000);
        try {
            acc.withdraw(1_000_000);
        } catch (IllegalStateException e) {
            System.out.println("Rejected: " + e.getMessage());
        }
        System.out.println("Balance (paise): " + acc.balance());

        System.out.println("== Polymorphism ==");
        List<Shape> shapes = List.of(new Circle(1), new Rectangle(2, 3));
        double total = 0;
        for (Shape s : shapes) total += s.area();   // no if/else on the type
        System.out.printf("Total area: %.2f%n", total);

        System.out.println("== Abstract class with shared algorithm ==");
        RetryingNotifier sms = new FlakySmsNotifier();
        System.out.println("Delivered: " + sms.send("+91-98xxxx", "OTP 1234"));

        System.out.println("== Inheritance gone wrong vs composition ==");
        InheritedCountingSet<String> bad = new InheritedCountingSet<>();
        bad.addAll(List.of("a", "b", "c"));
        System.out.println("Inheritance addCount (expected 3): " + bad.addCount());
        CountingSet<String> good = new CountingSet<>(new HashSet<>());
        good.addAll(List.of("a", "b", "c"));
        System.out.println("Composition addCount (expected 3): " + good.addCount());

        System.out.println("== Relationships ==");
        Player p1 = new Player("player-1");
        Team team = new Team("Blue");
        team.add(p1);                         // aggregation: player existed before team
        Order order = new Order("O-1", new Customer("C-1", "a@x.com",
                new Address("12 MG Road", "Pune")));
        order.addLine("SKU-1", 2, 300);       // composition: line created inside order
        order.addLine("SKU-2", 1, 500);
        new ReceiptPrinter().print(order, new PlainFormatter()); // dependency

        System.out.println("== Value objects vs entities ==");
        Money a = Money.ofRupees(10);
        Money b = Money.ofRupees(10);
        System.out.println("Money equal by value: " + a.equals(b));
        System.out.println("a + b = " + a.plus(b) + ", a is unchanged: " + a);
        Customer c1 = new Customer("C-7", "old@x.com", new Address("1 Lane", "Delhi"));
        Customer c2 = new Customer("C-7", "new@x.com", new Address("9 Road", "Goa"));
        System.out.println("Customers equal by id: " + c1.equals(c2));

        System.out.println("== Law of Demeter / tell, don't ask ==");
        System.out.println("Ship to: " + order.shippingCity());
        System.out.println("Order total: " + order.total());
    }
}

// ---------- Encapsulation ----------
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

// ---------- Abstraction + polymorphism via an interface ----------
interface Shape {
    double area();
}

record Circle(double radius) implements Shape {
    public double area() { return Math.PI * radius * radius; }
}

record Rectangle(double width, double height) implements Shape {
    public double area() { return width * height; }
}

// ---------- Abstract class: shared state + shared algorithm ----------
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

// ---------- Inheritance gone wrong ----------
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

// ---------- Composition: wrap, don't extend ----------
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

// ---------- Relationships ----------
class Player {                            // lives on its own
    private final String name;
    Player(String name) { this.name = name; }
    String name() { return name; }
}

class Team {                              // aggregation: holds references it did not create
    private final String name;
    private final List<Player> players = new ArrayList<>();
    Team(String name) { this.name = name; }
    void add(Player p) { players.add(p); }
}

record Address(String street, String city) {}

class Customer {                          // entity: identity = id
    private final String id;
    private String email;                 // can change; still the same customer
    private Address address;

    Customer(String id, String email, Address address) {
        this.id = id;
        this.email = email;
        this.address = address;
    }

    String id() { return id; }
    Address address() { return address; }
    void changeEmail(String newEmail) { this.email = newEmail; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Customer other && id.equals(other.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}

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

    long total() {
        long sum = 0;
        for (OrderLine l : lines) sum += l.subtotal();
        return sum;
    }

    String id() { return id; }
    List<OrderLine> lines() { return List.copyOf(lines); } // read-only view

    // Law of Demeter: callers ask the order, not order.customer().address().city()
    String shippingCity() { return customer.address().city(); }
}

record OrderLine(String sku, int qty, long unitPaise) {
    long subtotal() { return qty * unitPaise; }
}

interface Formatter {
    String format(Order order);
}

class PlainFormatter implements Formatter {   // realization
    public String format(Order order) {
        return "Order " + order.id() + " lines=" + order.lines().size()
                + " total=" + order.total();
    }
}

class ReceiptPrinter {
    // dependency: uses Formatter only for the duration of the call
    void print(Order order, Formatter formatter) {
        System.out.println("  " + formatter.format(order));
    }
}

// ---------- Value object ----------
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
