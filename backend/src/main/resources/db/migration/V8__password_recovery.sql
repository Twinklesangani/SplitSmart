ALTER TABLE users
    ADD COLUMN auth_token_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN password_reset_requested_at DATETIME(6) NULL;

CREATE TABLE password_reset_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_password_reset_user_created (user_id, created_at),
    INDEX idx_password_reset_expiry (expires_at)
) ENGINE=InnoDB;
