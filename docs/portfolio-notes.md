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

---

## M5: Assignment (2026-10-02)

**What was built**
- `PUT /api/tickets/{id}/assignment`: take, release, assign, reassign and transfer, authorized
  by a pure `AssignmentPolicy` that combines role (agent vs team lead) with team membership and
  the ticket's current owner.
- Ownership drives status through **SYSTEM-only** workflow moves applied by the entity (NEW →
  ASSIGNED on gaining an owner; ASSIGNED/IN_PROGRESS/WAITING → NEW "return to queue" on losing it),
  so users can't reach those statuses through `/transitions`, and work in progress always has an
  owner.
- Idempotent PUT (same values → no version bump, no audit). A compact response, because a transfer
  can remove the caller's right to see the ticket.

**Design correction (good interview story)**
- In M3 I enforced "assignee is a team member" with a **composite foreign key**. Designing
  membership removal exposed the flaw: closed tickets keep their historical assignee, so the FK would
  stop anyone who had ever worked a ticket from ever leaving that team. I replaced it (V5, ADR-008)
  with two triggers expressing the real rules: membership is checked **at assignment time**, and
  a member can't be removed while owning **open** tickets. The service translates the latter to
  409 `MEMBER_HAS_OPEN_TICKETS`; a demotion that would orphan open tickets is rolled back as a whole.
  Takeaway: FKs are for whole-lifetime invariants.

**Bug caught in my own design before shipping**
- The first version of the "take" rule let an agent assign themselves a ticket **already owned by a
  colleague**. Restricted taking to unassigned tickets. **Mutation-checked:** removing the guard
  makes `agentCannotTakeATicketAlreadyOwnedByAColleague` fail.

**Tests:** 20 new unit tests (12 policy cases, entity status effects, membership-removal
translation, updated exhaustive workflow tables) and 18 new integration tests (assignment API with
real tokens, membership triggers incl. "closed history doesn't block removal", two team leads
assigning concurrently). Total: 248 unit + 147 integration. V5 also applied as an incremental
migration to a local database that already held data.

---

## M6: Comments, internal notes & history (2026-10-02)

**What was built**
- Ticket conversation with PUBLIC comments and INTERNAL notes (immutable), plus a staff-only
  history timeline built from the audit trail, with actor names loaded in one query and before/after
  values returned as structured JSON.
- Transition reasons (waiting for user, cancel, reopen) become public comments in the same
  transaction, so requesters see what support is asking.

**Decisions worth discussing**
- *Relationship-based visibility.* "Can see internal notes" means "admin or supporting **this**
  ticket", not "has a staff role". The edge case: an agent who raised a ticket handled by another
  team is only its requester there.
- *Filter in the query, not after loading.* The allowed visibilities are part of the SQL `WHERE`,
  so a requester's request never loads internal rows, and a later mapping change can't leak them.
- *First response without false conflicts.* A conditional bulk update
  (`SET first_responded_at = :now WHERE id = :id AND first_responded_at IS NULL`) is atomic and
  intentionally bypasses `@Version`. The field is write-once, and bumping the version would give
  agents editing the ticket a spurious 409 just because someone commented. Tested: version stays 0,
  and the timestamp never moves after the first reply.

**Mutation-checked guarantees**
- Removing the visibility filter from the query → 2 API tests fail (including the raw-JSON check).
- Replacing "supporting this ticket" with "is staff" → the edge-case unit test fails.

**Tests:** 5 new unit tests (comment policy incl. the cross-team requester) and 10 new
integration tests (comment API visibility, first response, transition notes, cancelled-ticket
behaviour, audit without content, API and DB body validation, history timeline and access).
Total: 253 unit + 157 integration.

---

## M7: Queues & search (2026-10-03)

**What was built**
- `GET /api/tickets`: views (requested / mine / team / unassigned), 10 optional filters, sorting,
  paging, and search over references, full text, requester and category names, as a **SQL read
  model** (ADR-009) with names joined in one query.
