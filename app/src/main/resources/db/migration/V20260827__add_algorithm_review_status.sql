ALTER TABLE algorithm_submissions
  ADD COLUMN evaluation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN evaluation_error_code VARCHAR(64);

ALTER TABLE algorithm_submissions
  ADD CONSTRAINT algorithm_submissions_evaluation_status_check
    CHECK (evaluation_status IN ('PENDING', 'COMPLETED', 'FAILED'));

CREATE INDEX idx_algorithm_submissions_evaluation_status
  ON algorithm_submissions(evaluation_status, updated_at DESC);
