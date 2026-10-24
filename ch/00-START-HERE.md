# 00 — Start here

You already know how to make a program fast on **one machine**: pick the right data structure,
get the complexity down, avoid wasted work. System design asks a different question: how do you
keep a program **correct, fast and running** when it runs on many machines, serves millions of
people, stores more data than fits anywhere, and some part of it is always broken?

These notes teach that from zero. They don't assume you have built a backend.

---

## 00.1 What HLD and LLD mean

| | **HLD: High-Level Design** | **LLD: Low-Level Design** |
|---|---|---|
| Question | Which *boxes* (services, databases, caches, queues) and how they connect | Which *classes* and how they collaborate inside one box |
| Unit | Machines, services, data stores, networks | Classes, interfaces, methods, objects |
| Main worries | Scale, latency, availability, consistency, cost, failure | Readability, extensibility, correctness, testability |
| Output | Architecture diagram + data model + API + trade-offs | Class diagram + working code |
| Typical prompt | "Design WhatsApp", "Design a URL shortener" | "Design a parking lot", "Design Splitwise" |

```text
HLD zoom level                          LLD zoom level
+--------+   +----+   +---------+       +-----------------------------+
| Client |-->| LB |-->| Booking |--+    |  BookingService             |
+--------+   +----+   | Service |  |    |   - seatLockStore           |
                      +---------+  |    |   + book(showId, seats)     |
                           |       |    +-------------+---------------+
                        +-----+ +-----+               | uses
                        |Cache| | DB  |   ===>  +-----v---------+   +-----------------+
                        +-----+ +-----+         | SeatLock      |   | PricingStrategy |
                                                +---------------+   +-----------------+
```

LLD is one box from the HLD picture, opened up.

---

## 00.2 How the course is laid out

Read it in order the first time, since each part builds on the one before.

| Part | Chapters | What you get |
|---|---|---|
| 1. Backend foundations | 01–04 | How requests travel, APIs, servers, the numbers to reason with |
| 2. Data | 05–09 | SQL in depth, NoSQL, replication, sharding, caching, search, analytics |
| 3. Distributed systems | 10–15 | CAP, consensus, queues/Kafka, load balancing, resilience, ops, security |
| 4. HLD in practice | 16–21 | A repeatable method + 11 classic systems designed end to end |
| 5. LLD | 22–26 | OOP, SOLID, patterns, the LLD method, 7 full designs with runnable Java, concurrency |
| 6. Confidence | 27 | Glossary, phrase bank, model answers to follow-ups, a study plan |

Every chapter ends with a **Recap** and **Check yourself** questions with hidden answers. Try to
answer before you open them.

---

## 00.3 The ten ideas the whole subject reduces to

Each later chapter expands one or more of these. If you only remember one page, make it this one.

1. **Everything is a trade-off.** No design is "best". A design fits a set of requirements.
   Saying what you are giving up is what makes a design answer convincing.
2. **Numbers drive the design.** 100 requests per second and 100,000 requests per second need
   different architectures. Estimate first (ch 04).
3. **Stateless compute, stateful storage.** Keep app servers interchangeable and push state into
   databases and caches, so you can add servers freely (ch 03).
4. **Reads and writes scale differently.** Reads scale with caches and replicas. Writes scale with
   sharding and async processing (ch 07, 08).
5. **Copies make you fast and available, and they make you inconsistent.** Replicas, caches and
   CDNs all hold copies, and copies go stale. Decide how stale is acceptable (ch 07, 10).
6. **The network is unreliable.** Messages get lost, delayed or duplicated, and machines die. So
   use timeouts, retries, and **idempotency**, which means doing the same thing twice has the
   same effect as doing it once (ch 02, 10, 13).
7. **Async decouples.** If the user doesn't need to wait for something, put it on a queue
   (ch 11).
8. **Partition to scale.** Split data and work by a key so that each machine owns a slice
   (ch 07).
9. **Remove single points of failure.** Anything that exists only once will eventually take the
   whole system down. Use redundancy, health checks and failover (ch 12, 14).
10. **Design for change (LLD).** Find what varies, hide it behind an interface, and keep each
    class responsible for one thing (ch 22–24).

---

## 00.4 What being comfortable in a design conversation looks like

Engineers who sound confident in design talks are rarely the ones who know the most products.
They do these five things:

1. **Ask before answering.** "Read-heavy or write-heavy? How many users? Is it OK if a like
   count is a few seconds stale?"
2. **Put a rough number on it.** "That's about 1,200 writes per second at peak, so a single
   well-indexed database is fine for now."
3. **Name the trade-off.** "Fan-out on write makes reads cheap, but celebrities with 50M
   followers make writes explode, so I'd go hybrid."
4. **Talk about failure.** "If the cache dies, the DB gets the full read load. We'd need request
   coalescing or we'd melt it."
5. **Say what you don't know.** "I haven't run Cassandra in production, but from first
   principles the partition key here should be conversation_id, because…"

Chapter 27 gives you phrases for each of these.

---

## 00.5 How to study (suggested 4 weeks, ~1 hour/day)

> **Short on time?** The [Fast track](#ft) page covers the essentials in 8 to 12 hours, session by session. Start there instead of reading everything.

| Week | Read | Practise |
|---|---|---|
| 1 | 00–06 | Explain out loud: life of a request; index vs no index; SQL vs NoSQL for 3 apps |
| 2 | 07–13 | Draw from memory: consistent hashing ring, cache-aside flow, Kafka consumer group, circuit breaker |
| 3 | 14–21 | Do each case study yourself *before* reading it, then compare |
| 4 | 22–27 | Code 4 LLD problems in 60 min each, then mock HLD rounds from the ch 27 list |

Three habits that help a lot:

- **Draw it.** After each chapter, sketch its main diagram on paper without looking.
- **Teach it.** Explain one concept a day to someone else, or out loud to yourself, in two
  minutes. If you get stuck, that's the part you haven't understood yet.
- **Connect it to your work.** For any system you touch, ask: where's the cache? What's the
  partition key? What happens if this dependency is down? Real systems make the theory stick.

---

## 00.6 Conventions in these notes

- **Bold** marks a term when it is first defined. The glossary in ch 27 has all of them.
- `> Say it like this:` boxes are sentences you can use in a real conversation.
- `> Common mistake:` boxes are traps people fall into.
- Numbers marked "roughly" are orders of magnitude. Use them for estimation, not as
  benchmarks.
- LLD code is Java 21. Runnable demos are in `code/` and each one has been run.

Start with chapter 01.