- PostgreSQL full-text search: a generated, weighted `tsvector` column + GIN index,
  `websearch_to_tsquery`, relevance ranking. No Elasticsearch.
- Visibility rules rendered into the `WHERE` clause; views can only narrow.

**Measured (not estimated) results**, on 100k realistic tickets (`docs/performance.md`):
- Unassigned queue page: **0.041 ms** with a partial index vs 0.47 ms without.
- Full-text search: **2.5 ms** with GIN vs 16.9 ms sequential scan.

**What I learned (good interview material)**
- My first index test expected the GIN index on 20k rows. PostgreSQL chose a sequential scan, and
  was right (cost 1,020 vs 1,930). GIN has a high fixed cost; it wins on bigger tables.
- **Test data shapes query plans.** Two seed-data mistakes (every open ticket unassigned; status
  correlated with team via modulo arithmetic) produced misleading plans. I caught the second by
  reading `actual rows=5000` for one team in `EXPLAIN ANALYZE`. The seed now models a realistic
  distribution.
- A **partial index** is only usable when the planner can prove the query implies its predicate,
  so the terminal-status list is inlined as literals rather than bind parameters.
- Also corrected a wrong test assumption: the English stemmer maps "printing" → `print` but
  "printer" → `printer`.

**Testing**
- `QueryPlanIT` turns index decisions into regression tests: seed 100k tickets, `EXPLAIN` the exact
  generated SQL, assert the intended index is used.
- Injection payloads through every input (unit) plus a live `DROP TABLE` attempt (API).

**Tests:** 23 new unit tests (SQL builder: visibility, views, filters, references, injection,
ordering) and 23 new integration tests (list API: visibility, views, filters, search, sort, paging,
validation; 4 query-plan assertions). Total: 276 unit + 180 integration.

---

## M8: API contract & documentation (2026-10-03)

**What was built**
- A precise OpenAPI 3.1 contract, committed as `docs/openapi.json`, for generating the frontend's
  TypeScript types:
  - explicit operation ids (`createIncident`, not `create_1`);
  - correct 201s and `application/json`;
  - an RFC 9457 `ApiProblem` error schema listing every error code, on every operation, plus 401
    on protected ones;
  - a relative server URL.
- **Exact nullability:** Jackson always writes every field, so response properties are marked
  present. Fields that can be null are annotated `@Nullable` and become `T | null`. Nullable object
  references are wrapped as `oneOf: [$ref, null]`, because OpenAPI 3.1 ignores keywords next to
  `$ref`. Verified by generating types with `openapi-typescript`
  (`assignee: NamedRef | null`, `createdAt: string`).
- `docs/api.md`: conventions, an auth flow, an error-code table, endpoint tables and a curl
  walkthrough.

**Testing**
- `ApiContractIT`: the live spec must equal the committed snapshot, so API changes become
  reviewable diffs; regenerate with `-Dopenapi.update=true`. It also enforces the conventions and
  checks a real error response against the documented schema. **Mutation-checked:** changing one
  summary fails the build with a clear message.
- `ApiDocumentationTest`: every `ErrorCode` and every operation id must appear in `docs/api.md`.

**Things found along the way**
- jspecify's `@Nullable` (a TYPE_USE annotation) isn't seen by swagger-core on record components;
  `jakarta.annotation.Nullable` (a declaration annotation) is. Found by probing the generated spec
  before annotating 21 fields.
- The contract test caught a **test-only controller leaking into the published spec**. Fixed by
  limiting springdoc to `/api/**`.

**Tests:** 2 new unit tests and 6 new integration tests. Total: 278 unit + 186 integration.

---

## M9: Web app foundation (2026-10-03)

**What was built**
- React 19 + TypeScript (strict) + Vite app with Tailwind 4 and shadcn/ui (Radix), React Router,
  TanStack Query, React Hook Form + Zod.
- A **typed API client generated from the backend contract** (openapi-typescript + openapi-fetch),
  plus `check:api` to catch stale types: contract → types → compiler, end to end.
