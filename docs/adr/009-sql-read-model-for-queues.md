# ADR-009: Hand-written SQL read model for ticket queues and search

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

The agent dashboard needs one endpoint that lists tickets with: visibility rules, four views, ten
optional filters, PostgreSQL full-text search (`@@`, `websearch_to_tsquery`, `ts_rank`), sorting,
paging, and names (category, team, requester, assignee) in every row. The write side uses JPA
aggregates.

## Decision

Build the list query as **SQL assembled from constant fragments**, executed with
`NamedParameterJdbcTemplate` and mapped straight to a `TicketSummary` record (`TicketListQuery`,
`TicketSearchRepository`). JPA remains the model for writes.

- Every client value is a **named bind parameter**. Sort fields go through a fixed map. Unit tests
  push injection payloads through every input.
- `TicketAccessPolicy`'s rules are rendered into the `WHERE` clause, so invisible rows are never
  loaded.
- The builder is pure, so tests assert on the SQL and `QueryPlanIT` runs `EXPLAIN` on exactly
  the generated SQL.

## Alternatives considered

- **JPA Specifications / Criteria API.** Fine for simple filters (used for the admin user search),
  but full-text operators aren't expressible without custom function registration. It would also
  load entities (or need verbose constructor projections) and makes the generated SQL harder to
  reason about for index tuning.
- **QueryDSL or jOOQ.** Type-safe SQL is attractive (jOOQ especially), but it's a new code-generation
  toolchain for one query. Worth revisiting if reporting (Phase 3) adds many more.
- **Elasticsearch / OpenSearch.** Better relevance tuning and scale, but a second datastore to
  operate and keep in sync. PostgreSQL full-text search with a GIN index measured 2.5 ms on 100k
  tickets (`docs/performance.md`); there's no requirement it fails to meet.

## Consequences

- Column renames must be reflected in the SQL by hand. The integration tests (`TicketListApiIT`)
  catch mismatches.
- The read model can evolve independently of the entities (e.g. adding SLA state columns in
  Phase 2) without touching the write side.
