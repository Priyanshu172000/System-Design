# 19 — Case study: news feed and chat

This chapter designs two systems that show up in almost every design conversation: a social
**news feed** (a home timeline like Twitter or Instagram) and a **chat system** (1:1 and group
messaging like WhatsApp or Slack). They look different but share one core question: when one
user produces something, how does it reach many other users quickly, in the right order, without
melting the servers? Both designs follow the chapter-16 method step by step.

## 19.1 How to read this chapter

Each case study walks the chapter-16 steps in order: requirements, estimation, API, data model,
high-level diagram, deep dives, bottlenecks, final diagram, and a 5-minute summary. All numbers
are **assumptions**: say them out loud ("let me assume 300M daily active users") and let the
listener correct the scale. The point is showing that the numbers drive the design.

---

# Part A — News feed

## 19.2 Requirements

### Functional requirements

- A user can **create a post** (text, optionally images or a short video).
- A user can **follow** and **unfollow** other users.
- A user can open their **home feed**: posts from people they follow, newest or "best" first.
- The feed is **paginated**: scroll down to load more.
- Users can **like** a post and see the like count.

Out of scope (say so explicitly): comments, search, ads, messaging, notifications (chapter 20).

### Non-functional requirements

| Requirement | Target (assumption) | Why it matters |
|---|---|---|
| Feed read latency | p99 < 200 ms server side | Opening the app must feel instant |
| Availability | 99.95%+ for reading the feed | A blank feed is the app being "down" |
| Post visibility delay | a few seconds is fine | Nobody notices if a post appears 5 s late |
| Consistency | eventual for feeds and counts | A like count off by a few is invisible |
| Durability | posts must never be lost | Losing user content is unacceptable |
| Read:write ratio | very read-heavy | Drives "precompute on write" choices |

> **Say it like this:** "The feed is read-heavy and tolerates a few seconds of staleness, so I
> will trade freshness for read speed: precompute feeds where I can, and accept eventual
> consistency on feeds and like counts. Posts themselves must be durable."

## 19.3 Estimation

Assumptions (state them):

- 300M **DAU** (daily active users), 500M monthly; 100M new posts per day.
- Average user follows 200 accounts; average follower count is also roughly 200, but the
  distribution is extremely skewed: a few accounts have tens of millions of followers.
- Each DAU opens the feed 10 times a day, 20 posts per load; 20% of posts have a ~500 KB image.

Throughput:

```text
Posts:        100M / 86,400 s        ≈ 1,200 posts/s avg   → peak ×3 ≈ 3,500/s
Feed reads:   300M × 10 / 86,400     ≈ 35,000 reads/s avg  → peak ≈ 100,000/s
Post fetches: 100k feed reads × 20   ≈ 2M post lookups/s at peak (must be cached)
Fan-out:      100M posts × 200 followers = 20B feed inserts/day
              20B / 86,400           ≈ 230,000 inserts/s avg → peak ≈ 700,000/s
```

Storage:

```text
Post metadata: ~1 KB each × 100M/day = 100 GB/day   → ~36 TB/year (before replication)
Images:        20M/day × 500 KB      = 10 TB/day    → ~3.6 PB/year (object storage)
Feed cache:    keep the newest ~500 post IDs per active user
               500 IDs × ~10 bytes (compact list encoding, roughly) ≈ 5 KB per user
               500M users × 5 KB ≈ 2.5 TB of RAM (spread across a Redis cluster)
```

Two numbers drive the whole design:

1. **Reads outnumber posts by ~30×** (35k vs 1.2k per second), so reading must be cheap.
2. **Fan-out is huge and skewed**: one post from an account with 50M followers is 50M writes.

## 19.4 API

REST over HTTPS (chapter 02). The client sends an auth token; the server derives the user.

```http
POST /v1/posts
Idempotency-Key: 6f1c...            (client-generated, so a retry does not double-post)
{ "text": "hello", "media_ids": ["m_123"] }
→ 201 { "post_id": "1790012345678901234", "created_at": "..." }

POST   /v1/users/{user_id}/follow     → 204
DELETE /v1/users/{user_id}/follow     → 204

GET /v1/feed?limit=20&cursor=<opaque>
→ 200 { "posts": [ {post...}, ... ], "next_cursor": "eyJ..." }

PUT    /v1/posts/{post_id}/like       → 204   (PUT: liking twice is the same as once)
DELETE /v1/posts/{post_id}/like       → 204

POST /v1/media/upload-url        → 200 { "media_id": "m_123", "upload_url": "https://..." }
```

Like uses `PUT`/`DELETE` because it is naturally **idempotent** (chapter 02); the feed uses an
**opaque cursor**, not `page=3` (19.11); media goes **directly to object storage** via a
pre-signed URL (chapter 09).

## 19.5 Data model

| Data | Shape | Store | Why |
|---|---|---|---|
| Users | `user_id, name, avatar_url, ...` | SQL (sharded by user_id) | Small, relational, strong consistency for profile edits |
| Posts | `post_id, author_id, text, media_ids, created_at` | Wide-column or sharded SQL, keyed by post_id | Huge volume, simple key lookups, append-mostly |
| Author timeline | `(author_id) → post_ids newest first` | Wide-column: partition `author_id`, cluster `post_id DESC` | "Recent posts by X" is one partition scan |
| Follow graph | `follower_id, followee_id, created_at` | Two tables: by follower and by followee | Both directions are queried |
| Home feed cache | `user_id → [post_id, ...]` | Redis | Precomputed, read 100k/s |
| Likes | `(post_id, user_id)` | Wide-column, partition post_id | Idempotent, "did I like this?" check |
| Like counts | `post_id → count` | Redis + periodic flush to DB | Hot, approximate is fine |
| Media | binary blobs | Object storage + CDN | Cheap, durable, edge-cached |

