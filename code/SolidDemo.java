import java.util.*;

// Chapter 22 - SOLID: each principle as "violating" vs "fixed", plus
// constructor injection and a test written with fakes.
// Run: java SolidDemo.java
public class SolidDemo {
    public static void main(String[] args) {
        System.out.println("== S: single responsibility ==");
        new InvoiceManager().process(List.of(10_000L, 25_000L));
        new InvoiceService(new TaxCalculator(), new InvoiceRenderer(), new InvoiceStore())
                .process(List.of(10_000L, 25_000L));

        System.out.println("== O: open/closed ==");
        System.out.println("Bad  discount: " + new DiscountCalculatorBad().discount("FESTIVAL", 120_000, 12));
        Checkout checkout = new Checkout(List.of(new FestivalDiscount(), new BulkDiscount(10)));
        System.out.println("Good discount: " + checkout.totalDiscount(120_000, 12));

        System.out.println("== L: Liskov substitution ==");
        System.out.println("Rectangle ok? " + LiskovCheck.resizeWorks(new MutableRectangle()));
        System.out.println("Square ok?    " + LiskovCheck.resizeWorks(new MutableSquare()));

        System.out.println("== I: interface segregation ==");
        try {
            new SimplePrinterBad().fax("doc");
        } catch (UnsupportedOperationException e) {
            System.out.println("Fat interface forced a fake method: " + e.getMessage());
        }
        Printer printer = new SimplePrinter();
        printer.print("report.pdf");

        System.out.println("== D: dependency inversion + constructor injection ==");
        // Production wiring would pass real implementations here.
        // A test passes fakes - no network, no mocks library needed.
        FakePaymentGateway gateway = new FakePaymentGateway(true);
        RecordingNotifier notifier = new RecordingNotifier();
        OrderService service = new OrderService(gateway, notifier);
        check(service.placeOrder("O-42", "U-1", 99_900), "order should succeed");
        check(gateway.charges.equals(List.of("O-42:99900")), "gateway charged once");
        check(notifier.sent.size() == 1, "one notification sent");

        FakePaymentGateway declining = new FakePaymentGateway(false);
        RecordingNotifier notifier2 = new RecordingNotifier();
        OrderService service2 = new OrderService(declining, notifier2);
        check(!service2.placeOrder("O-43", "U-1", 5_000), "declined order fails");
        check(notifier2.sent.equals(List.of("U-1: payment failed for O-43")), "failure notified");
        System.out.println("All OrderService checks passed");
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
    }
}

// =============== S ===============
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

// Fixed: one reason to change per class; a thin service coordinates them.
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

// =============== O ===============
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

// =============== L ===============
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
// Fix: do not make Square a subtype of a mutable Rectangle. Model both as
// immutable shapes behind a Shape interface (see OopBasicsDemo).

// =============== I ===============
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

// =============== D ===============
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
