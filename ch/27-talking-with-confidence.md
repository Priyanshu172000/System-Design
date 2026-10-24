# 27 — Talking with confidence: glossary, phrases and a study plan

This last chapter is a toolkit for the conversation itself. It collects the vocabulary of the
whole course into one glossary, gives you ready sentences for the awkward moments (clarifying,
pushing back, not knowing), answers the follow-up questions that come up again and again, and
lays out a four-week plan to make it all stick. Use it as a reference before an interview or
design review, and as a checklist of what you should be able to explain in your own words.

## 27.1 How to use this chapter

- **Glossary (27.2):** read it once end to end. Any term you cannot explain in one breath,
  go back to the chapter listed next to it.
- **Phrase bank (27.3):** read the sentences out loud. Confidence in conversation is partly
  just having said the sentence before.
- **"How would you…?" (27.4):** cover the answer, answer aloud, then compare.
- **Trade-offs cheat sheet (27.5):** the 20 decisions that cover most design discussions.
- **Study plan and practice prompts (27.6):** four weeks, then keep practicing.
- **Papers and blogs (27.7):** how to read them without drowning.

## 27.2 Glossary A–Z

Each term has a short definition and, in brackets, the chapter where it is covered in depth.

### A

- **ACID** — The four guarantees of a database transaction: Atomicity (all or nothing),
  Consistency (moves the DB between valid states, respecting constraints), Isolation
  (concurrent transactions do not see each other's partial work, to a defined degree),
  Durability (committed data survives crashes). [05]
- **Anti-entropy** — A background process that compares replicas and repairs differences,
  often using Merkle trees. [18]
- **API gateway** — A server in front of your services that handles cross-cutting concerns:
  routing, authentication, rate limiting, TLS termination. [12]
- **At-least-once delivery** — A message is delivered one or more times; never lost but may be
  duplicated, so consumers must be idempotent. [11]
- **At-most-once delivery** — A message is delivered zero or one times; never duplicated but may
  be lost. [11]
- **Availability** — The fraction of time a system serves requests successfully, often stated
  in "nines": 99.9% ≈ 8.8 hours of downtime/year, 99.99% ≈ 53 minutes/year. [14]
- **Availability zone (AZ)** — An isolated data center (or group) within a cloud region, with
  separate power and networking, so a failure in one rarely affects another. [14]
- **Autoscaling** — Automatically adding or removing instances based on a metric such as CPU or
  queue length. [03]

### B

- **Backpressure** — A way for a slow consumer to signal producers to slow down (bounded
  queues, blocking, rejecting), instead of buffering without limit until memory runs out. [11]
- **BASE** — "Basically Available, Soft state, Eventually consistent": the loose philosophy of
  many NoSQL systems that trade strict ACID guarantees for availability and scale. [06]
- **Batch processing** — Processing a large, bounded set of data in one job (e.g. nightly),
  optimizing throughput over latency. [09]
- **Bloom filter** — A compact bit array plus several hash functions that answers "definitely
  not present" or "maybe present"; false positives possible, false negatives impossible. [06]
- **B-tree / B+ tree** — A balanced, wide tree kept on disk; the standard index structure of
  relational databases. Supports point lookups and range scans in O(log n) page reads. [05]
- **Bulkhead** — Isolating resources (thread pools, connection pools) per dependency so one
  failing dependency cannot exhaust everything. [13]

### C

- **Cache-aside** — The application reads the cache first; on a miss it reads the DB and fills
  the cache. The most common caching pattern. [08]
- **Cache stampede** — Many requests miss the cache for the same key at once (e.g. right after
  it expires) and all hit the DB. Fixed with request coalescing, locks, or early refresh. [08]
- **CAP theorem** — When a network partition happens, a distributed system must choose between
  consistency (every read sees the latest write, linearizably) and availability (every request
  to a non-failed node gets a non-error response). Without a partition, you can have both. [10]
- **CDC (change data capture)** — Streaming a database's changes (usually by reading its
  replication log) to other systems such as caches, search indexes or Kafka. [11]
- **CDN (content delivery network)** — A network of edge servers near users that cache content
  (images, video, static files) to cut latency and origin load. [08]
- **Circuit breaker** — A wrapper around a remote call that "opens" after repeated failures and
  fails fast for a while instead of waiting on a broken dependency, then tests with a few
  requests ("half-open") before closing again. [13]
- **Compaction** — In LSM-based stores, merging SSTables to discard overwritten and deleted
  data and keep reads fast. [06]
- **Connection pooling** — Reusing a fixed set of open connections (e.g. to a DB) instead of
  opening one per request, which is slow and can exhaust the server. [05]
- **Consensus** — A protocol by which a group of nodes agrees on a value (or an ordered log)
  despite failures, e.g. Raft, Paxos. Needs a majority to make progress. [10]
- **Consistent hashing** — Placing nodes and keys on a hash ring so adding or removing a node
  moves only about 1/n of the keys. [07]
- **Consumer group** — In Kafka, a set of consumers sharing a topic's partitions; each partition
  is read by exactly one consumer in the group, which gives parallelism with per-partition
  order. [11]
- **CQRS (command query responsibility segregation)** — Using separate models (often separate
  stores) for writes and reads, with the read model updated from the write side,
  usually asynchronously. [13]
- **CRDT (conflict-free replicated data type)** — A data type (counter, set, map) whose
  replicas can be updated independently and always merged deterministically without
  conflicts. [10]

### D

- **Dead letter queue (DLQ)** — Where messages go after repeatedly failing processing, so they
  do not block the queue and can be inspected later. [11]
