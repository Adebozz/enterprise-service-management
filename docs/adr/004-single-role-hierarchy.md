# ADR-004: One role per user, with a role hierarchy

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

The system has four roles: REQUESTER, AGENT, TEAM_LEAD and ADMIN. Each one has every permission of
the role below it. The original specification suggested `roles` and `user_roles` tables.

## Decision

- Store **one `role` column** on `users`, guarded by a CHECK constraint.
- Express inclusion with Spring Security's **`RoleHierarchy`**, generated from the `Role` enum,
  so `hasRole('AGENT')` also admits team leads and admins.
- Model **scope** (which team's tickets) separately, through team membership, rather than as
  more roles.
- Model **per-item permissions** (for example "approver for this change") as data on that item
  (Phase 2 approvals), not as global roles.

## Alternatives considered

- **Many-to-many `user_roles`.** Needed only if roles combine independently (for example "AGENT +
  AUDITOR"). Here they are strictly nested, so it would add joins, a richer admin UI and invalid
  combinations (REQUESTER + ADMIN) without adding expressiveness.
- **Fine-grained permissions** (`TICKET_ASSIGN`, `USER_MANAGE`, ...) mapped to roles. More flexible,
  but premature without a requirement for customer-defined roles.

## Consequences

- Authorization checks stay short and readable, and the single enum keeps the database, Java and
  Spring Security in agreement.
- If independent roles become necessary, migrate to a join table: the `Role` enum and the
  `hasRole` checks remain valid.
