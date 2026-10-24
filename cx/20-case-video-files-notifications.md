# 20 — Case study: video, file sync, notifications

*Why it matters:* these three teach reusable patterns: pipelines + CDNs (video), content-addressed
storage + sync protocols (file sync), and reliable delivery through unreliable third parties.

## What the three have in common

**How it works** · match data shape to pattern; all numbers are stated **assumptions**.

```text
Large bytes, uploaded once, read many times   -> object storage + CDN    (video)
Large bytes, changed often, synced everywhere -> chunks+hashes+metadata  (file sync)
Small messages, many channels, third parties  -> queues + retries + DLQ  (notifications)
```

**Example** · a 2 GB video, a 9 MB report edited on two devices, an "order delivered" ping: rows 1-3.

## Video: requirements and numbers

**How it works** · bandwidth and storage dominate, so the numbers come first.
- **Functional:** resumable upload (~10 GB), multi-quality processing, adaptive playback, views.
- **Non-functional:** start < 2 s, degrade before stalling, 99.99% playback, originals durable.

| Quantity (500k uploads/day x 5 min @ 10 Mbps; 1B views/day x 5 min @ 2.5 Mbps) | Value |
|---|---|
| Upload | ~375 MB/video, ~190 TB/day, ~17 Gbit/s ingress |
| Stored per video | ladder ~375 MB + 2nd codec ~225 MB + original ~375 MB = ~1 GB |
| Storage growth | ~500 TB/day, ~180 PB/year before replication |
| Egress | ~94 MB/view, ~94 PB/day, ~8.7 Tbit/s avg, ~2x peak |
| Transcode compute (~20 CPU-s per video-s) | ~3B CPU-s/day, ~35,000 cores busy |

**Example** · 300 s x 2.5 Mbit/s = ~94 MB/view; x 1B = 94 PB/day = ~8.7 Tbit/s. So: a **CDN** is
mandatory, **storage tiering** matters, transcoding is a **batch compute farm**.

## Video: API, data model, architecture, one upload end to end

**How it works** · API: `POST /videos`, PUT parts, `complete` -> `202`, `status`, CDN
`master.m3u8`, view events. Metadata + renditions: sharded SQL/wide-column by `video_id`; job
state in SQL or a workflow engine; media in **object storage**; views in Redis + flush. **State
machine**: `UPLOADING -> UPLOADED -> PROCESSING -> READY` (or `FAILED`, `BLOCKED` by moderation).

```text
Creator --pre-signed multipart--> Object storage (originals) --"object created"-->
   Workflow orchestrator (DAG state) --tasks--> Task queue --> Transcode workers (CPU/GPU)
        ^------------------- results --------------------------|  write segments/manifests
Viewer --> CDN edge --miss--> regional shield --miss--> origin (object storage)
Viewer --> Video API -> metadata cache/DB;  view events -> Kafka -> aggregator -> counters
```

**Example** · follow v_88, a 2 GB phone video (2 GB x 8 / 10 Mbit/s = ~1,600 s, ~27 min).

```text
1 POST /v1/videos {size 2 GB} -> v_88, up_5, 128 part URLs (2,048 MiB / 16 MiB)
2 PUT 6 parts at a time; Wi-Fi drops after part 90, app killed
3 restart: /upload/status -> ListParts = 1..90; PUT 91..128; complete -> 202 PROCESSING
4 "object created" -> validate -> split at keyframes into 320 chunks of 5 s
5 320 x 5 renditions = 1,600 encode tasks + audio, thumbnails, moderation in parallel
6 worker dies on v_88/720p/chunk_017.mp4 -> task redelivered, same path overwritten
7 360p + 720p merged + packaged -> READY in minutes; 1080p and AV1 added later
8 viewer: GET cdn/v_88/master.m3u8 -> 360p 0.9, 720p 3.3, 1080p 5.5 Mbit/s
9 seg 0-1 @360p (fast start); measured 8 Mbit/s, buffer 12 s -> 720p (3.3 x 1.5 < 8)
10 1080p needs 5.5 x 1.5 = 8.25 > 8: stay. Tunnel: 1.2 Mbit/s, buffer drains -> 360p
```
So: bytes never touch app servers, a crash costs one part or one task, quality follows bandwidth.

## Upload: pre-signed multipart and resume

