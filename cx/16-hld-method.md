# 16 — The HLD method: how to run a design conversation
*Why it matters:* a repeatable procedure turns "I know the pieces" (ch. 01–15) into "I can lead the
conversation" under time pressure, and it is the skeleton of every case study in 17–21.

## HLD vs LLD, and what the evaluator wants
**How it works** · **High-level design (HLD)** = components (services, DBs, caches, queues), where
data lives, how requests flow, how it scales and fails. **Low-level design (LLD)** = classes and
methods inside one box (ch. 22–26).

**Example** · QuickBite "place an order" at both levels:

| | HLD | LLD |
|---|---|---|
| Decision | `order-service` writes `orders` in SQL, publishes `order-events` via outbox | `OrderService` uses a `PricingStrategy`; `Order.status` moves only along allowed transitions |
| Artifacts, concerns | Diagram, APIs, data model, numbers; scale, latency, availability, cost | Class/sequence diagram, code skeleton; extensibility, readability, testability |
| Vocabulary, failure | CAP, replication, sharding; "the orders DB primary dies" | SOLID, coupling; "two threads confirm `o_90311` at once" |
| Typical prompt | "Design YouTube" | "Design a parking lot" |

So: HLD decides the boxes and arrows; LLD decides the code inside one box.

The evaluator checks *how you think*, not a hidden answer: **scoping** (ask before drawing),
**numbers** that drive decisions, **fundamentals** (tool fits access pattern), **trade-offs**
("what we pay, when I'd choose otherwise"), **failure thinking** unprompted, **depth on demand**,
**communication** (structure, check-ins), **pragmatism** (start simple). Not on the list: naming
many technologies or reciting a famous architecture; a defended unusual design beats a textbook
one you cannot defend.

## The eight steps with time boxes
**How it works** · For a 45-minute round (in 60 minutes, give the extra to deep dives; a real
design review runs the same steps over days):

```text
 min  0 ─┬─ (1) Functional requirements ..... ~4   "what must it do?"
         ├─ (2) Non-functional requirements .. ~4   "how well, how big?"
         ├─ (3) Estimation ................... ~3   "what numbers matter?"
         ├─ (4) API design ................... ~4   "what's the contract?"
         ├─ (5) Data model ................... ~4   "what do we store, where?"
 min 19 ─┼─ (6) High-level architecture ...... ~8   "boxes and arrows"
 min 27 ─┼─ (7) Deep dives (2–3 hard parts) .. ~13  "where is it actually hard?"
 min 40 ─┴─ (8) Bottlenecks, failures, evolution ~5
```

Steps 1–5 must be brisk: skip them and you design the wrong system, drown in them and you never
draw. "READ HD FT": Requirements, Estimate, API, Data, High-level, Deep dives, Failures, Trade-offs.

**Example** · "Design QuickBite's order placement": what you say and draw at each step.

1. **Functional requirements** — cut to 3–5 core features; parking the rest is a strength. *Say:*
   "In: browse a menu, place an order, pay, see status. Parked: search, ratings, live map."
2. **NFRs** — drive architecture more than features ("store a key" is a hash map at 10 req/s,
   a cluster at 10 million). Told "you decide"? Assume and move on. *Say:* "5 M DAU, dinner
   peak 8–9 pm. Place order p99 under 1 s; never lose an acked order or charge twice; menus may
   be a minute stale."
3. **Estimation** (ch. 04) — only what changes a decision: QPS avg and peak, storage, bandwidth,
   cache size. A day is about 10^5 s; peak often 2–5x. Always end in a conclusion. *Write:*
   ```text
   orders  2 M/day / 86,400 s ~ 23/s avg, ~120/s at dinner peak -> one SQL primary is fine
   menus   100:1 -> ~2,300/s avg, ~12,000/s peak  -> cache menus, don't hit the DB
   storage ~1 KB/order x 2 M = ~2 GB/day, ~730 GB/yr -> one DB for now, archive old orders
   ```
4. **API** (ch. 02) — one endpoint per feature; credit for **cursor pagination**, **idempotency
   keys**, `/v1`, user from the auth token, not a `user_id` param. *Write:*
   ```http
   POST /v1/orders   Idempotency-Key: 3f9a-...   (user u_1842 from JWT)
   { "restaurant_id": "r_77", "items": [{"item_id": "m_12", "qty": 2}] }
   -> 201 { "order_id": "o_90311", "status": "PLACED", "amount": 450 }
   GET /v1/orders/o_90311 -> 200 { "status": "PREPARING", ... }
   ```
5. **Data model** — entities, key fields, above all **access patterns** (they pick the DB, ch.
   05–06). *Say:* "`orders(order_id PK, user_id, restaurant_id, status, amount, created_at)`,
   `order_items`, `payments`. By `order_id`; Asha's history by `(user_id, created_at)`; Spice
   Route's live orders by `(restaurant_id, status)`. Money needs transactions: relational."
