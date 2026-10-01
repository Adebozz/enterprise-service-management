# Portfolio notes

Running log of **implemented** engineering work, used later for CV bullets and interview STAR
stories. Only things that exist in the code are recorded here. No invented metrics.

---

## M0: Bootstrap (2026-10-01)

**What was built**
- Monorepo with a Spring Boot 4.1 / Java 21 backend, PostgreSQL 17 through docker-compose, and a
  Flyway migration pipeline.
- One error contract for the whole API: RFC 9457 ProblemDetail plus `code`, `timestamp` and
  `correlationId` extensions. It covers business errors, validation, Spring MVC errors (404/405/
  malformed JSON) and Spring Security 401/403.
- Request correlation IDs: a servlet filter puts an ID in the SLF4J MDC, so every log line and
  every error response for a request share it.
- Integration tests against real PostgreSQL via Testcontainers, sharing one cached Spring context
  and container. Unit and integration tests are split between Surefire (`*Test`) and Failsafe
  (`*IT`), with a JaCoCo coverage report.

**Decisions worth discussing**
- *Security errors use the same renderer as everything else.* Spring Security's entry point and
  access-denied handler delegate to MVC's `HandlerExceptionResolver`, so one `@RestControllerAdvice`
  renders every error. There is one source of truth and no duplicated JSON writing.
- *A client-supplied correlation ID is validated* (`[A-Za-z0-9-]{8,64}`), which blocks log injection
  through the `X-Request-Id` header. This is covered by a parameterized test that includes a
  newline-injection case.
- *`open-in-view` is disabled* so lazy-loading problems show up in tests instead of hiding as N+1
  queries in production.
- *Spring Boot 4 instead of 3.x* (ADR-003). The original specification said 3.x, but Initializr no
  longer offers 3.x for new projects.

**Bug found and fixed**
- Spring MVC's 404 (unknown route) and 405 (wrong method) responses were missing the custom `code`
  field. Cause: for these exceptions, `ResponseEntityExceptionHandler.handleExceptionInternal`
  receives `body == null` and creates the ProblemDetail **inside** `super`, so enriching the
  incoming body did nothing. Fix: call `super` first, then enrich the returned body. An integration
  test caught this, and both cases are now covered.

**Tests:** 7 unit tests (correlation filter) and 14 integration tests (boot + migrations + Postgres
version, health probes, actuator exposure, OpenAPI, and the full error contract including the
"no internal details leaked on 500" case).

---

## M1: Users & teams (2026-10-01)

**What was built**
- `V2` migration: `users`, `teams`, `team_members` with check, unique and foreign-key
  constraints. Every constraint is verified by raw-SQL tests that bypass the application.
- Admin APIs to create, search, update and deactivate users and to manage teams and membership,
  plus a read API for support staff to list teams.
- A role hierarchy (`ADMIN > TEAM_LEAD > AGENT > REQUESTER`) generated from one enum and applied to
  both URL and method security.

**Decisions worth discussing**
- *Last-admin race condition.* "Can't demote the last admin" is a check-then-act race: two admins
  demoting each other concurrently both see the other still active. I fixed it with a
  pessimistic lock (`SELECT … FOR UPDATE` via Spring Data `@Lock`) on the active-admin rows. Under
  PostgreSQL READ COMMITTED, the blocked transaction re-evaluates its WHERE clause after the first
  commits, so it sees one admin and refuses. **Verified both ways:** with the lock removed, the
  concurrency test failed 5/5 runs with both demotions succeeding (zero admins left). With the lock,
  it passes 5/5.
- *Optimistic locking for API clients.* Clients send back the `version` they read, and a stale
  version gets a 409. I flush before building the response so it carries the *incremented*
  version. Without the flush, the client's next edit would hit a false conflict.
- *Avoiding a module cycle with a synchronous domain event.* `team` depends on `user`. For the rule
  "demoted requesters leave all teams", `user` publishes `UserRoleChangedEvent` and `team` listens,
  in the same transaction, so the change stays atomic without a circular dependency.
- *Membership as an entity instead of `@ManyToMany`*, so the join row can carry `joined_at` and
  later be the target of a composite foreign key. It references the user by ID only, and the
  member list is one JPQL join projected directly into a DTO, so there's no N+1.
- *Case-insensitive uniqueness in the database.* A `CHECK (email = lower(btrim(email)))` combined
  with a plain `UNIQUE`, and a unique index on `lower(name)` for teams. Duplicate races are caught
  at the constraint and translated to a 409.
