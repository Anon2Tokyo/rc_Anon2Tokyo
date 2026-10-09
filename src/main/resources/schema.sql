CREATE TABLE IF NOT EXISTS notification_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    supplier VARCHAR(64) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    contact_id VARCHAR(100) NOT NULL,
    contact_status VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    total_attempts INT NOT NULL DEFAULT 0,
    replay_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    last_error VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uq_notification_request UNIQUE (business_id, request_id),
    INDEX ix_notification_due (business_id, status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
