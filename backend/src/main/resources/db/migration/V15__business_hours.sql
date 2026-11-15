-- V15: business-hours SLAs (Phase 2, M25): targets that count only the service desk's working time.
--
-- One calendar for the organisation: opening hours per weekday and closed days (holidays). The
-- time zone comes from configuration (esm.sla.business-hours.zone), since changing it would move
-- every deadline. Each SLA policy says whether its targets run around the clock or in business
-- hours, and each clock keeps the choice it started with, as it keeps its target.

-- Weekdays the desk is open, ISO numbering (1 = Monday). A day without a row is closed.
CREATE TABLE business_hours (
    day_of_week smallint PRIMARY KEY,
    opens_at    time     NOT NULL,
    closes_at   time     NOT NULL,

    CONSTRAINT business_hours_day_check CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT business_hours_order_check CHECK (closes_at > opens_at)
);

-- A working week to start from: Monday to Friday, 09:00 to 17:30.
INSERT INTO business_hours (day_of_week, opens_at, closes_at)
VALUES (1, '09:00', '17:30'), (2, '09:00', '17:30'), (3, '09:00', '17:30'), (4, '09:00', '17:30'), (5, '09:00', '17:30');

-- Whole days the desk is closed although the week says open.
CREATE TABLE business_holidays (
    day  date         PRIMARY KEY,
    name varchar(100) NOT NULL,

    CONSTRAINT business_holidays_name_check CHECK (btrim(name) <> '')
);

-- Existing policies keep counting around the clock; admins choose business hours per policy.
ALTER TABLE sla_policies
    ADD COLUMN business_hours boolean NOT NULL DEFAULT false;

-- business_hours: copied from the policy when the clock starts.
-- at_risk_at: when a running clock crosses the at-risk share of its target. Stored like due_at so
-- the queue can show "at risk" with a comparison, without knowing the calendar.
ALTER TABLE sla_clocks
    ADD COLUMN business_hours boolean NOT NULL DEFAULT false,
    ADD COLUMN at_risk_at timestamptz;

-- Running clocks so far all count around the clock: at risk once 80% of the target is used.
UPDATE sla_clocks
SET at_risk_at = running_since + (target_minutes * 60000 * 0.8 - elapsed_ms) * interval '1 millisecond'
WHERE state = 'RUNNING';

ALTER TABLE sla_clocks
    ADD CONSTRAINT sla_clocks_at_risk_check CHECK (state = 'RUNNING' OR at_risk_at IS NULL);
