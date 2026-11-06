ALTER TABLE users ADD COLUMN email_verified_at DATETIME(6) NULL;
ALTER TABLE group_invitations ADD COLUMN revoked_at TIMESTAMP(6) NULL;
CREATE TABLE group_invite_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    accepted_by BIGINT NULL,
    revoked_at TIMESTAMP(6) NULL,
    FOREIGN KEY (group_id) REFERENCES expense_groups(id),
    FOREIGN KEY (accepted_by) REFERENCES users(id),
    INDEX idx_group_invite_links (group_id, id)
);
CREATE TABLE email_verification_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_email_proof_user (user_id, created_at)
);
