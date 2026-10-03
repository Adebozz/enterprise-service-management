# Performance notes

Only measured results are recorded here, with how they were measured. Load testing (k6:
throughput, p50/p95/p99 latency) is planned for Phase 3 and is **not** done yet.

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

## Known future work
- Offset pagination gets slower on deep pages; keyset ("seek") pagination would fix that if needed.
- The count query runs on every page after the first; for very large result sets an estimate or
  "has more" flag would be cheaper.
