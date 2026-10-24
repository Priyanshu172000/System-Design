# 12 — Load balancers, gateways and rate limiting
*Why it matters:* these layers decide which server gets a request, route around dead ones, and
stop one caller from drowning everyone; they come up in almost every design conversation.

## Why a load balancer exists
**How it works** · Clients know one address; a **load balancer (LB)** accepts connections on it
and forwards each request to a healthy server in the **backend pool** (upstream, target group).

**Example** · `api.quickbite.com` fronts 3 stateless `order-service` instances at the dinner
peak. Asha (`u_1842`) taps "Place order"; the LB sends it to backend 2, skipping backend 3 that
crashed a minute ago; a 4th instance joins and phones see no change. With DNS round robin,
phones would keep cached IPs, including dead backend 3, and DNS knows nothing about load.

So: one stable address gives horizontal scaling, failure hiding, zero-downtime deploys, and one
place for TLS/limits/logging. Costs: an extra hop (well under 1 ms in a DC), one more thing to
run, a **SPOF** if single. Backends must be **stateless** (sessions in a shared store).

## L4 vs L7
**How it works** · **L4** sees only the 5-tuple (IPs, ports, protocol): it picks a backend when a
TCP connection opens and forwards bytes for its whole life, usually passing TLS through. **L7**
terminates the connection, parses each HTTP request, and picks a backend per request.

**Example** · Asha's app sends three requests on one HTTP/2 connection.
```text
GET /v1/restaurants/r_77/menu   L4: -> backend 2 | L7: -> restaurant-service (node A)
GET /img/r_77/biryani.jpg       L4: -> backend 2 | L7: -> static pool (node X)
GET /v1/orders/o_90311          L4: -> backend 2 | L7: -> order-service (node B)
```
So: L4 pinned everything to one backend because it saw one connection; L7 routed by path. This
is the **HTTP/2 / gRPC gotcha** (one node at 90% CPU, rest idle), a classic reason to move gRPC
to L7. L4: very fast, any TCP/UDP, TLS passthrough (cloud network LB). L7: more CPU, HTTP family
only, terminates TLS, can rewrite (NGINX, HAProxy, Envoy, cloud application LB). Common stack:
L4 at the edge over a fleet of L7 proxies.

## Load-balancing algorithms
**How it works** · "Which healthy backend for this request?"

| Algorithm | Mechanism | Use when / watch out |
|---|---|---|
| **Round robin** | A, B, C, A, ... | Identical servers, uniform requests; bad if some are 100x heavier |
| **Weighted RR** | Weight 3 gets 3 per 1 for weight 1 | Mixed machine sizes, canary percentages |
| **Least connections** | Fewest open connections | Long/variable requests; approximate across many LBs |
| **Least response time** | Lowest recent latency | A node failing in 1 ms looks "fastest" unless errors count |
| **IP hash** | `hash(IP) mod N` | Crude stickiness; NAT piles up; changing N remaps nearly all |
| **Consistent hashing** | Nodes (many **virtual nodes**) on a hash ring; key walks clockwise | Per-key state (cache, shards); add/remove moves only ~1/N of keys |
| **Power of two choices** | 2 random backends, take the less loaded | Many LB instances; avoids the **herd effect** |

**Example** · Backends A, B, C have 12, 3 and 40 in-flight requests.
1. Round robin: whoever is next, even C with 40. Weighted 3:1 (A:B): A, A, A, B, A, ...
2. Least connections: B. But 10 LBs with the same stale view all pick B at once: a herd.
3. Power of two: LB-1 samples {A, C} -> A (12 < 40); LB-2 samples {B, C} -> B. Load spreads.
4. Consistent hashing, ring 0..99 for illustration: A at 20, B at 50, C at 80. `menu:r_77`
   hashes to 63 -> clockwise -> C. Add D at 60: only keys in (50, 60] move (C -> D); 63 stays
   on C. With `mod N`, going from 3 to 4 nodes remaps most keys.

So: pick by workload. With n balls in n bins, max load is about `log n / log log n` with one
random choice, about `log log n` with two; many proxies use it as "least request". The ring is
a sorted array of hashes; lookup is `lower_bound` with wrap-around.

> **Common mistake:** answering "round robin" to every LB question. Say why: "request cost
> varies, so least-request with power of two choices; the cache tier uses consistent hashing."

## Health checks, draining, sticky sessions
**How it works** · **Active checks** call `GET /healthz` every ~5 s; **passive** (**outlier
detection**) ejects on real errors; **draining** lets in-flight work finish before removal.

