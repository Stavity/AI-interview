ALTER TABLE knowledge_base_question_tags
  DROP CONSTRAINT knowledge_base_question_tag_source_check;

ALTER TABLE knowledge_base_question_tags
  ADD CONSTRAINT knowledge_base_question_tag_source_check
    CHECK (tagging_source IN ('MANUAL', 'LLM', 'IMPORT', 'LEGACY', 'BACKFILLED'));
