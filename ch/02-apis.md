# 02 — APIs: how services talk

Every box in a system diagram talks to other boxes through an API. In design conversations you
will be asked "what does the API look like?" almost immediately. This chapter gives you the
vocabulary (REST, idempotency, pagination, gRPC, GraphQL, WebSockets, webhooks) and, more
importantly, the reasons behind each choice so you can defend an API design.

## 02.1 What an API is

An **API** (Application Programming Interface) is a contract: "send me a request shaped like
this, and I promise a response shaped like that". It hides how the work is done.

- A **public API** is used by outside developers (a payments API, a maps API).
- An **internal API** is used between your own services (the order service calling the inventory
  service).
- A **client API** is used by your own frontend and mobile apps.

Why the contract matters so much: once clients depend on it, you cannot change it freely. A
mobile app version released a year ago is still calling your API today. Bad API decisions live for
years, which is why designers spend real time on them.

A good API is **predictable** (same naming, errors and pagination everywhere), **hard to misuse**
(retries are safe, defaults sensible), **evolvable** (you can add things without breaking existing
clients) and **efficient** (clients do not need 20 calls for one screen).

## 02.2 REST

### The idea

**REST** (Representational State Transfer) is a style of designing HTTP APIs around
**resources** — the nouns of your domain (users, orders, products) — and using the standard HTTP
methods as the verbs.

```text
 Resource-oriented (REST)          Action-oriented (not REST)
 GET    /orders/42                 POST /getOrder        {"id": 42}
 POST   /orders                    POST /createOrder     {...}
 PATCH  /orders/42                 POST /updateOrderStatus {...}
 DELETE /orders/42                 POST /deleteOrder     {"id": 42}
```

Why the resource style helps: everyone already knows what `GET` and `DELETE` mean, so caches,
proxies, client libraries and humans can all reason about your API without reading docs. A `GET`
can be cached and retried by any proxy; a `POST /getOrder` cannot be, because nothing tells the
proxy it is a read.

Key REST properties: resources are identified by URLs (`/orders/42` is one order); the server
sends a **representation** of the resource (usually JSON); each request is **stateless**, carrying
everything needed including the auth token; and every resource shares the same small set of
methods (the **uniform interface**).

### HTTP methods and their semantics

Two properties are crucial:

- **Safe**: the method does not change server state. The client may call it freely (prefetch,
  crawl, retry).
- **Idempotent**: doing it once or N times leaves the server in the **same state**. The response
  may differ (a second DELETE may return 404), but the effect is the same. Idempotent requests can
  be retried safely after a timeout.

| Method | Typical use | Safe | Idempotent | Has body |
|---|---|---|---|---|
| GET | read a resource or list | yes | yes | no |
| HEAD | like GET but headers only | yes | yes | no |
| OPTIONS | ask what is allowed (used by browsers for CORS) | yes | yes | no |
| POST | create a child resource, or trigger an action | no | **no** | yes |
| PUT | replace a resource entirely (create at a known URL) | no | yes | yes |
| PATCH | partially update a resource | no | not guaranteed | yes |
| DELETE | remove a resource | no | yes | usually no |

Notes:

- `PUT /users/7 {full user}` sent twice leaves the same user: idempotent.
- `PATCH /accounts/7 {"balanceDelta": 10}` sent twice adds 20: not idempotent. A patch like
  `{"email": "a@b.com"}` (set a value) happens to be idempotent. Design patches as "set" rather than
  "increment" when you can.
- `POST /orders` sent twice creates two orders. This is the core retry problem, solved by
  idempotency keys (section 02.4).

> **Say it like this:** "GET, PUT and DELETE are idempotent, so clients and proxies can retry them
> on a timeout. POST isn't, so for anything like creating a payment I'd require an idempotency key
> to make retries safe."

### Status codes: families and the 15 you will actually use

| Family | Meaning | Who should act |
|---|---|---|
| 2xx | it worked | — |
| 3xx | look elsewhere / use your cached copy | client follows |
| 4xx | your request is wrong; retrying the same thing will not help | client fixes the request |
| 5xx | the server failed; the same request may succeed later | client may retry (with backoff) |

| Code | Name | When to use |
|---|---|---|
| 200 | OK | successful GET, or an update returning the resource |
| 201 | Created | POST created a resource; include `Location` header |
| 202 | Accepted | request accepted for asynchronous processing, not done yet |
| 204 | No Content | success with no body (common for DELETE) |
| 301 | Moved Permanently | resource has a new permanent URL (clients and caches update) |
| 304 | Not Modified | conditional GET: your cached copy is still valid |
| 400 | Bad Request | malformed JSON, missing or invalid field |
| 401 | Unauthorized | not authenticated: missing/invalid/expired credentials |
| 403 | Forbidden | authenticated, but not allowed to do this |
| 404 | Not Found | resource does not exist (or you may not know it exists) |
| 409 | Conflict | state conflict: duplicate unique value, version mismatch |
| 422 | Unprocessable Content | well-formed but semantically invalid (some APIs use 400 instead) |
| 429 | Too Many Requests | rate limited; include `Retry-After` |
| 500 | Internal Server Error | unexpected bug on the server |
| 503 | Service Unavailable | overloaded or in maintenance; temporary; may include `Retry-After` |

