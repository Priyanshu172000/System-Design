# 12 — Load balancers, gateways and rate limiting: quick notes

These layers decide which server gets each request, steer around dead servers, and stop one
caller from drowning everyone else.

## 1. Why a load balancer

- A **load balancer (LB)** gives clients one stable address and forwards each request to a
  healthy server in a group (the **backend pool**). Think of a restaurant host seating guests.
- *e.g.* `api.quickbite.com` fronts 3 order-service servers. Asha's "Place order" goes to
  server 2, skipping server 3, which crashed a minute ago. A 4th server joins; phones notice
  nothing.
- Plain DNS can't do this. Phones cache old addresses (even dead ones), and DNS knows nothing
  about load.
- Cost: one extra hop (well under 1 ms) and one more thing to run. A single LB is a **SPOF**
  (single point of failure).
- Servers must be **stateless**: sessions live in a shared store, so any server can take any request.

## 2. L4 vs L7

- **L4** sees only addresses and ports. It picks a server when a connection opens, then passes
  bytes along blindly. Very fast, works for any TCP/UDP traffic.
- **L7** reads each HTTP request (path, headers) and picks a server per request. It can route by
  path and handle **TLS** (the encryption behind https), at the cost of more CPU.

```text
one connection, 3 requests      L4               L7
GET /v1/restaurants/r_77/menu   -> server 2      -> restaurant-service
GET /img/r_77/biryani.jpg       -> server 2      -> image servers
GET /v1/orders/o_90311          -> server 2      -> order-service
```

- **gRPC gotcha:** gRPC sends many requests over one long-lived connection. So L4 pins them all to
  one server (90% CPU, the rest idle). Move gRPC to L7.
- Common setup: L4 at the edge, spreading connections over a fleet of L7 proxies.

## 3. Picking a server: algorithms

| Algorithm | How | Use when |
|---|---|---|
| **Round robin** | A, B, C, A, ... | Same-size servers, similar requests |
| **Weighted round robin** | A big server gets 3 for every 1 | Mixed sizes; send 5% to a new version |
| **Least connections** | Fewest open connections | Long or uneven requests |
| **IP hash** | `hash(IP) mod N` | Crude "same user, same server"; breaks when N changes |
| **Consistent hashing** | Servers on a ring; a key goes to the next one clockwise | Server holds per-key data (a cache) |
| **Power of two choices** | Pick 2 at random, use the less busy | Many LBs at once; the usual good default |

- *e.g.* servers A, B, C have 12, 3 and 40 requests in progress. Ten LBs using "least
  connections" all pick B at once and swamp it (a **herd**). With power of two, LB-1 samples
  {A, C} and picks A; LB-2 samples {B, C} and picks B. Load spreads.
- Consistent hashing: adding a server moves only ~1/N of keys. With `mod N`, almost all move.

## 4. Health checks, draining, sticky sessions

- **Active check:** the LB calls `GET /healthz` every ~5 s. 3 failures in a row: removed. 2
  successes: back in. The thresholds stop it **flapping** in and out.
- **Passive check:** watch real traffic. *e.g.* five errors in a row: eject for a cool-down. Use both.
- **Liveness** = "is it stuck? restart it". **Readiness** = "can it take traffic now?" Trap:
  if readiness checks the shared DB, one 10 s DB blip fails every server, and the LB drops the
  whole pool.
- **Draining:** before removing a server, send it no new requests and let current ones finish.
  *e.g.* Asha's in-flight checkout completes; after 30 s, anything still open is closed.
- **Sticky sessions** (always send a user to the same server) cause uneven load, and sessions
  die with the server anyway. Keep sessions in Redis instead. Use stickiness only as a speed hint.

## 5. Keeping the LB itself alive

- **Active-passive pair:** LB-1 holds a **floating IP** (an address that can move between
  machines). LB-2 watches it with heartbeats. If LB-1 dies, LB-2 takes the IP in about 1–3 s.
- Cloud LBs already run as a fleet across zones.
- Across regions: **DNS steering** (slowed by caching), **anycast** (one IP announced from many
  sites; the nearest one answers), **GSLB** (smart DNS that sends you to the closest healthy
  region). Failover takes seconds to minutes.

