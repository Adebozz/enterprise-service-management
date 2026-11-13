# ADR-013: Changes as a fourth ticket type, approved through the existing approvals

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

Support needs to plan changes to live services ("Raise the VPN gateway licence to 500 sessions"),
have the risky ones approved before they happen, see what else is planned for the same time, and
record how each one went. A change is owned by a team, assigned, discussed and audited, and moves
through a lifecycle: everything a ticket already does. Approval already exists for catalogue
requests (M19), with an inbox for team leads.

## Decision

- **A change is a `WorkItem` subtype** (`CHANGE`, references `CHG-000001`), with its own table
  (`changes`) and workflow: `NEW → ASSIGNED → APPROVAL_PENDING → APPROVED → IMPLEMENTING → REVIEW →
  CLOSED`, plus `REJECTED` and `CANCELLED`. Like problems, changes use incident categories, have no
  SLA clocks, are readable by all staff (the schedule is how teams avoid clashing) and are changed
  only by their team.
- **Three change types.** A *standard* change is routine and low risk, so it is pre-approved and
  goes straight from `ASSIGNED` to `APPROVED` ("Schedule standard change"). *Normal* and
  *emergency* changes are submitted for approval; only an emergency may have a window that has
  already started. Both rules, and "the plan is complete", are checked by `Change.checkCanEnter`
  before anything changes.
- **Only the moves that can apply are offered.** A new hook, `WorkItem.offersMoveTo`, lets a ticket
  rule out moves that its own data makes impossible in every case (a standard change is never
  submitted for approval). The transitions list filters on it, so the UI shows no button that can
  only fail. Moves that merely need more information first are still offered and explain what is
  missing.
- **Approval reuses the approvals module, driven by an event.** The `approval` module already
  depends on `ticket`, so `ticket.change` can't call it. Submitting is an ordinary transition to
  `APPROVAL_PENDING`; `ApprovalService` hears the resulting `TicketChangedEvent` and opens the
  approval in the same transaction. Decisions, the inbox, "never your own", withdrawal on
  cancellation and the ticket panel all work unchanged. Changes use the same status names as
  requests for the approval outcomes (`APPROVAL_PENDING`, `APPROVED`, `REJECTED`); a test keeps
  them in step.
- **The plan is ticket data with its own endpoint,** editable until submission and fixed after it:
  what was approved is what happens. The outcome (`SUCCESSFUL`, `SUCCESSFUL_WITH_ISSUES`, `FAILED`,
  `BACKED_OUT`) and review notes have their own endpoint too, and closing needs them.
- **Clashes are computed when read.** A change's details list the other scheduled changes
  (awaiting approval, approved or under way) whose windows overlap its own, and
  `GET /api/changes/schedule` lists those whose window hasn't ended. Nothing blocks a clash:
  overlapping work is often fine, and the people involved decide.
- **A change can name the open problem it fixes** (`changes.problem_id`); the problem lists its
  changes.

## Alternatives considered

- **A `submit` endpoint in the change module that calls the approvals module.** Simpler to read,
  but it creates a module cycle (`approval` → `ticket` → `approval`) that the architecture test
  rejects. The event is how the codebase already breaks such cycles (SLA clocks, problem notes).
- **A separate change-approval table or a CAB with several approvers.** More realistic for large
  organisations, but a second approval mechanism for one milestone. Single-stage approval by the
  team's leads matches what requests already have; multi-stage approval stays on the roadmap for
  both.
- **Re-planning an approved change.** Sending it back for a fresh approval needs more than one
  approval per ticket, which the approvals table forbids today. A change that needs a different
  plan is cancelled and raised again, so each approval always matches one plan.
- **Refusing overlapping windows.** Easy to enforce, but it would block legitimate work in
  different parts of the estate. Showing the clash leaves the decision to people.

## Consequences

- The approvals module now handles two ticket types; its event listener decides by type whether to
  open an approval or just withdraw one.
- `checkCanEnter` now receives the time of the move, because "the window must not have started"
  depends on it.
- A rejected change is final. The roadmap item for multi-stage approvals will have to revisit the
  one-approval-per-ticket rule, and with it re-planning.