- *Security details.* Sort-field allow-lists (otherwise `?sort=passwordHash` leaks hash ordering),
  LIKE-wildcard escaping, a UTF-8 byte-length check for BCrypt's 72-byte limit, and no password
  field in any response DTO.

**Tests:** 34 new unit tests (role hierarchy, user and team business rules with Mockito, sort
allow-list, LIKE escaping) and 37 new integration tests (schema constraints, admin user API, team
API, cross-module demotion, concurrency). Total: 41 unit + 51 integration, all against PostgreSQL 17.

---

## M2: Authentication (2026-10-01)

**What was built**
- Login, refresh, logout, `/api/users/me` and self-service password change on Spring Security's
  OAuth2 Resource Server support: Nimbus `JwtEncoder`/`JwtDecoder`, HS256, 15-minute access tokens,
  and a custom converter that turns JWT claims into a typed `CurrentUser` principal.
- Rotating refresh tokens in an HttpOnly/Secure/SameSite=Strict cookie. Only SHA-256 hashes are
  stored. Reuse detection revokes the whole token family, with an absolute 7-day session.
- First-admin bootstrap from environment variables (idempotent, safe with multiple instances).
- A controllable test clock (`MutableClock`) and real-login test helpers, so security tests use
  real tokens instead of mocked principals.

**Decisions worth discussing**
- *Revocation must survive a failed request.* When a reused token is detected, we revoke the family
  and then return 401. A thrown exception normally rolls the transaction back, undoing the
  revocation. Fixed with `@Transactional(noRollbackFor = InvalidRefreshTokenException.class)` and by
  keeping the calling service **non-transactional**, because an outer transaction would roll back
  anyway. **Verified both ways:** with `noRollbackFor` removed, the reuse-detection test fails (the
  "revoked" session still refreshes).
- *Multi-tab refresh race vs theft.* Two tabs refreshing at once present the same token. A
  `SELECT … FOR UPDATE` row lock makes exactly one rotate (concurrency test, 5 repeats). The loser
  falls inside a 30 s grace window and is rejected without revoking the session.
- *Account-enumeration resistance.* Identical error for unknown email, wrong password and inactive
  account, and a BCrypt comparison against a dummy hash for unknown emails so the timing is equal.
- *JWT hardening, verified by tests:* only HS256 is accepted (an `alg: none` token is rejected),
  plus wrong-key, tampered-payload, wrong-issuer and expired tokens (via the test clock), and
  startup fails on a missing or short secret.
- *Revocation lag trade-off* (ADR-005). Stateless access tokens mean role changes and deactivation
  apply at the next refresh (at most 15 min), because refresh re-reads the user.

**Bugs caught by tests**
- `ddl-auto=validate` rejected the V3 migration at startup: `char(64)` vs the entity's `varchar`.
  Fixed before the migration was ever applied, keeping the fixed length as a regex CHECK.
- `expiresIn` returned 899 instead of 900, and cookie `Max-Age` 604799 instead of 604800, because
  the code re-read the clock after issuing the token. Fixed by deriving both from the token's own
  issue and expiry timestamps.

**Tests:** 16 new unit tests (secret validation, login enumeration resistance, bootstrap rules,
password change) and 33 new integration tests (`AuthApiIT` 14, `JwtSecurityIT` 14,
`RefreshConcurrencyIT` 5). Total: 57 unit + 84 integration. Also smoke-tested manually against the
docker-compose database with curl (bootstrap → login → `/me` → admin create → refresh → logout →
refresh rejected).

---

## M3: Ticket core & audit trail (2026-10-01)

**What was built**
- Incidents and service requests on one `work_items` table with type-specific extension tables
  (JPA `JOINED` inheritance and a discriminator). The database `CHECK` validates `(type, status)`.
- Race-free human-readable references (`INC-000001`) from per-type PostgreSQL sequences, proven by
  20 concurrent creations all receiving distinct references.
- Priority from a configurable impact × urgency matrix, validated at startup (a missing cell
  stops the app), behind a single `PriorityPolicy`.
- Category-based routing (subcategory team overrides parent team), with a DB check guaranteeing
  every top-level category routes somewhere. Admin category management.
- `TicketAccessPolicy`: owners, team staff and admins see a ticket; everyone else gets 404 (not
  403) to prevent id enumeration. It's a pure function unit-tested case by case, including an agent
  who raised a ticket in another team's queue.