- **Denormalization** — Deliberately duplicating data (e.g. storing the author name on each post)
  to avoid joins and speed up reads, at the cost of keeping copies in sync. [05]
- **Dirty read** — Reading another transaction's uncommitted changes. Prevented at Read
  Committed and above. [05]
- **DNS** — The system that maps names (api.example.com) to IP addresses; also used for coarse
  load balancing and failover. [01]
- **Durability** — Once a write is acknowledged, it survives crashes (persisted to disk and/or
  replicated). [05, 14]

### E

- **Event sourcing** — Storing the sequence of events (facts that happened) as the source of
  truth and deriving current state by replaying them. [11]
- **Eventual consistency** — If no new writes happen, all replicas eventually converge to the
  same value; reads in the meantime may be stale. [10]
- **Exactly-once** — Each message's *effect* happens exactly once. In practice achieved as
  at-least-once delivery + idempotent processing (or transactional processing within one
  system, such as Kafka transactions). [11]
- **Exponential backoff** — Waiting increasingly longer between retries (e.g. 100 ms, 200 ms,
  400 ms…), usually with random jitter, so retries do not hammer a struggling service. [13]

### F

- **Failover** — Switching traffic from a failed component to a standby (e.g. promoting a
  replica to primary). [07, 14]
- **Fan-out on write (push)** — Doing the work at write time, e.g. inserting a new post into
  every follower's feed. Fast reads, expensive writes for users with many followers. [19]
- **Fan-out on read (pull)** — Doing the work at read time, e.g. building a feed by fetching
  recent posts of everyone you follow. Cheap writes, slower reads. [19]
- **Fencing token** — A monotonically increasing number issued with a lock; the protected
  resource rejects requests carrying an older token, so a paused former lock-holder cannot
  corrupt data. [10]
- **Follower (replica)** — A node that copies the leader's data and can serve reads. [07]

### G

- **Geohash** — Encoding a latitude/longitude into a string where shared prefixes mean nearby
  areas, enabling proximity search with prefix queries. [09]
- **Gossip protocol** — Nodes periodically exchange state with random peers, so information
  (membership, liveness) spreads to all nodes in about O(log n) rounds without a master. [18]

### H

- **Health check** — A periodic probe (e.g. `GET /health`) a load balancer or orchestrator uses
  to decide whether an instance should receive traffic. [03, 12]
- **Heartbeat** — A periodic "I'm alive" message; missing heartbeats suggest a failure. [10]
- **Hinted handoff** — When a replica is down, another node stores its writes with a hint and
  hands them over when it recovers. [18]
- **Horizontal scaling (scale out)** — Adding more machines. Opposite: vertical scaling
  (bigger machine). [03]
- **Hot key / hot partition** — A key or shard receiving far more traffic than others, so it
  becomes a bottleneck even though the cluster overall has capacity. [07]
- **HyperLogLog** — A probabilistic structure that estimates the number of distinct items with
  ~1% error using a few KB of memory. [08]

### I

- **Idempotency** — An operation is idempotent if doing it twice has the same effect as doing it
  once (e.g. `PUT`, "set balance to 50"; not "add 50"). [02]
- **Idempotency key** — A unique client-generated ID sent with a request so the server can detect
  and ignore retries of the same operation. [02]
- **Index** — An extra data structure (usually a B+ tree) that lets the DB find rows without a
  full scan; speeds reads, slows writes, costs space. [05]
- **Inverted index** — A map from each term to the list of documents containing it; the core of
  full-text search. [09]
- **ISR (in-sync replicas)** — In Kafka, the set of replicas of a partition that are caught up
  with the leader. With `acks=all`, a write is acknowledged once all current ISR members have
  it; `min.insync.replicas` sets how small the ISR may get before writes are refused. [11]
- **Isolation level** — How much concurrent transactions can see of each other: Read
  Uncommitted, Read Committed, Repeatable Read / Snapshot Isolation, Serializable. Weaker =
  faster but more anomalies. [05]

### J

- **Jitter** — Randomness added to retry delays or schedules so many clients do not act at the
  same instant. [13]

### K

- **Kafka offset** — The position of a message within a partition; consumers commit offsets to
  record progress. [11]
- **Kafka partition** — An ordered, append-only log; a topic is split into partitions for
  parallelism. Ordering is guaranteed only within a partition. [11]
- **Key-value store** — A store that maps keys to opaque values with get/put; simple, fast and
  easy to partition. [06, 18]

### L

- **Latency** — Time to complete one request. Usually reported as percentiles (p50, p99). [04]
- **Leader election** — Choosing one node to act as leader, typically via consensus or a lease
  in a coordination service, so only one node performs a given role at a time. [10]
- **Leader-follower replication** — One node (leader/primary) accepts writes and streams changes
  to followers; also called primary-replica or single-leader. [07]
- **Leaderless replication** — Any replica accepts writes; clients or coordinators use quorums
  (Dynamo-style). [07, 18]
- **Linearizability** — The strongest single-object consistency: every operation appears to take
  effect atomically at one instant between its start and end, consistent with real time. [10]
- **Load balancer** — Distributes requests across instances. L4 balances TCP/UDP connections;
  L7 understands HTTP and can route by path, header or cookie. [12]
- **Load shedding** — Deliberately rejecting some requests (e.g. low-priority ones) when
  overloaded, so the rest succeed. [13]
- **Long polling** — The client sends a request the server holds open until data is available or
  a timeout, then the client immediately re-requests. [02]
