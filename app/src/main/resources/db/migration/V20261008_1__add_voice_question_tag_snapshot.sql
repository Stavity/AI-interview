ALTER TABLE voice_interview_messages
  ADD COLUMN question_difficulty_level SMALLINT,
  ADD COLUMN question_type VARCHAR(32),
  ADD COLUMN primary_knowledge_code VARCHAR(255),
  ADD COLUMN secondary_knowledge_codes_json TEXT,
  ADD COLUMN taxonomy_version INTEGER,
  ADD COLUMN tagging_status VARCHAR(32);

ALTER TABLE voice_interview_messages
  ADD CONSTRAINT voice_message_difficulty_check
    CHECK (question_difficulty_level IS NULL OR question_difficulty_level BETWEEN 1 AND 5),
  ADD CONSTRAINT voice_message_question_type_check
    CHECK (question_type IS NULL OR question_type IN (
      'CONCEPT', 'PRINCIPLE', 'COMPARISON', 'CODING', 'SQL', 'DESIGN',
      'SCENARIO', 'DEBUGGING', 'CODE_REVIEW', 'PROJECT', 'OPERATION', 'BEHAVIORAL'
    )),
  ADD CONSTRAINT voice_message_tagging_status_check
    CHECK (tagging_status IS NULL OR tagging_status IN ('REVIEW_REQUIRED', 'VERIFIED', 'LEGACY_UNTAGGED')),
  ADD CONSTRAINT voice_message_tag_snapshot_check
    CHECK (
      (tagging_status IS NULL AND taxonomy_version IS NULL AND primary_knowledge_code IS NULL)
      OR
      (tagging_status IS NOT NULL AND taxonomy_version IS NOT NULL AND primary_knowledge_code IS NOT NULL)
    ),
  ADD CONSTRAINT fk_voice_message_primary_knowledge
    FOREIGN KEY (taxonomy_version, primary_knowledge_code)
    REFERENCES question_knowledge_nodes(taxonomy_version, code);
