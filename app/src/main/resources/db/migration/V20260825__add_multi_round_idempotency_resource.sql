ALTER TABLE multi_round_idempotency
  ADD COLUMN resource_type VARCHAR(32),
  ADD COLUMN resource_id BIGINT;
