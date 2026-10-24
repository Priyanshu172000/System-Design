# 19 — Case study: news feed and chat

*Why it matters:* both are "one producer, many consumers" problems, and the push-vs-pull choice
you make here comes back in notifications, channels and ad delivery.

## How to read this chapter

> **Black box:** each system walks the chapter-16 steps (requirements -> numbers -> API/data ->
> diagram -> deep dives -> failures -> 5-minute pitch). **Use when:** always; say numbers as
> **assumptions** ("let me assume 300M DAU"). **Cost:** none; show that numbers drive the design.

## Feed: requirements and numbers

**How it works** · state scope, then let the numbers pick the design.
- **Functional:** post (text/media), follow, paginated home feed, likes; no comments/search/ads.
- **Non-functional:** feed p99 < 200 ms, 99.95%+, few-s staleness OK, eventual counts, posts durable.

| Quantity (300M DAU, 100M posts/day, 200 follows, 10 opens x 20 posts) | Value |
|---|---|
| Posts | ~1,200/s avg, ~3,500/s peak |
| Feed reads | ~35,000/s avg, ~100,000/s peak |
| Post lookups to hydrate pages | ~2M/s at peak (must be cached) |
| Fan-out inserts (100M x 200) | 20B/day, ~230k/s avg, ~700k/s peak |
| Storage | post metadata ~100 GB/day (~36 TB/yr); images ~10 TB/day (~3.6 PB/yr) |
| Feed cache | 500 IDs x ~10 B = ~5 KB/user; 500M users = ~2.5 TB RAM |

**Example** · reads: 300M x 10 / 86,400 s = ~34,700/s (35k) vs posts 100M / 86,400 = ~1,160/s,
so **reads ~30x posts**: make reads cheap. Skew: one 40M-follower post = 40M feed inserts.

## Feed: API and data model

**How it works** · REST. `POST /v1/posts` with an **Idempotency-Key**; follow `POST`/`DELETE`;
like `PUT`/`DELETE` (naturally **idempotent**); `GET /v1/feed?cursor=<opaque>`; media via a
**pre-signed URL** straight to object storage.

| Data | Store | Why |
|---|---|---|
| Posts | wide-column or sharded SQL, key post_id | huge, key lookups, append-mostly |
| Author timeline | wide-column, partition author_id, cluster post_id DESC | "recent posts by X" = one scan |
| Follow graph | two tables: `followers_by_user`, `following_by_user` | both directions are queried |
| Home feed | Redis `user_id -> [post_id...]` | precomputed, read 100k/s |
| Likes / counts | wide-column (post_id, user_id) / Redis + periodic flush | idempotent / hot, approximate |
| Users / media | SQL sharded by user_id / object storage + CDN | relational / cheap, edge-cached |

