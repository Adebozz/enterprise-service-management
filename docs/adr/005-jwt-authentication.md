# ADR-005: Stateless JWT access tokens with rotating refresh tokens

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

A React SPA calls a REST API. The backend must scale horizontally on ECS (several identical tasks
behind a load balancer), support logout and revocation, and resist the usual browser attacks (XSS
token theft, CSRF, stolen-token replay).

## Decision

- **Access token:** a short-lived (15 min) JWT signed with HS256, issued and validated by Spring
  Security's Nimbus `JwtEncoder`/`JwtDecoder` (OAuth2 Resource Server support). It is validated
  statelessly, with no database lookup per request. The SPA holds it **in memory only**.
- **Refresh token:** an opaque 256-bit random value in an `HttpOnly; Secure; SameSite=Strict`
  cookie scoped to `/api/auth`. Only its SHA-256 hash is stored. It is **rotated on every use**,
  with family-wide **reuse detection** and a 30 s grace window for concurrent tabs. Sessions have
  an absolute 7-day lifetime.
- **HS256** (one shared secret) because the same application issues and verifies tokens.

## Alternatives considered

- **Server-side sessions (`JSESSIONID`).** Simple revocation, but they need sticky sessions or a
  shared session store (Redis) to scale across tasks, plus CSRF tokens for every state-changing
  request.
- **Long-lived JWT in `localStorage`.** Any XSS can steal it, and it can't be revoked before
  expiry.
- **Refresh token in `localStorage` or the response body.** Readable by XSS. The HttpOnly cookie
  keeps it out of JavaScript entirely.
- **RS256 / external identity provider (Cognito, Keycloak).** Asymmetric keys matter when other
  services verify our tokens, or when SSO and MFA are required. That's a likely future step for a
  real organisation (Cognito with SSO), but it's not needed for a single API. Switching means
  replacing the `JwtDecoder` (e.g. `NimbusJwtDecoder.withJwkSetUri(...)`) and mapping claims in
  `CurrentUserJwtConverter`; business code using `CurrentUser` is unaffected.

## Consequences

- **Revocation lag.** A deactivated or demoted user keeps their current access token until it
  expires (at most 15 min); refresh re-reads the user and applies the change. Closing this gap
  would mean a per-request database or cache check, a cost we choose not to pay now.
- **The secret is critical.** It is loaded from `ESM_JWT_SECRET` (Secrets Manager in AWS), and the
  app refuses to start if it is weak. Rotating it invalidates all access tokens (users re-
  authenticate via refresh tokens, which are unaffected).
- **The frontend must** keep the access token in memory, refresh on 401 or before expiry, and
  single-flight concurrent refreshes (M9).
