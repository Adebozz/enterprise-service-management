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
  member list is one JPQL join projected directly into a DTO, so there's no N+1. *(In M5 the
  composite foreign key was replaced by triggers, because it froze ticket history when people
  changed teams; see ADR-008.)*
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
planned with the agent portal (M11). *(Done in M11: 434 KB, 137 KB gzipped.)*

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

## M13: Continuous integration (2026-10-05)

**What was built**
- A GitHub Actions workflow on every pull request and push to `main`, as three parallel jobs:
  - **backend:** `./mvnw verify`, covering formatting, unit tests, Testcontainers integration tests
    against real PostgreSQL, the OpenAPI snapshot test and coverage floors. Reports are uploaded as
    an artifact.
  - **frontend:** lint, strict type-check, a check that the generated API types match the contract,
    the tests and a production build.
  - **stack:** builds both Docker images, starts the Compose stack (waiting on every health check)
    and runs an end-to-end smoke test through nginx.
- **JaCoCo coverage floors** enforced at `verify`: 90% lines and 80% branches overall, and 90% /
  85% on the security- and rule-critical packages (auth, ticket access, assignment, priority,
  transitions, workflow). Each floor is set a few points below measured coverage, so that only a
  real drop fails the build.
- `scripts/smoke-test.sh` makes 13 checks of what a browser depends on: the app and SPA fallback,
  security headers, the `/api` proxy, problem+json errors, actuator not reachable from outside, demo
  sign-in, requester visibility, and role enforcement (403) through the proxy.
- Dependabot for Maven, npm, Actions and Docker base images, grouped weekly. The TypeScript major
  version is held back with the reason recorded.

**Decisions and checks worth discussing**
- *Contract drift is closed from both sides:* the backend job proves the code matches
  `docs/openapi.json`, and the frontend job proves the generated types match the same file.
- *Supply chain:* the actions are pinned to full commit SHAs (tags can be moved), the workflow
  token is read-only, and CI does not push images yet because nothing deploys them.
- *Gates that can't pass silently:* I raised the package floor to 99% temporarily to prove that
  the package patterns really match (four packages were then reported as violations). I also
  stopped the web container to prove the smoke test fails, and it reported all 13 checks failing
  with exit 1.
- *Linted:* the workflow passes actionlint (which also runs shellcheck on its `run:` steps), and
  the smoke script passes shellcheck.

**Verified locally** with the same commands CI runs: backend `verify` passed with all coverage
checks met (279 unit + 190 integration), the frontend steps passed (43 tests), and the Compose stack
plus smoke test passed 13/13. On GitHub (run on `7a65155`), all three jobs passed in parallel,
and the whole run took under two minutes (backend ~1m40s, stack ~1m40s, frontend ~30s).

**Adjusted after the first runs**
- The first push's backend and frontend jobs were cancelled because GitHub never assigned them a
  hosted runner (its own capacity issue; the annotations say so). That was not a test failure, and
  I didn't treat it as one.
- Dependabot's first scan proposed Java 21→25 and Node 22→26 base images. Runtime majors are
  deliberate upgrades that touch the pom, CI and docs, so major Docker bumps are now ignored. The
  runner is also pinned to `ubuntu-24.04` instead of `ubuntu-latest`, so an OS change is a reviewed
  change.

## M14: Documentation, screenshots and boundary tests (2026-10-06)

**What was built**
- A README written for a reader with two minutes:
  - what the project is, and a one-command demo with the demo accounts;
  - features shown with real screenshots;
  - the engineering highlights, each linked to its ADR or doc;
  - a Mermaid architecture diagram;
  - the testing and CI approach;
  - a roadmap that separates what's built from what's planned.
- Seven screenshots taken from the running Docker stack and its demo data, with a headless-Chrome
  script that is read-only: it submits nothing and backs out of the one dialog it opens. The
  requester and agent views of the same ticket show internal notes being withheld.
- **Module boundaries enforced by ArchUnit** (`ArchitectureTest`), turning an ADR-001 promise into
  a build rule:
  - no dependency cycles between modules or between `ticket`'s sub-packages;
  - `common` doesn't depend on any feature;
  - controllers never use repositories.
