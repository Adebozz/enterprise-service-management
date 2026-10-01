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

## Planned (approved design, M2 onwards)

| Concern | Design |
|---|---|
| Passwords | `DelegatingPasswordEncoder` (BCrypt; the `{id}` prefix allows a later Argon2 migration) |
| Access token | JWT (HS256), ~15 min, issued with Spring's Nimbus `JwtEncoder`, validated by the OAuth2 Resource Server `JwtDecoder`. Held **in memory** by the SPA |
| Refresh token | Opaque random value in an `HttpOnly; Secure; SameSite=Strict` cookie (path `/api/auth`). Only a SHA-256 hash is stored. Rotated on every use; **reuse revokes the whole token family** |
| Roles | One role per user with hierarchy `ADMIN > TEAM_LEAD > AGENT > REQUESTER` |
| Authorization | URL rules → `@PreAuthorize` on services → `TicketAccessPolicy` for ownership and team scope, applied **inside queries** |
| Hidden resources | Tickets outside your scope return **404** rather than 403, which prevents ID enumeration |
| Internal notes | Returned only to staff with team-scope access to the ticket; filtered server-side |
| Login | Identical error and timing for an unknown email or a wrong password |
