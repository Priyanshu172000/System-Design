# 18 — Case study: distributed rate limiter and key-value store

Two infrastructure systems that show up in interviews and in real platforms. The rate limiter
is a small system with sharp edges: atomic counters, a strict latency budget and a hard
decision about what to do when it breaks. The key-value store is the opposite: a large system
that combines almost every distributed-systems technique from chapters 06, 07 and 10 into one
coherent design, in the style of Amazon's Dynamo paper.

## 18.1 Part A — The rate limiter as a system

Chapter 12 covered rate limiting *algorithms* (fixed window, sliding window log, sliding window
counter, token bucket, leaky bucket). Here we design the *system* around them: where it runs,
where state lives, how rules are configured, how it behaves across machines and regions, and
how it fails.

### Why a distributed rate limiter at all?

A **rate limiter** caps how many requests a client (user, API key, IP, tenant) may make in a
time window. Reasons: protect backends from overload, stop abuse (credential stuffing,
scraping), enforce paid quotas ("1,000 calls/hour on the free tier"), and keep one noisy tenant
from hurting others.

With one server, a hash map of counters in memory is enough. With 50 API servers behind a load
balancer, each server only sees ~1/50 of a client's traffic:

```text
 client (limit 100/min) --> LB --+--> server 1: sees 2/min   "fine"
                                 +--> server 2: sees 2/min   "fine"
                                 ...
                                 +--> server 50: sees 2/min  "fine"
   each server allows up to 100/min locally -> real limit up to 50 x 100 = 5,000/min
```

If each server enforces "100/min" locally, the real limit is 100 × 50 = 5,000/min. We need a
**shared view** of each client's usage.

### Requirements

Functional:

- Limit requests by a key (user ID, API key, IP, or a combination like `api_key + endpoint`).
- Rules configurable without redeploying: e.g. `POST /login`: 5/min per IP;
  `GET /search`: 100/min per API key; free tier 1,000/hour.
- When over the limit: reject with `429 Too Many Requests` and tell the client when to retry.

Non-functional:

- **Low latency:** the limiter is on the path of every request. Budget: about 1–2 ms added at
  p99 (a rule of thumb; it must be small relative to the request itself).
- **High availability:** the limiter must not become the reason the API is down.
- **Accuracy:** close enough. Allowing 102 instead of 100 occasionally is acceptable for most
  limits; for billing-grade quotas we may need exactness.
- **Scale:** say 1 million requests/sec across the fleet, 10 million distinct keys.

### Where it sits

| Placement | How | Pros | Cons |
|---|---|---|---|
| Client side | SDK throttles itself | Saves network | Untrusted — clients can ignore it. Only a courtesy. |
| Edge / API gateway | Gateway checks every request before routing (chapter 12) | One place, protects all services, rejects early and cheaply | Gateway only knows request metadata (path, key, IP), not business context |
| Middleware in each service | A library calls the shared counter store | Can use business context (e.g. "per account, per operation") | Each service must integrate; consistent rules harder |
| Sidecar / service mesh | Proxy next to each service calls a rate limit service | Language-agnostic, central rules | Extra hop and infrastructure |

Common answer: **at the API gateway** for coarse per-key/per-IP limits, plus in-service limits
for business-specific quotas. Global DDoS-scale filtering happens even earlier, at the CDN/edge.

### Architecture

```text
                         +---------------------+
                         |  Rules config store |   (DB / config service, versioned)
                         +----------+----------+
                                    | push or poll every ~10 s, cached in memory
                                    v
 client --> [LB] --> +---------------------------+         +---------------------+
                     | API gateway x N           | --1--> |  Redis cluster       |
                     |  - match request -> rule  | <--2-- |  counters / buckets  |
                     |  - build key              |         |  (sharded by key)    |
                     |  - atomic check (Lua)     |         +---------------------+
                     +-------------+-------------+
                          allowed  |   rejected -> 429 + Retry-After
                                   v
                           [ backend services ]
```

1. Gateway matches the request to a rule (e.g. `POST /login` → "5/min per IP") using rules
   held in memory.
2. Builds the counter key, e.g. `rl:login:ip:203.0.113.7`.
3. Runs an atomic check-and-update in Redis (one round trip).
4. Allows or rejects.

