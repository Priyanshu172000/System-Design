# 01 — How the web works: clients, servers, networks

Every system design conversation assumes you know what happens between "user clicks" and "page
appears". This chapter walks that path one hop at a time: addresses, DNS, TCP, TLS, HTTP, and the
servers behind them. Once you can narrate a request end to end, every later chapter is just
"zooming into one box" of this picture.

## 01.1 What a "backend" is

When you open an app on your phone, two kinds of programs are involved:

- The **client**: the program the user touches. A browser, a mobile app, a smart TV app, or even
  another program calling yours. It shows things and collects input.
- The **server**: a program running on a machine you control (in a data center or a cloud). It
  waits for requests, does the work, and sends back responses.

The **backend** is everything on the server side: the application servers that run your business
logic, the databases that store data, the caches, queues, and background workers. The
**frontend** is the client-side code that draws the UI.

Why split things this way at all? Why not do everything on the user's device?

- **Shared data.** Your bank balance must be the same on your phone and your laptop. One copy
  must live somewhere both can reach.
- **Trust.** You cannot trust the client. A user can modify an app to say "my balance is a
  million". The server is the place where rules are enforced.
- **Power.** A search over billions of documents does not fit on a phone.

```text
   Clients                           Backend (your data center / cloud)
 +-----------+                  +------------------------------------------+
 | Browser   |---+              |  +-----------+     +------------------+  |
 +-----------+   |   Internet   |  | App server|---->| Database         |  |
 | Phone app |---+------------->|  | (logic)   |     | (source of truth)|  |
 +-----------+   |              |  +-----------+     +------------------+  |
 | Other svc |---+              |        |                                 |
 +-----------+                  |        +--------> Cache, queues, workers |
                                +------------------------------------------+
```

This is the **client-server model**: clients ask, servers answer. The client always opens the
conversation. When the server needs to push something (a chat message, a notification), it does
so over a connection the client opened earlier, such as a WebSocket, or through the phone OS's
push service (chapter 02).

> **Say it like this:** "The client is untrusted and only renders; the backend owns the data and
> enforces the rules. So any check that matters — permissions, balances, limits — happens on the
> server."

## 01.2 The life of a request (the big picture)

Before details, here is the whole journey when you type `https://shop.example.com/cart` and press
Enter. Every numbered step gets its own section below.

```text
 You type https://shop.example.com/cart

 Browser                                                              Backend
 -------                                                              -------
 1. DNS lookup:   "what IP is shop.example.com?"
    Browser -> OS cache -> resolver -> (root -> .com -> example.com NS)
    <- "203.0.113.10"
 2. TCP connect to 203.0.113.10:443
    SYN  ------------------------------------------------------------>
         <------------------------------------------------------ SYN-ACK
    ACK  ------------------------------------------------------------>
 3. TLS handshake (agree on keys, check certificate)
    ClientHello ----------------------------------------------------->
         <------------------------------ ServerHello + certificate ...
    Finished -------------------------------------------------------->
 4. HTTP request (encrypted)
    GET /cart  Host: shop.example.com  Cookie: session=... ---------->
                                                 5. Load balancer picks an app server
                                                 6. App server runs code:
                                                    - checks session
                                                    - queries DB / cache
                                                 7. Builds response
         <-------------------------------- HTTP/1.1 200 OK + HTML/JSON
 8. Browser renders; fetches more resources (CSS, JS, images) over the
    same connection, many of them from a CDN close to the user.
```

Notice how many round trips happen before a single byte of your page is requested: DNS (maybe
several), TCP (1 round trip), TLS (1–2 round trips). If the server is 100 ms away, that is easily
300 ms of pure setup. That is why so many optimizations (connection reuse, CDNs, HTTP/3) target
setup cost rather than the server's actual work.

## 01.3 IP addresses, ports, and sockets

### IP addresses

Every machine on a network needs an address so packets can be delivered to it. That is the **IP
address** (Internet Protocol address).

- **IPv4**: 32 bits, written as four numbers 0–255: `203.0.113.10`. About 4.3 billion addresses
  total — not enough for every device on Earth.
- **IPv6**: 128 bits, written in hex groups: `2001:db8::1`. Effectively unlimited.

Because IPv4 ran out, most home and office devices use **private addresses** (like `10.x.x.x`,
`192.168.x.x`, `172.16–31.x.x`) that are only meaningful inside a local network. A router
performs **NAT** (Network Address Translation): it rewrites outgoing packets so many private
devices share one public IP. Servers you want the world to reach need a **public IP** (or sit
behind something that has one, like a load balancer).

