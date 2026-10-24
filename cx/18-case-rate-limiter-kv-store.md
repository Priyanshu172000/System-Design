# 18 — Case study: distributed rate limiter and key-value store
*Why it matters:* the rate limiter is a small system with sharp edges (atomic counters, a tight
latency budget, a hard failure decision); the Dynamo-style KV store combines nearly every
distributed technique from ch. 06, 07 and 10 into one design.

## Rate limiter: why distributed, and requirements
**How it works** · A **rate limiter** caps requests per key (user, API key, IP, tenant) per window:
protects backends, stops abuse (credential stuffing, scraping), enforces paid quotas, isolates
noisy tenants (algorithms in ch. 12). One server: an in-memory map. Many: each sees a slice.

**Example** · key `k_42`, limit 100/min, 50 gateways, the client sends 3,000 requests in a minute:

```text
 local counters   each gateway sees 3,000 / 50 = 60/min < 100 -> all 3,000 allowed (30x)
 shared counter   all 50 gateways count in one place -> 100 allowed, 2,900 get 429
```

So: local "100/min" really means up to 50 x 100 = 5,000/min; we need a **shared view** of usage.

| Functional | Non-functional |
|---|---|
| Limit by key (user, API key, IP, or `api_key + endpoint`) | **Latency:** about 1–2 ms added at p99 (rule of thumb) |
| Rules changeable without redeploy: `POST /login` 5/min per IP, `GET /search` 100/min per key, free tier 1,000/hour | **Availability:** the limiter must never be why the API is down |
| Over limit: `429` and when to retry | **Accuracy:** 102 instead of 100 occasionally is fine; billing-grade may need exact |
| | **Scale:** say 1 M req/s, 10 M distinct keys (about 1 GB of state at ~100 B/key) |

## Rate limiter: where it sits
**How it works** · Common answer: **API gateway** for coarse per-key/per-IP limits plus in-service
limits for business quotas; DDoS-scale filtering happens earlier at the CDN/edge.

**Example** · a login storm: the edge drops a botnet flood by IP reputation; the gateway allows
`POST /login` 5/min per IP; the auth service locks *account* `u_1842` after 10 failures from any
IPs (business context the gateway lacks).

| Placement | Pros | Cons |
|---|---|---|
| Client SDK | Saves network | Untrusted, a courtesy only |
| Edge / API gateway (ch. 12) | One place, protects everything, rejects early | Knows only request metadata (path, key, IP) |
| Middleware in each service | Has business context ("per account, per operation") | Every service integrates; rules drift |
| Sidecar / mesh | Language-agnostic, central rules | Extra hop and infrastructure |

## Architecture and API: follow one request
**How it works** · Rules live in a versioned config store and are pushed or polled (~10 s) into
gateway memory, never fetched per request. Per request: match rule in memory, build key, run one
atomic check-and-update in Redis, allow or reject.

```text
 [Rules config store] --push/poll ~10 s, cached in memory--+
                                                           v
 client -> [LB] -> [API gateway x N: match rule, build key] --1 Lua--> [Redis cluster,
                         | allowed           rejected -> 429      <--2-- sharded by key]
                         v
                  [backend services]
```

A rule is data: `{match: POST /login, key: ip, algorithm: sliding_window_counter, limit: 5,
window: 60s}` or `{match: GET /search, key: api_key, algorithm: token_bucket, capacity: 100,
refill_per_sec: 10}`.

**Example** · follow `GET /search?q=biryani` from key `k_42`:
1. Gateway 17 matches rule `search-per-key` in memory and builds key `rl:search:k_42` (µs).
2. `EVALSHA <token-bucket> 1 rl:search:k_42 100 10 1` goes to the Redis shard owning that slot.
3. Redis returns `{1, 4}` in ~0.5 ms: allowed, 4 tokens left (arithmetic in the next section).
4. The request goes to search-service; the response carries `RateLimit-Remaining: 4`.
5. Five calls later in the same millisecond Redis returns `{0, 0}`; the gateway answers
   `429 Too Many Requests`, `Retry-After: 1`, `RateLimit-Remaining: 0`; search never sees it.

So: one in-memory lookup plus one Redis round trip, ~1 ms. `429`/`Retry-After` are standard
HTTP; `RateLimit-*` an IETF draft (or `X-RateLimit-*`); quota on successes lets clients slow early.