**Post IDs** are Snowflake-style 64-bit IDs (chapter 10): timestamp in the high bits, so sorting
by ID is (roughly) sorting by time. This one choice makes feeds, cursors and merging easy.

The **follow graph** is stored twice because there are two questions:

```text
followers_by_user   (partition: followee_id, cluster: follower_id)
   "Who follows X?"   → used by fan-out when X posts

following_by_user   (partition: follower_id, cluster: followee_id)
   "Whom does Y follow?" → used to build Y's feed on read, and for the "Following" page
```

Writing both on follow is cheap (follows are rare); a background job repairs any disagreement.
This is classic **denormalization** (chapter 06): pay on write, save on read.

> **Common mistake:** Putting the follow graph in a graph database "because it is a graph". The
> feed only needs one-hop lookups (followers of X, followees of Y). Two key-value style tables
> answer both in one partition read. A graph database earns its place for multi-hop queries such
> as "friends of friends", not here.

## 19.6 High-level architecture

```text
                         ┌──────────────┐
  Mobile / Web ─────────►│ API gateway  │  auth, rate limit (ch 12)
                         └──────┬───────┘
          ┌─────────────────────┼──────────────────────┐
          ▼                     ▼                      ▼
   ┌─────────────┐       ┌─────────────┐        ┌─────────────┐
   │ Post service│       │ Feed service│        │ Graph svc   │
   └──────┬──────┘       └──────┬──────┘        └──────┬──────┘
          │ write post          │ read feed IDs        │ followers / following
          ▼                     ▼                      ▼
   ┌─────────────┐       ┌─────────────┐        ┌─────────────┐
   │ Posts DB    │       │ Feed cache  │        │ Follow DB   │
   │ (wide-col)  │       │ (Redis)     │        └─────────────┘
   └──────┬──────┘       └──────▲──────┘
          │ "post created"      │ push post_id into followers' feeds
          ▼                     │
   ┌─────────────┐       ┌──────┴──────┐
   │ Kafka topic │──────►│ Fan-out     │  (workers, horizontally scaled)
   │ post-events │       │ workers     │
   └─────────────┘       └─────────────┘

   Media: client ──pre-signed PUT──► Object storage ──► CDN ──► clients
```

Write path: the post service stores the post, then publishes `post-created` (via an **outbox**,
chapter 11, so DB write and event cannot diverge); fan-out workers push the post ID into each
follower's feed. Read path: the feed service reads the user's post IDs from Redis, **hydrates**
them (IDs → full posts with author, avatar, like count) from caches, and returns a page.

## 19.7 Deep dive: fan-out on write vs fan-out on read vs hybrid

This is the central decision of the design. **Fan-out** means delivering one item to many
destinations.

### Fan-out on write (push model)

When a user posts, immediately append the post ID to the feed of every follower.

```text
Alice posts P  ──►  followers(Alice) = [Bob, Carol, Dan, ...]
                    feed:Bob   ← push P
                    feed:Carol ← push P
                    feed:Dan   ← push P
Reading Bob's feed later = read one list. O(1) lookups, no merging.
```

- Gain: reads are trivially cheap (one Redis read). Reads are 30× more common, so this is
  usually the right default.
- Pay: write amplification equal to follower count. Wasted work for followers who never log in.

### Fan-out on read (pull model)

Store nothing per reader. When Bob opens the feed, fetch the recent posts of everyone Bob follows
and merge them.

```text
Bob opens feed ──► following(Bob) = 200 accounts
                   fetch latest ~20 post IDs from each author timeline
                   k-way merge by post_id (time) → top 20
```

DSA bridge: this is merging k sorted lists. With a min-heap (here a max-heap on time) of size k
you get the top N in O(N log k) after fetching. The expensive part is not the merge, it is the
**200 network fetches per feed open**: 100k feed reads/s × 200 = 20M timeline reads/s.

- Gain: cheap posting, no wasted work, always fresh. Pay: slow, expensive reads on the hot path.

### Why neither works alone: the numbers

Pure push breaks on **celebrities**. Assume an account with 50M followers:

```text
One post        = 50,000,000 feed inserts
Cluster budget  ≈ 1,000,000 inserts/s dedicated to fan-out (assumption)
Time to deliver ≈ 50 s of the ENTIRE fan-out capacity for one post
10 posts/day    = 500M inserts from one account (~2.5% of all daily fan-out)
Most followers  are inactive on any given day → most of those writes are wasted
```

Meanwhile everyone else's posts wait behind it (head-of-line blocking).

Pure pull breaks on **read volume**: 20M timeline reads/s, and p99 is set by the slowest of 200
parallel calls (**tail latency amplification**: if each call has a 1% chance of being slow,
200 calls almost always include a slow one; 1 − 0.99^200 ≈ 87%).

### Hybrid: push for normal users, pull for celebrities

```text
Author has  < T followers (e.g. T = 10k–100k)  → fan-out on write
Author has >= T followers ("celebrity")        → no fan-out; stored only in author timeline

Bob opens feed:
  1. read feed:Bob from Redis              (pushed posts from normal authors)
  2. celebs = following(Bob) ∩ celebrity set   (typically a handful, say ≤ 20)
  3. fetch recent post IDs of each celeb   (author timelines are extremely hot → cached)
  4. merge 1 + 3 by post_id, take top N
```

Why it works: a user follows only a few celebrities, so step 3 is a handful of cache reads, and
celebrity timelines are read by millions, so they are always cached. The worst offenders never
fan out.

More refinements that are common in practice:

- **Skip inactive users** during fan-out: only push to followers active in the last N days. When
  an inactive user returns, build their feed with a one-time pull.
- **Priority lanes**: separate fan-out queues by account size; tune T so fan-out cost and
  read-time merge cost balance.