6. **Architecture** — simplest design that works, then improve; narrate **write** and **read
   path** separately. *Draw:* the next section's diagram. *Say:* "Write: gateway -> order-service
   -> one txn (order + outbox row) -> Kafka. Read: menus from Redis, status from the orders DB."
7. **Deep dives** — find the 2–3 cruxes of *this* problem (finding them is a signal). *Say:*
   "The hard parts are the double-tap on Place order (idempotency key, unique constraint) and the
   payment saga (charge fails -> CANCELLED). Shall I start with the double-tap?"
8. **Wrap-up** — bottleneck at 10x, **single points of failure** box by box, trade-offs made,
   evolution. *Say:* "At 10x (1,200 orders/s) I'd shard orders by `user_id`; menus and status
   are eventually consistent by a few seconds; next I'd add the rider live map."

So: each step yields one artifact or sentence that later steps use. Stuck at minute 20 on the
data model? "I'll lock this in and move on"; deep dives and failures carry the most signal.

Other cruxes: URL shortener = unique codes, redirect caching; feed = fan-out, celebrities; chat
= WebSockets, ordering, receipts; rate limiter = atomic shared counters, algorithm; video =
transcoding, adaptive bitrate, CDN; typeahead = prefix structure, top-k, freshness; payments =
idempotency, exactly-once effect, reconciliation.

| NFR dimension | Ask | Decides |
|---|---|---|
| Scale | Users, DAU, peak req/s, growth? | Single box vs sharded fleet |
| Read/write ratio | Read-heavy (feeds, redirects) or write-heavy (logs, metrics)? | Caching, replicas, LSM vs B-tree |
| Data size | Item size, retention? | Storage tier, sharding, blob storage |
| Latency | p50 / p99 for the main operation? | Caching, precompute, sync vs async |
| Availability | 99.9% (about 8.8 h/yr, 43 min/mo) or 99.99% (about 53 min/yr, 4.3 min/mo)? | Replication, multi-AZ/region, failover |
| Consistency | See own write at once? Everyone same value? | Strong vs eventual, leader reads, quorum |
| Durability | Lose an acked write: ok (metrics) or never (payments)? | Sync replication, fsync, backups |
| Geography / security / cost | Global? PII, compliance? Budget? | CDN, residency; encryption; tiering, managed |

## Drawing the diagram
**How it works** · Place boxes in the order a request travels, left to right: client -> DNS/CDN ->
load balancer (+ gateway) -> services -> cache -> database, with queue -> workers on the side.
Start minimal and add each box only when a requirement or number justifies it.

**Example** · QuickBite order placement, grown from `client -> order-service -> Orders DB`:

```text
 app -> CDN (dish photos)
 app -POST /v1/orders-> api-gateway (JWT, rate limit) -> order-service x N
                                                          | 1 txn: order + outbox row
 app -GET menu-> restaurant-service x N -> Menu cache    [Orders DB: primary + replica]
                                          (Redis) -> DB   | outbox relay
                                                          v
                                       Kafka order-events - - -> payment-service
                                                          - - -> notification-service
                                                          - - -> rider-service
```

Each box has a reason: CDN for images, gateway for auth and OTP rate limit, Redis for 12,000 menu
reads/s, Kafka for three slow side effects. So: name boxes by role, label arrows, split write and
read paths, stack stateless services, solid = sync, dashed = async; 15 early boxes = 15 to defend.

## Driving vs being driven
**How it works** · **Driving** = you set the agenda; **being driven** = waiting for the next
question. Drive by **signposting**, **time-checking** yourself, **checking in at transitions**
(not every sentence) and **offering choices** for deep dives.

**Example** · the same QuickBite round, two voices:

| Moment | Being driven (weaker) | Driving (stronger) |
|---|---|---|
| Minute 0 | Waits for questions | "Requirements, numbers, API, data, then architecture and deep dives." |
| Minute 15, still on NFRs | Keeps going | "Let me move to the architecture now." |
| After the diagram | "Is that ok?" | "Let me walk o_90311 through this, from tap to Kafka." |
| Choosing a DB | "Should I use SQL?" | "SQL, because order + payment need a transaction, unless you see a reason not to." |
| "How do you store menus?" | Returns to own plan | Answers it at once; they just told you what they want to see |

## The classic follow-ups
**How it works** · **"Traffic goes 10x?"** Never just "add servers"; walk the path for what breaks
first. Stateless tiers add instances, **stateful** tiers partition, then hunt **hot spots**
partitioning does not fix.

**Example** · QuickBite at 10x: 1,200 orders/s and 120,000 menu reads/s at peak.

