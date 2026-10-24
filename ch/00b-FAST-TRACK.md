# Fast track — the course in 8 to 12 hours

The full course takes about 30 hours to read properly. This page picks the roughly 20% of it that
covers most of what comes up in real design conversations. It is split into one-hour sessions.

- **8 hours:** do the 8 core sessions. After them you can hold your own in an HLD or LLD
  discussion.
- **9 to 12 hours:** add the optional sessions in the order given. Each one adds a specific
  ability.

Section numbers like **05.4** refer to headings inside a chapter. Click the chapter link, then
use the "On this page" box at the top to jump to the section.

---

## How to run each session (this matters more than the reading)

```text
 0 ─────────────── 40 min ──────────────── 55 ───── 60
 |  read the listed sections only         | do the  | say the
 |  (skip the "Check yourself" answers)   | task    | "you can now say"
 |                                        | listed  | lines out loud
```

- **Read with a pen.** For every diagram, redraw it on paper once. Drawing it yourself is what
  makes it stick.
- **Don't read the Recap or Check yourself** sections during the 40 minutes. They're for the
  last 10 minutes or the next morning.
- **Say it out loud.** The "you can now say" lines are the test. If you can't say one smoothly,
  reread that section tomorrow instead of moving on.
- **Skim when told to skim.** "Skim" means read the headings, the diagrams and the bold terms,
  then move on.

---

## Core session 1 — The big picture and the numbers