| Approach | Write cost | Read cost | Freshness | Best for |
|---|---|---|---|---|
| Push (on write) | O(followers) | O(1) | Few seconds lag | Normal accounts |
| Pull (on read) | O(1) | O(following) | Real time | Celebrities, inactive readers |
| Hybrid | Bounded | O(1) + few merges | Mixed, fine | Real social networks |

> **Say it like this:** "I would fan out on write for normal accounts because reads dominate,
> but skip fan-out for accounts above roughly 100k followers. Their posts are pulled and merged
> at read time; a user follows only a few of them, so the merge is cheap, and their timelines
> are always hot in cache."

## 19.8 Deep dive: the feed cache in Redis

Each user's precomputed feed is a bounded list of post IDs in Redis (chapter 08).

Option 1: **Redis list** of post IDs, newest at the head.

```text
LPUSH feed:{user} <post_id>      # push new post at head
LTRIM feed:{user} 0 499          # keep only newest 500
LRANGE feed:{user} 0 19          # first page
```

Option 2: **Redis sorted set**, score = timestamp or ranking score.

```text
ZADD feed:{user} <score> <post_id>
ZREMRANGEBYRANK feed:{user} 0 -501         # trim to newest 500 (lowest scores removed)
ZREVRANGEBYSCORE feed:{user} (<cursor_score> -inf LIMIT 0 20   # page after cursor
```

| | List | Sorted set |
|---|---|---|
| Ordering | Insertion order | By score |
| Out-of-order arrival | Lands in wrong place | Placed correctly |
| Page after a cursor | By index (shifts as new items arrive) | By score (stable) |
| Memory per entry | Small (compact encoding) | Several times larger (skip list + hash) |

The sorted set handles late-arriving events and cursor paging cleanly; the list is cheaper in
RAM. With 500M users the difference is real (a few TB vs potentially 10+ TB), so many designs use
lists of IDs and rely on Snowflake IDs being time-ordered, or use sorted sets only for active
users. Either answer is fine if you state the trade-off.

Practical rules:

- **Store IDs, not posts.** A post sits in thousands of feeds; copying its body would multiply
  storage and make edits impossible.
- **Bound the list** (a few hundred to ~800 entries); deeper pages fall back to pull.
- **It is a cache**: evict inactive users; a lost feed is rebuilt by pulling from the follow
  graph and author timelines, so it needs no heroic durability.
- **Shard by user_id** across a Redis cluster (hash slots, chapter 07).

Hydrating one page of 20 = 1 call for the IDs + one batched `MGET` each for posts, authors and
like counts (misses go to the DB and fill the cache): 4 round trips of ~1 ms instead of 60.

## 19.9 Deep dive: ranking (high level)

"Newest first" is easy. Most real feeds rank by predicted engagement. A common shape is a
**funnel** that goes from many cheap candidates to few expensively scored items:

```text
Candidate generation   Feature fetch        Scoring               Re-ranking / rules
(~500–1,000 IDs)  ──►  (author affinity, ──► (ML model predicts ──► (diversity: not 5 posts
 pushed feed +          post age, likes,      P(like), P(comment),   in a row from one
 celebrity pulls +      media type, ...)      P(hide) ...)           author; freshness;
 "recommended" posts                          score = weighted sum   policy filters)
                                                                     → top 20 returned
```

The precomputed feed is the candidate pool; features come from a low-latency **feature store**
(chapter 21, same pattern as ad serving); a model scores a few hundred candidates in tens of
milliseconds; the ranked list is **cached per session** so pagination stays stable.