- Authentication: access token in memory only, silent session restore on reload via the HttpOnly
  refresh cookie, **single-flight refresh** shared by concurrent 401s with one retry per request,
  sign-out that revokes server-side and clears cached data, and protected routes that return users
  to the page they asked for.
- An accessible login form (labels, `aria-invalid`, `aria-describedby`, alert role, autocomplete
  hints) and a skip link.

**Decisions worth discussing**
- *Why single-flight refresh matters here specifically:* the backend revokes a whole session when a
  rotated refresh token is reused. Several requests failing at once would each present the same
  token. Sharing one refresh promise prevents that. **Mutation-checked:** without it, the test sees
  2 refresh calls instead of 1. **Seen in a real browser:** React StrictMode runs the start-up effect
  twice in development, yet the network log shows exactly one refresh.
- *Tests through the real client:* MSW intercepts real `fetch`, so tests cover token attachment,
  refresh, retry with the original POST body intact (`request.clone()`), and "refresh failed → back to
  login, no loop".

**Things found along the way**
- `openapi-typescript` doesn't support TypeScript 6 yet. I **pinned TypeScript 5.9** rather than
  forcing peer dependencies.
- That downgrade silently **turned strict mode off** (TS 6 enables it by default; the template
  relied on that). I caught it reading `tsconfig` and enabled `strict` and `noUncheckedIndexedAccess`
  explicitly.

**Verified end to end** in a browser against the real backend: anonymous → `/login`; sign-in;
reload keeps the session (one refresh); no token in `localStorage`, `sessionStorage` or
`document.cookie`; sign-out → server revoked both tokens (`LOGOUT`).

**Known limitation:** the single JS bundle is 596 KB (188 KB gzipped). Route-level code splitting is
planned with the agent portal (M11).

**Tests:** 14 frontend tests (API client 6, login 3, session lifecycle 5).

---

## M10: Requester portal (2026-10-04)

**What was built**
- My tickets: a responsive table with status and priority badges, an Open/All filter and paging
  kept **in the URL** (back button, refresh and shared links all work), with empty/loading/error
  states.
- "Report a problem" and "Request something" forms: a dependent category → subcategory picker
  (the stale subcategory is cleared when the category changes), plain-language impact/urgency
  options, Zod validation, and server `fieldErrors`/codes mapped onto the matching field.
- Ticket page: details, the public conversation with reply, and **action buttons generated from the
  server's `/transitions` answer**, with a reason dialog where required.

**Decisions worth discussing**
- *The UI never encodes the workflow.* Buttons come from the server, so frontend and backend can't
  disagree about what a user may do. Moves needing inputs this screen doesn't collect yet aren't
  offered (rather than shown and failing).
- *Optimistic locking surfaced to users.* Moves carry the ticket `version`; a 409 produces "someone
  else updated this ticket" plus a reload, never a silent overwrite. **Mutation-checked:** sending
  a fixed version fails 2 tests.
- *Native `<select>` over a custom listbox:* accessible and mobile-friendly by default.

**Found along the way**
- A test failed because the category placeholder and its validation error had the same text, which
  is confusing for screen-reader users. Renamed the placeholder.
- Verified that an icon-only dialog close button has an accessible name ("Close") with an explicit
  role/name assertion, rather than trusting a browser tool's simplified tree.

**Verified end to end** in a browser against the real backend: sign in as a requester → report a
Wi-Fi problem (got `INC-000002`, P2 from Medium × High, routed to Network Team) → reply → cancel
with a reason (it appears as a status note in the conversation; the reply box closes) → Open list
empty, All list shows the cancelled ticket, and no other user's tickets.

**Tests:** 15 new frontend tests (my tickets 4, incident form 4, ticket page 7). Frontend total: 29.

---

## M11: Agent portal (2026-10-05)

**What was built**
- Queue page: Mine / Team / Unassigned / All, search (reference, full text, names), type and
  priority filters, priority-then-oldest default sort, "best match" when searching, all URL-driven.
