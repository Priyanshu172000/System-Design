import java.util.*;

// Chapter 23 - Structural patterns: Adapter, Decorator, Facade, Proxy, Composite.
// Run: java StructuralPatterns.java
public class StructuralPatterns {
    public static void main(String[] args) {
        System.out.println("== Adapter ==");
        PaymentGateway gw = new LegacyPaymentAdapter(new LegacyXmlPaymentClient());
        System.out.println("charged: " + gw.charge("O-1", 49_900));

        System.out.println("== Decorator ==");
        PriceService prices = new LoggingPriceService(
                new CachingPriceService(new DbPriceService()));
        prices.priceOf("SKU-1");
        prices.priceOf("SKU-1");     // second call served by the cache layer

        System.out.println("== Facade ==");
        OrderFacade shop = new OrderFacade(new Inventory(), new Payments(), new Shipping());
        System.out.println("order placed: " + shop.placeOrder("SKU-1", 2, "U-9"));

        System.out.println("== Proxy ==");
        ReportService reports = new AccessControlledReports(new RealReportService());
        System.out.println(reports.generate("admin", "revenue-q3"));
        try {
            reports.generate("guest", "revenue-q3");
        } catch (SecurityException e) {
            System.out.println("blocked: " + e.getMessage());
        }

        System.out.println("== Composite ==");
        Directory root = new Directory("root");
        Directory src = new Directory("src");
        src.add(new FileNode("Main.java", 1_200));
        src.add(new FileNode("Util.java", 800));
        root.add(src);
        root.add(new FileNode("README.md", 300));
        root.print("");
        System.out.println("total size: " + root.size());
    }
}

// ---------- Adapter ----------
interface PaymentGateway {                       // what our code expects
    boolean charge(String orderId, long paise);
}

class LegacyXmlPaymentClient {                   // what we have (cannot change it)
    String makePayment(String xml) {
        System.out.println("  legacy client got: " + xml);
        return "<status>OK</status>";
    }
}

class LegacyPaymentAdapter implements PaymentGateway {
    private final LegacyXmlPaymentClient legacy;
    LegacyPaymentAdapter(LegacyXmlPaymentClient legacy) { this.legacy = legacy; }

    public boolean charge(String orderId, long paise) {
        String xml = "<pay><order>" + orderId + "</order><amount>" + paise + "</amount></pay>";
        return legacy.makePayment(xml).contains("OK");    // translate both ways
    }
}

// ---------- Decorator ----------
interface PriceService {
    long priceOf(String sku);
}

class DbPriceService implements PriceService {
    public long priceOf(String sku) {
        System.out.println("  [db] lookup " + sku);
        return 19_900;
    }
}

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

// ---------- Facade ----------
class Inventory {
    boolean reserve(String sku, int qty) { System.out.println("  reserve " + qty + "x" + sku); return true; }
    void release(String sku, int qty) { System.out.println("  release " + qty + "x" + sku); }
}

class Payments {
    boolean charge(String userId, long paise) { System.out.println("  charge " + userId + " " + paise); return true; }
}

class Shipping {
    String schedule(String sku, String userId) { System.out.println("  ship " + sku + " to " + userId); return "TRK-1"; }
}

class OrderFacade {
    private final Inventory inventory;
    private final Payments payments;
    private final Shipping shipping;

    OrderFacade(Inventory i, Payments p, Shipping s) { inventory = i; payments = p; shipping = s; }

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
}

// ---------- Proxy ----------
interface ReportService {
    String generate(String user, String reportId);
}

class RealReportService implements ReportService {
    public String generate(String user, String reportId) { return "  report " + reportId + " for " + user; }
}

class AccessControlledReports implements ReportService {
    private final ReportService real;
    AccessControlledReports(ReportService real) { this.real = real; }

    public String generate(String user, String reportId) {
        if (!user.equals("admin")) throw new SecurityException(user + " may not read " + reportId);
        return real.generate(user, reportId);       // same interface, controlled access
    }
}

// ---------- Composite ----------
interface FsNode {
    long size();
    void print(String indent);
}

record FileNode(String name, long bytes) implements FsNode {
    public long size() { return bytes; }
    public void print(String indent) { System.out.println(indent + name + " (" + bytes + ")"); }
}

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

    public void print(String indent) {
        System.out.println(indent + name + "/");
        for (FsNode c : children) c.print(indent + "  ");
    }
}