**Rules config:** stored in a DB or config service, versioned, pushed (or polled) to gateways
and cached in memory. Rules must never be fetched per request. Example rule:

```text
- id: login-per-ip
  match: { method: POST, path: /login }
  key: ip
  algorithm: sliding_window_counter
  limit: 5
  window: 60s
- id: search-per-key
  match: { method: GET, path: /search }
  key: api_key
  algorithm: token_bucket
  capacity: 100        # burst
  refill_per_sec: 10   # sustained rate
```

### Why atomicity matters: the race

A naive implementation reads the counter, checks it, then writes it back:

```text
 gateway A: GET count -> 99            gateway B: GET count -> 99
 gateway A: 99 < 100, allow            gateway B: 99 < 100, allow
 gateway A: SET count 100              gateway B: SET count 100
 -> two requests allowed, count says 100 (should be 101; limit exceeded)
```

This is the classic **read-modify-write race**. Fixes:

- Use an atomic primitive: `INCR` returns the new value atomically. For a fixed window:
  `INCR key` → if result is 1, `EXPIRE key 60` → allow if result ≤ limit. (The `INCR` +
  `EXPIRE` pair is two commands; if the process dies between them the key never expires — so
  run both in a script or use `SET key 0 EX 60 NX` first.)
- For anything more complex (token bucket, sliding window), use a **Lua script**. Redis runs a
  script atomically: no other command runs on that Redis instance while the script executes,
  because Redis executes commands on a single thread. The whole read-compute-write becomes
  one indivisible step and one network round trip.

### Token bucket in Redis with Lua

State per key: `tokens` (float) and `ts` (last refill time in ms). On each request, refill
based on elapsed time, then try to take tokens.

```lua
-- KEYS[1] = bucket key
-- ARGV[1] = capacity, ARGV[2] = refill tokens per second, ARGV[3] = cost (usually 1)
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])

local t   = redis.call('TIME')                       -- server clock: {seconds, micros}
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local b      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or capacity            -- new key starts full
local ts     = tonumber(b[2]) or now

tokens = math.min(capacity, tokens + (now - ts) / 1000 * rate)

local allowed = 0
if tokens >= cost then
  tokens  = tokens - cost
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- expire idle buckets: after capacity/rate seconds a bucket is full again anyway
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)

return { allowed, math.floor(tokens) }               -- Lua numbers -> Redis integers
```

Notes:

- Using Redis's own `TIME` avoids clock skew between gateways. (Calling `TIME` inside a script
  that writes is fine in modern Redis, which replicates the script's *effects* rather than
  re-running the script on replicas; very old versions disallowed it.)
- One script call = one round trip, typically well under 1 ms inside a data center.
- In **Redis Cluster**, every key a script touches must be in the same hash slot. A
  single-key script is fine; for multi-key scripts use hash tags like `rl:{user42}:a` and
  `rl:{user42}:b`.
- Expiring idle keys keeps memory bounded: 10 million keys × ~100 bytes ≈ 1 GB, small.

