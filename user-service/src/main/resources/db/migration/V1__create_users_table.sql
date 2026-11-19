-- Create users table with proper constraints
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    -- BCrypt hash (60 characters). The plain password is never stored.
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_users_email_not_empty CHECK (LENGTH(TRIM(email)) > 0)
);

-- Note: emails are stored in lower case by the service, so the unique key
-- also stops two accounts that differ only by upper/lower case letters.
-- updated_at is maintained by Hibernate's @UpdateTimestamp on the User entity,
-- not a DB-level trigger (same as the other services).
