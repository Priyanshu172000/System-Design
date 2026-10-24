# 27 — Talking with confidence: glossary, phrases and a study plan
*Why it matters:* this is the toolkit for the conversation itself: words for every concept, ready
sentences for the awkward moments, crisp answers to the follow-ups that keep coming back.

## How to use this chapter
- **Phrase bank:** read the sentences out loud; confidence is partly having said them before.
- **Follow-ups:** cover the answer, answer aloud, compare.
- **Trade-offs cheat sheet:** the 20 decisions that cover most design discussions.
- **Glossary:** a self-test. Any term you cannot explain in one breath, revisit its chapter.
- **Study plan, practice prompts, reading papers:** at the end.

## Phrase bank (the best 20)
Adapt the details, keep the structure.

**Opening and clarifying**
1. "Before I draw anything, I'd like to pin down the scope — can I ask a few questions?"
2. "Roughly how many daily active users, and what's the peak traffic compared to average?"
3. "Is this read-heavy or write-heavy? What's the rough ratio?"
4. "How fresh does the data need to be — is a few seconds of delay acceptable?"
5. "What happens if we lose a write — is it an inconvenience or a serious problem?"

**Stating assumptions**
6. "I'll assume 50 million DAU and a 20-to-1 read-to-write ratio; tell me if that's off."
7. "Rounding generously: a day is about 10^5 seconds, so 100 million requests a day is about
   1,000 per second on average."

**Signposting**
8. "Here's my plan: requirements, quick numbers, API, data model, then the architecture and deep
   dives."
9. "Let me first draw the simplest version that works, and then we'll scale it."
10. "I see three hard parts here. I'd start with the ID generation — or is there one you'd rather
    go into?"

**Presenting a trade-off**
11. "There are two options here. Option A gives us X but costs Y; option B is the reverse."
12. "We gain availability, and the price is that a user might see a slightly stale value for a
    few seconds."
13. "I'd start with the simpler option and switch when we see this specific signal: ..."

**Pushing back politely**
14. "I could add a queue there, but I'm not sure it buys us much at this scale — what problem
    would you want it to solve?"
15. "I may be missing something — what's the requirement that pushes us toward strong
    consistency here?"

**Not knowing**
16. "I haven't used X in production, but from first principles I'd expect it to behave like this,
    because..."
17. "I don't remember the exact number; my rough estimate is in the order of a millisecond, and
    the design doesn't depend on the precise value."

**Follow-ups and summarizing**
18. "Good point — that breaks my earlier assumption. Let me adjust: ..."
19. "When you say consistent, do you mean the user sees their own write, or everyone sees the same
    value at once?"
20. "To recap: writes go through ..., reads come from ..., and the main trade-off we accepted is ..."

The full bank (39 sentences) also covers constraints (region, budget, compliance), parking scope,
"modular monolith first", measuring before committing, and "the biggest risk is ..., the first
thing I'd monitor is ...".

## The 20 trade-offs cheat sheet

