ALTER TABLE interview_agent_runs
  ADD COLUMN runtime_type VARCHAR(32),
  ADD COLUMN runtime_version VARCHAR(64),
  ADD COLUMN runtime_session_id VARCHAR(128),
  ADD COLUMN policy_version VARCHAR(64),
  ADD COLUMN rejection_reason VARCHAR(128);

UPDATE interview_agent_runs
SET runtime_type = 'LEGACY'
WHERE runtime_type IS NULL;

CREATE UNIQUE INDEX uq_interview_agent_runs_runtime_session
  ON interview_agent_runs(runtime_session_id)
  WHERE runtime_session_id IS NOT NULL;

CREATE INDEX idx_interview_agent_runs_runtime_started
  ON interview_agent_runs(runtime_type, started_at DESC);

ALTER TABLE interview_agent_steps
  ADD COLUMN event_type VARCHAR(32),
  ADD COLUMN tool_call_id VARCHAR(128);

CREATE INDEX idx_interview_agent_steps_event
  ON interview_agent_steps(run_id, event_type, step_no);
