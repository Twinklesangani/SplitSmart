CREATE TABLE expense_creation_operations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    response_json LONGTEXT NOT NULL,
    CONSTRAINT uk_expense_creation_operation UNIQUE (group_id, user_id, operation_key),
    CONSTRAINT fk_expense_operation_group FOREIGN KEY (group_id) REFERENCES expense_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_expense_operation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
