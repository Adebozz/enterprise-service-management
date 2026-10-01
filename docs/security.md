# Security model

## Implemented (M0)

- **Deny by default.** Only `/actuator/health/**`, `/actuator/info` and the OpenAPI/Swagger
  endpoints are public. Every other request requires authentication, and until M2 adds login, they
  all receive 401.
- **Stateless.** No HTTP session, no form login, no HTTP Basic.
- **CSRF protection is disabled for the API.** API calls authenticate with a bearer token in the
  `Authorization` header, which a browser never attaches automatically, so CSRF does not apply.
  The single cookie (refresh token, M2) will be restricted to `/api/auth`, `SameSite=Strict`, and
  protected by an `Origin` check.
- **Safe errors.** Stack traces and exception messages are never returned. Unexpected exceptions
  produce a generic 500 problem response, and full details go to the log under the request's
  correlation ID. Malformed-JSON errors don't echo parser internals.
- **Header hardening.** Spring Security's default headers are on (`X-Content-Type-Options`,
  `X-Frame-Options`, cache control).
- **Log-injection resistance.** Client-supplied `X-Request-Id` values are accepted only if they
  match `[A-Za-z0-9-]{8,64}`.
- **Minimal actuator exposure.** Health shows no component details, and env/beans/heapdump etc. are
  not exposed.
- **Secrets.** None in the repository. Local values live in the git-ignored `.env`, and production
  values are injected from AWS Secrets Manager (Phase 3).

## Implemented (M1)

- **Role hierarchy** `ADMIN > TEAM_LEAD > AGENT > REQUESTER`, generated from the `Role` enum and
  registered as a `RoleHierarchy` bean. Spring Security applies it to both URL rules and
  `@PreAuthorize`.
- **Two layers of authorization.** A URL rule makes `/api/admin/**` ADMIN-only, and every service
  method declares its own `@PreAuthorize`. A future endpoint that forgets a URL rule is still
  protected.
- **Password storage.** `DelegatingPasswordEncoder` (BCrypt). Passwords are 12–72 characters, and
  the service also rejects passwords over 72 **bytes** in UTF-8, because BCrypt silently ignores
  everything after byte 72.
- **No password data in responses.** `UserResponse` has no hash field, which is checked by test.
- **Sort allow-lists.** `?sort=passwordHash` returns 400 `INVALID_SORT_PROPERTY` instead of
  revealing the order of password hashes.
- **Search input** is always a bound parameter, and LIKE wildcards are escaped.
- **Lockout protection.** The last active admin can't be demoted or deactivated. This is serialised
  with `SELECT … FOR UPDATE`, and a concurrency test proves it.

## Implemented (M2): authentication

```mermaid
sequenceDiagram
    participant SPA as React SPA
    participant API as Spring Boot API
    participant DB as PostgreSQL
    SPA->>API: POST /api/auth/login {email, password}
    API->>DB: load user, BCrypt verify (dummy hash if unknown email)
    API->>DB: insert refresh_tokens (hash only, new family)
    API-->>SPA: 200 {accessToken (15 min)} + Set-Cookie esm_refresh (HttpOnly, Strict, /api/auth)
    SPA->>API: GET /api/... Authorization: Bearer <jwt>
    Note over API: signature (HS256 only), exp, iss checked. No DB lookup.
    SPA->>API: POST /api/auth/refresh (cookie sent automatically)
    API->>DB: SELECT ... FOR UPDATE by hash; mark used; insert successor (same family, same expiry)
    API-->>SPA: 200 {new accessToken} + rotated cookie
```

| Concern | Implementation |
|---|---|
| Access token | JWT, HS256, 15 min, claims `iss, sub (user id), jti, iat, exp, role, name`. Issued by Nimbus `JwtEncoder`; validated by the resource-server `JwtDecoder` (HS256 only, issuer and timestamps checked with the app `Clock`). The SPA keeps it **in memory only** |
| Signing key | `ESM_JWT_SECRET`, base64, at least 32 bytes. Startup **fails** if it is missing, weak or malformed |
| Refresh token | 256-bit random, base64url, in cookie `esm_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`). Only its SHA-256 hex is stored |
| Rotation | Every refresh marks the token used and issues a successor in the same family. Row lock (`FOR UPDATE`) means concurrent refreshes rotate exactly once |
| Reuse detection | A used token presented again more than 30 s later revokes the **whole family** (`REUSE_DETECTED`, logged as a `SECURITY` warning). The revocation commits even though the request fails (`noRollbackFor`). Within 30 s it is treated as a multi-tab race: rejected, not revoked |
| Session lifetime | Absolute 7 days from login; rotated tokens inherit the expiry |
| Role changes / deactivation | Take effect at the next refresh (refresh re-reads the user). Worst case: one access-token lifetime (15 min). See ADR-005 |
| Logout | Revokes the family, clears the cookie. Idempotent, needs no access token |
| Password change | Requires the current password. Revokes **all** of the user's refresh tokens (`PASSWORD_CHANGED`) in the same transaction |
| CSRF | The cookie is only used by `/api/auth/refresh` and `/logout`: `SameSite=Strict`, plus `OriginPolicy` rejects any `Origin` not in `ESM_ALLOWED_ORIGINS` |
| Account enumeration | Unknown email, wrong password and inactive account give the same 401 `INVALID_CREDENTIALS`, and unknown emails still run a BCrypt comparison (equal timing) |
| Unauthenticated / bad token | 401 ProblemDetail with `WWW-Authenticate: Bearer` |
| First admin | `ESM_BOOTSTRAP_ADMIN_*` creates one admin only if no active admin exists (idempotent, safe with multiple instances) |
| Caching | Token responses carry `Cache-Control: no-store` |

Verified by `AuthApiIT`, `JwtSecurityIT` (expired, tampered, wrong key, `alg: none`, wrong issuer, malformed, and an RBAC matrix with real tokens) and `RefreshConcurrencyIT`.

## Implemented (M3): ticket visibility

`TicketAccessPolicy` (a pure function, unit-tested case by case):

| Caller | Sees |
|---|---|
| ADMIN | every ticket |
| anyone | tickets they raised |
| AGENT / TEAM_LEAD | tickets assigned to them or to any of their teams |

Tickets outside the caller's scope return **404 `RESOURCE_NOT_FOUND`**, identical to a
non-existent id, so outsiders can't enumerate ids. Team membership counts only for staff roles.
The requester and team are always taken from the token and routing rules, never from the request
body.

## Planned

| Concern | Design |
|---|---|
| List/queue authorization | The same rules applied **inside the SQL query** (JPA Specification), not by filtering results (M7) |
| Internal notes | Returned only to staff with team-scope access to the ticket; filtered server-side (M6) |

## Known limitations (security)

- **No login rate limiting or lockout yet.** Planned at the edge (AWS WAF rate-based rule on
  `/api/auth/login`) in Phase 3; an application-level limiter is an alternative.
- **Admin-set initial passwords** aren't flagged "must change at first login".
- Expired and revoked refresh-token rows aren't purged yet (a scheduled cleanup comes with Phase 2
  scheduling).