- **LSM tree (log-structured merge tree)** — A storage engine that buffers writes in memory
  (memtable), flushes them as sorted immutable files (SSTables), and merges them in the
  background. Fast writes; reads may check several files. [06]
- **LWW (last-write-wins)** — Conflict resolution that keeps the value with the highest
  timestamp; simple but silently drops concurrent writes. [18]

### M

- **MapReduce** — A batch model: a map step emits key-value pairs, the framework groups by key
  (shuffle), a reduce step aggregates each group. [09]
- **Merkle tree** — A tree of hashes where each parent hashes its children; lets two replicas
  find differing data by comparing a few hashes. [18]
- **Message queue** — A buffer between producers and consumers so they are decoupled in time
  and rate. [11]
- **Microservices** — An architecture of small, independently deployable services, each owning
  its data. Gains team autonomy and independent scaling; pays in network calls, distributed
  data and operational complexity. [13]
- **Multi-leader replication** — Several nodes accept writes (often one per region) and
  replicate to each other; requires conflict resolution. [07]
- **MVCC (multi-version concurrency control)** — The DB keeps several versions of a row so
  readers see a consistent snapshot without blocking writers. [05]

### N

- **Non-repeatable read** — Reading the same row twice in one transaction and getting different
  values because another transaction committed in between. Prevented at Repeatable Read /
  Snapshot Isolation. [05]
- **Normalization** — Organizing tables so each fact is stored once, avoiding update anomalies;
  requires joins to read combined data. [05]
- **NoSQL** — An umbrella for non-relational stores: key-value, document, wide-column, graph.
  [06]

### O

- **Object storage** — A store for large immutable blobs addressed by key (S3-like), very
  durable and cheap per GB, accessed over HTTP. [09]