Trade-off: ranking increases engagement but costs latency, compute, and debuggability ("why did I
see this?"). Chronological feeds are simpler and fully predictable.

## 19.10 Deep dive: media

Images and video never flow through the post service:

```text
1. Client  ──POST /media/upload-url──► Media service  (creates media_id, signs URL)
2. Client  ──PUT bytes (pre-signed)──► Object storage (S3-like, chapter 09)
3. Storage ──"object created" event──► Media processor: resize to several sizes, strip
                                        metadata (e.g. GPS EXIF), generate thumbnails
4. Client  ──POST /posts {media_ids}─► Post service (checks media_id belongs to user, ready)
5. Readers fetch https://cdn.example.com/m_123_640w.jpg  ──► CDN edge ──miss──► storage
```

App servers stay stateless, storage is cheap and durable, and media files are **immutable** (an
edit = a new file name), so the CDN can cache them with very long TTLs (chapter 08).

## 19.11 Deep dive: pagination by cursor

**Offset pagination** (`?page=3&size=20` → "skip 40, take 20") breaks on a live feed:

```text
t=0  feed: [P10 P9 P8 P7 P6 P5 ...]     page 1 shows P10..P9 (size 2 for illustration)
t=1  two new posts arrive: [P12 P11 P10 P9 P8 ...]
t=2  page 2 = skip 2 → [P10 P9]  ← DUPLICATES of page 1
```

Deletions cause the opposite bug: skipped items. Large offsets are also slow in SQL (the database
still walks the skipped rows).

**Cursor pagination**: the response includes "where I stopped"; the next request continues
strictly after it.

```text
page 1: return P12 P11 ... P(k)        next_cursor = encode({last_post_id: k})
page 2: items with post_id < k          (Redis: score < k; SQL: WHERE post_id < k
                                         ORDER BY post_id DESC LIMIT 20)
```

Snowflake IDs are unique and time-ordered, so `post_id` alone is a stable cursor. Encode it
(base64 JSON, optionally signed) so clients treat it as opaque; for ranked feeds it points into
the cached ranked list (`{rank_session: abc, position: 40}`).

DSA bridge: a cursor is a `lower_bound` on a sorted key; the next page is "walk forward from the
lower bound", which a B+ tree or sorted set does in O(log n + page size).

## 19.12 Deep dive: likes and counters at scale

Two separate things: **the like itself** (who liked what; correct and idempotent) and **the
count** (fast; approximate is fine).

### The like record

```text
likes  (partition: post_id, clustering: user_id)     PUT = upsert → naturally idempotent
user_likes (partition: user_id, clustering: post_id) "did I like this?" for my feed rendering
```

### The hot counter problem

A viral post gets, say, 50k likes per second. `UPDATE posts SET likes = likes + 1` on one row
means 50k writers fighting over **one row lock** (or one Redis key on one shard). That single key
becomes a **hot key**.

**Sharded counter**: split one logical counter into N physical counters.

```text
like on post P  → INCR likes:P:{rand(0..N-1)}      (N = 16, say, only for hot posts)
read count      → sum of likes:P:0 .. likes:P:15   (or a cached total refreshed every second)
```

Writes spread across N keys (and potentially N shards); reads cost N lookups, so cache the sum.

**Async aggregation** (usually the better default at scale):

```text
Like API ──► write like record ──► Kafka "like-events" (key = post_id)
                                     │
                                     ▼
                         Aggregator: in-memory map post_id → delta,
                         flush every 1 s: INCRBY likes:P <delta>  and periodic DB update
```

50k increments become one `INCRBY 50000` per second; the count lags about a second. Commit Kafka
offsets after each flush (at-least-once: a crash may double-apply one batch, fine for display; a
periodic recount from the likes table repairs drift).

> **Say it like this:** "I separate the like record from the like count. The record is an
> idempotent upsert keyed by post and user. The count is eventually consistent: like events go
> through Kafka keyed by post ID, an aggregator batches increments per second, and hot posts
> never create a single hot row."

## 19.13 Bottlenecks, failures and trade-offs

| Risk | What happens | Mitigation |
|---|---|---|
| Celebrity post | Fan-out storm, queue lag for everyone | Hybrid pull; separate fan-out lanes |
| Redis feed node dies | Some users' feeds empty | Replicas; rebuild on miss by pulling |
| Fan-out lag | Posts appear late | Autoscale workers on Kafka consumer lag |
| Hot post (viral) | Post cache key and counter overloaded | Replicate hot keys, local in-process cache, sharded counters |
| Deleted post | Its ID remains in millions of feed lists | Filter on hydration (post marked deleted); lazy cleanup |
| Unfollow | Old posts from that author stay in feed | Filter on read against following set, or accept briefly |
| Ranking service slow | Feed latency blows up | Timeout and fall back to chronological |

Trade-offs to name: **write amplification vs read latency** (push vs pull), **RAM cost vs
freshness** (IDs per user, for which users), **exact vs approximate counts**.

## 19.14 Final architecture (news feed)

```text
                               ┌───────────────┐
 Clients ──► CDN (media) ◄──── │Object storage │◄── pre-signed uploads
    │                          └───────▲───────┘
    ▼                                  │ resize/thumbs
 API gateway (auth, rate limit)    Media processor
    │
    ├──► Post svc ──► Posts DB (wide-col) + outbox ──► Kafka: post-events
    │                                                      │
    │                         ┌────────────────────────────┤
    │                         ▼                            ▼
    │                  Fan-out workers              Search/notification
    │                  (skip celebs, skip            consumers (other chapters)
    │                   inactive users)
    │                         │ LPUSH/LTRIM
    │                         ▼
    ├──► Feed svc ──► Feed cache (Redis, by user) ──► merge celeb timelines
    │        │                                         (author timeline cache)
    │        ├──► Ranking svc ──► Feature store
    │        └──► Hydration: post cache, user cache, counter cache
    │
    ├──► Graph svc ──► followers_by_user / following_by_user
    │
    └──► Like svc ──► likes table ──► Kafka: like-events ──► Aggregator ──► counter cache/DB
```

## 19.15 How to present this in 5 minutes (news feed)

1. **Scope (30 s):** post, follow, home feed with pagination, likes. Read-heavy, eventual
   consistency OK, posts durable.
2. **Numbers (45 s):** 300M DAU, 35k feed reads/s avg (100k peak), 1.2k posts/s, average 200
   followers → 230k feed inserts/s, and one celebrity post can be 50M inserts.
3. **API and data (45 s):** REST, cursor pagination, Snowflake IDs; posts in wide-column, follow
   graph stored in both directions, feeds as lists of IDs in Redis, media in object storage + CDN.
4. **Core decision (90 s):** hybrid fan-out: push for normal users, pull and merge for
   celebrities, skip inactive users. Show the 50M-follower arithmetic.
5. **Deep dives and failures (90 s):** feed cache of IDs + hydration, ranking with chronological
   fallback, async like counters, rebuildable cache, fan-out lag alerts.

---

# Part B — Chat

## 19.16 Requirements

### Functional requirements

- **1:1 chat** and **group chat** (assume groups up to ~1,000 members).
- Real-time text messages; media via object storage (as in 19.10).
- **Offline delivery** on reconnect, plus a **push notification**.
- **Delivery receipts** (sent, delivered, read) and **presence** (online, "last seen").
- **Multi-device**: a user's phone and laptop both see the full conversation.
- **Message history**: scroll back through old messages.

Out of scope: voice/video calls, message search, bots.

### Non-functional requirements

| Requirement | Target (assumption) |
|---|---|
| End-to-end delivery latency (both online) | p99 < 500 ms, typical ~100 ms in-region |
| Ordering | Messages in one conversation appear in the same order for everyone |
| Durability | An acknowledged message is never lost |
| Delivery semantics | At-least-once over the network, deduplicated to effectively-once on screen |
| Availability | Very high; chat is used for important things |
| Presence | Best effort; a few seconds stale is fine |

> **Common mistake:** Promising "exactly-once delivery". Over an unreliable network you can only
> do at-least-once (retry until acknowledged) and make it look exactly-once by deduplicating with
> a unique message ID on the receiver. Say that, and it sounds senior.

## 19.17 Estimation

Assumptions:

- 500M DAU, 40 messages/day each; peak concurrent connections ~40% of DAU = 200M.
- ~100 bytes of text per message, ~200 bytes stored with metadata.
- On average 2 recipient deliveries per message (mostly 1:1, some groups).

```text
Messages sent:  500M × 40 = 20B/day  → 20B / 86,400 ≈ 230,000 msgs/s avg → peak ×3 ≈ 700k/s
Deliveries:     ~2 per message       → ~1.4M deliveries/s at peak
Storage:        20B × 200 B = 4 TB/day → ~1.5 PB/year raw, ×3 replicas ≈ 4.5 PB/year
Connections:    200M concurrent; if one gateway holds ~200k connections (assumption:
                memory ~10–20 KB per idle TLS WebSocket, so ~2–4 GB per server)
                → ~1,000 gateway servers (plus headroom → ~1,500)
Heartbeats:     one per connection every 30 s → 200M / 30 ≈ 6.7M heartbeats/s
```

Those 6.7M heartbeats/s must **terminate at the gateways**, never at a database (19.25).

## 19.18 API

The server must **push** to clients, so each client keeps one long-lived **WebSocket** (chapter
02): full-duplex over TCP, started by an HTTP upgrade (long polling as a fallback).

Socket frames (JSON for readability; real systems often use a binary encoding):

```text
Client → Server   { "type": "send", "client_msg_id": "c-7f3a", "conv_id": "c_42",
                    "body": "hi", "sent_at_client": 1728550000000 }
Server → Client   { "type": "ack", "client_msg_id": "c-7f3a", "msg_id": "m_991",
                    "seq": 1057 }                         ← single tick: "sent"
Server → Client   { "type": "message", "conv_id": "c_42", "seq": 1057, "msg_id": "m_991",
                    "from": "u_1", "body": "hi" }
Client → Server   { "type": "delivered", "conv_id": "c_42", "up_to_seq": 1057 }
Client → Server   { "type": "read",      "conv_id": "c_42", "up_to_seq": 1057 }
Both directions   { "type": "ping" } / { "type": "pong" }     ← heartbeats
```

Regular HTTP for non-real-time things:

```http
GET  /v1/conversations?cursor=...                       → my conversation list
GET  /v1/conversations/{id}/messages?before_seq=1000&limit=50   → history (cursor by seq)
GET  /v1/sync?since=<per-conversation seqs>             → catch up after reconnect
POST /v1/conversations  { "members": [...] }            → create group
```

The `client_msg_id` is the **idempotency key**: if the client retries a send because the ack was
lost, the server sees the same ID and returns the original `msg_id`/`seq` instead of storing a
duplicate.

## 19.19 Data model

Chat history is write-heavy, append-only, and always read "latest N messages of one
conversation". That is exactly what a **wide-column store** (Cassandra/ScyllaDB/HBase style,
chapter 06) is good at:

```sql
-- Messages: one partition per (conversation, bucket), rows sorted by seq inside it
CREATE TABLE messages (
  conv_id     text,
  bucket      int,          -- e.g. seq / 10000, keeps partitions bounded in size
  seq         bigint,       -- per-conversation sequence number
  msg_id      text,         -- globally unique (Snowflake-style)
  sender_id   text,
  body        blob,         -- ciphertext if end-to-end encrypted
  created_at  timestamp,
  PRIMARY KEY ((conv_id, bucket), seq)
) WITH CLUSTERING ORDER BY (seq DESC);
```

Why this layout:

- **Partition key** `(conv_id, bucket)`: a conversation's messages live together, so "last 50"
  is one sequential read. The bucket stops busy, long-lived groups from growing one partition
  forever (huge partitions hurt compaction and repair); "channel + time bucket" partitioning is a
  well-documented approach at large chat companies.
- **Clustering key** `seq DESC`: rows are stored sorted, so newest-first reads need no sort.
- **LSM storage** makes appends cheap (sequential writes, chapter 06).

Other tables:

```text
conversations        (conv_id) → type (1:1 | group), created_at, name, ...
conversation_members (conv_id, user_id) → role, joined_seq
user_conversations   (user_id, conv_id) → last_read_seq, last_delivered_seq, muted
devices              (user_id, device_id) → push_token, platform, last_seen_seq per conv
```

Choice justified: high write throughput (700k/s), horizontal scaling, single-partition queries,
no cross-conversation joins or transactions. Sharded SQL works too, with the same partitioning.

For a **1:1 conversation ID**, a deterministic ID such as `min(u1,u2) + ":" + max(u1,u2)` makes
"get or create the chat between A and B" a single lookup with no race.

## 19.20 High-level architecture

```text
 Phone A ══WebSocket══► Gateway G1 ─┐                 ┌─► Gateway G7 ══WebSocket══► Phone B
 (sender)                           │                 │                          (recipient)
                                    ▼                 │
                             ┌──────────────┐         │
                             │ Chat service │─────────┤ "deliver to B" via G7
                             │ (owner of    │         │
                             │  conv c_42)  │   ┌─────┴────────┐
                             └──┬───────┬───┘   │ Connection   │ user → {device → gateway}
                                │       │       │ registry     │ (Redis, TTL)
               append(seq)      │       │       └──────────────┘
                                ▼       ▼
                     ┌──────────────┐  ┌──────────────┐     ┌──────────────┐
                     │ Messages DB  │  │ Kafka        │────►│ Push service │──► APNs / FCM
                     │ (wide-col)   │  │ (offline,    │     │ (offline     │
                     └──────────────┘  │  receipts,   │     │  users)      │
                                       │  analytics)  │     └──────────────┘
                                       └──────────────┘
                     ┌──────────────┐
                     │ Presence svc │ ◄── connect/disconnect events from gateways
                     └──────────────┘
```

**Gateways** only hold sockets, so deploying chat logic never drops 200M connections; the
**chat service** owns validation, sequence numbers, persistence and fan-out; the **registry**
answers "which gateway is B's device on right now?".

