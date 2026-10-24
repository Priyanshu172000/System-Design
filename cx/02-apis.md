# 02 — APIs: how services talk

*Why it matters:* "what does the API look like?" comes up almost immediately in any design discussion, and you need reasons, not just vocabulary, to defend one.

## What an API is

**How it works** · An **API** is a contract: send a request shaped like this, get a response shaped like that; how the work is done stays hidden. **Public** (outside developers), **internal** (service to service), **client** (your own web/mobile apps).

**Example** · QuickBite's app v3.2 (a year old) still calls `GET /v1/orders/o_90311` and reads `amount`.
1. A backend dev renames `amount` to `total` "for clarity" and deploys.
2. Every v3.2 phone now shows a blank price; you cannot force-update them all.

So: once clients depend on it you can't change it freely. Good APIs are **predictable** (same naming, errors, pagination everywhere), **hard to misuse** (safe retries, sensible defaults), **evolvable** (add without breaking), **efficient** (not 20 calls per screen).

## REST: resources, methods, status codes

**How it works** · **REST** models the nouns of your domain as **resources** identified by URLs and uses HTTP methods as verbs instead of `POST /getOrder`. Because everyone knows what `GET` means, caches, proxies and libraries can cache and retry it; nothing tells a proxy `POST /getOrder` is a read. The server sends a **representation** (usually JSON), each request is **stateless** (carries the auth token), all resources share the **uniform interface**.
- **Safe**: doesn't change state (prefetch, crawl, retry freely).
- **Idempotent**: once or N times leaves the **same state** (the response may differ, e.g. a second DELETE returns 404). Retry safely after a timeout.

**Example** · The same request sent twice on QuickBite:

| Request (twice) | End state | Idempotent? |
|---|---|---|
| `GET /orders/o_90311` | unchanged | yes, and safe |
| `PUT /users/u_1842 {full profile}` | same profile | yes |
| `PATCH /orders/o_90311 {"status":"CANCELLED"}` | cancelled once | yes ("set") |
| `PATCH /wallets/u_1842 {"balanceDelta": 100}` | +₹200 | **no** ("increment") |
| `DELETE /carts/c_9/items/i_4` | item gone (2nd call: 404) | yes |
| `POST /orders {cart}` | **two orders** | **no** |

So: design patches as "set", not "increment"; the POST row is the retry problem, solved by idempotency keys.

| Method | Use | Safe | Idempotent |
|---|---|---|---|
| GET / HEAD / OPTIONS | read / headers only / what's allowed (CORS) | yes | yes |
| POST | create child resource, trigger action | no | **no** |
| PUT | replace entirely (create at known URL) | no | yes |
| PATCH | partial update | no | not guaranteed |
| DELETE | remove | no | yes |

Status families: **2xx** worked; **3xx** look elsewhere / use cache; **4xx** your request is wrong, retrying won't help; **5xx** server failed, may succeed later (retry with backoff).

| Code | Use |
|---|---|
| 200 / 201 / 202 / 204 | OK / Created + `Location` / Accepted for async work / No Content (DELETE) |
| 301 / 304 | Moved Permanently / Not Modified (cached copy valid) |
| 400 / 422 | malformed or invalid field / well-formed but semantically invalid (some use 400) |
| 401 / 403 | not authenticated ("who are you?") / authenticated but not allowed ("no") |
| 404 / 409 | not found (or you may not know it exists) / conflict: duplicate, version mismatch |
| 429 | rate limited, include `Retry-After` |
| 500 / 503 | unexpected bug / overloaded or maintenance, temporary |
| 502 / 504 | proxy got a bad response / gave up waiting upstream: problem is behind the LB |

> **Common mistake:** returning `200 OK` with `{"error": "not found"}`. Monitoring, caches, LBs and client libraries read the status code; a failure disguised as 200 is invisible and may even be cached.

