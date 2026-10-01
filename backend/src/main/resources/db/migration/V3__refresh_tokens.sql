-- V3: refresh tokens (rotation + reuse detection).
--
-- The raw token is a 256-bit random value that only ever exists in the user's HttpOnly cookie.
-- We store its SHA-256 hash, so a database leak does not yield usable tokens. (A fast hash is fine
-- here: unlike passwords, the input has full entropy and can't be brute-forced.)
--
-- All tokens descending from one login share a family_id. Presenting an already-rotated token
-- (outside a short grace window) revokes the whole family.
CREATE TABLE refresh_tokens (
    id                uuid        PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES users (id),
    family_id         uuid        NOT NULL,
    token_hash        varchar(64) NOT NULL,
    issued_at         timestamptz NOT NULL,
    -- Absolute session end: rotated tokens inherit it, so a session cannot be extended forever.
    expires_at        timestamptz NOT NULL,
    used_at           timestamptz,
    revoked_at        timestamptz,
    revocation_reason varchar(30),

    CONSTRAINT refresh_tokens_hash_uk UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_hash_format_check CHECK (token_hash ~ '^[0-9a-f]{64}$'), -- hex SHA-256
    CONSTRAINT refresh_tokens_expiry_check CHECK (expires_at > issued_at),
    CONSTRAINT refresh_tokens_revocation_check CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT refresh_tokens_reason_check CHECK (
        revocation_reason IN ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'USER_INACTIVE'))
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
-- For the periodic purge of expired tokens.
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at);