**Example** · `order-service` backend 2 during a bad deploy, then a scale-down.
```text
20:00:00 /healthz fails (1)   20:00:05 fails (2)   20:00:10 fails (3) -> removed
20:00:25 succeeds (1)         20:00:30 succeeds (2) -> re-added (no flapping)
20:00:40 passive: five 5xx in a row on real traffic -> ejected for a cool-down
21:00:00 scale-down: backend 2 "draining", no new requests; app got SIGTERM
21:00:02 Asha's in-flight checkout finishes; WebSocket clients told to reconnect
21:00:30 drain timeout: anything still open is closed; instance exits
```
So: use both styles (passive needs no extra traffic, but real users hit the failure first).
- **Liveness** = "stuck? restart it" (orchestrator). **Readiness** = "can serve now?" (LB). Trap:
  readiness that checks the shared DB fails everywhere during a 10 s DB hiccup and the LB drops
  the whole pool. Keep readiness about the instance; handle deps with timeouts and fallbacks.
- **Sticky sessions** (cookie `lb_backend=2` or IP hash) exist because sessions sit in local
  memory. They cause uneven load, lose sessions on crash anyway, starve new servers, slow drains.
  Put sessions in Redis or a signed token; use affinity only as a **performance hint** (warm
  cache via consistent hashing on user ID), never for correctness.

## Making the LB itself redundant
**How it works** · **Active-passive pair**: active holds a **floating IP (VIP)**; passive
heartbeats it (VRRP). **Active-active** uses both, but each must absorb the other's load.
Managed cloud LBs are already a multi-AZ fleet.

**Example** · VIP `10.0.0.100` is held by LB-1. At 20:15:00 LB-1 loses power; heartbeats stop;
about 1-3 s later LB-2 announces "10.0.0.100 is at my MAC now" and forwards. Phones keep the
same address; a few in-flight connections reset and retry. So: failover in seconds.

> **Black box:** global steering: **DNS LB** (per-region answers, limited by cached **TTL**),
> **anycast** (one IP announced from many sites via BGP; nearest wins; best for short connections),
> **GSLB** (smart DNS answering the closest healthy region). **Use when:** multi-region or
> surviving a region loss. **Cost:** failover takes seconds to minutes, never instant for everyone.

## Reverse proxy roles
**How it works** · A **forward proxy** fronts clients; a **reverse proxy** fronts servers (an L7
LB is one that also spreads load). Jobs: **TLS termination**, compression, caching, size limits,
hiding topology, static files, and **buffering slow clients**.

**Example** · Rider `rd_5521` on a weak link uploads a 5 MB delivery photo over 20 s. Without a
proxy, a `rider-service` worker thread is tied up for 20 s. A non-blocking proxy reads all 5 MB
cheaply, hands it to the app in one fast local burst (thread free in milliseconds), then absorbs
the response and trickles it back at the rider's speed. So: thousands of slow clients cost the
app almost nothing; this also blunts **slowloris**.

## API gateway vs LB vs service mesh
**How it works** · **LB**: spread load, skip dead nodes; no idea who the user is. **API gateway**:
L7 front door for external (**north-south**) traffic: auth once, per-key quotas, routing,
aggregation, transformation, request IDs, CORS. **Service mesh**: internal (**east-west**) calls
via a **sidecar** proxy per instance, configured by a **control plane**.

**Example** · Follow Asha's `POST /v1/orders` through the stack.
```text
phone -> L4 LB -> api-gateway: validate JWT, quota ok, add X-User-Id: u_1842
                  and X-Request-Id: req-7f3a, route /v1/orders/* -> order-service
order-service -> localhost sidecar ==mTLS==> payment-service sidecar -> payment app
sidecar applies timeout + retry policy, emits metrics and a trace span; no app code
```
So: they stack (internet -> L4 LB -> gateway fleet -> services with sidecars). Keep the gateway
thin: business logic in it (checking a wallet balance) makes a shared bottleneck every team
must redeploy.

> **Black box:** mesh sidecars give **mTLS** with auto-rotated certs, central retries/timeouts,
> circuit breaking, per-request LB, telemetry, traffic shifting, all without app changes.
> **Use when:** many services need uniform security and observability. **Cost:** a hop per call
> (about 1 ms or less, adds up in deep chains), memory per pod, operational complexity.