- A docs pass:
  - stale wording fixed (the security doc still described pre-login behaviour; the audit list
    and the "so far" sections were out of date);
  - the architecture diagram now labels the AWS components as planned;
  - the README no longer lists an `infrastructure/` folder that doesn't exist;
  - all relative links checked.

**Found and fixed while taking the screenshots**
- *An ambiguous destructive button.* The cancel dialog offered "Back" and "Cancel", where "Cancel"
  meant cancelling the ticket. Labels belong to the server's workflow definitions, so the fix went
  there ("Cancel ticket", "Cancel request"). `TransitionLabelsTest` now forbids labels that read
  like dialog controls and requires distinct labels from each status.
- *Wrong navigation highlight.* "My tickets" was highlighted on any `/tickets/...` page, including
  a ticket an agent opened from the queue. A frontend test now covers it.

**Checks that each new rule can fail:** a deliberate violation was added for each ArchUnit rule
(each one failed, then the violating class was deleted), and the label test failed against the
old labels. The nav test also failed without its fix.

**Tests:** +7 backend unit tests (4 architecture rules, 3 label rules) and +1 frontend test. Total:
286 unit + 190 integration (backend), 44 frontend, plus the 13-check smoke test. Coverage
unchanged: 95.0% lines / 84.6% branches overall.

---

## Phase 1 summary (2026-10-06)

**Built and tested:**
- **Product:** a two-portal service desk (requester and agent) for incidents and service
  requests, with routing, a priority matrix, workflows as data, assignment, internal notes,
  full-text queues, a history timeline and an append-only audit trail.
- **Backend:**
  - Spring Boot 4.1 / Java 21 modular monolith with tested module boundaries;
  - PostgreSQL 17 with 7 Flyway migrations, including constraints and triggers;
  - JWT plus rotating refresh-token authentication;
  - an OpenAPI 3.1 contract snapshot-tested against the code.
- **Frontend:** React 19 + strict TypeScript with types generated from that contract, a
  memory-only access token and server-driven workflow actions.
- **Delivery:** non-root Docker images, nginx with a CSP, a health-ordered Compose stack with demo
  data, and GitHub Actions CI with coverage floors and an end-to-end smoke test.
- **Tests:** 286 backend unit tests, 190 backend integration tests against real PostgreSQL,
  44 frontend tests and a 13-check smoke test. Backend coverage is 95.0% lines and 84.6% branches,
  with enforced floors.

**Not built (planned for Phase 2/3, and not to be claimed):**
- SLA engine, service catalogue and approvals, problem and change management, attachments,
  notifications, an admin UI;
- reporting, load testing (so there are no throughput or latency figures), AWS deployment
  (ECS/RDS/S3/CloudFront/Terraform) and login rate limiting.


## After Phase 1: findings from hands-on testing (2026-10-11)

I clicked through every demo role in the containerised app; the backend logged no warnings or
errors. Two findings came from that session:

- **A gap in the demo taxonomy (fixed).** A requester filed a login problem under Hardware ›
  Laptop, because the Identity & Access team only had a service-request category, so account faults
  couldn't be reported as incidents. I added "Account & login" (with "Can't sign in" and
  "Multi-factor authentication"), routed to Identity & Access. `DemoDataSeederIT` now checks that
  *every* team can receive incidents through some category; it failed with
  `["Identity & Access"]` before the fix. The running demo got the same categories through the
  admin API, so they went through validation and are in the audit trail.
- **Resume doesn't require a reply (kept, noted for Phase 2).** A requester can move a ticket from
  "Waiting for user" back to "In progress" without writing anything. Many service desks resume
  automatically when the requester replies. That belongs with the Phase 2 SLA work, where waiting
  for the user pauses the SLA clock.

---

## M15: SLA policies and clocks (2026-11-11)

**What was built**
- A new `sla` module (depends on `ticket`, never the reverse) with migration V8.
- `sla_policies`: response and resolution targets for each ticket type and priority, seeded as
  reference data (P1 incident: respond in 15 minutes, resolve in 4 hours).
- `SlaClock`: one clock per ticket and metric that can run, pause, resume, complete, reopen, be
  cancelled and be given a new target, keeping the time already used. It derives a status: on
  track, at risk, breached, paused, met, missed or cancelled.
