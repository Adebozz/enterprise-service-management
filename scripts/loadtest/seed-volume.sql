-- Adds 50,000 incidents spread over the last 90 days to the demo database, for load testing only.
--
--   docker compose exec -T postgres psql -U esm -d esm_demo -f - < scripts/loadtest/seed-volume.sql
--
-- Shape (so plans and timings resemble a working desk, see docs/performance.md): 70% closed, 10%
-- resolved awaiting confirmation, 15% in progress and assigned, 5% new and unassigned; teams and
-- categories taken from the demo data, requesters spread over the demo requesters; open tickets get
-- running SLA clocks (some already breached). Never run against a database you care about.

CREATE TEMP TABLE seed AS
WITH roots AS (
    SELECT id AS category_id, default_team_id AS team_id,
           row_number() OVER (ORDER BY code) - 1 AS i, count(*) OVER () AS n
    FROM categories
    WHERE parent_id IS NULL AND applies_to IN ('INCIDENT', 'ANY') AND default_team_id IS NOT NULL
), requesters AS (
    SELECT id, row_number() OVER (ORDER BY email) - 1 AS i, count(*) OVER () AS n FROM users WHERE role = 'REQUESTER'
), base AS (
    SELECT g,
           now() - random() * interval '90 days' AS created,
           CASE WHEN g % 100 < 70 THEN 'CLOSED' WHEN g % 100 < 80 THEN 'RESOLVED'
                WHEN g % 100 < 95 THEN 'IN_PROGRESS' ELSE 'NEW' END AS status,
           (ARRAY['P1', 'P2', 'P2', 'P3', 'P3', 'P3', 'P4', 'P4'])[1 + g % 8] AS priority
    FROM generate_series(1, 50000) AS g
)
SELECT gen_random_uuid() AS id, b.g, b.status, b.priority, r.category_id, r.team_id, q.id AS requester_id,
       b.created,
       CASE WHEN b.status <> 'NEW' THEN b.created + (5 + random() * 235) * interval '1 minute' END AS responded,
       CASE WHEN b.status IN ('RESOLVED', 'CLOSED')
            THEN least(now(), b.created + (1 + random() * 71) * interval '1 hour') END AS resolved,
       (ARRAY['Printer offline', 'Cannot sign in', 'VPN drops', 'Laptop slow', 'Email not syncing',
              'Wi-Fi unstable', 'Monitor flickers', 'Teams calls choppy'])[1 + b.g % 8] AS subject
FROM base b
JOIN roots r ON r.i = b.g % r.n
JOIN requesters q ON q.i = b.g % q.n;

INSERT INTO work_items (id, reference, type, title, description, status, impact, urgency, priority,
                        category_id, requester_id, assigned_team_id, assignee_id,
                        first_responded_at, resolved_at, closed_at, created_at, updated_at)
SELECT s.id,
       'INC-' || lpad(nextval('incident_ref_seq')::text, 6, '0'),
       'INCIDENT',
       s.subject || ' (load test ' || s.g || ')',
       'Generated for load testing.',
       s.status,
       CASE s.priority WHEN 'P1' THEN 'HIGH' WHEN 'P2' THEN 'HIGH' WHEN 'P3' THEN 'MEDIUM' ELSE 'LOW' END,
       CASE s.priority WHEN 'P1' THEN 'HIGH' WHEN 'P2' THEN 'MEDIUM' WHEN 'P3' THEN 'MEDIUM' ELSE 'LOW' END,
       s.priority, s.category_id, s.requester_id, s.team_id,
       CASE WHEN s.status <> 'NEW'
            THEN (SELECT tm.user_id FROM team_members tm WHERE tm.team_id = s.team_id ORDER BY tm.user_id LIMIT 1) END,
       s.responded, s.resolved,
       CASE WHEN s.status = 'CLOSED' THEN least(now(), s.resolved + interval '1 day') END,
       s.created,
       coalesce(s.resolved, s.responded, s.created)
FROM seed s;

INSERT INTO incidents (work_item_id, resolution_code, resolution_notes)
SELECT id,
       CASE WHEN status IN ('RESOLVED', 'CLOSED') THEN 'FIXED' END,
       CASE WHEN status IN ('RESOLVED', 'CLOSED') THEN 'Fixed.' END
FROM seed;

-- Open tickets carry clocks like real ones: a running resolution target, and a response target
-- that is running (new) or met (in progress).
INSERT INTO sla_clocks (id, work_item_id, metric, target_minutes, state, elapsed_ms, running_since, due_at,
                        at_risk_at, breached_at, completed_at, created_at, updated_at)
SELECT gen_random_uuid(), s.id, 'RESOLUTION', 480, 'RUNNING', 0, s.created,
       s.created + interval '8 hours', s.created + interval '384 minutes',
       CASE WHEN s.created + interval '8 hours' < now() THEN s.created + interval '8 hours' END,
       NULL, s.created, s.created
FROM seed s WHERE s.status IN ('NEW', 'IN_PROGRESS')
UNION ALL
SELECT gen_random_uuid(), s.id, 'RESPONSE', 60,
       CASE WHEN s.status = 'NEW' THEN 'RUNNING' ELSE 'COMPLETED' END, 0,
       CASE WHEN s.status = 'NEW' THEN s.created END,
       CASE WHEN s.status = 'NEW' THEN s.created + interval '1 hour' END,
       CASE WHEN s.status = 'NEW' THEN s.created + interval '48 minutes' END,
       CASE WHEN s.status = 'NEW' AND s.created + interval '1 hour' < now() THEN s.created + interval '1 hour' END,
       CASE WHEN s.status = 'IN_PROGRESS' THEN s.responded END,
       s.created, s.created
FROM seed s WHERE s.status IN ('NEW', 'IN_PROGRESS');

ANALYZE;
SELECT status, count(*) FROM work_items GROUP BY status ORDER BY 2 DESC;