Two more worth recognizing: **502 Bad Gateway** (a proxy got a bad response from the upstream
server) and **504 Gateway Timeout** (a proxy gave up waiting for the upstream). When you see them,
the problem is usually behind the load balancer, not in it.

> **Common mistake:** Returning `200 OK` with `{"error": "not found"}` in the body. Monitoring,
> caches, load balancers and client libraries all look at the status code. A failure disguised as
> 200 is invisible to them, and a cache may even store it.

> **Common mistake:** Mixing up 401 and 403. 401 = "I don't know who you are" (log in). 403 =
> "I know who you are, and the answer is no".

### Good URL design

- **Nouns, plural, lowercase**: `/orders`, `/orders/42`, not `/getOrders` or `/Order`.
- **Hierarchy for ownership**: `/users/7/orders` = orders belonging to user 7. Keep nesting
  shallow (one or two levels); `/orders/42` is better than `/users/7/orders/42/items/3/notes`.
- **IDs in the path, options in the query**: `/orders/42` vs `/orders?status=shipped&limit=20`.
- **Actions that are not CRUD**: model them as a sub-resource or a state change.
  `POST /orders/42/cancellation` or `POST /orders/42:cancel`, or `PATCH /orders/42
  {"status": "CANCELLED"}` if the state machine allows it. Pick one convention and keep it.
