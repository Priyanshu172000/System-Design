# 15 — Security basics: quick notes

How users log in, how services prove who they are, what stops one user reading another's data,
and the classic attacks every backend must block.

## 1. Authentication vs authorization

- **Authentication (authn):** who are you? *e.g.* Asha's token says `u_1842`.
- **Authorization (authz):** may you do this to this thing? *e.g.* does `o_90311` belong to her?
- No or expired token → **401**. Valid token, someone else's order → **403** (or 404 to hide it).
- Broken authorization, not broken login, is one of the most common real bugs.

## 2. Storing passwords

Assume the users table leaks one day.

- **Hash:** a one-way scramble. Store the hash, never the password.
- **Salt:** a random value per user, stored beside the hash. *e.g.* Asha and another user both
  pick `biryani123` but get different hashes, so one precomputed table can't crack both.
- A fast hash (SHA-256) + salt is still not enough: a GPU tries billions of guesses a second.
- **Slow password hash** (**Argon2id**, scrypt, bcrypt): ~100 ms per try.
  *e.g.* 1 billion guesses × 0.1 s ≈ 3 years per user.
- Add rate limits, MFA and breached-password checks.

## 3. Sessions vs tokens (JWT)

- **Session:** a random ID in a cookie maps to `{user, expiry}` in Redis, like a coat-check
  ticket. Logout is instant, but every request needs a lookup.
- Cookie flags: **HttpOnly** (JavaScript can't read it), **Secure** (HTTPS only), **SameSite**
  (not sent on most cross-site requests).
- **JWT:** a signed token the server checks **locally**, no lookup. *e.g.* payload
  `{"sub":"u_1842","aud":"orders-api","exp":...}`, valid 15 min.
- JWTs are **signed, not encrypted**: anyone can read them, so no secrets inside.
- Prefer RS256 (private key signs, public key verifies). Allow only expected algorithms, then
  check `exp`, `iss`, `aud`.
- **Revocation problem:** a stolen JWT works until it expires. Fix: short access token (15 min)
  + a **refresh token** that is swapped on each use. *e.g.* a replayed old refresh token is
  detected, the chain is revoked, Asha logs in again.

## 4. OAuth, OpenID Connect, SSO

- **OAuth 2.0:** lets an app access part of your data without your password.
  *e.g.* a fitness app gets "read your orders" (**scope**) but can't place orders.

```text
app -> browser -> auth.quickbite.com: Asha logs in, approves "read orders"
browser -> app: one-time code
app -> auth (server to server): code + verifier -> access token
app -> API: GET /v1/orders with the token
```

- **PKCE:** the app proves it started the login, so a stolen code is useless. Always use it.
- **state** parameter blocks forged logins.
- **OpenID Connect (OIDC):** adds an **ID token** that says who the user is. *e.g.* "Sign in with
  <provider>" maps its `sub` to `u_1842`. An access token is not login proof.
- **SSO:** log in once at an **identity provider**; one place for MFA and offboarding.

## 5. API keys and service-to-service

- **API key:** identifies an app, not a user. Store only its hash; send in a header, never a
  URL. *e.g.* a partner's leaked `sk_live_...` key is revoked and reissued.
- **Zero trust:** being inside the network proves nothing.
- **mTLS:** both sides show certificates. *e.g.* only `order-service` may call payment's
  `/charge`; `rider-service` gets 403.

## 6. Authorization models and IDOR

- **RBAC:** permissions per role. *e.g.* Spice Route staff are `viewer` or `manager`.
- **ABAC:** rules over attributes. *e.g.* support may refund up to ₹500, so ₹450 passes.
- **ReBAC:** rules from relationships (team owns folder, folder holds doc).
- **IDOR:** changing an ID in the URL shows someone else's data. *e.g.* `u_5003` reads Asha's
  `o_90311` because the handler only checked login. Fix:
  `WHERE order_id = ? AND user_id = ?` with user_id taken from the token.
- Random UUIDs make guessing harder, but they are **not** an access check.

## 7. Encryption and secrets

- **TLS** encrypts traffic in transit. **At rest:** disk-level, or **field-level** in the app.
  *e.g.* disk encryption protects a lost snapshot, but not `SELECT phone` on the live DB.
- **Envelope encryption:** a data key encrypts the data; a master key inside a key service
  (**KMS**) encrypts the data key.
- **Secrets** (DB passwords) live in a secrets manager, never in git, and are rotated.

## 8. Common attacks

| Attack | What happens | Fix |
|---|---|---|
| **SQL injection** | Input becomes code: `' OR '1'='1` returns every user | Parameterized queries |
| **XSS** | A review with `<script>` runs in every visitor's browser | Escape output, CSP, HttpOnly |
| **CSRF** | evil.example makes Asha's browser POST with her cookie | SameSite cookies, CSRF tokens |
| **SSRF** | "Import photo from URL" fetches `169.254.169.254` (cloud secrets) | Block internal IPs, allowlist |

- Column names (sort fields) can't be parameters, so **allowlist** them. *e.g.* `{rating, price}`.
- **Broken access control** also covers **mass assignment**: `{"role":"admin"}` copied straight
  into the user. Fix: allowlist editable fields; deny by default.

## 9. CORS

- **Same-origin policy:** a script from evil.example can send to your API but can't **read**
  the reply.
- **CORS:** headers where your server tells the browser which other sites may read.
  *e.g.* allow `https://app.quickbite.com` only.
- It is enforced **only by browsers**. *e.g.* curl with a stolen token ignores it. CORS is not
  authentication and not CSRF protection.

## 10. Validation, least privilege, DDoS, audit, PII

- **Validate input** on the server with allowlists and size limits. *e.g.* reject
  `quantity: -3` or a 5 MB body.
- **Least privilege:** each part gets only the access it needs. *e.g.* order-service's DB user
  can't drop tables, so an injected `DROP TABLE` fails.
- **DDoS:** a flood of traffic. Absorb it in layers: CDN, scrubbing, **WAF** (web firewall),
  rate limits, load shedding.
- **Audit log:** append-only record of who did what. *e.g.* a support agent revealing Asha's
  phone for `o_90311` is logged.
- **PII** (data identifying a person): collect less, mask it (`******3210`), use pseudonyms in
  analytics, honour deletion. Card numbers stay with the payment provider.

## Remember these 5

1. 401 = who are you; 403 = not allowed. Scope every query by the token's user.
2. Passwords: salt + slow hash (Argon2id), never plain SHA-256.
3. JWTs are signed, not secret: short access token + rotating refresh token.
4. Parameterized queries, output escaping, SameSite cookies, blocked internal URLs.
5. CORS is browser-only; the server must still check everything.
