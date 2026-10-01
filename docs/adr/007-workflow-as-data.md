# ADR-007: Ticket lifecycles as declarative data, enforced by the entity

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Each ticket type has a lifecycle with rules about which status changes are allowed, who may make
them (requester, support, system) and what they need (resolution notes, a reason, an assignee).
Clients must never be able to set an arbitrary status. Phase 2 adds problems and changes with
their own lifecycles.

## Decision

- Each lifecycle is a **`WorkflowDefinition`**: an immutable table of `Transition(from, to, label,
  allowedActors, requirements)` built once per type.
- **The entity is the only thing that changes status** (`WorkItem.transition`). It checks the move
  exists and its requirements are met, then applies type-specific side effects.
- **Authorization is in the service**, expressed through *actors* (the caller's relationship to
  the ticket), not global roles.
- Status changes are a **separate API resource** (`/transitions`), not a field on PATCH. A
  `GET` lists the moves available to the caller, which drives the UI.

## Alternatives considered

- **Spring Statemachine.** Powerful (hierarchical states, guards, persisted machines) but heavy for
  a flat lifecycle, adds its own concurrency and persistence model, and has seen little recent
  development. Our needs fit in about 150 lines that are easy to test exhaustively.
- **`if/switch` checks in each service method.** Rules get scattered and duplicated, are hard to
  review, and you can't list "what can this user do now?" without re-implementing them.
- **Writable `status` on PATCH with validation.** Encourages clients to think in terms of setting
  state; every caller must remember to validate.

## Consequences

- Adding a status or move is a one-line change to the definition plus its exhaustive test table,
  which deliberately has to be updated too.
- The frontend never duplicates workflow rules.
- `SYSTEM` moves (assignment, approval, auto-close) are invoked by application services, not
  the API, so automation can't be impersonated by users.