**URL design** · plural lowercase nouns (`/orders`); shallow hierarchy for ownership (`/users/u_1842/orders`, 1–2 levels); IDs in path, options in query (`/restaurants?cuisine=biryani&sort=-rating&limit=20`); non-CRUD actions as sub-resource or state change (`POST /orders/o_90311/cancellation`, `POST /orders/o_90311:cancel`, or `PATCH {"status":"CANCELLED"}`), pick one convention; no verbs for CRUD, no file extensions, consistent trailing slash; **opaque, non-sequential public IDs** (UUIDs) so "order 1000234" doesn't leak volume or allow enumeration.

## Request and response design

**How it works** · Validate everything server-side (types, ranges, lengths, enums), accept only expected fields, one naming style, **money as integer minor units + currency** (never floats: `0.1 + 0.2 != 0.3`), ISO-8601 UTC timestamps. Return the resource after create/update; envelope (`{"data", "meta"}`) or not, be consistent; **partial responses** `?fields=id,name`; compression (`gzip`/brotli). **Conditional requests**: `ETag` = version fingerprint; `Cache-Control: max-age=60` lets browsers/CDNs cache (chapter 08).

**Example** · `r_77`'s owner and a manager both edit the "Chicken Biryani" price (`m_12`, `{"amount": 25000, "currency": "INR"}` = ₹250):
1. Both `GET /menu-items/m_12` -> `ETag: "v7"`.
2. Owner: `PATCH {"amount": 27000}` with `If-Match: "v7"` -> 200, now `"v8"`.
3. Manager: `PATCH {"amount": 26000}` with `If-Match: "v7"` -> `412 Precondition Failed`; re-reads, sees ₹270.
4. Asha's app later: `GET` with `If-None-Match: "v8"` -> `304 Not Modified`, no body.

So: `If-Match` is **optimistic concurrency control** over HTTP (no lost update); `If-None-Match` saves bandwidth.

## Idempotency keys: making retries safe

**How it works** · A client can't tell "request never arrived" from "processed but response lost". No retry may lose a payment; retry may charge twice. So make the **retry safe**: the client generates one **idempotency key** (UUID) per *logical* operation and sends it on every retry (`Idempotency-Key:` header). Server:

```text
 rec = store.get(key)
 if rec: if hash(body) differs -> 422;  if IN_PROGRESS -> 409 retry later
         else return rec.savedResponse          # replay, do NOT charge again
 store.insertIfAbsent(key, IN_PROGRESS, hash(body))   # atomic
 resp = doThePayment(body); store.update(key, DONE, resp)  # keep ~24 h
```

**Example** · Asha pays ₹450 for `o_90311` on a flaky 4G connection, key `k=6f1c...`:
1. 20:14:02 `POST /payments {amount: 45000}` + `k` -> insert `k` IN_PROGRESS, card charged, `pay_311` saved, DONE.
2. The 201 is lost; the app times out at 20:14:12.
3. 20:14:13 retry with the same `k` -> record found, hash matches -> replay `201 {id: pay_311}`. No second charge.
4. Variant: she double-taps and both arrive together -> only one atomic insert wins; the other gets 409.

So: at-least-once delivery becomes **exactly-once effect** (widely used by payment APIs).
- **Atomic insert** (unique constraint or Redis `SET NX`), else two concurrent retries both charge.
- **Store the response** so a retry gets the same payment ID. **Scope keys per client/account**. **Expire** after a window (commonly about 24 h).
- Ideally record the key **in the same DB transaction** as the business write. A crash leaves IN_PROGRESS: needs recovery (time out, complete or roll back).
- **Natural idempotency** without a key: `PUT /orders/{clientGeneratedUuid}`; absolute updates ("set status = DELIVERED"); unique constraint on a business key (one payout per `invoiceId`).

> **Say it like this:** "A timeout doesn't tell the client whether the charge happened, so it retries with the same Idempotency-Key. The server stores the key atomically with the result and replays the stored response instead of charging again."

