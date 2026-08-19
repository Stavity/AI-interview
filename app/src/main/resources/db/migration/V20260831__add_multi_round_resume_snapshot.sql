ALTER TABLE multi_round_interviews
  ADD COLUMN resume_snapshot TEXT,
  ADD COLUMN resume_snapshot_version VARCHAR(64),
  ADD COLUMN jd_snapshot TEXT,
  ADD COLUMN skill_id VARCHAR(64),
  ADD COLUMN difficulty VARCHAR(16),
  ADD COLUMN scope_fingerprint VARCHAR(64);

CREATE INDEX idx_multi_round_interviews_scope_status
  ON multi_round_interviews(scope_fingerprint, status);

CREATE UNIQUE INDEX uq_multi_round_active_scope
  ON multi_round_interviews(scope_fingerprint)
  WHERE status IN ('CREATED', 'ROUND_READY', 'IN_PROGRESS', 'ROUND_EVALUATING', 'ROUND_COMPLETED');