Inside a data center or cloud, your servers usually live in a private network and only the load
balancer faces the internet. That is both a security choice and an address-saving one.

### Ports

One machine runs many programs that talk on the network: a web server, a database, an SSH
daemon. The IP address gets the packet to the machine; the **port** (a 16-bit number, 0–65535)
gets it to the right program on that machine.

| Port | Usual service |
|---|---|
| 22 | SSH (remote shell) |
| 53 | DNS |
| 80 | HTTP |
| 443 | HTTPS |
| 3306 | MySQL |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka |

These are conventions, not laws. You can run a web server on port 8080 — browsers just assume 80
for `http://` and 443 for `https://` when you do not write a port.

### Sockets

A **socket** is the operating system's handle for one end of a network conversation. Your
program asks the OS for a socket, then reads and writes bytes to it like a file.

A TCP connection is uniquely identified by a 4-tuple:

```text
 (client IP, client port, server IP, server port)
 (198.51.100.7, 52811, 203.0.113.10, 443)
```

The client port is picked randomly by the client's OS (an **ephemeral port**). This is why one
server listening on port 443 can hold hundreds of thousands of simultaneous connections: each one
has a different client IP/port pair, so each 4-tuple is distinct.

DSA bridge: think of the OS as holding a hash map from the 4-tuple to a connection object. Every
arriving packet is looked up by that key and appended to that connection's buffer.

> **Common mistake:** Thinking a server can only have 65,535 connections "because ports are 16
> bits". The limit of 65,535 applies to *one client IP talking to one server IP:port*. A server
> listening on a single port can accept connections from millions of distinct client IP/port
> pairs; the real limits are memory, file descriptors, and CPU.

## 01.4 DNS: turning names into addresses

### The problem

Humans remember `shop.example.com`; networks route to `203.0.113.10`. Also, the IP behind a name
changes: you move to a new cloud, add servers, fail over to another region. We need a lookup
service that is global, fast, and can be updated. That is **DNS** (Domain Name System).

DSA bridge: DNS is a giant distributed key-value store, partitioned by the tree of name labels
(`.` → `com` → `example.com` → `shop.example.com`), with aggressive caching at every level.

### Who is involved

- **Stub resolver**: a tiny piece of your OS that asks someone else.
- **Recursive resolver**: a server (run by your ISP, your company, or a public provider) that does
  the legwork of finding the answer and caches it.
- **Root servers**: know where the servers for each top-level domain (`.com`, `.org`, `.in`) are.
- **TLD servers**: know which name servers are responsible for `example.com`.
- **Authoritative name server**: the server that holds the actual records for `example.com`.
  This is where you (or your DNS provider) configure your records.

```text
 Browser cache? OS cache? (hit -> done)
        |
        v miss
 +---------------------+   1. "shop.example.com?"   +-------------+
 | Recursive resolver  |--------------------------->| Root server |
 | (caches everything) |<---- "ask .com servers" ---+-------------+
 |                     |   2.                       +-------------+
 |                     |--------------------------->| .com TLD    |
 |                     |<-- "ask ns1.example.com" --+-------------+
 |                     |   3.                       +------------------+
 |                     |--------------------------->| Authoritative NS |
 |                     |<-- "203.0.113.10, TTL 300" +------------------+
 +---------------------+
        |
        v  answer returned to client and cached for TTL seconds
```

In practice almost every lookup is answered from a cache somewhere (browser, OS, resolver), so
the full walk is rare.

### Record types you should know

| Record | Maps | Example |
|---|---|---|
| **A** | name → IPv4 address | `shop.example.com A 203.0.113.10` |
| **AAAA** | name → IPv6 address | `shop.example.com AAAA 2001:db8::10` |
| **CNAME** | name → another name (alias) | `www.example.com CNAME shop.example.com` |
| **NS** | zone → its authoritative name servers | `example.com NS ns1.dnshost.net` |
| **MX** | domain → mail servers | `example.com MX 10 mail.example.com` |
| **TXT** | name → arbitrary text (domain ownership proofs, email policies) | `"v=spf1 ..."` |

A **CNAME** is how you point your domain at a provider: `cdn.example.com CNAME
example.cdnprovider.net` lets the CDN change its IPs without you editing anything. One rule: a
name that has a CNAME cannot have other records, which is why you usually cannot put a plain
CNAME on the bare domain `example.com` (many DNS providers offer a non-standard "alias" record to
work around this).

### TTL and caching

Every record has a **TTL** (time to live) in seconds. Resolvers cache the answer for that long.

