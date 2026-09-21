-- A text interview may be the root record of a multi-round interview.  The
-- original foreign keys used the PostgreSQL default RESTRICT behavior, which
-- made DELETE /api/interview/sessions/{sessionId} fail for every multi-round
-- entry in the interview history.  These tables contain data owned solely by
-- that root interview, so they must be removed together.

ALTER TABLE algorithm_submissions
  DROP CONSTRAINT IF EXISTS algorithm_submissions_question_id_fkey,
  ADD CONSTRAINT algorithm_submissions_question_id_fkey
    FOREIGN KEY (question_id) REFERENCES multi_round_questions(id) ON DELETE CASCADE;

ALTER TABLE multi_round_questions
  DROP CONSTRAINT IF EXISTS multi_round_questions_round_id_fkey,
  ADD CONSTRAINT multi_round_questions_round_id_fkey
    FOREIGN KEY (round_id) REFERENCES multi_rounds(id) ON DELETE CASCADE;

ALTER TABLE multi_rounds
  DROP CONSTRAINT IF EXISTS multi_rounds_multi_round_interview_id_fkey,
  ADD CONSTRAINT multi_rounds_multi_round_interview_id_fkey
    FOREIGN KEY (multi_round_interview_id) REFERENCES multi_round_interviews(id) ON DELETE CASCADE;

ALTER TABLE interview_agent_runs
  DROP CONSTRAINT IF EXISTS interview_agent_runs_multi_round_interview_id_fkey,
  ADD CONSTRAINT interview_agent_runs_multi_round_interview_id_fkey
    FOREIGN KEY (multi_round_interview_id) REFERENCES multi_round_interviews(id) ON DELETE CASCADE;

ALTER TABLE multi_round_idempotency
  DROP CONSTRAINT IF EXISTS multi_round_idempotency_multi_round_interview_id_fkey,
  ADD CONSTRAINT multi_round_idempotency_multi_round_interview_id_fkey
    FOREIGN KEY (multi_round_interview_id) REFERENCES multi_round_interviews(id) ON DELETE CASCADE;

ALTER TABLE multi_round_interviews
  DROP CONSTRAINT IF EXISTS multi_round_interviews_interview_session_id_fkey,
  ADD CONSTRAINT multi_round_interviews_interview_session_id_fkey
    FOREIGN KEY (interview_session_id) REFERENCES interview_sessions(id) ON DELETE CASCADE;