```text
 layer            at 10x                                  fix
 order-service    CPU bound                               more pods (stateless)
 Menu cache       one Redis node's memory/network         shard (consistent hashing)
 Orders DB writes one primary near its limit             shard by user_id (ch. 07)
 Kafka consumers  rider-service lag grows                 more partitions + consumers
 hot key          promo: menu:r_77 at 20,000/s, one shard  replicate hot key, local cache
```

**"This node dies?"** Three parts: **detect** (LB health checks, heartbeats, consensus, ch. 10),
**fail over** (stateless: trivial; promote replica: needs election, split-brain risk; standby
cache: cold, DB spike), **lose what?** (async replication may lose acked-but-unreplicated writes;
sync loses none but writes are slower). *Example:* the Orders DB primary dies at 8:30 pm;
heartbeats detect it in seconds; a replica is promoted; with async replication `o_90311`, acked
50 ms earlier, may be gone, so orders (money) get a sync replica. Repeat for every stateful box.

**"Make it consistent?"** First ask which kind. *Example:* Asha places `o_90311`, opens "My
orders" and it is missing (a lagging replica served the read): that is read-your-writes, fixed by
reading from the leader for a few seconds after her write.

| They might mean | Typical tool |
|---|---|
| **Read-your-writes** | Read from leader briefly after a write, or sticky to leader |
| **Strong / linearizable** | Single leader + sync replication, quorum R + W > N, or consensus |
| Two systems agree (DB vs cache/index) | Invalidate/write-through; **outbox** + CDC (ch. 11) |
| Multi-step operation all-or-nothing | Transaction in one service; **saga** across services (ch. 13) |
| No double effects on retry | **Idempotency keys**, unique constraints |

Cost: more consistency = more coordination = higher latency, lower availability under partition.

**"Why not X?"** An invitation, not a trap: "X gives A but costs B; given C I'd still pick Y; if C
changed, X wins." *Example:* "Why not Cassandra for orders?" "Huge write throughput, but we lose
multi-row transactions; at 120 orders/s we need atomic order + payment state more. At 100x
writes I'd revisit." Changing your mind for a good reason is fine.

## Trade-off vocabulary
**How it works** · Most decisions are one of these; naming it states the decision in one sentence.

**Example** · menus cached a minute stale = *consistency vs availability* (AP; we pay stale
prices, so order-service re-checks price at placement). Rider pings batched = *latency vs
throughput*. Notifications via Kafka = *sync vs async* (seconds of delay buy a fast order API).

| Trade-off | A | B | Rule of thumb |
|---|---|---|---|
| **Consistency vs availability** | Refuse/delay in partition (CP) | Answer, maybe stale, reconcile (AP) | Money, inventory, locks CP; feeds, likes, carts AP |
| **Latency vs throughput** | Process each item now | Batch | User-facing latency; pipelines/logging throughput |
| **Read vs write optimization** | Work at write (precompute, index) | Work at read | Read-heavy pay at write; write-heavy keep writes cheap |
| **Push vs pull** | Producer pushes (fan-out on write, WebSockets) | Consumer polls (fan-out on read) | Few recipients, latency-sensitive push; huge fan-out/inactive pull |
| **Normalize vs denormalize** | One copy, joins at read | Duplicate to avoid joins | Write-heavy, consistency-critical normalize; read-heavy denormalize |
| **Sync vs async** | Caller waits | Hand off via queue | Need answer now sync; slow, retryable side effects async |
| **Cost vs performance** | More replicas, memory | Fewer resources, tiering | Spend on hot path, save on cold data |
| **Simplicity vs flexibility** | Monolith, one DB | Microservices, polyglot | Start simple; split on clear pain |
| **Accuracy vs resources** | Exact counts/dedup | HyperLogLog, Bloom, sampling | Billing exact; analytics, "views" approximate |

## The default toolbox
> **Black box:** a requirement -> default component lookup; defaults, not laws. **Use when:** a
> signal appears; start from the default and adjust (e.g. "biryani near me" -> search index +
> geohash). **Cost:** each has a "when to reconsider".

