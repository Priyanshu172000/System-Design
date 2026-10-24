import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

// Chapter 23 - Creational patterns: Singleton (enum, holder, double-checked
// locking), Simple Factory, Factory Method, Abstract Factory, Builder.
// Run: java CreationalPatterns.java
public class CreationalPatterns {
    public static void main(String[] args) {
        System.out.println("== Singleton ==");
        AppConfig.INSTANCE.set("timeoutMs", "2000");
        System.out.println("enum singleton timeoutMs=" + AppConfig.INSTANCE.get("timeoutMs"));
        System.out.println("holder ids: " + IdGenerator.getInstance().nextId()
                + ", " + IdGenerator.getInstance().nextId());
        System.out.println("same holder instance: "
                + (IdGenerator.getInstance() == IdGenerator.getInstance()));
        System.out.println("same DCL instance: "
                + (ConnectionRegistry.getInstance() == ConnectionRegistry.getInstance()));

        System.out.println("== Simple factory ==");
        NotificationChannel ch = NotificationFactory.create(ChannelType.SMS);
        ch.send("U-1", "Your OTP is 4821");

        System.out.println("== Factory method ==");
        Logistics road = new RoadLogistics();
        Logistics sea = new SeaLogistics();
        road.planDelivery("box-1");
        sea.planDelivery("container-9");

        System.out.println("== Abstract factory ==");
        renderForm(new DarkTheme());
        renderForm(new LightTheme());

        System.out.println("== Builder ==");
        HttpCall call = HttpCall.builder("https://api.example.com/orders")
                .method("POST")
                .header("Content-Type", "application/json")
                .timeoutMs(1500)
                .body("{\"sku\":\"A1\"}")
                .build();
        System.out.println(call);
        try {
            HttpCall.builder("https://x").method("GET").body("oops").build();
        } catch (IllegalStateException e) {
            System.out.println("build() rejected: " + e.getMessage());
        }
    }

    static void renderForm(UiFactory ui) {
        System.out.println("  " + ui.button().draw() + " " + ui.checkbox().draw());
    }
}

// ---------- Singleton 1: enum (simplest, thread-safe, serialization-safe) ----------
enum AppConfig {
    INSTANCE;

    private final Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
    String get(String key) { return values.get(key); }
    void set(String key, String value) { values.put(key, value); }
}

// ---------- Singleton 2: lazy initialization-on-demand holder ----------
final class IdGenerator {
    private final AtomicLong next = new AtomicLong(1);

    private IdGenerator() {}                     // nobody else can construct

    private static class Holder {                // loaded only on first getInstance()
        static final IdGenerator INSTANCE = new IdGenerator();
    }

    static IdGenerator getInstance() { return Holder.INSTANCE; }

    long nextId() { return next.getAndIncrement(); }
}

// ---------- Singleton 3: double-checked locking (needs volatile) ----------
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

// ---------- Simple factory ----------
enum ChannelType { EMAIL, SMS, PUSH }

interface NotificationChannel {
    void send(String userId, String text);
}

class EmailChannel implements NotificationChannel {
    public void send(String userId, String text) { System.out.println("  EMAIL to " + userId + ": " + text); }
}

class SmsChannel implements NotificationChannel {
    public void send(String userId, String text) { System.out.println("  SMS to " + userId + ": " + text); }
}

class PushChannel implements NotificationChannel {
    public void send(String userId, String text) { System.out.println("  PUSH to " + userId + ": " + text); }
}

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

// ---------- Factory method ----------
interface Transport {
    String deliver(String cargo);
}

class Truck implements Transport {
    public String deliver(String cargo) { return "truck carries " + cargo + " by road"; }
}

class Ship implements Transport {
    public String deliver(String cargo) { return "ship carries " + cargo + " by sea"; }
}

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

// ---------- Abstract factory: a family of products that must match ----------
interface Button { String draw(); }
interface Checkbox { String draw(); }

interface UiFactory {
    Button button();
    Checkbox checkbox();
}

class DarkTheme implements UiFactory {
    public Button button() { return () -> "[dark button]"; }
    public Checkbox checkbox() { return () -> "[dark checkbox]"; }
}

class LightTheme implements UiFactory {
    public Button button() { return () -> "[light button]"; }
    public Checkbox checkbox() { return () -> "[light checkbox]"; }
}

// ---------- Builder ----------
final class HttpCall {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final int timeoutMs;
    private final String body;

    private HttpCall(Builder b) {
        this.url = b.url;
        this.method = b.method;
        this.headers = Map.copyOf(b.headers);   // immutable once built
        this.timeoutMs = b.timeoutMs;
        this.body = b.body;
    }

    static Builder builder(String url) { return new Builder(url); }

    @Override
    public String toString() {
        return method + " " + url + " headers=" + headers + " timeoutMs=" + timeoutMs
                + (body == null ? "" : " body=" + body);
    }

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
}
