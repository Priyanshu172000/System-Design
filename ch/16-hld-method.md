# 16 — The HLD method: how to run a design conversation

Chapters 01–15 gave you the parts: servers, databases, caches, queues, consistency models.
This chapter gives you the procedure for putting them together out loud, under time pressure,
with someone asking questions. A repeatable method is what turns "I know the pieces" into
"I can lead the conversation", and it is the same method you will use for every case study in
chapters 17–21.

## 16.1 What HLD is (and what it is not)

**High-level design (HLD)** is the design of a system as a set of cooperating components:
which services exist, where data lives, how requests flow, how the system scales and fails.
The unit of thought is a box on a diagram (a service, a database, a queue).

**Low-level design (LLD)** is the design of the code inside one of those boxes: classes,
interfaces, relationships, design patterns, concurrency within a process (chapters 22–26).
The unit of thought is a class or a method.

| Aspect | HLD | LLD |
|---|---|---|
| Question it answers | "What components, and how do they talk?" | "What classes, and how do they collaborate?" |
| Unit | Service, database, cache, queue, CDN | Class, interface, enum, method |
| Typical artifacts | Architecture diagram, API list, data model, capacity numbers | Class diagram, sequence diagram, code skeleton |
| Main concerns | Scale, latency, availability, consistency, cost, failure | Extensibility, readability, correctness, testability |
| Trade-off vocabulary | CAP, replication, sharding, sync vs async | SOLID, coupling vs cohesion, composition vs inheritance |
| Failure thinking | "A node dies / a region goes down" | "Two threads hit this method at once" |
| Typical prompt | "Design YouTube" | "Design a parking lot" |

A useful one-liner: HLD decides that there is a "Payment Service" that talks to a "Ledger DB"
through an outbox; LLD decides that `PaymentService` has a `PaymentProcessor` strategy and a
`RetryPolicy`.

> **Say it like this:** "Let me stay at the component level for now — services, storage and
> data flow — and we can zoom into the class design of any one box if that's useful."

## 16.2 What the evaluator actually looks for

Whether it is an interview or a design review, the person across the table is rarely checking
whether you reach "the right answer". Most design problems have several acceptable answers.
They are checking *how you think*. Roughly, these signals:

| Signal | What it looks like when done well |
|---|---|
| Problem scoping | You ask questions before drawing; you pin down what is in and out of scope. |
| Quantitative thinking | You put numbers on load and storage and let them drive decisions. |
| Sound fundamentals | Your choice of DB, cache, queue fits the access pattern, and you say why. |
| Trade-off awareness | Every choice comes with "what we pay for this" and "when I would choose differently". |
| Failure thinking | You ask "what if this dies?" before being asked. |
| Depth on demand | When pushed on one component, you can go two levels deeper. |
| Communication | Structured, easy to follow, you check in, you summarize. |
| Pragmatism | You start simple and evolve; you do not over-engineer. |

Notice what is *not* on the list: naming the most technologies, or reproducing a famous
company's architecture from memory.

> **Common mistake:** Treating the conversation as a quiz with a hidden answer. The evaluator
> wants to see a reasoning process. A slightly unusual design with clearly stated trade-offs
> beats a "textbook" design you cannot defend.

## 16.3 The framework at a glance

Eight steps. The time boxes are for a 45-minute round; in a 60-minute round, give the extra
time to deep dives. In a real design review the same steps apply, just over days instead of
minutes.

```text
 min  0 ──┬── (1) Functional requirements ........ ~4 min   "what must it do?"
          ├── (2) Non-functional requirements ..... ~4 min   "how well, how big?"
          ├── (3) Estimation ...................... ~3 min   "what numbers matter?"
          ├── (4) API design ...................... ~4 min   "what's the contract?"
          ├── (5) Data model ...................... ~4 min   "what do we store, where?"
 min 19 ──┼── (6) High-level architecture ......... ~8 min   "boxes and arrows"
 min 27 ──┼── (7) Deep dives (2–3 hardest parts) .. ~13 min  "where is it actually hard?"
 min 40 ──┴── (8) Bottlenecks, failures, evolution  ~5 min   "what breaks, what next?"
 min 45
```