## Deep dive: atomicity and the token bucket in Lua
**How it works** · Naive GET-check-SET is a **read-modify-write race**: gateways A and B both read
99, both allow, both write 100; two requests pass, the limit is exceeded. Fixes:
- **`INCR`** is atomic. Fixed window: `INCR key`; if result is 1, `EXPIRE key 60`; allow if
  result <= limit. The pair is two commands, so a crash between leaves a key that never expires:
  run both in a script, or `SET key 0 EX 60 NX` first.
- **Lua script** for token bucket or sliding window: Redis runs commands on a single thread, so a
  script runs with nothing interleaved. Read-compute-write becomes one indivisible step and one
  round trip.

```lua
local capacity, rate, cost = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local t   = redis.call('TIME')                       -- server clock, no gateway skew
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local b      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or capacity            -- new key starts full
local ts     = tonumber(b[2]) or now
tokens = math.min(capacity, tokens + (now - ts) / 1000 * rate)
local allowed = 0
if tokens >= cost then tokens = tokens - cost; allowed = 1 end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)  -- idle buckets
return { allowed, math.floor(tokens) }
```

**Example** · `rl:search:k_42`, capacity 100, rate 10/s, cost 1; stored `{tokens: 3.0, ts: T}`:

```text
 call  now        refill               tokens before -> after   returns
 1     T+250 ms   0.25 s x 10 = +2.5   min(100, 5.5) -> 4.5     {1, 4}
 2-5   T+250 ms   +0 (same ms)         4.5 -> 3.5 -> 2.5 -> 1.5 -> 0.5   {1, 3}..{1, 0}
 6     T+250 ms   +0                   0.5 < 1, unchanged       {0, 0}  -> 429
 7     T+300 ms   0.05 s x 10 = +0.5   1.0 -> 0.0               {1, 0}
 each call: HSET tokens/ts, PEXPIRE ceil(100/10 x 1000) + 1000 = 11,000 ms
```

So: refill is computed lazily from elapsed time, no timer; idle 11 s, the key expires (a new key
starts full anyway), bounding memory (10 M keys x ~100 B ~ 1 GB). `TIME` in a writing script is
fine in modern Redis (effects are replicated). In **Redis Cluster** a script's keys must share a
hash slot: single-key is fine, else hash tags `rl:{user42}:a`.

## Deep dive: scaling and multi-region
**How it works** · **Shard by key** across the Redis cluster (hash slots); each request touches one
key, so it scales linearly. **Hot keys** (a huge tenant on one shard): a **local token lease**,
each gateway takes e.g. 50 tokens at once and spends them locally. A **replica per shard** for
failover; losing counters only resets some limits briefly.

**Example** · tenant `t_9` sends 20,000 req/s through 40 gateways. Per-request checks: 20,000
Redis calls/s on one shard. Leases of 50: 20,000 / 50 = 400 calls/s (50x fewer); imprecision: up
to 40 x 50 = 2,000 tokens sit leased on gateways that may not need them. Across 3 regions with a
1,000/min limit: independent limits allow up to 3,000/min; an even split (333 each) throttles
India at 333 while it sends 90% of traffic; traffic-weighted shares (900/50/50) fix that.

So: a cross-region round trip (tens to 100+ ms) blows the budget; **exact global limits and low
latency conflict** (CAP-style); most accept approximate.

| Approach | Accuracy | Latency / note |
|---|---|---|
| Independent per-region limits | Total can reach limit x regions | Local; simplest; fine if generous or traffic is regional |
| Split quota (limit / R, or traffic-weighted) | Under-allows if uneven | Local; rebalance shares periodically |
| Local counting + async sync | Approximately global, brief overshoot | Local; common at large platforms |
| Central global store | Exact | Cross-region RTT per request; usually unacceptable inline |
| Home-region routing (sticky by key) | Exact | Extra hop for far clients |

## Failures: fail open vs fail closed
**How it works** · Redis down or slow: **fail open** (allow; API up, temporarily unprotected) or
**fail closed** (reject; backends safe, API effectively down). Always a **timeout** of a few ms
plus a **circuit breaker** (ch. 13) so failures do not add timeout latency to every request, and
**two-layer limiting**: a coarse in-memory per-gateway limit (e.g. limit / gateways x 2) always
runs under the precise Redis limit.

