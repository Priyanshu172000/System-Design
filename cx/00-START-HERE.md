# 00 — Start here (compact edition)

*Why it matters:* this edition covers **every topic** of the full course in about a third of the
length, so you can finish it in about 12 hours and still lose nothing.

## How this edition decides depth

Every topic gets one of two treatments:

**How it works** · The mechanism is explained briefly but correctly. Use this when people discuss
the internals in design reviews or interviews, or when you need them to reason about a trade-off.
Examples: indexes, consistent hashing, Kafka consumer groups, the token bucket.

> **Black box:** For these topics you only need what it does. **Use when:** you'd reach for it.
> **Cost:** what you give up. Examples: the TLS handshake steps, LSM compaction, Merkle trees.

If you're curious about a black box, every chapter ends with **Go deeper**, which points to the
exact sections of the full chapter. You can switch editions at the top of the sidebar.

Every "How it works" topic comes with a worked **Example**. It shows the mechanism happening
with concrete values (ids, timestamps, numbers) traced step by step.

## The running example: QuickBite

Most chapters use one made-up app, so each new idea attaches to something you already pictured.

**QuickBite** is a food-delivery app. Customers browse restaurants, place an order, pay, and
watch a rider bring the food on a map.

| | |
|---|---|
| Scale | 20 M users (5 M daily), 50,000 restaurants, 2 M orders/day (~23/s on average, ~120/s at the 8–9 pm peak), about 100 menu views per order, ~25,000 rider location updates/s at peak |
| Services | `api-gateway`, `user-service`, `restaurant-service`, `order-service`, `payment-service`, `rider-service`, `notification-service`, `search-service` |
| Cast | Asha (user `u_1842`, Bengaluru) orders from Spice Route (`r_77`). Order `o_90311`, ₹450, delivered by rider `rd_5521` |
| Order life | PLACED → ACCEPTED → PREPARING → PICKED_UP → DELIVERED (or CANCELLED) |

```text
 Asha's phone --> CDN (menu photos)
      |
      v
 api-gateway --> restaurant-service --> cache --> DB      (browse menu: read-heavy)
      |      --> order-service ------> DB --> Kafka "order-events"
      |                                            |--> payment-service
      |                                            |--> rider-service
      |                                            '--> notification-service
      '<-- WebSocket: "rider is 4 min away" <-- rider-service <-- rider GPS pings
```

By chapter 16 you'll be able to explain every box and arrow in this picture.

## HLD vs LLD in one table

| | **HLD** (high-level design) | **LLD** (low-level design) |
|---|---|---|
| Question | Which boxes (services, DBs, caches, queues) and how they connect | Which classes, and how they collaborate inside one box |
| Worries | Scale, latency, availability, consistency, cost, failure | Readability, extensibility, correctness, testability |
| Output | Diagram + API + data model + trade-offs | Class diagram + working code |
| Prompt | "Design WhatsApp" | "Design a parking lot" |

## The ten ideas everything reduces to

1. **Everything is a trade-off.** Say what you give up.
2. **Numbers drive the design.** Estimate before choosing (ch 04).
3. **Stateless compute, stateful storage.** App servers are interchangeable; state lives in the DB
   and cache (ch 03).
4. **Reads scale with caches and replicas; writes scale with sharding and async** (ch 07, 08).
5. **Copies make you fast and available, and also inconsistent.** Decide how stale is OK
   (ch 07, 10).
6. **The network is unreliable.** Use timeouts, retries and **idempotency** (doing it twice has
   the same effect as doing it once) (ch 02, 13).
7. **Async decouples.** If the user doesn't need to wait, put it on a queue (ch 11).
8. **Partition to scale.** Split data and work by a key (ch 07).
9. **Remove single points of failure.** Use redundancy, health checks and failover (ch 12, 14).
10. **Design for change (LLD).** Find what varies and put it behind an interface (ch 22–24).

## Schedule (about 12 hours)

The examples make chapters a bit longer, but they read faster than abstract text.

| Session | Chapters | Time |
|---|---|---|
| 1 | 00–02 | 1 h 15 |
| 2 | 03–04 | 1 h |
| 3 | 05–06 | 1 h 15 |
| 4 | 07–08 | 1 h 15 |
| 5 | 09–11 | 1 h 30 |
| 6 | 12–15 | 1 h 45 |
| 7 | 16–18 | 1 h 15 |
| 8 | 19–21 | 1 h 30 |
| 9 | 22–26 | 1 h 45 |
| 10 | 27 + review | 30 min |

Only have 8–9 hours? Read every "How it works" paragraph and its Example, and only skim the
black-box notes and the tables.

## Each session

```text
 5 min  recall last session: redraw its diagrams, say its "In one minute" bullets
45 min  read; redraw every diagram on paper once
10 min  close the notes and say this session's "In one minute" bullets out loud
```

- Read the bold term, look away, and define it in one line. If you can't, reread it.
- For "How it works" topics, make sure you could sketch the mechanism.
- For black boxes, make sure you could answer three questions: what is it, when would I use
  it, and what does it cost?
- After session 7, ask Claude for a mock interview. It finds your gaps faster than rereading.

## Conventions

- `> Say it like this:` gives sentences you can use as they are in a conversation.
- `> Common mistake:` marks a trap people fall into.
- "Roughly" means the number is an order of magnitude.
- LLD code is Java 21. The full programs are under **Runnable code** in the sidebar.