Steps 1–5 are about 19 minutes. People new to this either skip them (and design the wrong
system) or drown in them (and never reach the architecture). Keep them brisk: the goal is just
enough clarity to make good decisions.

### Step 1 — Clarify functional requirements (~4 min)

**Functional requirements** are what the system does: features, user actions. The prompt
"design Twitter" is deliberately vague; your first job is to cut it down to 3–5 core features.

How to do it:

1. List the candidate features you can think of.
2. Propose which are in scope ("core") and which are out of scope.
3. Get agreement.

```text
Prompt: "Design Instagram"

Candidate features            In scope?
------------------------------------------
Upload photo with caption      yes  (core write path)
Follow / unfollow users        yes
Home feed of followed users    yes  (core read path)
Like / comment                 maybe -> "likes yes, comments out of scope"
Stories, reels, DMs, search    no   (explicitly parked)
Ads, recommendations           no
```

Explicitly parking features is a strength, not a weakness. It shows you can prioritize.

> **Say it like this:** "I'd like to focus on three things: posting a photo, following users,
> and the home feed. I'll leave stories, DMs and search out unless you want them in — does that
> match what you had in mind?"

### Step 2 — Non-functional requirements (~4 min)

**Non-functional requirements (NFRs)** are how well the system must do it: scale, speed,
availability, consistency, durability, security, cost. NFRs drive the architecture far more
than features do. "Store a key and return it" is a hash map at 10 requests/sec and a
distributed system at 10 million.

Checklist of questions to ask (pick the relevant ones, not all):

| Dimension | Questions to ask | Why it matters |
|---|---|---|
| Scale | How many users? Daily active users (DAU)? Requests/sec at peak? Growth rate? | Single box vs sharded fleet. |
| Read/write ratio | Is it read-heavy (feeds, URL redirects) or write-heavy (logging, metrics)? | Decides caching, replicas, LSM vs B-tree. |
| Data size | How big is one item? How long do we keep it? | Storage tier, sharding, blob storage. |
| Latency | What is acceptable at p50 / p99 for the main operation? | Caching, precomputation, sync vs async. |
| Availability | Is a few minutes of downtime a disaster? Target 99.9% or 99.99%? | Replication, multi-AZ/region, failover. |
| Consistency | Must a user see their own write immediately? Must everyone see the same value? | Strong vs eventual; leader reads; quorum. |
| Durability | Is losing an acknowledged write acceptable (metrics) or never (payments)? | Sync replication, fsync, backups. |
| Geography | One region or global? | CDN, multi-region, data residency. |
| Security/privacy | Auth? PII? Compliance? | Encryption, access control, retention. |
| Cost | Is there a budget constraint? | Storage tiering, managed vs self-hosted. |

Reminder of availability math (chapter 14): 99.9% allows about 8.8 hours of downtime per
year (about 43 minutes per month); 99.99% allows about 53 minutes per year (about 4.3 minutes
per month).

If the interviewer says "you decide", state an assumption and move on:

> **Say it like this:** "I'll assume 100 million daily active users, a 100-to-1 read-to-write
> ratio, p99 under 200 ms for reading the feed, and that a new post can take a few seconds to
> show up for followers — eventual consistency is fine there."

### Step 3 — Estimation (~3 min)

Back-of-the-envelope numbers (chapter 04). Only estimate what will change a decision:

- **QPS** (queries per second) average and peak — tells you how many servers and whether one
  DB can take the writes.
- **Storage** per day and over the retention period — tells you whether you must shard and
  whether data belongs in a blob store.
- **Bandwidth** — matters for media (video, images).
- **Cache size** — "can the hot set fit in memory?"

Shortcut: 1 day is about 86,400 seconds, call it 10^5. So "X per day" is roughly X / 10^5 per
second. 100 million writes/day is about 1,000–1,200 writes/sec on average. Peak is often
2–5x average.

Then say what the number *means*: "1,200 writes/sec fits on a single well-tuned primary DB, but
120,000 reads/sec does not — so reads need caching and replicas." A number with no conclusion
is wasted time.

> **Common mistake:** Spending 10 minutes on precise arithmetic. Round aggressively (powers of
> 10), state the result, draw the conclusion, move on.