## 19.21 Deep dive: WebSocket gateways and the connection registry

A WebSocket is a long-lived TCP connection to **one specific server**, breaking the "any server
can handle any request" assumption (chapter 03). So we need a **load balancer** that spreads new
connections across gateways (chapter 12), and a **registry** mapping user/device → gateway.

```text
On connect:    SET conn:{user_id}:{device_id} "G7" EX 90   (one key per device, own TTL)
Every ~60 s:   gateway refreshes TTLs of its connected devices in batches (pipelined)
On disconnect: DEL conn:{user_id}:{device_id}
Lookup:        MGET conn:{u}:{phone} conn:{u}:{laptop} → { phone: G7, laptop: G3 }
```

The TTL matters: if a gateway crashes without cleaning up, stale entries expire on their own.

The chat service reaches G7 by direct RPC, or via a per-gateway pub/sub channel.

**Gateway failure:** its clients reconnect through the load balancer to other gateways,
re-register, and call `/sync`. Messages were persisted before delivery, so nothing is lost. To
avoid a **reconnect storm**, clients use **exponential backoff with jitter** (chapter 14); deploys
drain gateways gradually.

> **Say it like this:** "Gateways are thin and hold only sockets. A registry in Redis maps each
> device to its gateway with a TTL, so a crashed gateway's entries expire. If a gateway dies,
> clients reconnect with jittered backoff and sync from their last seen sequence number, so
> nothing is lost."

## 19.22 Deep dive: message flow (recipient online vs offline)

### Send path (common to both cases)

```text
A's phone            Gateway G1          Chat svc (owner of c_42)       Messages DB
   │ send{client_msg_id}  │                        │                          │
   │─────────────────────►│───────────────────────►│ dedupe client_msg_id      │
   │                      │                        │ seq = next_seq(c_42)      │
   │                      │                        │──── append(c_42,seq) ────►│
   │                      │                        │◄──────── ok ──────────────│
   │◄──── ack{seq} ───────│◄───────────────────────│  (now "sent" ✓)           │
```

Rule: **persist first, then acknowledge**; an ack means "durably stored". A client that never
gets the ack retries with the same `client_msg_id`, and dedupe returns the stored result.

### Recipient online

```text
Chat svc ── lookup registry(B) ──► {phone: G7}
Chat svc ── deliver(msg) ────────► G7 ══► B's phone
B's phone ── delivered{up_to_seq} ──► G7 ──► Chat svc ──► update B's delivered watermark
                                                       ──► notify A: "delivered ✓✓"
```

### Recipient offline

```text
Chat svc ── lookup registry(B) ──► (empty)
Chat svc ── publish "undelivered" event ──► Kafka ──► Push service ──► APNs/FCM ──► B's phone
                                                   (notification: "A: hi", or just
                                                    "New message" if E2E encrypted)
Later: B opens app ── connects ── GET /sync?since=... ── receives all messages after B's
       last seen seq per conversation ── sends delivered{up_to_seq}
```

No separate offline inbox is needed: "what did I miss" is "rows with seq > my last_delivered_seq"
in each conversation. (Systems that delete messages after delivery keep per-user inbox queues.)

## 19.23 Deep dive: message ordering per conversation

Problem: two people in a group send at almost the same moment. Client clocks disagree by seconds,
messages take different network paths, and server instances process them in parallel. If each
recipient sorts by "time received", different people see different orders.

Solution: a **per-conversation sequence number**, assigned by the server, strictly increasing.
Everyone displays messages sorted by `seq`. Global ordering across conversations is not needed
(and would be a scalability disaster), only order **within** a conversation.

How to assign `seq` safely at 700k msgs/s overall:

| Option | How | Trade-off |
|---|---|---|
| Single owner per conversation | Route each `conv_id` to one chat-service instance (consistent hashing, or Kafka partition keyed by `conv_id`); it keeps an in-memory counter | Fast; needs a lease/fencing so two instances never own the same conversation after a failover (chapter 10) |
| Atomic counter in Redis | `INCR seq:{conv_id}` | Simple; a Redis failover with async replication can hand out a duplicate seq → combine with a conditional DB insert |
| Conditional write in DB | Insert with "seq must not exist" (compare-and-set / lightweight transaction) | Correct but each CAS costs extra round trips (Paxos-based in Cassandra) |

A robust common pattern combines them: route by `conv_id` to an owner that holds the counter,
and make the DB insert conditional so a stale owner after a failover gets an error instead of
silently overwriting. After failover, the new owner reads the max `seq` from the DB and continues.

Clients display by `seq`; receiving 1060 after 1057 reveals a **gap** (1058–1059) to fetch via
`/sync`. The sender's own message shows as "pending" until the ack assigns its `seq`.

> **Say it like this:** "I only guarantee order within a conversation. Each conversation has one
> owner that assigns a monotonically increasing sequence number before persisting; clients sort
> by it and detect gaps. Client timestamps are just for display, never for ordering."

## 19.24 Deep dive: delivery receipts (sent, delivered, read)

| State | Meaning | Triggered by |
|---|---|---|
| Sent (✓) | Server durably stored it | Server ack to sender |
| Delivered (✓✓) | Recipient's device received it | Recipient device sends `delivered` |
| Read (blue ✓✓) | Recipient opened the conversation | Recipient device sends `read` |

Trick: because messages are sequenced, receipts are **watermarks**, not per-message flags.
"Delivered up to seq 1057" covers every earlier message in one small update:

```text
user_conversations(B, c_42): last_delivered_seq = 1057, last_read_seq = 1050
Sender's UI: message with seq s is
   read       if s <= B.last_read_seq
   delivered  if s <= B.last_delivered_seq
   sent       otherwise
```

This turns millions of per-message receipt writes into a few watermark updates per conversation.