### Response to the client

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 12
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 12
```

`429` and `Retry-After` are standard HTTP. The `RateLimit-*` headers follow an IETF draft;
many APIs use `X-RateLimit-*` variants. Successful responses can also carry the remaining
quota so well-behaved clients slow down before hitting the wall.

> **Say it like this:** "The gateways share counters in Redis, and each check is a single Lua
> script so read-refill-decrement happens atomically in one round trip. Rules live in a config
> store and are cached in each gateway, so the only per-request network call is that one
> script."

### Scaling the counter store

- **Shard by key** across a Redis cluster (consistent hashing / hash slots). Each request
  touches exactly one key, so it scales linearly.
- **Hot keys:** one very busy API key lands on one shard. For huge tenants, use a
  **local token lease**: each gateway takes a batch of, say, 50 tokens from Redis at once and
  spends them locally. This cuts Redis calls ~50x at the cost of some imprecision (tokens
  leased by one gateway cannot be used by another).
- **Replication:** a replica per shard for failover. Counter loss on failover just resets some
  limits briefly — acceptable.

### Multi-region

Clients may hit any of several regions. A strict global limit needs a global counter, but a
cross-region round trip (tens to 100+ ms) on every request blows the latency budget. Options:

| Approach | How | Accuracy | Latency | Notes |
|---|---|---|---|---|
| Per-region independent limits | Each region enforces its own limit | Global total can reach limit × regions | Local | Simplest. Fine if limits are generous or traffic is regional. |
| Split quota | Each region gets limit / R (or a share weighted by its traffic) | Under-allows if traffic is uneven | Local | Rebalance shares periodically. |
| Local counting + async sync | Count locally, periodically exchange counts with other regions | Approximately global; brief overshoot possible | Local | Common approach for large platforms. |
| Central global store | All regions call one store | Exact | Cross-region RTT per request | Usually unacceptable inline. |
| Home-region routing | Route each client to one region (sticky by key) | Exact | Extra hop for far clients | Natural when clients already have a home region. |

The general point: **exact global limits and low latency conflict** (it is a small CAP-style
trade-off). Most systems accept approximate limits.

### Failure mode: fail open vs fail closed

What should the gateway do if Redis is down or slow?

- **Fail open:** allow the request without checking. The API stays available; you are
  temporarily unprotected.
- **Fail closed:** reject the request. Backends stay protected; the API is effectively down for
  everyone.

| Situation | Usual choice | Why |
|---|---|---|
| General API traffic limits | Fail open | The limiter must not cause an outage; brief overuse is tolerable |
| Login / OTP / password reset attempts | Fail closed (or fall back to a strict local limit) | Unlimited attempts enable brute force |
| Paid quota enforcement | Fail open, reconcile billing later | Customer experience first; charge correctly from logs |
| Protecting a fragile downstream | Fall back to local per-server limits | Some protection without the shared store |

Combine with a tight **timeout** on the Redis call (a few ms) and a **circuit breaker**
(chapter 13): if Redis fails repeatedly, stop calling it for a while and use the fallback
policy, so you do not add timeout latency to every request.

A strong pattern: **two-layer limiting.** A coarse in-memory limit on each gateway (e.g.
limit / number_of_gateways × 2) always runs; the precise Redis-backed limit runs on top. If
Redis fails, the local layer still bounds the damage.

> **Say it like this:** "If the counter store is unreachable I'd fail open for normal API
> limits, because the limiter shouldn't take the API down, but fail closed for login attempts.
> A local per-gateway limit stays on as a backstop either way."

### Latency budget summary

```text
 per request, inside one data center (rough):
   rule lookup (in-memory)                 ~ microseconds
   build key                               ~ microseconds
   Redis Lua round trip                    ~ 0.2 – 1 ms
   ---------------------------------------------------------
   total added                             ~ 1 ms typical, timeout at a few ms
```

### Rate limiter recap table

| Concern | Decision |
|---|---|
| Where | API gateway (coarse) + in-service (business quotas) |
| State | Redis cluster, sharded by limiter key, TTL on keys |
| Atomicity | Single Lua script per check (or `INCR` for fixed window) |
| Algorithm | Token bucket for bursty APIs; sliding window counter for smooth per-window limits |
| Rules | Config store, cached in gateways, hot reload |
| Hot keys | Local token leases |
| Multi-region | Approximate: local counting with async sync, or split quotas |
| Failure | Timeout + circuit breaker; fail open by default, fail closed for security-sensitive limits; local backstop limit |

## 18.2 Part B — A Dynamo-style distributed key-value store

### The problem

Design a **key-value store**: `put(key, value)` and `get(key)`. Values are opaque blobs
(say up to 1 MB). Requirements:

- Very large: billions of keys, hundreds of TB → many machines.
- **Always writable:** a write should succeed even when some nodes or links are failing
  (think shopping cart: refusing "add to cart" loses money).
- Low latency at high percentiles (p99.9 in the tens of ms).
- Tunable consistency: some callers want stronger reads, others want speed.
- Horizontally scalable: add nodes without downtime; no single master.

These goals push toward an **AP** design (chapter 10): stay available during partitions and
reconcile divergent copies later. This is the design space of Amazon's Dynamo paper (2007) and
systems it influenced (Cassandra, Riak, Voldemort). Note that DynamoDB, the managed AWS
service, shares the name but uses a different, leader-based internal design.

### API

```text
put(key, value, context) -> ok
get(key)                 -> (value or list of conflicting values, context)
delete(key, context)     -> ok          (implemented as a write of a tombstone)
```

The **context** is opaque metadata (the version, e.g. a vector clock) that the client got from
a previous `get` and passes back on `put`, so the store knows which version the write is based
on. This is how the store tells "update" apart from "concurrent conflicting write".

### Step 1 — Partitioning with consistent hashing

We must decide which node stores which key, and move as little data as possible when nodes join
or leave. **Consistent hashing** (chapter 07): hash every node and every key onto a ring of
hash values (e.g. 0 … 2^128 − 1); a key belongs to the first node clockwise from its hash.

**Virtual nodes (vnodes):** each physical node owns many positions (tokens) on the ring, e.g.
256. This spreads load evenly, lets bigger machines own more vnodes, and when a node dies its
load spreads across many nodes instead of landing all on one neighbour.

```text
  hash ring drawn as a line (positions increase to the right, the end wraps to the start)

  0 ---A1----B1----C1-------B2---A2---C2----x----C3----A3----B3----- 2^128 (wraps to 0)
                                            ^
                                      hash(k) lands here
  walk right from x: C3 (node C) -> A3 (node A) -> B3 (node B)

  preference list for k (N=3 distinct PHYSICAL nodes): [C, A, B]
  (if the next vnode belonged to a node already chosen, skip it)