### Step 4 — API design (~4 min)

Define the contract between client and system (chapter 02). For each core feature, one
endpoint. Include the important parameters, the response shape, and anything that affects
design (pagination, idempotency keys, auth).

```http
POST /v1/posts                       (auth: bearer token)
Idempotency-Key: 7f3c...
{ "caption": "...", "media_id": "m_123" }
-> 201 { "post_id": "p_987", "created_at": "..." }

GET /v1/feed?limit=20&cursor=eyJ0cyI6...
-> 200 { "items": [ ... ], "next_cursor": "..." }

POST /v1/users/{id}/follow
-> 204
```

Points that earn credit: cursor-based pagination for feeds (stable under inserts), idempotency
keys on non-idempotent writes, versioned paths (`/v1`), and identifying the user from the auth
token rather than a `user_id` parameter.

### Step 5 — Data model (~4 min)

List the main entities, their key fields, and — most important — the **access patterns**: the
queries the system will run. The access patterns choose the database (chapters 05–06).

```text
User(user_id PK, name, created_at)
Post(post_id PK, author_id, caption, media_url, created_at)
Follow(follower_id, followee_id, created_at)   PK(follower_id, followee_id)

Access patterns:
  A1  get posts by author, newest first        -> index (author_id, created_at DESC)
  A2  who does user X follow?                   -> PK prefix scan on follower_id
  A3  who follows user Y? (for fan-out)         -> secondary index (followee_id)
  A4  get home feed for X                       -> precomputed feed list (cache)
```

Then pick storage and justify in one sentence: "Users and follows are relational and modest in
size → a relational DB. Posts are large in count, append-mostly, accessed by key → a
wide-column store or sharded SQL keyed by author. Media bytes → object storage + CDN."

### Step 6 — High-level architecture (~8 min)

Draw the boxes and walk one request through them (section 16.4 shows how). Start with the
simplest design that meets the requirements, then improve it. Narrate the **write path**
(how data gets in) and the **read path** (how data gets out) separately — they often have
very different designs.

### Step 7 — Deep dives (~13 min)

Pick the 2–3 hardest parts of *this* problem and go deep. Every problem has a few "crux"
components; finding them is itself a signal.

| Problem | Typical cruxes |
|---|---|
| URL shortener | Unique short code generation; redirect latency/caching |
| News feed | Fan-out on write vs read; celebrity users |
| Chat | Connection management (websockets); message ordering; delivery receipts |
| Rate limiter | Atomic counters in a shared store; algorithm choice |
| Video streaming | Transcoding pipeline; adaptive bitrate; CDN |
| Typeahead | Prefix data structure; precomputed top-k; update freshness |
| Payments | Idempotency; exactly-once effect; reconciliation |

Often the interviewer picks the deep dive. If they do not, propose: "The two hardest parts
here are X and Y — shall I go into X first?"

### Step 8 — Bottlenecks, failure modes, trade-offs, evolution (~5 min)

Close by stepping back:

- **Bottlenecks:** "At 10x, the first thing to break is the follow-graph DB for fan-out."
- **Single points of failure (SPOFs):** walk the diagram box by box: "What if this dies?"
- **Trade-offs made:** "We chose eventual consistency for the feed; the cost is a post can
  take a few seconds to appear."
- **Evolution:** what you would add with more time or scale (multi-region, better ranking,
  archival tiering).

> **Say it like this:** "To summarize: writes go through the post service into the DB and
> trigger async fan-out; reads come from precomputed feeds in the cache. The weak spot is
> celebrity fan-out, which we handle with a hybrid pull model. If we grew 10x, I'd shard the
> feed cache by user and move fan-out workers to their own cluster."

## 16.4 How to draw the diagram

A diagram is easiest to follow when components appear in a standard order, left to right (or
top to bottom), in the direction a request travels:

```text
 client -> DNS / CDN -> load balancer -> services -> cache -> database
                                          |
                                          +-> queue -> workers -> (DB, search, blob, ...)
```

A typical full picture:

```text
                    +-----------+
                    |   DNS     |   resolves api.example.com
                    +-----------+
  +---------+             |            +-----------+
  | Client  |--- static ----------------->   CDN    |  images, JS, video segments
  | (app/   |                          +-----------+
  | browser)|--- API ---+
  +---------+           v
                 +---------------+
                 | Load balancer |   (+ API gateway: auth, rate limit)
                 +---------------+
                   |           |
          +--------v---+   +---v--------+
          | Service A  |   | Service B  |     stateless, horizontally scaled
          +------------+   +------------+
             |      |          |
             |  +---v----+     |
             |  | Cache  |     |              Redis / Memcached
             |  +--------+     |
          +--v-------------+   |
          |  Database      |   |              primary + replicas, sharded if needed
          +----------------+   |
                               v
                         +-----------+     +-----------+     +-------------+
                         |  Queue    |---->|  Workers  |---->| Search /    |
                         | (Kafka)   |     |           |     | blob / OLAP |
                         +-----------+     +-----------+     +-------------+
```

Rules that make diagrams readable:

1. **Name every box by its role**, not just a product: "Feed cache (Redis)", not "Redis".
2. **Label arrows** with what flows: "POST /posts", "PostCreated event", "fetch feed ids".
3. **Separate the write path and read path**, or number the steps (1, 2, 3...) along one
   request.
4. **Draw stateless services as a stack** (a "x N" note) to show they scale horizontally.
5. **Mark sync vs async**: solid arrow = synchronous call; dashed or "via queue" = async.
6. **Start small.** First draw the minimal version (client → service → DB), then add cache,
   queue, CDN as you justify each one. Every box should answer a requirement or a number.

> **Common mistake:** Drawing 15 boxes in the first two minutes (Kafka, Elasticsearch,
> Cassandra, three caches) before any requirement justifies them. Each box you add is a box you
> must be able to defend.

## 16.5 Driving vs being driven

**Driving** means you set the agenda: you announce the steps, move between them, and propose
what to deep-dive. **Being driven** means you wait for the interviewer to ask the next
question. Driving is a strong signal of seniority — but you must still listen.

How to drive well:

- **Signpost.** "First I'll clarify requirements, then rough numbers, then API and data model,
  then the architecture, then deep dives." This gives the listener a map.
- **Time-check yourself.** If you are 15 minutes in and still on requirements, say "Let me
  move to the architecture now" and do it.
- **Check in at transitions**, not every sentence: "Does this level of detail work, or would
  you like me to go deeper on storage?"
- **Offer choices for deep dives**: "I see three hard parts: A, B, C. I'd start with A
  because it's the most likely bottleneck — or is there one you'd prefer?"

When the interviewer steers, follow. A redirect ("let's talk about how you'd store this") is
information: they want to see that area. Do not fight to return to your plan.

| Situation | Being driven (weaker) | Driving (stronger) |
|---|---|---|
| Start of round | Waits for questions | Announces plan, asks scoping questions |
| Silence | Waits | "Next I'll sketch the API." |
| After drawing | "Is that ok?" | "Let me walk a request through this." |
| Choosing a DB | "Should I use SQL?" | "I'll use SQL because... unless you see a reason not to." |
| Interviewer redirects | — | Follows immediately, ties back later if relevant |

## 16.6 Handling the classic follow-up questions

Follow-ups are where the deep signal is. The four classics, with a script for each.

### "What if traffic goes 10x?"

Do not just say "add more servers". Walk the request path and find what breaks first:

```text
  layer           at 10x...                              fix
  -----------------------------------------------------------------------------
  LB              fine (scales horizontally / managed)   -
  app servers     CPU bound                              add instances (stateless)
  cache           memory / network of one node           shard cache (consistent hashing)
  DB reads        replicas saturated                     more replicas, better cache hit
  DB writes       single primary maxed out               shard by key (ch. 07)
  queue/workers   consumer lag grows                     more partitions + consumers
  hot keys        one celebrity key hammers one shard    replicate hot keys, local caches
```

The general pattern: stateless tiers scale by adding instances; **stateful** tiers (DB, cache)
need partitioning; then look for **hot spots** that partitioning alone does not fix.

> **Say it like this:** "The stateless services scale by adding instances, so the first real
> limit is write throughput on the primary database. At roughly 10x I'd shard by user ID.
> I'd also check for hot keys, because sharding doesn't help when one key gets most traffic."