**Example** · Bob follows @star: write `followers_by_user(@star, Bob)` (fan-out: "who follows
@star?") and `following_by_user(Bob, @star)` (read: "whom does Bob follow?"): **denormalized**, a
job repairs drift. Bob's `POST /v1/posts` (Idempotency-Key `6f1c`) times out; the retry with `6f1c`
returns the same `post_id 1790012345678901234`, a **Snowflake-style 64-bit ID** with the timestamp
in its high bits, so sorting IDs = sorting by time (feeds, cursors, merges get easy).

> **Common mistake:** a graph database "because it is a graph". One-hop lookups are two KV
> tables, one partition read each; graph DBs earn their place for multi-hop (friends-of-friends).

## Feed: architecture and one request end to end

**How it works** · async fan-out of IDs on write; read one ID list and hydrate it.

```text
Client -> API gateway (auth, rate limit)
   |-> Post svc -> Posts DB + outbox -> Kafka post-events -> Fan-out workers --push IDs--+
   |-> Feed svc -> Feed cache (Redis) <-------------------------------------------------+
   |        +-> merge celebrity timelines, ranking svc, hydration (post/user/count caches)
   |-> Graph svc -> followers_by_user / following_by_user
   |-> Like svc -> likes table -> Kafka like-events -> aggregator -> counter cache/DB
Media: client --pre-signed PUT--> object storage -> processor -> CDN -> readers
```

**Example** · follow one post by @star (40M followers, threshold T = 100k) and Bob's next open.

```text
09:00:00 @star POST /v1/posts -> post_id P* stored + outbox row (one DB transaction)
09:00:00 outbox -> Kafka post-events; fan-out worker: 40M >= T -> NO fan-out,
         only timeline:@star gets P* (1 write instead of 40M)
09:00:01 Dev (300 followers, 210 active in 30 d) posts -> 210 x (LPUSH + LTRIM 0 499)
09:00:05 Bob opens app (follows 200; 3 are celebs: @star, @chef, @coach)
  1 LRANGE feed:Bob 0 19 -> 20 pushed IDs (includes Dev's post)
  2 following(Bob) n celeb set -> 3 cached timeline reads x 20 IDs
  3 k-way merge 20 + 60 IDs by post_id -> top 20; P* is newest -> slot #1
  4 hydrate: MGET posts, MGET authors, MGET like counts (~1 ms each)
  5 200 OK, 20 posts, next_cursor = base64({last_post_id: <20th id>})
```
So: the celebrity paid 1 write; Bob paid 3 extra cache reads; the outbox kept DB and event in sync.

## Fan-out on write vs read vs hybrid

**How it works** · **Fan-out** = delivering one item to many destinations.
- **Push (fan-out on write):** on post, append the ID to every follower's feed; read = one list
  read. Pay: write amplification = follower count, wasted on followers who never log in.
- **Pull (fan-out on read):** on open, fetch ~20 recent IDs from each of the 200 followed authors
  and **k-way merge** (heap, O(N log k)). The merge is cheap; **200 network fetches per open** are
  not: 100k reads/s x 200 = 20M timeline reads/s.
- **Hybrid:** authors below a threshold T (10k-100k followers) fan out on write; "celebrities"
  only write their own timeline and are merged at read time. Refinements: **skip inactive users**
  (one-time pull when they return); **priority lanes** per account size; tune T.

**Example** · why neither pure model survives. Push: a 50M-follower post at a ~1M inserts/s
fan-out budget = ~50 s of the *entire* capacity; other posts wait (**head-of-line blocking**).
Pull: if each call is slow 1% of the time, 1 - 0.99^200 = ~87% of opens hit a slow call (**tail
latency amplification**). Hybrid for Bob: 3 celeb reads -> 1 - 0.99^3 = ~3%.

| Approach | Write | Read | Freshness | Best for |
|---|---|---|---|---|
| Push | O(followers) | O(1) | few s lag | normal accounts |
| Pull | O(1) | O(following) | real time | celebrities, inactive readers |
| Hybrid | bounded | O(1) + few merges | fine | real social networks |

> **Say it like this:** "I fan out on write for normal accounts because reads dominate, but skip
> fan-out above roughly 100k followers. Their posts are pulled and merged at read time; a user
> follows only a few of them, and their timelines are always hot in cache."

## The feed cache in Redis

**How it works** · each feed is a bounded list of **IDs, not posts** (a post sits in thousands
of feeds; edits must work), **sharded by user_id**, **rebuildable** (lost = rebuild by pull).

**Example** · a delayed fan-out event for P11 arrives after P12. **List** (`LPUSH` + `LTRIM 0
499`): `[P12 P10 P9]` -> `[P11 P12 P10 P9]`, wrong order, and index paging shifts. **Sorted set**
(`ZADD feed:Bob 11 P11`, score = time or rank): `[P12 P11 P10 P9]`, paged by score. Cost: list
~5 KB/user = ~2.5 TB at 500M users; sorted set several times more (10+ TB). So: lists + time-ordered
IDs, or sorted sets for active users only; bound it (a few hundred to ~800; deeper pages pull).
Hydrating 20 = 1 ID read + batched `MGET`s (posts, authors, counts): ~4 round trips, not 60.

## Ranking, media, pagination

**How it works** · a **cursor** says "where I stopped": next page = `post_id < k` (`WHERE post_id
< k ORDER BY post_id DESC LIMIT 20`, or score < k in Redis), a `lower_bound` on a sorted key,
O(log n + page). Snowflake IDs are unique and time-ordered, so `post_id` alone is stable; encode
it (base64, optionally signed) so it is opaque. Ranked feeds point into a cached ranked list.

**Example** · page size 2. t=0 feed `[P10 P9 P8 P7]`, page 1 = P10, P9. t=1 P12, P11 arrive:
`[P12 P11 P10 P9 P8 P7]`. Offset page 2 = skip 2 -> P10, P9 again: **duplicates** (a delete
would **skip** items; large SQL offsets also walk skipped rows). Cursor page 2 = `post_id < P9`
-> P8, P7: correct.

> **Black box:** **Ranking** funnel: ~500-1,000 candidates -> **feature store** -> model predicts
> P(like), P(comment), P(hide), weighted sum -> rules (diversity, freshness) -> top 20, cached per
> session. **Use when:** engagement matters. **Cost:** latency, compute, debuggability;
> chronological is the simple fallback.

> **Black box:** **Media** bypasses the post svc: pre-signed PUT -> "object created" event ->
> resize, thumbnails, strip EXIF/GPS -> CDN. **Use when:** any user media. **Cost:** async step;
> files are **immutable** (edit = new name, e.g. `m_123_640w.jpg`), so CDN TTLs can be very long.

## Likes and counters at scale

**How it works** · separate the **like record** (idempotent upsert, `likes(post_id, user_id)` +
`user_likes` for "did I like this?") from the **count**. `likes + 1` at 50k/s = a **hot key**.
Fixes: **sharded counter** (`INCR likes:P:{rand(0..15)}`, read = cached sum of 16 keys) or the
better default, **async aggregation**.

**Example** · P* gets 50,000 likes in one second. Each like upserts `likes(P*, user)` (a double
tap rewrites the row) and emits to Kafka `like-events` keyed by P*. The aggregator holds `{P*:
50000}`, at the 1 s tick runs `INCRBY likes:P* 50000`, then commits offsets. A crash between the
two replays the batch (+100,000, at-least-once); a periodic recount from the likes table repairs
it. So: 50k writes -> 1, the count lags ~1 s.

## Feed: failures, trade-offs, 5-minute pitch

**How it works** · celebrity storm -> hybrid + separate lanes; Redis node dies -> replicas, rebuild
by pull; fan-out lag -> autoscale on consumer lag; viral post -> replicated hot keys, in-process
cache, sharded counters; deleted post / unfollow -> filter at hydration; slow ranking -> chronological.

**Example** · the Redis shard holding `feed:Bob` dies and its replica lagged. Bob's `LRANGE`
misses -> feed svc pulls his 200 followees' timelines once, merges, writes 500 IDs back, serves.
One slow open (~200 reads), then O(1) again. Trade-offs: write amplification vs read latency,
RAM vs freshness, exact vs approximate counts.

**5-minute pitch:** (1) scope: post, follow, paged feed, likes; read-heavy, eventual OK; (2) 300M
DAU, 35k reads/s (100k peak), 230k inserts/s, 50M celeb; (3) REST + cursor, Snowflake IDs, two-way
graph, Redis ID lists, CDN; (4) hybrid fan-out, skip inactive, the 50M arithmetic; (5) ID cache +
hydration, ranking fallback, async counters, lag alerts.

## Chat: requirements and numbers

**How it works** · same method; socket count and heartbeat rate shape this design.
- **Functional:** 1:1 + groups (~1,000), offline delivery + push, receipts, presence, multi-device.
- **Non-functional:** p99 < 500 ms, per-conversation order, acked = never lost, presence best effort.

| Quantity (500M DAU, 40 msgs/day, 40% concurrent) | Value |
|---|---|
| Messages | 20B/day, ~230k/s avg, ~700k/s peak |
| Deliveries (~2 per message) | ~1.4M/s peak |
| Storage (~200 B/msg) | 4 TB/day, ~1.5 PB/yr raw, ~4.5 PB/yr with 3 replicas |
| Concurrent sockets | 200M; ~200k per gateway (10-20 KB each) -> ~1,000 (+headroom ~1,500) |
| Heartbeats (every 30 s) | ~6.7M/s, must terminate at gateways |

**Example** · 200M sockets / 200k per gateway = 1,000 gateways; 200M / 30 s = ~6.7M pings/s,
i.e. ~6,700/s per gateway: easy in memory, fatal if each ping hit a database.

> **Common mistake:** promising "exactly-once delivery". Over an unreliable network you do
> at-least-once (retry until acked) and make it *look* exactly-once by deduplicating on a unique
> message ID.

## Chat: API and data model

**How it works** · one **WebSocket** per client (full-duplex, HTTP upgrade; long-poll fallback).
Frames: `send{client_msg_id}` -> `ack{msg_id, seq}` -> recipient `message{seq}` ->
`delivered/read{up_to_seq}`; `ping`/`pong`. HTTP for history (`before_seq`), `/sync`, groups.
Messages: **wide-column** `PRIMARY KEY ((conv_id, bucket), seq)`, `seq DESC`, `bucket =
seq/10000`; LSM makes appends cheap. Also `conversations`, `conversation_members`,
`user_conversations -> last_read/delivered_seq`, `devices -> push_token`.

**Example** · (1) `client_msg_id c-7f3a` is the **idempotency key**: ack lost, resend `c-7f3a`,
server returns the original `m_991, seq 1043`. (2) seq 1043 -> bucket 0, seq 25,310 -> bucket 2:
"last 50" = one partition read, no partition grows forever. (3) 1:1 id for u_9 and u_3 =
`u_3:u_9` (`min:max`): both sides compute it, so get-or-create has no race.

## Chat: architecture and one message end to end

**How it works** · thin socket holders; the chat svc owns seq, persistence, fan-out.

```text
Phone A ==WS==> Gateway G1 --> Chat svc (owner of c_42) --> Gateway G7 ==WS==> Phone B
                                |  seq, dedupe, persist |
                                |                       +--> Registry (Redis, TTL)
                                +--> Messages DB (wide-col)   user/device -> gateway
                                +--> Kafka --> Push svc --> APNs / FCM (offline users)
Gateways --connect/disconnect--> Presence svc (Redis, TTL)
```

**Example** · message #1043 in c_42, from A to B, who is offline.

```text
09:14:02.100 A -> G1: send{c-7f3a, c_42, "on my way"}; owner: c-7f3a unseen
09:14:02.110 seq = next_seq(c_42) = 1043; append (c_42, 0, 1043); DB ok
09:14:02.130 ack{m_991, seq 1043} -> A: single tick (durably stored)
09:14:02.131 MGET conn:B:phone conn:B:laptop -> nil, nil: offline -> Kafka -> push -> FCM
09:20:30 B opens app -> G7: SET conn:B:phone G7 EX 90; GET /sync?since={c_42:1040}
09:20:30 server returns 1041..1043; phone: delivered{up_to_seq 1043}
         B.last_delivered_seq 1040 -> 1043 -> A sees double tick
09:21:05 B opens chat: read{up_to_seq 1043}; last_read_seq = 1043 -> A sees blue ticks
```
So: persist before ack, offline = push + sync by seq, receipts = two small watermark writes.

## WebSocket gateways and the registry

**How it works** · a WebSocket pins a client to **one server**, breaking "any server handles any
request". A load balancer spreads new connections; a **registry** maps device -> gateway:
`SET conn:{user}:{device} G7 EX 90` on connect, batched TTL refresh every ~60 s, `DEL` on
disconnect, `MGET` all devices on lookup. The chat svc reaches G7 by RPC or a per-gateway
pub/sub channel. Deploys drain gateways gradually.

**Example** · G7 crashes at 10:00:00 holding 200k sockets. No `DEL`s run, but keys last refreshed
by 09:59:30 all expire by ~10:01:00 (**TTL**). Clients reconnect with **backoff + jitter** (random
0-1 s, then 0-2 s, 0-4 s ...), so 200k arrivals spread out instead of a **reconnect storm**; each
re-registers and calls `/sync`. Messages were persisted first: none lost. A delivery to G7 in the
meantime fails -> treated as offline -> push.

## Message flow and ordering

**How it works** · send = dedupe -> seq -> append -> ack (**persist first, then ack**); online ->
deliver via registry; offline -> push, then `/sync` (`seq > last_delivered_seq`), no separate
inbox (delete-after-delivery systems keep per-user inbox queues). **Ordering:** clocks disagree,
so a server-assigned increasing **per-conversation seq** orders everything; no global order.

| Way to assign seq | Trade-off |
|---|---|
| One owner per conv_id (hashing or Kafka partition), in-memory counter | fast; needs lease + **fencing** after failover |
| Redis `INCR seq:{conv}` | simple; async-replica failover can repeat a seq |
| Conditional DB insert ("seq must not exist") | correct; CAS costs extra round trips (Paxos in Cassandra) |

**Example** · in group g_5, X and Y send in the same millisecond; Y's frame reaches the owner
first -> seq 1044, X -> 1045; every member shows Y then X, whatever their clocks. Owner O1 (lease
epoch 7) stalls; O2 (epoch 8) reads max seq 1045 and writes 1046; O1 wakes and tries 1046: the
conditional insert fails, no silent overwrite. A client holding 1045 that receives 1048 sees a
**gap** and fetches 1046-1047 via `/sync`. Own messages show "pending" until acked.

> **Say it like this:** "I only guarantee order within a conversation. One owner assigns a
> monotonically increasing seq before persisting; clients sort by it and detect gaps. Client
> timestamps are for display only."

## Receipts, presence, push

**How it works** · receipts are **watermarks**: read if `s <= last_read_seq`, delivered if `s <=
last_delivered_seq`, else sent. Groups: 500 x 500 receipt pushes is too many, so the sender
**pulls** "message info". **Presence:** ping every 30 s (TCP keepalive defaults are hours; a phone
losing signal sends no FIN), close after ~60-90 s of silence. **Heartbeats stay at the gateway**;
only connect/disconnect and batched TTL refreshes hit the presence store.

**Example** · B's watermarks are `delivered 1057, read 1050`. A's screen: 1043-1050 blue ticks,
1051-1057 double tick, 1058 single tick: three states from two numbers, no per-message rows.
Presence fan-out naively: 200M users x 10 flaps/day x 500 contacts = 1 trillion events/day
(~11.6M/s). If only 2 contacts have B's chat open, a flap costs 2 events; a 4 s tunnel drop is
**debounced** (0). No presence in large groups; "hide last seen" is a read-time filter.

> **Black box:** **Push** for offline users goes through **APNs/FCM**, which keep their own
> connection to the phone. Push svc looks up device tokens, **collapses** bursts ("5 new
> messages"), respects mute, deletes invalid tokens. **Use when:** no live socket. **Cost:**
> best effort (delay, drop, coalesce); the message store stays the source of truth; with E2EE it
> says "New message" or carries ciphertext.

## Groups, multi-device, E2EE

**How it works** · a group message is **stored once** and delivered to every member's online
devices (fan-out on write for delivery only). Hence a **group size cap** (hundreds to ~1,000);
beyond that a **channel** switches to fan-out on read: push only to viewers, no per-member
receipts, notify on mentions or digest, unread computed on read. **Multi-device:** each device
has its own registry key and per-conversation cursor; delivery includes the sender's other
devices; read state syncs.

**Example** · a 1,000-member group message = 1 append + 1,000 registry lookups + a push per
online device; if 1% of 700k msgs/s are large-group, 7k x 1,000 = 7M deliveries/s. A 200k-member
channel with 3k viewing = 3k pushes; my unread = 25,310 - 25,290 = 20, computed on read. A laptop
waking after 3 days: `GET /sync?since={c_42:1001, c_77:350}` -> newer messages + watermarks.

> **Black box:** **End-to-end encryption**: devices hold key pairs, public keys on a key server;
> a Signal-style protocol sets up per-pair session keys and keeps **ratcheting**; groups use a
> "sender key" to encrypt once. **Use when:** privacy is a product promise. **Cost:** server
> cannot search, moderate or preview; encrypt per recipient device; new-device history needs
> another device or an encrypted backup.

## Chat: failures, trade-offs, 5-minute pitch

**How it works** · persist-first plus IDs and TTLs make failures recoverable: gateway crash or
reconnect storm -> jitter, LB admission control, sync by seq; huge group -> caps, channels; owner
failover -> fencing + conditional insert; stale registry -> TTL; presence storms -> viewers only;
cross-region -> users and conversations homed to one region. Trade-offs: history forever vs
delete after delivery; exact receipts vs fan-out cost.

**Example** · A's ack for c-7f3a is lost and B gets m_991 twice after a retry: the server
dedupes `c-7f3a` (one row, seq 1043), B's client drops the second `m_991`: shown once.

**5-minute pitch:** (1) 1:1 + groups, offline, receipts, presence, devices; at-least-once + dedupe;
(2) 230k msgs/s (700k peak), 200M sockets -> ~1,000+ gateways, 6.7M pings/s; (3) thin WS gateways,
TTL'd registry, jittered reconnect + sync; (4) owner seq, persist, ack, deliver/push;
(5) watermarks, presence for viewers, group cap vs channels, devices, E2EE.

## In one minute

- Both are fan-out problems: choose **push vs pull by audience size**.
- Feed: push for normal users, pull + merge for celebrities, skip inactive users.
- Feed caches = bounded, rebuildable **post-ID lists**, batched hydration, **cursor** paging.
- Like **record** = idempotent upsert; like **count** = sharded or async-aggregated.
- Chat: thin **WebSocket gateways** + TTL'd **registry**; **persist, then ack**; dedupe by ID.
- Order **per conversation** via a fenced owner's **seq**; wide-column `(conv_id, bucket)`.
- Receipts are **watermarks**; presence goes to viewers only; big groups become channels.

## Go deeper

Full chapter 19: sections 19.7 (fan-out), 19.21 (gateways, registry), 19.23 (ordering), 19.12 (counters)