```

### Step 2 — Replication factor N

Each key is stored on **N** nodes (typically N = 3). Walk clockwise from the key's position
and take the first N **distinct physical nodes** (skip vnodes of a node already chosen; in
production also prefer different racks/availability zones). This list is the key's
**preference list**. The first is often called the coordinator for that key, but in Dynamo any
node in the list (or any node at all, forwarding) can coordinate a request.

### Step 3 — Quorum reads and writes: tunable consistency

Three numbers:

- **N** — number of replicas.
- **W** — number of replicas that must acknowledge a write before it is reported successful.
- **R** — number of replicas that must respond to a read before returning.

```text
 put(k, v), N=3, W=2:
   coordinator --write--> replica 1  ack  \
               --write--> replica 2  ack   >-- 2 acks -> success to client
               --write--> replica 3  (slow; will catch up)

 get(k), N=3, R=2:
   coordinator --read--> replica 1  v2 \
               --read--> replica 2  v1  >-- 2 responses -> return newest (v2),
               --read--> replica 3  ...     repair replica 2 in background (read repair)
```

If **R + W > N**, every read quorum overlaps every write quorum in at least one node, so a
read contacts at least one replica that has the latest acknowledged write. (Overlap: with
N = 3, W = 2, R = 2, any 2-node write set and any 2-node read set share a node.)

| Setting (N=3) | Behaviour |
|---|---|
| W=2, R=2 | Common balanced choice; overlap; tolerates 1 slow/failed replica for both reads and writes |
| W=3, R=1 | Fast reads; writes fail if any replica is down |
| W=1, R=3 | Fast, highly available writes; reads must hear from all |
| W=1, R=1 | Fastest, most available; no overlap → reads may be stale (eventual consistency) |

Important caveats, often missed:

- R + W > N gives overlap, **not full linearizability**. With concurrent writes, sloppy quorums
  (below), or last-write-wins on skewed clocks, reads can still see surprising results. Say
  "quorum consistency improves freshness" rather than "it is strongly consistent".
- A write that fails to reach W replicas is reported as failed, but it may have succeeded on
  some replicas — it is not rolled back. Later reads might return it. Clients must treat a
  failed write as "unknown outcome" and retry idempotently.

> **Say it like this:** "With N=3 and R=W=2, read and write quorums always overlap, so a read
> normally sees the latest acknowledged write while tolerating one slow replica. It's tunable
> per request: W=1 for a fast write path, R=1 for fast reads where staleness is fine."

### Step 4 — Sloppy quorum and hinted handoff

Strict quorum: the W acks must come from the key's N preference-list nodes. If two of the
three are unreachable, writes fail — violating "always writable".

**Sloppy quorum:** if a preference-list node is unreachable, the coordinator writes to the next
healthy node further along the ring instead, and counts that ack toward W. That stand-in node
stores the data with a **hint**: "this really belongs to node B".

**Hinted handoff:** the stand-in periodically checks whether B is back; when it is, it sends the
hinted data to B and deletes its local copy.

```text
 preference list for k: [A, B, C]     B is down
 next healthy node on ring: D

 put(k): A ack, C ack, D ack (stores k with hint "for B")   -> W=2 satisfied

 later, B recovers:
   D --hinted handoff: k--> B      D deletes its hinted copy
