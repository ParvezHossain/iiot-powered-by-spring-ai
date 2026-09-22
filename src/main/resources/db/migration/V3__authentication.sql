CREATE TABLE auth_users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_version BIGINT NOT NULL DEFAULT 0,
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth_users(id),
    family_id UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_auth_refresh_user ON auth_refresh_tokens(user_id);
CREATE INDEX ix_auth_refresh_family ON auth_refresh_tokens(family_id);
CREATE INDEX ix_auth_refresh_expiry ON auth_refresh_tokens(expires_at);
-- Serializes bootstrap across application instances.
CREATE TABLE auth_bootstrap_lock (id INTEGER PRIMARY KEY);
INSERT INTO auth_bootstrap_lock VALUES (1);