**How it works** · via app servers a 4 GB upload ties them up for minutes; a blip at 95% restarts
it. A **pre-signed URL** allows one operation (PUT this key, ~1 hour), so bytes go straight to
storage. **Multipart:** `CreateMultipartUpload -> upload_id`; PUT parts 4-8 in parallel, each
returns an **ETag**; `complete(upload_id, [(n, etag)])` assembles the object. **Resume:** client
keeps `(video_id, upload_id)`, server `ListParts`. A lifecycle rule aborts abandoned uploads
(orphan parts are billed forever); validate the file server side.

**Example** · S3 rules (parts >= 5 MiB except the last, <= 10,000 parts): 4 GB / 16 MiB = ~240
parts; 10 GB / 16 MiB = 640; a 200 GB file would need 12,800 > 10,000, so >= 21 MiB parts.

## Transcoding as a DAG

**How it works** · **transcoding** = re-encode into several **resolutions**, **bitrates**, **codecs**
(H.264 for reach; VP9/AV1/HEVC for compression), as a **DAG** in topological order (runnable at
in-degree zero). ~5-10 s chunks cut at **keyframes** (I-frames: complete images that decode on
their own) encode in parallel: an hour of serial encoding becomes minutes.

```text
validate -> split at keyframes -+-> encode chunk_i @ each rendition -> merge -+-> package
                                +-> audio, thumbnails, moderation (BLOCK?) ----+ -> READY
```

**Example** · a 1-hour video = 360 chunks x 5 renditions = 1,800 tasks. `merge_720p` starts
with in-degree 360; each finished 720p chunk decrements it; at 0 it is queued. A retry of chunk 17
rewrites `v_88/720p/chunk_017.mp4` (**idempotent**), so at-least-once delivery is harmless.
Bounded retries, then FAILED + alert; corrupt -> **DLQ**. **Publish early:** READY once 360p/720p
exist; bulk re-encodes in a low-priority lane.

> **Say it like this:** "Transcoding is an offline DAG, not a request path. I split at keyframes,
> fan out one idempotent task per chunk and rendition through a queue, then merge and package:
> minutes instead of an hour, and retries are safe because outputs have deterministic paths."

## Adaptive bitrate, CDN, views

**How it works** · bandwidth changes every second. **ABR:** several bitrates, each cut into 2-6 s
**segments**; the **player** reads the **manifest**, starts low, steps up if the **buffer** is
healthy and throughput > next rung x safety, down if it drains. **Aligned segment boundaries** make
switches glitch-free (trace step 9-10 above).

**Example** · for v_88's ~1,600 s: 2 s segments = 800 requests per rendition (fast start and
adaptation, more requests, worse compression); 6 s = ~267 (efficient, reacts slowly).

> **Black box:** **HLS** (`.m3u8` playlists, TS or fMP4 segments) and **DASH** (XML `.mpd`) are
> the formats: a master playlist pointing at per-rendition segment lists. **Use when:** any VOD
> playback. **Cost:** none to know beyond "plain HTTP files, so CDNs cache them".

**CDN:** at ~9 Tbit/s the CDN *is* the serving system: edge -> regional **shield** -> origin.
Skew: **pre-position** head content (filling ISP-hosted appliances overnight is documented); serve
the long tail from regional caches so it does not evict hits (first segments are hottest).
**Immutable** segments (long TTLs); **request collapsing** (10,000 premiere misses on `seg_00000`
-> one origin fetch); **signed URLs**; cold renditions to cheaper **storage tiers**.

**Views** = chapter-19 counters: Kafka (key video_id) -> aggregator (dedupe session, drop bots,
batched `INCRBY`) -> Redis -> DB; raw events to a lake for nightly recount and fraud. Define a
**view** (played >= N s): 5 reloads in one session count once.

## Video: failures, trade-offs, 5-minute pitch

**How it works** · failed upload -> resume + lifecycle cleanup; worker crash -> idempotent tasks,
visibility timeout; malicious file -> validation, sandboxed decoders, DLQ; premiere -> collapsing,
shield, pre-positioning; cost -> tiering, fewer cold renditions; AV1/VP9 only for popular videos.

**Example** · a worker dies mid-chunk: the visibility timeout expires and another worker redoes it.

**5-minute pitch:** (1) upload, transcode, ABR, views; (2) ~500 TB/day, ~9 Tbit/s -> CDN is the
core; (3) pre-signed multipart + ListParts; (4) DAG, keyframe chunks, idempotent tasks, publish
early; (5) HLS/DASH, 2-6 s segments, player ABR, tiered CDN, Kafka views, storage tiering.

## File sync: requirements and numbers

