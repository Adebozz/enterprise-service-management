# Architecture

> Sections marked **(planned)** describe approved design that isn't implemented yet. They're updated
> as each milestone lands.

## Style: modular monolith

One deployable Spring Boot application, split internally into domain modules with explicit
boundaries. See [ADR-001](adr/001-modular-monolith.md).

```mermaid
flowchart LR
    Browser -->|HTTPS| Edge["CloudFront (prod) / nginx or Vite proxy (local)"]
    Edge -->|"/*"| SPA["React SPA (static files)"]
    Edge -->|"/api/*"| API["Spring Boot API<br/>(ECS Fargate in prod)"]
    API --> DB[(PostgreSQL 17<br/>RDS in prod)]
    API --> S3[(S3 attachments<br/>Phase 2)]
    API -.secrets.-> SM[Secrets Manager]
```

**The SPA and API share one origin.** `/api/*` is routed to the backend by the edge (CloudFront in
production, the Vite dev proxy or nginx locally). This removes CORS from production and lets the
refresh-token cookie be `SameSite=Strict`.

## Backend modules

Base package: `com.ademola.esm`

| Module | Responsibility | Status |
|---|---|---|
| `common` | Error model (`ErrorCode`, `DomainException`, `GlobalExceptionHandler`), web (`CorrelationIdFilter`, `PageResponse`, `SortableFields`), persistence (`BaseEntity`), injectable `Clock` | Implemented (M0–M1) |
| `config` | Cross-cutting Spring configuration (OpenAPI, later Jackson/web) | Implemented (M0) |
| `auth` | Security filter chain, role hierarchy, JWT issue/validation, refresh-token rotation, login/refresh/logout, `/api/users/me` | Implemented (M0–M2) |
| `user` | Users, roles, password hashing and change, first-admin bootstrap, admin user API | Implemented (M1–M2) |
| `team` | Teams, membership, admin team API, team read API | Implemented (M1) |
| `ticket` | Work-item kernel (`WorkItem`, references) plus `priority`, `category`, `intake`, `incident`, `request`, `access`, `query`, `workflow` (engine), `transition` (API), `assignment` sub-packages; `comment` to come | Implemented (M3–M5) |
| `audit` | Append-only audit trail; `ActorProvider` port implemented by `auth` | Implemented (M3) |
| `sla`, `catalogue`, `approval`, `attachment`, `notification` | Phase 2 | Planned |
| `reporting` | Read-only aggregate queries | Planned (Phase 3) |

### Module conventions

- **Package by feature, not by layer.** Each module holds its own controller, service, repository,
  entities and DTOs. Classes are package-private where possible, so the compiler enforces
  boundaries.
- **Controllers are thin.** They validate input, call one service method, and map the result to a
  response DTO. Business rules live in services and entities.
- **JPA entities never leave the service layer.** Controllers return Java `record` DTOs.
- **No Lombok or MapStruct.** Records and explicit mapping methods keep the code transparent.
- **Modules reference each other's aggregates by ID**, not JPA associations. For example,
  `TeamMember` stores a `userId`.
- **Dependencies point one way, and events carry the reverse direction.** `team` depends on `user`.
  When `user` needs `team` to react (a user demoted to REQUESTER must leave all teams), it publishes
  `UserRoleChangedEvent` and `team` listens. Listeners are synchronous and run in the same
  transaction, so the change is atomic. Phase 2 uses the same pattern for ticket → SLA.

Inside `ticket`, packages form a one-way graph (no cycles):

```mermaid
flowchart LR
    transition --> query & access & core
    assignment --> access & core
    query --> incident & request & access & category
    incident & request --> intake
    intake --> category & priority & core["ticket (WorkItem)"]
    incident & request & access --> core
    core --> priority & workflow
```

Across modules:

```mermaid
flowchart LR
    ticket --> team & user & auth & audit
    auth -. implements ActorProvider .-> audit
    team -->|calls| user
    user -. "UserRoleChangedEvent (same tx)" .-> team
    auth -->|calls| user
    auth -->|teamsOf| team
    user -. "PasswordChangedEvent (same tx)" .-> auth
    user & team --> common
```

## Request lifecycle (implemented)

