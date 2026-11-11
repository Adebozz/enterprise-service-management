# ADR-012: Problems as a third ticket type, linked from incidents

- **Status:** Accepted
- **Date:** 2026-11-11

## Context

Support needs to record the cause behind incidents that keep coming back ("VPN refuses connections
at busy times"), share a workaround while the cause is fixed, and tell the affected incidents when
it is. A problem is investigated by a team, assigned, discussed in internal notes, and moves
through a lifecycle with a full history: everything a ticket already does.

## Decision

- **A problem is a `WorkItem` subtype** (`PROBLEM`, references `PRB-000001`), with its own table
  (`problems`) under the existing JOINED inheritance and its own workflow:
  `NEW → ASSIGNED → INVESTIGATING → KNOWN_ERROR → RESOLVED → CLOSED`, plus reopen and cancel.
  Queues, search, assignment, comments, history and the transition API work unchanged.
- **Problems use incident categories** (`CategoryScope.includes`), so they route to the team that
  handles those incidents, with no new category scope.
- **Visibility differs from other tickets in one way:** every agent sees every problem, because a
  known error is most useful to whoever meets the same incident next. Requesters see none. Only the
  owning team changes a problem. The rule sits in `TicketAccessPolicy` and in the list query's
  `WHERE` clause, as before.
- **Incidents point at their problem** (`incidents.problem_id`), many to one. Links are made by
  staff handling the incident or the problem, are audited on the incident, and only an open problem
  takes new incidents.
- **Analysis is ticket data, checked before the move.** Root cause and workaround are saved through
  their own endpoint; becoming a known error and resolving need them. A new hook,
  `WorkItem.checkCanEnter`, runs before the status changes, so a refused move leaves the ticket
  untouched.
- **No SLA clocks for problems.** The `sla` module ignores them.

## Alternatives considered

- **A separate `problem` module with its own entity**, outside `work_items`. Cleaner separation,
  but it would rebuild assignment, comments, history, queues and search, or need a second version
  of each.
- **A many-to-many link table** (an incident caused by several problems). More general, but rare in
  practice, and every screen would have to deal with lists. A single column keeps the rules
  ("only open problems take incidents") simple and can be widened by a migration.
- **Root cause and workaround as transition inputs.** One request instead of two, but the analysis
  grows during the investigation and is edited many times; tying it to a status change would lose
  that.
- **Notify linked incidents by changing their status.** Tempting, but whether an incident is
  really fixed is for whoever handles it to confirm. An internal note asks them to check.

## Consequences

- One shared ticket model keeps the new type small, but every `switch` over ticket types (SLA
  phases, labels) had to make a decision about problems; the compiler pointed each one out.
- The `comment` package now depends on `problem` (it posts the "problem fixed" notes), because
  `problem` must not depend on `comment`: `query` depends on `problem`, and `comment` on `query`.
- Linking writes to the incident row, so it bumps the incident's `version`, like any other change.