### "What if this node dies?"

Answer in three parts: **detect**, **fail over**, **lose what?**

1. **Detection:** health checks (LB), heartbeats, or a consensus system (chapter 10).
2. **Failover:** who takes over — another stateless instance (trivial), a replica promoted to
   leader (needs leader election, risk of split brain), a standby cache (cold cache → DB load
   spike).
3. **Data loss / impact:** with async replication, writes acknowledged by the old leader but
   not yet replicated may be lost. With sync replication, none are lost but writes are slower.

Apply this to each stateful box. For stateless boxes, the answer is short: "the LB stops
routing to it after failed health checks; other instances absorb load."

### "How would you make it consistent?"

First ask *which* consistency: the word means several things (chapter 10).

| What they might mean | Typical tool |
|---|---|
| User sees their own write (**read-your-writes**) | Read from leader for a short window after a write, or sticky session to the leader |
| No one sees stale data (**strong / linearizable**) | Single leader + sync replication, or quorum reads/writes (R + W > N), or consensus |
| Two systems agree (DB and cache, DB and search index) | Write-through/invalidate on write; **outbox** + CDC to propagate changes (chapter 11) |
| A multi-step business operation is all-or-nothing | DB transaction within one service; **saga** with compensations across services (chapter 13) |
| No double effects on retry | **Idempotency keys**, unique constraints |

Then state the cost: stronger consistency = more coordination = higher latency and lower
availability during partitions.

> **Say it like this:** "When you say consistent, do you mean the user should see their own
> post immediately, or that all users see it at the same moment? The first is cheap — I route
> that user's reads to the leader for a few seconds. The second needs synchronous replication,
> which costs write latency."

### "Why not use X instead?"

This is usually not a trap; it is an invitation to compare. Give the comparison honestly:
"X would give us A, but we'd pay B. Given our requirement C, I'd still pick Y — but if C
changed, X becomes the better choice." Changing your mind for a good reason is fine.

## 16.7 The trade-off vocabulary

Most design decisions are one of a handful of recurring trade-offs. Having names for them lets
you state a decision in one sentence.

| Trade-off | Option A | Option B | Rule of thumb |
|---|---|---|---|
| **Consistency vs availability** | Refuse or delay requests during a partition to stay correct (CP) | Keep answering, possibly stale, reconcile later (AP) | Money, inventory, locks → CP. Feeds, likes, carts → AP. |
| **Latency vs throughput** | Process each item immediately (low latency) | Batch items (higher throughput, each waits longer) | User-facing → latency. Pipelines, logging → throughput. |
| **Read vs write optimization** | Do work at write time (precompute, denormalize, index) | Do work at read time (compute on query) | Read-heavy → pay at write. Write-heavy → keep writes cheap. |
| **Push vs pull** | Producer pushes updates to consumers (fan-out on write, websockets) | Consumer pulls/polls when needed (fan-out on read) | Few recipients, latency-sensitive → push. Huge fan-out or inactive consumers → pull. |
| **Normalization vs denormalization** | One copy of each fact, joins at read time | Duplicate data to avoid joins | Write-heavy, consistency-critical → normalize. Read-heavy at scale → denormalize. |
| **Sync vs async** | Caller waits for the result | Caller hands off via a queue, gets result later | Need the answer now → sync. Slow, retryable, side effects → async. |
| **Cost vs performance** | More replicas, more memory, premium hardware | Fewer resources, tiered storage, accept slower paths | Spend on the hot path; save on cold data. |
| **Simplicity vs flexibility** | Monolith, one DB | Microservices, polyglot storage | Start simple; split when a clear pain appears. |
| **Accuracy vs resources** | Exact counts, exact dedup | Approximate (HyperLogLog, Bloom filter, sampling) | Billing → exact. Analytics, "views" → approximate is fine. |

> **Say it like this:** "This is a read-versus-write optimization trade-off: we do the work
> once at write time so that the 100x more frequent reads are cheap."

## 16.8 The default toolbox

When a requirement shows up, there is usually a standard component that answers it. These are
defaults, not laws — each has a "when not" — but starting from a sensible default and
adjusting is faster than inventing.