1. `CorrelationIdFilter` (highest precedence) accepts a safe `X-Request-Id` or generates one, stores
   it in the SLF4J MDC, and echoes it in the response.
2. The Spring Security filter chain runs. `BearerTokenAuthenticationFilter` verifies the JWT, and
   `CurrentUserJwtConverter` turns its claims into a `CurrentUser` principal. URL rules then apply
   (public: health, docs, `/api/auth/*`; ADMIN: `/api/admin/**`; everything else needs
   authentication).
3. Spring MVC dispatches to a controller.
4. Any exception, including authentication and access-denied failures delegated from Spring
   Security, is rendered by `GlobalExceptionHandler` as `application/problem+json`.

## Configuration

- 12-factor: environment variables (`ESM_DB_URL`, `ESM_DB_USERNAME`, `ESM_DB_PASSWORD`, ...).
- Locally, Spring imports the git-ignored repository-root `.env` (`spring.config.import`), so
  docker-compose and the backend read the same single file.
- The `prod` profile switches to JSON (ECS) structured logs and disables API docs.
- `spring.jpa.open-in-view=false` stops lazy loading during JSON rendering, which would hide N+1
  queries.
- `ddl-auto=validate`: Flyway owns the schema, and Hibernate only verifies it.

## API (implemented so far)

| Method & path | Who | Notes |
|---|---|---|
| `POST /api/auth/login` | anyone | `{email, password}` → `{accessToken, tokenType, expiresIn, user}` + refresh cookie |
| `POST /api/auth/refresh` | refresh cookie | Rotates the cookie, returns a new access token |
| `POST /api/auth/logout` | refresh cookie | 204; revokes the session family, clears the cookie |
| `GET /api/users/me` | any signed-in user | Profile + active teams |
| `POST /api/users/me/password` | any signed-in user | `{currentPassword, newPassword}`; 204; ends all sessions |
| `POST /api/admin/users` | ADMIN | 201 + `Location`; 409 `EMAIL_ALREADY_EXISTS` |
| `GET /api/admin/users?q&role&active&page&size&sort` | ADMIN | Paged; sort by `email`, `displayName`, `role`, `createdAt` |
| `GET /api/admin/users/{id}` | ADMIN | |
| `PATCH /api/admin/users/{id}` | ADMIN | `{displayName?, role?, active?, version}`; 409 on stale version or last admin |
| `GET /api/teams`, `GET /api/teams/{id}`, `GET /api/teams/{id}/members` | AGENT+ | Active teams only |
| `GET /api/admin/teams`, `POST /api/admin/teams`, `PATCH /api/admin/teams/{id}` | ADMIN | Includes inactive teams |
| `PUT / DELETE /api/admin/teams/{id}/members/{userId}` | ADMIN | Idempotent, 204 |

### Tickets & categories (M3)

| Method & path | Who | Notes |
|---|---|---|
| `POST /api/incidents` | any signed-in user | `{title, description, categoryId, subcategoryId?, impact, urgency, affectedService?}` → 201 `{id, reference, type, status, priority}` + `Location: /api/tickets/{id}` |
| `POST /api/service-requests` | any signed-in user | `{title, description, categoryId, subcategoryId?, urgency?}`; impact defaults LOW, urgency MEDIUM |
| `GET /api/tickets/{id}` | owner / team staff / admin | Full ticket with names resolved; **404** if not visible |
| `GET /api/categories?type=INCIDENT\|SERVICE_REQUEST` | any signed-in user | Active category tree for that type |
| `GET/POST /api/admin/categories`, `PATCH /api/admin/categories/{id}` | ADMIN | Code is immutable; top level needs a team; max two levels |

### Workflow (M4)

| Method & path | Who | Notes |
|---|---|---|
| `GET /api/tickets/{id}/transitions` | anyone who can see the ticket | Moves available to *this caller* now: `[{targetStatus, label, requirements}]`. The UI renders buttons from it |
| `POST /api/tickets/{id}/transitions` | depends on the move | `{targetStatus, version, reason?, resolutionCode?, notes?}` → updated ticket |

### Assignment (M5)

| Method & path | Who | Notes |
|---|---|---|
| `PUT /api/tickets/{id}/assignment` | AGENT+ (see rules) | `{teamId, assigneeId?, version}` → `{ticketId, reference, status, assignedTeam, assignee, version}`. Same values = no-op |