- **No verbs for CRUD, no file extensions, no trailing-slash inconsistency.**
- **Opaque, non-sequential public IDs** (`o_8fK2x...` or UUIDs) are often preferred over
  auto-increment integers: they do not leak your volume ("order 1000234 means they have about a
  million orders") and do not let attackers enumerate records.

```text
 GET    /products?category=shoes&sort=-price&limit=20     list, filtered, sorted
 GET    /products/p-123                                   one product
 POST   /carts/c-9/items        {"productId":"p-123"}      add item to cart
 DELETE /carts/c-9/items/i-4                              remove item
 POST   /orders                 {"cartId":"c-9"}          place order
 POST   /orders/o-789/cancellation                        cancel (action as resource)
```

## 02.3 Request and response design

### Requests

- Use JSON with a consistent naming style (`camelCase` or `snake_case`, never both).
- Validate everything on the server: types, ranges, lengths, enums. Never trust the client
  (chapter 01).
- Accept only the fields you expect; ignore or reject unknown fields consistently.
- Put money as **integer minor units plus currency** (`{"amount": 1999, "currency": "INR"}`) —
  never floating point, because `0.1 + 0.2 != 0.3` in binary floats.
- Use ISO-8601 timestamps in UTC: `"2026-10-10T08:30:00Z"`.

### Responses

- Return the resource after create/update (with its `id` and server-generated fields such as
  `createdAt`, `status`) so the client need not re-fetch.
- **Envelope or not?** Some APIs wrap everything (`{"data": ..., "meta": ...}`). It makes room for
  pagination info and warnings. Either style is fine — be consistent.
- **Partial responses**: allow `?fields=id,name,price` if payloads are big and clients need small
  subsets.
- **Compression**: `Content-Encoding: gzip` (or brotli) cuts JSON size a lot for large responses.

### Conditional requests and caching

- `ETag`: a version fingerprint of a resource. The client sends `If-None-Match: "v7"`; if
  unchanged, the server replies `304 Not Modified` with no body — saves bandwidth.
- `If-Match: "v7"` on an update: "only apply if the resource is still version 7". If someone else
  changed it, return `412 Precondition Failed`. This is **optimistic concurrency control** over
  HTTP and prevents lost updates when two users edit the same thing.
- `Cache-Control: max-age=60` lets browsers and CDNs cache a response. Chapter 08 goes deeper.

## 02.4 Idempotency keys: making retries safe

### The problem: retries create duplicates

Networks fail in an annoying way: the client cannot tell "my request never arrived" from "my
request was processed but the response got lost".

```text
 Client                         Payment API                    DB
   | POST /payments  $50          |                              |
   |----------------------------->| charge card, insert row ---->|  (succeeds)
   |                              |                              |
   |     response lost / timeout  X<-----------------------------|
   |                              |                              |
   | (did it work? no idea)       |                              |
   | retry POST /payments $50     |                              |
   |----------------------------->| charge card AGAIN ---------->|  customer pays $100
```

If the client does not retry, a real payment may be lost. If it does retry, it may pay twice.
For money, both are unacceptable. Since networks will always have timeouts, the fix must make the
**retry safe**.

### The idea

The client generates a unique **idempotency key** (usually a UUID) for each *logical* operation,
and sends the same key on every retry of that operation:

```http
POST /v1/payments HTTP/1.1
Idempotency-Key: 6f1c2a9e-8d4b-4c4f-9b0a-2f1e7c3d5a10
Content-Type: application/json

{"amount": 5000, "currency": "USD", "source": "card_tok_abc"}
```

The server remembers what it did for each key:

```text
 on request(key, body):
   rec = store.get(key)
   if rec exists:
       if rec.requestHash != hash(body): return 422 (key reused with different request)
       if rec.state == IN_PROGRESS:      return 409 (still processing, retry later)
       return rec.savedResponse          # replay, do NOT charge again
   store.insertIfAbsent(key, state=IN_PROGRESS, requestHash=hash(body))
       # atomic: if another request inserted first, treat as "rec exists"
   response = doThePayment(body)
   store.update(key, state=DONE, savedResponse=response)   # keep for e.g. 24 h
   return response
```

Important details:

- **The insert must be atomic** (a unique constraint on the key in the database, or `SET NX` in
  Redis). Otherwise two concurrent retries both see "absent" and both charge.
- **Store the response**, so a retry gets the identical answer (same payment ID) rather than an
  error.
- **Scope keys per client/account**, so one client cannot collide with another's key.
- **Expire keys** after a window (commonly around 24 hours) to bound storage.
- **Ideally record the key in the same database transaction as the business write**, so you can
  never end up with "payment done but key not recorded".
- If the work crashes midway, the record stays IN_PROGRESS; you need a recovery path (time out the
  record and either complete or roll back the operation).

This pattern is widely used by payment APIs. It turns "at-least-once delivery" (the client
retries until it gets an answer) into "exactly-once effect" (the payment happens once).

> **Say it like this:** "Because a timeout doesn't tell the client whether the charge happened,
> the client retries with the same Idempotency-Key. The server stores the key atomically with the
> result, and replays the stored response on a retry instead of charging again."

### Natural idempotency

Sometimes you do not need a separate key:

- Use `PUT` with a client-chosen ID: `PUT /orders/{clientGeneratedUuid}`. Twice = same order.
- Make updates absolute, not relative: "set status = SHIPPED" is idempotent; "add 1 to count" is
  not.
- Use a unique constraint on a natural business key (e.g. one payout per `invoiceId`).

## 02.5 Pagination

### The problem

`GET /orders` for a seller with 3 million orders cannot return everything: huge payload, slow
query, memory blowup. You return a **page** at a time. The question is how the client asks for
"the next page".

### Offset pagination

```http
GET /orders?limit=20&offset=40
```

```sql
SELECT * FROM orders WHERE seller_id = 7
ORDER BY created_at DESC
LIMIT 20 OFFSET 40;
```

Simple, and the client can jump to page 37. But it breaks at scale:

1. **Cost grows with the offset.** To return rows 1,000,000–1,000,020, the database must walk
   and discard the first million rows (an index cannot jump to "the millionth entry"
   directly; it has to count). Deep pages get slower and slower: O(offset + limit).
2. **Unstable under writes.** If a new order is inserted at the top while the user is paging,
   every row shifts down by one: the user sees an item twice. If one is deleted, they skip one.

```text
 Page 1 (offset 0):  [A B C D E]        new item X inserted at the top
 Page 2 (offset 5):  [E F G H I]        E shows up again (everything shifted)
```

### Cursor (keyset) pagination

Instead of "skip N rows", the client says "give me rows **after** this one". The server returns
an opaque **cursor** encoding the position of the last item.

```http
GET /orders?limit=20
-> {"data": [...20 orders...], "nextCursor": "eyJ0IjoiMjAyNi0xMC0w...OTg3fQ"}

GET /orders?limit=20&cursor=eyJ0IjoiMjAyNi0xMC0w...OTg3fQ
```

The cursor decodes to the sort key of the last row seen, e.g. `(created_at, id)`:

```sql
SELECT * FROM orders
WHERE seller_id = 7
  AND (created_at, id) < ('2026-10-09T12:00:00Z', 987)   -- strictly after the last row
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

With an index on `(seller_id, created_at, id)`, the database seeks straight to the position and
reads 20 entries: O(log n + limit), regardless of how deep you are. DSA bridge: it is
`lower_bound` on a sorted index plus a walk of 20 entries, versus walking from the start.

Why include `id`? `created_at` may not be unique; two orders in the same millisecond would make
the boundary ambiguous. Adding a unique tie-breaker makes the order total.

(Some databases do not support the row-value comparison `(a, b) < (x, y)`; then write it as
`a < x OR (a = x AND b < y)`.)

| | Offset | Cursor / keyset |
|---|---|---|
| Deep page cost | grows with offset | constant (index seek) |
| Stable under inserts/deletes | no (skips/dupes) | yes |
| Jump to page N | yes | no (only next/previous) |
| Total count / page numbers | easy (but COUNT is itself costly on big tables) | usually not provided |
| Best for | small admin tables, search results with page numbers | feeds, timelines, large lists, APIs, infinite scroll |

Make the cursor **opaque** (base64 of the values, possibly signed) so clients do not build
dependencies on its format and you can change it later.

> **Say it like this:** "I'd use cursor pagination keyed on (created_at, id). Offset pagination
> makes the database scan and throw away all skipped rows, so deep pages get slow, and it
> duplicates or skips items when new rows are inserted."

## 02.6 Filtering, sorting, searching

- **Filtering** via query parameters: `GET /orders?status=SHIPPED&createdAfter=2026-10-01`.
- **Sorting**: `?sort=-createdAt,price` (minus for descending) — a common convention.
- **Allow-list** which fields can be filtered and sorted. Every allowed combination needs an index
  to be fast (chapter 05); an unrestricted `sort=` on any column is an invitation to full table
  scans.
- For free-text search (`?q=red running shoes`) a relational `LIKE '%...%'` does not scale; that
  is a job for a search engine with an inverted index (chapter 09).
- With cursor pagination, the cursor is tied to the sort order: changing filters or sort means
  starting from the first page.

## 02.7 Versioning

### The problem

You need to rename a field, change a type, or remove an endpoint. Old clients (that year-old app
version) would break.

### Rule one: avoid breaking changes

Most evolution can be done **additively**:

| Safe (non-breaking) | Breaking |
|---|---|
| add a new optional field to a response | remove or rename a field |
| add a new endpoint | change a field's type or meaning |
| add an optional request parameter | make an optional parameter required |
| add a new enum value (if clients were told to tolerate unknowns) | change URL structure |
| | change error codes or auth requirements |

Clients should follow the **tolerant reader** rule: ignore fields they do not understand.

### When you must break: version it

| Approach | Example | Notes |
|---|---|---|
| URL path | `/v1/orders`, `/v2/orders` | most common; visible, easy to route and cache |
| Header | `Accept: application/vnd.shop.v2+json` or `Api-Version: 2026-10-01` | cleaner URLs; harder to test in a browser |
| Query param | `/orders?version=2` | simple, less common |

Then **deprecate** the old version: announce a date, add a `Deprecation`/`Sunset` header, watch
traffic on the old version fall, and remove it when usage is near zero. Running two versions
means maintaining two code paths, so keep the number of live versions small.

## 02.8 Error format

Clients need machine-readable errors, not just a sentence. Use one shape everywhere. A common
standard is "Problem Details" (`application/problem+json`); a typical shape:

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "https://api.shop.example.com/errors/validation",
  "title": "Invalid request",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "detail": "quantity must be between 1 and 10",
  "errors": [{"field": "items[0].quantity", "reason": "max 10"}],
  "requestId": "req_7Hc2..."
}
```

- **Status code** for the category (machines, proxies).
- **Stable error `code`** that clients can switch on; do not make clients parse messages.
- **Human-readable detail** for developers.
- **Field-level errors** for validation.
- **Request ID** so support can find the exact log line (chapter 14).
- **Never leak internals**: no stack traces, SQL, or hostnames in responses (chapter 15).

## 02.9 RPC and gRPC

### The idea of RPC

**RPC** (Remote Procedure Call) means calling a function on another machine as if it were local:
`inventory.reserve(sku, qty)`. Instead of thinking in resources and HTTP verbs, you think in
operations. REST is resource-oriented; RPC is action-oriented.

### gRPC

**gRPC** is a widely used RPC framework. Its pieces:

- **Protocol Buffers (protobuf)**: you define messages and services in a `.proto` schema file.
  Messages are encoded in a compact **binary** format — smaller and faster to parse than JSON.
- **Code generation**: from the `.proto`, tools generate client stubs and server interfaces in
  many languages. Types are checked at compile time on both sides.
- **HTTP/2 transport**: multiplexed streams over one long-lived connection (chapter 01).
- **Streaming**: besides one request/one response, it supports server streaming, client
  streaming, and bidirectional streaming.
- **Deadlines**: each call carries a deadline that propagates to downstream calls, so work stops
  when the caller has already given up.

```protobuf
syntax = "proto3";

service Inventory {
  rpc Reserve (ReserveRequest) returns (ReserveReply);              // unary
  rpc WatchStock (WatchRequest) returns (stream StockUpdate);       // server streaming
}

message ReserveRequest {
  string sku = 1;      // field numbers, not names, go on the wire
  int32 quantity = 2;
  string order_id = 3;
}
message ReserveReply { bool ok = 1; string reservation_id = 2; }
message WatchRequest { string sku = 1; }
message StockUpdate { string sku = 1; int32 available = 2; }
```

Field **numbers** are what go on the wire, so you can rename a field freely, but must never reuse
or change a number. Adding new fields with new numbers is backward compatible: old readers skip
unknown fields.

### REST vs gRPC

| | REST + JSON | gRPC + protobuf |
|---|---|---|
| Payload | text JSON, human-readable | binary, compact, faster to parse |
| Contract | optional (OpenAPI spec) | mandatory `.proto`, generated code |
| Transport | HTTP/1.1 or 2 | HTTP/2 |
| Streaming | not native (use SSE/WebSockets) | built in, both directions |
| Browser support | native | needs a proxy layer (gRPC-Web) |
| Debugging | curl, browser | special tools |
| Caching by proxies/CDNs | natural for GET | not really |
| Typical use | public APIs, browser/mobile clients | internal service-to-service calls |

> **Say it like this:** "Externally I'd expose REST with JSON because every client and tool
> understands it. Between internal services I'd use gRPC: a strict protobuf contract, smaller
> payloads, HTTP/2 multiplexing and deadlines that propagate across calls."

## 02.10 GraphQL

### The problem it solves

A mobile home screen needs: the user's name, their last 3 orders with product titles and images,
and unread notification count. With REST:

- **Under-fetching**: one endpoint does not have everything, so the app makes several calls
  (`/me`, `/orders?limit=3`, `/products/...` for each, `/notifications/count`). On a phone with
  150 ms RTT, sequential calls are slow.
- **Over-fetching**: `/orders` returns 40 fields per order when the screen needs 3. Wasted bytes
  on a mobile network.

### The idea

**GraphQL** exposes one endpoint (usually `POST /graphql`) and a **typed schema**. The client
sends a query describing exactly the shape it wants; the server returns exactly that.

```graphql
query HomeScreen {
  me {
    name
    orders(last: 3) {
      id
      total
      items { product { title imageUrl } }
    }
    unreadNotificationCount
  }
}
```

The server implements a **resolver** function for each field; the GraphQL engine walks the query
and calls them. Writes are done with **mutations**; real-time updates with **subscriptions**
(usually over WebSockets).

### The N+1 problem

Naive resolvers cause a classic performance bug:

```text
 resolve me.orders        -> 1 query: SELECT ... FROM orders WHERE user=7 LIMIT 3
 for each order (3):
   resolve items          -> 3 queries
   for each item (say 10):
     resolve product      -> 30 queries, one product at a time
 Total: 1 + 3 + 30 = 34 queries for one screen.
```

"N+1" = one query for the list, then one more per element. The standard fix is a **DataLoader**
(batching + per-request caching): resolvers ask for product 5, 9, 12…; the loader collects all
IDs requested in the same tick and issues one `SELECT ... WHERE id IN (5, 9, 12, ...)`. The same
N+1 trap exists in REST code and ORMs; GraphQL just makes it very easy to fall into.

### Costs and trade-offs

| Gain | Pay |
|---|---|
| client gets exactly what it needs in one round trip | server complexity (resolvers, batching) |
| strongly typed schema, great tooling, self-documenting | HTTP caching is harder (everything is POST to one URL) |
| frontends move fast without new backend endpoints | a client can write a very expensive query (deep nesting) → need depth/complexity limits, timeouts, persisted queries |
| one gateway can aggregate many backend services | errors often return 200 with an `errors` array; monitoring needs care |
| | rate limiting by "request" is meaningless; you limit by query cost |

When to use: many different clients (web, iOS, Android, partners) with different data needs over
a rich, connected domain. When not: simple CRUD service, internal service-to-service calls (gRPC
fits better), or when CDN caching of responses is critical.

> **Common mistake:** Saying "GraphQL is faster than REST". It removes round trips and
> over-fetching for the client, but the server does the same (or more) work, and an unguarded
> schema lets clients write queries that hammer your databases.

## 02.11 Real-time: polling, long polling, SSE, WebSockets

### The problem

HTTP is client-initiated. But some data originates on the server: a new chat message, a price
change, "your driver has arrived". How does the server tell the client?

### Short polling

The client asks every few seconds: "anything new?"

Simple, works everywhere, but wasteful (most responses are empty) and adds up to one interval of
delay. With a million clients polling every 2 s, that is 500,000 req/s of mostly nothing.

### Long polling

The client asks (`GET /messages?since=100`); the server **holds the request open** until there
is data or a timeout (say 30 s). On any response, the client immediately asks again with the new
`since` value.

Near-instant delivery, still plain HTTP. Costs: one held connection per client, reconnect
overhead after every message, and tricky ordering if messages arrive while reconnecting (hence
`since=` cursors).

### Server-Sent Events (SSE)

The client opens one HTTP request; the server keeps the response open and **streams** events as
lines of text (`Content-Type: text/event-stream`). One direction only: server → client. Browsers
have a built-in `EventSource` API that **reconnects automatically** and sends a `Last-Event-ID`
header so the server can resume.

```text
 GET /stream  ->  HTTP/1.1 200 OK
                  Content-Type: text/event-stream

                  id: 101
                  data: {"type":"message","text":"hi"}

                  id: 102
                  data: {"type":"typing","user":"u9"}
                  ...
```

Great for feeds, notifications, live scores, progress updates, and streaming generated text.

### WebSockets

A **WebSocket** starts as an HTTP request with `Upgrade: websocket`; the server answers `101
Switching Protocols`, and from then on the TCP connection carries a **full-duplex**, message-based
channel: both sides can send at any time, with tiny per-message overhead.

```text
 Client                                Server
   | GET /chat  Upgrade: websocket       |
   |------------------------------------>|
   |        101 Switching Protocols      |
   |<------------------------------------|
   |== bidirectional frames, any time ===|
   | {"send":"hello"} ------------------>|
   |<------------------ {"msg":"hi back"}|
   |<------------------ {"typing":"u9"}  |
```

Costs: long-lived **stateful** connections. Each server holds many open sockets (tens or hundreds
of thousands per machine is achievable with event-loop servers, chapter 03). Load balancers must
support them; deploys must drain connections; and if user A is connected to server 1 and user B
to server 7, you need a way to route a message between servers (commonly a pub/sub layer,
chapter 11). Reconnection and missed-message recovery are your job.

### Comparison

| | Short polling | Long polling | SSE | WebSockets |
|---|---|---|---|---|
| Direction | client pulls | client pulls (server delays reply) | server → client | both ways |
| Latency of updates | up to poll interval | near real time | near real time | near real time |
| Connection | new request each time | held, re-opened per message | one long HTTP response | one long upgraded TCP connection |
| Server cost | many wasted requests | one held request per client | one open stream per client | one open socket per client |
| Auto-reconnect | n/a | manual | built into browser API | manual |
| Works through proxies/firewalls | always | usually | usually (it is HTTP) | usually; some old proxies break it |
| Binary data | yes | yes | text only | yes |
| Best for | rare updates, simple clients | fallback, moderate updates | notifications, feeds, dashboards, token streaming | chat, multiplayer games, collaborative editing |

**Mobile push notifications** (via the phone OS's push service) are another channel for when the
app is not open; you send to the provider, it wakes the device.

> **Say it like this:** "If the server only needs to push to the client — notifications, a live
> feed — SSE is simpler: plain HTTP with automatic reconnect. If both sides send frequently, like
> chat or a multiplayer game, I'd use WebSockets and a pub/sub layer to route between socket
> servers."

## 02.12 Webhooks

### The problem

Your system depends on events in someone else's system ("payment succeeded" at the payment
provider). Polling their API constantly is wasteful and slow.

### The idea

A **webhook** is a reverse API call: you register a URL with the provider, and when the event
happens, *they* send an HTTP POST to *your* URL.

```text
 Your service                              Provider
   | register https://you.example/hooks/pay  |
   |---------------------------------------->|
   |               ... later, payment succeeds ...
   | POST /hooks/pay {"event":"payment.succeeded","id":"evt_1",...}
   |<----------------------------------------|
   | 200 OK (quickly!)                       |
   |---------------------------------------->|
```

Designing the receiving end correctly:

- **Verify the sender**: providers sign the payload (an HMAC signature header using a shared
  secret). Reject anything that does not verify — the URL is public, anyone can POST to it.
- **Respond fast, process async**: store the event (or put it on a queue) and return 2xx in
  milliseconds; do the slow work afterwards. Providers time out and retry slow endpoints.
- **Expect duplicates**: providers retry on failure, so delivery is at-least-once. Deduplicate by
  event ID (idempotency again).
- **Expect out-of-order delivery**: "payment.refunded" may arrive before "payment.succeeded".
  Check timestamps/state, or fetch the current state from the provider's API.
- **Have a reconciliation job**: webhooks can be lost entirely; periodically poll for anything
  missed.

When *you* send webhooks: sign them, retry with exponential backoff, let receivers see delivery
logs and replay events, and disable endpoints that fail for days.

## 02.13 Synchronous vs asynchronous APIs

### The problem

Some operations take long: generating a 500-page report, transcoding a video, a bulk import of a
million rows. Holding an HTTP request open for 10 minutes is fragile: proxies time out (often at
30–60 s), clients disconnect, a deploy kills the server mid-request, and the server ties up a
worker the whole time.

### The pattern: 202 Accepted + status resource

```text
 Client                                    API                      Worker / queue
   | POST /reports {"month":"2026-09"}      |                            |
   |--------------------------------------->| validate, create job,      |
   |                                        | enqueue ------------------>|
   | 202 Accepted                           |                            |
   | Location: /reports/jobs/j-55           |                            |
   |<---------------------------------------|                            |
   |                                        |            ... working ... |
   | GET /reports/jobs/j-55                 |                            |
   |--------------------------------------->|                            |
   | 200 {"status":"RUNNING","progress":40} |                            |
   |<---------------------------------------|                            |
   |              ...                       |<--- done, result stored ---|
   | GET /reports/jobs/j-55                 |                            |
   | 200 {"status":"SUCCEEDED",             |                            |
   |      "resultUrl":"/reports/r-9"}       |                            |
```

- The server responds immediately with **202 Accepted** and a URL for the job.
- The client **polls** the status (with `Retry-After` hints), or receives a webhook/push when done.
- Job states: `PENDING → RUNNING → SUCCEEDED | FAILED` (maybe `CANCELLED`).
- Combine with an idempotency key so a retried POST does not create two jobs.

Trade-off: more moving parts (a job store, a queue, workers — chapter 11) and the client must
handle the extra step. Use it when work exceeds a few seconds, is heavy, or must survive server
restarts. Keep fast, simple operations synchronous.

> **Say it like this:** "For anything that takes more than a few seconds I'd make the API
> asynchronous: return 202 with a job URL, process from a queue, and let the client poll or get a
> webhook. That keeps requests short and the work survives restarts."

## 02.14 API design checklist

Use this before calling an API design done:

1. **Resources and methods**: plural nouns, shallow nesting, consistent non-CRUD actions;
   safe/idempotent semantics respected.
2. **Idempotency**: every non-idempotent create/charge accepts an `Idempotency-Key`.
3. **Status codes**: correct families; 201 + `Location` on create; 202 for async.
4. **Errors**: one structured format with stable codes and request IDs.
5. **Pagination**: cursor-based for anything that can grow; max page size enforced.
6. **Filtering/sorting**: allow-listed fields, backed by indexes.
7. **Auth**: how callers authenticate (tokens), and per-resource authorization checks (users can
   only see their own orders) — chapter 15.
8. **Rate limits**: per client, with `429` and `Retry-After` — chapter 12.
9. **Versioning**: additive changes by default; a versioning scheme for breaking ones.
10. **Validation and limits**: field formats, max body size, max array lengths.
11. **Concurrency**: ETag / `If-Match` or version fields to avoid lost updates.
12. **Long operations**: async job pattern.
13. **Observability**: request IDs, logging, metrics per endpoint.
14. **Documentation**: a machine-readable spec (OpenAPI for REST, `.proto` for gRPC).

## 02.15 Worked example: an "orders" service API

**Requirements**: customers place orders from a cart, view their orders, and cancel before
shipping. Payment happens through an external provider. Sellers list their orders. Clients: web
and mobile.

### Resources

- `Order`: id, customerId, items, total, currency, status, createdAt, updatedAt, version.
- `OrderItem`: productId, quantity, unitPrice.
- Status state machine:

```text
 PENDING_PAYMENT --(payment ok)--> CONFIRMED --> SHIPPED --> DELIVERED
       |                              |
       +--(payment failed)--> FAILED  +--(cancel)--> CANCELLED
       +--(cancel)----------> CANCELLED
```

### Endpoints

| Method + path | Purpose | Success | Notable errors |
|---|---|---|---|
| `POST /v1/orders` | place an order from a cart | 201 + `Location` | 400/422 invalid, 409 cart already ordered or out of stock |
| `GET /v1/orders/{orderId}` | get one order | 200 | 404 (also for other people's orders) |
| `GET /v1/orders?status=&limit=&cursor=` | list my orders, newest first | 200 + `nextCursor` | 400 bad cursor |
| `POST /v1/orders/{orderId}/cancellation` | cancel | 200 with updated order | 409 already shipped |
| `GET /v1/sellers/{sellerId}/orders?cursor=` | seller's view | 200 | 403 not that seller |
| `POST /v1/webhooks/payments` | provider notifies payment result | 200 fast | 401 bad signature |

### Place order

```http
POST /v1/orders HTTP/1.1
Authorization: Bearer <token>
Idempotency-Key: 0b3f6c1e-2a4d-4e8b-9c2f-7d1a5e6b8c90
Content-Type: application/json

{"cartId": "c_9a2", "shippingAddressId": "addr_31"}
```

```http
HTTP/1.1 201 Created
Location: /v1/orders/o_7Kq2
Content-Type: application/json

{
  "id": "o_7Kq2",
  "status": "PENDING_PAYMENT",
  "items": [{"productId": "p_123", "quantity": 2, "unitPrice": 999}],
  "total": {"amount": 1998, "currency": "INR"},
  "payment": {"redirectUrl": "https://pay.provider.example/checkout/cs_88"},
  "createdAt": "2026-10-10T08:30:00Z",
  "version": 1
}
```

Design decisions and why:

- **Customer ID comes from the token, not the body.** Otherwise a client could place orders as
  someone else.
- **Prices are computed on the server** from the cart and the catalog; the client never sends a
  price it could tamper with.
- **Idempotency-Key required**: a double-tap or a mobile retry must not create two orders.
- **Payment is asynchronous**: order starts `PENDING_PAYMENT`; the provider's webhook moves it to
  `CONFIRMED` or `FAILED`. The webhook handler verifies the signature and dedupes by event ID.
- **Money in minor units with currency**.

### List orders

```http
GET /v1/orders?status=CONFIRMED&limit=20&cursor=eyJ0IjoiMjAy...In0
```

```json
{
  "data": [{"id": "o_6K0", "status": "CONFIRMED",
            "total": {"amount": 4500, "currency": "INR"},
            "createdAt": "2026-10-09T11:58:00Z"}],
  "nextCursor": "eyJ0IjoiMjAy...In0"
}
```

- Cursor on `(createdAt, id)`, index on `(customer_id, created_at, id)`, max `limit` 100.
- List items are a **summary** (no line items) to keep pages small; detail comes from
  `GET /v1/orders/{id}`.

### Cancel

```http
POST /v1/orders/o_7Kq2/cancellation HTTP/1.1
Authorization: Bearer <token>
If-Match: "1"
```

- Naturally idempotent: if the order is already `CANCELLED`, the server returns 200 with the
  current state (checked before the version precondition, so a retry after a lost response
  succeeds).
- `409 Conflict` with code `ORDER_ALREADY_SHIPPED` if the state machine forbids it.
- `If-Match` with the version prevents a race where the order ships between the client reading
  it and cancelling (`412` if the version changed).
- Cancelling a paid order triggers a refund **asynchronously**; the order shows
  `CANCELLED` with `refund.status = PENDING`.

Internally, the orders service calls inventory and payments over gRPC with deadlines, and
publishes `OrderPlaced` / `OrderCancelled` events to a message queue for email, analytics and the
seller dashboard (chapters 11 and 13).

> **Say it like this:** "The client sends only the cart ID and an idempotency key; the server
> derives the customer from the token and computes prices itself. Payment confirmation arrives by
> a signed, deduplicated webhook, and listing uses cursor pagination on created_at plus id."

## 02.16 Recap

- An API is a long-lived contract; design for predictability, safe retries, and evolution.
- REST models resources with nouns and uses HTTP verbs; GET/PUT/DELETE are idempotent, POST is not,
  PATCH depends on how it is written.
- Use real status codes: 2xx success, 4xx caller's fault (do not retry blindly), 5xx server's
  fault (retry with backoff); know 200/201/202/204/301/304/400/401/403/404/409/422/429/500/503.
- Idempotency keys turn client retries into exactly-once effects: atomic insert of the key, store
  the response, replay it on retries.
- Cursor (keyset) pagination is O(log n + page) and stable under writes; offset is O(offset) and
  skips/duplicates items.
- Evolve additively; version (usually in the path) only for breaking changes, then deprecate.
- gRPC: protobuf contract, binary, HTTP/2, streaming, deadlines — best for internal calls.
- GraphQL: client-shaped queries solve over/under-fetching, but bring N+1 risks, harder caching,
  and the need for query cost limits.
- Real-time: polling (simple, wasteful), long polling (fallback), SSE (server→client, auto-
  reconnect), WebSockets (bidirectional, stateful connections).
- Webhooks are at-least-once and unordered: verify signatures, ack fast, dedupe, reconcile.
- Long work: 202 Accepted + job resource + polling or callback.

## 02.17 Check yourself

1. A mobile client sends `POST /payments`, times out, and retries. Explain exactly how an
   idempotency key prevents a double charge, including what happens if both attempts arrive at
   the same time.

<details><summary>Answer</summary>

The client generates one key for the logical payment and sends it on every attempt. The server
atomically inserts the key (unique constraint or set-if-absent) before doing the work. If the
first attempt finished, the retry finds the key and gets the stored response back without
charging again. If both arrive at once, only one insert succeeds; the other sees the key exists in
IN_PROGRESS state and gets a 409 (retry later) instead of charging. A key reused with a different
body is rejected.

</details>

2. Why is `PUT /users/7` idempotent but `POST /users` not? Is `PATCH` idempotent?

<details><summary>Answer</summary>

PUT replaces the resource at a known URL with the given full representation; doing it twice leaves
the same state. POST asks the server to create a new child resource; doing it twice creates two.
PATCH is not guaranteed idempotent: "set email to X" is, but "increment counter by 1" is not.

</details>

3. Your feed endpoint uses `?offset=`. Users complain about duplicates when scrolling and deep
   pages are slow. Explain both problems and the fix.

<details><summary>Answer</summary>

Duplicates: new items inserted at the top shift every row down, so the next offset page repeats
items already seen (deletes cause skips). Slowness: the database must walk and discard `offset`
rows before returning the page, so cost grows with depth. Fix: cursor/keyset pagination on a
unique sort key such as `(created_at, id)`: the query seeks via the index to "rows after the last
one seen" and reads only `limit` rows, and inserted rows do not shift positions.

</details>

4. When would you choose SSE over WebSockets, and vice versa?

<details><summary>Answer</summary>

SSE when updates flow only from server to client (notifications, live feeds, progress, streamed
text): it is plain HTTP, simpler to operate, and the browser reconnects and resumes automatically.
WebSockets when both sides send frequently with low latency (chat with typing indicators,
multiplayer games, collaborative editing), accepting the cost of stateful connections, manual
reconnection, and cross-server message routing.

</details>

5. What is the N+1 problem in GraphQL and how is it usually fixed?

<details><summary>Answer</summary>

A list field is resolved with one query, then a nested field is resolved per element with its own
query, giving 1 + N queries (and it multiplies with nesting). The fix is batching with a
DataLoader-style component that collects all keys requested in the same execution step and loads
them with one `WHERE id IN (...)` query, plus per-request caching.

</details>

6. Your report generation takes 5 minutes. A client calls it synchronously and often gets 504
   errors. Redesign the API.

<details><summary>Answer</summary>

Make it asynchronous: `POST /reports` validates, creates a job, enqueues it, and returns `202
Accepted` with `Location: /reports/jobs/{id}` (use an idempotency key so retries do not create
duplicate jobs). Workers process from the queue. The client polls the job resource (status,
progress, then a result URL), or receives a webhook/notification on completion. The 504 came from
a proxy giving up on a request held too long.

</details>

7. You receive webhooks from a payment provider. List four things your endpoint must do.

<details><summary>Answer</summary>

Verify the signature (the URL is public); acknowledge quickly with 2xx and process asynchronously
(store or enqueue first); deduplicate by event ID because delivery is at-least-once; tolerate
out-of-order events by checking state or re-fetching from the provider; and run a periodic
reconciliation job for events that never arrived.

</details>

8. Which of these are breaking changes: (a) adding an optional response field, (b) renaming a
   field, (c) making an optional request parameter required, (d) adding a new endpoint?

<details><summary>Answer</summary>

(b) and (c) are breaking. Renaming removes the old field that existing clients read, and requiring
a parameter rejects requests old clients still send. (a) and (d) are additive and safe, provided
clients ignore fields they do not recognize.

</details>
