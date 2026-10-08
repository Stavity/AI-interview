-- Add discriminator ownership to all user-private rows. The single existing active
-- account is the only safe legacy recipient; refuse to guess if that invariant changes.
LOCK TABLE users IN SHARE MODE;

DO $$
DECLARE
  total_user_count BIGINT;
  active_user_count BIGINT;
  legacy_owner_id BIGINT;
  table_name TEXT;
  owner_tables TEXT[] := ARRAY[
    'resumes',
    'resume_analyses',
    'interview_sessions',
    'interview_answers',
    'interview_schedule',
    'knowledge_bases',
    'knowledge_base_questions',
    'knowledge_base_question_tags',
    'rag_chat_sessions',
    'rag_chat_messages',
    'chat_sessions',
    'chat_messages',
    'voice_interview_sessions',
    'voice_interview_messages',
    'voice_interview_evaluations',
    'learning_progress',
    'learning_plans',
    'learning_plan_tasks',
    'learning_reminders'
  ];
  id_owner_tables TEXT[] := ARRAY[
    'resumes',
    'resume_analyses',
    'interview_sessions',
    'interview_answers',
    'interview_schedule',
    'knowledge_bases',
    'knowledge_base_questions',
    'rag_chat_sessions',
    'rag_chat_messages',
    'chat_sessions',
    'chat_messages',
    'voice_interview_sessions',
    'voice_interview_messages',
    'voice_interview_evaluations',
    'learning_progress',
    'learning_plans',
    'learning_plan_tasks',
    'learning_reminders'
  ];
