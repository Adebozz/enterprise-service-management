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
| `auth` | Security filter chain, role hierarchy; login, JWT issuing, refresh tokens (M2) | Filter chain + hierarchy (M0–M1) |
| `user` | Users, roles, password hashing, admin user API | Implemented (M1) |
| `team` | Teams, membership, admin team API, team read API | Implemented (M1) |
| `ticket` | Work-item kernel plus `incident`, `request`, `workflow`, `priority`, `assignment`, `comment`, `category`, `query`, `access` sub-packages | Planned (M3–M7) |
| `audit` | Append-only audit trail | Planned (M3) |
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

```mermaid
flowchart LR
    team -->|calls| user
    user -. "UserRoleChangedEvent (same tx)" .-> team
    auth -->|Role| user
    user & team --> common
```

## Request lifecycle (implemented)

1. `CorrelationIdFilter` (highest precedence) accepts a safe `X-Request-Id` or generates one, stores
   it in the SLF4J MDC, and echoes it in the response.
2. The Spring Security filter chain runs. It is stateless, with public health and docs endpoints and
   authentication required for everything else.
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
| `POST /api/admin/users` | ADMIN | 201 + `Location`; 409 `EMAIL_ALREADY_EXISTS` |
| `GET /api/admin/users?q&role&active&page&size&sort` | ADMIN | Paged; sort by `email`, `displayName`, `role`, `createdAt` |
| `GET /api/admin/users/{id}` | ADMIN | |
| `PATCH /api/admin/users/{id}` | ADMIN | `{displayName?, role?, active?, version}`; 409 on stale version or last admin |
| `GET /api/teams`, `GET /api/teams/{id}`, `GET /api/teams/{id}/members` | AGENT+ | Active teams only |
| `GET /api/admin/teams`, `POST /api/admin/teams`, `PATCH /api/admin/teams/{id}` | ADMIN | Includes inactive teams |
| `PUT / DELETE /api/admin/teams/{id}/members/{userId}` | ADMIN | Idempotent, 204 |

## Observability (implemented so far)

- Correlation ID on every log line (`logging.pattern.correlation`) and in every error response.
- Actuator `health`, `health/liveness` and `health/readiness` are public, with no component
  details. Every other actuator endpoint is unexposed.

## Phase 1 milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Bootstrap: monorepo, Spring Boot, Flyway, Compose, error handling, correlation IDs, Testcontainers | **Done** |
| M1 | Users & teams, role hierarchy, admin APIs | **Done** |
| M2 | Authentication: login, JWT, refresh-token rotation, `/users/me`, bootstrap admin | Next |
| M3 | Ticket core + audit foundation | |
| M4 | Workflow engine | |
| M5 | Assignment & routing | |
| M6 | Comments & internal notes, history endpoint | |
| M7 | Queue, filtering, full-text search | |
| M8 | OpenAPI polish, `docs/api.md` | |
| M9 | Frontend foundation (auth, layout, generated API types) | |
| M10 | Requester portal | |
| M11 | Agent portal | |
| M12 | Full Docker Compose, production Dockerfiles, seed data | |
| M13 | GitHub Actions CI | |
| M14 | Documentation, ADRs, screenshots | |
