# ADR-002: PostgreSQL with Flyway, tested with Testcontainers

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

The domain is strongly relational: users, teams, memberships, work items, comments, approvals and
audit. It needs real integrity guarantees (foreign keys, unique constraints, check constraints),
transactional updates, aggregate reporting queries and text search. Production will run on a
managed AWS database.

## Decision

- **PostgreSQL 17** everywhere: docker-compose, integration tests and Amazon RDS.
- **Flyway** owns the schema through versioned SQL migrations. Hibernate runs with
  `ddl-auto=validate`, so it checks the schema and never changes it.
- **Testcontainers** runs integration tests against a real PostgreSQL 17 container. No H2.
- PostgreSQL features are used deliberately where they help: sequences for reference numbers,
  `tsvector` + GIN for search, `jsonb` for audit payloads, partial indexes, and triggers to make
  the audit log append-only.

## Alternatives considered

- **MySQL.** A viable choice, but it has weaker built-in full-text search and no partial indexes
  or `jsonb`-equivalent indexing.
- **Hibernate `ddl-auto=update`.** Unreviewable, can't express every constraint or index, and is
  unsafe in production.
- **H2 for tests.** Faster to start, but it is a different SQL dialect with different constraint
  behaviour. Tests would pass against a database we don't run in production.
- **Liquibase.** Equivalent capability. Flyway's plain-SQL migrations are simpler to read and review.

## Consequences

- Integration tests need Docker. On a shared Spring context they reuse one container per run, so the
  overhead is a few seconds.
- Migrations are immutable once merged, so schema mistakes are fixed forward.
- PostgreSQL-specific SQL ties us to PostgreSQL. That's an accepted trade-off, since there's no
  requirement to support other databases.