BEGIN
  SELECT COUNT(*) INTO total_user_count FROM users;
  SELECT COUNT(*), MIN(id)
    INTO active_user_count, legacy_owner_id
    FROM users
   WHERE status = 'ACTIVE';

  IF total_user_count <> 1 OR active_user_count <> 1 OR legacy_owner_id IS NULL THEN
    RAISE EXCEPTION
      'User ownership migration requires exactly one total and ACTIVE account; found total %, active %',
      total_user_count, active_user_count;
  END IF;

  FOREACH table_name IN ARRAY owner_tables LOOP
    EXECUTE format('ALTER TABLE %I ADD COLUMN owner_user_id BIGINT', table_name);
    EXECUTE format('UPDATE %I SET owner_user_id = %L', table_name, legacy_owner_id);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN owner_user_id SET NOT NULL', table_name);
    EXECUTE format(
      'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (owner_user_id) REFERENCES users(id)',
      table_name, 'fk_' || table_name || '_owner_user');
    EXECUTE format('CREATE INDEX %I ON %I (owner_user_id)',
      'idx_' || table_name || '_owner_user', table_name);
  END LOOP;

  FOREACH table_name IN ARRAY id_owner_tables LOOP
    EXECUTE format('CREATE UNIQUE INDEX %I ON %I (id, owner_user_id)',
      'uq_' || table_name || '_id_owner', table_name);
  END LOOP;

  -- Vector rows are external to Hibernate's tenant filter. Backfill their metadata
  -- from the now-owned knowledge base and abort if any row cannot be assigned.
  UPDATE vector_store v
     SET metadata = jsonb_set(v.metadata::jsonb, '{owner_user_id}',
                              to_jsonb(k.owner_user_id), TRUE)::json
    FROM knowledge_bases k
   WHERE CASE
           WHEN v.metadata->>'kb_target_id' ~ '^[0-9]+$'
             THEN (v.metadata->>'kb_target_id')::BIGINT
           WHEN v.metadata->>'kb_id' ~ '^[0-9]+$'
             THEN (v.metadata->>'kb_id')::BIGINT
           WHEN v.metadata->>'kb_id_long' ~ '^[0-9]+$'
             THEN (v.metadata->>'kb_id_long')::BIGINT
           ELSE NULL
         END = k.id;
  IF EXISTS (SELECT 1 FROM vector_store WHERE metadata->>'owner_user_id' IS NULL) THEN
    RAISE EXCEPTION 'User ownership migration found vector rows without an owning knowledge base';
  END IF;

  -- The outbox remains globally dispatchable; its task carries the owner snapshot.
  ALTER TABLE async_task ADD COLUMN owner_user_id BIGINT;
  UPDATE async_task SET owner_user_id = legacy_owner_id;
  ALTER TABLE async_task ALTER COLUMN owner_user_id SET NOT NULL;
  ALTER TABLE async_task
    ADD CONSTRAINT fk_async_task_owner_user FOREIGN KEY (owner_user_id) REFERENCES users(id);
  CREATE INDEX idx_async_task_owner_user ON async_task (owner_user_id, state, next_run_at);

  -- Built-in providers stay system-owned; custom provider rows belong to one account.
  ALTER TABLE llm_provider_config ADD COLUMN owner_user_id BIGINT;
  UPDATE llm_provider_config
     SET owner_user_id = legacy_owner_id
   WHERE builtin = FALSE;
  ALTER TABLE llm_provider_config
    ADD CONSTRAINT fk_llm_provider_config_owner_user
    FOREIGN KEY (owner_user_id) REFERENCES users(id);
  ALTER TABLE llm_provider_config
    ADD CONSTRAINT ck_llm_provider_config_owner
    CHECK ((builtin = TRUE AND owner_user_id IS NULL)
        OR (builtin = FALSE AND owner_user_id IS NOT NULL));

  IF EXISTS (
      SELECT 1
        FROM llm_global_setting s
        CROSS JOIN LATERAL unnest(ARRAY[
          s.default_chat_provider_id,
          s.default_embedding_provider_id,
          s.default_rerank_provider_id
        ]) AS configured(provider_id)
        LEFT JOIN llm_provider_config p ON p.id = configured.provider_id
       WHERE configured.provider_id IS NOT NULL
         AND (p.id IS NULL OR p.builtin = FALSE)
  ) THEN
    RAISE EXCEPTION 'Global LLM defaults must reference shared built-in providers';
  END IF;
  CREATE INDEX idx_llm_provider_config_owner_user
    ON llm_provider_config (owner_user_id)
    WHERE owner_user_id IS NOT NULL;

  -- File de-duplication and learning evidence are scoped per account.
  ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
  CREATE UNIQUE INDEX uq_resumes_owner_file_hash ON resumes (owner_user_id, file_hash);

  ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;
  CREATE UNIQUE INDEX uq_knowledge_bases_owner_file_hash
    ON knowledge_bases (owner_user_id, file_hash);

  ALTER TABLE learning_progress DROP CONSTRAINT IF EXISTS uk_learning_progress_source;
  ALTER TABLE learning_progress
    ADD CONSTRAINT uk_learning_progress_owner_source UNIQUE (owner_user_id, skill_source_id);

  DROP INDEX IF EXISTS uk_interview_sessions_request_id;
  CREATE UNIQUE INDEX uk_interview_sessions_owner_request_id
    ON interview_sessions (owner_user_id, request_id);

  -- Make parent-child ownership a database invariant, not just an application check.
  ALTER TABLE resume_analyses
    ADD CONSTRAINT fk_resume_analyses_same_owner
    FOREIGN KEY (resume_id, owner_user_id) REFERENCES resumes (id, owner_user_id);
  ALTER TABLE interview_sessions
    ADD CONSTRAINT fk_interview_sessions_resume_same_owner
    FOREIGN KEY (resume_id, owner_user_id) REFERENCES resumes (id, owner_user_id);
  ALTER TABLE interview_sessions
    ADD CONSTRAINT fk_interview_sessions_kb_same_owner
    FOREIGN KEY (knowledge_base_id, owner_user_id) REFERENCES knowledge_bases (id, owner_user_id);
  ALTER TABLE interview_answers
    ADD CONSTRAINT fk_interview_answers_same_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES interview_sessions (id, owner_user_id);
  ALTER TABLE knowledge_base_questions
    ADD CONSTRAINT fk_kb_questions_same_owner
    FOREIGN KEY (knowledge_base_id, owner_user_id) REFERENCES knowledge_bases (id, owner_user_id);
  ALTER TABLE knowledge_base_question_tags
    ADD CONSTRAINT fk_kb_question_tags_same_owner
    FOREIGN KEY (question_id, owner_user_id) REFERENCES knowledge_base_questions (id, owner_user_id);
  ALTER TABLE rag_chat_messages
    ADD CONSTRAINT fk_rag_chat_messages_same_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES rag_chat_sessions (id, owner_user_id);
  ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_same_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES chat_sessions (id, owner_user_id);
  ALTER TABLE voice_interview_sessions
    ADD CONSTRAINT fk_voice_interview_resume_same_owner
    FOREIGN KEY (resume_id, owner_user_id) REFERENCES resumes (id, owner_user_id);
  ALTER TABLE voice_interview_messages
    ADD CONSTRAINT fk_voice_interview_messages_same_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES voice_interview_sessions (id, owner_user_id);
  ALTER TABLE voice_interview_evaluations
    ADD CONSTRAINT fk_voice_interview_evaluations_same_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES voice_interview_sessions (id, owner_user_id);
  ALTER TABLE learning_plan_tasks
    ADD CONSTRAINT fk_learning_plan_tasks_same_owner
    FOREIGN KEY (plan_id, owner_user_id) REFERENCES learning_plans (id, owner_user_id);
  ALTER TABLE learning_reminders
    ADD CONSTRAINT fk_learning_reminders_same_owner
    FOREIGN KEY (task_id, owner_user_id) REFERENCES learning_plan_tasks (id, owner_user_id);

  -- Hibernate writes the many-to-many rows without an owner column. A trigger derives
  -- that owner from the session and rejects linking a foreign user's knowledge base.
  ALTER TABLE rag_session_knowledge_bases ADD COLUMN owner_user_id BIGINT;
  UPDATE rag_session_knowledge_bases link
     SET owner_user_id = session.owner_user_id
    FROM rag_chat_sessions session
   WHERE session.id = link.session_id;
  ALTER TABLE rag_session_knowledge_bases ALTER COLUMN owner_user_id SET NOT NULL;
  ALTER TABLE rag_session_knowledge_bases
    ADD CONSTRAINT fk_rag_session_kb_owner_user
    FOREIGN KEY (owner_user_id) REFERENCES users(id);
  ALTER TABLE rag_session_knowledge_bases
    ADD CONSTRAINT fk_rag_session_kb_same_session_owner
    FOREIGN KEY (session_id, owner_user_id) REFERENCES rag_chat_sessions (id, owner_user_id);
  ALTER TABLE rag_session_knowledge_bases
    ADD CONSTRAINT fk_rag_session_kb_same_kb_owner
    FOREIGN KEY (knowledge_base_id, owner_user_id) REFERENCES knowledge_bases (id, owner_user_id);

END $$;

CREATE FUNCTION enforce_rag_session_knowledge_base_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $trigger$
DECLARE
  session_owner BIGINT;
  knowledge_base_owner BIGINT;
BEGIN
  SELECT owner_user_id INTO session_owner
    FROM rag_chat_sessions WHERE id = NEW.session_id;
  SELECT owner_user_id INTO knowledge_base_owner
    FROM knowledge_bases WHERE id = NEW.knowledge_base_id;
  IF session_owner IS NULL OR knowledge_base_owner IS NULL OR session_owner <> knowledge_base_owner THEN
    RAISE EXCEPTION 'RAG session and knowledge base must have the same owner';
  END IF;
  NEW.owner_user_id := session_owner;
  RETURN NEW;
END;
$trigger$;

CREATE TRIGGER trg_rag_session_knowledge_base_owner
  BEFORE INSERT OR UPDATE ON rag_session_knowledge_bases
  FOR EACH ROW EXECUTE FUNCTION enforce_rag_session_knowledge_base_owner();
