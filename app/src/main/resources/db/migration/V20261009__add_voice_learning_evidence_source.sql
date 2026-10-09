ALTER TABLE learning_assessment_evidence
  ALTER COLUMN source_answer_id DROP NOT NULL,
  ADD COLUMN source_voice_message_id BIGINT;

ALTER TABLE learning_assessment_evidence
  ADD CONSTRAINT fk_learning_evidence_voice_message_same_owner
    FOREIGN KEY (source_voice_message_id, owner_user_id)
    REFERENCES voice_interview_messages(id, owner_user_id) ON DELETE CASCADE,
  ADD CONSTRAINT learning_evidence_source_reference_check
    CHECK (
      (source_type = 'TEXT_INTERVIEW'
        AND source_answer_id IS NOT NULL
        AND source_voice_message_id IS NULL)
      OR
      (source_type = 'VOICE_INTERVIEW'
        AND source_answer_id IS NULL
        AND source_voice_message_id IS NOT NULL)
    );

CREATE INDEX idx_learning_evidence_voice_message
  ON learning_assessment_evidence(owner_user_id, source_voice_message_id)
  WHERE source_voice_message_id IS NOT NULL;