## Pagination: offset vs cursor

**How it works** · A restaurant with 3 million past orders can't get them in one response; you return a **page**. **Offset** (`LIMIT 20 OFFSET 40`) is simple and can jump to page 37, but the DB walks and discards all skipped rows, O(offset + limit), and it is unstable under writes. **Cursor / keyset**: "rows **after** this one"; the server returns an opaque `nextCursor` encoding the last row's sort key.

**Example** · `r_77`'s order list, newest first, page size 5, during the dinner rush:

```text
 Offset   page 1 (offset 0): [A B C D E]      new order X arrives at the top
          page 2 (offset 5): [E F G H I]      E shown twice (deletes cause skips)
 Cursor   page 1 -> nextCursor = (E.created_at, E.id) = ('2026-10-09T20:05:00Z', 987)
          page 2: rows < that key           -> [F G H I J]   X doesn't shift anything
```

```sql
SELECT * FROM orders WHERE restaurant_id = 'r_77'
  AND (created_at, id) < ('2026-10-09T20:05:00Z', 987)
ORDER BY created_at DESC, id DESC LIMIT 20;
```

So: with an index on `(restaurant_id, created_at, id)` this is a seek plus 20 reads, O(log n + limit) at any depth (`lower_bound` plus a short walk). `id` is a unique tie-breaker since `created_at` may collide. Without row-value comparison write `a < x OR (a = x AND b < y)`. Keep the cursor **opaque** (base64, maybe signed) so you can change it.

| | Offset | Cursor / keyset |
|---|---|---|
| Deep page cost | grows with offset | constant (index seek) |
| Stable under inserts/deletes | no | yes |
| Jump to page N / total count | yes (COUNT is costly on big tables) | no, next/previous only |
| Best for | small admin tables, numbered search results | feeds, timelines, large lists, infinite scroll |

**Filtering, sorting, searching** · filters as query params (`?status=DELIVERED&createdAfter=...`), `?sort=-createdAt,amount`; **allow-list** filterable/sortable fields, each combination needs an index (chapter 05), unrestricted `sort=` invites full scans. Free text ("biryani near me") with `LIKE '%...%'` doesn't scale: use `search-service` with an inverted index (chapter 09). The cursor is tied to the sort: new filters or sort restart at page one.

## Versioning and error format

**How it works** · Rule one: **evolve additively**. Safe: add optional response field, new endpoint, optional request param, new enum value (if clients tolerate unknowns). Breaking: remove/rename a field, change type or meaning, make optional required, change URL structure, error codes or auth. Clients follow the **tolerant reader** rule: ignore unknown fields.

**Example** · Two QuickBite changes:
1. Add `"etaMinutes": 32` to the order response -> additive, v3.2 apps ignore it. No version bump.
2. Split `amount` into `{subtotal, deliveryFee, tax}` and drop `amount` -> breaking. Ship `/v2/orders`, keep `/v1` serving, send `Deprecation`/`Sunset` headers, watch `/v1` traffic fall, remove near zero.

So: version only when you must break; each live version is a code path to maintain. Ways: **URL path** `/v1/orders` (most common, easy to route/cache), **header** (`Accept: application/vnd.quickbite.v2+json`, `Api-Version: 2026-10-01`; cleaner URLs, harder to test), or **query param** (less common).

**Errors**: one machine-readable shape everywhere, e.g. "Problem Details" (`application/problem+json`): `422`, `"code": "VALIDATION_FAILED"`, `"detail": "quantity must be between 1 and 10"`, `"errors": [{"field": "items[0].quantity", "reason": "max 10"}]`, `"requestId": "req_7Hc2"`. Status code for the category, stable `code` clients switch on, human `detail`, field-level `errors`, `requestId` for support (chapter 14). Never leak stack traces, SQL, or hostnames (chapter 15).

## RPC and gRPC

**How it works** · **RPC** = call a function on another machine as if local: action-oriented, where REST is resource-oriented.

