# Enterprise Service Management Platform

A full-stack IT service management platform, in the spirit of a focused ServiceNow / Jira Service
Management: employees raise incidents and service requests, and support teams triage, assign,
investigate and resolve them under explicit workflows, SLAs and a complete audit trail.

> **Status: Phase 1 (MVP) in progress.** This README describes what is actually built. Planned
> work is listed separately and labelled as planned.

## What exists today

| Area | State |
|---|---|
| Monorepo, Spring Boot 4.1 / Java 21 backend, Maven Wrapper | Done (M0) |
| PostgreSQL 17 + Flyway migration pipeline | Done (M0), baseline migration only |
| Consistent RFC 9457 error responses (`application/problem+json`) | Done (M0) |
| Request correlation IDs in logs and responses | Done (M0) |
| Actuator health / liveness / readiness (public), everything else closed | Done (M0) |
| Testcontainers integration tests against real PostgreSQL | Done (M0) |
| Users, teams, JWT auth, incidents, workflow, assignment, comments, audit, React UI | Planned for Phase 1, see [Roadmap](#roadmap) |

## Tech stack

**Backend:** Java 21, Spring Boot 4.1 (Web MVC, Data JPA/Hibernate 7, Security 7, Validation,
Actuator), PostgreSQL 17, Flyway, springdoc-openapi.
**Testing:** JUnit 5, AssertJ, Mockito, Spring Boot Test, Testcontainers, JaCoCo.
**Planned:** React + TypeScript (Vite, TanStack Query, React Hook Form, Zod), Docker, GitHub
Actions, AWS (ECS Fargate, RDS, S3, CloudFront), Terraform.

## Repository layout

```
enterprise-service-management/
├── backend/            Spring Boot modular monolith (see docs/architecture.md)
├── frontend/           React + TypeScript SPA (from M9)
├── infrastructure/     Terraform for AWS (Phase 3)
├── docs/               Architecture, database, security, ADRs, portfolio notes
├── docker-compose.yml  Local PostgreSQL (backend/frontend services added in M12)
└── .env.example        Local configuration template (copy to .env, never commit .env)
```

## Running locally

Prerequisites: **JDK 21** and **Docker** (Docker Desktop on macOS/Windows).

```bash
cp .env.example .env            # local-only settings; .env is git-ignored
docker compose up -d --wait     # PostgreSQL 17 on localhost:5432
cd backend
./mvnw spring-boot:run          # API on http://localhost:8080
```

Useful URLs:

- Health: http://localhost:8080/actuator/health
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Swagger UI: http://localhost:8080/swagger-ui.html

There's no database setup step. Flyway applies migrations automatically on startup.

Alternative with no docker-compose: `./mvnw spring-boot:test-run` starts the app against a
throwaway Testcontainers PostgreSQL.

## Testing

```bash
cd backend
./mvnw verify          # unit tests (*Test) + integration tests (*IT) + formatting check + coverage
./mvnw spotless:apply  # auto-format code before committing
```

- **Unit tests** (`*Test`, Surefire) run without Spring or Docker.
- **Integration tests** (`*IT`, Failsafe) start the full application against a real PostgreSQL 17
  container. H2 is deliberately not used, so tests run against the same SQL dialect, constraints
  and migrations as production.
- The coverage report is written to `backend/target/site/jacoco/index.html`.

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

- [Architecture](docs/architecture.md)
- [Database design](docs/database.md)
- [Security model](docs/security.md)
- Architecture Decision Records: [docs/adr](docs/adr)

## Roadmap

Phase 1 milestones (M0–M14) are listed in [docs/architecture.md](docs/architecture.md#phase-1-milestones).
Phase 2 adds the SLA engine, service catalogue, approvals, problem/change management,
attachments and notifications. Phase 3 adds reporting, load testing and AWS deployment.