| Requirement / signal | Default choice | Chapter | When to reconsider |
|---|---|---|---|
| Structured data, relations, transactions | Relational DB (PostgreSQL/MySQL) | 05 | Write volume beyond a sharded SQL setup; schema-less needs |
| Huge write volume, simple key access | Wide-column / key-value (Cassandra, DynamoDB) | 06 | Need joins or multi-row transactions |
| Read-heavy, same items read repeatedly | Cache (Redis/Memcached), cache-aside | 08 | Data changes constantly; strict freshness |
| Static assets / media to global users | Object storage (S3-like) + CDN | 08, 09 | Highly personalized, uncacheable content |
| Large files / blobs | Object storage; metadata in DB | 09 | Small values (<~100 KB) may sit in the DB |
| Full-text search, fuzzy match | Search engine (inverted index, e.g. Elasticsearch) | 09 | Exact key lookups only |
| Analytics over large history | Columnar OLAP store / data warehouse | 09 | Low-latency point lookups |
| Decouple producer from slow consumer; absorb spikes | Message queue / log (Kafka, SQS-like) | 11 | Need an immediate synchronous answer |
| Event to many consumers, replayable | Log-based broker (Kafka) | 11 | Simple task queue suffices |
| DB change must reliably produce an event | Transactional outbox or CDC | 11 | — |
| Real-time server → client updates | WebSockets / SSE | 02 | Infrequent updates → polling is simpler |
| Protect from abuse / overload | Rate limiter at gateway (token bucket) | 12, 18 | — |
| Unique IDs across many machines | Snowflake-style IDs or UUIDs | 10 | Need dense sequential IDs → central sequence |
| Leader election, distributed lock, config | Consensus store (ZooKeeper/etcd) | 10 | — |
| Cross-service multi-step operation | Saga with compensations | 13 | Everything in one DB → plain transaction |
| Scheduled / delayed jobs | Job scheduler + queue with delay | 11 | — |
| Proximity search ("near me") | Geohash / quadtree index | 09 | — |
| Ranking / leaderboard | Redis sorted set | 08 | Huge cardinality → sharded or approximate |
| Counting distinct items approximately | HyperLogLog | 08 | Exact counts required |
| "Have we seen this before?" cheaply | Bloom filter | 06 | False positives unacceptable without a fallback check |

## 16.9 Red flags

Things that make a design conversation go badly, and the fix for each.

| Red flag | Why it hurts | Instead |
|---|---|---|
| Jumping to microservices with no reason | Adds network calls, deployment and consistency complexity | Start with a few services split by clear responsibility or scaling need |
| "Let's add Kafka" without a reason | Adds an operationally heavy component | Name the problem it solves: spikes, decoupling, replay, fan-out |
| No numbers at all | Decisions look arbitrary | Do 3 minutes of estimation, cite the numbers when choosing |
| Ignoring failure | Real systems fail constantly | For each stateful box: detect, fail over, data loss? |
| Saying "it's scalable" without how | Empty claim | Say which tier scales how: "stateless → add instances; DB → shard by X" |
| Choosing a technology by name only | "Use MongoDB" says nothing | Tie it to an access pattern: "key lookups at high write rate" |
| Over-designing early | Complexity before need | "V1 is X; at 10x we evolve to Y" |
| Never stating trade-offs | Looks like you don't see the costs | "We gain A, we pay B" for every major choice |
| Monologuing for 20 minutes | Evaluator cannot steer | Check in at transitions |
| Silent thinking for minutes | Evaluator cannot see your reasoning | Think aloud, even if tentatively |
| Single database for everything at huge scale with no plan | Obvious bottleneck | Say when and how you would split |

## 16.10 Mini worked run-through: design a leaderboard

A short end-to-end pass, to show the shape of the method. (Each step is compressed; the case
studies in 17–21 go deeper.)

**Prompt:** "Design a leaderboard for an online game."

**(1) Functional requirements.**
- Submit a score for a player after a match (score is cumulative per season).
- Get the top 100 players.
- Get a given player's rank and the players around them (5 above, 5 below).
- Out of scope: friend leaderboards, anti-cheat, historical seasons (mentioned and parked).