- **OLAP vs OLTP** — OLTP: many small transactional reads/writes (the app's DB). OLAP: large
  analytical scans and aggregations over history (warehouse, columnar stores). [09]
- **Optimistic locking** — Read a version, write only if the version is unchanged
  (`UPDATE ... WHERE version = 7`); retry on conflict. Good when conflicts are rare. [05]
- **Outbox pattern** — Writing the business change and an "event to publish" row in the same DB
  transaction; a relay then publishes the event. Avoids losing events or publishing phantom
  ones. [11]

### P

- **p99 (99th percentile)** — The latency that 99% of requests are faster than. Tail latency
  matters because a page that makes many calls almost always hits some slow ones. [04]
- **Partition (network)** — A failure where nodes are alive but cannot communicate. The "P" in
  CAP. [10]
- **Partitioning / sharding** — Splitting data across machines by key so each holds a subset.
  [07]
- **Pessimistic locking** — Taking a lock before reading/writing (`SELECT ... FOR UPDATE`) so
  others wait. Good when conflicts are frequent. [05]
- **Phantom read** — A query re-run in the same transaction returns a different *set* of rows
  because another transaction inserted or deleted matching rows. [05]
- **Pub/sub** — Publishers send messages to a topic; every subscriber (or subscriber group) gets
  a copy. [11]

### Q

- **Quorum** — The minimum number of nodes that must participate for an operation to succeed.
  Majority quorums (more than half) guarantee any two quorums overlap. [10, 18]

### R

- **Raft** — A consensus algorithm designed for understandability: elect a leader with majority
  votes, replicate a log, commit an entry once a majority stores it. [10]
- **Rate limiting** — Capping how many requests a client may make per unit of time. [12, 18]
- **Read repair** — During a read, fixing replicas that returned stale data. [18]
- **Read replica** — A follower used to serve read traffic and offload the leader. [07]
- **Read-your-writes** — A guarantee that a user always sees their own recent writes, even if
  others may see them later. [10]
- **Rebalancing** — Moving partitions between nodes when nodes are added or removed. [07]
- **Replication lag** — How far behind the leader a follower is; the source of stale reads. [07]
- **Reverse proxy** — A server that receives client requests on behalf of backend servers
  (TLS termination, caching, routing). [03, 12]
- **RPO / RTO** — Recovery Point Objective: how much data (in time) you can afford to lose.
  Recovery Time Objective: how long you can afford to be down. [14]

### S

- **Saga** — A long-running business transaction split into local transactions, each with a
  compensating action that undoes it if a later step fails. [13]
- **Serializability** — The strongest isolation level: concurrent transactions produce a result
  equivalent to some serial (one-at-a-time) order. [05]
- **Service discovery** — How a service finds the current addresses of another service's
  instances (registry, DNS, platform such as Kubernetes services). [03, 13]
- **Sidecar** — A helper process deployed next to each service instance (e.g. a proxy) that
  handles networking, retries, TLS or telemetry. [13]
- **SLA / SLO / SLI** — SLI: a measured indicator (e.g. % of requests under 200 ms). SLO: the
  internal target for it (99.9%). SLA: a contractual promise to customers, with penalties. [14]
- **Snapshot isolation** — Each transaction reads from a consistent snapshot taken at its
  start; prevents dirty and non-repeatable reads but allows write skew. [05]
- **Snowflake ID** — A 64-bit ID made of a timestamp, a machine ID and a per-millisecond
  sequence; unique without coordination and roughly time-ordered. [10]
- **Split brain** — Two nodes both believe they are the leader (e.g. after a partition) and
  both accept writes, causing divergence. Prevented by majority quorums and fencing. [10]
- **SSTable (sorted string table)** — An immutable on-disk file of key-value pairs sorted by
  key, used by LSM trees. [06]
- **Stateless service** — A service that keeps no client state between requests in its own
  memory, so any instance can handle any request and instances are interchangeable. [03]
- **Sticky session** — Routing all requests from one client to the same server instance. [12]
- **Stream processing** — Processing unbounded event streams continuously with low latency
  (windows, aggregations, joins). [09]
- **Strong consistency** — Loosely, every read sees the latest committed write; precisely,
  usually means linearizability. [10]

### T

- **Throughput** — Work done per unit of time (requests/sec, MB/sec). [04]
- **Thundering herd** — Many clients waking up or retrying at the same moment and overloading a
  resource (e.g. after an outage or a cache expiry). Fixed with jitter, coalescing, staggered
  expiry. [08, 13]
- **Timeout** — A maximum wait for a remote call; without one, a slow dependency ties up your
  threads indefinitely. [13]
- **Token bucket** — Rate limiting algorithm: tokens refill at a fixed rate up to a capacity;
  each request spends a token. Allows bursts up to capacity. [12]
- **Tombstone** — A marker recording that a key was deleted, so the delete propagates to
  replicas and wins over older values. [06, 18]
- **Two-phase commit (2PC)** — An atomic commit protocol: a coordinator asks all participants to
  prepare, then tells all to commit or abort. Blocks if the coordinator fails at the wrong time.
  [13]
- **TTL (time to live)** — How long a cached or stored item remains valid before it expires.
  [08]

### V

- **Vector clock** — A list of per-node counters attached to a version, used to tell whether one
  version descends from another or they are concurrent. [10, 18]
- **Vertical scaling (scale up)** — Using a bigger machine. Simple, but has a ceiling and a
  single point of failure. [03]
- **Virtual nodes (vnodes)** — Many ring positions per physical node in consistent hashing, for
  even load and smoother rebalancing. [07]

### W

- **WAL (write-ahead log)** — A log the DB appends changes to before applying them, so it can
  recover after a crash. Also used for replication. [05]
- **WebSocket** — A persistent, full-duplex connection over a single TCP connection, started by
  an HTTP upgrade; used for chat, live updates. [02]
- **Write amplification** — Bytes actually written to storage per byte the application writes
  (e.g. due to compaction or page rewrites). High values wear SSDs and cost throughput. [06]
- **Write-through / write-back / write-around** — Cache write policies: write to cache and DB
  together; write to cache and flush to DB later (fast, risk of loss); write only to DB and let
  reads populate the cache. [08]
- **Write skew** — Two transactions each read overlapping data, make decisions based on it, and
  write *different* rows, together violating a constraint (e.g. both on-call doctors go off
  duty). Allowed by snapshot isolation; prevented by serializable. [05]

### Z

- **Zero-downtime deployment** — Releasing without an outage, via rolling updates, blue-green
  (switch traffic between two full environments) or canary (send a small % to the new version
  first). [14]
- **ZooKeeper / etcd** — Consensus-backed coordination stores used for configuration, leader
  election, locks and service discovery. [10]

### A few more that come up constantly

- **Backfill** — Running a job to compute or copy data for the past (e.g. populating a new
  column or index). [09]
- **Blue-green deployment** — Two identical environments; deploy to the idle one, then switch
  traffic; switch back to roll back. [14]
- **Canary release** — Shipping a change to a small fraction of traffic first and watching
  metrics before rolling out further. [14]
- **Coalescing (request collapsing)** — Merging concurrent identical requests so only one goes
  to the backend and the others share the result. [08]
- **Cold start** — Slow first requests because caches are empty, JIT has not warmed up, or a
  serverless instance must start. [08, 03]
- **Data locality** — Placing computation near the data it reads (or data near the user) to
  avoid network transfer. [09]
- **Idempotent consumer** — A message handler that records processed message IDs (or uses
  upserts) so duplicates have no extra effect. [11]
- **Observability** — The ability to understand a system from its outputs: metrics, logs,
  traces. [14]
- **PACELC** — Extension of CAP: if Partition, choose Availability or Consistency; Else,
  choose Latency or Consistency. Captures that consistency costs latency even without
  failures. [10]
- **Poison message** — A message that always fails processing; without a DLQ it blocks or
  loops forever. [11]
- **Schema registry** — A service storing versions of event schemas and checking new versions
  are compatible, so producers cannot break consumers. [11]
- **Single point of failure (SPOF)** — A component whose failure takes the whole system down.
  [14]
- **Tail latency** — The slow end of the latency distribution (p99, p99.9). [04]
- **Write-ahead / commit log** — See WAL; in Kafka the log *is* the data. [11]

## 27.3 Phrase bank

Ready sentences, grouped by situation. Adapt the details; keep the structure.

### Opening and clarifying

1. "Before I draw anything, I'd like to pin down the scope — can I ask a few questions?"
2. "Who are the users and what are the two or three things they must be able to do?"
3. "Roughly how many daily active users, and what's the peak traffic compared to average?"
4. "Is this read-heavy or write-heavy? What's the rough ratio?"
5. "How fresh does the data need to be — is a few seconds of delay acceptable?"
6. "What happens if we lose a write — is it an inconvenience or a serious problem?"
7. "Is this one region or global from day one?"
8. "Are there any constraints I should know about — existing systems, budget, compliance?"

### Stating assumptions

9. "I'll assume 50 million DAU and a 20-to-1 read-to-write ratio; tell me if that's off."
10. "Let me assume eventual consistency is fine for the feed but not for payments."
11. "I'll assume an average object of about 1 KB — it only changes the storage estimate, not the
    design."
12. "For now I'll treat search as out of scope and come back to it if we have time."
13. "Rounding generously: a day is about 10^5 seconds, so 100 million requests a day is about
    1,000 per second on average."

### Signposting and structure

14. "Here's my plan: requirements, quick numbers, API, data model, then the architecture and
    deep dives."
15. "Let me first draw the simplest version that works, and then we'll scale it."
16. "I'll walk one request through this diagram, starting with the write path."
17. "I see three hard parts here. I'd start with the ID generation — or is there one you'd
    rather go into?"

### Presenting a trade-off

18. "There are two options here. Option A gives us X but costs Y; option B is the reverse."
19. "Given that reads outnumber writes a hundred to one, I'd pay the cost at write time."
20. "We gain availability, and the price is that a user might see a slightly stale value for a
    few seconds."
21. "I'd start with the simpler option and switch when we see this specific signal: …"
22. "This is a classic latency-versus-consistency choice. For this feature, I'd lean toward
    latency because…"

### Pushing back politely

23. "I could add a queue there, but I'm not sure it buys us much at this scale — what problem
    would you want it to solve?"
24. "That would work. My concern is the operational cost; could we get most of the benefit
    with a cache first?"
25. "I see the appeal of microservices here. I'd start with a modular monolith and split out the
    part that has a different scaling profile."
26. "I may be missing something — what's the requirement that pushes us toward strong
    consistency here?"
27. "If we do that, the cost is X. Are we comfortable with that, or is there a constraint I
    don't know about?"

### Admitting you don't know

28. "I haven't used X in production, but from first principles I'd expect it to behave like
    this, because…"
29. "I don't remember the exact number; my rough estimate is in the order of a millisecond,
    and the design doesn't depend on the precise value."
30. "I'm not sure how that product implements it internally. Here's how I would build it."
31. "Good question — I haven't thought about that case. Let me reason through it."
32. "I'd want to measure that before committing. My hypothesis is…, and the metric that would
    confirm it is…"

### Handling follow-ups and changing your mind

33. "Good point — that breaks my earlier assumption. Let me adjust: …"
34. "If traffic goes 10x, the stateless tier just scales out; the first real limit is the
    database write path, so I'd shard by …"
35. "If that node dies, three questions: how we detect it, who takes over, and what we might
    lose."
36. "When you say consistent, do you mean the user sees their own write, or everyone sees the
    same value at once?"

### Summarizing

37. "To recap: writes go through …, reads come from …, and the main trade-off we accepted is …"
38. "The biggest risk in this design is …, and the first thing I'd monitor is …"
39. "With more time I'd look at multi-region failover and cost optimization of cold data."

> **Say it like this:** "I haven't run that in production, but from first principles: it's an
> LSM-based store, so I'd expect fast writes and that reads get slower as SSTables pile up
> between compactions — which is what I'd watch."

## 27.4 "How would you…?" — 25 common follow-ups

Each answer is the core you should be able to say in about 30–60 seconds.

**1. How do you avoid charging a customer twice?**
Client generates an **idempotency key** per payment attempt and sends it on every retry. The
server stores the key with the result in the same transaction as the charge (unique
constraint); a retry with the same key returns the stored result instead of charging again.
Calls to the payment provider also carry an idempotency key. Reconcile against the provider's
records daily. [02, 13]

**2. How do you handle a hot key?**
First, identify it (per-key metrics). For reads: cache it closer — an in-process cache with a
short TTL on every app server — and/or replicate the key to several cache nodes and pick one at
random. For writes (e.g. a counter): split the key into N sub-keys (`likes:post42:0..15`),
write to a random one, sum on read. For partitions in a DB or Kafka, add a random suffix to
spread load and merge downstream. [07, 08]

**3. The database is slow. What do you check?**
Is it the DB or the path to it (connection pool exhausted, network)? Then: slow query log and
query plans (missing index, full scans), lock waits and long transactions, CPU/IO/memory
saturation, cache hit ratio, replication lag on replicas, a recent deploy or traffic change,
one hot table/row. Fix the specific cause: add an index, rewrite the query, add a cache, move
reads to replicas, kill long transactions. [05, 14]

**4. How do you migrate to a new database with zero downtime?**
Expand-and-contract in phases: (1) set up the new store and replicate changes into it (dual
writes or, better, CDC from the old DB); (2) backfill historical data; (3) verify (compare
counts and checksums, **shadow reads** comparing results); (4) switch reads gradually behind a
feature flag; (5) switch writes so the new store becomes the source of truth (keep reverse
replication for rollback); (6) decommission the old one. Every step is reversible. [05, 11, 14]

**5. How do you build a counter (likes, views) at large scale?**
Do not update one row per event. Options: Redis `INCR` (sharded if hot) with periodic flush to
the DB; or send events to Kafka and aggregate in a stream processor, writing totals every few
seconds. For a very hot counter, shard it into sub-counters and sum. Show approximate values
("1.2M") to users. For unique counts, use HyperLogLog. [08, 11]

**6. How do you keep a cache and the database consistent?**
Usually: write the DB, then **delete** (not update) the cache key; reads repopulate it
(cache-aside). Set a TTL so any missed invalidation heals. For stricter needs, drive
invalidation from CDC so every committed change evicts the key. Accept a small staleness window
or route read-your-writes to the DB. [08, 11]

**7. How do you process each message exactly once?**
Brokers give at-least-once delivery, so make the effect idempotent: store processed message IDs
(or use a natural unique key / upsert) in the same transaction as the side effect. Within Kafka
alone, transactions can give exactly-once for read-process-write between topics. [11]

**8. How do you survive a whole region going down?**
Replicate data to a second region (async for latency, accepting an RPO of seconds; or sync for
zero loss at a latency cost). Run services in both regions (active-active) or keep a warm
standby (active-passive). Use DNS / global load balancing with health checks to move traffic.
Practise the failover regularly; untested failover usually fails. [07, 14]

**9. How do you handle a celebrity with 50 million followers in a news feed?**
Hybrid fan-out: push posts of normal users into followers' feeds at write time; for accounts
above a follower threshold, don't fan out — at read time, merge their recent posts into the
reader's precomputed feed. [19]

**10. How do you generate unique IDs across many servers?**
UUIDv4 (random, 128-bit, no coordination, not ordered) or a Snowflake-style 64-bit ID
(timestamp + machine ID + sequence: unique, roughly time-sortable, fits a BIGINT). Use a
central sequence only if you need dense, strictly increasing numbers and can live with the
bottleneck (mitigated by range allocation). [10, 17]

**11. How do you paginate a large list that keeps changing?**
Cursor (keyset) pagination: `WHERE (created_at, id) < (:last_ts, :last_id) ORDER BY created_at
DESC, id DESC LIMIT 20`, and return an opaque cursor. Unlike OFFSET, it is O(page) with an
index and does not skip or duplicate items when new ones are inserted. [02]

**12. How do you deploy without downtime?**
Stateless services: rolling update or blue-green behind a load balancer, with readiness probes
and connection draining. Use canaries with automatic rollback on error-rate increase. For
schema changes, make them backward compatible (add column → deploy code that uses it → remove
old later), never a breaking change in the same release. [03, 14]

**13. p99 latency suddenly doubled. How do you investigate?**
Check what changed (deploy, config, traffic mix). Look at traces for the slow requests to find
which hop grew. Check dependencies' latency, GC pauses, CPU throttling, saturation (queue
lengths, thread/connection pools), a hot shard, cache hit rate drop, noisy neighbours. Compare
across instances: one bad host or all? [14]