## Service discovery
**How it works** · Instances churn, so hard-coded IPs break. A **service registry** (Kubernetes
API, Consul, etcd, ZooKeeper) maps name -> live instances; registration on start, removal on
deregister or lease expiry.

**Example** · `order-service` needs `payment-service` during the peak.
1. Registry: `payment-service -> [10.0.3.17, 10.0.3.22, 10.0.4.9]`. **Client-side**: the
   caller's library gets the list, does power-of-two, calls 10.0.3.22.
2. Autoscaler kills 10.0.3.17; its lease expires and it drops from the list.
3. **Server-side / DNS**: order-service calls `payment.default.svc.cluster.local`; a Kubernetes
   **Service** virtual IP forwards to live pods. A client that cached 10.0.3.17 fails.

So: client-side = no extra hop but a library per language; server-side = simple client, one
more hop; DNS caching is the usual weakness. A mesh is server-side discovery inside the sidecar.

## Rate limiting: why and where
**How it works** · Cap requests per caller (user, key, IP, tenant) per window, for abuse
(credential stuffing), fairness, paid downstream cost, protecting a DB, and plans (free 100/min).

**Example** · Limits that stack on QuickBite's OTP login: **client SDK** greys out "Resend OTP"
for 30 s (polite, untrusted); **edge/gateway** (main point, before expensive work) caps per IP
for botnet floods and 100 req/min per user; **service**: `user-service` allows 5 OTP sends per
phone per hour (only it knows the rule); **outbound**: stay inside the SMS partner's quota.
So: key on user/API key when known, IP for anonymous traffic (NAT, rotation); limits stack.

## Rate-limiting algorithms
**How it works** · Rule throughout: 100 requests per 60 s per user.

**Fixed window**: one counter per `floor(now/60)`, reject above 100; `INCR` + expiry.

**Example** · Asha's script hammers the menu API around a window boundary.
```text
key rl:u_1842:12:00  12:00:59.0-.9  100 requests -> counts 1..100, all allowed
                     12:00:59.95    request 101  -> count 101 > 100, rejected
key rl:u_1842:12:01  12:01:00.0-.9  100 requests -> counts 1..100, all allowed
rolling 60 s ending 12:01:00.9 holds 200 accepted requests, sent in under 2 s
```
So: up to **2x** the limit around a boundary. Fine for coarse quotas ("10,000 per day").

**Sliding window log**: timestamp per accepted request (sorted set); drop those older than
`now - 60s`; allow if count < 100. **Example** · at 12:01:30 it evicts entries at or before
12:00:30 and counts the rest, so the 200 burst above is impossible. Exact, but memory grows with
the limit; log only accepted requests or a hammering client never recovers. For small precise
limits (logins).

**Sliding window counter**: previous + current window counts, assuming even spread:
`weight = (window - elapsed_in_current) / window`;
`estimate = previous_count * weight + current_count`; allow if `estimate < limit`.

**Example** · Same rule, two situations.
1. Normal: prev = 80, curr = 30, 15 s in: 80 * 45/60 + 30 = 60 + 30 = 90 < 100 -> allow.
2. The burst: prev (12:00) = 100, all at :59. At 12:01:00.5: 100 * 59.5/60 + 0 = 99.17 ->
   allow (curr = 1). At 12:01:00.6: 100 * 59.4/60 + 1 = 99 + 1 = 100, not < 100 -> reject.
3. At 12:01:30: 100 * 30/60 + 1 = 51 -> allowed, though the true rolling count is 101.

So: two integers, the 200 burst cut to 101; a small error when the old window was bunched at its
end (step 3, where an exact log would still reject). Popular in production.

**Token bucket**: capacity **b** (burst), refill **r**/s, one token per request. Store `tokens` +
`last_refill_time` and refill lazily: `tokens = min(b, tokens + (now - last) * r)`; allow if
`tokens >= 1`, else reject with retry after `(1 - tokens) / r`.

**Example** · b = 20, r = 100/60 ≈ 1.667/s; Asha opens the app after an idle hour.
```text
t=0.0   tokens 20 (full); page load fires 20 calls -> all allowed, tokens 0
t=0.0   call 21: 0 < 1 -> 429, retry after (1 - 0) / 1.667 = 0.6 s
t=0.6   tokens = 0 + 0.6 * 1.667 = 1.0 -> allow, tokens 0
t=3.0   tokens = 0 + 2.4 * 1.667 = 4.0 -> allow, tokens 3.0
t=30.0  tokens = min(20, 3 + 27 * 1.667 = 48) = 20   (capped at b)
```
So: a burst of b, then r sustained; any interval T allows at most `b + r*T` (one minute: 20 +
100 = 120). Two knobs, cost weights (a report export = 10 tokens). Very widely used.