**(2) NFRs.** 50 million monthly players, 10 million daily active. Rank lookups should be fast
(p99 < 100 ms). Leaderboard can lag by a second or two (eventual consistency OK). Score
updates must not be lost (durable).

**(3) Estimation.**
- Each DAU plays ~10 matches/day → 100 million score updates/day → ~1,200/sec average, say
  ~5,000/sec peak.
- Reads: say 2x writes → ~2,500/sec average.
- Memory for one sorted set of 50 million players: a Redis sorted set costs very roughly
  ~100 bytes per member including overhead → ~5 GB. Fits in memory on one large node.

Conclusion: a single in-memory sorted set can hold it; write rate is well within one Redis
node's capability (tens of thousands of simple ops/sec or more). We still need durability and
a source of truth.

**(4) API.**

```http
POST /v1/scores        { "match_id": "m1", "player_id": "p42", "delta": 30 }
                       (match_id makes it idempotent: replays are ignored)
GET  /v1/leaderboard/top?limit=100
GET  /v1/leaderboard/players/{player_id}?around=5
```

**(5) Data model.**
- Source of truth (SQL): `match_result(match_id, player_id, delta, created_at)` with a
  unique key on `(match_id, player_id)` for idempotency, and `player_score(player_id, total)`.
- Serving structure: Redis **sorted set** `lb:season42` — member = player_id, score = total.
  A sorted set is a skip list + hash map: `ZINCRBY` is O(log N), `ZREVRANK` is O(log N),
  `ZREVRANGE 0 99` is O(log N + 100).

**(6) Architecture.**

```text
 game server --POST score--> [Score API] --txn--> [SQL: match_result, player_score]
                                  |                       |
                                  |                  (outbox / CDC)
                                  |                       v
                                  |                 [Queue] --> [LB updater]
                                  |                                   |
 client --GET top/rank--> [Leaderboard API] <--- ZREVRANGE/ZREVRANK -- [Redis sorted set]
                                                                ZINCRBY <--+
```

Write path: record the result in SQL (idempotent), emit an event via outbox, a worker applies
`ZINCRBY`. Read path: query Redis directly.

**(7) Deep dives.**
- *Idempotency:* if the worker retries an event, `ZINCRBY` would double-count. Fix: the
  worker sets the absolute total from SQL (`ZADD lb player total`) instead of incrementing,
  so replays are harmless.
- *Recovery:* if Redis is lost, rebuild the sorted set from `player_score` (a batch scan). Run
  a Redis replica for fast failover.
- *Scale beyond one node (say 1 billion players):* shard by score range is tricky (ranges
  shift); a common approach is to shard by player ID, compute exact top-K by merging each
  shard's top-K, and compute a player's approximate rank from a score histogram (count of
  players per score bucket). Exact global rank at that size is expensive; approximate is
  usually acceptable ("top 3%").

**(8) Wrap-up.**
- Bottleneck at 10x: Redis memory and single-node writes → shard as above.
- Failure: Redis primary dies → replica promoted; a few seconds of updates may be lost from
  Redis but SQL has them → reconcile job.
- Trade-off: rank is eventually consistent (seconds behind) in exchange for cheap reads and
  writes.

> **Say it like this:** "The source of truth is SQL so we never lose a score; Redis sorted set
> is a derived, rebuildable view optimized for rank queries. That split lets us use the fast
> structure without trusting it with durability."

## 16.11 A checklist you can keep in your head

```text
  R  Requirements      functional (3–5 features) + non-functional (scale, latency, CAP)
  E  Estimate          QPS, storage, bandwidth, cache -> one conclusion each
  A  API               endpoints, pagination, idempotency
  D  Data              entities, access patterns, storage choice
  H  High-level        boxes in order, write path + read path
  D  Deep dives        the 2–3 hard parts
  F  Failures & 10x    detect, failover, data loss; what breaks first
  T  Trade-offs        name them, say what you pay
```

Some people remember it as "READ HD FT". Use whatever mnemonic sticks; the order is what
matters.

## 16.12 Recap

- HLD is about components and data flow; LLD is about classes inside one component.
- Evaluators look for scoping, numbers, fundamentals, trade-offs, failure thinking and
  communication — not a single right answer.