- **Long TTL** (hours): fewer lookups, faster for users, less load on your DNS — but if you need
  to move traffic (server died, region down), clients keep using the old IP until their cache
  expires.
- **Short TTL** (30–60 s): you can redirect traffic quickly — but more lookups, and some clients
  and resolvers ignore very low TTLs anyway.

A common practice: keep TTL moderate normally, and lower it a day before a planned migration so
the switch propagates fast.

> **Common mistake:** "I changed the DNS record, so traffic moved instantly." Old answers live in
> caches until their TTL expires, and some clients cache longer than they should. DNS changes are
> gradual. Never rely on DNS alone for second-level failover.

### DNS-based load balancing

DNS can spread traffic before a request ever reaches your servers:

- **Round-robin DNS**: return several A records (`.10`, `.11`, `.12`); clients tend to pick
  different ones. Cheap, but DNS does not know if a server is dead, and caching makes the split
  uneven.
- **Geo DNS / latency-based DNS**: the authoritative server looks at where the query came from
  and answers with the IP of the nearest region. Users in India get the Mumbai data center, users
  in Germany get Frankfurt.
- **Health-checked DNS / failover**: the DNS provider probes your endpoints and stops handing out
  IPs of unhealthy ones (still limited by TTL caching).

Trade-off: DNS balancing is coarse (whole regions, slow to change). Fine-grained balancing across
individual servers is done by load balancers (chapter 12).

> **Say it like this:** "I'd use DNS for coarse routing — sending users to the nearest region —
> and a load balancer inside each region for per-server balancing and health checks, because DNS
> changes are cached and slow to take effect."

## 01.5 TCP and UDP: how bytes travel

### The problem

The IP layer delivers **packets** (small chunks, usually up to about 1,500 bytes on the wire) on
a "best effort" basis. Packets can be lost, duplicated, delayed, or arrive out of order. Your
program, though, wants to send "a 2 MB JSON response" and have it arrive intact. Something has to
bridge that gap. That is the **transport layer**, and there are two main choices.

### TCP: reliable, ordered byte stream

**TCP** (Transmission Control Protocol) gives you a **connection**: a two-way stream of bytes
that arrives complete and in order, or the connection reports an error.

How it achieves that:

- **Sequence numbers**: every byte is numbered, so the receiver can reorder and detect gaps.
- **Acknowledgements (ACKs)**: the receiver tells the sender what it got; unacknowledged data is
  **retransmitted** after a timeout.
- **Flow control**: the receiver advertises how much buffer it has, so a fast sender does not
  overwhelm a slow receiver.
- **Congestion control**: the sender starts slow ("slow start") and increases its sending rate
  until it sees loss, then backs off — so the network is shared fairly. Consequence: a brand-new
  connection cannot use full bandwidth immediately, another reason to reuse connections.

**The three-way handshake** sets up the connection:

```text
 Client                                Server
   |  SYN (seq=x)  "I want to talk"       |
   |------------------------------------->|
   |  SYN-ACK (seq=y, ack=x+1)            |
   |<-------------------------------------|
   |  ACK (ack=y+1)                       |
   |------------------------------------->|
   |  ... now data can flow both ways ... |

 Cost: 1 full round trip (RTT) before the first byte of request data.
```

A **round trip time (RTT)** is the time for a message to go to the other side and a reply to come
back. Same data center: roughly 0.5 ms. Across a continent: 30–80 ms. Across the world: 150–300 ms.

**Closing** a connection uses FIN packets in each direction. The side that closes first keeps the
socket in a `TIME_WAIT` state for a while (commonly about a minute) to catch stray late packets.
Servers that open and close huge numbers of short connections to the same destination can run out
of ephemeral ports this way — another reason for connection pools (chapter 03).

### Head-of-line blocking

Because TCP promises in-order delivery, if packet #5 is lost, packets #6, #7, #8 sit in the
receiver's buffer and are **not** handed to the application until #5 is retransmitted and
arrives. Everything waits behind the head of the line.

```text
 Sent:      [1][2][3][4][5][6][7][8]
 Arrived:   [1][2][3][4]   [6][7][8]     (5 lost)
 App sees:  [1][2][3][4] ...waiting.......... [5][6][7][8]
                          ^ 6,7,8 are here but blocked behind 5
```

This matters when one TCP connection carries several independent things (like multiple HTTP/2
streams, section 01.6): one lost packet stalls all of them.

### UDP: fire and forget

