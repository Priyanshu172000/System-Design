# 12 — Load balancers, gateways and rate limiting

Once you have more than one server, something has to decide which server gets each request,
notice when a server dies, and stop any single client from drowning everyone else. This
chapter covers the three layers that do that work: load balancers, reverse proxies / API
gateways / service meshes, and rate limiters. These words come up in almost every design
conversation, and people often blur them together. After this chapter you will not.

## 12.1 Why a load balancer exists

Start with the pain. You have one web server. It handles roughly 2,000 requests per second
before CPU hits 100%. Traffic grows to 6,000 requests per second. You buy two more servers.
Now you have three machines and one problem: clients only know one address.

Options without a load balancer:

- Give clients three addresses and let them pick. Clients pick badly, cache dead addresses,
  and you cannot add a fourth server without updating every client.
- Put all three IPs in DNS. Better, but DNS answers are cached for minutes to hours, so a dead
  server keeps receiving traffic, and DNS knows nothing about how busy each server is.

A **load balancer (LB)** is a component that sits in front of a group of servers (the
**backend pool**, or **upstream**, or **target group**), accepts every client connection on
one stable address, and forwards each request to one healthy backend.

```text
                         +-------------------+
   clients  ---------->  |   Load balancer   |   one public address, e.g. api.example.com
                         +---------+---------+
                                   |
                 +-----------------+-----------------+
                 |                 |                 |
           +-----v-----+     +-----v-----+     +-----v-----+
           | backend 1 |     | backend 2 |     | backend 3 |   (stateless app servers)
           +-----------+     +-----------+     +-----------+
```

What it gives you:

| Benefit | What it means |
|---|---|
| Horizontal scaling | Add servers behind the LB; clients see no change |
| Failure hiding | LB stops sending to dead servers (health checks, 12.4) |
| Zero-downtime deploys | Drain one server, upgrade it, put it back (12.4) |
| One place for cross-cutting work | TLS, compression, logging, limits (12.7) |

The trade-off: the LB is an extra network hop (usually well under a millisecond inside a data
center), one more thing to operate, and if you have only one of it, a **single point of
failure (SPOF)** — a component whose failure takes the whole system down. Section 12.6 fixes
that.

The backends must be **stateless** for this to work cleanly: any backend can serve any
request because user state (sessions, carts) lives in a shared store such as a database or a
cache, not in one server's memory. Chapter 03 covered why.

## 12.2 L4 vs L7 load balancing

The "L" numbers come from the OSI network model (chapter 01). Two layers matter here:

- **Layer 4 (transport)**: TCP and UDP. At this layer you see IP addresses and ports, and
  nothing about the content.
- **Layer 7 (application)**: HTTP, gRPC, WebSocket. At this layer you see the URL path,
  headers, cookies, method, and the body.

An **L4 load balancer** forwards TCP connections (or UDP packets) based on the 5-tuple
(source IP, source port, destination IP, destination port, protocol). It picks a backend when
a connection opens and then shovels bytes for the life of that connection. It never parses
HTTP. With TLS, it typically passes the encrypted bytes straight through.

An **L7 load balancer** terminates the client's connection, reads the HTTP request, decides
where to send it, and opens (or reuses) its own connection to a backend. Because it
understands the request, it can route `/api/*` to one pool and `/images/*` to another.

```text
L4: one client TCP connection is pinned to one backend for its whole life

   client ==TCP==> [ L4 LB ] ==same TCP stream (bytes/packets forwarded)==> backend 2

L7: LB ends the client connection, reads each request, picks a backend per request

   client ==TLS/HTTP==> [ L7 LB ] --GET /api/users --> api pool (backend A)
                                  --GET /img/1.png --> static pool (backend X)
                                  --GET /api/cart  --> api pool (backend B)
```

| | L4 load balancer | L7 load balancer |
|---|---|---|
| Sees | IPs, ports, protocol | Everything L4 sees, plus path, host, headers, cookies, body |
| Routing decisions | Per connection | Per request |
| TLS | Usually passthrough (backend decrypts) | Usually terminates TLS |
| Content-based routing | No | Yes (`/api` vs `/static`, host-based, header-based) |
| Can modify requests | No | Yes (add headers, rewrite paths, compress) |
| Speed / cost | Very fast, cheap per connection | More CPU per request (parsing, TLS) |
| Protocols | Any TCP/UDP (databases, game servers, MQTT) | HTTP-family (HTTP/1.1, HTTP/2, gRPC, WebSocket) |
| Typical products | Cloud "network load balancer", kernel-level LBs | NGINX, HAProxy (HTTP mode), Envoy, cloud "application load balancer" |

An important subtlety with **HTTP/2 and gRPC**: they multiplex many requests over one
long-lived TCP connection. An L4 balancer balances connections, so if a client opens one
connection, every request on it goes to the same backend. With a few big clients, load becomes
very uneven. An L7 balancer that understands HTTP/2 can spread individual requests across
backends. This is a common reason teams move gRPC traffic to L7 balancing.

> **Say it like this:** "L4 balances connections and is blind to content, so it is fast and
> protocol-agnostic. L7 balances requests and can route on path or headers, terminate TLS, and
> spread HTTP/2 streams, at the cost of more CPU per request."