**14. How do you prevent a cache stampede when a hot key expires?**
Request coalescing (one in-flight fetch per key; others wait for it), a short lock around the
recompute, refresh-ahead (recompute before expiry), serving stale data while one request
refreshes, and jittered TTLs so many keys don't expire together. [08]

**15. How do you make sure only one worker processes a job?**
Let the queue do it: a message is leased to one consumer at a time (visibility timeout / one
consumer per partition). If you need a lock, use a lease in a consensus store (etcd/ZooKeeper)
or Redis with TTL, and protect the resource with a **fencing token** so a stalled ex-holder's
writes are rejected. Also make the job idempotent, because leases can expire mid-work. [10, 11]

**16. A downstream dependency is slow. How do you protect your service?**
Timeouts on every call, retries with exponential backoff and jitter (only for idempotent
operations, with a retry budget), a circuit breaker to fail fast, bulkheads so it can't eat all
threads, a fallback (cached or default response), and async processing via a queue where the
caller doesn't need the answer now. [13]

**17. How do you store and serve user images or video?**
Upload directly to object storage with a pre-signed URL; store metadata in the DB; process
asynchronously (thumbnails, transcoding) via a queue; serve through a CDN with long cache TTLs
and content-hashed URLs. [09, 20]

**18. How do you add full-text search to a product?**
Keep the primary DB as source of truth; stream changes (CDC or outbox → Kafka) into a search
engine with an inverted index; query search for IDs, hydrate from the DB or cache. Accept that
search lags a little (eventual). Reindex from the source of truth when mappings change. [09]