**Leaky bucket**: as a **queue/shaper**, a fixed-size FIFO drained at constant r (full -> drop).
**Example** · SMS to a partner allowing 10/s, queue size 50: a burst of 60 -> 50 queued, 10
dropped; the last queued SMS leaves 5 s later (50 / 10). Perfectly smooth output, adds queueing
delay. As a **meter** it is a token bucket upside down.

> **Common mistake:** claiming fixed window enforces "100 per minute". It enforces 100 per
> calendar minute; a rolling 60 s can see 200.

## Distributed rate limiting with Redis
**How it works** · 20 gateways each enforcing 100/min locally let a user through 2,000, so state
goes in **Redis** (sub-ms, single-threaded, each command atomic).

**Example** · Two `api-gateway` instances, Asha at 99 of 100.
```text
naive: gw-1 GET -> 99       gw-2 GET -> 99        (both see room)
       gw-1 SET 100, allow  gw-2 SET 100, allow   -> 101 passed, counter says 100
INCR:  gw-1 INCR -> 100, allow                    gw-2 INCR -> 101 > 100, reject
```
So: never check-then-act; `n = INCR key`, `EXPIRE` on first hit, reject if `n > 100`.
- A crash between `INCR` and `EXPIRE` leaves a never-expiring key (user blocked forever): use
  `MULTI`/`EXEC` or a **Lua script** (atomic). Token bucket = Lua doing `HMGET` -> refill ->
  decide -> `HSET` -> `PEXPIRE`. Sliding log = sorted set with `ZREMRANGEBYSCORE`, `ZCARD`,
  `ZADD` in a script. Use Redis `TIME` for one clock; shard by user ID (one bucket, one shard).
- Redis down: **fail open** (fairness limits) or **fail closed** (logins, paid APIs); a few-ms
  timeout on the call either way.
- Cheaper variants: local-only limit/N per instance (wrong if traffic is uneven), local counts
  synced every ~100 ms (small overshoot), **token leasing** in batches of 10 (tokens can strand).
  Overshoot like 105/100 is fine for most APIs; money and security want exact.

## Telling clients, and load shedding
**How it works** · Return **429 Too Many Requests** with `Retry-After` (standard). The
`X-RateLimit-Limit / -Remaining / -Reset` headers are a convention (`Reset` may be epoch or
seconds; an IETF draft defines `RateLimit` / `RateLimit-Policy`); send them on successes too.
**Load shedding** rejects based on the server's own load, not the caller's allowance.

**Example** · Two rejections at 20:30 on a dinner-peak night.
```text
Asha's script, call 101 this minute:
  429 Too Many Requests  Retry-After: 12  X-RateLimit-Limit: 100  X-RateLimit-Remaining: 0
Viral promo, every user within limits, order-service in-flight over its threshold:
  GET /v1/recommendations -> 503 Service Unavailable, Retry-After: 5   (shed first)
  POST /v1/orders         -> 201 Created                               (protected)
```
So: **rate limiting** = "is this caller over their allowance?", fires even when idle, 429.
**Load shedding** = "is the server over capacity now?" (CPU, queue, in-flight, latency), 503.
Need both. Shed by priority (background work first, checkout and login last) and early at the
front door. Clients back off with jitter (chapter 13).

## In one minute
- An LB gives one stable address over stateless servers; make it redundant (VIP, DNS/anycast).
- L4 balances connections blind to content; L7 balances requests, routes, terminates TLS.
- Round robin if uniform, power-of-two least-request if variable, consistent hashing per key.
- Active + passive health checks; readiness about the instance; drain; stickiness only a hint.
- Gateway = external front door; mesh = sidecars for internal calls; registry for discovery.
- Fixed window (2x burst), sliding log (exact, heavy), sliding counter (weighted), token
  bucket (burst + rate), leaky bucket (smooth, delays).
- Distributed limits need atomic Redis ops (INCR, Lua) and a fail-open/closed decision; 429 for
  limits, 503 for shedding.

## Go deeper
Full chapter 12: sections 12.3 (LB algorithms), 12.8 (gateway vs mesh), 12.11 (rate-limiting algorithms), 12.12 (Redis limiting)
