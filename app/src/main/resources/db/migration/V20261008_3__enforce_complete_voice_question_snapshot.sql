ALTER TABLE voice_interview_messages
  DROP CONSTRAINT voice_message_tag_snapshot_check;

ALTER TABLE voice_interview_messages
  ADD CONSTRAINT voice_message_tag_snapshot_check
    CHECK (
      (
        tagging_status IS NULL
        AND taxonomy_version IS NULL
        AND primary_knowledge_code IS NULL
        AND secondary_knowledge_codes_json IS NULL
        AND question_difficulty_level IS NULL
        AND question_type IS NULL
      )
      OR
      (
        tagging_status IS NOT NULL
        AND taxonomy_version IS NOT NULL
        AND primary_knowledge_code IS NOT NULL
        AND secondary_knowledge_codes_json IS NOT NULL
        AND question_difficulty_level IS NOT NULL
        AND question_type IS NOT NULL
      )
    );