- Eight steps: functional reqs, NFRs, estimation, API, data model, architecture, deep dives,
  bottlenecks/failures/evolution. In 45 minutes, steps 1–5 take about 19 minutes.
- NFRs (scale, latency, availability, consistency, durability) drive architecture more than
  features do.
- Every estimate should end in a conclusion that changes a decision.
- Draw boxes in request order: client → DNS/CDN → LB → services → cache → DB, with queue →
  workers on the side; label arrows; separate write and read paths.
- Drive the conversation with signposts and check-ins; follow redirects immediately.
- For "10x": stateless tiers add instances, stateful tiers partition, then hunt hot spots.
  For "node dies": detect, fail over, what is lost. For "consistent": ask which kind.
- Name trade-offs explicitly and say what you pay.
- Avoid unjustified components, number-free designs and ignoring failure.

## 16.13 Check yourself

1. The prompt is "design Dropbox". List what you would ask in the first four minutes.

<details><summary>Answer</summary>

Functional scope: upload/download files, sync across devices, share with others, version
history? (Pick 3–4 core: upload, download, sync, share; park collaboration editing, search.)
Non-functional: number of users and DAU, average and max file size, total storage per user,
how fast a change must appear on other devices, durability expectations (very high — losing
files is unacceptable), whether offline edits and conflicts must be handled, global or one
region.

</details>

2. Why do NFRs usually influence the architecture more than functional requirements?

<details><summary>Answer</summary>

The same feature ("store and return a value") can be built with a hash map or with a
partitioned, replicated cluster; what decides between them is scale, latency, availability,
consistency and durability targets. Features determine *what* APIs and entities exist; NFRs
determine *how many machines, which storage, sync vs async, how much replication*.

</details>

3. You estimated 200,000 reads/sec and 2,000 writes/sec. What conclusions should you state?

<details><summary>Answer</summary>

Strongly read-heavy (100:1) → invest in read optimization: a cache in front of the DB with a
high hit rate, read replicas, possibly precomputation/denormalization. 2,000 writes/sec fits on
one well-tuned primary (roughly), so sharding for writes may not be needed yet. 200,000
reads/sec is too much for one DB node, so the cache is mandatory, not optional.

</details>

4. The interviewer asks "what if the cache cluster goes down?" Structure your answer.

<details><summary>Answer</summary>

Detect: health checks / client errors. Impact: all reads fall through to the DB — a sudden load
spike that can overload it (a "thundering herd"/cache-stampede scenario). Mitigations: cache
replicas for failover, rate-limit or shed load on the DB path, request coalescing so only one
request per key hits the DB, warm the cache gradually, serve stale data or degrade features.
Data loss: none, if the cache is only a derived copy of the DB.

</details>

5. Give an example of push vs pull and when you would choose each.

<details><summary>Answer</summary>

News feed: push (fan-out on write) copies a new post into every follower's feed at write
time — fast reads, but very expensive for users with millions of followers. Pull (fan-out on
read) assembles the feed at read time from followees' posts — cheap writes, slower reads. Push
for normal users with modest follower counts; pull for celebrities; many systems use a hybrid.

</details>

6. What is wrong with "I'll use Cassandra because it scales"?

<details><summary>Answer</summary>

It names a technology without tying it to an access pattern or a number. A better statement:
"We need ~50k writes/sec of append-only events, read by key and time range, with no joins or
multi-row transactions — a wide-column store like Cassandra fits because writes are cheap
(LSM) and it partitions by key. We give up joins and strong consistency by default."

</details>

7. In the leaderboard design, why did the worker use `ZADD` with the absolute total instead of
`ZINCRBY`?

<details><summary>Answer</summary>

Queues deliver at-least-once, so the worker may process the same event twice. `ZINCRBY` is not
idempotent — a replay double-counts. Setting the absolute total read from the source of truth
is idempotent: applying it twice leaves the same state.

</details>

8. You are 20 minutes into a 45-minute round and still discussing the data model. What do you
do?

<details><summary>Answer</summary>

Say so and move: "I'll lock this data model in and move to the architecture — we can revisit
storage in a deep dive." Then draw the high-level design quickly so there is time for deep
dives and failure discussion, which carry the most signal.

</details>