- Database CHECKs that keep a clock's fields consistent with its state, and a partial index for the
  breach job to come. Design recorded in ADR-010.
- **Not yet connected to tickets.** Clocks don't start or react to status changes until M16.

**What I learned (good interview material)**
- **Store stretches, not just a deadline.** Keeping `elapsed_ms` plus `running_since` makes pause,
  reopen and retarget simple moves of time between two fields. Storing only a deadline would have
  lost the time already used.
- **Order matters when a target changes.** A clock that was already overdue under its old target
  must keep that original breach time before the new target applies, otherwise a longer target
  would hide a real breach. A unit test pins this.
- **Seeded reference data and test cleanup collide.** The integration-test cleaner truncates every
  table, which would have wiped the seeded policies. It now keeps `sla_policies`, and a test checks
  that the policies survive a clean.

**Tests:** 21 new unit tests (clock arithmetic with exact instants: at-risk threshold, pause and
resume, breach recording, met and missed, reopen, retargeting, invalid calls) and 9 new integration
tests (seeded targets for all 8 combinations, missing-policy error, policy and clock constraints,
clock round trip, cascade delete). Total: 307 unit + 200 integration. The `sla` package is at
96.5% line and 100% branch coverage.

---

## M16: Clocks connected to tickets, auto-resume and breach check (2026-11-11)

**What was built**
- `TicketChangedEvent`, published by ticket intake, transitions, assignment (when it changes the
  status) and comments (first response, auto-resume). The `sla` module listens in the same
  transaction, so `ticket` still doesn't depend on `sla` (ArchUnit still passes).
- `SlaClockService` reconciles both clocks with the ticket's state rather than scripting one
  operation per transition; `SlaPhase` maps every status of both ticket types with exhaustive
  switches.
- **Auto-resume:** a requester's public reply moves a ticket from Waiting for user to In progress,
  audited with `trigger: REQUESTER_REPLY` and shown in the history as "... by replying". This
  closes the gap noted after Phase 1.
- `SlaBreachJob`: every minute, claims overdue clocks in batches with `FOR UPDATE SKIP LOCKED` and
  records each breach once. Breaches are audited as the system (`AuditService.recordAsSystem`).
- The history timeline names breaches ("System flagged the response time target as breached").

**What I learned (good interview material)**
- **Reconcile beats per-transition rules.** One function from (phase, responded) to clock state
  handles every route to a status, and running it twice is harmless.
- **A new behaviour changed existing data.** The demo seeder put a ticket into Waiting for user and
  then posted the requester's answer, which now resumes it, so `DemoDataSeederIT` failed with no
  ticket waiting. The seed now leaves the question unanswered.
- **Who caused it matters in an audit trail.** A breach noticed while an agent starts work late
  would have been recorded under that agent's name. It's now always the system's.
- **Moving the test clock also expires tokens.** Jumping hours ahead made the 15-minute access
  tokens invalid; the SLA tests log in again after each jump.