A common architecture uses both: an L4 balancer at the edge spreads raw connections across a
fleet of L7 proxies, which then do the smart per-request routing.

## 12.3 Load-balancing algorithms

The algorithm answers: "for this request, which healthy backend?" Each one fits a different
situation.

### Round robin

Send request 1 to backend A, 2 to B, 3 to C, 4 to A again. Stateless and simple.

Good when backends are identical and requests cost roughly the same. Bad when some requests
are 100x heavier than others (one unlucky server gets several heavy ones in a row) or when
servers differ in size.

### Weighted round robin

Each backend gets a weight proportional to its capacity. A server with weight 3 gets three
requests for every one sent to a weight-1 server. Use it when the pool mixes machine sizes, or
to send a small share of traffic to a new version (a canary, chapter 14).

### Least connections

Send the request to the backend with the fewest open connections right now. Open connections
are a rough proxy for "how busy". Good for long or variable-length requests (WebSockets, file
uploads, slow reports). Needs the LB to track per-backend counts, which is easy for a single LB
but approximate when many LB instances each see only their own connections.

### Least response time

Pick the backend with the lowest recent latency (often combined with fewest active requests).
Adapts to a server that is slow because of a noisy neighbor or garbage collection. Risk: a
server that fails fast (returns errors in 1 ms) looks "fastest" and attracts all traffic. Good
implementations count errors as bad, not fast.

### IP hash

Compute `hash(client IP) mod N` and always send that client to the same backend. Gives a weak
form of stickiness without cookies. Problems: many users behind one corporate NAT share an IP
and pile onto one server, and when N changes, `mod N` remaps almost every client.

### Consistent hashing

Fixes the `mod N` remapping problem. Place backends on a ring of hash values (usually many
**virtual nodes** per backend for evenness). Hash the key (user ID, cache key) onto the ring
and walk clockwise to the first backend.

```text
                hash ring (0 .. 2^32-1)
                    B#1
              A#2 .       . C#1
                .           .         key k hashes here -> walk clockwise -> first node
             C#2 .    ring    . A#1
                .           .
              B#2 .       . A#3         adding/removing one backend moves only ~1/N of keys
                    C#3
```

When one backend is added or removed, only about 1/N of the keys move instead of nearly all of
them. Use it when the backend holds per-key state you want to reuse: cache servers (chapter
08), sharded stores (chapter 07), or a service that keeps a warm in-memory model per tenant.
DSA bridge: the ring is a sorted array of hashes, and "walk clockwise" is a `lower_bound` with
wrap-around.

### Power of two choices

Pick two backends at random, then send the request to the less loaded of the two. That is it.

Why it matters: "always pick the least loaded" sounds best, but with many LB instances each
acting on slightly stale load data, they all pick the same "least loaded" server at the same
moment and stampede it (a **herd effect**). Pure random avoids the herd but leaves some servers
noticeably more loaded than others. Picking the better of two random choices gets most of the
benefit of least-loaded with almost none of the herding. The classic balls-into-bins result:
with n balls into n bins, the most loaded bin holds about `log n / log log n` balls with one
random choice, but only about `log log n` with two choices — an exponential improvement for one
extra sample. Many modern proxies use this as their default "least request" strategy.

### Which one when

| Situation | Reasonable choice |
|---|---|
| Identical servers, short uniform requests | Round robin |
| Mixed server sizes, or canary percentages | Weighted round robin |
| Long-lived or highly variable requests | Least connections / least request |
| Many LB instances, want least-loaded without herding | Power of two choices |
| Backend holds per-key state (cache, tenant data) | Consistent hashing on that key |
| Want crude stickiness without cookies | IP hash (beware NAT and resizing) |

> **Common mistake:** Saying "round robin" as the answer to every LB question. Interviewers
> want to hear why: "requests vary a lot in cost, so I'd use least-request with power of two
> choices; the cache tier uses consistent hashing so keys stay on the same node."

## 12.4 Health checks and connection draining

### Health checks

The LB must know which backends are alive. Two styles:

- **Active health checks**: the LB periodically calls each backend, e.g. `GET /healthz` every
  5 seconds. After, say, 3 consecutive failures the backend is marked unhealthy and removed;
  after 2 consecutive successes it is added back. The thresholds prevent **flapping** (rapidly
  toggling in and out).
- **Passive health checks** (also called **outlier detection**): the LB watches real traffic.
  If a backend returns five 5xx errors in a row or its connections keep timing out, it is
  ejected for a cool-down period. No extra traffic, but it only notices problems after real
  users hit them.

Most production setups use both.

What should `/healthz` check? There are two kinds of endpoint:

| Endpoint | Question it answers | Used by |
|---|---|---|
| **Liveness** | "Is the process stuck or dead? Should it be restarted?" | Orchestrator (k8s restarts it) |
| **Readiness** | "Can this instance serve traffic right now?" | LB / service routing |

A trap: if readiness checks deep dependencies (database, cache, other services), then when the
shared database has a hiccup, every backend fails its check at once and the LB removes the
entire pool. A small outage becomes a total outage. Keep readiness mostly about the instance
itself (warmed up, not overloaded, config loaded), and let the request path handle dependency
failures with timeouts and fallbacks (chapter 13).