Groups: pushing every member's read receipt to every member is 500 × 500 events for a
500-member group; instead store watermarks and let the sender **pull** "message info" on demand.
Many apps simplify receipts for large groups for exactly this reason.

## 19.25 Deep dive: presence (online, last seen)

Presence answers "is B online?" and "when was B last seen?".

```text
Client ── ping every 30 s ──► Gateway        (heartbeat; also keeps NAT/mobile paths alive)
Gateway: if no ping for ~2 intervals (60–90 s) → consider connection dead → close it
Gateway ── on connect / on disconnect ──► Presence service
Presence store (Redis):  presence:{user} = { status: online, last_seen: <ts> }  with TTL
```

Why heartbeats: a phone that loses signal sends no TCP FIN, and TCP keepalive defaults are
measured in hours. Key insight: **heartbeats stay at the gateway** (6.7M/s over ~1,000 gateways is
~7k/s each); only state **changes** and batched TTL refreshes reach the presence service.

The real expense is **presence fan-out**: when B comes online, who should be told?

```text
B has 500 contacts. 200M users flap online/offline several times a day.
Naive push to all contacts: 200M × (say) 10 changes/day × 500 = 1 trillion events/day
                           ≈ 11.6M presence events/s. Too expensive for a cosmetic feature.
```

Common mitigations:

- **Subscribe only while looking**: push presence only for chats/screens currently open.
- **Pull on open** instead of continuous push; **debounce** flaps shorter than a few seconds.
- **No presence in large groups**; privacy settings ("hide last seen") are read-time filters.

## 19.26 Deep dive: push notifications for offline users

If a recipient has no live connection, the chat service emits an event; the **push service**
(designed fully in chapter 20) sends a notification through Apple's **APNs** or Google's **FCM**.
Those platform services keep their own persistent connection to the phone, which is how a phone
with your app closed can still be woken up.

```text
Chat svc ── no active device for B ──► Kafka: offline-messages
Push svc: look up B's device tokens ── collapse (many messages → "5 new messages")
          ── respect mute settings ── send to APNs/FCM ── on "invalid token" → delete token
```

Push is **best effort** (APNs/FCM may delay, drop or coalesce); the message store stays the
source of truth. **Collapse** bursts into one notification. With end-to-end encryption the push
says "New message" or carries ciphertext the app decrypts locally.

## 19.27 Deep dive: group chat and fan-out limits