**19. How do you schedule millions of delayed jobs (e.g. "remind me in 3 days")?**
Store jobs in a DB table indexed by `run_at`, sharded; pollers claim due jobs in small batches
(`SELECT ... FOR UPDATE SKIP LOCKED` or a status update with a condition) and push them onto a
work queue. For short delays use a queue with delayed delivery or a timing wheel / sorted set
keyed by time. Make execution idempotent. [11]

**20. How do you rate limit an API across many servers?**
Token bucket per key stored in Redis, checked atomically with a Lua script at the API gateway;
rules in a config store; return 429 with `Retry-After`; fail open on store failure except for
security-sensitive limits; local backstop limits. [12, 18]

**21. How do you change an event's schema without breaking consumers?**
Use a schema registry and enforce compatibility: only add optional fields with defaults, never
remove or retype a field in place. For breaking changes, publish a new version (or topic),
migrate consumers, then retire the old one. [11]

**22. How do you make sure a DB write and the event it triggers never disagree?**
Transactional outbox: write the row and an outbox record in one DB transaction; a relay (or CDC)
publishes outbox records to the broker and marks them sent. Consumers are idempotent because the
relay may publish twice. [11]

**23. How do you scale WebSockets to millions of connections?**
Dedicated connection gateway servers (each holds tens to hundreds of thousands of mostly idle
connections), an L4 load balancer, a registry mapping user → gateway (in Redis), and a pub/sub
layer so any backend can deliver a message to the right gateway. Handle reconnects with resume
tokens and missed-message sync. [02, 19]

**24. How do you count unique visitors or deduplicate a huge event stream?**
Unique counts: HyperLogLog per (page, day), mergeable across shards, ~1% error. Dedup: keep
recent event IDs in a TTL'd set or a Bloom filter for a "probably seen" fast check, backed by
an exact check where false positives matter. [06, 08]

**25. How do you reprocess or backfill data after a bug?**
Keep raw immutable events (Kafka with long retention or object storage). Fix the code, then
replay from an offset or re-run a batch job over the affected range into a new output, verify,
and swap. This is why idempotent, deterministic processing and keeping raw data pay off. [09, 11]

