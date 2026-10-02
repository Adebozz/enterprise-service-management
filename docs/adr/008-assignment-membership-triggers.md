# ADR-008: Enforce "assignee belongs to the team" at assignment time, not for the row's lifetime

- **Status:** Accepted (supersedes the composite foreign key introduced in V4)
- **Date:** 2026-10-02

## Context

V4 enforced "the assignee is a member of the assigned team" with a composite foreign key
`(assigned_team_id, assignee_id) → team_members(team_id, user_id)`. While designing team-membership
removal for assignment (M5), I found a flaw: a foreign key must hold for as long as the row exists,
and closed tickets keep their historical assignee. So anyone who had ever owned a ticket in a team
could **never** be removed from it, and normal staff changes would be blocked by history.

## Decision

Replace the foreign key (migration V5) with two triggers that express the actual business rules:

1. `work_items_assignee_membership`: on insert or when `assignee_id` / `assigned_team_id`
   changes, the assignee must be a member of the team (`foreign_key_violation`).
2. `team_members_no_open_assignments`: a membership can't be deleted while that person owns
   **open** tickets (not CLOSED/CANCELLED/REJECTED) in that team (`restrict_violation`).

The service layer performs the same checks first to return precise errors (422
`ASSIGNEE_NOT_IN_TEAM`, 409 `MEMBER_HAS_OPEN_TICKETS`); the triggers are the last line of defence.

## Alternatives considered

- **Keep the FK and null out assignees when closing.** This destroys history ("who resolved this?")
  that reporting (MTTR per agent) needs.
- **Application checks only.** Simpler, but loses the database guarantee against bugs and
  concurrent edits (e.g. removing a member while someone assigns them).
- **Soft-delete memberships (an `active` flag) so the FK always holds.** Workable, but every
  membership query must then filter inactive rows, and it still doesn't express "only open tickets
  block removal".

## Consequences

- History is preserved and team membership can change once a person's open work is reassigned.
- Trigger logic lives in SQL, so it's tested by raw-SQL integration tests (`TicketSchemaIT`).
- Lesson recorded: a foreign key is for invariants that hold for the row's whole life. Rules
  about a moment in time (assignment) or a subset of rows (open tickets) belong in triggers or
  application logic.
