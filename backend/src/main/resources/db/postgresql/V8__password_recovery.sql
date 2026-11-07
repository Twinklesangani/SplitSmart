ALTER TABLE users
    ADD COLUMN auth_token_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN password_reset_requested_at TIMESTAMP(6) WITHOUT TIME ZONE NULL;

CREATE TABLE password_reset_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_password_reset_user_created ON password_reset_tokens (user_id, created_at);
CREATE INDEX idx_password_reset_expiry ON password_reset_tokens (expires_at);