| Read | Section | Lines |
|---|---|---|
| [Ch 00](#00) | 00.1, 00.3, 00.4 | 75 |
| [Ch 01](#01) | 01.2 The life of a request, 01.10 What to say about "type a URL" | 65 |
| [Ch 03](#03) | 03.5 Stateless vs stateful, **03.7 The evolution of a system** (most important) | 222 |
| [Ch 04](#04) | 04.2 Latency numbers, 04.4 Time conversions, 04.5 Average vs peak | 110 |

**Task (15 min):** On paper, draw the system at each stage of 03.7, from one server up to shards
and a CDN, from memory. Under each stage, write the problem that forced it.

**You can now say:**
- "App servers are stateless so I can add more behind a load balancer. State lives in the
  database and the cache."
- "One million requests a day is only about 12 per second on average. Peak is maybe 2 to 5
  times that."
- "A memory read is about 100 nanoseconds, a round trip inside one data center about half a
  millisecond, and a round trip across the world 100 to 300 milliseconds. That's why we cache
  and use CDNs."

---

## Core session 2 — APIs and relational databases

| Read | Section | Lines |
|---|---|---|
| [Ch 02](#02) | 02.4 Idempotency keys | 81 |
| [Ch 05](#05) | **05.4 Indexes** (read the first half carefully, skim from "EXPLAIN" onward) | 216 |
| [Ch 05](#05) | 05.6 Transactions and ACID, 05.7 Isolation levels and anomalies | 132 |

**Task:** Explain to an imaginary colleague, in 2 minutes, why a payment API needs an idempotency
key and what happens without one. Then write the composite index you'd add for
`WHERE user_id = ? ORDER BY created_at DESC`, and say why the column order matters.

**You can now say:**
- "A B+ tree index makes a lookup take about 3 to 4 page reads even for millions of rows, but
  every index slows down writes."
- "Retries are unavoidable, so writes that change state take an idempotency key and the server
  dedupes on it."
- "Read committed still allows lost updates. I'd use `SELECT ... FOR UPDATE` or a version
  column."

---

## Core session 3 — Scaling the data

| Read | Section | Lines |
|---|---|---|
| [Ch 06](#06) | 06.5 SQL vs NoSQL: deciding | 40 |
| [Ch 07](#07) | 07.2 Leader-follower replication, 07.3 Replication lag | 105 |
| [Ch 07](#07) | 07.8 Sharding strategies, 07.9 Choosing a shard key, 07.10 Hot spots | 117 |
| [Ch 07](#07) | **07.12 Consistent hashing from scratch** | 96 |
| [Ch 08](#08) | 08.3 Caching patterns | 98 |

**Task:** Draw the consistent-hashing ring with 3 nodes and some keys. Add a fourth node and shade
which keys move. Then draw the cache-aside read and write flow.

**You can now say:**
- "Replicas scale reads but not writes, and async replicas can serve stale reads. For
  read-your-own-writes I'd route that user's reads to the leader for a short while."
- "Sharding scales writes. The shard key decides everything: it needs high cardinality, an even
  spread, and the common query should hit one shard."
- "With consistent hashing, adding a node moves only about 1/N of the keys, not almost all of
  them like `hash % N`."
- "I'd default to Postgres or MySQL unless the access pattern or scale clearly needs something
  else."

---

## Core session 4 — When caches fail, CAP, and going async

| Read | Section | Lines |
|---|---|---|
| [Ch 08](#08) | 08.5 Invalidation, 08.7 Stampedes, penetration, avalanches, hot keys | 146 |
| [Ch 10](#10) | 10.3 The CAP theorem, stated precisely | 58 |
| [Ch 11](#11) | 11.1 Why go asynchronous | 65 |
| [Ch 11](#11) | **11.4 Kafka deep dive** | 167 |

**Task:** Draw a Kafka topic with 4 partitions and a consumer group of 3 consumers. Then show
what happens when a 5th consumer joins (it sits idle) and when one consumer dies.

**You can now say:**
- "On a cache miss storm I'd coalesce requests so only one goes to the DB, and add jitter to
  TTLs so keys don't all expire together."
- "CAP is about what happens during a network partition: return possibly stale data, or refuse
  to answer. When there's no partition, the real trade-off is latency vs consistency."
- "Kafka only orders messages within a partition. So I key by entity id, for example order_id,
  to keep each entity's events in order."

---

## Core session 5 — Making it reliable

| Read | Section | Lines |
|---|---|---|
| [Ch 11](#11) | 11.5 Delivery semantics, 11.10 The dual-write problem and the outbox | 112 |
| [Ch 13](#13) | 13.4 The cascading failure story, 13.6 Retries, 13.7 Circuit breakers | 177 |
| [Ch 12](#12) | **12.11 Rate-limiting algorithms** (token bucket and sliding window carefully, skim the rest) | 162 |

**Task:** Write the token bucket algorithm in about 15 lines in any language. (Your competitive
programming background makes this quick.) Then draw the circuit breaker's three states and the
transitions between them.

**You can now say:**
- "Exactly-once delivery doesn't really exist end to end. I get at-least-once delivery plus an
  idempotent consumer."
- "Writing to the DB and then publishing to Kafka can fail halfway between the two, so I'd use a
  transactional outbox."
- "Every remote call gets a timeout, a few retries with exponential backoff and jitter, and a
  circuit breaker so a slow dependency can't take us down."

---

## Core session 6 — The HLD method, and your first full design

| Read | Section | Lines |
|---|---|---|
| [Ch 16](#16) | **16.3 The framework at a glance**, 16.6 Handling follow-ups, 16.8 Default toolbox | 287 |
| [Ch 27](#27) | 27.5 The 20 trade-offs cheat sheet | 28 |
| [Ch 17](#17) | 17.7 Generating the short code, 17.14 Final architecture | 195 |

**Task (do this *before* reading 17.7):** Design a URL shortener yourself in 15 minutes using the
16.3 steps: requirements, rough numbers, API, data model, diagram. Then read 17.7 and 17.14 and
compare. Note what you missed.

**You can now say:**
- "Before designing, let me pin down requirements and scale. Is it read-heavy? How stale can
  things be?"
- "Here's the high-level path: client, CDN, load balancer, service, cache, DB, with a queue for
  anything the user doesn't wait on."
- "The trade-off here is X versus Y. I'm choosing X because of requirement Z."

---

## Core session 7 — Two classic designs: news feed and chat

**Do it yourself first (15 min):** sketch a Twitter home timeline. Then read:

| Read | Section | Lines |
|---|---|---|
| [Ch 19](#19) | 19.6 Architecture, **19.7 Fan-out on write vs read vs hybrid**, 19.15 5-minute summary | 140 |
| [Ch 19](#19) | 19.20 Chat architecture, 19.21 WebSocket gateways, 19.22 Message flow, 19.23 Ordering, 19.31 5-minute summary | 138 |
| [Ch 27](#27) | 27.3 Phrase bank | 88 |

**Task:** Present the news feed design out loud in 5 minutes, using phrases from 27.3. Time
yourself.

**You can now say:**
- "Fan-out on write makes reads cheap, but celebrities break it, so I'd use a hybrid: push for
  normal users, pull for accounts with millions of followers."
- "For chat, each user holds a WebSocket to a gateway, and a registry maps user to gateway.
  Offline users get the message from storage plus a push notification."
- "Ordering is per conversation, using a sequence number from the conversation's partition.
  Global ordering isn't needed."

---

## Core session 8 — LLD in one hour

| Read | Section | Lines |
|---|---|---|
| [Ch 24](#24) | 24.2 Method at a glance, 24.3 Clarify and scope, 24.5 Nouns to classes, 24.6 Entities/value objects/services, 24.8 What varies goes behind interfaces, 24.9 The class diagram | 171 |
| [Ch 23](#23) | 23.5 Which pattern for which smell, 23.6 Patterns in common LLD problems | 47 |
| [Ch 25](#25) | **25.2 Parking lot** (read the design, skim the code) | 205 |

**Task:** Without looking, write the class list and interfaces for a parking lot and say where
Strategy is used (spot allocation and pricing). Run the real thing:
`java ParkingLot.java` from the `code/` folder.

**You can now say:**
- "Let me scope it first: actors, core use cases, what's in and out of scope, and whether it's
  concurrent."
- "The thing that varies here is the pricing rule, so it goes behind a `PricingStrategy`
  interface. Adding a new rule means adding a class, not editing existing code."

**The 8-hour mark.** You now know the main ideas behind almost every design discussion.

---

## Optional sessions (in priority order)

### Optional 9 — SOLID and design principles (most-asked LLD theory)

| Read | Section | Lines |
|---|---|---|
| [Ch 22](#22) | 22.8 Composition over inheritance, **22.11 SOLID** | 396 |

**Task:** For each SOLID letter, say the principle and one violation from memory.

### Optional 10 — Your own domain: ad serving

| Read | Section | Lines |
|---|---|---|
| [Ch 21](#21) | 21.21 Vocabulary through 21.33 Tracking pipeline, then 21.38 5-minute summary | ~335 |

**Task:** Compare this with the system you work on. Where do your services match these boxes, and
where do they differ? This is the design you can talk about with the most authority.

### Optional 11 — Concurrency in LLD: movie booking

| Read | Section | Lines |
|---|---|---|
| [Ch 25](#25) | 25.4 Movie ticket booking | 255 |
| [Ch 26](#26) | 26.3 Race conditions, 26.7 Atomics and CAS, 26.18 Optimistic vs pessimistic, 26.19 How to say it | 176 |

**Task:** Run `java MovieBooking.java` and read its concurrency test. Explain why exactly one of
50 racing users gets the seat.

### Optional 12 — Follow-up drills and consistency

| Read | Section | Lines |
|---|---|---|
| [Ch 27](#27) | **27.4 "How would you…?" — 25 common follow-ups** | 150 |
| [Ch 10](#10) | 10.4 Consistency models | 116 |
| [Ch 14](#14) | 14.1 to 14.3 Nines, availability math, SLI/SLO/SLA | 109 |

**Task:** Cover the answers in 27.4 and answer 10 of the questions out loud first.

---

## What you can safely skip in this budget

Skip these with no guilt. They're in the course for later, or for depth you only need if you
specialise.

- Ch 01 beyond 01.2 and 01.10 (TCP/TLS details), ch 02 except idempotency (GraphQL, gRPC,
  versioning)
- Ch 06 families detail and LSM internals, ch 09 (search, OLAP, batch/stream)
- Ch 10 clocks and Raft internals, ch 14 deployments and observability, ch 15 security
- Ch 18 (Dynamo-style KV store), ch 20 (video, file sync, notifications), the rest of ch 21
- Ch 22 beyond 22.8/22.11, most of ch 23's pattern code, ch 26 beyond the four sections above
- Every chapter's "Check yourself" except as a quick end-of-session test

When you have spare time later, ch 20 (notifications) and ch 15 (security basics: JWT, OAuth) are
the next most useful.

---

## The night-before page

If you only have 20 minutes before a conversation, reread these four:

1. [Ch 00](#00), section 00.3: the ten ideas.
2. [Ch 16](#16), section 16.3: the framework steps.
3. [Ch 27](#27), section 27.5: the trade-offs cheat sheet.
4. [Ch 27](#27), section 27.3: the phrase bank.
