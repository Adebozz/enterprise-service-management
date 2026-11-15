-- V16: login throttling (Phase 3, M27): failed sign-ins, so repeated guessing is slowed down.
--
-- In the database rather than in memory so the limit holds across several backend instances. Rows
-- only matter for the throttling window (15 minutes) and are deleted as they age out. The email is
-- stored as a SHA-256 hash: the table needs to recognise repeats, not to know what people typed.
CREATE TABLE login_failures (
    id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_hash char(64)     NOT NULL,
    address      varchar(45)  NOT NULL,
    failed_at    timestamptz  NOT NULL
);

CREATE INDEX login_failures_account_idx ON login_failures (account_hash, failed_at);
CREATE INDEX login_failures_address_idx ON login_failures (address, failed_at);
CREATE INDEX login_failures_age_idx ON login_failures (failed_at);
