# ADR-006: Synchronous, same-transaction, append-only audit trail

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Every important change must be auditable: ticket created, status/priority/assignment changed,
user/role changes, approvals. An auditor must be able to trust two things: every change has an
entry, and no entry describes a change that didn't happen. Entries must not be editable.

## Decision

- An explicit **`AuditService.record(AuditRecord)`** call from the service that makes the
  change, with `@Transactional(propagation = MANDATORY)`, so it runs **inside** the business
  transaction and fails if there is none.
- **Business-level entries** (`TICKET_CREATED`, `USER_UPDATED` with changed fields only), with
  `jsonb` before/after values, the actor (via an `ActorProvider` port implemented by `auth`) and
  the request correlation id.
- **Append-only** enforced by a PostgreSQL trigger (`BEFORE UPDATE OR DELETE → restrict_violation`),
  an `@Immutable` entity, and a repository that exposes only `save` and `find`.

## Alternatives considered

- **Hibernate Envers.** Automatic, but it records *row versions*, not business actions ("status
  changed from NEW to ASSIGNED by X because Y"). It adds `_AUD` tables for every entity, and reading
  a timeline means diffing revisions.
- **Asynchronous events** (`@TransactionalEventListener(AFTER_COMMIT)` or a message broker). These
  decouple the write, but a crash between commit and audit write loses the entry. A transactional
  outbox would fix that at the cost of significant complexity, which isn't justified at this scale.
- **Database triggers that write the audit themselves.** Complete coverage, but they don't know
  the actor, the business intent or the correlation id.

## Consequences

- Audit writes add a small insert to each business transaction. That's acceptable for an ITSM
  workload.
- Developers must remember to call `record()`. Tests assert audit entries for each audited
  operation, and the review checklist includes it.
- `TRUNCATE` is not blocked by row triggers (tests use it). In production, the application's
  database role must not hold `TRUNCATE` on `audit_events`. Documented for Phase 3 RDS setup.