### Connection draining

When you deploy or scale down, you remove a backend. If you just kill it, in-flight requests
fail. **Connection draining** (also called **deregistration delay**) means:

```text
t=0    mark backend 2 "draining": LB sends it NO new requests
t=0..  requests already in progress on backend 2 are allowed to finish
t=30s  (drain timeout) whatever is still open is closed; backend 2 shuts down
```

The app should cooperate: on a shutdown signal (SIGTERM), stop accepting new work, finish
in-flight requests, then exit. Long-lived connections (WebSockets) need the server to tell
clients to reconnect, since they would otherwise never "finish".

## 12.5 Sticky sessions, and why to avoid them

**Sticky sessions** (session affinity) mean the LB sends every request from the same user to
the same backend, usually by setting a cookie such as `lb_backend=2`, or by IP hash.

Why people want them: the app stored the user's session in local memory, and moving the user
to another server would "log them out".

Why to avoid them:

- **Uneven load**: a few heavy users stuck to one server overload it while others idle.
- **Failure loses state**: when that server dies, every session on it is gone anyway.
- **Scaling and deploys get harder**: new servers get no existing users; draining a server
  means waiting for its sessions to expire.
- **It hides a design smell**: the server is stateful when it did not need to be.

The fix: keep servers stateless and store sessions in a shared store (Redis, a database) or
in a signed token the client carries (chapter 15). Then any server can handle any request.

When stickiness is legitimately useful: as a **performance hint**, not a correctness
requirement. Example: routing a user to the server that already has their data warm in a local
cache. If the hint breaks, the request still works, just a little slower. Consistent hashing on
user ID gives this without cookies. Long-lived connections (WebSocket) are inherently pinned
for the connection's life, which is fine.

> **Say it like this:** "I'd keep the app servers stateless and put sessions in Redis, so the
> balancer can send any request anywhere. If I want affinity for cache warmth, I'd make it a
> hint via consistent hashing, not something correctness depends on."

## 12.6 Making the load balancer itself redundant

One LB in front of ten servers just moves the single point of failure. Fixes, from one data
center up to global.

### Active-passive pair with a floating IP

Run two LB machines. The **active** one holds a **floating IP** (also called a **virtual IP,
VIP**): an IP address that is not tied to one machine and can be moved. The **passive** one
watches the active one with heartbeats (a protocol such as VRRP does this). If heartbeats
stop, the passive claims the floating IP (announcing "this IP is at my MAC address now" on the
local network) and becomes active. Failover takes roughly a second to a few seconds.

```text
                    floating IP 10.0.0.100
                           |
         +-----------------+------------------+
         |  holds VIP                         |  standby, heartbeats
   +-----v------+    heartbeat     +----------v-+
   |  LB-1      | <--------------> |  LB-2      |
   |  ACTIVE    |                  |  PASSIVE   |
   +-----+------+                  +------------+
         |
     backends ...        if LB-1 dies: LB-2 takes 10.0.0.100 and carries on
```

**Active-active** LBs (both serving at once, e.g. two VIPs in DNS, or routers spreading
traffic across several LBs using equal-cost multi-path routing) use all the hardware, but each
must be able to absorb the other's load if one fails.

Cloud-managed load balancers do this for you: the "load balancer" you create is really a
fleet of machines across availability zones behind one name.

### Global: DNS, anycast and GSLB

For users across continents and for surviving the loss of a whole region:

- **DNS-based load balancing**: the DNS server returns different IPs for the same name (round
  robin across regions, or based on the resolver's location). Limitation: DNS answers are
  cached according to their **TTL** (time to live), and some resolvers ignore low TTLs, so
  failover takes seconds to minutes and is never instant for everyone.
- **Anycast**: the same IP address is announced from many locations using BGP, the routing
  protocol of the internet. Each user's packets naturally go to the topologically nearest
  location. If one site withdraws its announcement, traffic shifts to the next nearest. CDNs
  and public DNS resolvers use anycast heavily. It works best for short connections, since a
  routing change mid-connection can send packets to a site that has no state for that
  connection.
- **GSLB (global server load balancing)**: a smart DNS layer that health-checks each region and
  answers with the closest healthy region's address, possibly weighted by capacity or latency.

```text
             user in Mumbai                       user in Frankfurt
                   |                                     |
           DNS / GSLB: "closest healthy region?"   (or anycast: same IP, nearest site)
                   |                                     |
          +--------v---------+                  +--------v---------+
          | region ap-south  |                  | region eu-central|
          |  L4 -> L7 -> app |                  |  L4 -> L7 -> app |
          +------------------+                  +------------------+
```

## 12.7 Reverse proxy roles

A **forward proxy** sits in front of clients and talks to the internet for them (a corporate
proxy). A **reverse proxy** sits in front of servers and receives requests on their behalf.
An L7 load balancer is a reverse proxy that also spreads load. Even with a single backend, a
reverse proxy is useful for these jobs:

| Role | Pain it removes |
|---|---|
| **TLS termination** | Certificates and TLS handshakes live in one place; backends speak plain HTTP on a private network (or re-encrypt if policy requires) |
| **Compression** | gzip/brotli responses once, at the edge, instead of in every app |
| **Caching** | Serve repeated GETs for static or semi-static content without hitting the app (chapter 08) |
| **Buffering slow clients** | See below |
| **Request limits** | Reject huge bodies or headers before they reach the app |
| **Hiding topology** | Clients see one host; internal servers and ports stay private |
| **Static files** | Serve files directly, faster than an app framework |

**Buffering slow clients** deserves a closer look. Many app servers use a fixed pool of worker
threads. A user on a slow mobile link uploads a 5 MB body over 20 seconds, or reads a response
slowly. Without a proxy, an app thread is tied up for those 20 seconds doing almost nothing. A
reverse proxy built on non-blocking I/O can hold thousands of slow connections cheaply: it reads
the full request first, then hands it to the app in one fast burst over the local network, and
it absorbs the response and trickles it out to the client. App threads are freed in
milliseconds. This also blunts "slowloris" attacks, where an attacker opens many connections
and sends headers extremely slowly to tie up server threads.

## 12.8 API gateway vs load balancer vs service mesh

These three overlap, and people mix them up. Separate them by **what traffic they handle** and
**what job they do**.

### Load balancer

Job: spread traffic across instances of something and route around dead ones. It does not
care who the user is or what the business rules are.

### API gateway

An **API gateway** is an L7 reverse proxy at the edge of your system that is the single front
door for external clients (**north-south traffic**: traffic entering or leaving your system).
It handles concerns about **the API as a product**:

- **Authentication**: validate the API key or token once (chapter 15), so services behind it
  receive a trusted identity header.
- **Rate limiting and quotas**: per API key, per plan ("free tier: 100 requests/minute").
- **Routing**: `/v1/orders/*` to the order service, `/v1/users/*` to the user service. Hides
  the internal service layout from clients.
- **Aggregation (composition)**: one client call fans out to three services and returns a
  combined response, saving a mobile client three round trips (see BFF in chapter 13).
- **Request/response transformation**: rename fields, convert versions, map REST to gRPC,
  strip internal headers.
- **Cross-cutting**: request IDs, logging, metrics, CORS, caching.

### Service mesh

A **service mesh** manages traffic **between your own services** (**east-west traffic**). It
works by putting a small proxy, a **sidecar**, next to every service instance (in Kubernetes,
an extra container in the same pod). The service talks to `localhost`; the sidecar handles the
network. A **control plane** pushes configuration to all sidecars, which form the **data
plane**.

```text
 north-south                                   east-west (inside the system)

 client --> [ API gateway ] --> +-----------------------+       +-----------------------+
             auth, quotas,      | pod: order service    |       | pod: payment service  |
             routing            |  app <-> [sidecar] ===|=mTLS==|=> [sidecar] <-> app   |
                                +-----------------------+       +-----------------------+
                                             ^                               ^
                                             |   config, certs, policies     |
                                        +----+-------------------------------+---+
                                        |          mesh control plane            |
                                        +----------------------------------------+
```

What sidecars give you without changing app code:

- **mTLS** (mutual TLS): both sides present certificates, so every service-to-service call is
  encrypted and both ends are authenticated (chapter 15). The control plane issues and rotates
  certificates automatically.
- **Retries and timeouts** configured centrally (with care; see retry storms, chapter 13).
- **Circuit breaking and outlier detection.**
- **Load balancing** per request between service instances.
- **Telemetry**: uniform metrics and traces for every call.
- **Traffic shifting**: send 5% of calls to version 2.

The cost: an extra proxy hop on every call (often around a millisecond or less, but it adds
up across deep call chains), more memory per pod, and a complex system to operate and debug.

### Side by side

| | Load balancer | API gateway | Service mesh |
|---|---|---|---|
| Main traffic | Any, in front of a pool | North-south (external clients in) | East-west (service to service) |
| Core job | Spread load, avoid dead nodes | Front door: auth, quotas, routing, API shaping | Secure, observable, reliable internal calls |
| Knows about users / API keys | No | Yes | Usually only service identities |
| Where it runs | Central (in front of pool) | Central (edge) | Distributed (sidecar per instance) |
| Business-aware | No | Somewhat (plans, versions, aggregation) | No |

They stack together: internet -> L4 LB -> API gateway fleet -> services with mesh sidecars.

> **Say it like this:** "The gateway is the front door for external clients — it does auth,
> per-key rate limits and routing. The mesh handles calls between our own services with
> sidecars — mTLS, retries, timeouts, telemetry. Load balancing happens inside both."

> **Common mistake:** Putting business logic in the gateway ("the gateway checks the user's
> account balance"). Gateways should stay thin and generic. Once they hold domain logic, every
> team must change and redeploy a shared component, and it becomes a bottleneck.

## 12.9 Service discovery

In a dynamic environment, instances come and go every minute (autoscaling, deploys, crashed
pods replaced). Hard-coding `payment-service = 10.0.3.17` breaks immediately. **Service
discovery** answers "what are the current healthy addresses of service X?"

The core piece is a **service registry**: a database of `service name -> list of instances`.
Instances register on startup and are removed when they stop (either they deregister, or their
heartbeat/lease expires). Examples: the Kubernetes API with its Endpoints objects, Consul,
etcd, ZooKeeper (chapter 10 covers why these are built on consensus).

Two ways clients use it:

```text
Client-side discovery                       Server-side discovery

  +---------+  1. who is payment?           +---------+
  | order   |------------> [registry]       | order   |---> payment.internal
  | service |<--- [ip1, ip2, ip3] ---       | service |            |
  |         |                               +---------+     +------v------+
  |         |  2. pick one itself,                          | LB / proxy  |<-- watches
  |         |     call it directly                          +------+------+    registry
  +----+----+                                                      |
       +--------> payment ip2                               payment ip1 / ip2 / ip3
```

| | Client-side | Server-side |
|---|---|---|
| Who picks the instance | The calling service (a library) | A load balancer or proxy |
| Extra hop | No | Yes |
| Client complexity | Every language needs the discovery + LB library | Client just calls a name |
| Smart balancing | Client can do power-of-two, zone awareness | LB does it |

**DNS-based discovery** is the simplest form: the service name resolves to instance IPs (or to
a virtual IP that a proxy owns). In Kubernetes, a **Service** gets a stable DNS name like
`payment.default.svc.cluster.local` and a stable virtual IP; the node's networking layer
forwards connections to the current healthy pods. DNS caching is the usual weakness: clients
that cache an IP too long keep calling instances that are gone. A service mesh is effectively
server-side discovery moved into the sidecar: the app calls a name, and the local sidecar knows
the live instances.

## 12.10 Rate limiting: why and where

**Rate limiting** means capping how many requests a given caller (user, API key, IP, tenant)
may make in a given time window, and rejecting or delaying the rest.

Why you need it:

| Reason | Example |
|---|---|
| **Abuse and security** | Credential stuffing: 10,000 login attempts per minute from a botnet; scrapers |
| **Fairness** | One tenant's runaway script must not slow down every other tenant |
| **Cost control** | Each call triggers a paid third-party API or an expensive ML model |
| **Protect downstream** | The database can do 5k writes/sec; do not let 50k arrive |
| **Commercial plans** | Free tier 100 req/min, paid tier 10,000 req/min |

Where to enforce it:

```text
 [client SDK] --> [CDN / WAF] --> [API gateway] --> [service] --> [DB / 3rd-party API]
   self-limit     per-IP floods    per key / plan    per expensive  limit on what WE
   (polite, but   at the edge      / per user        operation      send out
   untrusted)
```

- **Client**: a well-behaved SDK throttles itself and backs off on 429. Useful, but never
  trusted; attackers do not use your SDK.
- **Edge / gateway**: the usual main enforcement point. Rejects early, before any expensive
  work, with the caller's identity known.
- **Service**: finer-grained limits that only the service understands ("5 password-reset
  emails per user per hour", "2 report exports at a time").
- **Outbound**: limit your own calls to a partner API so you stay inside their quota.

What key to limit on: authenticated user or API key when available (most accurate); IP
address for anonymous traffic (but NATs put many users behind one IP, and attackers rotate
IPs). Often several limits apply at once: per IP, per user, per endpoint, and a global cap.

## 12.11 Rate-limiting algorithms

Throughout, assume the rule "at most **100 requests per 60 seconds** per user".

### Fixed window counter

Split time into fixed windows (12:00:00-12:00:59, 12:01:00-12:01:59, ...). Keep one counter
per user per window. On each request, increment; if the count exceeds 100, reject.

```text
 window key = user:42:  floor(now / 60)

 |---- window 12:00 ----|---- window 12:01 ----|
   count: 1,2,...,100     count resets to 0 at 12:01:00
```

- Memory: one integer per user per window. Very cheap. Easy in Redis (`INCR` + expiry).
- **Boundary burst problem**: a user sends 100 requests at 12:00:59 and 100 more at 12:01:00.
  Each window sees only 100, so all 200 pass, within about one second. Around any boundary the
  user can get up to **2x the limit** in a short span.

```text
            12:00:00                 12:01:00                 12:02:00
               |------- window A -------|------- window B -------|
                                     ###|###
                         100 req at :59 | 100 req at :00  -> 200 in ~1 second, all allowed
```

Use it when roughly correct is fine and simplicity wins (coarse quotas like "10,000 per day").

### Sliding window log

Store a timestamp for every accepted request (for example in a sorted set). On each request,
delete timestamps older than `now - 60s`, count what remains, and allow if the count is under
100.

```text
 now = 12:01:30, window = (12:00:30, 12:01:30]
 log: [12:00:10 x][12:00:25 x][12:00:40][12:00:41]...[12:01:29]   x = evicted (too old)
 allow if remaining count < 100, then append 12:01:30
```

- Exact: no boundary burst; the window truly slides.
- Memory grows with the limit: up to 100 timestamps per user. For a limit of 10,000 per hour
  across millions of users, that is a lot of memory and work.
- Design choice: whether rejected requests are logged. If they are, a client that keeps
  hammering never recovers until it stops completely; most implementations log only accepted
  requests.

Use it when precision matters and limits are small (login attempts, expensive operations).

### Sliding window counter

A cheap approximation of the sliding log. Keep only two counters: the previous fixed window and
the current one. Assume requests in the previous window were spread evenly, and count the part
of the previous window that still overlaps the sliding window.

```text
 limit 100 per 60s. now = 12:01:15 -> 15s into current window (25% elapsed)

   previous window 12:00 (count 80)     current window 12:01 (count 30 so far)
   |-----------------------------------|---------|...............|
                  |<------------- sliding 60s window ----------->|
                  ^ 45s of previous window still overlaps (75%)   ^ now

   estimate = prev_count * (1 - elapsed/window) + curr_count
            = 80 * (1 - 15/60) + 30
            = 80 * 0.75 + 30 = 60 + 30 = 90    -> under 100, allow
```

The formula, precisely:

```text
  elapsed  = now - start_of_current_window
  weight   = (window_size - elapsed) / window_size   # share of previous window still inside
  estimate = previous_count * weight + current_count
  allow if estimate < limit  (then current_count += 1)
```

- Memory: two integers per user. Smooths the boundary burst almost entirely.
- It is an approximation: if the previous window's traffic was all bunched at its very end,
  the estimate undercounts. In practice the error is small, which is why this is a popular
  production choice.

### Token bucket

Picture a bucket that holds at most **b** tokens (the **capacity**, or burst size). Tokens are
added at a steady rate **r** per second, and any overflow is discarded. Each request takes one
token; if the bucket is empty, the request is rejected (or waits).

```text
            refill r tokens/sec
                  |
                  v
             +---------+
             | o o o o |   capacity b (max burst)
             | o o o   |
             +----+----+
                  |  each request removes 1 token
                  v
        token available? -> allow      empty? -> reject (429) or queue
```

You do not need a timer that adds tokens. Store just two values per user — `tokens` and
`last_refill_time` — and refill lazily on each request:

```text
  on request at time now:
     tokens = min(b, tokens + (now - last_refill_time) * r)
     last_refill_time = now
     if tokens >= 1: tokens -= 1; allow
     else: reject  (retry after (1 - tokens) / r seconds)
```

Example: b = 20, r = 100/60 ≈ 1.67 per second. A user idle for a while can fire 20 requests
instantly (burst), then sustains about 1.67 per second. Over a long period the average rate is
capped at r, and any interval of length T allows at most `b + r*T` requests.

- Allows bursts, which matches real client behavior (a page load fires 15 API calls at once).
- Two numbers per key; cheap. Two knobs that are easy to explain (burst and sustained rate).
- Very widely used by cloud APIs and gateways.
- Cost-weighted requests are easy: an expensive call takes 10 tokens.

### Leaky bucket

Two meanings exist; know both.

1. **Leaky bucket as a queue (traffic shaper)**: requests enter a FIFO queue of fixed size;
   a worker takes them out at a constant rate. If the queue is full, new requests are dropped.
   Output is perfectly smooth regardless of input bursts.

```text
     bursty input -> | | | | | | queue (size Q) -> drips out at constant rate r -> backend
                       full? drop new requests
```

2. **Leaky bucket as a meter**: a counter that "leaks" at rate r and fills by 1 per request;
   reject if it would overflow capacity. This version is mathematically equivalent to a token
   bucket viewed upside down.

The shaper version is what people usually mean when they contrast the two: it **smooths**
traffic (good for protecting a backend that needs a steady rate, like a legacy system or an
outbound partner quota) but adds **queueing delay**, and a burst of good traffic waits in line
instead of being served immediately.

### Comparison

| Algorithm | State per key | Bursts | Accuracy | Notes |
|---|---|---|---|---|
| Fixed window | 1 counter | Up to 2x at boundaries | Coarse | Simplest; fine for daily quotas |
| Sliding log | N timestamps | None beyond limit | Exact | Memory heavy for big limits |
| Sliding window counter | 2 counters | Smoothed | Approximate, small error | Great default for "N per window" |
| Token bucket | tokens + timestamp | Allowed up to b | Exact for its model | Great default for APIs; burst + rate |
| Leaky bucket (queue) | queue | Absorbed, output smooth | Exact | Adds latency; for shaping output |

> **Say it like this:** "For a public API I'd use a token bucket per API key: capacity covers
> normal bursts like a page load, refill rate is the plan's sustained limit. It's two numbers
> per key in Redis, updated atomically with a Lua script."

> **Common mistake:** Describing fixed window and claiming it enforces "100 per minute"
> exactly. It enforces 100 per calendar minute; any rolling 60-second span can see up to 200.

## 12.12 Distributed rate limiting with Redis

With one gateway instance, counters can live in memory. With 20 gateway instances behind an
LB, each sees only part of a user's traffic. If each enforces 100/min locally, the user really
gets up to 2,000/min. You need shared state, and **Redis** (an in-memory key-value store,
chapter 08) is the usual choice: fast (well under a millisecond per operation on a local
network), and single-threaded command execution, so each command is atomic.

### Fixed window in Redis, and the race to avoid

Naive version:

```text
  count = GET rl:user42:2026-10-10T12:01
  if count < 100:
      SET rl:user42:... count+1        # RACE: two gateways both read 99, both write 100,
      allow                            # both allow -> 101 requests got through
```

**Read-then-write is a race** (a check-then-act bug, chapter 26). Fix it with an atomic
increment, which returns the new value:

```text
  n = INCR rl:user42:{window}          # atomic: concurrent INCRs never lose updates
  if n == 1: EXPIRE rl:user42:{window} 60
  if n > 100: reject  else: allow
```

There is still a subtle gap: if the process crashes between `INCR` and `EXPIRE`, the key never
expires and the user is blocked forever. Fixes: send both in one `MULTI`/`EXEC` transaction
(`INCR` then `EXPIRE` every time), or use a Lua script so both run as one atomic unit.

### Token bucket as a Lua script

Redis runs a **Lua script** atomically: no other command interleaves while it executes. That
turns "read tokens, compute refill, decide, write back" into one indivisible step.

```lua
-- KEYS[1] = bucket key, ARGV: capacity, refill_per_sec, now_ms, cost
local cap   = tonumber(ARGV[1])
local rate  = tonumber(ARGV[2])
local now   = tonumber(ARGV[3])
local cost  = tonumber(ARGV[4])

local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or cap        -- new key starts full
local ts     = tonumber(b[2]) or now

tokens = math.min(cap, tokens + (now - ts) / 1000 * rate)
local allowed = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000) * 2)  -- idle keys vanish
return { allowed, tokens }
```

Details worth mentioning:

- **Clock source**: here the gateway passes `now`. If gateway clocks disagree, refills get
  slightly wrong. An alternative is to read Redis's own clock inside the script (`TIME`), so
  one clock is used for everyone.
- **Sliding log in Redis**: a sorted set per key, `ZREMRANGEBYSCORE` to drop old entries,
  `ZCARD` to count, `ZADD` to add — again wrapped in a script or transaction for atomicity.
- **Sharding**: one Redis may not hold every key or survive the request rate; shard keys across
  a Redis cluster by user ID (chapter 07). Each user's bucket still lives on one shard, so the
  per-key atomicity holds.

### When Redis is slow or down

The limiter is now on every request's critical path. Decide up front:

- **Fail open** (allow traffic if the limiter is unavailable): the product keeps working; you
  lose protection temporarily. Common for fairness limits.
- **Fail closed** (reject): safer for abuse-critical limits (login attempts, expensive paid
  APIs) but turns a Redis outage into an API outage.

Always put a short timeout (a few milliseconds) on the limiter call.

### Local vs global limits

Calling Redis on every request costs latency and Redis capacity. Middle grounds:

| Approach | How | Trade-off |
|---|---|---|
| Global (exact) | Every request hits Redis | Accurate; adds a round trip; Redis is a dependency |
| Local only | Each of N instances enforces limit/N in memory | No network; inaccurate if traffic is unevenly spread across instances |
| Local + periodic sync | Instances count locally, push deltas to Redis every ~100 ms, pull global totals | Small overshoot allowed; far fewer Redis calls |
| Token leasing | Instance grabs a batch of 10 tokens from the global bucket, spends them locally | Fewer calls; tokens can be stranded on one instance |

For most APIs, a little overshoot (allowing 105 instead of 100) is acceptable. For money or
security, prefer exact.

## 12.13 Telling clients they are limited

Rejected requests get **HTTP 429 Too Many Requests**. Good APIs also tell clients how to
behave:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 12
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1791712920

{"error": "rate_limited", "message": "Limit of 100 requests per minute exceeded"}
```

- `Retry-After`: a standard HTTP header: seconds to wait, or an HTTP date. Well-behaved
  clients wait at least this long.
- `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`: a widely used
  convention, not a formal standard. Check whether `Reset` means epoch seconds or "seconds
  from now" in each API, because both exist. An IETF draft standardizes un-prefixed
  `RateLimit` and `RateLimit-Policy` headers for the same purpose.
- Include the remaining-quota headers on successful responses too, so clients can slow down
  before hitting the wall.

Clients should respond to 429 with exponential backoff and jitter (chapter 13), not with an
immediate retry loop.

## 12.14 Load shedding vs rate limiting

They both reject requests, but they answer different questions.

| | Rate limiting | Load shedding |
|---|---|---|
| Question | "Is **this caller** over **their** allowance?" | "Is **the server** over **its** capacity right now?" |
| Trigger | Per-key counter exceeds a configured limit | Server-side signal: CPU, queue length, in-flight requests, latency |
| Fires when system is idle? | Yes (the caller is still over their limit) | No |
| Typical response | 429 Too Many Requests | 503 Service Unavailable (with Retry-After) |
| Goal | Fairness, abuse prevention, plans | Survive overload; keep latency OK for the work you accept |

Why you need both: a million well-behaved users each within their limits can still exceed
total capacity (a viral moment). Rate limits will not catch that; load shedding will.

Good load shedding is **prioritized**: when overloaded, first drop latency-insensitive
background work (prefetch, analytics beacons), then lower-priority traffic, and protect
checkout and login last. And shed **early and cheaply**: rejecting at the front door costs
microseconds, while accepting a request you will time out on wastes all the work done for it.
Chapter 13 covers load shedding and backpressure inside services in more depth.

> **Say it like this:** "Rate limiting is per-caller fairness — it returns 429 even when we're
> idle. Load shedding is self-protection — when in-flight requests or queue depth cross a
> threshold we return 503 for low-priority traffic first, regardless of who's asking."

## 12.15 Recap

- A load balancer gives one stable address, spreads traffic over a pool of stateless servers,
  and routes around failures; it must itself be redundant (VIP pair, managed LB, DNS/anycast).
- L4 balances connections and sees only IPs/ports; L7 balances requests and sees paths,
  headers and cookies, terminates TLS, and handles HTTP/2 multiplexing properly.
- Pick algorithms by workload: round robin for uniform work, least-request / power of two
  choices for variable work, consistent hashing when backends hold per-key state.
- Active plus passive health checks; keep readiness about the instance, not shared
  dependencies; drain connections before removing a server.
- Sticky sessions are a smell; store sessions in a shared store and use affinity only as a
  performance hint.
- Reverse proxies handle TLS, compression, caching and buffering slow clients.
- API gateway = front door for external traffic (auth, quotas, routing, aggregation); service
  mesh = sidecars for internal calls (mTLS, retries, telemetry); LB = spread load.
- Service discovery maps names to live instances via a registry, client-side or server-side.
- Rate-limit algorithms: fixed window (2x boundary burst), sliding log (exact, memory heavy),
  sliding counter (weighted estimate, cheap), token bucket (burst + rate), leaky bucket
  (smooth output, adds delay).
- Distributed limits need atomic operations (INCR, Lua scripts) and a fail-open/closed decision.
- 429 + Retry-After for rate limits; load shedding protects capacity and returns 503.

## 12.16 Check yourself

1. Your gRPC service sits behind an L4 load balancer. One backend runs at 90% CPU while the
   others idle. What is the likely cause and fix?

<details><summary>Answer</summary>

gRPC uses HTTP/2, which multiplexes many requests over one long-lived TCP connection. An L4
balancer picks a backend per connection, so a client with one connection sends everything to
one backend. Fix: use an L7 balancer (or mesh sidecar / client-side balancing) that understands
HTTP/2 and spreads individual requests, or force clients to open several connections and
recycle them periodically.

</details>

2. With a "100 per minute" fixed window limit, what is the maximum number of requests a client
   can get accepted within a two-second span, and why?

<details><summary>Answer</summary>

200. The client sends 100 at the end of one window (e.g. 12:00:59) and 100 at the start of the
next (12:01:00). Each window's counter sees only 100, so all are allowed. This is the boundary
burst problem.

</details>

3. Previous window count = 50, current window count = 40, window = 60 s, and we are 20 s into
   the current window. Limit is 80. Does the sliding window counter allow the next request?

<details><summary>Answer</summary>

estimate = 50 * (1 - 20/60) + 40 = 50 * (2/3) + 40 ≈ 33.3 + 40 = 73.3. That is below 80, so
the request is allowed (and the current count becomes 41).

</details>

4. Why can "always send to the least loaded backend" perform worse than "pick two at random,
   use the less loaded"?

<details><summary>Answer</summary>

With many load balancer instances acting on slightly stale load information, they all see the
same server as least loaded and send to it simultaneously, overloading it (a herd effect).
Power of two choices adds randomness so instances do not all agree, while still avoiding the
worst servers. Its maximum load is close to the ideal (about log log n versus log n / log log n
for pure random).

</details>

5. Two gateway instances implement a Redis limit as `GET count; if count < 100 then SET
   count+1`. What goes wrong and how do you fix it?

<details><summary>Answer</summary>

A race: both instances read 99, both decide to allow, both write 100, so 101 requests pass and
an update is lost. Use an atomic operation: `INCR` (returns the new value) and compare after
incrementing, or wrap the whole read-compute-write logic (e.g. token bucket) in a Lua script,
which Redis executes atomically. Also make sure the expiry is set atomically with the first
increment so a crash cannot leave a key that never expires.

</details>

6. Your readiness probe checks that the database is reachable. The database has a 10-second
   hiccup. What happens, and what would you change?

<details><summary>Answer</summary>

Every instance fails readiness at the same moment, so the load balancer removes the whole pool
and all traffic fails, including requests that did not need the database (cached reads, static
pages). Keep readiness focused on the instance itself; handle dependency failures in the
request path with timeouts, circuit breakers and fallbacks.

</details>

7. Explain the difference between an API gateway and a service mesh in two sentences.

<details><summary>Answer</summary>

An API gateway is a central front door for external (north-south) traffic, handling API-level
concerns like authenticating API keys, per-plan rate limits, routing and response aggregation.
A service mesh puts a sidecar proxy next to every service instance to handle internal
(east-west) calls, providing mTLS, retries, timeouts, load balancing and telemetry without
changing application code.

</details>

8. Every user is within their rate limit, yet servers are overloaded and latency is climbing.
   What mechanism is missing, and what status code would it return?

<details><summary>Answer</summary>

Load shedding: rejecting work based on the server's own load (in-flight requests, queue depth,
latency) rather than per-caller counters. It typically returns 503 Service Unavailable,
ideally with Retry-After, and drops low-priority traffic first.

</details>