> **Say it like this:** "Brokers give me at-least-once, so I don't fight that — I make the
> effect idempotent by recording the message ID in the same transaction as the side effect."

## 27.5 The 20 trade-offs cheat sheet

| # | Option A vs Option B | Choose A when… | Choose B when… |
|---|---|---|---|
| 1 | SQL vs NoSQL | Relations, joins, transactions, ad-hoc queries, data fits a (sharded) primary | Huge scale with simple key access, flexible schema, very high write volume |
| 2 | Strong vs eventual consistency | Money, inventory, uniqueness, locks | Feeds, likes, analytics, caches; availability and latency matter more |
| 3 | Sync vs async replication | No acknowledged write may be lost | Lower write latency matters; can tolerate losing the last seconds on failover |
| 4 | Single-leader vs leaderless | Simpler reasoning, transactions, read-your-writes from leader | Always-writable, multi-DC tolerant, tunable quorums |
| 5 | Push (fan-out on write) vs pull (on read) | Few recipients, read latency critical | Huge fan-out (celebrities), many inactive readers |
| 6 | Normalize vs denormalize | Write-heavy, consistency-critical, storage-sensitive | Read-heavy, joins too slow at scale |
| 7 | Cache-aside vs write-through | General purpose; only cache what is read | Data read right after write; keep cache always warm |
| 8 | Sync call vs async queue | Caller needs the result now | Slow, retryable, spiky or fan-out work |
| 9 | Polling vs WebSockets/SSE | Updates infrequent, simplicity wins | Real-time, frequent server→client updates |
| 10 | B-tree vs LSM storage | Read-heavy, range scans, predictable reads | Write-heavy ingest, sequential writes |
| 11 | Vertical vs horizontal scaling | Early stage; simpler; one big machine suffices | Beyond one machine's limits; need redundancy |
| 12 | Hash vs range partitioning | Even distribution, point lookups | Range scans by key (time, alphabetical), accepting hot-range risk |
| 13 | Optimistic vs pessimistic locking | Conflicts rare; avoid lock waits | Conflicts frequent; retries would be wasteful |
| 14 | Batch vs stream processing | Complete, accurate results; cheaper; latency of hours OK | Results needed in seconds; continuous data |
| 15 | LWW vs vector clocks / CRDTs | Any recent value is fine; simplicity | Concurrent updates must all be kept / merged |
| 16 | Fail open vs fail closed | Availability of the main path matters most | Security or safety must not be bypassed |
| 17 | 2PC vs saga | Strict atomicity across a few resources you control, short transactions | Long-running, cross-service workflows; compensation acceptable |
| 18 | REST vs gRPC | Public APIs, browsers, simplicity, cacheability | Internal service-to-service, low latency, streaming, strict contracts |
| 19 | Exact vs approximate | Billing, money, legal counts | Analytics, "views", distinct counts at scale (HLL, sampling) |
| 20 | Monolith vs microservices | Small team, early product, unclear boundaries | Many teams, clear domains, different scaling or release needs |

> **Say it like this:** "Neither is better in general. I'd pick A here because of this
> requirement, and I'd switch to B if that requirement changed."

## 27.6 Four-week study plan

Assumes about 1–1.5 hours on weekdays and 2–3 hours on weekend days. Adjust the pace, keep the
order: fundamentals first, then the method, then many case studies, then LLD.

### Week 1 — Foundations

| Day | Read | Do |
|---|---|---|
| 1 | 01 client-server & networking | Draw what happens when you type a URL, from DNS to response |
| 2 | 02 APIs | Design the REST API for a todo app with pagination and idempotency |
| 3 | 03 servers & scaling | Explain stateless vs stateful out loud in two minutes |
| 4 | 04 numbers & estimation | Do 5 estimations (QPS, storage) for apps you use daily |
| 5 | 05 SQL databases | Write down each isolation level and one anomaly it allows |
| 6 | 06 NoSQL & LSM | Draw the LSM write and read paths from memory |
| 7 | 07 replication & sharding | Explain consistent hashing and leader failover; rest |

### Week 2 — Distributed systems toolkit

| Day | Read | Do |
|---|---|---|
| 8 | 08 caching & CDN & Redis | List 4 cache problems and their fixes |
| 9 | 09 blob, search, OLAP, batch/stream | Sketch an inverted index for 3 small documents |
| 10 | 10 distributed fundamentals | Explain CAP, Raft leader election and Snowflake IDs aloud |
| 11 | 11 messaging & Kafka & outbox | Draw outbox + relay + idempotent consumer |
| 12 | 12 load balancing, gateways, rate limiting | Compare 4 rate limiting algorithms in a table |
| 13 | 13 microservices & resilience | Write a saga with compensations for "book trip" |
| 14 | 14 reliability, 15 security | Define SLI/SLO/SLA; explain OAuth flow at a high level |

### Week 3 — HLD method and case studies

| Day | Read | Do |
|---|---|---|
| 15 | 16 HLD method | Run the 8 steps on "design a leaderboard" with a timer |
| 16 | 17 URL shortener + Pastebin | Redo it from a blank page in 45 minutes, then compare |
| 17 | 18 rate limiter + KV store | Explain the Dynamo technique table from memory |
| 18 | 19 news feed + chat | Practise prompt: design Instagram (timed) |
| 19 | 20 video, file sync, notifications | Practise prompt: design Dropbox (timed) |
| 20 | 21 typeahead, crawler, ad serving | Practise prompt: design a web crawler (timed) |
| 21 | Review | Two timed mock designs, ideally with a partner; record yourself |

