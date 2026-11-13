# Enterprise Service Management Platform

[![CI](https://github.com/Adebozz/enterprise-service-management/actions/workflows/ci.yml/badge.svg)](https://github.com/Adebozz/enterprise-service-management/actions/workflows/ci.yml)

A full-stack IT service desk in the spirit of a focused ServiceNow / Jira Service Management.
Employees report incidents and request things; support teams triage, assign, investigate and
resolve them under explicit workflows, with internal notes and a complete, tamper-resistant audit
trail.

**Java 21 · Spring Boot 4.1 · PostgreSQL 17 · React 19 + TypeScript · Docker · GitHub Actions**

> **Status: Phase 1 (MVP) complete; Phase 2 under way, with the SLA engine, service catalogue, approvals, problem management and change management done.** Everything
> described here is built and tested. Planned work is listed separately under [Roadmap](#roadmap)
> and labelled as planned.

![Agent view of a ticket: internal note, conversation, assignment panel and history](docs/screenshots/06-agent-ticket.png)

## Try it in one command

Prerequisite: Docker.

```bash
cp .env.example .env && docker compose up --build
```

Open **http://localhost:3000** and sign in with a demo account. The password for every account is
the `ESM_DEMO_PASSWORD` value in `.env` (local demos only).

| Email | Role | Try |
|---|---|---|
| `rita@demo.local` | Requester | My tickets, answer the agent's question, confirm a resolved incident |
| `sam@demo.local` | Requester | Report a problem or request something |
| `nina@demo.local` | Agent (Network) | Queue, take/resolve, internal notes, history, the VPN known error under *Problems*, its fix under *Changes* |
| `hugo@demo.local` | Agent (Hardware) | Another team's queue, a standard change that clashes with Nina's |
| `iris@demo.local` | Agent (Identity & Access) | Service requests |
| `lead@demo.local` | Team lead (Network, Identity & Access) | Assign work to team members, approve a shared drive request and the VPN licence change |
| `admin@demo.local` | Administrator | Every ticket, service catalogue, SLA targets |

The demo data is created through the real application services on first start: 7 people,
3 teams, 12 categories, 3 catalogue items and 14 tickets across their lifecycles (a request and a
change waiting for approval, a known-error problem explaining two incidents, and changes scheduled,
clashing and reviewed), each with a genuine audit history. Demo data is created only once, so a database from before an update keeps
its old data; `docker compose down -v` then `up` starts again with the current demo.

## What it does

**For requesters**
- Report a problem (incident), or order from the service catalogue: each item ("New laptop",
  "Shared drive access") asks only its own questions. Anything not listed can still be requested
  in free text. Categories route every ticket to the right team automatically.
- Items that need sign-off (access to data, for example) wait for a team lead's approval, and the
  requester sees who decided and why.
- Priority is derived from impact × urgency through a configurable matrix, never typed in.
- Follow their own tickets, reply in the conversation, confirm a resolution or reopen it.

**For support staff**
- Queues: *mine*, *team*, *unassigned* and *all*, with full-text search, filters and priority-first
  sorting. All of it is URL-driven, so every view can be bookmarked.
- Take, release, assign (team leads) and transfer tickets between teams.
- Workflow actions with the inputs each move requires: a reason to cancel or reopen, a resolution
  code and notes to resolve, delivery notes to fulfil.
- Internal notes that requesters never see. The filtering happens in SQL, not in the browser.
- A readable history of every change, built from the audit trail.
- Team leads approve or reject requests for their teams, from an *Approvals* inbox or the ticket
  itself; nobody approves their own request, and a rejection must say why.
- Problem management: raise a problem from a recurring incident, record its root cause and
  workaround (a *known error*), and link incidents to it. Agents handling a linked incident see
  the workaround on it, and fixing the problem leaves a note on every incident still open.
- Change management: plan a change (type, risk, implementation and backout plans, window), have
  it approved by a team lead (standard changes are pre-approved), carry it out and record how it
  went. A schedule shows what is coming up, and each change warns about others in the same window.
- Service levels: every ticket has a response and a resolution target by type and priority.
  Queues show each ticket as on track, at risk, breached or paused and can sort by the next
  deadline; the ticket page shows both clocks. Waiting for the requester pauses the clock, and
  their reply resumes the ticket automatically.

**For administrators**
- Maintain the service catalogue: add items, write their questions (short answer, paragraph,
  choice or date), choose the category that routes them, mark items that need approval, and
  withdraw items. Requests already
  raised keep the questions as they were asked.
- Set the SLA targets for each ticket type and priority; changes apply to tickets raised afterwards.

| Requester | Agent |
|---|---|
| ![Requester's list of their tickets](docs/screenshots/02-requester-my-tickets.png) | ![Agent queue with priority, status, assignee and team](docs/screenshots/05-agent-queue.png) |
| ![The same ticket as the requester sees it: no internal note](docs/screenshots/04-requester-ticket.png) | ![A workflow action asking for its required reason](docs/screenshots/07-agent-action-dialog.png) |

More: [sign-in](docs/screenshots/01-sign-in.png) · [reporting a problem](docs/screenshots/03-requester-new-incident.png)

## Engineering highlights

- **The backend enforces every rule.** Role checks, ticket visibility, workflow permissions and
  assignment rules live in the services and the database. The frontend only hides what the
  server would refuse anyway. A ticket the caller may not see returns 404, not 403, so its
  existence isn't leaked.
- **Workflows are data.** Each lifecycle is a declarative table of moves: who may make each one
  and what it requires. The entity enforces it, the API publishes the moves available to the
  caller right now, and the UI renders buttons from that list. Because the UI never encodes the
  workflow, it can't drift from the backend ([ADR-007](docs/adr/007-workflow-as-data.md)).
- **Authentication:**
  - short-lived JWT access tokens, kept in memory only;
  - opaque refresh tokens in an HttpOnly, SameSite=Strict cookie, stored as SHA-256 hashes and
    rotated on every use;
  - reuse detection that revokes the whole session family;
  - timing-safe login ([security model](docs/security.md)).
- **Audit trail you can trust.** Audit entries are written in the same transaction as the change
  (`Propagation.MANDATORY`), and a database trigger rejects any update or delete
  ([ADR-006](docs/adr/006-transactional-audit-trail.md)).
- **Correctness under concurrency:**
  - optimistic locking (the client sends the version it saw) on users, teams, categories and
    ticket changes;
  - pessimistic locks where a check-then-act would race (last admin, refresh-token rotation);
  - per-type sequences for references (`INC-000042`);
  - database triggers for team-membership rules.
- **Queues on a hand-written SQL read model.** Visibility is part of the WHERE clause, and search
  uses PostgreSQL full-text search with a GIN index. Index use is verified by plan tests on 100k
  realistic rows ([ADR-009](docs/adr/009-sql-read-model-for-queues.md),
  [performance notes](docs/performance.md)).
- **A typed contract end to end.** The OpenAPI 3.1 contract has exact nullability and typed errors.
  It's snapshot-tested against the code, and the frontend's TypeScript types are generated from it,
  so CI fails if either side drifts.
- **One error shape** everywhere: RFC 9457 problem details with a stable `code` and a
  `correlationId` that matches the logs.
- **SLA clocks that survive pauses, reopening and retargeting.** Each clock stores the time used in
  finished stretches plus the current one, and records breaches permanently. Ticket changes reach
  the `sla` module as events in the same transaction, so `ticket` never depends on it. A scheduled
  job claims overdue clocks with `FOR UPDATE SKIP LOCKED`, so several instances never record a
  breach twice ([ADR-010](docs/adr/010-sla-clocks.md)).
- **Catalogue forms as data, answers as a snapshot.** Each item's questions are JSON checked by one
  pure class that reports every problem per question. A request keeps its answers with the
  questions worded as they were asked, so editing a form never rewrites old requests. Ordering
  raises an ordinary service request, so routing, workflow and SLAs need no special cases
  ([ADR-011](docs/adr/011-catalogue-forms-as-data.md)).
- **A new ticket type in one migration.** Problems reuse the shared ticket model, so queues,
  assignment, comments, history and search needed no changes. The compiler flagged every place
  that had to decide what a problem means (SLA phases, labels), and a pre-transition hook keeps a
  refused move from leaving the ticket half-changed ([ADR-012](docs/adr/012-problems-as-a-ticket-type.md)).
- **Reuse without a dependency cycle.** Changes are approved through the same approvals as
  requests, but the approvals module already depends on tickets. Submitting a change is an ordinary
  transition, and the approvals module opens the approval when it hears of it, in the same
  transaction ([ADR-013](docs/adr/013-changes-and-approval-by-event.md)).

## Architecture

```mermaid
flowchart LR
    browser["Browser<br/>React 19 SPA"] -->|"same origin"| nginx["nginx<br/>static app · /api proxy<br/>security headers, CSP"]
    nginx -->|"/api"| api["Spring Boot 4.1<br/>modular monolith"]
    api -->|"JPA for writes<br/>JDBC read model for queues"| db[("PostgreSQL 17<br/>Flyway migrations")]

    subgraph modules["Backend modules (package by feature)"]
        direction TB
        auth["auth"] --- user["user"] --- team["team"]
        ticket["ticket<br/>intake · workflow · assignment<br/>comments · queues · history"] --- audit["audit"]
    end
    api -.- modules
```

A **modular monolith**, packaged by feature. Modules refer to each other's aggregates by ID, and the
dependency graph is one-way: cycles are broken with synchronous domain events and dependency
inversion ([ADR-001](docs/adr/001-modular-monolith.md)). Controllers are thin and never expose JPA
entities; business rules live in services and entities. The full picture is in
[docs/architecture.md](docs/architecture.md).

## Tech stack

**Backend:** Java 21, Spring Boot 4.1 (Web MVC, Data JPA/Hibernate 7, Security 7, Validation,
Actuator), PostgreSQL 17, Flyway, springdoc-openapi.
**Frontend:** React 19, TypeScript 5.9 (strict), Vite 8, Tailwind CSS 4, shadcn/ui (Radix), React
Router 7, TanStack Query 5, React Hook Form + Zod 4, openapi-fetch + openapi-typescript.
**Testing:** JUnit 5, AssertJ, Mockito, Spring Boot Test, Testcontainers, JaCoCo; Vitest, React
Testing Library, MSW.
**Delivery:** Docker (multi-stage, non-root images), Docker Compose, nginx, GitHub Actions,
Dependabot.

## Testing and CI

| Suite | What it covers |
|---|---|
| Backend unit tests (`*Test`) | Domain rules: workflows, priority matrix, access policy, assignment, tokens |
| Backend integration tests (`*IT`) | The real application against real PostgreSQL 17 in Testcontainers (no H2): HTTP → security → services → SQL constraints and triggers, query plans, the API contract snapshot |
| Frontend tests | The real app and routes against a mocked API (MSW): sign-in and session refresh, requester and agent flows, validation, error handling |
| Smoke test | The running Docker stack through nginx: headers, proxy, error format, demo sign-in, role enforcement |

```bash
cd backend && ./mvnw verify          # format check, unit + integration tests, contract, coverage floors
cd frontend && npm test && npm run typecheck && npm run lint
docker compose up --build --wait && ./scripts/smoke-test.sh
```

`verify` fails if coverage drops below 90% lines and 80% branches overall, or below 90% / 85% in the
security- and rule-critical packages (auth, ticket access, assignment, priority, transitions,
workflow).

[CI](.github/workflows/ci.yml) runs all of this on every pull request and push to `main`, as three
parallel jobs:
- **backend:** `./mvnw verify`;
- **frontend:** lint, types, the generated-API-types check, tests and a production build;
- **stack:** builds both images, starts Compose and runs the smoke test.

Actions are pinned to commit SHAs and the workflow token is read-only.
[Dependabot](.github/dependabot.yml) proposes weekly dependency updates.

## Developing locally

Prerequisites: JDK 21, Node 22 and Docker.

```bash
cp .env.example .env
docker compose up -d --wait postgres   # database only, on localhost:5432
cd backend && ./mvnw spring-boot:run   # API on http://localhost:8080
```

```bash
cd frontend && npm install && npm run dev   # web app on http://localhost:5173
```

Flyway migrates the database on start-up. The first admin account is created from the
`ESM_BOOTSTRAP_ADMIN_*` values in `.env` (local development only). Swagger UI is at
http://localhost:8080/swagger-ui.html. `./mvnw spring-boot:test-run` starts the backend against a
throwaway Testcontainers database instead. Run `./mvnw spotless:apply` before committing backend
code.

## Repository layout

```
enterprise-service-management/
├── backend/             Spring Boot application, migrations, tests
├── frontend/            React + TypeScript web app, nginx config (see frontend/README.md)
├── docs/                Architecture, database, security, API, ADRs, screenshots
├── scripts/             End-to-end smoke test
├── docker/              Compose support files (Postgres init script)
├── .github/             CI workflow and Dependabot
├── docker-compose.yml   Full local stack, or the database alone
└── .env.example         Local configuration template (copy to .env, never commit .env)
```

## Documentation

- [Architecture](docs/architecture.md) · [Database design](docs/database.md) ·
  [Security model](docs/security.md) · [Performance notes](docs/performance.md)
- [API reference](docs/api.md) and the [OpenAPI contract](docs/openapi.json)
- [Deployment & containers](docs/deployment.md)
- [Architecture Decision Records](docs/adr)
- [Portfolio notes](docs/portfolio-notes.md): what was built in each milestone, and why

## Roadmap

**Phase 1 (done):** everything above except service levels. The milestone list is in
[docs/architecture.md](docs/architecture.md#phase-1-milestones).

**Phase 2, done so far:**
- the SLA engine (M15–M17): targets per type and priority, response and resolution clocks that
  pause and reopen, breach recording, auto-resume on the requester's reply, queue badges and
  sorting, and an admin screen for targets;
- the service catalogue (M18): items with their own forms, ordering, and an admin editor;
- approvals (M19): requests for items that need sign-off wait, with their SLA clocks paused, until
  a team lead of the receiving team or an admin approves or rejects them;
- problem management (M20): problems as a third ticket type, root cause and workaround as a known
  error, incidents linked to the problem behind them, and a note on each when it is fixed;
- change management (M21): changes as a fourth ticket type with standard, normal and emergency
  types, approval through the approvals inbox, a schedule with clash warnings, a review of how each
  went, and links to the problem a change fixes.

**Not built yet:**
- **Phase 2 (planned):**
  - business-hours SLA calendars (clocks run 24/7 today);
  - multi-stage approvals and a requester's line manager as approver (single-stage by team lead today);
  - attachments (S3);
  - notifications (breaches are recorded and shown, but nobody is alerted yet);
  - admin screens for users, teams and categories (managed through the API today).
- **Phase 3 (planned):**
  - reporting dashboards;
  - load testing;
  - AWS deployment (ECS Fargate, RDS, S3, CloudFront) with Terraform;
  - login rate limiting at the edge.

Known limitations are listed in [docs/security.md](docs/security.md#known-limitations-security)
and [docs/performance.md](docs/performance.md#known-future-work).