**Example** · the Redis shard for `rl:search:*` and `rl:login:*` hangs for 20 s at 19:00:
1. Calls time out after 5 ms; after a burst of failures the breaker opens and requests skip Redis.
2. `GET /search` fails open, but the local layer caps `k_42` at 100 / 50 x 2 = 4/min per
   gateway: at most 200/min fleet-wide (2x the limit, bounded).
3. `POST /login` fails closed, or a strict local 5/min per IP: no brute-force window.
4. Paid quotas fail open, billing reconciled from logs; the breaker half-opens, limits resume.

So: the limiter degrades to "roughly right" instead of taking the API down. Latency budget: rule
lookup and key build µs, Lua round trip ~0.2–1 ms; ~1 ms total.

**Recap:** gateway + in-service limits, rules cached from a config store with hot reload; Redis
sharded by key with TTLs, one Lua script per check (`INCR` for fixed window); token bucket for
bursty APIs, sliding window counter for smooth limits; leases for hot keys; timeout + breaker.

> **Say it like this:** "If the counter store is unreachable I'd fail open for normal API limits,
> because the limiter shouldn't take the API down, but fail closed for login attempts, with a
> local per-gateway limit as a backstop either way."

## Dynamo-style KV store: requirements and API
**How it works** · `put`, `get`, opaque values up to ~1 MB; billions of keys, hundreds of TB;
**always writable** despite failing nodes or links (refusing "add to cart" loses money); p99.9 in
tens of ms; **tunable consistency**; add nodes without downtime, no master. So an **AP** design
(ch. 10). The Dynamo paper (2007) space: Cassandra, Riak, Voldemort (managed DynamoDB shares the
name but is leader-based inside).

```text
put(key, value, context) -> ok
get(key)                 -> (value or list of conflicting values, context)
delete(key, context)     -> ok        (a write of a tombstone)
```

**Example** · `get("cart:u7")` -> `(["book"], ctx [A:1])`; the client adds a pen and calls
`put("cart:u7", ["book","pen"], ctx [A:1])`. So: the **context** is opaque version metadata (a
vector clock) handed back on `put`, so the store can tell "update of what I read" from "concurrent
conflicting write".

## Partitioning and replication
**How it works** · **Consistent hashing** (ch. 07): nodes and keys hash onto a ring (0 to
2^128 - 1); a key belongs to the first node clockwise. **Virtual nodes** (e.g. 256 per machine)
even out load, let big machines own more, spread a dead node's load. A key lives on the first
**N** (typically 3) **distinct physical nodes** clockwise (prefer different racks/AZs): the
**preference list**; any node in it (or any node, forwarding) can coordinate.

**Example** · where `cart:u7` lives, N = 3:

```text
 0 --A1--B1--C1-----B2--A2--C2--x--C3--A3--B3-- 2^128 (wraps to 0)
                                ^ hash(cart:u7)
 walk right: C3 -> A3 -> B3   => preference list = [C, A, B]
 (had the walk hit C again before 3 nodes, it would be skipped: N distinct machines)
```

So: a lookup is local math, no directory. **Adding node D:** it picks tokens, joins via seeds,
gossip spreads them, it streams newly owned ranges from old holders, which then drop them; with
vnodes D takes a small slice from many nodes (~1/4 of keys in a 4-node ring), none overloaded.

## Quorums: tunable consistency, with a conflicting write
**How it works** · **N** replicas, a write succeeds after **W** acks, a read returns after **R**
responses. If **R + W > N**, every read set overlaps every write set in at least one node, so a read
reaches a replica with the latest acknowledged write.

**Example** · `cart:u7`, preference list [A, B, C], N=3, W=2, R=2; two shoppers on one account:

```text
 1 put ["book"] via A      A, B ack (W=2) -> ok; C gets it 30 ms later    v1 [A:1]
 2 P and Q both get        any 2 replicas -> v1, ctx [A:1]
 3 P put ["book","pen"]  ctx [A:1] via A -> v2 [A:2]       acked by A, B
   Q put ["book","mug"]  ctx [A:1] via C -> v3 [A:1,C:1]   acked by C, B
   compare v2/v3: A 2 > 1 but C 0 < 1 -> neither dominates -> CONCURRENT
   state: A {v2}   B {v2, v3} siblings   C {v3}
 4 get, R=2 from A and C   v2, v3 -> both returned + merged ctx [A:2,C:1]
 5 client merges ["book","pen","mug"], put via B -> v4 [A:2,B:1,C:1]
   v4 dominates v2 and v3; read repair replaces the siblings on A and C
```

So: any 2 of {A, B, C} contain a node holding each acked write, so step 4 cannot miss either one
(with R=1 a read of A alone would show only the pen). Under LWW instead, the later timestamp
(say v3) wins and the pen silently disappears.

| N=3 | Behaviour |
|---|---|
| W=2, R=2 | Balanced; overlap; tolerates 1 slow/failed replica on both paths |
| W=3, R=1 | Fast reads; writes fail if any replica is down |
| W=1, R=3 | Fast, available writes; reads must hear from all |
| W=1, R=1 | Fastest; no overlap, reads may be stale; a 1-ack write dies with its node |

Caveats: overlap is **not linearizability**; concurrent writes, sloppy quorums or LWW on skewed
clocks still surprise, so say "quorums improve freshness". A write that misses W is reported failed
but **not rolled back**: it may sit on some replicas and appear later, so treat failure as
"unknown outcome" and retry idempotently.

> **Common mistake:** "R + W > N, so it's strongly consistent." It only guarantees overlap of
> strict quorums; sloppy quorums, concurrent writes and clock-based LWW all break the stronger claim.

## Staying writable: sloppy quorum and hinted handoff
> **Black box:** a preference-list node is down -> write to the next healthy node with a **hint**
> and count its ack toward W (**sloppy quorum**); the stand-in hands it over when the owner
> returns (**hinted handoff**). E.g. B down: acks from A and D ("for B"). **Use when:** "always
> writable". **Cost:** overlap is lost (a read of A, B, C can miss D's copy); stand-in load; a
> stand-in dying before handoff leaves replicas low until anti-entropy.

## Conflict handling: last-write-wins vs vector clocks
**How it works** · Replicas can hold **concurrent** values, neither older. **Last-write-wins
(LWW):** highest timestamp wins (Cassandra, per cell). **Vector clocks:** `(node, counter)` pairs,
the coordinator bumps its own; if every entry of X <= Y's (missing = 0), X is an **ancestor**
(discard); else **concurrent**: keep both as **siblings** (traced in the quorum example).

**Example** · LWW with a skewed clock: node A's clock runs 200 ms fast. Write X via A at real
time 12:00:00.000 is stamped .200; write Y via B at real .100 is stamped .100. Every replica keeps
X: the later write Y is silently lost. Had X and Y been concurrent (same context), vector
clocks would have returned both as siblings.

So: LWW is simple, with tiny metadata, but **silently discards** one concurrent write and trusts
clocks: fine for "any recent value" data (a profile photo URL), bad for carts and counters.

| | LWW | Vector clocks + siblings |
|---|---|---|
| Detects concurrency / loses data | No / yes, silently | Yes / no, client merges |
| Client work / metadata | None / one timestamp | Merge siblings / grows with coordinators (truncated) |
| Clock dependency | Needs synced clocks | None (logical) |

Dynamo leaves semantic merge to the app (cart union; a deleted item can reappear, a known
trade-off). Truncating old clock entries can rarely produce a false "concurrent" (extra siblings,
not loss). **CRDTs** (counters, sets with built-in merge rules) are an alternative for some types.

> **Say it like this:** "LWW is simple but silently drops one of two concurrent writes and trusts
> wall clocks; vector clocks detect concurrency and hand both versions to the app to merge, which
> is right for a shopping cart."

## Repair and membership
> **Black box:** **Anti-entropy with Merkle trees**: a hash tree per key range (leaves hash keys,
> parents hash children); replicas compare roots and descend only into differing subtrees (1 bad
> key among millions = one root-to-leaf path). **Use when:** permanent drift (lost hint, node down
> for days, disk replaced), even unread keys. **Cost:** transfer ~ differences; tree upkeep.

**Read repair** (newest version written back to stale replicas during a `get`) only fixes keys
that are read; hinted handoff fixes short outages; Merkle catches the rest.

