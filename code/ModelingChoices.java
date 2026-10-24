import java.util.*;

// Chapter 24 - Modeling choices that come up in every LLD round:
// enum vs enum-with-behavior vs sealed interface vs open interface,
// and an anemic model vs a rich model.
// Run: java ModelingChoices.java
public class ModelingChoices {
    public static void main(String[] args) {
        System.out.println("== 1. Plain enum with data ==");
        for (SpotSize s : SpotSize.values()) System.out.println("  " + s + " fits up to " + s.maxWheels + " wheels");

        System.out.println("== 2. Enum with constant-specific behavior ==");
        for (Operation op : Operation.values()) System.out.println("  7 " + op.symbol + " 3 = " + op.apply(7, 3));

        System.out.println("== 3. Sealed interface + exhaustive switch ==");
        List<Payment> payments = List.of(new Card("4111", 50_000), new Upi("a@bank", 20_000),
                new Wallet(5_000));
        for (Payment p : payments) System.out.println("  fee for " + p + " = " + fee(p));

        System.out.println("== 4. Anemic vs rich model ==");
        AnemicTicket a = new AnemicTicket();
        a.setStatus("OPEN");
        new TicketServiceAnemic().close(a);
        a.setStatus("OPEN");                     // nothing stops anyone from reopening it
        System.out.println("  anemic ticket status after outside meddling: " + a.getStatus());

        RichTicket r = new RichTicket("T-1");
        r.close("fixed in v2");
        try {
            r.close("again");
        } catch (IllegalStateException e) {
            System.out.println("  rich ticket refused: " + e.getMessage());
        }
    }

    // A switch over a sealed type: the compiler checks every subtype is handled.
    static long fee(Payment p) {
        return switch (p) {
            case Card c -> c.amountPaise() * 2 / 100;          // 2%
            case Upi u -> 0;
            case Wallet w -> Math.min(w.amountPaise() / 100, 1_000);
        };
    }
}

// 1. Fixed set, differences are only data -> enum with fields.
enum SpotSize {
    SMALL(2), MEDIUM(4), LARGE(6);

    final int maxWheels;
    SpotSize(int maxWheels) { this.maxWheels = maxWheels; }
}

// 2. Fixed set, small behavior per value -> enum with an abstract method.
enum Operation {
    ADD("+") { int apply(int a, int b) { return a + b; } },
    SUB("-") { int apply(int a, int b) { return a - b; } },
    MUL("*") { int apply(int a, int b) { return a * b; } };

    final String symbol;
    Operation(String symbol) { this.symbol = symbol; }
    abstract int apply(int a, int b);
}

// 3. Fixed set of types with different data -> sealed interface of records.
sealed interface Payment permits Card, Upi, Wallet {}
record Card(String last4, long amountPaise) implements Payment {}
record Upi(String vpa, long amountPaise) implements Payment {}
record Wallet(long amountPaise) implements Payment {}

// 4a. Anemic: a bag of getters/setters; rules live elsewhere (or nowhere).
class AnemicTicket {
    private String status;
    private String resolution;
    String getStatus() { return status; }
    void setStatus(String s) { status = s; }
    void setResolution(String r) { resolution = r; }
}

class TicketServiceAnemic {
    void close(AnemicTicket t) {
        if (t.getStatus().equals("OPEN")) {
            t.setStatus("CLOSED");
            t.setResolution("done");
        }
    }
}

// 4b. Rich: the object guards its own rules; no setters.
class RichTicket {
    enum Status { OPEN, CLOSED }

    private final String id;
    private Status status = Status.OPEN;
    private String resolution;

    RichTicket(String id) { this.id = id; }

    void close(String resolution) {
        if (status == Status.CLOSED) throw new IllegalStateException(id + " already closed");
        if (resolution == null || resolution.isBlank()) throw new IllegalArgumentException("need a resolution");
        this.status = Status.CLOSED;
        this.resolution = resolution;
    }

    Status status() { return status; }
}