- Staff ticket view: take / release / assign (team leads, from the member list) / transfer;
  generalised action dialog (reason, resolution code + notes, fulfilment notes) driven by the
  server's `requirements`; internal notes visibly marked; a readable history timeline.
- Route-level code splitting: main bundle **596 → 434 KB (188 → 137 KB gzipped)** while adding
  features.

**Contract improvements (backend)**
- Resolution codes became a typed enum in the API, and the UI's label map is
  `Record<ResolutionCode, string>`: a new backend code breaks the frontend build until it is
  labelled. Exhaustiveness checked by the compiler.
- History entries carry a `names` map (users/teams/categories found in audit values, one batch
  query per kind), so the timeline reads "assigned it to Alex Agent".
- Found and fixed a contract bug: swagger-core described Jackson's `JsonNode` *class* (24 boolean
  "fields") instead of "any JSON object". Mapped it to a free-form object, then caught that the
  replacement had lost nullability. Generalised the nullable customizer, and added a contract
  assertion so neither can regress.

**Incident worth telling:** midway through, the Mac's disk filled up (317 MB free) and Docker's
storage began failing with I/O errors. I diagnosed it (`df`, `docker system df`) rather than
retrying, reported the reclaimable space without deleting anything myself, and resumed once the
user freed space, re-running the interrupted verification first.

**Verified end to end** in a browser against the real backend as an agent: Unassigned queue (P1
first, cancelled ticket excluded) → open INC-000003 → Take → Start work (offered only once
assigned) → internal note → Resolve with "Fixed" + notes → history shows five readable entries
with names.

**Tests:** backend +2 integration tests (history names, enum code rejected) → 278 unit + 187
integration; frontend +14 (agent ticket page 5, queue 5, history sentences 4, requester sees no
staff panels 1, replacing one obsolete test) → 43.

---

## M12: Containers & demo data (2026-10-05)

**What was built**
- Multi-stage Dockerfiles: the backend uses a cached dependency layer and **Spring Boot layered jar**
  on a JRE runtime as uid 10001 with container-aware heap; the web image builds with Node and is
  served by **unprivileged nginx** (83 MB).
- nginx as the single entry point: SPA fallback, `/api` reverse proxy (the same single-origin
  layout as the planned CloudFront setup), immutable caching for hashed assets, and security headers
  including a CSP.
- Compose with **health-ordered start-up** (postgres → backend readiness → web). The backend
  isn't published. The whole stack is healthy in ~17 s once built.
- A demo seeder that creates 7 users, 3 teams, 9 categories and 8 tickets in every interesting
  state **through the real services, as the real users**, so the data obeys every rule and has a
  genuine audit history. Idempotent and profile-gated.

**Decisions and checks worth discussing**
- *Seeding through services, not SQL:* a SQL seed could create states the application forbids.
  Going through services means the demo is also a smoke test of the whole domain, and
  `DemoDataSeederIT` asserts every ticket has an actor-attributed creation event.
- *nginx `add_header` inheritance trap:* headers declared at server level disappear in any location
  that adds its own header. I used an included snippet per location and verified headers on both
  `/` and `/assets/`.
- *CSP verified, not assumed:* I tried `style-src 'self'`; Chrome blocked the Radix dialog's
  injected scroll-lock style, so `'unsafe-inline'` stays for styles only, with the reason recorded.
- *Separate demo database* (`esm_demo`), so the containerised demo never collides with IDE
  development data.

**Verified** in a browser against the containerised stack: sign in as the demo team lead → team
queue (P1 first) → seeded ticket with internal note, status notes and a 7-entry history → lead-only
"Assign to" member list → Radix dialog renders with no CSP violations.

**Tests:** +1 unit (weak demo password refused) and +3 integration (`DemoDataSeederIT`: states
via the real rules, idempotency, demo sign-in and requester visibility). Total: 279 unit + 190
integration; frontend 43.
