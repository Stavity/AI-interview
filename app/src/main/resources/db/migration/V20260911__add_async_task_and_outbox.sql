CREATE TABLE async_task (
    id UUID PRIMARY KEY,
    task_type VARCHAR(40) NOT NULL,
    business_id VARCHAR(128) NOT NULL,
    active_key VARCHAR(200) UNIQUE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
    input_payload JSONB NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    execution_version BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    execution_deadline TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL,
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((state IN ('QUEUED','RUNNING','RETRY_WAIT')) = (active_key IS NOT NULL))
);
CREATE INDEX idx_async_task_recovery ON async_task(state, lease_until, next_run_at, updated_at);
CREATE TABLE task_outbox (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES async_task(id),
    topic VARCHAR(120) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('PENDING','SENDING','SENT')),
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ
);
CREATE INDEX idx_task_outbox_ready ON task_outbox(state, available_at, lease_until);
CREATE INDEX idx_task_outbox_task ON task_outbox(task_id, topic, state);
