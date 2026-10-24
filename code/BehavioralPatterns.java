import java.util.*;

// Chapter 23 - Behavioral patterns: Strategy, Observer, Command, State,
// Template Method, Chain of Responsibility, Iterator.
// Run: java BehavioralPatterns.java
public class BehavioralPatterns {
    public static void main(String[] args) {
        System.out.println("== Strategy ==");
        RideBooking booking = new RideBooking(new NormalFare());
        System.out.println("normal fare 10km: " + booking.quote(10));
        booking.setFareStrategy(new SurgeFare(new NormalFare(), 1.5));
        System.out.println("surge  fare 10km: " + booking.quote(10));
        booking.setFareStrategy(km -> 5_000);          // a lambda is a strategy too
        System.out.println("flat   fare 10km: " + booking.quote(10));

        System.out.println("== Observer ==");
        OrderEvents events = new OrderEvents();
        events.subscribe(id -> System.out.println("  email: order " + id + " confirmed"));
        events.subscribe(id -> System.out.println("  analytics: +1 order (" + id + ")"));
        events.orderPlaced("O-7");

        System.out.println("== Command ==");
        Editor editor = new Editor();
        editor.run(new AppendCommand(editor, "Hello"));
        editor.run(new AppendCommand(editor, ", world"));
        System.out.println("  text: " + editor.text());
        editor.undo();
        System.out.println("  after undo: " + editor.text());

        System.out.println("== State ==");
        OrderContext order = new OrderContext("O-9");
        order.pay();
        order.ship();
        order.cancel();                                // not allowed once shipped
        OrderContext order2 = new OrderContext("O-10");
        order2.cancel();
        order2.pay();                                  // not allowed once cancelled

        System.out.println("== Template method ==");
        new CsvImporter().importData("id,name\n1,pen\n2,ink");
        new PipeImporter().importData("1|pen|2|");

        System.out.println("== Chain of responsibility ==");
        Handler chain = Handler.link(new AuthHandler(), new RateLimitHandler(2), new ValidationHandler());
        for (Request r : List.of(new Request("tok", "a"), new Request(null, "b"),
                new Request("tok", ""), new Request("tok", "c"), new Request("tok", "d"))) {
            System.out.println("  " + r + " -> " + chain.handle(r));
        }

        System.out.println("== Iterator ==");
        Bst tree = new Bst();
        for (int v : new int[]{50, 30, 70, 20, 40, 60, 80}) tree.insert(v);
        StringBuilder sb = new StringBuilder();
        for (int v : tree) sb.append(v).append(' ');   // for-each works because Bst is Iterable
        System.out.println("  in-order: " + sb.toString().trim());
    }
}

// ---------- Strategy ----------
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

// ---------- Observer ----------
interface OrderListener {
    void onOrderPlaced(String orderId);
}

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

// ---------- Command ----------
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

// ---------- State ----------
interface OrderState {
    OrderState pay(OrderContext o);
    OrderState ship(OrderContext o);
    OrderState cancel(OrderContext o);
    String name();
}

class OrderContext {
    private final String id;
    private OrderState state = new CreatedState();
    OrderContext(String id) { this.id = id; }

    void pay() { transition(state.pay(this), "pay"); }
    void ship() { transition(state.ship(this), "ship"); }
    void cancel() { transition(state.cancel(this), "cancel"); }

    private void transition(OrderState next, String action) {
        if (next == state) return;                 // rejected; state already explained why
        System.out.println("  " + id + ": " + action + " " + state.name() + " -> " + next.name());
        state = next;
    }

    void reject(String action) {
        System.out.println("  " + id + ": cannot " + action + " in state " + state.name());
    }
}

class CreatedState implements OrderState {
    public OrderState pay(OrderContext o) { return new PaidState(); }
    public OrderState ship(OrderContext o) { o.reject("ship"); return this; }
    public OrderState cancel(OrderContext o) { return new CancelledState(); }
    public String name() { return "CREATED"; }
}

class PaidState implements OrderState {
    public OrderState pay(OrderContext o) { o.reject("pay"); return this; }
    public OrderState ship(OrderContext o) { return new ShippedState(); }
    public OrderState cancel(OrderContext o) { System.out.println("  refund issued"); return new CancelledState(); }
    public String name() { return "PAID"; }
}

class ShippedState implements OrderState {
    public OrderState pay(OrderContext o) { o.reject("pay"); return this; }
    public OrderState ship(OrderContext o) { o.reject("ship"); return this; }
    public OrderState cancel(OrderContext o) { o.reject("cancel"); return this; }
    public String name() { return "SHIPPED"; }
}

class CancelledState implements OrderState {
    public OrderState pay(OrderContext o) { o.reject("pay"); return this; }
    public OrderState ship(OrderContext o) { o.reject("ship"); return this; }
    public OrderState cancel(OrderContext o) { o.reject("cancel"); return this; }
    public String name() { return "CANCELLED"; }
}

// ---------- Template method ----------
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

class CsvImporter extends DataImporter {
    protected List<String[]> parse(String raw) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = raw.split("\n");
        for (int i = 1; i < lines.length; i++) rows.add(lines[i].split(","));   // skip header
        return rows;
    }
}

class PipeImporter extends DataImporter {
    protected List<String[]> parse(String raw) {
        String[] parts = raw.split("\\|", -1);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i + 1 < parts.length; i += 2) rows.add(new String[]{parts[i], parts[i + 1]});
        return rows;
    }
}

// ---------- Chain of responsibility ----------
record Request(String token, String payload) {}

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

class AuthHandler extends Handler {
    protected String check(Request r) { return r.token() == null ? "401 Unauthorized" : null; }
}

class RateLimitHandler extends Handler {
    private final int limit;
    private int used = 0;
    RateLimitHandler(int limit) { this.limit = limit; }
    protected String check(Request r) { return ++used > limit ? "429 Too Many Requests" : null; }
}

class ValidationHandler extends Handler {
    protected String check(Request r) { return r.payload().isEmpty() ? "400 Bad Request" : null; }
}

// ---------- Iterator ----------
class Bst implements Iterable<Integer> {
    private static final class Node {
        final int val;
        Node left, right;
        Node(int v) { val = v; }
    }

    private Node root;

    void insert(int v) { root = insert(root, v); }

    private Node insert(Node n, int v) {
        if (n == null) return new Node(v);
        if (v < n.val) n.left = insert(n.left, v); else n.right = insert(n.right, v);
        return n;
    }

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
}