| Signal | Default (ch.) | Reconsider when |
|---|---|---|
| Relations, transactions | Relational DB (05) | Writes beyond sharded SQL; schema-less |
| Huge writes, simple key access | Wide-column / KV, Cassandra, DynamoDB (06) | Need joins, multi-row txns |
| Same items read repeatedly | Cache-aside, Redis/Memcached (08) | Constantly changing, strict freshness |
| Static media, large blobs | Object storage + CDN, metadata in DB (08, 09) | Personalized; small values (<~100 KB) can sit in DB |
| Full-text / fuzzy search | Inverted-index engine, Elasticsearch (09) | Exact key lookups only |
| Analytics over history | Columnar OLAP / warehouse (09) | Low-latency point lookups |
| Absorb spikes, decouple; many consumers, replay | Queue (SQS-like) / log broker, Kafka (11) | Need a sync answer; simple task queue suffices |
| DB change must emit event; abuse/overload | Outbox or CDC (11); gateway token bucket (12, 18) | — |
| Server -> client real time | WebSockets / SSE (02) | Rare updates: polling is simpler |
| Unique IDs across machines | Snowflake-style or UUIDs (10) | Dense sequential: central sequence |
| Leader election, locks, config; delayed jobs | ZooKeeper / etcd (10); scheduler + delay queue (11) | — |
| Cross-service multi-step | Saga with compensations (13) | One DB: plain transaction |
| "Near me"; leaderboard | Geohash / quadtree (09); Redis sorted set (08) | Huge leaderboard: shard or approximate |
| Approx. distinct count; "seen before?" cheaply | HyperLogLog (08); Bloom filter (06) | Exact required; false positives need a fallback check |

## Red flags
**How it works** · Unjustified microservices (add network, deploy, consistency cost; split by clear
responsibility or scaling need) · "add Kafka" with no named problem (spikes, decoupling, replay,
fan-out) · no numbers (decisions look arbitrary) · ignoring failure · "it's scalable" without
saying which tier scales how · technology by name only (tie to an access pattern) ·
over-designing early ("V1 is X; at 10x Y") · never stating trade-offs · 20-minute monologues or
silent thinking (think aloud, check in) · one DB for everything at huge scale with no split plan.

**Example** · flagged: "Eight microservices, Kafka, MongoDB and Cassandra; it's scalable." Fixed:
"order-service owns orders in SQL (order + payment need a transaction; 120/s fits one primary);
Kafka because three consumers react to `order-events`; at 10x I shard by `user_id`."

## Worked run-through: a game leaderboard
**How it works** · The same eight steps on a different prompt, compressed, to show the shape.

**Example** · "Design a leaderboard for an online game."
(1) Submit cumulative season score per match; top 100; a player's rank with 5 above/below.
Parked: friend boards, anti-cheat, past seasons. (2) 50 M monthly, 10 M DAU; p99 rank < 100 ms;
lag of a second or two ok; score updates durable. (3) 10 matches/DAU -> 100 M updates/day ->
about 1,200/s avg, ~5,000/s peak; reads ~2x writes. Sorted set at very roughly 100 B/member ->
~5 GB for 50 M: fits one Redis node; still need a durable source of truth. (4) `POST /v1/scores
{match_id, player_id, delta}` (match_id makes replays no-ops), `GET /v1/leaderboard/top?limit=100`,
`GET /v1/leaderboard/players/{id}?around=5`. (5) SQL `match_result` unique on
`(match_id, player_id)` + `player_score`; Redis **sorted set** `lb:season42` (skip list + hash:
`ZINCRBY`, `ZREVRANK` O(log N); `ZREVRANGE 0 99` O(log N + 100)). (6) Write: game server ->
Score API -> SQL txn -> outbox/CDC -> queue -> LB updater -> Redis. Read: client -> Leaderboard
API -> `ZREVRANGE`/`ZREVRANK` on Redis. (7) Queues redeliver: `p42` had 1,220, match `m1` adds
30, event delivered twice: `ZINCRBY` gives 1,280 (wrong); instead the worker reads the total
1,250 from SQL and runs `ZADD lb:season42 1250 p42`, so a replay leaves 1,250 (idempotent).
Redis lost -> rebuild from `player_score`; keep a replica. At ~1 billion players: shard by player
ID, merge per-shard top-K, approximate rank from a score histogram ("top 3%"). (8) 10x: shard
Redis. Primary dies: replica promoted, reconcile missed seconds from SQL. Trade-off: rank is
seconds behind for cheap reads and writes.

> **Say it like this:** "SQL is the source of truth so we never lose a score; the Redis sorted set
> is a derived, rebuildable view optimized for rank queries."

## In one minute
- HLD = components and data flow; LLD = classes inside one box.
- Evaluators grade reasoning: scoping, numbers, fundamentals, trade-offs, failures, communication.
- Eight steps; in 45 min, steps 1–5 take about 19 min, deep dives get the most time.
- NFRs drive architecture; every estimate ends in a decision (23 orders/s -> one primary).
- Draw in request order, label arrows, add boxes only when justified; drive with signposts.
- 10x: stateless adds instances, stateful partitions, then hot spots. Node dies: detect, fail over,
  what is lost. Consistent: ask which kind. Always say what you pay.

## Go deeper
Full chapter 16: sections 16.3 (the eight steps), 16.6 (follow-up questions), 16.10 (leaderboard run-through)
