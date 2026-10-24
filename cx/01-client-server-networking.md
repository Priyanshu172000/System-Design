# 01 — How the web works: clients, servers, networks

*Why it matters:* every design discussion assumes you can narrate "user clicks" to "page appears"; every later chapter zooms into one box of that picture.

## Client, server, backend

**How it works** · The **client** (browser, app, another service) shows things and collects input; the **server** runs on machines you control, does the work and responds. The **backend** = app servers + databases + caches + queues + workers; the **frontend** = client-side UI code.

**Example** · Asha (`u_1842`) applies a ₹100 coupon on order `o_90311` (₹450) in the QuickBite app.
1. The app shows "₹350" instantly (frontend, just a display).
2. It sends `POST /orders` with `coupon=FEAST100` to `order-service`.
3. `order-service` checks the coupon is valid, unused and allowed for `r_77`, then stores `amount=350`.
4. A modified app that sends `amount=1` changes nothing: the server recomputes the price.

So: the three reasons for the split are **shared data** (same order on phone and laptop), **trust** (the client can be modified, rules live on the server) and **power** (searching 50,000 restaurants does not fit on a phone). In the **client-server model** the client always opens the conversation; "push" (the rider is 2 min away) rides on a connection the client opened earlier (WebSocket) or the phone OS push service (chapter 02).

> **Say it like this:** "The client is untrusted and only renders; the backend owns the data and enforces the rules, so any check that matters happens on the server."

## The life of a request

**How it works** · A fresh request pays for name lookup and two handshakes before any HTTP is sent.

**Example** · Asha opens `https://api.quickbite.com/restaurants/r_77/menu`, server about 100 ms RTT away:

```text
 1. Parse URL: https, host api.quickbite.com, path /restaurants/r_77/menu, port 443
 2. DNS: browser cache -> OS cache -> resolver -> (root -> .com -> authoritative)
    <- 203.0.113.10                                          (~100 ms if uncached)
 3. TCP 3-way handshake to 203.0.113.10:443                  (1 RTT = 100 ms)
 4. TLS 1.3 handshake: verify cert, derive keys              (1 RTT = 100 ms)
 5. HTTP: GET /restaurants/r_77/menu, Host, Cookie/Authorization, Accept
 6. LB terminates TLS, picks a healthy restaurant-service instance
 7. restaurant-service reads menu from cache/DB -> 200 OK + JSON
 8. App renders; dish images come from a CDN, multiplexed on HTTP/2 or 3
 9. Connection kept open; DNS and TLS session cached for the next tap
```

So: roughly 300 ms of pure setup before the server does any work; the second tap pays only the request itself. That is why connection reuse, CDNs and HTTP/3 target setup cost. In an interview, give this list, then offer to zoom into DNS, TLS, or the backend.

## IP addresses, ports, sockets

**How it works** · The **IP address** finds the machine, the **port** finds the program, and the 4-tuple identifies one connection.
- **IPv4**: 32 bits (`203.0.113.10`), about 4.3 billion, ran out. **IPv6**: 128 bits (`2001:db8::1`), effectively unlimited. Devices use **private addresses** (`10.x`, `192.168.x`, `172.16–31.x`); a router does **NAT** so many share one **public IP**. In a cloud, servers live in a private network and only the load balancer faces the internet (security and address saving).
- **Port** (16 bits, 0–65535), conventions not laws: 22 SSH, 53 DNS, 80 HTTP, 443 HTTPS, 3306 MySQL, 5432 PostgreSQL, 6379 Redis, 9092 Kafka. Browsers assume 443 for `https://`.
- **Socket** = the OS handle for one end of a conversation; read/write bytes like a file. A TCP connection is the 4-tuple **(client IP, client port, server IP, server port)**; the client port is a random **ephemeral port**. The OS is a hash map from 4-tuple to connection object.

**Example** · Two phones and one laptop hit the QuickBite load balancer on port 443:

| Client IP | Client port | Server IP:port | Connection |
|---|---|---|---|
| 198.51.100.7 (Asha's phone) | 52811 | 203.0.113.10:443 | #1 |
| 198.51.100.7 (same phone, 2nd conn) | 52812 | 203.0.113.10:443 | #2 |
| 192.0.2.44 (another user) | 52811 | 203.0.113.10:443 | #3 |

So: one listening port serves all three because every 4-tuple differs.

> **Common mistake:** "A server can only hold 65,535 connections because ports are 16 bits." That limit is per client IP talking to one server IP:port. One listening port can accept millions of distinct client IP/port pairs; real limits are memory, file descriptors, CPU.

## DNS: names to addresses

**How it works** · Names are memorable and the IP behind them changes (migration, failover), so we need a global, fast, updatable lookup: **DNS**, a distributed key-value store partitioned by the label tree (`.` -> `com` -> `quickbite.com`), cached at every level. The **stub resolver** (OS) asks a **recursive resolver** (ISP, company, public), which walks **root servers** -> **TLD servers** -> **authoritative name server** (holds your records) and caches the answer.

**Example** · First-ever lookup of `api.quickbite.com` from Asha's ISP resolver in Bengaluru:
1. Browser and OS caches miss; resolver cache misses.
2. Root server: "ask the `.com` servers".
3. `.com` TLD: "ask `ns1.dnshost.net`" (the NS record for `quickbite.com`).
4. Authoritative: `api.quickbite.com A 203.0.113.10, TTL 300`.
5. The next Bengaluru user on that ISP within 300 s gets the answer straight from the resolver cache.

So: the full walk is rare; almost every lookup is a cache hit (browser, OS, resolver).

| Record | Maps | QuickBite example |
|---|---|---|
| **A** / **AAAA** | name -> IPv4 / IPv6 | `api.quickbite.com A 203.0.113.10` |
| **CNAME** | name -> another name | `img.quickbite.com CNAME quickbite.cdnprovider.net` (CDN can change IPs); a CNAME name can have no other records, so not on the bare domain (providers offer non-standard "alias") |
| **NS** | zone -> its authoritative servers | `quickbite.com NS ns1.dnshost.net` |
| **MX** | domain -> mail servers | `quickbite.com MX 10 mail.quickbite.com` |
| **TXT** | name -> text | ownership proofs, email policies (`"v=spf1 ..."`) |

**TTL and caching** · Each record has a **TTL** (seconds) that resolvers cache it for. Long TTL (hours): fewer lookups, faster, but clients keep the old IP after a failure. Short TTL (30–60 s): fast redirection, more lookups, and some clients ignore very low TTLs.

**Example** · QuickBite moves `api` to a new data center on Saturday; normal TTL is 3,600 s.
1. Friday (at least one old TTL before): lower TTL to 60 s.
2. Saturday: change the A record to the new IP; most clients follow within about 60 s.
3. Keep the old site serving for a while (some resolvers cache longer), then raise TTL again.

**DNS-based load balancing** ·
- **Round-robin DNS**: several A records (`.10`, `.11`, `.12`). Cheap, but no health awareness; caching makes the split uneven.
- **Geo / latency-based DNS**: answer with the nearest region's IP (Asha in India -> Mumbai, a user in Germany -> Frankfurt).
- **Health-checked DNS / failover**: provider probes endpoints and stops handing out dead IPs (still TTL-limited).

DNS balancing is coarse (regions, slow to change); per-server balancing is the load balancer's job (chapter 12). Misconception: "I changed the record, so traffic moved instantly." Cached answers live until TTL expiry, so never rely on DNS alone for second-level failover. DNS is your first routing layer, not "just a lookup".

## TCP and UDP

**How it works** · IP delivers **packets** (up to about 1,500 bytes) best-effort: lost, duplicated, delayed, reordered. The **transport layer** bridges that to what programs want. **TCP** gives a **connection**: a two-way byte stream, complete and in order, or an error.
- **Sequence numbers** (reorder, detect gaps); **ACKs** + **retransmission** after timeout.
- **Flow control**: receiver advertises buffer space. **Congestion control**: "slow start", ramp up until loss, back off; a new connection cannot use full bandwidth at once (reuse connections).
- **Three-way handshake** SYN -> SYN-ACK -> ACK: 1 **RTT** before the first data byte. RTT roughly: same DC 0.5 ms, across a continent 30–80 ms, across the world 150–300 ms.
- **Closing** with FIN; the side that closes first holds `TIME_WAIT` (commonly about a minute). Many short connections to one destination can exhaust ephemeral ports -> use connection pools (chapter 03).

**Example** · The menu JSON for `r_77` is 8 packets; packet 5 is lost (**head-of-line blocking**):

```text
 Client  SYN seq=1000            ->  Server
         <- SYN-ACK seq=5000 ack=1001
         ACK ack=5001            ->        (1 RTT spent; now data flows)
 Sent:      [1][2][3][4][5][6][7][8]
 Arrived:   [1][2][3][4]   [6][7][8]       (5 lost)
 App sees:  [1][2][3][4] ...waiting...  [5][6][7][8]   after 5 is retransmitted
```

So: 6–8 are already on the phone but blocked behind 5. Bad when one connection carries independent streams (HTTP/2).

**UDP**: a thin layer over IP. Send a **datagram** to IP:port; no connection, handshake, retransmission, ordering or congestion control. The app handles loss, or doesn't care.

| | TCP | UDP |
|---|---|---|
| Setup | 3-way handshake (1 RTT) | none |
| Reliability / ordering | retransmits, in-order stream | none (app's job) |
| Message boundaries | none (app frames messages) | each datagram is one message |
| Congestion control | built in | none (app's job) |
| Head-of-line blocking | yes | no |
| Typical uses | HTTP/1.1, HTTP/2, databases, SSH, email | DNS, voice/video, games, metrics, QUIC/HTTP3 |

UDP wins when **late data is useless** (in a support voice call with rider `rd_5521`, 300 ms-old audio is worthless; skip it), for **tiny request/response** (a DNS query is one packet; just ask again), and when **building your own reliability** (QUIC, in user space). UDP is not "bad", it is unopinionated.

> **Say it like this:** "TCP gives a reliable ordered stream at the cost of a handshake and head-of-line blocking. I'd pick UDP when stale data is worthless, like live audio, or when building my own reliability, which is what QUIC does."

## HTTP basics

**How it works** · **HTTP** is request/response over a transport; one response per request. Request = **method** (`GET` read, `POST` create/act, `PUT` replace, `PATCH` partial, `DELETE`) + **path** + **query string** + **headers** (`Host` since one IP hosts many sites, `Content-Type`, `Authorization`, `Accept`, `Cookie`) + optional **body** (GET normally has none). Response = status line (version + **status code**) + headers (`Content-Type`, `Location`, `Cache-Control`, `Set-Cookie`) + body.

**Example** · Asha taps "Place order":

```http
POST /api/orders?source=app HTTP/1.1
Host: api.quickbite.com
Content-Type: application/json
Authorization: Bearer eyJhbGciOi...

{"restaurantId": "r_77", "items": [{"id": "m_12", "qty": 2}]}
```

`order-service` answers `HTTP/1.1 201 Created`, `Location: /api/orders/o_90311`, `Cache-Control: no-store`, body `{"id": "o_90311", "status": "PLACED", "amount": 450}`. So: the method says "create", the status says it worked, `Location` says where it lives.

| Range | Meaning | Examples |
|---|---|---|
| 1xx | informational | `101 Switching Protocols` (starts WebSockets) |
| 2xx | success | `200`, `201 Created`, `204 No Content` |
| 3xx | redirection | `301 Moved Permanently`, `304 Not Modified` |
| 4xx | client's fault: retrying won't help | `400`, `401`, `404`, `429 Too Many Requests` |
| 5xx | server's fault: may work later | `500`, `502 Bad Gateway`, `503 Service Unavailable` |

**Stateless**: each request stands alone; identity comes from a **cookie** (session ID) or **token** in `Authorization` every time. Asha's next request may land on a different `order-service` instance and still works because the token travels with it (chapter 03).

## HTTP versions: 1.1, 2, 3

**How it works** · HTTP/1.0 opened a TCP connection per request. **HTTP/1.1** made **keep-alive** default, but one request at a time per connection (pipelining is broken, unused), so browsers open about **6 connections per host**. **HTTP/2** splits messages into binary **frames** tagged with **stream IDs** and **multiplexes** many streams over one TCP connection, plus header compression (HPACK). HTTP/2 is also gRPC's transport.

**Example** · The QuickBite home screen needs 50 restaurant thumbnails:

| Version | What happens |
|---|---|
| HTTP/1.0 | 50 TCP (+TLS) handshakes |
| HTTP/1.1 | 6 kept-alive connections, 8–9 images each in turn; a slow image blocks the ones behind it |
| HTTP/2 | 1 connection, frames `[A1][B1][C1][A2]...` interleaved; but one lost TCP packet stalls all 50 |
| HTTP/3 | 1 QUIC connection; a lost packet stalls only its own image |

So: on lossy mobile, HTTP/2 can lose to 6 HTTP/1.1 connections; HTTP/3 fixes that.

> **Black box:** **HTTP/3** runs on **QUIC** over UDP: reliability, ordering, congestion control per stream; transport + TLS handshake combined (1 RTT new, 0 RTT resume); **connection migration** by connection ID (e.g. Asha's phone moving from Wi-Fi to cellular keeps tracking). **Use when:** mobile/lossy clients, latency-sensitive setup. **Cost:** UDP sometimes blocked/throttled by corporate networks; more CPU than kernel TCP; clients fall back to HTTP/2.

| | HTTP/1.1 | HTTP/2 | HTTP/3 |
|---|---|---|---|
| Transport | TCP | TCP | QUIC over UDP |
| Requests at once per connection | 1 | many | many |
| Format / header compression | text / none | binary / HPACK | binary / QPACK |
| Head-of-line blocking | HTTP + TCP level | TCP level | none across streams |
| New connection setup with TLS | 2–3 RTT | 2–3 RTT | 1 RTT (0 on resume) |

## HTTPS and TLS

> **Black box:** **TLS** wraps the connection; **HTTPS** = HTTP inside TLS. It gives **confidentiality**, **integrity**, **authentication**. **Use when:** always, for anything crossing networks you don't control (e.g. café Wi-Fi could read Asha's OTP or swap the payment amount in plain HTTP). **Cost:** 1 RTT handshake in TLS 1.3 (2 in TLS 1.2) on top of TCP, plus decrypt CPU.

- **Certificate**: "public key K belongs to `api.quickbite.com`", signed by a **CA**. Browsers trust **root CAs**; a **chain** root -> intermediate -> site is verified, plus expiry and hostname. The server proves it holds the **private key**, so a copied certificate is useless to an impostor.
- Handshake in one line: hellos + key shares, server sends cert chain and a signature, both derive session keys. **Asymmetric** crypto only in the handshake (slow, lets strangers agree); **symmetric** (AES) for data (fast). Ephemeral key exchange gives **forward secrecy**: a later key leak can't decrypt recorded traffic.
- Not hidden: server IP, data volume, usually the hostname (**SNI**). Protects data in transit only, not at rest.
- **TLS termination**: the load balancer / gateway decrypts and talks to app servers over the private network (plain or re-encrypted). Zero trust uses **mTLS** (both sides present certs). Chapter 15.

> **Common mistake:** "HTTPS means the site is safe." It means the connection is private and you reached the domain on the certificate; a phishing site can have a valid cert for its own domain.

## Latency, bandwidth, throughput

**How it works** · **Latency** = time for one thing (length of the pipe). **Bandwidth** = max rate of a link (width). **Throughput** = what actually gets done per second (what flows). Light in fiber travels roughly 200,000 km/s, so one small message across the world is latency-bound; a 10 GB backup is bandwidth-bound. Ping time is one round trip, not your API latency.

**Example** · Two QuickBite problems, two different fixes:
1. A chatty app makes 100 sequential calls over a 50 ms RTT: 100 x 50 ms = 5 s even on infinite bandwidth. Fix: batch into one call (50 ms), not a bigger pipe.
2. **Little's Law** (in-flight = throughput x latency; intuition: 20 guests/hour who each stay 3 hours fill 60 tables at once; step-by-step trace in ch 03): `restaurant-service` serves 2,000 req/s at 50 ms -> 2,000 x 0.05 = 100 in flight. The menu DB slows to 500 ms at the same rate -> 2,000 x 0.5 = 1,000 in flight. A 200-thread pool is now full; requests queue and latency grows further.

So: a slow dependency exhausts thread pools with no traffic change (chapter 13).

## Proxies: forward and reverse

**How it works** · A **proxy** forwards traffic on someone's behalf; the question is whose.
- **Forward proxy** acts for clients, configured by the client's network; servers see the proxy. Uses: corporate outbound filtering/logging, shared caching, hiding client identity.
- **Reverse proxy** acts for servers, configured by the site owner; clients see only the proxy. Uses: load balancing, TLS termination, caching, compression, rate limiting, path routing, hiding topology. Load balancers, API gateways and CDNs are all reverse proxies (chapters 08, 12).

**Example** · Both kinds on one QuickBite request from an office laptop:

```text
 [office laptop] -> [corporate fwd proxy] -> Internet -> [api-gateway (reverse proxy)]
                     servers see the office IP              /api/orders/* -> order-service
                                                            /api/menu/*   -> restaurant-service
                                                            /static/*     -> CDN / object store
```

So: "put a proxy in front" almost always means reverse; the client never learns how many `order-service` instances exist.

## The layers, briefly

> **Black box:** the OSI-style stack: application (7: HTTP, gRPC, DNS) / TLS between 4 and 7 / transport (4: TCP, UDP, QUIC) / network (3: IP) / link-physical (2/1: Ethernet, Wi-Fi); each wraps the one above like envelopes. **Use when:** you hear "L4 vs L7". **Cost:** an **L4 load balancer** sees only IPs/ports, fast, can't route by URL; an **L7 load balancer** understands HTTP (e.g. sends `/api/orders` to `order-service`, retries, rewrites) but costs more CPU and must terminate TLS (chapter 12).

## In one minute

- Clients are untrusted renderers; the backend owns data and enforces rules (the server recomputes the order price).
- A fresh request pays DNS + TCP (1 RTT) + TLS (1 RTT in 1.3) before HTTP; reuse connections and use CDNs/HTTP/3 to cut setup.
- IP finds the machine, port finds the program, the 4-tuple identifies a connection, so one port holds huge numbers of connections.
- DNS is a cached hierarchical KV store; TTL trades failover speed for lookups (lower it before a migration); geo DNS routes to the nearest region, LBs do per-server balancing.
- TCP = reliable ordered stream with handshake and head-of-line blocking; UDP = no guarantees, good for stale-is-worthless data or custom reliability.
- HTTP is stateless; 4xx = fix the request, 5xx = retry later. HTTP/2 multiplexes on one TCP connection; HTTP/3 uses QUIC to remove cross-stream blocking.
- TLS gives confidentiality, integrity, authentication via CA-signed certs; usually terminated at the load balancer.
- Little's Law: in-flight = rate x latency; reverse proxies (LB, gateway, CDN) act for servers.

## Go deeper

Full chapter 01: sections 01.4 (DNS), 01.5 (TCP and UDP), 01.6 (HTTP versions), 01.8 (latency and Little's Law)