| # | A vs B | Choose A when... | Choose B when... |
|---|---|---|---|
| 1 | SQL vs NoSQL | Relations, joins, transactions, ad-hoc queries, data fits a (sharded) primary | Huge scale with simple key access, flexible schema, very high write volume |
| 2 | Strong vs eventual consistency | Money, inventory, uniqueness, locks | Feeds, likes, analytics, caches; availability and latency matter more |
| 3 | Sync vs async replication | No acknowledged write may be lost | Lower write latency; can lose the last seconds on failover |
| 4 | Single-leader vs leaderless | Simpler reasoning, transactions, read-your-writes from leader | Always-writable, multi-DC tolerant, tunable quorums |
| 5 | Push (fan-out on write) vs pull (on read) | Few recipients, read latency critical | Huge fan-out (celebrities), many inactive readers |
| 6 | Normalize vs denormalize | Write-heavy, consistency-critical, storage-sensitive | Read-heavy, joins too slow at scale |
| 7 | Cache-aside vs write-through | General purpose; only cache what is read | Data read right after write; keep cache always warm |
| 8 | Sync call vs async queue | Caller needs the result now | Slow, retryable, spiky or fan-out work |
| 9 | Polling vs WebSockets/SSE | Updates infrequent, simplicity wins | Real-time, frequent server-to-client updates |
| 10 | B-tree vs LSM storage | Read-heavy, range scans, predictable reads | Write-heavy ingest, sequential writes |
| 11 | Vertical vs horizontal scaling | Early stage; one big machine suffices | Beyond one machine; need redundancy |
| 12 | Hash vs range partitioning | Even distribution, point lookups | Range scans by key (time, alphabetical), accepting hot-range risk |
| 13 | Optimistic vs pessimistic locking | Conflicts rare; avoid lock waits | Conflicts frequent; retries would be wasteful |
| 14 | Batch vs stream processing | Complete, accurate, cheaper; hours of latency OK | Results needed in seconds; continuous data |
| 15 | LWW vs vector clocks / CRDTs | Any recent value is fine; simplicity | Concurrent updates must all be kept / merged |
| 16 | Fail open vs fail closed | Availability of the main path matters most | Security or safety must not be bypassed |
| 17 | 2PC vs saga | Strict atomicity across a few resources you control, short txns | Long-running, cross-service workflows; compensation acceptable |
| 18 | REST vs gRPC | Public APIs, browsers, simplicity, cacheability | Internal service-to-service, low latency, streaming, strict contracts |
| 19 | Exact vs approximate | Billing, money, legal counts | Analytics, "views", distinct counts at scale (HLL, sampling) |
| 20 | Monolith vs microservices | Small team, early product, unclear boundaries | Many teams, clear domains, different scaling or release needs |

> **Say it like this:** "Neither is better in general. I'd pick A here because of this
> requirement, and I'd switch to B if that requirement changed."

## Top 15 "How would you...?" follow-ups
Most reduce to a few patterns: idempotency, caching and invalidation, sharding and hot keys, async
queues, replication and failover, observability.

1. **Avoid charging twice?** Client sends an **idempotency key** per attempt; server stores key +
   result in the same transaction as the charge (unique constraint) and replays the result on retry.
   E.g. a double-tap sends key `3f9a` twice: the second insert hits the constraint and gets the
   stored `201 o_90311` back, one charge. Pass a key to the provider too; reconcile daily. [02, 13]
2. **Hot key?** Find it via per-key metrics. Reads: short-TTL in-process cache, or replicate the key
   to several cache nodes. Writes: split into sub-keys (`likes:post42:0..15`), sum on read. DB/Kafka
   partitions: random suffix, merge downstream. [07, 08]
3. **Cache and DB consistent?** Write the DB, then **delete** the cache key; reads repopulate
   (cache-aside), e.g. dish price changes: `UPDATE menu_items`, then `DEL menu:r_77`. TTL heals
   missed invalidations; CDC-driven eviction for stricter needs. [08, 11]
4. **Exactly once?** Delivery is at-least-once, so make the effect idempotent: record message IDs
   (or upsert on a natural key) in the same transaction as the side effect. E.g. a consumer credits
   points for `msg-1043`, crashes before committing its offset, gets it again, finds `msg-1043` in
   its processed table, skips. Kafka transactions give exactly-once only for read-process-write
   between topics. [11]
5. **DB write and its event never disagree?** **Transactional outbox**: row + outbox record in one
   transaction; a relay or CDC publishes; consumers idempotent since the relay may publish twice. [11]
6. **Zero-downtime DB migration?** Expand-and-contract: replicate into the new store (CDC better than
   dual writes), backfill, verify (counts, checksums, **shadow reads**), move reads behind a flag,
   move writes (keep reverse replication for rollback), decommission. Every step reversible. [05, 11, 14]
7. **Counter at scale?** Never one row per event. Redis `INCR` (sharded if hot) flushed to the DB,
   or Kafka + stream aggregation every few seconds; sub-counters for very hot ones; show "1.2M";
   HyperLogLog for uniques. [08, 11]
8. **Survive a region outage?** Replicate to a second region (async: RPO of seconds, e.g. 2 s lag
   = up to 2 s of writes lost; sync: no loss, more latency); active-active or warm standby; DNS /
   global LB with health checks. Practise it; untested failover usually fails. [07, 14]
9. **Unique IDs across servers?** UUIDv4 (random, 128-bit, unordered) or Snowflake (64-bit,
   timestamp + machine + sequence, roughly time-sorted, fits a BIGINT). Central sequence only for
   dense increasing numbers, eased by range allocation. [10, 17]
