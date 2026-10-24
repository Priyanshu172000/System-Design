# 20 — Case study: video streaming, file sync and notifications

Three systems that move a lot of bytes or a lot of messages: a **video platform** (YouTube-like
upload, transcode and stream), a **file sync service** (Dropbox or Google-Drive-like), and a
**notification system** that delivers push, SMS, email and in-app messages. Each one teaches a
reusable pattern: video teaches pipelines and CDNs, file sync teaches content-addressed storage
and sync protocols, notifications teach reliable delivery through unreliable third parties. All
three follow the chapter-16 method.

## 20.1 What the three have in common

```text
 Large bytes, uploaded once, read many times   → object storage + CDN       (video)
 Large bytes, changed often, synced everywhere → chunks + hashes + metadata (file sync)
 Small messages, many channels, third parties  → queues + retries + DLQ     (notifications)
```

All numbers below are **assumptions**, stated so the arithmetic can drive decisions.

---

# Part A — Video streaming

## 20.2 Requirements

### Functional

- Upload a video (up to, say, 10 GB), with **resumable** uploads on flaky networks.
- Videos are processed into multiple qualities and become watchable.
- Watch a video with **adaptive quality** (it adjusts to the viewer's bandwidth).
- Video page metadata: title, description, uploader, thumbnail, **view count**.

Out of scope: comments, recommendations, live streaming, monetization.

### Non-functional

| Requirement | Target (assumption) |
|---|---|
| Playback start time | < 2 s typical |
| Rebuffering | Rare; quality should drop before playback stalls |
| Availability of playback | Very high (99.99% for the serving path) |
| Processing time | Minutes after upload is acceptable |
| Durability | Uploaded originals must never be lost |
| Cost | Bandwidth and storage dominate; must be designed for |

## 20.3 Estimation

Assumptions: 500k uploads/day, average length 5 minutes, uploads at ~10 Mbps (1080p from a
phone); 1B views/day, average 5 minutes watched at an average 2.5 Mbps.

```text
Upload ingress:   300 s × 10 Mbit/s = 3,000 Mbit ≈ 375 MB per video
                  500k × 375 MB ≈ 190 TB/day  → ≈ 17 Gbit/s average ingress

Rendition ladder (one codec, roughly):
   1080p 5 Mbps + 720p 3 + 480p 1.2 + 360p 0.7 + 240p 0.3 ≈ 10 Mbps total
   300 s × 10 Mbit/s ≈ 375 MB per codec ladder
   + a second, more efficient codec (~30–50% smaller)  ≈ +225 MB
   + original kept                                     ≈ +375 MB
   ≈ 1 GB per video  → 500k × 1 GB = 500 TB/day ≈ 180 PB/year (before replication)

Egress (playback):
   300 s × 2.5 Mbit/s = 750 Mbit ≈ 94 MB per view
   1B × 94 MB ≈ 94 PB/day → 94e15 × 8 bits / 86,400 s ≈ 8.7 Tbit/s average, ~2× at peak

Transcoding compute (rough): if a full ladder costs ~20 CPU-seconds per second of video,
   500k × 300 s × 20 = 3B CPU-seconds/day ≈ 35,000 cores busy around the clock
```

So: ~9 Tbit/s of egress makes a **CDN** mandatory, hundreds of PB per year make **storage
tiering** matter, and transcoding is a **batch compute farm**, not a web service.

## 20.4 API

```http
POST /v1/videos                       { "title": "...", "size_bytes": 3900000000 }
→ 201 { "video_id": "v_88", "upload_id": "up_5", "part_size": 16777216,
        "parts": [ { "n": 1, "url": "https://storage/...sig..." }, ... ] }

PUT  <pre-signed part URL>            (client → object storage directly, one per part)
→ 200  ETag: "abc..."

POST /v1/videos/v_88/upload/complete  { "parts": [ { "n": 1, "etag": "abc" }, ... ] }
→ 202 { "status": "PROCESSING" }

GET  /v1/videos/v_88/upload/status    → which parts are already stored (for resume)
GET  /v1/videos/v_88                  → metadata, status, thumbnail, manifest URL
GET  https://cdn.example.com/v_88/master.m3u8      (playback, via CDN)
POST /v1/videos/v_88/views            (view event; see 20.11)
```

## 20.5 Data model

| Data | Store | Why |
|---|---|---|
| Video metadata (`video_id, owner, title, status, duration, created_at`) | Sharded SQL or wide-column, key `video_id` | Small rows, point lookups, status transitions need consistency |
| Renditions (`video_id, codec, resolution, bitrate, manifest_path`) | Same DB, child rows | Listed when building the player |
| Processing job state (`video_id, task_id, state, attempts`) | SQL or a workflow engine's store | Needs transactional state transitions |
| Originals, segments, manifests, thumbnails | Object storage (S3-like) | Cheap, durable (multi-AZ erasure coding), effectively unlimited |
| View counts | Counter store (Redis) + periodic flush to DB / OLAP | Very hot, approximate is fine |

Status of a video is a small **state machine**:

```text
UPLOADING → UPLOADED → PROCESSING → READY
                           │
                           └──► FAILED (retryable)   BLOCKED (failed moderation)
```

## 20.6 High-level architecture

```text
            ┌───────────── upload (pre-signed multipart) ─────────────┐
            │                                                          ▼
 Creator ───┤                                                  ┌───────────────┐
            └──► Upload API ──► Metadata DB                    │ Object storage│
                     │ complete                                │  originals/   │
                     ▼                                         │  segments/    │
               ┌───────────┐   tasks   ┌─────────────────┐     │  manifests/   │
               │ Workflow  │──────────►│ Task queue      │     └───────▲───────┘
               │ orchestr. │◄──────────│ (Kafka/SQS-like)│             │ write outputs
               └───────────┘  results  └────────┬────────┘             │
                                                ▼                      │
                                   ┌───────────────────────┐           │
                                   │ Transcode workers     │───────────┘
                                   │ (CPU/GPU fleet)       │
                                   └───────────────────────┘

 Viewer ──► Video page API (metadata) ──► Metadata DB / cache
 Viewer ──► CDN edge ──miss──► regional cache ──miss──► origin (object storage)
```

## 20.7 Deep dive: upload with pre-signed multipart URLs (and resume)

Problem: a 4 GB upload through app servers ties them up for minutes, and a blip at 95% means
starting over. A **pre-signed URL** allows exactly one operation (PUT this key, valid ~1 hour), so
the client uploads straight to object storage and app servers never touch the bytes (chapter 09).

**Multipart upload** (S3-style, other stores are similar): the file is split into parts that are
uploaded independently, in parallel, then stitched together by a final "complete" call.

```text
1. Upload API: CreateMultipartUpload → upload_id
2. Client splits file into parts (S3 rules: each part ≥ 5 MiB except the last,
   at most 10,000 parts)  e.g. 4 GB / 16 MiB ≈ 240 parts
3. Client PUTs parts in parallel (e.g. 4–8 at a time); each returns an ETag
4. Client: complete(upload_id, [(part_no, etag)...]) → storage assembles the object
5. Storage emits "object created" → workflow starts processing
```

**Resume** after the app is killed or the network drops:

```text
Client stored (video_id, upload_id) locally.
On restart: GET /upload/status → server calls ListParts(upload_id) → parts 1..173 done
Client uploads 174..240 only → complete
```

Hygiene: a lifecycle rule aborts never-completed multipart uploads (orphaned parts are billed
forever otherwise), and the server validates the file instead of trusting the client.

## 20.8 Deep dive: the transcoding pipeline as a DAG

**Transcoding** = decoding the uploaded video and re-encoding it into the formats players need:
several **resolutions** (240p … 1080p/4K), **bitrates**, and **codecs** (H.264 for maximum device
support; VP9/AV1/HEVC for better compression on devices that support them).

The work is a **DAG** (directed acyclic graph) of tasks. DSA bridge: the orchestrator runs a
topological order; a task becomes runnable when all its parents finish (in-degree reaches zero).

```text
                      ┌──► encode chunk 1 @1080p ──┐
                      ├──► encode chunk 1 @720p  ──┤
 validate ──► split ──┼──► ...                     ├──► merge per    ──► package ──► READY
 (probe)    (~5–10 s  ├──► encode chunk N @240p  ──┘    rendition        (HLS/DASH
             chunks at├──► audio encode ──────────────────────────────►   segments +
             keyframes├──► thumbnails ────────────────────────────────►   manifests)
                      └──► moderation / captions ─────────────────────► (may BLOCK)
```

Why **chunking**: a 1-hour video encoded serially might take an hour or more per rendition. Split
it into ~5–10 s chunks at **keyframe** boundaries (a keyframe, or I-frame, is a complete image
that does not depend on earlier frames, so each chunk can be decoded on its own), and hundreds of
workers encode in parallel. 360 chunks × 5 renditions = 1,800 independent tasks; wall-clock time
drops to minutes.

Mechanics:

- The **orchestrator** (a workflow engine or a service with a state table) pushes runnable tasks
  to a **queue**; workers pull, process, write output to object storage, report done.
- Tasks are **idempotent**: deterministic output paths (`v_88/720p/chunk_017.mp4`) make a retry
  overwrite the same object, so at-least-once queues are safe.
- **Retries with limits**, then FAILED + alert; a corrupt file goes to a dead-letter queue.
- **Priorities and publish early**: bulk re-encodes in a low-priority lane; mark READY once a
  basic ladder (360p, 720p) exists and add 4K or newer codecs later.

> **Say it like this:** "Transcoding is an offline DAG, not a request path. I split the video at
> keyframes into short chunks, fan out one idempotent task per chunk and rendition through a
> queue, then merge and package. That turns an hour of serial encoding into minutes of parallel
> work, and retries are safe because outputs have deterministic paths."

## 20.9 Deep dive: adaptive bitrate streaming (HLS and DASH)

Problem: a viewer's bandwidth changes second to second (Wi-Fi to 4G to a tunnel). One fixed-quality
file either stalls on bad networks or looks bad on good ones.

**Adaptive bitrate streaming (ABR)**: encode the video at several bitrates, cut each into short
**segments** (typically 2–6 seconds), and let the **player** choose, segment by segment, which
quality to download. The two standard formats:

**HLS** (HTTP Live Streaming: `.m3u8` playlists, MPEG-TS or fragmented MP4 segments) and **DASH**
(an XML `.mpd` manifest, usually fragmented MP4). Both are plain HTTP files, so CDNs cache them.

```text
master.m3u8 (the "menu")
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3300000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
360p/index.m3u8

720p/index.m3u8 (one rendition's media playlist)
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6.0,
seg_00000.m4s
#EXTINF:6.0,
seg_00001.m4s
...
#EXT-X-ENDLIST
```

Player loop (simplified):

```text
1. GET master.m3u8 → list of renditions
2. Start at a conservative quality (fast first frame)
3. For each next segment:
     measure recent download throughput and current buffer level (seconds buffered)
     if buffer is healthy and throughput > next rung's bitrate × safety → step up
     if buffer is draining → step down
     GET <rendition>/seg_N.m4s
```

All renditions are cut at the **same segment boundaries** (aligned keyframes), so the player can
switch between qualities at any segment edge without glitches.

Trade-off: short segments adapt and start faster but cost more requests and compression; long
segments are efficient but react slowly.

## 20.10 Deep dive: CDN serving and popularity-aware caching

At ~9 Tbit/s average egress, the CDN is the serving system. A **CDN** is a network of edge
servers close to users that cache content (chapter 08).

```text
Viewer ──► Edge PoP (near user, hit ratio high for popular content)
              │ miss
              ▼
          Regional / mid-tier cache ("shield": protects origin when many edges miss)
              │ miss
              ▼
          Origin: object storage (+ origin servers that may package on the fly)
```

Video popularity is extremely skewed: a small fraction of videos produce most views, and a long
tail is watched rarely. So:

- **Head content** (new viral videos, a popular series premiere): pre-position (push) segments to
  edges ahead of demand; a well-documented approach in the industry is to fill edge appliances
  inside ISPs overnight during off-peak hours.
- **Long tail**: served on demand from regional caches or origin, so rare segments do not evict
  popular ones. First segments are hotter than later ones (people abandon videos early).
- Finished segments and manifests are **immutable** → long TTLs, no invalidation.
- **Request collapsing**: 10,000 viewers asking for a new segment at once cause one origin fetch.
- **Signed URLs or tokens** protect private or paid content.

**Storage tiering**: renditions of videos not watched in months move to colder, cheaper storage.

## 20.11 Deep dive: view counts and metadata reads

Metadata reads are cached (Redis/Memcached, invalidated on edit). View counting is a **counter at
scale** problem, the same pattern as likes in chapter 19:

```text
Player ──"view started / watched 30 s"──► Event collector ──► Kafka (key = video_id)
                                                                │
          ┌─────────────────────────────────────────────────────┤
          ▼                                                     ▼
 Stream aggregator: dedupe (same session + video          Raw events to data lake
 within a window), drop obvious bots,                      (batch recount, fraud analysis,
 batch per video per few seconds → INCRBY                  creator analytics)
          │
          ▼
 Counter cache (Redis) ──periodic──► Metadata DB
```

Displayed counts are approximate and lag by seconds; a nightly batch job can recount from the raw
log and correct drift or remove fraudulent views. Define a **view** precisely (for example
"played at least N seconds") so it cannot be inflated by reloading the page.

## 20.12 Bottlenecks, failures and trade-offs (video)

| Issue | Mitigation |
|---|---|
| Upload fails mid-way | Multipart + resume via ListParts; lifecycle cleanup of abandoned uploads |
| Transcode worker crashes | Idempotent tasks, visibility timeout on queue, re-run |
| Corrupt or malicious file | Validation step, sandboxed decoders, DLQ after N failures |
| Viral spike / premiere | CDN request collapsing, origin shield, pre-positioning |
| Storage cost explodes | Tiering, fewer renditions for cold videos, efficient codecs for hot videos |
| Codec trade-off | AV1/VP9 save bandwidth but cost far more encode CPU; use them for popular videos |

## 20.13 Final architecture (video)

```text
 Creator ──► Upload API ──► Metadata DB (status machine)
    │             │ pre-signed multipart URLs
    └──── parts ──┴──────────────────────────► Object storage (originals)
                                                     │ "object created"
                                                     ▼
                     Workflow orchestrator ──► Task queue ──► Transcode workers
                     (DAG state)           ◄── results ──┘        │
                                                                  ▼
                                                 Object storage (segments, manifests, thumbs)
                                                                  │
 Viewer ──► CDN edge ──► regional shield ─────────────────────────┘
    │
    ├──► Video API ──► metadata cache ──► Metadata DB
    └──► view events ──► Kafka ──► stream aggregator ──► counters ──► DB / OLAP
```

## 20.14 How to present this in 5 minutes (video)

1. **Scope:** upload (resumable), transcode, adaptive playback, metadata and view counts.
2. **Numbers:** ~1 GB stored per video → ~500 TB/day; ~9 Tbit/s egress → CDN is the core.
3. **Upload:** pre-signed multipart straight to object storage; resume with ListParts.
4. **Processing:** DAG on a queue, chunked at keyframes, idempotent tasks, publish early.
5. **Playback and counts:** HLS/DASH + 2–6 s segments, player-side ABR, tiered CDN with
   pre-positioning; views via Kafka + aggregation; storage tiering.

---

# Part B — File sync (Dropbox / Google Drive style)

## 20.15 Requirements

### Functional

- Users have a folder on each device; any change on one device appears on the others.
- Upload, download, rename, move, delete files and folders.
- **Version history** (restore a previous version for some retention period).
- Share a folder with other users (they see changes too).
- Handle **offline edits** and **conflicts** sensibly.

### Non-functional

| Requirement | Target (assumption) |
|---|---|
| Durability | Never lose a committed file (this is the product) |
| Sync latency | A small change visible on other online devices within seconds |
| Bandwidth efficiency | Editing 1 byte of a 1 GB file must not re-upload 1 GB |
| Correctness | Never silently overwrite someone's edit |
| Scale | Hundreds of millions of users, exabytes of data |

## 20.16 Estimation

Assumptions: 200M users, 50M DAU, average 10 GB stored per user, each DAU changes 20 files a day,
average bytes actually uploaded per change ~200 KB (after delta and dedup).

```text
Logical storage:  200M × 10 GB = 2 EB (before dedup; physical storage is less after dedup,
                  more after replication / erasure coding)
File changes:     50M × 20 = 1B/day ≈ 11,600 commits/s avg, peak ~3× ≈ 35k/s
Upload bytes:     1B × 200 KB = 200 TB/day ≈ 18.5 Gbit/s average
Change listeners: ~50M online clients waiting for "something changed" signals
```

Two very different workloads appear, **metadata** (small transactional updates, 11.6k/s) and
**blocks** (big immutable bytes), and they get separate systems.

## 20.17 API

```http
POST /v1/commit
{ "namespace_id": "ns_1", "path": "/docs/report.pdf",
  "parent_rev": 41, "size": 9437184,
  "blocks": ["sha256:aa..", "sha256:bb..", "sha256:cc.."] }
→ 200 { "rev": 42 }                                  (all blocks already on server)
→ 200 { "need_blocks": ["sha256:cc.."] }             (nothing committed; upload, retry)
→ 409 { "conflict": true, "server_rev": 43 }        (someone else changed it first)

PUT  /v1/blocks/{sha256}         (raw bytes, ≤ 4 MB; server verifies the hash)
GET  /v1/blocks/{sha256}

GET  /v1/changes?namespace_id=ns_1&cursor=<c>  → { "entries": [...], "cursor": "<c2>",
                                                    "has_more": false }
GET  /v1/notify?cursor=<c>       (long poll: returns when something changed, or after ~60 s)
GET  /v1/files/{id}/revisions    → version history
```

## 20.18 Data model

```text
Block store (content-addressed, immutable):
  key = SHA-256(block bytes)  →  value = up to 4 MB of bytes      (object storage)

Metadata (sharded SQL; strong consistency per namespace):
  namespaces      (namespace_id, owner, ...)              one per user root or shared folder
  journal         (namespace_id, journal_id, path, rev, blocklist, size, deleted, mtime)
                  PRIMARY KEY (namespace_id, journal_id)  ← append-only change log
  file_latest     (namespace_id, path) → latest rev, blocklist   (fast "current state")
  block_refs      (block_hash) → refcount / or computed by GC
  memberships     (user_id, namespace_id, mount_path, permission)
```

Why SQL for metadata: commits must be **atomic** ("new rev only if parent_rev is still current")
and each namespace's journal **totally ordered**; sharding by `namespace_id` keeps both
single-shard. Why object storage for blocks: immutable, huge, cheap, and the hash is the key.

A **namespace** is the unit of sharing and sync: a user's root folder is one namespace; a shared
folder is another namespace mounted into several users' trees.

## 20.19 High-level architecture

```text
 ┌─────────── Desktop / mobile client ────────────┐
 │ File watcher → local index DB → chunker/hasher │
 └───┬──────────────────────┬───────────────▲─────┘
     │ commit / changes     │ blocks        │ "something changed" (long poll)
     ▼                      ▼               │
 ┌──────────────┐    ┌──────────────┐  ┌────┴─────────────┐
 │ Metadata svc │    │ Block svc    │  │ Notification svc │
 │ (commit,     │    │ (put/get by  │  │ (holds 50M       │
 │  journal,    │    │  hash, verify│  │  long polls)     │
 │  conflicts)  │    │  hash)       │  └────▲─────────────┘
 └──┬────────┬──┘    └──────┬───────┘       │
    ▼        │              ▼               │
 Metadata DB │         Object storage       │
 (sharded by │         (blocks)             │
 namespace)  └── "ns changed" ──► Kafka ────┘
```

## 20.20 Deep dive: chunking, content hashing and dedup

Split each file into **blocks** (a common, well-documented choice is fixed 4 MB blocks) and hash
each block with SHA-256. A file version is then just an ordered **list of block hashes**.

```text
report.pdf (9 MB) → [ blk A (4 MB) | blk B (4 MB) | blk C (1 MB) ]
version 41 = [hA, hB, hC]

Edit page 30 (inside block B) → new block B'
version 42 = [hA, hB', hC]   → upload only B' (4 MB), not 9 MB
```

What hashing buys:

- **Delta upload**: only blocks the server lacks are uploaded.
- **Deduplication**: identical blocks (one installer in 1M folders) are stored once; the store is
  **content-addressed** (the key is derived from the content).
- **Integrity**: the server recomputes the hash on upload and rejects mismatches.
- **Immutability**: a change is a new block with a new hash, so caching and replication are easy.

Fixed-size blocks have a weakness: inserting one byte at the start of a file **shifts every
block boundary**, so every block hash changes. **Content-defined chunking** fixes this: compute a
rolling hash over a sliding window (Rabin fingerprint style) and cut a block wherever the hash
matches a pattern (e.g. low 22 bits are zero → average ~4 MB blocks). Boundaries then depend on
content, and an insertion only changes the blocks near it. DSA bridge: it is the same rolling
hash idea as Rabin-Karp string matching.

Dedup trade-off: **cross-user dedup** saves a lot of storage but leaks information ("the server
already had this block" reveals that someone uploaded that exact file). Services may limit dedup
to one user's or organization's data. Mentioning it is a good signal.

> **Say it like this:** "A file is a list of SHA-256 block hashes. The block store is
> content-addressed and immutable, so I upload only blocks the server lacks, which gives delta
> sync and dedup for free. Content-defined chunking keeps an insertion from shifting every block."

## 20.21 Deep dive: metadata service vs block storage

Separating them is the key architectural move:

| | Metadata service | Block storage |
|---|---|---|
| Data | Paths, revisions, block lists, sharing | Raw 4 MB blobs |
| Size | Small (bytes to KB per file) | Huge (EB) |
| Access | Transactional, ordered, frequent | Write-once, read-by-key |
| Store | Sharded SQL | Object storage |
| Scaling problem | Hot namespaces, commit contention | Bandwidth and capacity |

**Commit protocol** (upload side), in the order that keeps things safe:

```text
1. Client hashes blocks of the changed file.
2. Client: commit(path, parent_rev=41, blocklist)
3. Server: which hashes are missing? → need_blocks [hB'] (nothing committed yet)
4. Client: PUT /blocks/hB'   (server verifies hash, stores durably)
5. Client: commit(...) again → server checks every block exists, then in ONE transaction:
      if file_latest.rev == 41: append journal row (rev 42), update file_latest
      else: conflict
6. Server publishes "namespace ns_1 changed" → notification service
```

Blocks are written **before** the metadata that references them, so a crash in between leaves at
worst an unreferenced block (garbage, collected later), never metadata pointing at missing data.
This "write data, then publish pointer" ordering is a general pattern.

## 20.22 Deep dive: the sync protocol

Client side:

```text
File watcher   — OS notifications (inotify on Linux, FSEvents on macOS,
                 ReadDirectoryChangesW on Windows) plus periodic rescans as a safety net
Local index DB — for each path: last synced rev, block list, size, mtime
Upload loop    — changed file → re-chunk → hash → commit protocol above
Download loop  — wait for notification → GET /changes?cursor → apply entries:
                   fetch missing blocks (often from the local cache of blocks the
                   client already has) → write to a temp file → atomic rename
                 → store new cursor
```

The **cursor** is a position in the namespace's journal (essentially the last `journal_id` seen).
"What changed since cursor 1,000?" is a range scan on the journal's primary key. A device that was
offline for a month just pages through the journal from its old cursor (or, if the journal was
compacted, does a full re-listing).

How the client learns something changed:

- **Polling** every few seconds × 50M clients = millions of mostly empty requests/s.
- **Long polling** (chapter 02): `/notify?cursor=c` is held open until something changes or ~60 s
  pass, then the client re-polls; changes arrive within about a second. (WebSockets also work;
  long polling is simpler through proxies and firewalls.)

The notification service only says "something changed"; the client then pulls the actual
changes from the metadata service. Keeping the signal tiny means it can be lossy: a dropped signal
just delays sync until the next poll or timeout.

> **Common mistake:** Sending the file changes themselves through the notification channel.
> Notifications should carry no data, only "go look". The journal plus cursor is the source of
> truth, so lost or duplicated notifications never corrupt state.

## 20.23 Deep dive: conflicts and versioning

**Conflict**: laptop and phone both edit `notes.txt` (offline or at the same moment) starting from
rev 41.

```text
Laptop: commit(parent_rev=41) → success, rev 42
Phone:  commit(parent_rev=41) → 409 conflict (current rev is 42)
Phone:  saves its version as "notes (Phone's conflicted copy 2026-10-10).txt",
        downloads rev 42 as notes.txt
```

This is **optimistic concurrency control** (compare-and-set on `rev`, chapter 05). The service
cannot merge arbitrary files (a spreadsheet, an image), so a **conflicted copy** keeps both and the
human decides. Real-time co-editing (operational transformation, CRDTs) is out of scope.

**Versioning** comes almost for free: every commit is a new journal row with its own block list.

```text
journal: rev 40 [hA,hB,hC]   rev 41 [hA,hB2,hC]   rev 42 [hA,hB3,hC]
restore rev 40 = new commit rev 43 with block list [hA,hB,hC]  (no bytes copied!)
```

Retention (say 30 or 180 days) is enforced by deleting old journal rows; blocks are only deleted
by **garbage collection** when no remaining version references them (reference counting, or
periodic mark-and-sweep over all live block lists). Deletes go through a grace period because a
GC bug that deletes a live block is a data-loss incident.

## 20.24 Bottlenecks, failures and trade-offs (file sync)

| Issue | Mitigation |
|---|---|
| Huge shared namespace (company-wide folder) is a hot shard | Split namespaces, rate-limit, dedicated shards |
| 50M long polls | Many lightweight connection servers; signal only, no data |
| Client bugs could delete everything everywhere | Server-side version history, mass-delete detection and confirmation |
| Partial uploads | Blocks before metadata; GC unreferenced blocks |
| Fixed blocks and insertions | Content-defined chunking |
| Dedup privacy | Scope dedup to user/org, or accept with care |
| Mobile clients | Download on demand (placeholders), not full sync |

## 20.25 Final architecture (file sync)

```text
 Client (watcher, local index, chunker, block cache)
   │  commit / changes (HTTPS)        │ PUT/GET blocks by hash         ▲ long poll /notify
   ▼                                  ▼                                │
 API gateway ──► Metadata svc ──► Metadata DB (sharded by namespace:   │
                    │              journal + file_latest + memberships)│
                    │ "ns changed" ──► Kafka ──► Notification svc ─────┘
                    │
                    └──► Block svc ──► Object storage (content-addressed, erasure coded)
                                          ▲
                         Version GC / retention jobs ──┘
```

## 20.26 How to present this in 5 minutes (file sync)

1. **Scope:** multi-device sync, sharing, versions, conflicts; durability above all.
2. **Numbers:** 2 EB logical, ~11.6k commits/s, ~50M listening clients.
3. **Key split:** metadata (sharded SQL, transactional journal per namespace) vs blocks (content
   addressed object storage).
4. **Chunking:** 4 MB blocks, SHA-256, upload only missing blocks; dedup; CDC for insertions.
5. **Sync, conflicts, versions:** blocks first, then metadata; long-poll "go look" → pull by
   cursor; parent_rev compare-and-set with conflicted copies; versions are block lists + GC.

---

# Part C — Notification system

## 20.27 Requirements

### Functional

- Internal services send notifications to users through **push** (iOS via APNs, Android via FCM),
  **SMS**, **email**, and **in-app** (a notification inbox inside the app).
- Support **templates** with parameters and localization.
- Respect **user preferences** and opt-outs per channel and category (e.g. "no marketing email").
- **Scheduling** ("send at 9 AM user's local time") and bulk campaigns.
- **Track** status: sent, delivered (when the channel reports it), opened, clicked.

### Non-functional

| Requirement | Target (assumption) |
|---|---|
| Latency for critical messages (OTP codes, security alerts) | Seconds, end to end |
| Marketing/bulk | Minutes to hours is fine |
| Delivery semantics | At-least-once with dedupe; never spam duplicates |
| Isolation | A marketing blast must never delay an OTP |
| Compliance | Opt-outs honored, quiet hours, unsubscribe links |
| Availability | High; but third-party providers will fail, so retries are first-class |

## 20.28 Estimation

Assumptions: 200M users. Per day: 1B push, 100M email, 10M SMS, 300M in-app.

```text
Push:   1B / 86,400 ≈ 11.6k/s avg; campaigns spike to ~100k+/s for minutes
Email:  100M / 86,400 ≈ 1.2k/s avg
SMS:    10M / 86,400 ≈ 115/s avg — but cost: at roughly a cent or more per message,
        10M/day is a large bill → SMS is reserved for OTP/critical, rate-limited hard
Status events: each notification produces ~3–5 tracking events → ~5B events/day ≈ 60k/s
Notification log: 1.4B/day × ~500 B ≈ 700 GB/day (keep ~90 days hot ≈ 63 TB)
```

## 20.29 API

```http
POST /v1/notifications
Idempotency-Key: order-123-shipped              (caller's dedupe key)
{
  "user_ids": ["u_1"],                         // or "segment_id": "seg_inactive_7d"
  "category": "order_updates",                 // maps to preferences and priority
  "template_id": "order_shipped_v3",
  "params": { "order_id": "123", "eta": "Friday" },
  "channels": ["push", "in_app", "email"],     // allowed channels, in preference order
  "priority": "high",                          // critical | high | normal | low
  "send_at": "2026-10-11T09:00:00",            // optional
  "send_at_timezone": "user_local"             // optional
}
→ 202 { "notification_id": "n_9f2", "status": "ACCEPTED" }

GET  /v1/notifications/n_9f2           → per-channel status
GET  /v1/users/u_1/preferences         PUT /v1/users/u_1/preferences
GET  /v1/users/u_1/inbox?cursor=...    (in-app notifications)
```

`202 Accepted` is the honest response: the system has durably accepted the request; delivery
happens asynchronously.

## 20.30 Data model

| Data | Store | Notes |
|---|---|---|
| Templates (`template_id, version, channel, locale, body`) | SQL + cache | Versioned; rendered per user locale |
| Preferences (`user_id, category, channel, enabled`), quiet hours, timezone | SQL or KV + cache | Read on every send → cache aggressively |
| Device tokens (`user_id, device_id, platform, token, updated_at`) | KV/wide-column by user_id | Delete on "unregistered" responses |
| Notification log (`notification_id, user_id, channel, status, timestamps`) | Wide-column partitioned by user_id / time | Write-heavy, tracking |
| Dedupe keys (`idempotency_key → notification_id`) | Redis with TTL (e.g. 24–72 h) + DB | Prevents duplicate sends |
| Scheduled jobs (`send_at_bucket, notification_id`) | SQL/wide-column indexed by time bucket | Scanned by scheduler |
| In-app inbox (`user_id, created_at DESC → notification`) | Wide-column by user_id | Paginated by cursor |
| Suppression list (bounced emails, STOP'd numbers) | KV | Checked before sending |

## 20.31 High-level architecture

```text
 Services ──► Notification API ──► validate, idempotency check, persist ──► Kafka "requests"
 (orders,          │                                                         (by priority)
  auth, mktg)      └─► Scheduler (send_at in future) ──── at due time ────────────┘
                                                                               │
                                                                               ▼
                    ┌───────────────────── Orchestrator workers ───────────────────┐
                    │ 1 expand segment → users     4 per-user rate limit / dedupe  │
                    │ 2 preferences, opt-outs,     5 render template (locale)      │
                    │   quiet hours                6 route to channel queue        │
                    │ 3 pick channels, tokens                                      │
                    └──────┬──────────────┬──────────────┬─────────────┬───────────┘
                           ▼              ▼              ▼             ▼
                    push-critical    push-normal      email          sms         in-app
                    queue            queue            queue          queue       queue
                           │              │              │             │           │
                           ▼              ▼              ▼             ▼           ▼
                       Push workers ──► APNs / FCM   Email workers  SMS workers  Inbox writer
                                                     ──► email       ──► SMS     + WebSocket
                                                     provider        gateway     if online
                           │ responses/webhooks (delivered, bounced, invalid token)
                           ▼
                    Tracking svc ──► notification log, analytics, token cleanup, suppression
                    Failed after N retries ──► DLQ ──► alert / manual replay
```

## 20.32 Deep dive: channels and their quirks

| Channel | How it works | Quirks |
|---|---|---|
| Push (iOS) | Server sends HTTP/2 request to **APNs** with the device token | A success response means APNs accepted it, not that the phone displayed it; invalid tokens return an error (e.g. "unregistered") → delete token |
| Push (Android) | Server calls **FCM** HTTP API with registration token | Similar; "unregistered" → delete; priority affects whether a sleeping device is woken |
| SMS | Through SMS gateway providers / aggregators | Costly per message, per-country rules, sender IDs, delivery receipts are delayed and not always reliable, "STOP" opt-outs must be honored |
| Email | SMTP or an email delivery provider's API | Deliverability needs SPF/DKIM/DMARC; bounces and spam complaints feed the suppression list; bulk mail needs an easy unsubscribe |
| In-app | Write to the user's inbox; if the user is online, push over the app's WebSocket | Fully under your control; the most reliable channel |

Both APNs and FCM support **collapse keys** (a notification with the same collapse ID replaces
the previous one on the device), which limits visible duplicates when retries happen.

## 20.33 Deep dive: preferences, templates, rate limiting and dedupe

**Preferences and opt-outs** are checked in the orchestrator, at send time (not at request time:
the user may have opted out between scheduling and sending). Order of checks:

```text
legal/suppression (unsubscribed, STOP, hard bounce) → category opt-out → channel opt-out
→ quiet hours (defer non-critical to the end of quiet hours) → frequency caps
```

Critical categories (security alerts, OTP) may bypass marketing opt-outs but never legal ones.

**Templates**: stored with version and locale; rendered with the params; channel-specific
variants (push has ~100 characters of useful text, email has HTML). Rendering at send time lets
a template fix apply to queued messages; pinning the template version in the request avoids
surprises mid-campaign. Pick one and say which.

**Rate limiting per user** (chapter 12, chapter 18): protects users from spam caused by bugs or
overlapping campaigns.

```text
key = ratelimit:{user_id}:{channel}:{category}
e.g. marketing push: max 3 per day; any push: max 20 per hour
Implement with a sliding-window counter in Redis; over limit → drop (marketing) or
digest ("You have 5 new updates") rather than send.
```

**Dedupe and idempotency** at two levels:

1. **Request level**: `Idempotency-Key` from the caller. The API does `SET dedupe:{key}
   n_9f2 NX EX 86400`; if the key exists, return the existing notification ID. A retried
   "order shipped" call does not notify twice.
2. **Delivery level**: queues are at-least-once, so a worker may process the same message twice
   (crash after sending, before committing the offset). Keep a per `(notification_id, channel,
   user)` "sent" marker and check it before calling the provider. This shrinks the duplicate
   window but cannot eliminate it, because the provider call and the marker write are not
   atomic; collapse keys make the rare duplicate invisible.

> **Say it like this:** "Delivery is at-least-once end to end. I dedupe requests with the
> caller's idempotency key, record a sent marker per notification and channel before acking the
> queue, and use collapse keys so the rare duplicate replaces rather than adds a notification."

## 20.34 Deep dive: priority queues, retries and the DLQ

**Priority isolation**: one queue per channel per priority, each with its own consumer pool and
quota.

```text
push-critical   (OTP, security)     ← dedicated workers, never shared
push-normal     (social, orders)
push-bulk       (marketing)          ← throttled to protect provider quotas
```

A 50M-user campaign only fills `push-bulk`; OTPs keep flowing. A Kafka partition has no "jump
the queue", so priority means **separate topics/queues**, not a field on the message.

**Retries**:

```text
Provider response         Action
2xx                       mark SENT
429 / 5xx / timeout       retry, exponential backoff + jitter (1 s, 2 s, 4 s ... up to a cap)
4xx bad token             delete token, do not retry
4xx bad request           do not retry → DLQ (bug in template or payload)
attempts > N (e.g. 5–8)   → DLQ, alert
```

Retries must not block the main queue: failed messages go to a **retry topic** (or delayed
queue) with a "not before" time, so healthy messages behind them keep flowing. A **dead-letter
queue (DLQ)** holds messages that will not succeed by retrying; operators inspect, fix, and
replay them (chapter 11).

**Provider failover**: for SMS and email, keep two providers and switch on elevated error rate
(circuit breaker, chapter 13). For APNs/FCM there is no alternative provider; you can only back
off and retry.

Also: **time-to-live**. An OTP that arrives 10 minutes late is useless and confusing; set an
expiry per message and drop it instead of retrying forever. APNs and FCM accept an expiration
too.

## 20.35 Deep dive: scheduling and tracking

**Scheduling**: store scheduled notifications by **time bucket** (e.g. per minute):

```text
scheduled (bucket = '2026-10-11T09:00', notification_id, ...)
Scheduler: every minute, read bucket 'now' (plus any missed ones), enqueue, mark done.
"9 AM local time" for 200M users → spread across ~24+ timezone buckets automatically, which
also smooths load.
```

Multiple scheduler instances split buckets by hash (or use leader election, chapter 10); marking a
row done must be idempotent because a scheduler can crash after enqueueing.

**Tracking** is a status machine per notification and channel:

```text
ACCEPTED → QUEUED → SENT (provider accepted) → DELIVERED (if channel reports it)
                     │                         → OPENED / CLICKED
                     └→ FAILED / DLQ           → BOUNCED / UNSUBSCRIBED (email)
```

| Signal | Source | Reliability |
|---|---|---|
| Sent | Provider API response | Reliable |
| Delivered | SMS delivery receipts, email provider webhooks; push is limited | Partial |
| Opened (push) | App reports when the user taps the notification | Good |
| Opened (email) | Tracking pixel | Unreliable: image blocking and privacy proxies that prefetch images |
| Clicked | Links go through a redirect service that logs then redirects | Good |

Events flow to Kafka → stream jobs update the notification log and aggregate to OLAP for
dashboards (send volume, open rate by template, failure rate by provider).

## 20.36 Bottlenecks, failures and trade-offs (notifications)

| Issue | Mitigation |
|---|---|
| Campaign floods providers (429s) | Per-provider token bucket on workers; bulk queue throttled |
| Provider outage | Retry topic with backoff, secondary provider for SMS/email, circuit breaker |
| Duplicate sends | Idempotency key, sent marker, collapse keys |
| Stale device tokens | Delete on unregistered responses; refresh token on app start |
| User spam from overlapping campaigns | Per-user caps, digests |
| Preference cache stale after opt-out | Short TTL + invalidation on update; legal opt-outs read from the source of truth for bulk sends |
| Late OTP | Separate critical lane, message TTL |

## 20.37 Final architecture (notifications)

```text
 Producers ──► Notification API ──► idempotency (Redis) ──► request log (DB)
                  │                                           │
                  ├──► Scheduler (time buckets) ──────────────┤
                  ▼                                           ▼
             Kafka: requests.{critical,high,normal,bulk} ──► Orchestrator
                                                             │ prefs, opt-outs, quiet hours,
                                                             │ per-user rate limit, render
                     ┌──────────────┬──────────────┬─────────┴───┬────────────┐
                     ▼              ▼              ▼             ▼            ▼
               push-critical   push-normal/bulk  email         sms          in-app
                     │              │              │             │            │
                Push workers (token buckets)   Email workers  SMS workers  Inbox DB
                     │                            │             │          + live socket
                 APNs / FCM                  Email provider  SMS providers
                     │                            │             │
                     └──── responses / webhooks ──┴─────────────┘
                                       ▼
                     Tracking svc ──► notification log, token cleanup, suppression list
                     Retry topics (delayed) ──► back to channel queues;  DLQ ──► alerts
```

## 20.38 How to present this in 5 minutes (notifications)

1. **Scope:** push/SMS/email/in-app, templates, preferences, scheduling, tracking.
2. **Numbers:** ~11.6k push/s average with 100k/s campaign spikes; SMS is expensive.
3. **API:** async `202`, caller idempotency key, category drives preferences and priority.
4. **Pipeline:** API → Kafka by priority → orchestrator (prefs, caps, render) → per-channel,
   per-priority queues → workers → providers.
5. **Reliability:** at-least-once + dedupe, retries with backoff in a retry topic, DLQ, provider
   failover, message TTL.
6. **Tracking:** status machine from provider responses, webhooks, app opens and click redirects.

## 20.39 Recap

- Video: **pre-signed multipart** uploads straight to object storage (resume via listing parts);
  transcoding is an **offline DAG** of idempotent chunk × rendition tasks on a queue.
- Playback uses **HLS/DASH**: a manifest of renditions, short aligned segments, and a player that
  picks the quality per segment.
- Video egress (Tbit/s) makes the **CDN** the serving system: tiered caches, pre-positioning for hot
  content, immutable segments with long TTLs, request collapsing.
- File sync splits **metadata** (transactional journal per namespace) from **blocks**
  (content-addressed, immutable); upload only missing blocks, **blocks before metadata**.
- Sync uses a tiny **"go look" signal** (long poll) and pulls changes by cursor; conflicts use
  parent-revision compare-and-set and **conflicted copies**; versions are block lists plus GC.
- Notifications are **async and at-least-once**: idempotency keys, sent markers, collapse keys.
- **Separate queues per channel and priority** so bulk never delays critical; retries go to delayed
  retry topics, poison messages to a **DLQ**.
- Check **preferences, opt-outs, quiet hours and per-user caps at send time**; track status with
  provider responses, webhooks, app opens and click redirects.

## 20.40 Check yourself

1. Why should video uploads not pass through your application servers, and how does resume work?

<details><summary>Answer</summary>

Multi-GB uploads would tie up app server connections and bandwidth for minutes and make app
servers stateful. Instead the API issues pre-signed URLs and the client uploads parts directly to
object storage using multipart upload. The client remembers the upload ID; after an interruption
it asks which parts are already stored (ListParts) and uploads only the rest, then calls complete.

</details>

2. Why split a video into chunks before transcoding, and what must the chunk boundaries be?

<details><summary>Answer</summary>

Encoding a long video serially takes a long time per rendition. Splitting into short chunks lets
hundreds of workers encode chunk × rendition tasks in parallel, then merge. Boundaries must be at
keyframes (I-frames) so each chunk decodes independently, and renditions should share aligned
segment boundaries so the player can switch quality at any segment edge.

</details>

3. Explain how adaptive bitrate streaming decides the quality.

<details><summary>Answer</summary>

The server publishes a master manifest listing renditions with their bandwidth, and per-rendition
playlists of short segments. The player (not the server) measures recent download throughput and
its buffer level and, for each next segment, chooses the highest rendition it can sustain with a
safety margin, stepping down when the buffer drains. Everything is plain HTTP files, so CDNs cache
it.

</details>

4. In the file sync design, why are blocks uploaded before the metadata commit?

<details><summary>Answer</summary>

So metadata never points at data that does not exist. If the client crashes after uploading
blocks but before committing, the only damage is unreferenced blocks, which garbage collection
removes later. The reverse order could produce a committed version whose blocks are missing,
which is data loss from the user's point of view.

</details>

5. Two devices edit the same file offline from revision 41. What happens when both come online?

<details><summary>Answer</summary>

Each commit includes parent_rev = 41. The first commit succeeds and creates rev 42. The second
fails the compare-and-set (current rev is 42, not 41) and gets a conflict. The second client
keeps its version as a "conflicted copy" file and downloads rev 42 under the original name. No
data is lost and a human resolves it.

</details>

6. Why does the file sync notification channel carry only "something changed" and no data?

<details><summary>Answer</summary>

So it can be cheap and lossy. The journal plus each client's cursor is the source of truth: the
client pulls all changes since its cursor. A lost notification only delays sync until the next
long-poll timeout, and a duplicate notification causes a harmless empty pull. Carrying data would
require reliable, ordered delivery over the notification channel.

</details>

7. A marketing campaign to 50M users starts and OTP codes begin arriving late. What design
   mistake caused this and how do you fix it?

<details><summary>Answer</summary>

Critical and bulk notifications shared a queue and worker pool (and possibly provider quota), so
OTPs waited behind millions of marketing messages. Fix with separate queues per priority and
channel, dedicated workers for the critical lane, throttling (token bucket) on the bulk lane, and
a TTL on OTP messages so a late one is dropped rather than delivered stale.

</details>

8. Can you guarantee a push notification is delivered exactly once? What do you do instead?

<details><summary>Answer</summary>

No. Queues are at-least-once, and the provider call and your "sent" record cannot be one atomic
operation, so a crash between them can cause a resend; providers also only confirm acceptance.
Instead: dedupe requests with the caller's idempotency key, check a per-notification sent marker
before sending, and use APNs/FCM collapse IDs so a rare duplicate replaces the earlier one on the
device.

</details>