**How it works** · two workloads, metadata and blocks, get two separate systems.
- **Functional:** folder synced across devices, file ops, versions, sharing, offline edits.
- **Non-functional:** never lose a file, seconds to sync, delta uploads, never silently overwrite.

| Quantity (200M users, 50M DAU, 10 GB/user, 20 changes/day, ~200 KB uploaded/change) | Value |
|---|---|
| Logical storage | 2 EB (less after dedup, more after replication) |
| Commits | 1B/day, ~11,600/s avg, ~35k/s peak |
| Upload bytes | 200 TB/day, ~18.5 Gbit/s |
| Change listeners | ~50M online clients |

**Example** · 50M x 20 = 1B commits/day = ~11.6k/s small transactions vs 200 TB/day of bytes.

## File sync: API, data model, architecture, one edit end to end

**How it works** · `POST /commit` -> `{rev}`, `{need_blocks}` (nothing committed) or `409`;
`PUT/GET /blocks/{sha256}` (<= 4 MB, verified); `GET /changes?cursor=`; `GET /notify` long-polls
(~60 s). Data: **content-addressed** blocks (key = SHA-256, object storage) + **sharded SQL**
metadata: append-only `journal(namespace_id, journal_id, path, rev, blocklist)`, `file_latest`,
`block_refs`, `memberships`. SQL: commits are **atomic**, each journal **totally ordered**; sharding
by `namespace_id` keeps both single-shard. A **namespace** (user root or shared folder) is the
unit of sharing and sync.

```text
Client: file watcher -> local index DB -> chunker/hasher -> block cache
  |-- commit / changes --> Metadata svc --> Metadata DB (sharded by namespace)
  |                            +-- "ns changed" --> Kafka --> Notification svc --+
  |-- PUT/GET by hash ---> Block svc ----> Object storage (content-addressed)    |
  ^------------------------- long poll /notify ("go look") --------------------+
```

**Example** · edit page 30 of `/docs/report.pdf` (9 MB, rev 41 = [hA, hB, hC]) on a laptop.

```text
1 watcher fires; re-chunk 4 MB blocks -> [hA, hB', hC]; only hB' is new
2 POST /commit {ns_1, parent_rev 41, [hA,hB',hC]} -> {need_blocks: [hB']}
3 PUT /blocks/hB' (4 MB; server re-hashes, matches); commit again
4 one txn: file_latest.rev == 41 -> journal_id 1734 = rev 42; file_latest = 42
5 "ns_1 changed" -> Kafka -> notification svc completes the phone's held /notify
6 phone: GET /changes?cursor=1733 -> [report.pdf rev 42 [hA,hB',hC]], cursor 1734
7 phone block cache has hA, hC -> GET /blocks/hB' only (4 MB of 9)
8 write temp file -> atomic rename -> save cursor 1734
```
So: 4 MB up, 4 MB down for a 9 MB file; a colleague saving the identical file gets
`need_blocks: []` and uploads 0 bytes (**dedup**).

## Chunking, hashing, dedup

**How it works** · split files into **blocks** (fixed 4 MB is a documented choice), SHA-256 each;
a version = ordered **list of block hashes**. Buys **delta upload**, **dedup** (one installer in
1M folders stored once), **integrity** (server re-hashes), **immutability**. **Content-defined
chunking (CDC)** cuts where a rolling hash (as in Rabin-Karp) matches a pattern (low 22 bits zero
-> ~4 MB average).

**Example** · insert 100 bytes at the start of report.pdf. Fixed blocks: every boundary shifts by
100, so hA, hB, hC all change: 9 MB re-uploaded. CDC: boundaries follow content, so only the
first block changes: ~4 MB. **Cross-user dedup** has a leak: if `need_blocks` comes back empty for
a private file, someone already uploaded it, so services may scope dedup to a user or org.

## Metadata vs blocks, sync protocol, conflicts