**Tests:** 9 new unit tests (reconcile rules, every status's phase) and 10 new integration tests
(clock targets at creation, full lifecycle through the API, auto-resume and its audit, support
replies not resuming, first response by comment, return to queue, cancel, breach job once and as
the system, breach during an agent's change, tickets without clocks). 2 new frontend tests for the
history wording. Total: 316 unit + 210 integration backend tests, 46 frontend tests. The `sla`
package is at 95.4% line and 94.8% branch coverage.

---

## M17: SLA in the API, the queues and an admin screen (2026-11-11)

**What was built**
- `GET /api/tickets/{id}/sla`: both clocks with status, target, elapsed and remaining time, for the
  support staff handling the ticket (requesters get 403, like the history).
- Queue rows carry the open target (`sla`: metric, status, due time) for staff only, and the queue
  sorts by `slaDueAt`. Computed in the queue's SQL with a `LATERAL` join to `sla_clocks`, so the
  read model stays one query.
- Admin endpoints and the first admin screen: SLA targets per type and priority, with optimistic
  locking, a response-before-resolution rule and an audit entry (`SLA_POLICY_UPDATED`). Changes apply
  to tickets raised afterwards.
- Web app: SLA column and "SLA due soonest" sort in the queue, a Service levels panel on the ticket
  page, and the SLA targets page in the navigation for admins.

**What I learned (good interview material)**
- **One rule, two implementations, one test.** The badge is computed in SQL (for sorting and one
  query per page) and in Java (for the ticket's own clocks). Both read the same
  `esm.sla.at-risk-threshold`, and `SlaApiIT` checks they agree at each stage (at risk, breached,
  paused). The alternative, one implementation behind an extra call per row, would have cost the
  single-query queue.
- **Snapshot targets, don't recompute.** Each clock keeps the target it started with, so editing a
  policy can't silently turn yesterday's met tickets into breaches.
- **Contract names are part of the API.** springdoc named a nested record `Clock` in the contract;
  I renamed it `SlaClockView` before anyone generated code against it.

**Tests:** 7 new integration tests (clocks endpoint and its access rules, queue badge for staff
only, badge and detail agreeing over time, sort by deadline, admin list and update applying to new
tickets only, validation, versioning and authorization). 9 new frontend tests (queue badge and
sort, Service levels panel and its hidden state, admin page saving and showing errors, admin-only
navigation, duration formatting). Total: 316 unit + 217 integration backend tests, 55 frontend tests.
The `sla` package is at 96.7% line and 90.8% branch coverage; the backend overall at 95.3% and 85.8%.

## M18: Service catalogue (2026-11-11)

**What was built**
- A `catalogue` module: items with a name, summary, description, routing category and their own
  form of up to 20 questions (short answer, paragraph, choice, date; required or optional, with
  hints). Items are withdrawn, never deleted.
- Ordering (`POST /api/catalogue/{id}/requests`): answers are checked against the item's form,
  then an ordinary service request is raised, titled with the item's name and routed by its
  category. The request keeps the answers with each question's wording at the time.
- Admin API with optimistic locking, case-insensitive unique names and audit entries listing only
  what changed.
- Web app: "Request something" now opens the catalogue, with a "Something else" card for
  free-text requests; each item's form is built from its questions, and server-side answer errors
  appear next to their question. Admins get a *Catalogue* page and an item editor with a question
  builder (add, reorder, remove, options one per line).
- Demo data: three catalogue items and a laptop ordered from the catalogue.

**What I learned (good interview material)**
- **Snapshot what was asked.** Storing answers by key alone would let an admin's later edit
  silently change what an old request says was asked. Copying the label in costs a few bytes per
  answer ([ADR-011](adr/011-catalogue-forms-as-data.md)).
- **JSON where the data moves as a whole.** A form is always read and saved whole, so a jsonb
  column with one pure validator beat two child tables and a diff-and-merge on every save. The
  ADR records when that would stop being true.
- **Dependencies point one way.** The catalogue calls into the ticket module with a plain
  `CatalogueSubmission`; the ticket module never imports the catalogue, and `ArchitectureTest`
  would fail if it did.
- **Tests find real bugs.** The editor test caught an existing item losing its subcategory: the
  saved value was applied before the category list had loaded, so the browser dropped it. The
  editor now waits for categories, and only clears the subcategory when the category really changes.

**Tests:** 13 new unit tests (`CatalogueFormTest`: form rules, answer rules for each type,
description text). 10 new integration tests (`CatalogueApiIT`: publish and browse, ordering and
routing with answers kept, per-question errors with nothing raised, an item without questions,
admin-only maintenance, duplicate names and invalid forms and categories, withdrawing, editing a
form without rewriting old requests, stale versions, renames), plus seeder checks. 11 new frontend
tests (catalogue browsing, form building and validation, ordering, server errors next to their
question, admin list, creating with generated keys, editing without losing the subcategory,
option validation, editor helpers). Total: 329 unit + 227 integration backend tests, 66 frontend
tests.
The `catalogue` package is at 97.7% line and 93.8% branch coverage; the backend overall at 95.6%
and 86.6%.

## M19: Approvals (2026-11-11)

**What was built**
- Catalogue items can need approval. Ordering one raises the request straight into "awaiting
  approval", in the same transaction, with its SLA clocks paused.
- An `approval` module: one approval per request, decided once (approved, rejected or withdrawn).
  Team leads of the request's team and admins decide, never the requester, and a rejection must
  say why. The decision moves the request through SYSTEM transitions, audited with their trigger,
  and the comment reaches the requester in the conversation.
- A request cancelled while waiting has its approval withdrawn automatically, through the existing
  `TicketChangedEvent`.
- Web app: an *Approvals* inbox for team leads, an Approval panel on the ticket (status, who
  decided and why, and the decision buttons for whoever may decide), a "Needs approval" option in
  the catalogue editor, and a note on the order page.
- Demo: Leo Lead also leads Identity & Access, and Sam's shared drive request waits for him.

**What I learned (good interview material)**
- **Designing for it early paid off.** The M4 workflow already had approval states as SYSTEM-only
  moves, and the M15 SLA phases already paused clocks while awaiting approval. Approvals needed
  no workflow or SLA changes, only a module that makes those system moves.
- **Derive permissions, don't store them.** Storing an approver per request would go stale when a
  request is transferred or a lead changes. Deciding "team leads of the assigned team, now" at
  decision time is always current.
- **Segregation of duties.** "Not your own request" applies to every role, admins included, and
  the error says why, rather than giving a generic 403.
- **Events for the reverse direction.** Cancelling is a ticket action; withdrawing the approval is
  the approval module's business. A listener on the existing event keeps `ticket` unaware of
  approvals.

**Tests:** 9 new unit tests (`ApprovalPolicyTest`: who may decide, including self-approval;
`ApprovalTest`: decisions are final, rejections need reasons, withdrawals have no decider). 10 new
integration tests (`ApprovalApiIT`: held with clocks paused and no way to start work, the inbox
per role, approving resumes clocks and posts the comment, rejecting needs a reason and cancels
clocks, who may decide, no approving through the transitions endpoint, final decisions and stale
versions, withdrawal on cancel, items without approval, hidden from others), 4 for the new system
transition (`SystemTransitionIT`, added when the stricter workflow coverage floor caught its
untested branches), plus seeder and catalogue checks. 9 new frontend tests (inbox approve and reject, the reason prompt, a decision
lost to someone else, navigation by role, the ticket panel for requester and team lead, no panel
when none is needed, history text). Total: 338 unit + 241 integration backend tests, 75 frontend
tests. The `approval` package is at 100% line and branch coverage; the backend overall at 95.9% and
87.3%.

---

## M20: Problem management (2026-11-11)

**What was built**
- Problems as a third ticket type (`PRB-000001`) with their own lifecycle: new, assigned,
  investigating, known error, resolved, closed, and reopen and cancel. Support raises them,
  optionally from the incident that prompted it. They use incident categories, so they reach the
  team that handles those incidents, and they have no SLA clocks.
- Root cause and workaround, edited by the owning team during the investigation. A problem can only
  become a known error with both, and only be resolved with the root cause and a description of the
  permanent fix.
- Incidents link to the problem behind them (audited, only to open problems). Staff on a linked
  incident see the problem's status and workaround there. Resolving the problem posts an internal
  note on each linked incident still open.
- Every agent can read every problem, so a known error helps whoever meets the same incident.
  Requesters see neither problems nor the link on their own incident.
- Web app: a *Problems* page, a form to raise a problem (from scratch or from an incident), an
  analysis section and linked incidents on the problem, a problem panel on incidents (link,
  workaround, remove link, raise a new problem), and a fix prompt when resolving.
- Demo: Nina's "VPN refuses connections at busy times" is a known error explaining two incidents.

**What I learned (good interview material)**
- **Reuse through the shared model.** Making a problem a `WorkItem` subtype meant queues,
  assignment, comments, history, search and the transition API needed no changes. The exhaustive
  `switch` statements over ticket types (SLA phases, UI labels typed as `Record<TicketType, …>`)
  failed to compile until each one decided what a problem means.
- **Validate before mutating.** The first version checked the analysis inside the transition's side
  effects, after the status had already changed. Inside a transaction a rollback hid that, but a
  unit test caught the entity left half-changed. A `checkCanEnter` hook now runs before anything
  changes.
- **Architecture tests catch real cycles.** `problem` posting comments, `comment` using a type from
  `query`, and `query` reading problems made a cycle that ArchUnit rejected. Moving the "problem
  fixed" notes into `comment`, the package that owns the conversation, removed it.
- **A deliberate exception to visibility.** Team scoping is the rule everywhere else; problems are
  readable by all staff on purpose, and the same exception is expressed once in the access policy
  and once in the queue's SQL, each with its own test.

**Tests:** 14 new unit tests (`ProblemLifecycleTest`: what each step needs, reopening, known-error
dates, closed problems; problems have no SLA phase; the queue SQL shows problems to staff and never
to requesters). 9 new integration tests (`ProblemApiIT`: routing and no clocks, visibility per role
and search by reference, raising from an incident, who records the analysis, known errors showing
their workaround on incidents, the fix note on open incidents only, unlinking and auditing, who may
link, and raising an incident versus handling it), plus demo seeder checks. 7 new frontend tests
(problems list, analysis editing, no editing for another team, the fix prompt, linking and
unlinking from an incident, raising from an incident, history text). Total: 352 unit + 250
integration backend tests, 82 frontend tests. The `problem` package is at 100% line and 89.5%
branch coverage; the backend overall at 96.3% and 88.0%.

## M21: Change management (2026-11-13)

**What was built**
- Changes as a fourth ticket type (`CHG-000001`): new, assigned, awaiting approval, approved,
  implementing, review, closed, plus rejected and cancelled. Each has a type (standard, normal,
  emergency), a risk, an implementation plan, a backout plan and a planned window.
- The plan can be edited until the change is submitted, then it is fixed. Submitting needs the whole
  plan and a window that hasn't started (only an emergency may start straight away). A standard
  change must be low risk and is pre-approved, so it is scheduled without an approval.
- Approval through the existing approvals inbox: team leads of the change's team or admins decide,
  never whoever raised it. Cancelling a waiting change withdraws its approval.
- A change schedule (changes awaiting approval, approved or under way whose window hasn't ended),
  and on each change a warning listing other scheduled changes in an overlapping window.
- A review: the outcome (successful, successful with issues, failed, backed out) and notes, needed
  before closing.
- A change can name the open problem it fixes; the problem lists its changes and offers "Raise a
  change to fix this".
- Web app: a *Changes* page with the schedule and the list, a form to raise a change, plan and
  review sections on the change, the approval panel on changes, and changes in the approvals inbox.
- Demo: the VPN known error's fix waiting for Leo's approval, a clashing standard printer change,
  and yesterday's emergency firewall change, reviewed and closed.

**What I learned (good interview material)**
- **Events instead of a dependency cycle.** Changes should reuse approvals, but the approvals module
  already depends on the ticket module, so the change code can't call it. Submitting is an ordinary
  transition, and the approvals module opens the approval when it hears the ticket-changed event,
  in the same transaction. The architecture test would have rejected the direct call.
- **Don't offer a button that can only fail.** Which way out of "assigned" applies depends on the
  change's type. A small `offersMoveTo` hook lets the ticket hide moves its own data rules out,
  while moves that only need more information stay visible and explain what is missing.
- **Tests find schema mistakes.** The first integration run failed with a 500: the outcome
  `SUCCESSFUL_WITH_ISSUES` is 22 characters and the column was `varchar(20)`. It was caught before
  the migration was ever released, so the migration itself could be corrected.
- **Shared status names are a contract.** Requests and changes both use `APPROVAL_PENDING`,
  `APPROVED` and `REJECTED`, which lets one approval service drive both; a unit test fails if
  either type renames them.

**Tests:** 11 new unit tests (`ChangeLifecycleTest`: assignment, standard changes low risk and
pre-approved, the window, a complete plan before submitting, emergencies only starting at once,
the plan fixed after submission, carrying out and reviewing, rejection and cancellation; the shared
approval status names). 9 new integration tests (`ChangeApiIT`: routing, visibility and no clocks,
who writes the plan and when, approval in the lead's inbox and never by the raiser or through
transitions, rejection and withdrawal, standard changes without approval, incomplete or past plans,
the schedule and clashes, the review, the problem link), plus demo seeder checks. 7 new frontend
tests (schedule and overlap flag, plan and clashes with editing, the fixed plan, the review, raising
a change for a problem, a problem's changes, history text). Total: 363 unit + 259 integration
backend tests, 89 frontend tests. The `change` package is at 100% line and 92.0% branch coverage;
the backend overall at 96.6% and 88.4%.