- Append-only audit trail written in the same transaction (`Propagation.MANDATORY`), with actor,
  correlation id and jsonb before/after values. Retrofitted to user, team, password and category
  operations.

**Decisions worth discussing**
- *Aggregates reference each other by id, not JPA associations*, even within the ticket module.
  This keeps the package graph acyclic (verified while designing: a `@ManyToOne Category` on
  `WorkItem` would have created a `ticket ↔ category` cycle) and rules out lazy-loading N+1 by
  construction.
- *Dependency inversion for the audit actor.* `audit` defines `ActorProvider`, `auth`
  implements it, so every module can depend on `audit` without a cycle through security.
- *Composition over inheritance for services.* `TicketIntake` holds the shared creation steps;
  `IncidentService` and `ServiceRequestService` call it rather than extending a base service.

**Bugs found and fixed**
- *Latent M2 bug in the admin bootstrap:* it caught the duplicate-email exception **inside**
  `@Transactional`, but a failed flush marks the transaction rollback-only, so the commit would
  throw `UnexpectedRollbackException` and abort startup in exactly the multi-instance race it
  claimed to handle. Fixed with `TransactionTemplate` and catching **outside** the transaction.
  **Verified both ways:** the new `AdminBootstrapIT` fails with
  `UnexpectedRollbackException … marked as rollback-only` when the catch is moved back inside.
- *Misleading error translation:* the audit trigger first raised `insufficient_privilege` (SQLSTATE
  42501), which Spring translates to `BadSqlGrammarException`. Switched to `restrict_violation`
  (23001), so the app sees a `DataIntegrityViolationException`.
- `ddl-auto=validate` again pinned a `text` vs `varchar` mapping (fixed with `columnDefinition`).

**Tests:** 31 new unit tests (priority matrix 9 cells + incomplete matrix, access policy, category
routing, reference format, audit assertions in user/team tests) and 28 new integration tests
(ticket API with real tokens, schema constraints incl. audit trigger and assignee-in-team FK,
category API, audit guarantees, bootstrap race, reference concurrency). Total: 88 unit + 112
integration. Also verified V4 as an incremental migration on an existing local database.

---

## M4: Workflow engine (2026-10-01)

**What was built**
- A small declarative workflow engine: `WorkflowDefinition` (immutable table of transitions with
  label, allowed actors and requirements). Incident and service-request lifecycles are defined as data.
- The entity is the only thing that changes status: it validates the move and its requirements
  (reason, resolution code + notes, fulfilment notes, assignee present) and applies side effects
  (first response time, resolved/closed timestamps, reopen count, clearing a failed resolution).
- `TicketTransitionService` adds visibility (404), version (409), lifecycle (409) and actor-based
  permission (403) checks, plus audit with reason and resolution code.
- `GET /api/tickets/{id}/transitions` returns the moves the caller can make now, so the UI never
  duplicates workflow rules.

**Decisions worth discussing**
- *Actors are relationships, not roles.* An agent who raised a ticket in another team's queue is
  only its REQUESTER: they can confirm closure but can't resolve their own ticket. Admins act as
  SUPPORT everywhere; SYSTEM is never granted through the API.
- *Defence in depth for the state machine.* The service checks the move before authorization, and
  the entity re-checks it on every change, so future non-API callers (scheduler, assignment rules)
  can't bypass it.
- *409 instead of 400 for invalid transitions.* The request is valid; it conflicts with current state.
- *Workflow as data instead of Spring Statemachine* (ADR-007).

**Testing insight**
- Exhaustive parameterised tests check **every from/to pair** of both lifecycles (49 + 64 cases)
  against an independently written allowed-set, so any lifecycle change must be made twice,
  deliberately.
- Concurrency: two agents changing the same ticket version simultaneously, repeated. I
  instrumented the losing path and found it was **always** Hibernate's `@Version` check (30/30
  runs), never the explicit version comparison, because both threads read before either committed.
  I corrected the test's documentation rather than claim it covers both, and the explicit check
  has its own sequential test. Also verified the loser's audit entry is rolled back with its change.

**Tests:** 140 new unit test cases (exhaustive lifecycle matrices, definition builder rules,
entity side effects, actor resolution) and 17 new integration tests (full lifecycle with audit
trail, error codes, permissions, available transitions per caller, SYSTEM-only approvals,
concurrency). Total: 228 unit + 129 integration.