A group message is stored **once** (in the group's conversation partition) and delivered to every
member's online devices. This is fan-out on write for delivery, but storage is not multiplied.

```text
Group of 1,000 members, one message:
  1 DB append + 1,000 registry lookups + ~(online members × devices) socket pushes
  At 1% of 700k msgs/s being large-group messages: 7k × 1,000 = 7M deliveries/s → noticeable
```

That is why chat apps **cap group size** (on the order of hundreds to about a thousand members).
Beyond that, the product becomes a **channel/broadcast** (Slack-style large channels, Telegram-style
channels), and the delivery model changes to **fan-out on read**:

| Small group (≤ ~1,000) | Large channel (10k–1M+) |
|---|---|
| Push every message to every member's device | Push only to members currently viewing the channel |
| Per-member delivered/read receipts | No per-member receipts; maybe a view count |
| Push notification per message | Notifications only for mentions, or a digest |
| Unread counts per member updated eagerly | Unread = latest seq − my last_read_seq, computed on read |

Same push-vs-pull trade-off as the news feed: write amplification grows with audience size.

## 19.28 Deep dive: multi-device sync and end-to-end encryption

### Multi-device

Each device is a separate delivery target with its own registry entry and its own cursor:

```text
devices(B, phone):  last_synced_seq per conversation
devices(B, laptop): last_synced_seq per conversation

Laptop wakes after 3 days:
  GET /sync?since={c_42: 1001, c_77: 350, ...}
  → server returns messages with seq > cursor per conversation (paged), plus updated
    watermarks (read on phone → shows as read on laptop too)
```

Delivery fan-out targets **all devices of all members, including the sender's other devices**,
and read state syncs the same way (reading on the phone clears the laptop's unread badge).

### End-to-end encryption (high level only)

With **end-to-end encryption (E2EE)**, messages are encrypted on the sender's device with keys
only recipients' devices hold; the server stores and forwards ciphertext it cannot read.

- Each device has a key pair; public keys live on a key server. A widely used protocol (Signal)
  sets up per-pair session keys and keeps rotating them ("ratcheting").
- Groups commonly use a "sender key" scheme so the sender encrypts once for the group rather than
  once per member device.
- Design impact: the server cannot search, moderate, or render previews of message content;
  multi-device means encrypting for every device of every recipient; history on a new device
  must come from another device or an encrypted backup.

## 19.29 Bottlenecks, failures and trade-offs (chat)

| Risk | Impact | Mitigation |
|---|---|---|
| Gateway crash | 200k clients disconnected | Jittered reconnect, sync by seq, no data loss (persist first) |
| Reconnect storm after region blip | Login/registry overload | Backoff with jitter, admission control at LB |
| Hot group (huge, very active) | One conversation owner overloaded | Group size caps, channel model with fan-out on read |
| Owner failover for a conversation | Duplicate or skipped seq | Leases with fencing tokens + conditional insert |
| Lost ack | Client retries → duplicates | `client_msg_id` dedupe on server; `msg_id` dedupe on client |
| Registry stale (gateway died) | Messages sent to a dead gateway | TTL on entries; delivery failure → treat as offline → push |
| Presence storms | Millions of events/s | Subscribe-while-viewing, debounce, no presence for big groups |
| Cross-region users | High latency | Users homed to a region; conversations routed to one home region |

Trade-offs: keep history forever (multi-device, search) vs delete after delivery (smaller, more
private); exact receipts and presence vs fan-out cost.

## 19.30 Final architecture (chat)

```text
                        ┌────────────── L4/L7 load balancer (sticky per connection) ─────┐
 Devices ═WebSocket═════╪══► Gateway 1 … Gateway N  (hold sockets, heartbeats, TLS)      │
                        └──────────┬──────────────────────────────────▲──────────────────┘
                                   │ frames                           │ deliver(msg)
                                   ▼                                  │
                     ┌──────────────────────────────┐        ┌───────┴────────┐
                     │ Chat service                 │◄──────►│ Connection     │
                     │ (conv_id → owner, seq,       │ lookup │ registry Redis │
                     │  dedupe, persist, fan-out)   │        └────────────────┘
                     └───┬───────────┬──────────┬───┘
                         │           │          │
                         ▼           ▼          ▼
               Messages DB     Watermarks    Kafka ──► Push svc ──► APNs / FCM
               (wide-col,      (user_conv,          └─► Analytics, abuse detection
                conv+bucket)    devices)
                         ▲
                         │ history / sync (HTTP)
 Devices ──HTTPS──► API svc ──► Presence svc (Redis, TTL)    Media ──► Object storage + CDN
```

## 19.31 How to present this in 5 minutes (chat)

1. **Scope (30 s):** 1:1 and groups up to ~1,000, online and offline delivery, receipts,
   presence, multi-device. Ordering within a conversation; at-least-once + dedupe.
2. **Numbers (45 s):** 500M DAU, 230k msgs/s avg (~700k peak), 200M concurrent sockets
   → ~1,000+ gateways, 4 TB/day of messages, 6.7M heartbeats/s that must stay at the gateways.
3. **Connections (60 s):** thin WebSocket gateways, Redis registry user/device → gateway with
   TTL, reconnect with jitter and sync by sequence number.
4. **Message path (60 s):** owner assigns per-conversation seq, persist to wide-column
   `(conv_id, bucket) / seq DESC`, ack sender, deliver to online devices, push for offline.
5. **Details and failures (90 s):** watermark receipts, presence only for viewers, group cap vs
   channel model, multi-device cursors, E2EE; gateway crash, owner failover, dedupe by IDs.

## 19.32 Recap

- Both systems are "one producer, many consumers" problems; the main lever is **push (fan-out on
  write) vs pull (fan-out on read)**, chosen by audience size.
- News feed: push for normal users, pull and merge for celebrities, skip inactive users; the
  50M-follower arithmetic is why.
- Feed caches store bounded, rebuildable lists of **post IDs**, hydrated in batched calls; use
  **cursor pagination** on time-ordered IDs (offsets duplicate or skip items).
- Separate the **like record** (idempotent upsert) from the **like count** (sharded counter or
  async aggregation, eventually consistent).
- Chat: thin **WebSocket gateways** + a TTL'd **registry** of device → gateway; **persist, then
  ack**; at-least-once delivery plus ID dedupe gives effectively-once on screen.
- Order is guaranteed **per conversation** via a server-assigned **sequence number** from a single
  owner with fencing; clients detect gaps.
- Store messages in a **wide-column** table partitioned by `(conv_id, bucket)` and clustered by
  `seq DESC`.
- Receipts are **watermarks**; presence is expensive mainly because of **fan-out**, so push it only
  to viewers; large groups switch to a channel model.

## 19.33 Check yourself

1. An account with 30M followers posts 20 times a day. Why is pure fan-out on write a problem,
   and what do you do instead?

<details><summary>Answer</summary>

Each post is 30M feed inserts, 600M per day from one account, most of them to followers who will
not open the app that day. It also delays everyone else's fan-out behind it. Use the hybrid
model: do not fan out posts of accounts above a follower threshold; at read time, fetch the
recent posts of the few celebrities the reader follows from their (hot, cached) author timelines
and merge them with the precomputed feed.

</details>

2. Why do feed caches store post IDs instead of full post objects?

<details><summary>Answer</summary>

One post can appear in millions of feeds. Storing full content would multiply memory by the
fan-out factor and make edits, deletes and like-count updates require touching millions of
entries. Storing IDs keeps each entry around 8–10 bytes; the page is hydrated from a shared post
cache with a batched multi-get.

</details>

3. A viral post receives 50k likes per second. What breaks with `likes = likes + 1`, and how
   do you fix it?

<details><summary>Answer</summary>

All writers contend for one row (or one Redis key on one shard): a hot key. Fix by separating
the idempotent like record from the count, and either shard the counter into N sub-counters that
are summed on read (with the sum cached), or send like events to Kafka keyed by post ID and have
an aggregator flush one `INCRBY delta` per post per second. The count becomes eventually
consistent, which is fine for display.

</details>

4. How does the chat system know which server to send a message to for user B?

<details><summary>Answer</summary>

A connection registry (for example one Redis key per device, with a TTL) maps each of B's
devices to the gateway holding its WebSocket. Gateways write the entry on connect, refresh its TTL periodically, and
delete it on disconnect; the TTL cleans up after crashed gateways. If B has no entries, B is
offline: the message stays in storage and a push notification is sent.

</details>

5. Two members of a group send messages at the same moment. How do all members see the same order?

<details><summary>Answer</summary>

The server assigns a per-conversation sequence number from a single owner of that conversation
(routing by conv_id, with a lease and fencing plus a conditional insert to survive failover).
Clients sort by seq, not by client timestamps or arrival time, and if they see a gap they fetch
the missing range. Ordering across different conversations is not guaranteed and not needed.

</details>

6. Heartbeats arrive at 6.7M per second. Why is that not a database problem, and what part of
   presence actually is expensive?

<details><summary>Answer</summary>

Heartbeats terminate at the ~1,000 gateways (a few thousand per second each) and only state
changes or batched TTL refreshes reach the presence store. The expensive part is presence
fan-out: telling every contact whenever someone goes online or offline. Mitigate by pushing
presence only to clients currently viewing that user, debouncing short flaps, and skipping
presence for large groups.

</details>
