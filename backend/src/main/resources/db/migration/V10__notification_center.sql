CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    event_key VARCHAR(160) NOT NULL,
    type VARCHAR(24) NOT NULL,
    title VARCHAR(160) NOT NULL,
    resource_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    FOREIGN KEY (recipient_id) REFERENCES users(id),
    FOREIGN KEY (group_id) REFERENCES expense_groups(id),
    CONSTRAINT uk_notification_event UNIQUE(recipient_id,event_key),
    INDEX idx_notification_recipient_time(recipient_id,id),
    INDEX idx_notification_unread(recipient_id,read_at,id)
);
