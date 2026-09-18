-- 检索层 hybrid：metadata JSON→JSONB + paradedb BM25 全文索引

-- 1. metadata JSON → JSONB（为 kb_id 过滤与邻居扩展加速；经核实对 Spring AI PgVectorStore 安全：
--    DocumentRowMapper 用 String + Jackson 读写，PgVectorSchemaValidator 不强制类型）
ALTER TABLE vector_store ALTER COLUMN metadata TYPE jsonb USING metadata::jsonb;

-- metadata GIN 索引，加速 kb_id / chunk_index 等 metadata 键过滤
CREATE INDEX IF NOT EXISTS vector_store_metadata_gin
  ON vector_store USING GIN (metadata);

-- 2. paradedb BM25 全文索引（运维前置：需 PG 服务器安装 paradedb 扩展二进制）
--    防御性创建：若 paradedb 不可用，跳过 bm25 索引，不阻塞启动；KeywordSearchRepository 会优雅降级。
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS paradedb;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'vector_store_content_bm25') THEN
    -- 默认 tokenizer；中文分词 tokenizer 类型按已装 paradedb 版本调优（ngram/jieba/icu）
    CREATE INDEX vector_store_content_bm25
      ON vector_store USING bm25 (content)
      WITH (text_fields = '{"content": {}}');
  END IF;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'paradedb 不可用，跳过 BM25 索引: %', SQLERRM;
END
$$;
