# Performance notes

Only measured results are recorded here, with how they were measured.

## Ticket queue and search: index effectiveness (M7)

**Setup:** PostgreSQL 17 in local Docker on a MacBook Air (Apple Silicon). 100,000 incidents across
10 teams and 1,000 requesters: 70% closed; in-progress, waiting and resolved tickets assigned; 5%
NEW and unassigned (independent of team); "printer" in 1 of 500 titles. Measured with
`EXPLAIN (ANALYZE, BUFFERS)`, warm cache, single execution, inside a transaction that was rolled
back. These are single-query database timings, not end-to-end API latency.

| Query | With index | Without | Plan with index |
|---|---|---|---|
| Unassigned queue, one team, oldest first, page of 20 | **0.041 ms** (partial index) | 0.47 ms (read all 500 unassigned rows of the team, then sorted) | Index Scan `work_items_unassigned_queue_idx`, stops after 21 rows |
| Full-text search "printer" (count) | **2.5 ms** (GIN) | 16.9 ms (sequential scan) | Bitmap Index Scan `work_items_search_idx` |
| Requester's "my tickets", page of 20 | **0.038 ms** | n/a | Index Scan `work_items_requester_created_idx` |

**Findings worth knowing**
- **At 20,000 tickets PostgreSQL correctly preferred a sequential scan for full-text search**
  (estimated cost 1,020 vs 1,930 for the GIN bitmap scan): a GIN lookup has a high fixed cost and
  only wins on larger tables. My first index test was wrong to expect the index at that size.
- **Test data shapes plans.** My first seed made every non-closed ticket unassigned, and a second
  correlated status with team (all NEW tickets in one team). Both produced misleading plans. The
  seed in `QueryPlanIT` now models a realistic distribution, and the comments explain why.
- **The partial index only applies when the query's predicate implies it.** The terminal statuses
  are written as literals in the SQL (`TicketListQuery.NOT_TERMINAL`), not bind parameters, so the
  planner can prove the implication.

**Regression protection:** `QueryPlanIT` seeds the same 100,000-ticket shape (about 11 s) and
asserts these indexes appear in `EXPLAIN` for the exact SQL the application generates.

## Load test: the whole stack under concurrent users (M28)

**Setup:** the Docker Compose stack (nginx, Spring Boot, PostgreSQL 17, Mailpit) on a Mac
(Apple M4, 10 cores, 16 GB; Docker Desktop with 10 CPUs and 7.75 GB). The demo data plus 50,000
incidents over 90 days from `scripts/loadtest/seed-volume.sql` (70% closed, 10% resolved, 15% in
progress, 5% new; open tickets with SLA clocks). k6 0.57 ran in a container on the stack's network,
so every request went through nginx. Script: `scripts/loadtest/service-desk.js`, run with
`scripts/loadtest/run.sh`. Agents work their queue (team view sorted by SLA deadline, a search,
open a ticket with its comments and SLA, the unread count) with 1 to 2 s think time; requesters
raise an incident, list their tickets and comment; two team leads open the 30-day report every 5 s.
Single runs on a laptop: indicative, not a capacity guarantee.

| Run | Users | Requests/s | Errors | p50 | p95 | p99 |
|---|---|---|---|---|---|---|
| Baseline, 3 min | 20 agents, 10 requesters, 2 leads | 46.5 | 0 of 8,507 | 4.8 ms | 59 ms | 90 ms |
| Heavier, 2 min | 80 agents, 20 requesters, 2 leads | 165.8 | 0 of 20,575 | 2.7 ms | 71 ms | 104 ms |

Per endpoint (p50 / p95 / p99, ms):

| Endpoint | Baseline | Heavier |
|---|---|---|
| Team queue, sorted by SLA deadline | 48 / 74 / 86 | 61 / 102 / 200 |
| Search (`q=printer` etc., all tickets) | 24 / 38 / 58 | 13 / 30 / 36 |
| Ticket detail | 2.6 / 13 / 18 | 1.6 / 5.6 / 9.8 |
| Comments / SLA / unread count | ≤ 2.3 / ≤ 10 / ≤ 15 | ≤ 1.5 / ≤ 6 / ≤ 9 |
| Raise an incident | 15 / 20 / 27 | 14 / 48 / 67 |
| Requester's own tickets | 12 / 22 / 27 | 13 / 36 / 51 |
| Add a comment | 4.8 / 12 / 90 | 5.4 / 33 / 58 |
| 30-day report (lead's teams) | 156 / 186 / 193 | 150 / 208 / 271 |

All k6 thresholds held (no failed requests; queue p95 < 500 ms, ticket p95 < 300 ms, raising an
incident p95 < 500 ms). Requests per second rose 3.6× for 3.4× the users with p95 almost unchanged,
so this load is far from saturating the stack.

**Findings worth knowing**
- **The SLA-sorted queue is the most expensive common request, and it's the sort.** Alone it takes
  16 ms end to end against 5.5 ms for the default order. `EXPLAIN ANALYZE` (22 ms in the database)
  shows why: to sort by deadline, PostgreSQL looks up the open SLA clock of every one of the team's
  5,005 open tickets (a lateral lookup each, 3 µs apiece) before it can keep the first 20. If this
  ever matters, store each ticket's next deadline on `work_items` (kept current by the SLA module,
  like `at_risk_at`) and index it, so the page is read in order.
- **The report is the slowest endpoint (about 150 ms)** because it aggregates up to 90 days of the
  lead's tickets in eight queries. It is opened occasionally, not polled; caching it for a minute
  would be the first step if it were.
- **The baseline row is a repeat run.** The first 3-minute run at the same load, straight after
  start-up, had an overall p50 of 7.7 ms instead of 4.8 ms (p95 63 ms, no errors); most likely JIT
  warm-up. The seed script ends with `ANALYZE` so the planner has statistics from the first query.

## Known future work
- Sorting the queue by SLA deadline evaluates every open ticket of the team (see the load test);
  a stored next deadline per ticket would make it an index read.
- Offset pagination gets slower on deep pages; keyset ("seek") pagination would fix that if needed.
- The count query runs on every page after the first; for very large result sets an estimate or
  "has more" flag would be cheaper.