> **Black box:** **Gossip**: each second or so a node swaps its view (members, tokens, heartbeats)
> with a random peer; news spreads in ~O(log N) rounds, no master. "Suspect" after T s without a
> newer heartbeat, or adaptive **phi accrual** (Cassandra); **seed nodes** bootstrap joins and
> prevent split groups. **Cost:** an eventually consistent membership view.

Key distinction: "temporarily unreachable" (sloppy quorum, ring unchanged) vs "permanently
removed" (explicit admin membership change that moves ranges), so a flaky network never triggers
expensive rebalancing.

## Storage engine and deletes
> **Black box:** each node runs an **LSM tree** (ch. 06, as in Cassandra/RocksDB) for the
> write-heavy, point-read workload: commit log + memtable, flushed to immutable SSTables, merged by
> compaction; a **Bloom filter** per SSTable skips files that definitely lack the key. **Cost:**
> read/write amplification, compaction, small memory and false positives for filters.

**Tombstones:** removing a key outright lets a replica that missed the delete "repair" it back,
so a delete writes a versioned tombstone, kept for a grace period (Cassandra `gc_grace_seconds`,
default 10 days). A replica down longer resurrects **zombie** data: repair more often than that.

## Whole system: one put and get end to end
**How it works** · Every node is identical: request coordinator, gossip + failure detector,
anti-entropy (Merkle), hinted-handoff store, LSM engine; clients use a ring-aware library or any
node as coordinator.

**Example** · follow `put("cart:u7", v)` then `get("cart:u7")`, N=3, W=2, R=2:
1. Client library hashes the key -> preference list [C, A, B]; sends to C, which coordinates.
2. C makes the new version (bumps C in the vector clock) and sends it to C, A, B in parallel.
3. Each replica appends to its commit log, inserts into its memtable, acks. C and A ack ->
   W=2 -> ok to client; the message to B is lost (had B been down: sloppy quorum + hint).
4. Later each memtable flushes to an immutable SSTable; compaction merges old ones.
5. `get` from a client via A: A asks the replicas (all 3, or R plus spares for tail latency).
   Each checks memtable, then SSTables newest first: Bloom says "definitely not" for 5 of 6
   files, so one index lookup and one block read.
6. A and B answer first (R=2); B's old version is dominated -> drop it, return A's; read
   repair sends the newest version to B. Concurrent versions would come back as siblings.

So: no leader anywhere; every step is local math, a parallel fan-out, and a count of acks.

| Problem -> technique | Cost |
|---|---|
| Spread data -> consistent hashing + vnodes (~1/n keys move); survive loss -> N replicas on distinct racks/AZs | Ring metadata; N x storage |
| Freshness vs latency -> quorum R, W (R + W > N); writable in failure -> sloppy quorum + hints | Waiting on replicas; weaker overlap |
| Divergent writes -> vector clocks + merge, or LWW; drift -> Merkle, read repair | Merge logic or silent loss; upkeep |
| Membership -> gossip + phi accrual; ingest -> LSM + Bloom; deletes -> tombstones + grace | Eventual view; amplification; zombies if repair lags |

**Not this design** for multi-key transactions or linearizable reads (balances, uniqueness: use
Raft-based stores or distributed SQL), data that fits one primary (plain SQL or Redis), or
secondary indexes, joins, cross-key ranges (a KV store only answers "value for this key").

## In one minute
- Local limits multiply by server count: shared counters in sharded Redis, one atomic Lua
  script (or `INCR`) per check, rules cached at the gateway, about 1 ms.
- Exact global limits fight latency: local counting + async sync, or split quotas.
- Timeouts + breaker; fail open for general limits, closed for login; local backstop limit.
- Dynamo KV: consistent hashing + vnodes, N replicas, R + W > N for overlap (not linearizability).
- Sloppy quorum + hinted handoff keep writes available but weaken overlap; vector clocks detect
  concurrent writes (siblings), LWW silently drops one and trusts clocks.
- Merkle anti-entropy, read repair, gossip membership, LSM nodes, tombstones with a grace period.

## Go deeper
Full chapter 18: sections 18.1 (Lua atomicity, fail open vs closed), 18.2 Step 3 (quorums), 18.2 Step 5 (vector clocks)