### Week 4 — LLD and polish

| Day | Read | Do |
|---|---|---|
| 22 | 22 OOP & SOLID | Find one SOLID violation in your own code and fix it |
| 23 | 23 design patterns | Implement Strategy, Observer and Factory in small examples |
| 24 | 24 LLD method & UML | Run the method on "parking lot" with a timer |
| 25 | 25 LLD case studies | Implement one case study end to end and test it |
| 26 | 26 concurrency for LLD | Make your case study thread-safe; explain your locking |
| 27 | 27 this chapter | Answer all 25 "how would you" questions aloud |
| 28 | Mock day | One HLD and one LLD mock under time; write down 3 gaps and revisit those chapters |

After the four weeks: two or three timed practice problems per week from the lists below,
rotating through them, beats any amount of re-reading.

### Practice prompts — 20 classic HLD problems

1. URL shortener
2. Pastebin
3. Rate limiter (distributed)
4. Distributed key-value store
5. News feed (Twitter / Instagram home timeline)
6. Chat / messaging (one-to-one and groups)
7. Notification system (push, email, SMS)
8. Video streaming (YouTube / Netflix)
9. File storage and sync (Dropbox / Google Drive)
10. Typeahead / search autocomplete
11. Web crawler
12. Ad serving / click tracking
13. Ride sharing (matching riders and drivers, location updates)
14. Proximity search (nearby restaurants)
15. Hotel / ticket booking (no double booking)
16. Payment system (idempotent, ledger)
17. Distributed job scheduler
18. Metrics / monitoring system (time-series ingestion and query)
19. Collaborative document editing
20. Top-K / trending (heavy hitters over a time window)

### Practice prompts — 15 classic LLD problems

1. Parking lot
2. Elevator system
3. Vending machine
4. Library management
5. Snake and ladder / board game
6. Tic-tac-toe / chess (move validation)
7. LRU cache (with thread safety)
8. In-memory rate limiter
9. Splitwise (expense sharing)
10. Movie ticket booking (seat locking)
11. Hotel reservation
12. Logger / logging framework
13. Pub/sub message broker (in-process)
14. Food delivery order lifecycle (state machine)
15. ATM

## 27.7 Reading engineering blogs and papers efficiently

### How to read a paper

Do not read a systems paper front to back on the first pass. Use three passes:

1. **Pass 1 (10 minutes):** title, abstract, introduction, section headings, conclusion,
   figures. Answer: what problem, for what workload, and what is the core idea?
2. **Pass 2 (1 hour):** read the design sections; redraw the main architecture diagram
   yourself; note every assumption ("machines fail often", "files are large and appended").
3. **Pass 3 (optional):** details, proofs, evaluation — only for papers you need deeply.

For every paper, write five lines:

```text
Problem:      what pain at what scale?
Assumptions:  what about the workload/environment made this design possible?
Core idea:    one or two sentences.
Trade-off:    what did they give up to get it?
Legacy:       which systems today use the idea?
```

### How to read an engineering blog post

- Find the **"why"** first: what broke or did not scale. That is the transferable lesson.
- Find the **numbers**: QPS, sizes, latencies before and after.
- Redraw the **before and after** diagrams.
- Separate **principle** from **local detail** (their exact tools, their org constraints).
- Ask "what would I have done with a simpler design, and why didn't they?"

### Classic papers and what to take from each

| Paper (year) | One-line takeaway |
|---|---|
| GFS — The Google File System (2003) | One master for metadata, many chunkservers for data, large chunks, replicate x3, and design for constant component failure and append-heavy workloads. |
| MapReduce (2004) | Express batch jobs as map + group-by-key + reduce, and let the framework handle distribution, retries of failed tasks and data locality. |
| Bigtable (2006) | A sparse, sorted, multi-dimensional map (row, column, timestamp) split into tablets, built on LSM-style storage — the template for wide-column stores. |
| Dynamo (2007) | An always-writable, leaderless key-value store: consistent hashing, quorums, sloppy quorum + hinted handoff, vector clocks, Merkle anti-entropy, gossip. |
| Kafka (2011) | Treat messaging as a partitioned, append-only log on disk that consumers read by offset — sequential IO gives high throughput and replay for free. |
| Raft (2014) | Consensus made understandable: leader election with terms, log replication, commit on majority, with safety rules that keep logs consistent. |

Worth adding later: Chubby (2006, a lock service and the lease idea), ZooKeeper (2010,
coordination primitives), Spanner (2012, globally consistent transactions using tightly bounded
clock uncertainty), "Time, Clocks, and the Ordering of Events in a Distributed System" (1978,
logical clocks and happened-before).

## 27.8 Recap

- Use the glossary as a self-test: every term should be explainable in one or two sentences,
  with its trade-off.
- Have sentences ready for clarifying, assuming, signposting, trade-offs, pushing back, not
  knowing and summarizing; saying them aloud before you need them builds confidence.
- Most follow-up questions reduce to a few patterns: idempotency, caching and invalidation,
  sharding and hot keys, async with queues, replication and failover, and observability.
- Every trade-off answer has the same shape: two options, when each wins, what you pay.
- Practise under time: two or three timed problems per week from the HLD and LLD lists.
- Read papers in passes and summarize each as problem, assumptions, core idea, trade-off,
  legacy.
- Confidence comes from structure: requirements, numbers, simple design, deep dives, failures,
  trade-offs — in that order, every time.
