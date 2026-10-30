CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB;

CREATE TABLE expense_groups (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_expense_groups PRIMARY KEY (id),
    CONSTRAINT fk_expense_groups_created_by_users
        FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_expense_groups_created_by (created_by)
) ENGINE=InnoDB;

CREATE TABLE group_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT pk_group_members PRIMARY KEY (id),
    CONSTRAINT uk_group_members_group_user UNIQUE (group_id, user_id),
    CONSTRAINT fk_group_members_expense_group
        FOREIGN KEY (group_id) REFERENCES expense_groups (id),
    CONSTRAINT fk_group_members_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_group_members_user_id (user_id)
) ENGINE=InnoDB;
