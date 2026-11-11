# ADR-010: SLA clocks as stored stretches of time, with breaches recorded

- **Status:** Accepted
- **Date:** 2026-11-11

## Context

Each ticket has two SLA measurements: time to first response and time to resolution. Their targets
depend on ticket type and priority. The clocks pause while support waits for the requester, a
reopened ticket's resolution clock continues rather than restarting, and a priority change moves
the deadline. Agents need "what is due soonest" and "what has breached" quickly, and reports need
to know afterwards whether each target was met.

## Decision

- **One row per ticket and metric** in `sla_clocks`, owned by a separate `sla` module that depends
  on `ticket` (ticket events will drive it, keeping the dependency one-way).
- **Time is stored as stretches:** `elapsed_ms` (finished running stretches) plus `running_since`
  (the current one). Elapsed time is `elapsed_ms + (now - running_since)` while running. Pause,
  resume, complete and reopen only move time between these two fields.
- **`due_at` is stored, not only computed,** while the clock runs, with a partial index on running,
  unbreached clocks. The breach job then asks the index "which deadlines have passed?" instead of
  computing every clock's deadline.
- **Breaches are recorded permanently** in `breached_at`, at the moment the deadline passed. Pausing,
  completing or a longer target later doesn't erase them. A shorter target that is already used up
  records the breach at the moment of the change.
- **Wall-clock time, 24/7, for now.** Business hours would change how `due_at` is computed, not what
  is stored.
- **Targets are rows** in `sla_policies` (one per type and priority, seeded by migration), so admins
  can change them without a deployment.

## Alternatives considered

- **Derive everything from the ticket's status history.** No extra table, but every queue page and
  report would replay history for every ticket, and "due soonest" couldn't use an index.
- **Store only `started_at` and `due_at`, and shift `due_at` on resume.** Simpler at first, but the
  time used is then implicit, and reopening or retargeting needs it back.
- **Targets in configuration files** (as the priority matrix is). Fine for fixed rules, but SLA
  targets are business settings that admins expect to change in the app.

## Consequences

- Every SLA rule is a method on one entity with no Spring or database dependency, so the arithmetic
  is unit-tested with exact instants.
- Status shown to people (on track, at risk, breached, met, missed) is derived on read from the
  stored fields and an at-risk threshold, so the threshold can change without a migration.
- Business hours, when added, need a calendar-aware deadline calculation and recalculation of
  running clocks' `due_at` when the calendar changes.
