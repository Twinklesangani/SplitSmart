CREATE TABLE group_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_slot VARCHAR(1) NULL,
    CONSTRAINT uk_group_invitation_email UNIQUE (group_id, email, active_slot),
    CONSTRAINT fk_invitation_group FOREIGN KEY (group_id) REFERENCES expense_groups(id),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    INDEX idx_invitation_recipient (email, status, expires_at)
);
