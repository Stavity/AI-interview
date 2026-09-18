-- 检索层 rerank 基础设施:provider 增加 rerank 能力字段
ALTER TABLE llm_provider_config
  ADD COLUMN IF NOT EXISTS supports_rerank BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS rerank_model VARCHAR(128);

ALTER TABLE llm_global_setting
  ADD COLUMN IF NOT EXISTS default_rerank_provider_id VARCHAR(64);
