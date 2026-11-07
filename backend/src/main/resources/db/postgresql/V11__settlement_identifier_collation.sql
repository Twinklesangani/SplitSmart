-- PostgreSQL-only compatibility addition. Preserve the MySQL case-insensitive
-- UUID comparison used by request replay lookup and the unique group/request key.
ALTER TABLE settlements ALTER COLUMN request_id
    TYPE VARCHAR(36) COLLATE splitsmart_email_ci;
