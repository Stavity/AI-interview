ALTER TABLE knowledge_base_questions
  ADD COLUMN difficulty_level SMALLINT,
  ADD COLUMN question_type VARCHAR(32),
  ADD COLUMN source_type VARCHAR(32),
  ADD COLUMN source_ref TEXT,
  ADD COLUMN taxonomy_version INTEGER REFERENCES question_taxonomy_versions(version),
  ADD COLUMN tagging_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNTAGGED';

ALTER TABLE knowledge_base_questions
  ADD CONSTRAINT knowledge_base_question_difficulty_level_check
    CHECK (difficulty_level IS NULL OR difficulty_level BETWEEN 1 AND 5),
  ADD CONSTRAINT knowledge_base_question_type_check
    CHECK (question_type IS NULL OR question_type IN (
      'CONCEPT', 'PRINCIPLE', 'COMPARISON', 'CODING', 'SQL', 'DESIGN', 'SCENARIO',
      'DEBUGGING', 'CODE_REVIEW', 'PROJECT', 'OPERATION', 'BEHAVIORAL'
    )),
  ADD CONSTRAINT knowledge_base_question_source_type_check
    CHECK (source_type IS NULL OR source_type IN (
      'SYSTEM_SEEDED', 'MANUAL', 'KNOWLEDGE_BASE', 'MOCK_INTERVIEW_GENERATED',
      'IMPORTED', 'USER_CREATED'
    )),
  ADD CONSTRAINT knowledge_base_question_tagging_status_check
    CHECK (tagging_status IN ('REVIEW_REQUIRED', 'VERIFIED', 'LEGACY_UNTAGGED'));

UPDATE knowledge_base_questions
SET source_type = CASE
      WHEN kb_content_hash IS NOT NULL THEN 'KNOWLEDGE_BASE'
      ELSE 'IMPORTED'
    END,
    source_ref = jsonb_strip_nulls(jsonb_build_object(
      'knowledgeBaseId', knowledge_base_id,
      'documentHash', kb_content_hash,
      'chunkIds', '[]'::jsonb,
      'sourceLabel', CASE
        WHEN kb_content_hash IS NOT NULL THEN 'legacy knowledge-base question'
        ELSE 'legacy question without preserved document hash'
      END
    ))::text
WHERE source_type IS NULL;

CREATE TABLE knowledge_base_question_tags (
  question_id BIGINT NOT NULL REFERENCES knowledge_base_questions(id) ON DELETE CASCADE,
  taxonomy_version INTEGER NOT NULL,
  node_code VARCHAR(255) NOT NULL,
  relation_type VARCHAR(20) NOT NULL,
  relevance_weight NUMERIC(5,4) NOT NULL DEFAULT 1,
  tagging_source VARCHAR(20) NOT NULL,
  confidence NUMERIC(5,4),
  PRIMARY KEY (question_id, node_code),
  CONSTRAINT fk_kb_question_tag_node
    FOREIGN KEY (taxonomy_version, node_code)
    REFERENCES question_knowledge_nodes(taxonomy_version, code),
  CONSTRAINT knowledge_base_question_tag_relation_check
    CHECK (relation_type IN ('PRIMARY', 'SECONDARY')),
  CONSTRAINT knowledge_base_question_tag_source_check
    CHECK (tagging_source IN ('MANUAL', 'LLM', 'IMPORT', 'LEGACY')),
  CONSTRAINT knowledge_base_question_tag_weight_check
    CHECK (relevance_weight > 0 AND relevance_weight <= 1),
  CONSTRAINT knowledge_base_question_tag_confidence_check
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE UNIQUE INDEX uq_kb_question_single_primary_tag
  ON knowledge_base_question_tags(question_id)
  WHERE relation_type = 'PRIMARY';

CREATE INDEX idx_kb_question_tag_node
  ON knowledge_base_question_tags(taxonomy_version, node_code, relation_type);