## Ticket intake (M3)

Every new ticket, of any type, goes through `TicketIntake`, in one transaction:

1. **Category check:** active, top-level, applicable to the type; the subcategory must belong to it.
   Failures are 422 `INVALID_CATEGORY` (never 404, which would allow probing for ids).
2. **Routing:** subcategory team ?? category team; that team must be active.
3. **Priority:** `PriorityPolicy` (below).
4. **Reference:** `nextval` on the type's sequence → `INC-000042`.
5. Save, then **audit** `TICKET_CREATED` in the same transaction.

### Priority matrix

Configured in `application.yml` (`esm.priority.matrix`), bound to a validated record. Startup
fails if any cell is missing. `PriorityPolicy` is the only code that maps impact/urgency to
priority.

| Impact \ Urgency | HIGH | MEDIUM | LOW |
|---|---|---|---|
| **HIGH** | P1 Critical | P2 High | P3 Medium |
| **MEDIUM** | P2 High | P3 Medium | P4 Low |
| **LOW** | P3 Medium | P4 Low | P4 Low |

## Workflow engine (M4)

Lifecycles are **data** (`WorkflowDefinition`): every allowed `from → to` move, its UI label, the
**actors** who may make it, and its **requirements**. Undeclared moves are impossible. See
[ADR-007](adr/007-workflow-as-data.md).

| Layer | Enforces | Error |
|---|---|---|
| `TicketTransitionService` | caller can see the ticket | 404 |
| | client's `version` is current | 409 `CONCURRENT_MODIFICATION` |
| | move exists in the lifecycle | 409 `INVALID_STATUS_TRANSITION` |
| | caller's actor may make it | 403 `TRANSITION_NOT_PERMITTED` |
| `WorkItem.transition()` (entity) | move exists (again: a domain invariant for every caller) | 409 |
| | requirements: reason / resolution / fulfilment notes / assignee | 422 |
| | side effects: first response, resolved/closed timestamps, reopen count | n/a |

**Actors** are relationships to the ticket: `REQUESTER` (raised it), `SUPPORT` (assignee or
assigned-team member; admins everywhere), `ADMIN`, `SYSTEM` (never granted via the API).

**Incident** lifecycle:

```mermaid
stateDiagram-v2
    [*] --> NEW
    NEW --> ASSIGNED: Assign (system)
    ASSIGNED --> NEW: Unassign (system)
    IN_PROGRESS --> NEW: Return to queue (system)
    WAITING_FOR_USER --> NEW: Return to queue (system)
    ASSIGNED --> IN_PROGRESS: Start work (support, needs assignee)
    IN_PROGRESS --> WAITING_FOR_USER: Wait for user (support, reason)
    WAITING_FOR_USER --> IN_PROGRESS: Resume (support/requester)
    IN_PROGRESS --> RESOLVED: Resolve (support, code + notes)
    RESOLVED --> IN_PROGRESS: Reopen (requester/support, reason)
    RESOLVED --> CLOSED: Confirm and close (requester/admin/system)
    NEW --> CANCELLED: Cancel (reason)
    ASSIGNED --> CANCELLED
    IN_PROGRESS --> CANCELLED
    WAITING_FOR_USER --> CANCELLED
    CLOSED --> [*]
    CANCELLED --> [*]
```

**Service request** lifecycle:

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED
    SUBMITTED --> IN_PROGRESS: Start fulfilment (support, needs assignee)
    SUBMITTED --> APPROVAL_PENDING: Request approval (system, Phase 2)
    APPROVAL_PENDING --> APPROVED: system
    APPROVAL_PENDING --> REJECTED: system
    APPROVED --> IN_PROGRESS: Start fulfilment
    IN_PROGRESS --> FULFILLED: Fulfil (support, notes)
    IN_PROGRESS --> SUBMITTED: Return to queue (system)
    FULFILLED --> IN_PROGRESS: Reopen (reason)
    FULFILLED --> CLOSED: Confirm and close (requester/admin/system)
    SUBMITTED --> CANCELLED: Cancel (reason)
    APPROVAL_PENDING --> CANCELLED
    APPROVED --> CANCELLED
    IN_PROGRESS --> CANCELLED