**Example** · `order-service` placing `o_90311` calls `restaurant-service.ReserveItems(order_id="o_90311", items=[m_12 x2])` with a 200 ms deadline. `restaurant-service` spends 150 ms and calls the stock DB with the remaining ~50 ms. If the DB hangs, the whole chain stops at 200 ms instead of doing work nobody waits for. So: a typed contract plus propagated deadlines is why gRPC fits internal calls.

> **Black box:** **gRPC** = RPC framework: **protobuf** schema (`.proto`) -> compact **binary** messages + generated, compile-time-checked client/server code; HTTP/2 transport; unary plus server/client/bidirectional **streaming**; **deadlines** that propagate downstream. Field **numbers** go on the wire (`string order_id = 3;`): rename freely, never reuse a number, new numbers are backward compatible. **Use when:** internal service-to-service calls. **Cost:** browsers need gRPC-Web proxy, debugging needs special tools, no natural proxy/CDN caching.

| | REST + JSON | gRPC + protobuf |
|---|---|---|
| Payload / contract | text, optional OpenAPI | binary, mandatory `.proto` + codegen |
| Transport / streaming | HTTP/1.1 or 2 / not native (SSE, WebSockets) | HTTP/2 / built in |
| Browser, debugging, caching | native, curl, natural for GET | proxy needed, special tools, not really |
| Typical use | public APIs, browser/mobile | internal calls |

> **Say it like this:** "Externally REST with JSON because every client understands it; internally gRPC for a strict protobuf contract, smaller payloads, HTTP/2 multiplexing and propagating deadlines."

## GraphQL

> **Black box:** one endpoint (`POST /graphql`) + a **typed schema**; the client sends a query with exactly the shape it wants; per-field **resolvers** fill it; **mutations** write, **subscriptions** push (usually WebSockets). Solves **under-fetching** (several sequential REST calls at 150 ms RTT on a phone) and **over-fetching** (40 fields when 3 are needed); e.g. QuickBite's home screen asks once for `me { name orders(last: 3) { id items { dish { title imageUrl } } } }`. **Use when:** many different clients (web, iOS, Android, partners) over a rich connected domain. **Cost:** below; skip it for simple CRUD, internal calls (gRPC), or when CDN caching is critical.

- **N+1 problem**: resolve 3 orders (1 query), items per order (3), dish per item (say 10 each, 30) = 34 queries for one screen. Fix: **DataLoader** batches IDs requested in the same tick into one `WHERE id IN (...)`, plus per-request cache. The same trap exists in REST/ORMs.
- Costs: server complexity; HTTP caching hard (all POST, one URL); expensive deep queries need depth/complexity limits, timeouts, persisted queries; errors often come as 200 + `errors` array; rate-limit by query cost, not request count. Not "faster than REST": the server does the same or more work.

## Real-time: polling, long polling, SSE, WebSockets

**How it works** · HTTP is client-initiated, but some data starts on the server ("your rider has arrived").
- **Short polling**: ask every few seconds. Simple, wasteful, up to one interval of delay.
- **Long polling**: `GET /orders/o_90311/events?since=100`; the server **holds** the request until data or timeout (say 30 s); client re-asks immediately. Near-instant, plain HTTP; one held connection per client, reconnect per message, `since=` cursors for ordering.
- **SSE**: one HTTP response kept open, server streams `text/event-stream` lines, server -> client only. Browser `EventSource` **auto-reconnects** with `Last-Event-ID` to resume.
- **WebSocket**: HTTP `Upgrade: websocket` -> `101 Switching Protocols`, then a **full-duplex** message channel on the TCP connection. Costs: long-lived **stateful** connections (tens or hundreds of thousands per machine with event-loop servers, chapter 03), LB support, draining on deploy, cross-server routing via **pub/sub** (chapter 11), reconnection and missed-message recovery are yours.