```

| Gains | Costs |
|---|---|
| Writes succeed during node failures and partitions (high availability) | R + W > N no longer guarantees overlap: the write might sit on D while a read asks A, B, C |
| Data is not lost while a node is temporarily down | Stand-in nodes carry extra data and load |
| | If the stand-in also fails before handoff, the replica count stays low until anti-entropy fixes it |

Hinted handoff handles **temporary** failures. Permanent divergence is handled by anti-entropy
(step 6).

### Step 5 — Conflict resolution: vector clocks vs last-write-wins

Because writes can land on different replicas during failures, two replicas can hold different
values for the same key, and neither is "the old one" — they are **concurrent**. Two ways to
resolve.

**Last-write-wins (LWW):** each write carries a timestamp; the highest timestamp wins.

- Simple, no client involvement, small metadata. Cassandra uses timestamps per column (cell).
- Cost: **silently discards** one of two concurrent writes. With clock skew between machines,
  even a *later* write can lose to an earlier one with a faster clock. Fine for data where any
  recent value is OK (a profile photo URL, a cache-like value); bad for data where both updates
  matter (cart additions, counters).

**Vector clocks:** each version carries a list of `(node, counter)` pairs — one counter per
node that has coordinated a write to this key. A node handling a write increments its own
entry.

Comparing two clocks:

- If every entry in X is ≤ the corresponding entry in Y (missing = 0), X is an **ancestor** of
  Y → Y supersedes X; discard X.
- If neither is ≤ the other, they are **concurrent** → keep both as **siblings**.

```text
 1. client writes v1 via node A                 v1 [A:1]
 2. client reads v1, writes v2 via node A       v2 [A:2]          (supersedes v1)
 3. partition. Client P reads v2, writes via B: v3 [A:2, B:1]
                Client Q reads v2, writes via C: v4 [A:2, C:1]
    compare v3 vs v4: A equal, B: 1 vs 0, C: 0 vs 1 -> neither dominates -> CONCURRENT
 4. get(k) returns both siblings {v3, v4} plus a merged context
 5. client merges (e.g. union of cart items) and writes v5 via A:
                                                v5 [A:3, B:1, C:1]  (dominates v3, v4)