## 6. Reverse proxy, API gateway, service mesh

- A **reverse proxy** stands in front of servers. It handles TLS, compression, caching, size
  limits and static files.
- It also **buffers slow clients**. *e.g.* rider `rd_5521` uploads a 5 MB photo over 20 s on a
  weak network. The proxy collects it all, then hands it over in milliseconds. The app's thread
  isn't stuck waiting.
- **API gateway:** the front door for **outside** traffic. It checks login once, enforces
  per-user quotas, routes `/v1/orders/*` to order-service, and adds a request id.
- **Service mesh:** handles calls **between your own services** via a **sidecar**, a small proxy
  next to each service. It adds encryption (**mTLS**), retries, timeouts and metrics with no app
  code. Cost: an extra hop (~1 ms or less) on every call.
- They stack: internet → L4 LB → api-gateway → services with sidecars. Keep the gateway thin: no
  business logic.
- **Service discovery:** a **registry** maps a name to live addresses. *e.g.*
  `payment-service → [10.0.3.17, 10.0.3.22, ...]`. The caller picks one itself (client-side), or
  calls a name and a proxy picks (server-side, like a Kubernetes Service).

## 7. Rate limiting: why and where

- **Rate limiting** = cap how many requests one caller makes per time window.
- Why: stop abuse (password guessing), fairness, cost, protect the DB, paid plans.
- Where, all stacked: the app greys out "Resend OTP" for 30 s (polite, not trusted). The gateway
  caps 100 requests/min per user (the main point). user-service allows 5 OTP SMS per phone per hour.
- Limit by user or API key when known; by IP for anonymous traffic.

## 8. Rate-limiting algorithms (rule: 100 per 60 s)

| Algorithm | How | Catch |
|---|---|---|
| **Fixed window** | One counter per clock minute | 100 at 12:00:59 + 100 at 12:01:00 = **200** in ~1 s |
| **Sliding log** | Store each request's time; count the last 60 s | Exact, but memory grows with the limit |
| **Sliding counter** | Last window's count × overlap share + this window's | Two numbers, small error; popular |
| **Token bucket** | Bucket of b tokens refills at r/s; each request takes 1 | Allows a burst, then a steady rate |
| **Leaky bucket** | Queue drained at a fixed rate | Smooth output, but adds waiting |

- Sliding counter, 15 s into a minute with last minute 80 and this one 30:
  `80 × 45/60 + 30 = 90 < 100`, so allow.
- Token bucket: b = 20, r ≈ 1.67/s. *e.g.* Asha opens the app; its 20 calls pass at once. Call
  21 is told to wait 0.6 s.
- Leaky bucket: *e.g.* the SMS partner allows 10/s, so a queue of 50 drains at 10/s.

## 9. Rate limiting across many servers

- 20 gateways each allowing 100 locally let a user do 2,000. So the count lives in **Redis**
  (a shared in-memory store).
- Never "read, check, write": two gateways both read 99, and both allow. Use atomic `INCR`:
  the first gets 100 (allow), the second gets 101 (reject).
- Set the expiry in the same atomic step (`MULTI`/`EXEC` or a **Lua script**). Otherwise a crash
  leaves a key that never expires, and Asha is blocked forever.
- If Redis is down: **fail open** (allow) for fairness limits; **fail closed** (reject) for
  logins and paid APIs.

## 10. Telling clients, and load shedding

- Over the limit: **429 Too Many Requests** with `Retry-After: 12`, plus `X-RateLimit-Remaining` headers.
- **Load shedding** = the server rejects work because **it** is overloaded, whatever the caller
  did. It returns **503**.
- *e.g.* a viral promo: every user is within limits but order-service is swamped. Drop
  recommendations (503) and keep `POST /v1/orders` working.
- Clients should back off with random jitter, not retry instantly.

## Remember these 5

1. An LB gives one address over stateless servers, and must itself be redundant.
2. L4 balances connections; L7 balances requests (needed for gRPC).
3. Power of two choices for general load; consistent hashing when a server holds per-key data.
4. Token bucket = burst + steady rate; fixed window lets 2x through at the boundary.
5. Rate limiting (429) protects fairness; load shedding (503) protects the server.
