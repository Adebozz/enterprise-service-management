# ADR-016: Business-hours SLAs with stored deadlines

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

SLA clocks counted every minute (ADR-010). Most service desks promise lower-priority work in
working hours only: a P3 raised at 17:00 on Friday shouldn't breach over the weekend. The queue
computes each ticket's badge in SQL (ADR-009), and did so from `elapsed_ms` plus the wall-clock
time since `running_since`, which is wrong as soon as nights and weekends don't count.

## Decision

- **One business calendar for the organisation:** opening hours per weekday (`business_hours`,
  seeded Monday to Friday 09:00 to 17:30) and closed days (`business_holidays`), in one time zone
  from configuration (`esm.sla.business-hours.zone`, default Europe/London). Admins edit the week
  and holidays; the zone is deployment configuration because changing it moves every deadline.
- **Each SLA policy chooses** around the clock or business hours (`sla_policies.business_hours`,
  default false, so existing behaviour is unchanged until an admin opts in). A clock copies the
  choice when it starts, as it copies its target: a running clock never changes mode.
- **The arithmetic is one pure class,** `BusinessCalendar`, behind a two-method `SlaSchedule`
  interface (`workingTime(from, to)`, `addWorkingTime(start, amount)`); `SlaSchedule.ALWAYS` is the
  24/7 case. Days are turned into instants through the zone, so daylight-saving changes are
  handled by `java.time`. The clock entity counts through whichever schedule applies; the calendar
  is loaded from the database and attached by the services (a business-hours clock used without
  it fails loudly rather than counting the wrong time).
- **Deadlines are stored, not computed by readers.** Besides `due_at`, a running clock stores
  `at_risk_at`, the moment it crosses the at-risk share of its target. The queue's SQL now compares
  `now` with these two instants instead of adding up time, so it is correct for both kinds of clock
  and needs no calendar. The breach job already worked from `due_at`.
- **Calendar changes apply at once.** Adding a holiday or changing the hours recomputes the
  deadlines of running business-hours clocks in the same transaction, so badges never show a
  deadline from the old hours. Time already folded into `elapsed_ms` stays as counted.

## Alternatives considered

- **Computing working time in SQL** (a generated series of opening intervals joined to each clock).
  Possible in PostgreSQL, but the queue query would grow a calendar join for every row, and the rule
  would exist twice (SQL and Java) with daylight saving in both.
- **A calendar per team or per category.** Real for follow-the-sun support; it needs calendars as
  first-class data and a choice per ticket. One calendar covers a single-site desk; the
  `SlaSchedule` interface is where per-team calendars would plug in.
- **Recomputing deadlines lazily** (only when a clock is next touched). Simpler writes, but the queue
  would show stale badges until then.

## Consequences

- `at_risk_at` bakes in the at-risk threshold at the time it was computed; changing
  `esm.sla.at-risk-threshold` takes effect for each clock at its next change. The queue no longer
  receives the threshold at all.
- A business-hours change does work proportional to the number of running business-hours clocks;
  fine for a calendar edited a few times a year.
- The demo keeps every target around the clock; switching one to business hours is a single choice
  on the *SLA targets* page.