```

| | Last-write-wins | Vector clocks + siblings |
|---|---|---|
| Detects concurrency? | No | Yes |
| Data loss on concurrent writes | Yes, silently | No; client merges |
| Client complexity | None | Must handle multiple values and merge |
| Metadata size | One timestamp | Grows with number of coordinating nodes (truncated in practice) |
| Clock dependency | Needs reasonably synced clocks | None (logical clocks) |

Dynamo uses vector clocks and leaves semantic merging to the application (a cart takes the
union of items; a deleted item can reappear — a known trade-off). Vector clocks are bounded by
dropping the oldest entries past a size threshold, which can in rare cases cause a false
"concurrent" result (extra siblings, not data loss). An alternative for specific data types is
**CRDTs** (conflict-free replicated data types: counters, sets with merge rules built in).

> **Say it like this:** "Last-write-wins is simple but silently drops one of two concurrent
> writes and trusts wall clocks. Vector clocks detect that two versions are concurrent and hand
> both to the application to merge, which is right for something like a shopping cart."

### Step 6 — Anti-entropy with Merkle trees

Replicas can drift permanently: a hinted handoff lost, a node down for days, a disk replaced.
**Anti-entropy** is a background process that compares replicas and repairs differences.
Comparing every key would mean sending the entire dataset over the network. A **Merkle tree**
makes the comparison cheap.

A Merkle tree (hash tree) over a key range: leaves are hashes of individual keys' values (or
buckets of keys); each parent is the hash of its children; the root summarizes the whole range.

```text
 replica X                                   replica Y
            H(root)                                    H(root')       roots differ
           /       \                                  /        \
      H(L)           H(R)                       H(L)            H(R')   L same, R differs
     /    \         /    \                     /    \          /    \
   h1      h2     h3      h4                 h1      h2      h3      h4'  only h4 differs
  k1-k25 k26-50 k51-75  k76-100                                           -> sync k76-100
```

Two replicas exchange root hashes. Equal → done (one hash sent). Different → compare children,
descend only into differing subtrees. Data transferred is proportional to the *differences*,
not the dataset. Each node keeps one Merkle tree per key range it holds.

Cost: trees must be rebuilt or updated as data changes, and when node ownership of ranges
changes, trees for affected ranges are recalculated.

Two more repair mechanisms complete the picture:

- **Read repair:** during a `get`, if the coordinator sees a stale replica, it writes the newest
  version back to it. Cheap, but only fixes keys that are read.
- **Hinted handoff:** fixes short outages (step 4).

Merkle anti-entropy catches everything else, including keys nobody reads.

### Step 7 — Membership and failure detection with gossip

With no master, how does each node know which nodes exist and which are alive?

**Gossip protocol:** every second or so, each node picks a random peer and exchanges its view of
the cluster (members, their ring tokens, heartbeat counters, status). Each node merges the
received view into its own, keeping the newer information. Like an epidemic, news reaches all
N nodes in roughly O(log N) rounds, with no central coordinator.

```text
 round 1:  A knows "E joined"  -> gossips to C
 round 2:  A -> B, C -> F                     (now A, C, B, F know)
 round 3:  each of the 4 tells one more ...   (spreads exponentially)
```

**Failure detection:** each node records when it last heard (directly or via gossip) a newer
heartbeat from each peer.

- Simple approach: mark a node "suspected down" after T seconds without a new heartbeat.
- Adaptive approach: the **phi accrual failure detector** (used by Cassandra) outputs a
  suspicion level based on the observed distribution of heartbeat inter-arrival times, so slow
  networks raise suspicion more gradually than fixed timeouts.

Key distinction: "temporarily unreachable" (handled by sloppy quorum and hinted handoff, the
ring does not change) is different from "permanently removed" (an explicit membership change
that moves ranges to other nodes). Dynamo made permanent joins/leaves an explicit admin action,
precisely so a flaky network does not trigger expensive data rebalancing.

**Seed nodes:** a few well-known nodes every node contacts at startup, so new nodes can join the
gossip and the cluster does not split into groups that never hear of each other.

### Step 8 — Storage engine on each node: LSM tree

Each node needs a local engine for its share of keys. The workload is write-heavy with point
reads, so a common choice is an **LSM tree** (chapter 06), as in Cassandra and RocksDB-based
stores.

### Write path

```text
 client --put(k,v,ctx)--> coordinator (any node; or a client-side ring-aware library)
   1. hash(k) -> preference list [A, B, C]
   2. coordinator generates the new version (vector clock / timestamp)
   3. send to all N replicas in parallel (sloppy: substitute healthy nodes + hints)
        each replica:
          a. append to commit log (sequential write; fsync per policy)
          b. insert into memtable (in-memory sorted map)
          c. ack
          (later) memtable full -> flush to an immutable SSTable on disk
          (later) compaction merges SSTables, drops overwritten values, old tombstones
   4. wait for W acks -> reply success   (fewer than W before timeout -> reply failure)
```

### Read path

```text
 client --get(k)--> coordinator
   1. hash(k) -> preference list [A, B, C]
   2. send read to replicas (all N, or R plus spares to cut tail latency)
        each replica:
          a. check memtable
          b. for each SSTable newest -> oldest:
               Bloom filter says "maybe here"? -> index lookup -> read block
               ("definitely not here" -> skip, no disk read)
          c. return value + version
   3. wait for R responses
   4. reconcile: drop versions dominated by others; if one remains -> return it;
      if concurrent -> return siblings (vector clocks) or newest timestamp (LWW)
   5. read repair: send the reconciled version to replicas that returned stale data
```

### Deletes: tombstones

You cannot simply remove a key from one replica: another replica that missed the delete would
later "repair" the key back to life during anti-entropy. A delete is therefore a write of a
**tombstone** (a marker meaning "deleted at version V"). Tombstones are kept for a grace period
long enough for all replicas to learn of them (Cassandra's default `gc_grace_seconds` is 10
days), then removed by compaction. If a replica is down longer than that grace period, deleted
data can come back ("zombie" data) — which is why repairs must run more often than the grace
period.

### Adding a node

1. New node picks its vnode tokens and joins via seed nodes; gossip spreads its tokens.
2. For each range it now owns, it streams data from the nodes that previously held that range.
3. Once caught up, the old owners drop the transferred ranges.

Thanks to vnodes, the new node takes a small slice from many nodes, so no single node is
overloaded during the transfer.

### Whole-system picture

```text
                        +-----------------------------------------------+
  client (ring-aware    |                 Hash ring (vnodes)            |
  library or any node   |                                               |
  as coordinator) ----> |   [Node A] <--gossip--> [Node B] <--gossip--> |
                        |      ^  \                  |          [Node C]|
                        |      |   \__ Merkle sync __/              ^    |
                        |   gossip                                   |   |
                        |      v                                     |   |
                        |   [Node D] <---- hinted handoff ------- [Node E]
                        +-----------------------------------------------+
  each node:  request coordinator | gossip + failure detector | anti-entropy (Merkle)
              | hinted handoff store | storage engine (commit log, memtable, SSTables)
```

### Technique ↔ problem summary

| Problem | Technique | What it buys | What it costs |
|---|---|---|---|
| Spread data across nodes; add/remove nodes cheaply | Consistent hashing + virtual nodes | Only ~1/n of keys move on membership change; even load | Ring metadata to maintain |
| Survive node loss without losing data | Replication to N distinct nodes (racks/AZs) | Durability, read capacity | N× storage, replication traffic |
| Balance consistency vs latency per request | Quorum R, W with R + W > N for overlap | Tunable freshness vs speed | Waiting for more replicas = higher latency |
| Stay writable during temporary failures | Sloppy quorum + hinted handoff | "Always writable" | Weaker overlap guarantee; hint storage |
| Concurrent writes to divergent replicas | Vector clocks (+ app merge) or LWW | Detect conflicts / simple resolution | Client merge logic, or silent data loss |
| Replicas drift permanently | Anti-entropy with Merkle trees | Repair with minimal data transfer | Tree maintenance CPU/IO |
| Stale replica seen during a read | Read repair | Hot keys converge quickly | Extra writes on reads |
| Know who is in the cluster and alive, without a master | Gossip membership + (phi accrual) failure detection | Decentralized, no SPOF | Eventually consistent membership view |
| High write throughput on each node | LSM storage engine (commit log, memtable, SSTables) | Sequential writes, fast ingest | Read and write amplification, compaction |
| Avoid disk reads for absent keys | Bloom filters per SSTable | Skip most SSTables | Small memory; false positives |
| Deletes that stick across replicas | Tombstones with grace period | Correct deletes | Tombstone buildup; zombie risk if repair lags |

> **Say it like this:** "It's a leaderless AP store: consistent hashing with vnodes for
> placement, N replicas, tunable R and W quorums, sloppy quorum with hinted handoff to stay
> writable, vector clocks or LWW for conflicts, Merkle-tree anti-entropy for repair, and gossip
> for membership. Each technique covers one specific failure or scaling problem."

### When NOT to use this design

- You need transactions across keys, or strict linearizable reads (e.g. bank balances,
  uniqueness constraints): use a leader-based, consensus-replicated store (chapter 10) such as
  one built on Raft, or a distributed SQL database.
- Your data fits on one primary with replicas: a plain relational DB or Redis is far simpler.
- You need rich queries (secondary indexes, joins, ranges across keys): a KV store only answers
  "value for this key".

## 18.3 Recap

- A distributed rate limiter needs a shared counter store; local-only counters multiply the
  limit by the number of servers.
- Put coarse limits at the API gateway; keep rules in a config store cached in memory.
- Make check-and-update atomic: `INCR` for fixed windows, a Lua script for token bucket or
  sliding windows; one round trip, ~1 ms.
- Multi-region: exact global limits conflict with low latency; prefer local counting with async
  sync or split quotas.
- Fail open for general limits, fail closed for security-sensitive ones; always use timeouts,
  a circuit breaker and a local backstop limit.
- Dynamo-style KV store: consistent hashing with vnodes, N replicas on distinct nodes, quorum
  R/W (R + W > N for overlap).
- Sloppy quorum + hinted handoff keeps writes available through temporary failures but weakens
  the overlap guarantee.
- Vector clocks detect concurrent writes and return siblings; LWW is simpler but silently drops
  data and depends on clocks.
- Merkle trees make anti-entropy proportional to the differences; read repair fixes hot keys.
- Gossip spreads membership and liveness without a master; deletes are tombstones with a
  grace period.

## 18.4 Check yourself

1. Why does each server enforcing "100 requests/min" locally fail with 20 servers behind a
load balancer?

<details><summary>Answer</summary>

Each server only sees its share of the client's traffic and counts it separately. The client
can send up to 100 to each server, so the effective limit becomes up to 20 × 100 = 2,000/min.
A shared counter store (or a precise per-server share plus sticky routing) is needed for a true
global limit.

</details>

2. Why use a Lua script rather than `GET` then `SET` from the gateway?

<details><summary>Answer</summary>

`GET`-check-`SET` from separate clients is a read-modify-write race: two gateways can read the
same value, both allow, and both write back, exceeding the limit. Redis executes a Lua script
atomically (no other command interleaves), so read-refill-decide-write is one indivisible
operation, and it also takes just one network round trip.

</details>

3. Redis behind your rate limiter becomes unreachable. Walk through what happens for a search
endpoint and for a login endpoint.

<details><summary>Answer</summary>

The Redis call times out after a few ms; repeated failures open a circuit breaker so later
requests skip Redis immediately. Search: fail open — requests are allowed, bounded only by the
local per-gateway backstop limit; API stays up. Login: fail closed or use a strict local limit,
because unlimited login attempts allow brute-force attacks. Alerts fire either way.

</details>

4. N = 3, W = 1, R = 1. What do you gain and what do you risk?

<details><summary>Answer</summary>

Lowest latency and highest availability: a write succeeds when any one replica acks; a read
returns from any one replica. Risk: R + W = 2 ≤ N, so read and write sets may not overlap — a
read can return stale data even long after a write succeeded, until replication, read repair
or anti-entropy catches up. Also a write acknowledged by one node is lost if that node dies
before replicating.

</details>

5. Two versions have vector clocks [A:2, B:1] and [A:2, C:1]. What does the store do? And
[A:2, B:1] vs [A:3, B:1]?

<details><summary>Answer</summary>

First pair: neither is ≤ the other in every entry (B:1 vs 0, C:0 vs 1), so they are concurrent;
the store keeps both as siblings and returns both on read for the client to merge. Second pair:
[A:2, B:1] ≤ [A:3, B:1] in every entry, so the second descends from the first; the older one
is discarded.

</details>

6. Why does a sloppy quorum weaken the R + W > N guarantee?

<details><summary>Answer</summary>

With a sloppy quorum, writes may be acknowledged by stand-in nodes outside the key's
preference list. A subsequent read that contacts the normal preference-list nodes may not
include any node holding the new write, so the read and write sets need not overlap until
hinted handoff delivers the data back.

</details>

7. Two replicas each hold 100 million keys and differ in 50 keys. How does a Merkle tree avoid
sending 100 million keys?

<details><summary>Answer</summary>

The replicas compare root hashes; since they differ, they compare child hashes and only descend
into subtrees whose hashes differ. Subtrees with equal hashes are skipped entirely. The work and
data transferred are roughly proportional to the number of differing leaves times the tree
depth, and then only the keys in those differing leaf ranges are exchanged.

</details>

8. Why are deletes implemented as tombstones instead of removing the key?

<details><summary>Answer</summary>

If a replica simply removed the key, a replica that missed the delete would still have the old
value, and anti-entropy or read repair would copy it back — resurrecting deleted data. A
tombstone is a versioned write that wins over older values and propagates like any write. It is
garbage-collected after a grace period long enough for all replicas to have seen it.

</details>
