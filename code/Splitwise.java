import java.util.*;

// Chapter 25 - Splitwise: expenses, pluggable split strategies (equal, exact,
// percent), net balances, and greedy debt simplification with two heaps.
// Money is kept in integer paise to avoid floating-point drift.
// Run: java Splitwise.java
public class Splitwise {
    public static void main(String[] args) {
        ExpenseService svc = new ExpenseService();
        for (String u : List.of("A", "B", "C", "D")) svc.addUser(new User(u, "user-" + u));

        // Dinner: A paid 1000.00, split equally among A, B, C (333.34 / 333.33 / 333.33)
        svc.addExpense("dinner", "A", 100_000, List.of("A", "B", "C"), new EqualSplit());
        // Cab: B paid 600.00; exact amounts
        svc.addExpense("cab", "B", 60_000, List.of("B", "C", "D"),
                new ExactSplit(Map.of("B", 10_000L, "C", 20_000L, "D", 30_000L)));
        // Hotel: D paid 4000.00 split 50/25/25
        svc.addExpense("hotel", "D", 400_000, List.of("A", "B", "D"),
                new PercentSplit(Map.of("A", 50.0, "B", 25.0, "D", 25.0)));
        // Tickets: C paid 1500.00 split equally among all four
        svc.addExpense("tickets", "C", 150_000, List.of("A", "B", "C", "D"), new EqualSplit());

        System.out.println("Net balances (+ = is owed, - = owes):");
        svc.netBalances().forEach((u, b) -> System.out.println("  " + u + ": " + rupees(b)));

        List<Settlement> plan = svc.simplifyDebts();
        System.out.println("Simplified settlements:");
        for (Settlement s : plan) System.out.println("  " + s.from() + " pays " + s.to() + " " + rupees(s.paise()));

        // Check: applying the plan zeroes every balance, using at most n-1 transfers.
        Map<String, Long> after = new HashMap<>(svc.netBalances());
        for (Settlement s : plan) {
            after.merge(s.from(), s.paise(), Long::sum);
            after.merge(s.to(), -s.paise(), Long::sum);
        }
        check(after.values().stream().allMatch(v -> v == 0), "all settled");
        check(plan.size() <= svc.netBalances().size() - 1, "at most n-1 transfers");
        System.out.println("Check passed: plan settles everyone in " + plan.size() + " transfers");

        try {
            svc.addExpense("bad", "A", 10_000, List.of("A", "B"),
                    new ExactSplit(Map.of("A", 1_000L, "B", 1_000L)));
        } catch (IllegalArgumentException e) {
            System.out.println("Rejected: " + e.getMessage());
        }
    }

    static String rupees(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return String.format("%sRs %d.%02d", sign, abs / 100, abs % 100);
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
    }
}

record User(String id, String name) {}

record Expense(String id, String description, String paidBy, long amountPaise,
               Map<String, Long> shares) {}

record Settlement(String from, String to, long paise) {}

// ---------- Split strategies ----------
interface SplitStrategy {
    // Returns each participant's share; shares must add up to totalPaise exactly.
    Map<String, Long> split(long totalPaise, List<String> participants);
}

class EqualSplit implements SplitStrategy {
    public Map<String, Long> split(long totalPaise, List<String> participants) {
        int n = participants.size();
        long base = totalPaise / n;
        long remainder = totalPaise % n;              // leftover paise go to the first few
        Map<String, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) shares.put(participants.get(i), base + (i < remainder ? 1 : 0));
        return shares;
    }
}

class ExactSplit implements SplitStrategy {
    private final Map<String, Long> amounts;
    ExactSplit(Map<String, Long> amounts) { this.amounts = Map.copyOf(amounts); }

    public Map<String, Long> split(long totalPaise, List<String> participants) {
        if (!amounts.keySet().equals(new HashSet<>(participants)))
            throw new IllegalArgumentException("exact amounts must cover exactly the participants");
        long sum = amounts.values().stream().mapToLong(Long::longValue).sum();
        if (sum != totalPaise)
            throw new IllegalArgumentException("exact amounts add to " + sum + ", expected " + totalPaise);
        return new LinkedHashMap<>(amounts);
    }
}

class PercentSplit implements SplitStrategy {
    private final Map<String, Double> percents;
    PercentSplit(Map<String, Double> percents) { this.percents = Map.copyOf(percents); }

    public Map<String, Long> split(long totalPaise, List<String> participants) {
        if (!percents.keySet().equals(new HashSet<>(participants)))
            throw new IllegalArgumentException("percents must cover exactly the participants");
        double sumPct = percents.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sumPct - 100.0) > 1e-9)
            throw new IllegalArgumentException("percents add to " + sumPct + ", expected 100");
        Map<String, Long> shares = new LinkedHashMap<>();
        long assigned = 0;
        for (String p : participants) {
            long share = (long) Math.floor(totalPaise * percents.get(p) / 100.0);
            shares.put(p, share);
            assigned += share;
        }
        long leftover = totalPaise - assigned;        // rounding dust, a few paise at most
        for (int i = 0; leftover > 0; i = (i + 1) % participants.size(), leftover--) {
            shares.merge(participants.get(i), 1L, Long::sum);
        }
        return shares;
    }
}

// ---------- Service ----------
class ExpenseService {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final List<Expense> expenses = new ArrayList<>();
    private final Map<String, Long> net = new LinkedHashMap<>();   // + is owed, - owes
    private int seq = 1;

    void addUser(User u) {
        users.put(u.id(), u);
        net.put(u.id(), 0L);
    }

    Expense addExpense(String description, String paidBy, long amountPaise,
                       List<String> participants, SplitStrategy strategy) {
        if (amountPaise <= 0) throw new IllegalArgumentException("amount must be positive");
        if (!users.containsKey(paidBy) || !users.keySet().containsAll(participants))
            throw new IllegalArgumentException("unknown user");
        Map<String, Long> shares = strategy.split(amountPaise, participants);  // validates first
        Expense e = new Expense("E-" + seq++, description, paidBy, amountPaise, Map.copyOf(shares));
        expenses.add(e);
        net.merge(paidBy, amountPaise, Long::sum);
        shares.forEach((u, s) -> net.merge(u, -s, Long::sum));
        return e;
    }

    Map<String, Long> netBalances() { return Collections.unmodifiableMap(net); }

    // Greedy: repeatedly match the biggest creditor with the biggest debtor.
    // Each step settles at least one person fully, so at most n-1 transfers.
    // (Finding the true minimum number of transfers is NP-hard in general.)
    List<Settlement> simplifyDebts() {
        PriorityQueue<long[]> creditors = new PriorityQueue<>((x, y) -> Long.compare(y[1], x[1]));
        PriorityQueue<long[]> debtors = new PriorityQueue<>((x, y) -> Long.compare(y[1], x[1]));
        List<String> ids = new ArrayList<>(net.keySet());
        for (int i = 0; i < ids.size(); i++) {
            long b = net.get(ids.get(i));
            if (b > 0) creditors.add(new long[]{i, b});
            else if (b < 0) debtors.add(new long[]{i, -b});
        }
        List<Settlement> out = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            long[] c = creditors.poll();
            long[] d = debtors.poll();
            long pay = Math.min(c[1], d[1]);
            out.add(new Settlement(ids.get((int) d[0]), ids.get((int) c[0]), pay));
            if (c[1] > pay) creditors.add(new long[]{c[0], c[1] - pay});
            if (d[1] > pay) debtors.add(new long[]{d[0], d[1] - pay});
        }
        return out;
    }
}