**Example** · "Where is my order" for `o_90311`:

```text
 Short poll: 1 M tracking users x 1 req / 2 s = 500,000 req/s, almost all "no change"
 SSE:        GET /orders/o_90311/stream
             id: 101  data: {"status":"PREPARING"}
             id: 102  data: {"status":"PICKED_UP","rider":"rd_5521"}
             (Wi-Fi drops; EventSource reconnects with Last-Event-ID: 102)
             id: 103  data: {"status":"DELIVERED"}
 WebSocket:  needed for in-app chat with rd_5521 (both sides send)
```

So: tracking is server -> client only, so SSE is enough; chat is two-way, so WebSocket.

| | Short poll | Long poll | SSE | WebSockets |
|---|---|---|---|---|
| Direction | client pulls | client pulls, server delays | server -> client | both |
| Update latency | up to interval | near real time | near real time | near real time |
| Server cost | many wasted requests | held request per client | open stream per client | open socket per client |
| Auto-reconnect | n/a | manual | built in | manual |
| Proxies / binary | always / yes | usually / yes | usually (HTTP) / text only | usually (old proxies break) / yes |
| Best for | rare updates | fallback | notifications, feeds, dashboards, token streaming | chat, games, collaborative editing |

**Mobile push** via the OS push service reaches apps that aren't open (`notification-service` sends "Order delivered").

## Webhooks

**How it works** · Instead of polling a provider, register a URL; when the event happens *they* POST to *you* (a reverse API call).

**Example** · The payment provider calls `payment-service` at `POST /v1/webhooks/payments`:
1. `evt_1 payment.succeeded pay_311` arrives -> HMAC signature checked against the shared secret (the URL is public).
2. Row stored, job enqueued, `200` returned in ~10 ms (slow endpoints get retried).
3. The provider didn't see the 200 in time and resends `evt_1` -> event ID already seen -> 200, ignored.
4. `evt_2 payment.refunded` arrives before `evt_1` on another day -> check current state or re-fetch from the provider.
5. Nightly **reconciliation job** lists the provider's payments and finds `pay_318`, whose webhook never came.

So: verify, ack fast and process async, **dedupe by event ID** (at-least-once), tolerate out-of-order, reconcile. When sending: sign, retry with exponential backoff, delivery logs and replay, disable endpoints failing for days.

## Synchronous vs asynchronous APIs

**How it works** · A 10-minute request is fragile: proxies time out (often 30–60 s, giving 504), clients disconnect, deploys kill it, a worker is tied up. Pattern: validate, create a job, enqueue, return **202 Accepted** + `Location`; client **polls** (`Retry-After` hints) or gets a webhook/push.

**Example** · `r_77` asks for its September payout report:

```text
 POST /reports {"month":"2026-09"} + Idempotency-Key -> 202, Location: /reports/jobs/j-55
 GET  /reports/jobs/j-55 -> 200 {"status":"RUNNING","progress":40}   Retry-After: 10
 GET  /reports/jobs/j-55 -> 200 {"status":"SUCCEEDED","resultUrl":"/reports/r-9"}
```

So: states `PENDING -> RUNNING -> SUCCEEDED | FAILED` (maybe `CANCELLED`); the idempotency key stops a retried POST from creating two jobs. Cost: job store, queue, workers (chapter 11), extra client step. Use beyond a few seconds, for heavy work, or work that must survive restarts.

## API design checklist

**How it works** · Before calling a design done, check: resources/methods and safe/idempotent semantics · `Idempotency-Key` on every non-idempotent create/charge · correct codes (201 + `Location`, 202 async) · one structured error format with codes and request IDs · cursor pagination with max page size · allow-listed, indexed filter/sort · auth plus per-resource authorization (chapter 15) · per-client rate limits with `429` + `Retry-After` (chapter 12) · additive changes, versioning for breaking ones · validation and limits (body size, array length) · ETag/`If-Match` or version fields · async job pattern · request IDs, logs, per-endpoint metrics · machine-readable spec (OpenAPI, `.proto`).