**How it works** · metadata: small, transactional, ordered (sharded SQL; risk = hot namespaces);
blocks: huge, write-once, by key (object storage; risk = bandwidth). **Blocks before metadata**:
a crash leaves an unreferenced block (GC'd), never metadata pointing at missing data ("write data,
then publish pointer"). **Sync** client = watcher (OS events + rescans), local index, upload and
download loops; **cursor** = last `journal_id` seen (a key range scan). Polling 50M clients =
millions of empty requests/s, so **long polling**.

**Example** · laptop and phone both edit `notes.txt` from rev 41. Laptop commits -> rev 42. Phone
`commit(parent_rev=41)` -> `409` (**optimistic concurrency**, CAS on rev), so it saves "notes
(Phone's conflicted copy).txt" and downloads rev 42: no merging arbitrary files (OT/CRDTs out of
scope). "Restore rev 40" = commit rev 43 with rev 40's block list: **versions** copy no bytes.
Retention deletes journal rows; blocks go only via **GC** (refcount or mark-and-sweep) after a
grace period, since a GC bug is data loss.

> **Common mistake:** sending file changes through the notification channel. It carries only "go
> look"; journal + cursor are the truth, so a lost signal delays sync, a duplicate = empty pull.

## File sync: failures, trade-offs, 5-minute pitch

**How it works** · hot namespace -> split, rate-limit, own shards; 50M long polls -> light servers,
signal only; buggy mass delete -> server versions, detection; partial uploads -> blocks first +
GC; insertions -> CDC; dedup privacy -> scope it; mobile -> download on demand (placeholders).

**Example** · a client commits 50,000 deletes in a minute: flagged, confirmed, else restored from versions.

**5-minute pitch:** (1) sync, sharing, versions, durability; (2) 2 EB, ~11.6k commits/s, ~50M
listeners; (3) journal per namespace vs content-addressed blocks; (4) 4 MB SHA-256 blocks, dedup,
CDC; (5) blocks first, long-poll + cursor, parent_rev CAS, conflicted copies, versions + GC.

## Notifications: requirements and numbers

**How it works** · many channels, third parties that fail, and priorities that must not mix.
- **Functional:** push, SMS, email, in-app; templates; preferences; scheduling; tracking.
- **Non-functional:** OTP in seconds, bulk never delays OTP, at-least-once + dedupe, compliance.

| Quantity (200M users; per day 1B push, 100M email, 10M SMS, 300M in-app) | Value |
|---|---|
| Push | ~11.6k/s avg, campaigns ~100k+/s for minutes |
| Email / SMS | ~1.2k/s / ~115/s (SMS ~a cent or more each: OTP/critical only) |
| Tracking events (3-5 per notification) | ~5B/day, ~60k/s |
| Notification log | ~700 GB/day, ~63 TB for 90 days hot |

**Example** · 10M SMS/day x a cent = $100k/day, so SMS is for OTP/critical only, rate-limited hard.

## Notifications: API, data model, architecture, one notification end to end

**How it works** · `POST /v1/notifications` + **Idempotency-Key**: users/segment, `category`
(prefs, priority), template + params, ordered `channels`, `priority`, optional local `send_at` ->
**`202 Accepted`**. Data: versioned templates, cached prefs, device tokens, wide-column log and
inbox, Redis dedupe keys (24-72 h), time-bucketed jobs, **suppression list** (bounces, STOP).

```text
Services -> Notification API (validate, idempotency, persist) -> Kafka requests.{prio}
              +-> Scheduler (time buckets) -- at due time --^
Orchestrator: segment -> prefs/quiet hours -> channels+tokens -> caps/dedupe -> render
  -> push-critical | push-normal | push-bulk | email | sms | in-app queues
  -> workers -> APNs/FCM, email provider, SMS gateway, inbox + WebSocket
Responses/webhooks -> Tracking svc (log, token cleanup, suppression)
Failures -> retry topics (delayed) -> channel queues; after N attempts -> DLQ -> alert
```

**Example** · order o_90311 delivered to u_1842; channels [push, in_app, email].

```text
1 order-svc POST, Idempotency-Key o_90311-delivered -> SET dedupe:.. n_9f2 NX EX 86400
  -> OK, persist, 202 {n_9f2}; caller times out, retries -> NX fails -> 202 {n_9f2}
2 orchestrator: not opted out, not quiet hours, push 4th of 20/hour; render en-IN
3 in-app: inbox row + WebSocket push (user online) -> DELIVERED
4 push-normal: FCM 503 -> retry topic, not before +1 s; again 503 -> +2 s (+ jitter)
5 3rd try 200 -> sent marker (n_9f2, push, u_1842) -> SENT; collapse key o_90311
6 email: provider 202 -> SENT; webhook "delivered"; pixel open (unreliable)
7 user taps the push -> app reports OPENED -> Kafka -> notification log + OLAP
```
So: one request id, one delivery per channel, a retry that never blocked other messages.

## Channels, preferences, templates, dedupe

> **Black box:** **Channels.** Push: HTTP/2 to **APNs** / FCM API with a device token; success =
> accepted, not displayed; "unregistered" -> delete token. SMS: costly, per-country rules, late
> receipts, honor STOP. Email: SPF/DKIM/DMARC, bounces feed suppression, easy unsubscribe.
> In-app: your own inbox, most reliable. **Collapse keys** let a repeat replace the previous one.

**How it works** · **preferences** checked at **send time** (opt-outs after scheduling count):
legal/suppression -> category -> channel -> quiet hours (defer non-critical) -> frequency caps;
OTP/security may bypass marketing opt-outs, never legal ones. **Templates** versioned per locale
and channel (push ~100 chars, email HTML); render at send time (fixes reach queued messages) or
pin the version (no mid-campaign surprises): say which. **Per-user rate limits** (user + channel
+ category, Redis sliding window). **Dedupe** twice: request key (`SET .. NX`) and a delivery
"sent" marker per notification+channel+user, checked before the provider call.

**Example** · a marketing push for u_1842 at 23:30 is deferred past quiet hours, then would be
today's 4th against a cap of 3: dropped or **digested**. A worker that crashes after the provider
call but before the marker resends (not atomic); the collapse key replaces the first on screen.

> **Say it like this:** "Delivery is at-least-once end to end. I dedupe requests with the caller's
> idempotency key, record a sent marker per notification and channel, and use collapse keys so a
> rare duplicate replaces rather than adds a notification."

## Priority queues, retries, DLQ, scheduling, tracking

**How it works** · **priority isolation** = a queue per channel x priority with own workers and
quota (`push-critical`, `push-normal`, `push-bulk` throttled); Kafka cannot "jump the queue", so
priority = **separate topics**. **Retries:** 2xx -> SENT; 429/5xx/timeout -> **backoff + jitter**
(1, 2, 4 s ... capped) via a delayed **retry topic**; bad token -> delete; bad request -> DLQ;
attempts > N (5-8) -> **DLQ** + alert, fix, replay. **Failover** for SMS/email (two providers,
circuit breaker); APNs/FCM have no alternative, only backoff. **TTL** per message.

**Example** · 20:00 a 50M-user campaign fills `push-bulk` and FCM returns 429s; u_1842's OTP at
20:01 rides `push-critical` (own workers, quota) and lands in ~1 s. If FCM were down, its 10-min
TTL drops it instead of delivering a useless code at 20:15.

**Scheduling:** per-minute **time buckets**; each minute enqueue bucket "now" (+ missed ones),
mark done idempotently; split buckets by hash or elect a leader. "9 AM local" on 2026-10-11 =
03:30 UTC for India, 08:00 UTC for London (BST): ~24+ buckets smooth the load. **Tracking:**
`ACCEPTED -> QUEUED -> SENT -> DELIVERED -> OPENED/CLICKED` (or FAILED/DLQ, BOUNCED). Sent is
reliable; delivered partial; push opens and clicks (redirect service) good; email pixel opens
unreliable. Events -> Kafka -> log + OLAP.

## Notifications: failures, trade-offs, 5-minute pitch

**How it works** · 429s -> per-provider token bucket, throttled bulk; outage -> retry topic,
second provider, breaker; duplicates -> key + marker + collapse; stale tokens -> delete, refresh
on app start; spam -> caps, digests; stale opt-out cache -> short TTL + invalidation (bulk reads
legal opt-outs from the source); late OTP -> critical lane + TTL.

**Example** · SMS provider A hits 40% errors: breaker opens, traffic moves to B, A re-probed later.

**5-minute pitch:** (1) 4 channels, templates, prefs, scheduling, tracking; (2) ~11.6k push/s,
100k/s spikes, SMS costly; (3) async `202`, idempotency key, category; (4) Kafka by priority ->
orchestrator -> channel x priority queues; (5) dedupe, retry topics, DLQ, failover, TTL, tracking.

## In one minute

- Video: **pre-signed multipart** uploads; transcoding = **offline DAG** of chunk x rendition tasks.
- Playback: aligned short segments, **player picks quality**; the **CDN** is the serving system.
- File sync: **metadata journal** per namespace vs **content-addressed blocks**; blocks first.
- Sync = "go look" signal + cursor pull; **parent_rev CAS** -> conflicted copies; versions + GC.
- Notifications: at-least-once + keys/markers/collapse; **channel x priority queues**; retry, DLQ, TTL.

## Go deeper

Full chapter 20: sections 20.8 (transcoding DAG), 20.20 (chunking), 20.22 (sync), 20.34 (retries)