10. **Paginate a changing list?** Cursor (keyset): `WHERE (created_at, id) < (:last_ts, :last_id)
    ORDER BY ... LIMIT 20`, return an opaque cursor; O(page) with an index, no skips or duplicates,
    unlike OFFSET (a new post on top shifts item 20 onto page 2 again: a duplicate). [02]
11. **Cache stampede on a hot expiry?** Request coalescing, a short recompute lock, refresh-ahead,
    serve stale while one request refreshes, jittered TTLs. [08]
12. **Only one worker per job?** Let the queue lease it (visibility timeout, one consumer per
    partition). For a lock: lease in etcd/ZooKeeper or Redis with TTL plus a **fencing token**
    (worker with token 33 pauses, the lease passes to 34, storage rejects 33's late write); still
    make the job idempotent since leases expire mid-work. [10, 11]
13. **Slow dependency?** Timeouts everywhere; retries with backoff + jitter (idempotent ops only,
    retry budget); circuit breaker; bulkheads; fallback response; queue it if the answer is not
    needed now. [13]
14. **p99 doubled?** What changed (deploy, config, traffic mix)? Traces for the slow hop; dependency
    latency, GC, CPU throttling, saturated pools/queues, hot shard, cache hit drop, noisy neighbours;
    one host or all? [14]
15. **Rate limit across servers?** Token bucket per key in Redis, atomic Lua at the gateway, rules
    from config, 429 + `Retry-After` (capacity 100, refill 10/s: call 101 of a burst waits ~0.1 s),
    fail open except security limits, local backstop. [12, 18]

The full list (25) also covers: slow DB checklist (pool, query plans, locks, saturation, lag),
celebrity fan-out (hybrid push/pull), zero-downtime deploys (rolling/blue-green, canaries,
backward-compatible schema steps), media storage (pre-signed upload, async processing, CDN), search
(CDC/outbox into an inverted index, hydrate from DB), millions of delayed jobs (`run_at` index,
`SKIP LOCKED` pollers, delayed queues), event schema changes (registry, additive only), WebSockets at
scale (connection gateways, user-to-gateway registry, pub/sub), dedup (TTL set or Bloom + exact
check), and backfills (keep raw immutable events, replay, verify, swap).

> **Say it like this:** "Brokers give me at-least-once, so I don't fight that — I make the effect
> idempotent by recording the message ID in the same transaction as the side effect."

## Glossary: the ~60 most important terms
The full glossary has about 140 terms; these ~60 are the ones you must explain in one breath. [chapter]

- **ACID** — Atomicity, Consistency (constraints hold), Isolation, Durability of transactions. [05]
- **Anti-entropy** — Background replica comparison and repair, often via Merkle trees. [18]
- **API gateway** — Front door handling routing, auth, rate limiting, TLS termination. [12]
- **At-least-once / at-most-once** — Never lost but maybe duplicated / never duplicated but maybe lost. [11]
- **Availability** — Fraction of time serving successfully; 99.9% about 8.8 h/yr down, 99.99% about 53 min. [14]
- **Backpressure** — Slow consumer signals producers to slow down instead of buffering without limit. [11]
- **Bloom filter** — Bits + hashes: "definitely not" or "maybe"; false positives, no false negatives. [06]
- **B+ tree** — Balanced wide on-disk tree; standard SQL index; point and range in O(log n) pages. [05]
- **Cache-aside** — Read cache; on miss read DB and fill cache. The most common pattern. [08]
- **Cache stampede / thundering herd** — Many requests or retries hit the backend at once; fix with coalescing, jitter, early refresh. [08, 13]
- **CAP** — During a partition choose consistency (linearizable) or availability; without one, both. [10]
- **CDC** — Streaming a DB's changes (from its replication log) to caches, indexes, Kafka. [11]
- **CDN** — Edge servers near users caching content to cut latency and origin load. [08]
- **Circuit breaker** — Opens after repeated failures to fail fast, half-opens to test, then closes. [13]
- **Consensus (Raft)** — Nodes agree on an ordered log despite failures; leader elected by majority, entry committed once a majority stores it. [10]
- **Consistent hashing** — Nodes and keys on a ring; adding/removing a node moves about 1/n of keys. [07]
- **Virtual nodes** — Many ring positions per physical node for even load and smoother rebalancing. [07]
- **Consumer group** — Kafka consumers sharing partitions, one consumer per partition: parallel, per-partition order. [11]
- **Kafka partition / offset** — Ordered append-only log (order only within it) / a message's position, committed as progress. [11]
- **CQRS** — Separate write and read models, the read side updated (usually async) from the write side. [13]
- **CRDT** — Data type whose replicas update independently and always merge deterministically. [10]
- **Normalization / denormalization** — Each fact once (joins to read) / duplicate data to skip joins, keep copies in sync. [05]
- **Eventual consistency** — With no new writes, replicas converge; meanwhile reads may be stale. [10]
- **Exactly-once** — Each *effect* once; in practice at-least-once + idempotent processing. [11]
- **Fan-out on write / on read** — Push into every follower's feed (fast reads) / assemble at read time (cheap writes). [19]
- **Fencing token** — Increasing number issued with a lock; resource rejects older tokens from paused ex-holders. [10]
- **Gossip** — Periodic state swaps with random peers; spreads in about O(log n) rounds, no master. [18]
- **Hinted handoff** — A stand-in stores a down replica's writes with a hint and hands them over later. [18]
- **Hot key / hot partition** — One key or shard gets far more traffic and bottlenecks a cluster with spare capacity. [07]
- **HyperLogLog** — Distinct-count estimate with about 1% error in a few KB. [08]
- **Idempotency / idempotency key** — Twice has the same effect as once / client ID that lets the server ignore retries. [02]
- **Inverted index** — Term to list of documents; the core of full-text search. [09]
- **Isolation levels** — Read Uncommitted, Read Committed, Repeatable Read / Snapshot, Serializable; weaker is faster with more anomalies. [05]
- **Write skew** — Two txns read overlapping data, write different rows, jointly break a constraint; allowed by snapshot isolation. [05]
- **Optimistic / pessimistic locking** — Write only if version unchanged, retry on conflict / lock first (`FOR UPDATE`). [05]
- **Leader-follower / multi-leader / leaderless** — One writer streams to followers / several writers need conflict resolution / any replica writes, quorums. [07, 18]
- **Replication lag** — How far a follower trails the leader; the source of stale reads. [07]
- **Linearizability** — Each operation appears atomic at one instant between start and end, in real-time order. [10]
- **Read-your-writes** — A user always sees their own recent writes. [10]
- **Load balancer L4 / L7** — Balances TCP/UDP connections / understands HTTP, routes by path, header, cookie. [12]
- **LSM tree / SSTable / compaction** — Memtable flushed to sorted immutable files, merged in the background; fast writes, reads may check several files. [06]
- **LWW** — Highest timestamp wins; simple, silently drops concurrent writes. [18]
- **Vector clock** — Per-node counters on a version telling "descends from" vs "concurrent". [10, 18]
- **Merkle tree** — Hash tree; replicas find differences by comparing a few hashes. [18]
- **Object storage** — Durable, cheap-per-GB blobs by key over HTTP (S3-like). [09]
- **OLTP vs OLAP** — Many small transactional ops vs large analytical scans (columnar, warehouse). [09]
- **Outbox pattern** — Business change + event row in one transaction; a relay publishes. [11]
- **p99 / tail latency** — 99% of requests are faster; the slow tail dominates pages making many calls. [04]
- **Sharding** — Splitting data across machines by key. [07]
- **Quorum** — Minimum participants for success; majorities always overlap. [10, 18]
- **RPO / RTO** — How much data (in time) you may lose / how long you may be down. [14]
- **Saga** — Local transactions each with a compensating action; 2PC instead blocks if the coordinator fails at the wrong time. [13]
- **SLI / SLO / SLA** — Measured indicator / internal target / contractual promise with penalties. [14]
- **Snowflake ID** — 64-bit timestamp + machine + sequence; unique without coordination, roughly time-ordered. [10]
- **Split brain** — Two nodes both act as leader; prevented by majority quorums and fencing. [10]
- **Stateless service** — Keeps no client state in memory, so instances are interchangeable. [03]
- **Token bucket** — Tokens refill at a rate up to capacity; each request spends one; allows bursts. [12]
- **Tombstone** — Versioned delete marker that propagates and beats older values. [06, 18]
- **WAL** — Log appended before applying changes, for crash recovery and replication. [05]
- **Write-through / write-back / write-around** — Cache and DB together / cache then flush later (risk of loss) / DB only. [08]
- **ZooKeeper / etcd** — Consensus-backed stores for config, leader election, locks, discovery. [10]

## Four-week study plan and practice prompts
**How it works** · About 1–1.5 h on weekdays, 2–3 h on weekend days; keep the order.

| Week | Chapters | Representative "do" tasks |
|---|---|---|
| 1 Foundations | 01–07 | Trace a URL from DNS to response; todo API with pagination and idempotency; 5 estimations; isolation levels and their anomalies; LSM paths from memory; consistent hashing and failover aloud |
| 2 Toolkit | 08–15 | 4 cache problems and fixes; inverted index for 3 docs; CAP, Raft, Snowflake aloud; outbox + idempotent consumer; compare rate limit algorithms; "book trip" saga; SLI/SLO/SLA, OAuth |
| 3 HLD | 16–21 | Leaderboard with a timer; URL shortener from blank page in 45 min; Dynamo table from memory; timed Instagram, Dropbox, crawler; two mock designs, record yourself |
| 4 LLD + polish | 22–27 | Fix a SOLID violation; Strategy/Observer/Factory; parking lot timed; one LLD case end to end, then thread-safe; all 25 follow-ups aloud; one HLD + one LLD mock, revisit 3 gaps |

Afterwards, two or three timed problems a week beat re-reading.
**20 HLD prompts:** URL shortener, Pastebin, distributed rate limiter, distributed KV store, news
feed, chat, notifications, video streaming, file sync, typeahead, web crawler, ad serving / click
tracking, ride sharing, proximity search, hotel/ticket booking, payments, job scheduler, metrics
system, collaborative editing, top-K / trending.
**15 LLD prompts:** parking lot, elevator, vending machine, library, snake and ladder, tic-tac-toe /
chess, thread-safe LRU cache, in-memory rate limiter, Splitwise, movie ticket booking, hotel
reservation, logger, in-process pub/sub, food delivery order state machine, ATM.

## Reading papers and engineering blogs
**How it works** · Papers in three passes: (1) 10 min on title, abstract, intro, headings,
conclusion, figures: what problem, what workload, core idea; (2) 1 h on design sections, redraw the
architecture, note every assumption; (3) optional details and evaluation. Summarize each in five
lines: **problem, assumptions, core idea, trade-off, legacy**. Blogs: find the "why" (what broke),
the numbers before and after, redraw before/after diagrams, separate principle from local detail,
and ask what a simpler design would have done.

| Paper (year) | Takeaway |
|---|---|
| GFS (2003) | One metadata master, many chunkservers, large chunks, x3 replicas; design for constant failure and appends |
| MapReduce (2004) | Map + group-by-key + reduce; framework handles distribution, retries, data locality |
| Bigtable (2006) | Sparse sorted (row, column, timestamp) map in tablets on LSM-style storage; the wide-column template |
| Dynamo (2007) | Always-writable leaderless KV: consistent hashing, quorums, hinted handoff, vector clocks, Merkle, gossip |
| Kafka (2011) | Messaging as a partitioned on-disk log read by offset; sequential IO, replay for free |
| Raft (2014) | Understandable consensus: terms, leader election, log replication, commit on majority |

Later: Chubby (2006, leases), ZooKeeper (2010), Spanner (2012, bounded clock uncertainty), and
"Time, Clocks, and the Ordering of Events" (1978, happened-before).

## In one minute
- Every glossary term should come out in one or two sentences, with its trade-off.
- Have sentences ready for clarifying, assuming, signposting, trade-offs, pushing back, not
  knowing and summarizing; say them aloud beforehand.
- Follow-ups reduce to idempotency, caching/invalidation, sharding/hot keys, queues, replication
  and failover, observability.
- Every trade-off answer: two options, when each wins, what you pay, what would flip it.
- Practise timed: two or three HLD/LLD prompts a week.
- Read papers in passes; summarize as problem, assumptions, idea, trade-off, legacy.
- Confidence comes from structure: requirements, numbers, simple design, deep dives, failures,
  trade-offs, in that order, every time.

## Go deeper
Full chapter 27: sections 27.2 (full glossary), 27.3 (full phrase bank), 27.4 (all 25 follow-ups)