**Example** · Reviewing a draft `POST /orders {userId, items, price}`: `userId` from the body (take it from the token), `price` from the client (compute server-side), no idempotency key (double-tap = two orders), list endpoint uses `offset` (switch to cursor), no max `items` length (cap it). So: five fixes from one pass.

## Worked example: QuickBite order-service API

**How it works** · Customers order from a cart, view, cancel before pickup; external payment provider; restaurants list their orders. `Order` = id, userId, restaurantId, items, amount, currency, status, timestamps, version. States: `PLACED -> ACCEPTED -> PREPARING -> PICKED_UP -> DELIVERED`, or `CANCELLED`; payment is confirmed asynchronously by webhook.

| Endpoint | Success | Notable errors |
|---|---|---|
| `POST /v1/orders` {cartId, addressId} + `Idempotency-Key` | 201 + `Location`, payment redirect URL | 400/422, 409 cart already ordered / dish sold out |
| `GET /v1/orders/{id}` | 200 | 404 (also for other people's orders) |
| `GET /v1/orders?status=&limit=&cursor=` | 200 + `nextCursor` | 400 bad cursor |
| `POST /v1/orders/{id}/cancellation` + `If-Match` | 200 updated order | 409 `ORDER_ALREADY_PICKED_UP`, 412 version changed |
| `GET /v1/restaurants/{restaurantId}/orders` | 200 | 403 not that restaurant |
| `POST /v1/webhooks/payments` | 200 fast | 401 bad signature |

**Example** · Asha's order end to end:
1. `POST /v1/orders {"cartId":"c_9a2","addressId":"addr_31"}`, key `0b3f...` -> `201`, `Location: /v1/orders/o_90311`, `amount: 45000 INR`, `version: 1`. `userId=u_1842` comes from the token; the price is computed from `r_77`'s menu.
2. Double-tap with the same key -> same `201` replayed, still one order.
3. Webhook `payment.succeeded` (signed, deduped) -> payment recorded; `r_77` accepts -> `ACCEPTED`, `version: 2`.
4. Asha cancels with `If-Match: "1"` -> `412` (version moved); app re-reads and retries with `"2"` -> `CANCELLED`, `refund.status = PENDING` (refund is async).
5. Lost response, she retries the cancel -> already `CANCELLED` -> 200 (checked before the version precondition).

So: identity from the token, prices server-side, idempotency for create, natural idempotency plus `If-Match` for cancel. List: cursor on `(createdAt, id)`, index `(user_id, created_at, id)`, max limit 100, **summary** rows (no line items). Internally: gRPC with deadlines to `restaurant-service`/`payment-service`; `OrderPlaced`/`OrderCancelled` events to a queue for `notification-service`, analytics, the restaurant dashboard (chapters 11, 13).

## In one minute

- An API is a long-lived contract: predictable, safe to retry, evolvable (year-old apps still call it).
- REST = nouns + HTTP verbs; GET/PUT/DELETE idempotent, POST not, PATCH depends ("set" yes, "increment" no).
- Real status codes: 4xx fix the request, 5xx retry with backoff; never errors inside a 200.
- Idempotency keys: atomic insert of the key, store the response, replay on retry = exactly-once effect.
- Cursor pagination on `(created_at, id)` is O(log n + page) and stable; offset is O(offset) and skips/duplicates.
- Evolve additively; version (usually in the path) only for breaking changes, then deprecate.
- gRPC for internal calls, GraphQL for many varied clients (watch N+1, caching, query cost); SSE for server push, WebSockets for two-way.
- Webhooks: verify, ack fast, dedupe, reconcile. Long work: 202 + job resource + poll or callback.

## Go deeper

Full chapter 02: sections 02.4 (idempotency keys), 02.5 (pagination), 02.11 (real-time options), 02.15 (worked example)
