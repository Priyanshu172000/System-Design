import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

// Chapter 25 - Logging framework: levels, a chain of handlers (chain of
// responsibility), pluggable appenders and formatters, singleton access.
// Run: java LoggingFramework.java
public class LoggingFramework {
    public static void main(String[] args) {
        InMemoryAppender errorsOnly = new InMemoryAppender();
        Logger log = Logger.getInstance();
        log.configure(LogLevel.DEBUG,
                new LevelHandler(LogLevel.INFO, new ConsoleAppender(new SimpleFormatter())),
                new LevelHandler(LogLevel.ERROR, errorsOnly));

        log.debug("cache warmed");                 // below every handler: dropped
        log.info("server started on 8080");        // console only
        log.warn("slow query 1200 ms");            // console only
        log.error("payment gateway timeout");      // console + error store

        System.out.println("error store holds: " + errorsOnly.lines());

        log.setMinLevel(LogLevel.ERROR);           // runtime change: silence INFO/WARN
        log.info("this is filtered at the logger");
        log.error("disk 95% full");
        System.out.println("error store now has " + errorsOnly.lines().size() + " lines");
    }
}

enum LogLevel {
    DEBUG, INFO, WARN, ERROR;                  // ordinal order = severity order

    boolean atLeast(LogLevel other) { return compareTo(other) >= 0; }
}

record LogMessage(Instant time, LogLevel level, String thread, String text) {}

interface LogFormatter {
    String format(LogMessage m);
}

class SimpleFormatter implements LogFormatter {
    public String format(LogMessage m) {
        return "[" + m.level() + "] [" + m.thread() + "] " + m.text();
    }
}

interface Appender {                           // where log lines go
    void append(LogMessage m);
}

class ConsoleAppender implements Appender {
    private final LogFormatter formatter;
    ConsoleAppender(LogFormatter formatter) { this.formatter = formatter; }
    public synchronized void append(LogMessage m) { System.out.println(formatter.format(m)); }
}

class InMemoryAppender implements Appender {   // stands in for a file or remote sink
    private final List<String> lines = new CopyOnWriteArrayList<>();
    public void append(LogMessage m) { lines.add(m.level() + " " + m.text()); }
    List<String> lines() { return List.copyOf(lines); }
}

// One link in the chain: writes if the message is severe enough, then
// always passes it along so later handlers can also act.
class LevelHandler {
    private final LogLevel threshold;
    private final Appender appender;
    private LevelHandler next;

    LevelHandler(LogLevel threshold, Appender appender) {
        this.threshold = threshold;
        this.appender = appender;
    }

    void setNext(LevelHandler next) { this.next = next; }

    void handle(LogMessage m) {
        if (m.level().atLeast(threshold)) appender.append(m);
        if (next != null) next.handle(m);
    }
}

final class Logger {
    private volatile LogLevel minLevel = LogLevel.INFO;
    private volatile LevelHandler chainHead;

    private Logger() {}

    private static class Holder {
        static final Logger INSTANCE = new Logger();
    }

    static Logger getInstance() { return Holder.INSTANCE; }

    synchronized void configure(LogLevel minLevel, LevelHandler... handlers) {
        for (int i = 0; i + 1 < handlers.length; i++) handlers[i].setNext(handlers[i + 1]);
        this.chainHead = handlers.length == 0 ? null : handlers[0];
        this.minLevel = minLevel;
    }

    void setMinLevel(LogLevel level) { this.minLevel = level; }

    void debug(String msg) { log(LogLevel.DEBUG, msg); }
    void info(String msg) { log(LogLevel.INFO, msg); }
    void warn(String msg) { log(LogLevel.WARN, msg); }
    void error(String msg) { log(LogLevel.ERROR, msg); }

    private void log(LogLevel level, String msg) {
        if (!level.atLeast(minLevel)) return;            // cheap early exit
        LevelHandler head = chainHead;
        if (head == null) return;
        head.handle(new LogMessage(Instant.now(), level, Thread.currentThread().getName(), msg));
    }
}
