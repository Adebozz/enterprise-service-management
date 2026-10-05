# Enterprise Service Management Platform

[![CI](https://github.com/Adebozz/enterprise-service-management/actions/workflows/ci.yml/badge.svg)](https://github.com/Adebozz/enterprise-service-management/actions/workflows/ci.yml)

A full-stack IT service management platform, in the spirit of a focused ServiceNow / Jira Service
Management: employees raise incidents and service requests, and support teams triage, assign,
investigate and resolve them under explicit workflows, SLAs and a complete audit trail.

> **Status: Phase 1 (MVP) in progress.** This README describes what is actually built. Planned
> work is listed separately and labelled as planned.

## What exists today

| Area | State |
|---|---|
| Monorepo, Spring Boot 4.1 / Java 21 backend, Maven Wrapper | Done (M0) |
| PostgreSQL 17 + Flyway migration pipeline | Done (M0) |
| Consistent RFC 9457 error responses (`application/problem+json`) | Done (M0) |
| Request correlation IDs in logs and responses | Done (M0) |
| Actuator health / liveness / readiness (public), everything else closed | Done (M0) |
| Testcontainers integration tests against real PostgreSQL | Done (M0) |
| Users & teams: admin APIs, role hierarchy, optimistic locking, last-admin protection | Done (M1) |
| Authentication: JWT access tokens, rotating refresh-token cookie with reuse detection, logout, password change, first-admin bootstrap | Done (M2) |
| Tickets: incidents & service requests (JPA JOINED inheritance), race-free references, configurable priority matrix, category-based routing, ownership/team visibility | Done (M3) |
| Append-only audit trail (same-transaction, DB-trigger enforced) | Done (M3) |
| Workflow engine: lifecycles as data, actor-based permissions, requirements, lifecycle timestamps, available-transitions endpoint | Done (M4) |
| Assignment: take/release/assign/transfer with role-and-team rules, status follows ownership, DB-enforced membership rules | Done (M5) |
| Comments & internal notes (filtered in SQL), first-response tracking, ticket history timeline | Done (M6) |
| Queues & search: views, filters, paging, PostgreSQL full-text search, visibility in SQL, index regression tests ([performance notes](docs/performance.md)) | Done (M7) |
| API contract: OpenAPI 3.1 snapshot with typed errors and exact nullability, guarded by a contract test; [API reference](docs/api.md) | Done (M8) |
| Web app foundation: React 19 + TypeScript (strict), typed client generated from the contract, in-memory access token with single-flight silent refresh, protected routes, sign-in/out | Done (M9) |
| Requester portal: my tickets (URL-driven filter/paging), report a problem, request something, ticket page with conversation and server-driven actions | Done (M10) |
| Agent portal: queues (mine/team/unassigned/all) with search, filters and priority sort; take/release/assign/transfer; resolve/fulfil; internal notes; readable history; route-level code splitting | Done (M11) |
| Docker: multi-stage non-root images, nginx single-origin proxy with CSP, health-ordered compose, demo data through the real services | Done (M12) |
| CI (GitHub Actions): backend verify with Testcontainers and coverage floors, frontend lint/types/contract/tests/build, Docker images + end-to-end smoke test; Dependabot | Done (M13) |
| Final docs and screenshots | Planned for Phase 1, see [Roadmap](#roadmap) |

## Tech stack

**Backend:** Java 21, Spring Boot 4.1 (Web MVC, Data JPA/Hibernate 7, Security 7, Validation,
Actuator), PostgreSQL 17, Flyway, springdoc-openapi.
**Testing:** JUnit 5, AssertJ, Mockito, Spring Boot Test, Testcontainers, JaCoCo.
**Frontend:** React 19, TypeScript 5.9 (strict), Vite 8, Tailwind CSS 4, shadcn/ui (Radix), React
Router 7, TanStack Query 5, React Hook Form + Zod 4, openapi-fetch + openapi-typescript; tested with
Vitest, React Testing Library and MSW.
**Infrastructure:** Docker (multi-stage builds), Docker Compose, nginx, GitHub Actions, Dependabot.
**Planned:** AWS (ECS Fargate, RDS, S3, CloudFront), Terraform.

## Repository layout

```
enterprise-service-management/
├── backend/            Spring Boot modular monolith (see docs/architecture.md)
├── frontend/           React + TypeScript web app (see frontend/README.md)
├── infrastructure/     Terraform for AWS (Phase 3)
├── docs/               Architecture, database, security, ADRs, portfolio notes
├── docker/             Compose support files (Postgres init script)
├── docker-compose.yml  Full local stack (postgres, backend, web) or database only
└── .env.example        Local configuration template (copy to .env, never commit .env)
```

## Running locally

### Option 1: the whole system in Docker (quickest)

Prerequisite: **Docker** (Docker Desktop on macOS/Windows). Nothing else.

```bash
cp .env.example .env
docker compose up --build
```

Open **http://localhost:3000** once all three containers report healthy (about 20 seconds after the
first build). The stack starts in health order (PostgreSQL → backend → web) and comes with demo
data: teams, categories, and tickets in every state, each with a real audit history.

**Demo accounts** (password for all: the `ESM_DEMO_PASSWORD` value in `.env`, `Demo-Password-2026`
by default; **local demos only**):

| Email | Role | Try |
|---|---|---|
| `rita@demo.local` | Requester | My tickets, confirm a resolved incident, reply to a question |
| `sam@demo.local` | Requester | Raise an incident or request |
| `nina@demo.local` | Agent (Network) | Queue, take/resolve, internal notes, history |
| `hugo@demo.local` | Agent (Hardware) | Another team's queue |
| `iris@demo.local` | Agent (Identity & Access) | Service requests |
| `lead@demo.local` | Team lead (Network) | Assign to team members |
| `admin@demo.local` | Administrator | Everything |

The stack uses its own database (`esm_demo`), separate from the `esm` database used for IDE
development, so the two never interfere. Details: [docs/deployment.md](docs/deployment.md).

### Option 2: develop with hot reload

Prerequisites: **JDK 21**, **Node 22** and **Docker**.

```bash
cp .env.example .env
docker compose up -d --wait postgres   # database only, on localhost:5432
cd backend && ./mvnw spring-boot:run   # API on http://localhost:8080
```

In a second terminal, start the web app and open http://localhost:5173:

```bash
cd frontend && npm install && npm run dev
```

There's no database setup step. Flyway applies migrations automatically on startup, and the first
admin account is created from the `ESM_BOOTSTRAP_ADMIN_*` values in `.env` (`admin@esm.local`,
local development only).

Backend URLs: health http://localhost:8080/actuator/health, Swagger UI
http://localhost:8080/swagger-ui.html (sign in with `POST /api/auth/login`, then **Authorize**).

Alternative with no docker-compose: `./mvnw spring-boot:test-run` starts the backend against a
throwaway Testcontainers PostgreSQL.

## Testing

```bash
cd frontend && npm test && npm run typecheck && npm run lint
```

```bash
cd backend
./mvnw verify          # unit tests (*Test) + integration tests (*IT) + formatting check + coverage
./mvnw spotless:apply  # auto-format code before committing
```

- **Unit tests** (`*Test`, Surefire) run without Spring or Docker.
- **Integration tests** (`*IT`, Failsafe) start the full application against a real PostgreSQL 17
  container. H2 is deliberately not used, so tests run against the same SQL dialect, constraints
  and migrations as production.
- The coverage report is written to `backend/target/site/jacoco/index.html`. `verify` fails if
  coverage drops below the floors in `backend/pom.xml`: 90% lines / 80% branches overall, and
  90% / 85% for the security- and rule-critical packages (auth, ticket access, assignment,
  priority, transitions, workflow).
- **End-to-end smoke test** of the running Docker stack (nginx headers, `/api` proxy, problem
  responses, demo sign-in, role enforcement through the proxy):

  ```bash
  docker compose up --build --wait && ./scripts/smoke-test.sh
  ```

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request and push to
`main`, as three parallel jobs: **backend** (`./mvnw verify`: formatting, unit and Testcontainers
integration tests, OpenAPI snapshot, coverage floors; reports uploaded as an artifact),
**frontend** (lint, type-check, generated-API-types check, tests, production build) and **stack**
(builds both Docker images, starts the Compose stack and runs the smoke test). Actions are pinned
to commit SHAs with read-only permissions; [Dependabot](.github/dependabot.yml) proposes weekly
updates for Maven, npm, Actions and base images.

## Error responses

Every error, including 401/403 from Spring Security and Spring MVC's own 404/405, uses one shape:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required to access this resource",
  "instance": "/api/incidents",
  "code": "AUTHENTICATION_REQUIRED",
  "timestamp": "2026-10-01T10:14:28.714Z",
  "correlationId": "smoke-test-00001"
}
```

Clients branch on `code`. The `correlationId` matches the `X-Request-Id` response header and every
log line written while handling the request.

## Documentation

- [API reference](docs/api.md) and [OpenAPI contract](docs/openapi.json)
- [Architecture](docs/architecture.md)
- [Database design](docs/database.md)
- [Security model](docs/security.md)
- [Performance notes](docs/performance.md)
- [Deployment & containers](docs/deployment.md)
- Architecture Decision Records: [docs/adr](docs/adr)

## Roadmap

Phase 1 milestones (M0–M14) are listed in [docs/architecture.md](docs/architecture.md#phase-1-milestones).
Phase 2 adds the SLA engine, service catalogue, approvals, problem/change management,
attachments and notifications. Phase 3 adds reporting, load testing and AWS deployment.