**UDP** (User Datagram Protocol) is a thin layer over IP: you send a **datagram** (a single
message) to an IP:port. No connection, no handshake, no retransmission, no ordering, no
congestion control. Messages may be lost, duplicated, or reordered — the application deals with
it, or does not care.

### TCP vs UDP

| | TCP | UDP |
|---|---|---|
| Connection setup | 3-way handshake (1 RTT) | none |
| Reliability | retransmits lost data | none (app's job) |
| Ordering | in-order byte stream | none, messages independent |
| Message boundaries | none (a stream of bytes; app frames messages) | each datagram is one message |
| Congestion control | built in | none (app's job) |
| Head-of-line blocking | yes | no |
| Typical uses | web (HTTP/1.1, HTTP/2), databases, SSH, email | DNS, video/voice calls, online games, metrics, QUIC/HTTP3 |

### When UDP wins

- **Late data is useless data.** In a video call, a voice packet from 300 ms ago is worthless;
  retransmitting it just delays the fresh audio. Better to skip it and play the next one.
- **Tiny request/response.** A DNS query fits in one packet. A handshake would triple the cost.
  If no answer arrives, the client just asks again.
- **You want to build your own reliability.** QUIC (section 01.6) runs on UDP precisely so it can
  implement smarter reliability per stream, in user space, without waiting for OS kernels to
  change.

> **Say it like this:** "TCP gives me a reliable ordered stream at the cost of a handshake and
> head-of-line blocking. I'd pick UDP when stale data is worthless, like live audio or game state,
> or when I'm building my own reliability on top, which is what QUIC does."

## 01.6 HTTP: the language of the web

**HTTP** (HyperText Transfer Protocol) is a request/response protocol that runs on top of a
transport. The client sends a request; the server sends exactly one response to it.

### Anatomy of a request

```http
POST /api/orders?source=web HTTP/1.1
Host: shop.example.com
Content-Type: application/json
Authorization: Bearer eyJhbGciOi...
Accept: application/json
Content-Length: 48

{"productId": "p-123", "quantity": 2}
```

- **Method** (`POST`): what kind of action. `GET` read, `POST` create/act, `PUT` replace,
  `PATCH` partial update, `DELETE` remove (semantics in chapter 02).
- **Path** (`/api/orders`) and **query string** (`?source=web`): which resource, plus parameters.
- **Headers**: key-value metadata. `Host` (which site, since one IP can host many), `Content-Type`
  (format of the body), `Authorization` (who you are), `Accept` (formats you can handle),
  `Cookie` (state the server asked the browser to store and send back).
- **Body**: the payload, optional. GET requests normally have none.

### Anatomy of a response

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/orders/o-789
Cache-Control: no-store
Content-Length: 61

{"id": "o-789", "status": "PENDING", "total": {"amount": 1998}}
```

- **Status line**: version + **status code** + reason phrase.
- **Headers**: `Content-Type`, `Location` (where the new thing lives), `Cache-Control` (may this
  be cached, for how long), `Set-Cookie`, etc.
- **Body**: the data.

### Status code families

| Range | Meaning | Examples |
|---|---|---|
| 1xx | informational | `101 Switching Protocols` (used to start WebSockets) |
| 2xx | success | `200 OK`, `201 Created`, `204 No Content` |
| 3xx | redirection | `301 Moved Permanently`, `304 Not Modified` |
| 4xx | client's fault | `400 Bad Request`, `401 Unauthorized`, `404 Not Found`, `429 Too Many Requests` |
| 5xx | server's fault | `500 Internal Server Error`, `502 Bad Gateway`, `503 Service Unavailable` |

The 4xx vs 5xx split matters: a 4xx says "retrying the same request will not help, fix it"; a
5xx says "it may work if you try again later". Clients, load balancers, and alerting all use this
distinction. Chapter 02 covers the most used codes in detail.

### HTTP is stateless

Each HTTP request stands alone; the protocol does not remember previous requests. If a server
needs to know "this is the same logged-in user", the client must send something every time — a
**cookie** with a session ID, or a **token** in the `Authorization` header. This statelessness is
what lets any server behind a load balancer handle any request (chapter 03).

### HTTP/1.0 → HTTP/1.1: keep-alive

Early HTTP opened a new TCP connection for every request. A page with 50 images meant 50
handshakes. **HTTP/1.1** made **persistent connections** (keep-alive) the default: after a
response, the connection stays open for the next request.

But HTTP/1.1 is still one request at a time per connection: request 2 cannot be sent and answered
until response 1 is fully done (pipelining existed but was broken in practice and is not used).
So a slow response blocks the ones behind it — head-of-line blocking at the HTTP level. Browsers
work around it by opening about **6 parallel connections per host**.

### HTTP/2: multiplexing

**HTTP/2** keeps the same methods, headers, and status codes, but changes how they travel:

- Messages are split into binary **frames**, each tagged with a **stream ID**.
- Many requests/responses (**streams**) are interleaved over **one** TCP connection —
  **multiplexing**. A slow response no longer blocks others at the HTTP level.
- **Header compression** (HPACK): headers repeated on every request (cookies, user agent) are
  sent once and referenced afterwards.

```text
 HTTP/1.1, one connection:      HTTP/2, one connection:
 [req A.........resp A]         [A1][B1][C1][A2][C2][B2][A3]...
 [req B....resp B]                frames of A, B, C interleaved
 (must wait for A)
```

The catch: all streams share one TCP connection, so a single lost packet triggers TCP
head-of-line blocking for **all** streams. On a lossy mobile network, HTTP/2 can be worse than
several HTTP/1.1 connections.

HTTP/2 is also the transport under **gRPC** (chapter 02).

### HTTP/3 and QUIC

**HTTP/3** runs on **QUIC**, a transport protocol built on UDP. QUIC re-implements reliability,
ordering, and congestion control, but **per stream**:

- A lost packet only stalls the stream it belongs to. No cross-stream head-of-line blocking.
- Transport and encryption handshakes are combined: a new connection typically needs **1 RTT**
  (vs TCP + TLS 1.3 = 2 RTTs), and a resumed connection can send data in **0 RTT**.
- **Connection migration**: a connection is identified by a connection ID, not the 4-tuple, so a
  phone switching from Wi-Fi to cellular can keep the same connection.

Trade-off: UDP is sometimes blocked or throttled by corporate networks, and QUIC's user-space
processing costs more CPU than kernel TCP. Clients fall back to HTTP/2 when HTTP/3 fails.

| | HTTP/1.1 | HTTP/2 | HTTP/3 |
|---|---|---|---|
| Transport | TCP | TCP | QUIC over UDP |
| Requests per connection at once | 1 | many (multiplexed) | many (multiplexed) |
| Format | text | binary frames | binary frames |
| Header compression | no | HPACK | QPACK |
| Head-of-line blocking | HTTP + TCP level | TCP level | none across streams |
| Typical new-connection setup with TLS | 2–3 RTT | 2–3 RTT | 1 RTT (0 on resume) |

> **Say it like this:** "HTTP/2 multiplexes many requests over one TCP connection, which fixes
> HTTP-level head-of-line blocking but not TCP-level blocking. HTTP/3 moves to QUIC over UDP so
> loss on one stream doesn't stall the others and setup is a round trip cheaper."

## 01.7 HTTPS and TLS

### The problem

Plain HTTP travels as readable bytes through many machines you do not control: Wi-Fi routers,
ISPs, backbone networks. Any of them could read your password, or change the response (inject
ads, swap a bank account number). We need protection.

**TLS** (Transport Layer Security) wraps the connection and gives three guarantees:

1. **Confidentiality**: eavesdroppers see only encrypted bytes.
2. **Integrity**: any tampering is detected and the connection is dropped.
3. **Authentication**: you are really talking to `shop.example.com`, not an impostor.

**HTTPS** is simply HTTP running inside a TLS connection.

What TLS does **not** hide: the server's IP address, roughly how much data flows, and usually the
hostname (sent in the clear in a field called SNI during the handshake, unless an extension that
encrypts it is used). And TLS protects data in transit only — not data sitting in your database.

### Certificates: how you know who you are talking to

Encryption alone is useless if you encrypted to an attacker. The server proves its identity with
a **certificate**: a document saying "the public key K belongs to `shop.example.com`", digitally
signed by a **Certificate Authority (CA)**.

- Your OS/browser ships with a list of trusted **root CAs**.
- Certificates form a **chain**: root CA signs an intermediate CA, the intermediate signs the
  site's certificate. The browser verifies each signature up to a trusted root.
- The browser also checks the certificate is not expired and the hostname matches.

The server keeps the matching **private key** secret. Only the real server can prove it holds
that key, so an impostor with a copied certificate still fails.

### The handshake at a high level (TLS 1.3)

```text
 Client                                         Server
   | ClientHello: supported ciphers,               |
   |   key share (client's half of a key exchange) |
   |---------------------------------------------->|
   |   ServerHello: chosen cipher, server key share|
   |   Certificate (chain)                         |
   |   CertificateVerify (signature proving it     |
   |     owns the private key)                     |
   |   Finished                                    |
   |<----------------------------------------------|
   | Verify cert chain + hostname + signature.     |
   | Both sides now derive the same session keys.  |
   | Finished  + first encrypted HTTP request      |
   |---------------------------------------------->|

 TLS 1.3: 1 RTT (on top of TCP's 1 RTT). TLS 1.2 needed 2 RTTs.
```

Two kinds of cryptography cooperate:

- **Asymmetric** (public/private keys) is slow but lets strangers agree on a secret and prove
  identity. Used only during the handshake.
- **Symmetric** (one shared key, like AES) is fast. Used for all the actual data.

Modern TLS uses an ephemeral key exchange, which gives **forward secrecy**: even if the server's
private key leaks later, recorded past traffic cannot be decrypted.

### Where TLS ends: TLS termination

Decrypting costs CPU and every server needs the certificate. A common setup: the load balancer
(or API gateway) **terminates TLS** — decrypts there — and talks to the app servers over the
private network, either plain HTTP or re-encrypted. In stricter setups (zero trust), service-to-
service traffic is also encrypted, often with **mutual TLS (mTLS)** where both sides present
certificates. More in chapter 15.

> **Common mistake:** "HTTPS means the site is safe." HTTPS means the connection is private and
> you reached the domain named in the certificate. A phishing site can have a perfectly valid
> certificate for its own phishing domain.

## 01.8 Latency, bandwidth, throughput

These three words are used loosely; in design discussions use them precisely.

- **Latency**: how long one thing takes, start to finish. "The request takes 120 ms." Measured in
  time.
- **Bandwidth**: the maximum data rate a link can carry. "This link is 1 Gbit/s." The width of
  the pipe.
- **Throughput**: how much is actually getting done per unit time. "We're serving 4,000 requests
  per second" or "copying at 300 MB/s". What actually flows through the pipe.

The pipe analogy:

```text
 Latency    = length of the pipe (how long a drop takes to travel through)
 Bandwidth  = width of the pipe  (how much can flow at once, at best)
 Throughput = what actually flows (limited by the narrowest part and by how you use it)

  [client] =================================================== [server]
            <---------------- 80 ms one way ------------------>
            ||||||||||||||| 100 Mbit/s wide |||||||||||||||||||
```

Why they are different:

- Sending one small message across the world: bandwidth irrelevant, latency (speed of light,
  roughly 200,000 km/s in fiber) dominates. Upgrading to a 10 Gbit link does nothing.
- Sending a 10 GB backup: latency irrelevant, bandwidth dominates.
- A chatty protocol doing 100 sequential round trips over a 50 ms link takes 5 seconds even on
  infinite bandwidth. Fix: fewer round trips (batching, multiplexing), not a bigger pipe.

**Little's Law** ties throughput and latency together for any stable system:

```text
 concurrency (requests in flight) = throughput (req/s) x latency (s)

 Example: 2,000 req/s, each taking 0.05 s -> 100 requests in flight on average.
 If latency jumps to 0.5 s at the same rate -> 1,000 in flight
 -> 10x the threads / connections / memory needed.
```

That is why a slow dependency can exhaust a server's thread pool even though traffic did not
change (chapter 13 covers the resilience fixes).

> **Say it like this:** "Latency is per-request time, throughput is completed work per second,
> bandwidth is the link's capacity. For cross-region calls I'm latency-bound, so I'd reduce round
> trips rather than add bandwidth."

## 01.9 Proxies: forward and reverse

A **proxy** is a server that sits in the middle and forwards traffic on someone's behalf. The
difference between the two kinds is *whose behalf*.

### Forward proxy (acts for clients)

Clients send their traffic to the proxy, which talks to the internet for them. The destination
servers see the proxy, not the individual clients.

Uses: corporate networks filtering or logging outbound traffic, caching for many users,
controlling which sites can be reached, hiding client identities.

### Reverse proxy (acts for servers)

Clients think they are talking to the website; actually they are talking to the reverse proxy,
which forwards to one of many backend servers. Clients never see the backends.

Uses: load balancing across app servers, TLS termination, caching responses, compression, rate
limiting, routing `/api/*` to one service and `/static/*` to another, hiding internal topology.
Load balancers, API gateways, and CDNs are all specialized reverse proxies (chapters 08 and 12).

```text
 Forward proxy (client side)              Reverse proxy (server side)

 [client A]--+                                            +-->[app server 1]
 [client B]--+-->[fwd proxy]--> Internet --> [rev proxy]--+-->[app server 2]
 [client C]--+                                            +-->[app server 3]

 Servers see: the forward proxy           Clients see: the reverse proxy
 Configured by: the client's network      Configured by: the site owner
```

> **Common mistake:** Using "proxy" to mean only the forward kind. In backend design, when someone
> says "put a proxy in front of it", they almost always mean a reverse proxy.

## 01.10 Putting it together: what to say about "type a URL and press Enter"

This is a classic opener. A strong, structured answer at the right altitude:

1. **Parse the URL**: scheme `https`, host `shop.example.com`, path `/cart`. Default port 443.
2. **DNS**: browser cache → OS cache → recursive resolver → (root → TLD → authoritative if not
   cached) → IP address. Geo DNS may hand back the nearest region's IP.
3. **TCP**: three-way handshake with that IP on port 443 (1 RTT). (With HTTP/3, QUIC over UDP
   combines this with step 4.)
4. **TLS**: handshake, certificate chain verified, session keys derived (1 RTT in TLS 1.3).
5. **HTTP request**: `GET /cart` with `Host`, cookies, `Accept` headers.
6. **Server side**: CDN or load balancer receives it, terminates TLS, routes to a healthy app
   server. The app server authenticates the session, reads from cache and/or database, renders
   HTML or JSON.
7. **Response**: status code, headers (including caching headers), body.
8. **Rendering**: browser parses HTML, discovers CSS/JS/images, fetches them (often from a CDN,
   multiplexed over HTTP/2 or HTTP/3), runs JS, paints.
9. **Reuse**: the connection stays open for subsequent requests; DNS and TLS session results are
   cached.

You can then offer to zoom into any step: "Do you want me to go deeper on DNS, TLS, or what the
backend does?"

> **Say it like this:** "Before the server does any real work we pay for DNS, a TCP handshake and
> a TLS handshake — that's two to three round trips — which is why connection reuse, CDNs near the
> user and HTTP/3 matter so much for perceived latency."

## 01.11 The layers, briefly

You will hear "layer 4" and "layer 7" constantly, especially with load balancers. The useful
simplified stack:

| Layer (common name) | Number in the OSI model | What it deals with | Examples |
|---|---|---|---|
| Application | 7 | meaning of messages | HTTP, gRPC, DNS, SMTP |
| (Security) | sits between 4 and 7 | encryption, identity | TLS |
| Transport | 4 | connections/ports, reliability | TCP, UDP, (QUIC over UDP) |
| Network | 3 | addressing and routing between networks | IP |
| Link / physical | 2 / 1 | one hop on a wire or radio | Ethernet, Wi-Fi |

- A **layer 4 load balancer** looks only at IPs and ports and forwards TCP/UDP flows; it is fast
  but cannot route by URL.
- A **layer 7 load balancer** understands HTTP: it can route `/api/users` to one service, read
  headers and cookies, retry, and rewrite. It costs more CPU and must terminate TLS to read the
  request. Chapter 12 goes deep.

Each layer wraps the one above it, like envelopes in envelopes: an HTTP request is inside a TLS
record, inside TCP segments, inside IP packets, inside Ethernet frames.

## 01.12 Misconceptions worth clearing up

- **"The server pushes the page to me."** In plain HTTP the client always asks first. Server push
  experiences (chat, notifications) are built with WebSockets, SSE, long polling, or OS push
  services (chapter 02).
- **"More bandwidth makes my API faster."** For small JSON calls, latency and round-trip count
  dominate. Bandwidth helps bulk transfers.
- **"UDP is unreliable, so it is bad."** It is *unopinionated*. Reliability can be added exactly
  where needed, which is why QUIC and real-time media use it.
- **"HTTP/2 always beats HTTP/1.1."** Usually, but on lossy links one shared TCP connection can
  suffer more from head-of-line blocking.
- **"DNS is only a lookup; it does not matter for design."** DNS is your first routing and
  failover layer, and its caching (TTL) decides how fast you can move traffic.
- **"Ping time is the latency of my API."** Ping is one network round trip. Your API latency adds
  DNS, handshakes, server processing, database calls, and response transfer.

## 01.13 Recap

- Clients are untrusted renderers; the backend owns data and enforces rules.
- A request pays for DNS, a TCP handshake (1 RTT) and a TLS handshake (1 RTT in TLS 1.3) before
  any HTTP is exchanged; connection reuse amortizes this.
- IP addresses locate machines, ports locate programs, and a connection is a unique 4-tuple —
  so one server port can hold a huge number of connections.
- DNS is a cached, hierarchical key-value system; TTL trades faster failover against more lookups;
  geo DNS routes users to the nearest region.
- TCP = reliable ordered stream with handshake and head-of-line blocking; UDP = independent
  datagrams with no guarantees, good when stale data is worthless or you build your own reliability.
- HTTP is stateless request/response; 4xx is the client's fault, 5xx is the server's.
- HTTP/1.1 reuses connections but serializes requests; HTTP/2 multiplexes on one TCP connection;
  HTTP/3 uses QUIC over UDP to remove cross-stream blocking and save a round trip.
- TLS gives confidentiality, integrity, and authentication via CA-signed certificates; it is often
  terminated at the load balancer.
- Latency = time per request, bandwidth = pipe capacity, throughput = actual work rate; Little's
  Law: in-flight = rate x latency.
- Forward proxies act for clients; reverse proxies (load balancers, gateways, CDNs) act for servers.

## 01.14 Check yourself

1. Your service runs on one IP and port 443. Someone claims it can never hold more than 65,535
   concurrent connections. Are they right?

<details><summary>Answer</summary>

No. A TCP connection is identified by the 4-tuple (client IP, client port, server IP, server
port). The 16-bit limit applies to the client port for a single client IP talking to the same
server IP:port. Different clients have different IPs and ports, so the server can hold far more
connections; the practical limits are memory, file descriptors, and CPU.

</details>

2. You need to move traffic from an old data center to a new one tomorrow with minimal disruption.
   What do you do with DNS, and why?

<details><summary>Answer</summary>

Lower the TTL on the relevant records well ahead of time (at least one old-TTL period before the
switch, for example a day before), so caches hold the answer only briefly. Then change the record
at switch time; clients pick up the new IP within the short TTL. Keep the old site serving for a
while, because some clients and resolvers cache longer than the TTL. Raise the TTL again afterwards.

</details>

3. Why do video calls typically run over UDP rather than TCP?

<details><summary>Answer</summary>

In a live call, late audio/video is useless. TCP would retransmit a lost packet and hold back all
newer data until it arrives (head-of-line blocking), adding delay and stutter. Over UDP the
application can skip lost packets, use its own error concealment, and always play the freshest
data.

</details>

4. HTTP/2 multiplexes requests over one connection. Why can it perform worse than HTTP/1.1 on a
   lossy mobile network, and what fixes it?

<details><summary>Answer</summary>

All HTTP/2 streams share one TCP connection. TCP delivers bytes strictly in order, so one lost
packet blocks every stream until it is retransmitted. HTTP/1.1 browsers use about six separate
connections, so a loss stalls only one of them. HTTP/3 fixes this by running over QUIC, which
handles loss per stream, so a lost packet only stalls its own stream.

</details>

5. What three guarantees does TLS give, and what does the certificate specifically contribute?

<details><summary>Answer</summary>

Confidentiality (encrypted), integrity (tampering detected), and authentication (you reach the
real server). The certificate binds the server's public key to its hostname, signed by a trusted
CA; combined with the server proving it holds the matching private key during the handshake, it
prevents an attacker from impersonating the site and intercepting the key exchange.

</details>

6. A service handles 1,000 req/s at 20 ms average latency. A downstream database slows down and
   latency rises to 400 ms at the same request rate. What happens to concurrency, and why might
   the service fall over?

<details><summary>Answer</summary>

By Little's Law, in-flight requests = rate x latency. Before: 1,000 x 0.02 = 20. After: 1,000 x
0.4 = 400 — twenty times more. If the server has a thread pool or connection pool sized for, say,
200, requests queue up, latency grows further, and memory/threads run out. Traffic did not
change; latency did.

</details>

7. What is the difference between a forward proxy and a reverse proxy? Give one use of each.

<details><summary>Answer</summary>

A forward proxy acts on behalf of clients (configured on the client side); destination servers
see the proxy instead of the client. Use: corporate outbound filtering or caching. A reverse
proxy acts on behalf of servers (configured by the site owner); clients see the proxy instead of
the backends. Use: load balancing and TLS termination in front of app servers.

</details>

8. Walk through, in order, the round trips needed before the first HTTP request byte reaches a
   server on a fresh HTTPS connection over HTTP/2, assuming DNS is not cached.

<details><summary>Answer</summary>

DNS resolution (one or more round trips to the resolver, which may itself walk root → TLD →
authoritative), then the TCP three-way handshake (1 RTT), then the TLS 1.3 handshake (1 RTT; the
first HTTP request is sent together with the client's Finished message). So roughly DNS + 2 RTTs.
With HTTP/3 (QUIC), the transport and TLS handshakes are combined into about 1 RTT.

</details>
