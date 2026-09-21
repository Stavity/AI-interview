-- Agent-step audit rows are owned by an agent run.  Without this cascade,
-- deleting a multi-round interview still fails after the run itself is
-- selected for deletion.
ALTER TABLE interview_agent_steps
  DROP CONSTRAINT IF EXISTS interview_agent_steps_run_id_fkey,
  ADD CONSTRAINT interview_agent_steps_run_id_fkey
    FOREIGN KEY (run_id) REFERENCES interview_agent_runs(id) ON DELETE CASCADE;