```

Timestamps: `first_responded_at` is set the first time support starts work and never moves;
`resolved_at` is set on RESOLVED/FULFILLED and cleared on reopen; `closed_at` is set on any terminal
state (CLOSED, CANCELLED, REJECTED). Resolution codes for incidents are a fixed list
(`FIXED, WORKAROUND, NO_FAULT_FOUND, DUPLICATE, USER_ERROR, NOT_REPRODUCIBLE`) for reporting.

> **Status codes:** the original spec's example used 400 for an invalid transition. We return
> **409 Conflict**: the request is well-formed but conflicts with the ticket's current state.

## Assignment (M5)

`WorkItem.assign()` is the only way ownership changes. `AssignmentPolicy` (pure, unit-tested)
decides who may do what; admins may do anything:

| Change | Allowed for |
|---|---|
| Take an **unassigned** ticket in my team (assignee = me) | any staff member of that team |
| Release my own ticket | its current assignee |
| Assign/reassign to someone else in the team | TEAM_LEAD of that team |
| Transfer to another team, unassigned | support on the ticket (team member or assignee) |
| Transfer and choose the new assignee | TEAM_LEAD of the target team |

Validation: the target team is active (422 `TEAM_INACTIVE`); the assignee is an active staff
member of it (422 `ASSIGNEE_NOT_IN_TEAM`); the ticket isn't resolved, fulfilled or terminal (409
`TICKET_NOT_ASSIGNABLE`).

**Status follows ownership through SYSTEM-only workflow moves**, applied by the entity:

| Ownership change | Incident | Service request |
|---|---|---|
| gains an owner while NEW | NEW → ASSIGNED | (no change) |
| loses its owner | ASSIGNED / IN_PROGRESS / WAITING_FOR_USER → NEW | IN_PROGRESS → SUBMITTED |
| owner replaced | unchanged | unchanged |

Users can't reach these statuses via `/transitions`, because the API never grants the SYSTEM
actor. Work in progress therefore always has an owner. The response is a compact
`AssignmentResponse`, because after a transfer the caller may no longer be allowed to see the
ticket.

Removing a member (or demoting them to REQUESTER) while they own open tickets in that team is
refused by the database and reported as 409 `MEMBER_HAS_OPEN_TICKETS`; a demotion is rolled back
as a whole.

## Audit trail (M3)

- `AuditService.record()` uses `Propagation.MANDATORY`: callable only inside the business
  transaction, so a change and its audit entry commit or roll back together (tested: a failed
  duplicate-email create leaves no audit row).
- The actor comes from `ActorProvider` (implemented by `auth` from the JWT principal; empty for
  system actions such as the admin bootstrap). The correlation id comes from the MDC.
- Audited so far: user created/updated/password changed, team created/updated/member
  added/removed, category created/updated, ticket created. Values hold only changed fields and never
  secrets.
- Append-only at three levels: `@Immutable` entity, a repository with no update/delete methods, and
  a database trigger.

## Observability (implemented so far)

- Correlation ID on every log line (`logging.pattern.correlation`) and in every error response.
- Actuator `health`, `health/liveness` and `health/readiness` are public, with no component
  details. Every other actuator endpoint is unexposed.

## Phase 1 milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Bootstrap: monorepo, Spring Boot, Flyway, Compose, error handling, correlation IDs, Testcontainers | **Done** |
| M1 | Users & teams, role hierarchy, admin APIs | **Done** |
| M2 | Authentication: login, JWT, refresh-token rotation, `/users/me`, bootstrap admin | **Done** |
| M3 | Ticket core + audit foundation | **Done** |
| M4 | Workflow engine | **Done** |
| M5 | Assignment & routing | **Done** |
| M6 | Comments & internal notes, history endpoint | Next |
| M7 | Queue, filtering, full-text search | |
| M8 | OpenAPI polish, `docs/api.md` | |
| M9 | Frontend foundation (auth, layout, generated API types) | |
| M10 | Requester portal | |
| M11 | Agent portal | |
| M12 | Full Docker Compose, production Dockerfiles, seed data | |
| M13 | GitHub Actions CI | |
| M14 | Documentation, ADRs, screenshots | |
