-- 知识库建立层优化:持久化解析文本 + HNSW 建索引参数调优

-- 1. 持久化解析后的纯文本，支持低成本重新切分/重新向量化（无需再从 RustFS 下载并重新解析）
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS parsed_content TEXT;

-- 2. HNSW 建索引参数调优（建索引期参数；ef_search 属检索期，不在此处调整）
--    m=32（默认16）：图连通度更高，召回质量提升；ef_construction=200（默认64）：建图更精细。
--    代价：建索引时间约 3×、图内存约 2×，属于一次性成本。
--    initialize-schema=false 保证 Spring AI auto-config 不会与 Flyway 冲突；V20260723 已应用不会重跑。
DROP INDEX IF EXISTS spring_ai_vector_index;
CREATE INDEX spring_ai_vector_index
  ON vector_store USING hnsw (embedding public.vector_cosine_ops)
  WITH (m = 32, ef_construction = 200);
