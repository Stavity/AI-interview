# RAG 链路优化交接文档

> 日期：2026-09-18　分支：`redesign-kafka`
> 范围：知识库**建立层**（已提交 `cae4dcf`）+ 检索层（**未提交**，工作区）

---

## 0. 背景与总体结论

本次工作把知识库 RAG 链路从"朴素实现"升级为工程化链路，分两层推进：

**优化前的问题**

| 层 | 问题 |
|---|---|
| 建立层 | `TokenTextSplitter.builder().build()` 全默认：800 token 硬切、标点只认 ASCII `['.','?','!','\n']`（**不含中文 `。？！`**）、**类本身无 overlap 参数**、tokenizer 用 `CL100K_BASE`（与 Qwen embedding 不匹配）。chunk 无语义 metadata。解析文本不持久化，整段塞进 `async_task.input_payload` JSONB。HNSW 全默认参数。 |
| 检索层 | 纯 dense 单通道，无 rerank / 无关键词通道 / 无融合。`candidateQueries` first-hit-wins，第二个 query 召回浪费且无去重。上下文直接 `\n\n---\n\n` 拼接，不分章节、不扩窗口、无字符上限。`history.*` 配置在 QueryService 里实际未接。 |

**优化后的完整链路**

```
【建立层】
上传 → Tika 解析 → TextCleaning → 结构化切分(markdown 标题 + 中文标点 + overlap)
  → 语义 metadata(chunk_index/parent_section/source_heading/chunk_hash/total_chunks)
  → 分批 embedding(DashScope text-embedding-v3, batch≤10)
  → PgVector 两阶段写入(pending → promote) → HNSW(m=32, ef_construction=200)

【检索层】
多 query 改写(可选) → dense over-fetch ∥ BM25 关键词召回(并行)
  → RRF 融合(k=60) → chunk_hash 去重 → DashScope rerank 精排(topN)
  → 邻块扩展(chunk_index ± window) → section-aware 装配(## heading + 字符上限)
  → LLM 生成
```

**三层降级已落地**：hybrid 关 → 纯 rerank；rerank provider 不可用/调用失败 → 原序取 topN；BM25 抛异常（paradedb 缺失）→ 返回空列表，不阻塞 dense。

**所有新能力默认关闭**，可灰度开启：`rerank.enabled` / `hybrid.enabled` / `multi-query.enabled` 均为 `false`。

---

## 1. 建立层（已提交 `cae4dcf "fix kb"`）

13 文件，+629/-33。

### 1.1 新增文件

| 文件 | 作用 |
|---|---|
| `modules/knowledgebase/service/KnowledgeBaseChunkService.java` | **核心**：取代 `TokenTextSplitter`。markdown 标题层级切段 → 段内按中英文标点切句 → 贪心打包 → overlap → 写语义 metadata |
| `modules/knowledgebase/service/KnowledgeBaseChunkProperties.java` | 分块参数（`app.ai.rag.chunk.*`） |
| `db/migration/V20260918__add_parsed_content_and_tune_hnsw.sql` | 加 `knowledge_bases.parsed_content TEXT`；重建 HNSW 索引为 `m=32, ef_construction=200` |
| `test/.../KnowledgeBaseChunkServiceTest.java` | 切分器单测 14 例 |

### 1.2 关键设计决策（含为什么）

1. **字符级切分，不是 token 级**
   `targetChunkChars=1000`（≈500–700 Qwen tokens，在 DashScope text-embedding-v3 的 2048 token 输入限内）。消除 `CL100K_BASE`（OpenAI tokenizer）与 Qwen embedding 模型的不匹配；简单、可测、对中文可预测。

2. **独立 `Service`，不继承 Spring AI `TextSplitter`**
   `TextSplitter.splitText(String)` 返回 `List<String>`，无法挂 per-chunk metadata；继承后整体重写 `apply()` 等于绕开抽象，无意义。故改为独立服务返回 `List<Document>`（metadata 已挂好）。

3. **中文标点是本次最高 ROI 的一处**
   句子边界正则 `[。？！.!?…\n]`。原实现在中文段落里找不到 ASCII 标点，`lastPunctuation` 常为 -1，于是在 800 token 处**硬截断**——连"按标点边界切分"这个承诺都没兑现。

4. **overlap 不跨 section**
   避免跨章节内容串味。段内相邻 chunk 前缀上一块末尾 `overlapChars` 字符。

5. **两阶段 metadata 写入对语义字段安全（已核实）**
   `applyPendingMetadata` 用 `put()` 合并写入，语义字段与 kb 管理字段共存；`VectorRepository.promoteVectorJob` 的 SQL 只 `jsonb_set` 改 `kb_id`、只 `- 'kb_vector_job_id' - 'kb_target_id'` 删两个键，**语义字段全程原样保留至检索期**。

6. **`chunk_hash` 现在就写，增量重切留 v2**
   `activateVectorJob` 维持"全删再 promote"的全量替换。增量 diff 需要预查已有 hash 并比对，边际收益小。

7. **持久化 parsed_content + 瘦身 async 载荷**
   原本整段文本作为 JSON 字符串塞进 `async_task.input_payload`（大文档膨胀）。现在存 `knowledge_bases.parsed_content`，async 任务只带 businessId，handler 从 KB 行读。重切免重下重解析。PG TOAST 自动外存 >2KB 文本，async 路径只读一次，无性能问题。

### 1.3 修改文件要点

| 文件 | 改动 |
|---|---|
| `model/KnowledgeBaseEntity.java` | 加 `parsedContent`（`@Column(columnDefinition="TEXT")`） |
| `service/KnowledgeBasePersistenceService.java` | `saveKnowledgeBase(...)` 增 `parsedContent` 入参 |
| `service/KnowledgeBaseUploadService.java` | 传 content 给持久化；`sendVectorizeTask(id)` 去掉 content；**`revectorize` 读 `parsedContent`，老行（迁移前）为 null 时回退 `downloadAndParseContent` 并回写** |
| `listener/VectorizeTaskSubmission.java` | 签名 `sendVectorizeTask(Long id)`，`tasks.submit(..., Map.of())` |
| `task/KnowledgeBaseVectorizeTaskHandler.java` | 从 KB 行读 `parsedContent`，不再 `context.required("content")` |
| `service/KnowledgeBaseVectorService.java` | 注入 `KnowledgeBaseChunkService` 替换 `TextSplitter`；完成回调里回填 `chunkCount` |
| `repository/VectorRepository.java` | 加 `updateChunkCount`（补缺口：`chunk_count` 原先从未被设置） |
| `application.yml` | 加 `app.ai.rag.chunk.*` |

### 1.4 建立层配置

```yaml
app.ai.rag.chunk:
  target-chunk-chars:        ${APP_AI_RAG_CHUNK_TARGET_CHARS:1000}
  overlap-chars:             ${APP_AI_RAG_CHUNK_OVERLAP_CHARS:200}
  min-chunk-chars:           ${APP_AI_RAG_CHUNK_MIN_CHARS:20}
  include-heading-in-chunk:  ${APP_AI_RAG_CHUNK_INCLUDE_HEADING:true}
```

---

## 2. 检索层（**未提交**，工作区 23 文件）

修改 17 文件（+598/-23）+ 新增 6 文件。

### 2.1 新增文件

| 文件 | 作用 |
|---|---|
| `common/ai/RerankService.java` | DashScope `TextReRank` 封装（gte-rerank-v2） |
| `modules/knowledgebase/repository/KeywordSearchRepository.java` | BM25 关键词通道（`@@@` + `paradedb.score(id)`） |
| `db/migration/V20260919__add_rerank_provider_support.sql` | provider 加 `supports_rerank`/`rerank_model`；global setting 加 `default_rerank_provider_id` |
| `db/migration/V20260920__hybrid_bm25_jsonb.sql` | metadata JSON→JSONB + GIN 索引 + paradedb BM25 索引（**防御性创建**） |
| `test/.../RerankServiceTest.java` | 7 例 |
| `test/.../KnowledgeBaseQueryServiceTest.java` | 9 例 |

### 2.2 修改文件要点

**Provider 全链路加 rerank 能力**（镜像已有 `supports_embedding`/`embedding_model` 模式）：
`model/LlmProviderEntity.java`、`model/LlmGlobalSettingEntity.java`、`dto/{ProviderDTO,CreateProviderRequest,UpdateProviderRequest,DefaultProviderDTO}.java`、`config/LlmProviderProperties.java`、`service/LlmProviderBootstrapService.java`（seed dashscope `supports_rerank=true`）、`service/LlmProviderConfigService.java`（DTO 链路 + create/update + `updateDefaultRerankProvider`）、`controller/LlmProviderController.java`（`PUT /default-rerank-provider`）。

**核心编排**：
- `common/ai/LlmProviderRegistry.java` — 加 `rerankServiceCache`、`ProviderSnapshot` 扩 `supportsRerank`/`rerankModel`、`resolveDefaultRerankProviderId()`、`getRerankService()`（无可用 provider 返回 `null`）
- `modules/knowledgebase/service/KnowledgeBaseQueryService.java` — 检索编排重构（见 2.4）
- `modules/knowledgebase/repository/VectorRepository.java` — 加 `findNeighborChunks`（首个 SELECT 查询，此前只有 DELETE/UPDATE）
- `modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 加 `findNeighborChunks` 委托方法
- `modules/knowledgebase/service/KnowledgeBaseQueryProperties.java` — 加 rerank/hybrid/context/multi-query 四个子节；`Search.mediumQueryLength` 把原代码里 `<=12` 的魔法数提为可配
- `resources/prompts/knowledgebase-query-rewrite.st` — 加 `{multi_query_instruction}` 占位，支持单/多 query 两种模式
- `resources/application.yml` — 各配置子节 + dashscope `supports-rerank`/`rerank-model`

### 2.3 关键设计决策（含为什么）

1. **Rerank 走 DashScope SDK，不走 RestClient / OpenAIClient**
   DashScope rerank endpoint **不是 OpenAI 兼容**的，现有 `ApiPathResolver.buildOpenAiClient()` 只懂 OpenAI 形状的 API，无法复用。Spring AI 2.0.0 **没有 `RerankModel` 抽象**。而 `com.alibaba:dashscope-sdk-java:2.22.7` 已在 `build.gradle`（原用于 QwenAsr/Tts），原生支持 rerank schema。

2. **⚠️ SDK 真实 API 与初版设计不同（已反编译核实）**
   规划阶段曾误判为 `TextReRank(String apiKey)` 单参构造。**实际**：
   - 构造：`TextReRank()` / `TextReRank(protocol)` / `TextReRank(protocol, baseUrl)` — **无 apiKey 参数**
   - apiKey 走 `TextReRankParam.builder().apiKey(...)`（继承 `HalfDuplexParamBase.apiKey`，`@SuperBuilder`），与项目里 QwenAsr 的 `.apikey(...)` 惯例一致
   - 模型常量：`TextReRank.Models.GTE_RERANK_V2 = "gte-rerank-v2"`
   - 结果：`.call(param).getOutput().getResults()` → 每项 `.getIndex()`（0-based 输入位置）+ `.getRelevanceScore()`
   - 抛 `ApiException`/`NoApiKeyException`/`InputRequiredException`（checked），故宽 catch `Exception`
   - **用无参 `new TextReRank()` 走原生 endpoint，不要把 provider 的 `compatible-mode/v1` base_url 传进去**

3. **metadata JSON→JSONB 安全（反编译 Spring AI 2.0.0 核实）**
   `DocumentRowMapper` 用 `ResultSet.getObject("metadata", String.class)` + Jackson 读写，对 `json`/`jsonb` 行为一致（PG JDBC 两者都返回文本表示）；`PgVectorSchemaValidator` 只查列存在、不强制类型；`initialize-schema=false` 时不跑建表。现有 `promoteVectorJob` 的 `::jsonb`/`::json` cast 在 jsonb 列上变 no-op。**建立层代码无需改动**。

4. **关键词通道选 paradedb pg_bm25**（用户决策）
   真 BM25 + 中文分词，质量最好。代价是运维前置（见 §4）。备选 pg_trgm 对中文只是 trigram 子串匹配，比较钝；PG 内置 FTS 默认 `simple` 配置对中文无分词、基本无效（已装扩展只有 `hstore`/`uuid-ossp`/`vector`）。

5. **`history.*` 配置接入**
   `RagChatSessionService.loadHistoryMessages` 已按 `maxMessages` 截断，但 `sanitizeHistory` 原先只做空检 —— 若直调 `answerQuestionStream`，历史无界。现改为尊重 `history.enabled` + 截断到最近 `maxMessages` 条，补第二道闸。

6. **跳过 HyDE**：双倍 embedding 成本、收益不确定，不做。

### 2.4 `KnowledgeBaseQueryService` 检索编排

```
retrieveRelevantDocs(queryContext, kbIds)
├── hybrid.enabled        → retrieveHybrid   (dense over-fetch + BM25 → rrfFuse → applyRerank)
├── rerank.enabled        → retrieveRerank   (dense over-fetch 合并 → dedupByChunkHash → applyRerank)
└── 都关                   → retrieveDenseFirstHit (原 first-hit-wins 行为，完全保留)
```

辅助方法：
- `applyRerank(...)` — 取 `getRerankService()`；为 `null` 或 `rerank(...)` 返回 `null` 时降级取原序 `topN`；否则按 index 映射回 Document、过滤 `score < minScore`
- `rrfFuse(dense, keyword, rrfK)` / `rrfAccumulate(...)` — RRF：`score = Σ 1/(rrfK + rank)`，rank 从 1 起，按融合分降序
- `dedupByChunkHash(...)` / `chunkKey(d)` — 按 `chunk_hash` 去重，无 hash 的老数据回退 `Document.getId()`
- `buildContext(docs)` — **替代原来的 `\n\n---\n\n` 直拼**：邻块扩展 → 去重 → 按 `parent_section` 分组 → 组内按 `chunk_index` 升序 → `includeSourceHeading` 时加 `## {source_heading}` 标记行 → 超 `maxContextChars` 截断
- `rewriteMultiQuery(...)` — 多 query 改写，按行 split、取前 N 个；失败回退单 query
- `resolveSearchParams(...)` — rerank 开启时统一返回 `(candidateTopK, minScore)`，不再按长度分档

### 2.5 检索层配置（默认值即当前行为，全部关闭）

```yaml
app.ai:
  default-rerank-provider: dashscope
  providers.dashscope:
    supports-rerank: true
    rerank-model: ${APP_AI_RAG_RERANK_MODEL:gte-rerank-v2}

app.ai.rag:
  rerank:
    enabled:          ${APP_AI_RAG_RERANK_ENABLED:false}
    candidate-top-k:  ${APP_AI_RAG_RERANK_CANDIDATE_TOP_K:30}
    top-n:            ${APP_AI_RAG_RERANK_TOP_N:6}
    min-score:        ${APP_AI_RAG_RERANK_MIN_SCORE:0.1}
    model:            ${APP_AI_RAG_RERANK_MODEL:gte-rerank-v2}
  hybrid:
    enabled:          ${APP_AI_RAG_HYBRID_ENABLED:false}
    keyword-top-k:    ${APP_AI_RAG_HYBRID_KEYWORD_TOP_K:20}
    rrf-k:            ${APP_AI_RAG_HYBRID_RRF_K:60}
  context:
    expand-window:         ${APP_AI_RAG_CONTEXT_EXPAND_WINDOW:1}
    max-context-chars:     ${APP_AI_RAG_CONTEXT_MAX_CHARS:8000}
    include-source-heading:${APP_AI_RAG_CONTEXT_INCLUDE_SOURCE_HEADING:true}
  multi-query:
    enabled:          ${APP_AI_RAG_MULTI_QUERY_ENABLED:false}
    variant-count:    ${APP_AI_RAG_MULTI_QUERY_VARIANT_COUNT:3}
```

---

## 3. chunk metadata schema（`vector_store.metadata`）

| 字段 | 写入方 | 过 promote | 用途 |
|---|---|---|---|
| `chunk_index` | 建立层切分 | 保留 | 顺序还原、邻块扩展 |
| `total_chunks` | 建立层（二趟回填） | 保留 | 总块数 |
| `parent_section` | 建立层切分 | 保留 | section 分组装配、出处回显 |
| `source_heading` | 建立层切分 | 保留 | `## heading` 标记行 |
| `chunk_hash` | 建立层切分（SHA-256 归一化文本） | 保留 | 检索期去重 / 未来增量 |
| `kb_id` | `applyPendingMetadata` → `promoteVectorJob` | **改写**为真实 id | 检索过滤 |
| `kb_target_id` / `kb_vector_job_id` | `applyPendingMetadata` | promote 时**删除** | 两阶段提交管理 |
| `kb_vector_created_at` | `applyPendingMetadata` | 保留 | 审计 |

检索期读取零成本：Spring AI PgVectorStore 把整条 metadata 反序列化进 `Document.getMetadata()`。

---

## 4. ⚠️ 遗留事项 / 需要接手人处理

### 4.1 检索层未提交
工作区 23 个文件（17 改 + 6 新）尚未 commit。

### 4.2 JDK 版本（重要）
项目要求 **JDK 25** 工具链（`app/build.gradle`：`JavaLanguageVersion.of(25)` + `options.release = 25`），且代码用了 Java 22+ preview 特性（`_` 未命名变量，见 `RateLimitAspect.java:159`、`GlobalExceptionHandler.java:94`）。

本机只有 JDK 21，foojay 自动下载报 `JvmVendorSpec.IBM_SEMERU` 错误。**验证时临时把工具链降到 21 并加 `--enable-preview`，验证完已完全还原 `app/build.gradle`**（`git diff app/build.gradle` 为空，已确认）。

→ **接手人请在 JDK 25 环境重跑 `./gradlew test`。**

### 4.3 paradedb 运维前置（Stage 3 的唯一外部依赖）
- BM25 关键词通道需在 **PG 服务器安装 paradedb 扩展二进制**，`CREATE EXTENSION` 才能成功
- Docker / testcontainers 需换成含 paradedb 的镜像
- **不装也能跑**：`V20260920` 的 BM25 部分包在 `DO $$ ... EXCEPTION WHEN OTHERS THEN RAISE NOTICE ... END $$` 里，扩展缺失时跳过索引创建、不阻塞启动；`KeywordSearchRepository` catch 异常返回空列表。但此时 `hybrid.enabled=true` 的关键词通道恒返回空，等价于纯 dense。
- **中文 tokenizer 未调优**：现用 `text_fields = '{"content": {}}'` 默认 tokenizer。装好 paradedb 后应按版本换成 `ngram`（CJK 稳妥兜底）/ `jieba` / `icu` 才能发挥中文分词优势。**此项在无扩展环境下无法验证。**

### 4.4 测试覆盖边界（诚实说明）
`KnowledgeBaseQueryServiceTest` 只验证**检索阶段**行为（调用参数、去重、双路、降级）。因为 `ChatClient` 未 mock，检索成功后生成阶段必然抛 `BusinessException`，测试用 `askIgnoringLlm(...)` 显式忽略并在注释里说明。

**未被单测覆盖，需端到端验证**：
- rerank 真实排序质量（依赖 DashScope API）
- BM25 真实召回质量（依赖 paradedb）
- `findNeighborChunks` 的实际 SQL 行为（需 testcontainers PG + jsonb + pgvector）
- 多 query 改写的 LLM 输出解析（依赖真实 LLM）

`RerankServiceTest` 因 `TextReRank` 在方法内 `new`、无法注入 mock，只覆盖入参校验 / apiKey 缺失 / 调用失败降级三类不依赖网络的分支。

### 4.5 建立层可选后续（v2）
- **增量重向量化**：`chunk_hash` 已就位，但 `activateVectorJob` 仍是全量替换。做增量需预查已有 hash 并 diff。
- **摘要向量 + 原文向量双存**：长文档 chunk 检索精度可再提，代价是入库多一次 LLM 调用。

---

## 5. 验证清单

```bash
# 1. 全量测试（JDK 25 环境）
./gradlew test

# 2. Flyway 迁移
#    确认 V20260918 / V20260919 / V20260920 均已应用
#    - knowledge_bases.parsed_content 存在
#    - llm_provider_config.supports_rerank / rerank_model 存在
#    - llm_global_setting.default_rerank_provider_id 存在
#    - vector_store.metadata 类型为 jsonb
#    - 索引：spring_ai_vector_index(m=32, ef_construction=200)、
#            vector_store_metadata_gin、vector_store_content_bm25(需 paradedb)
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'vector_store';
```

**端到端**

| # | 场景 | 预期 |
|---|---|---|
| 1 | 上传中文 markdown 知识库 | `parsed_content` 非空；`async_task.input_payload` 为 `{}`；`vector_store.metadata` 含 `chunk_index`/`parent_section`/`source_heading`/`chunk_hash`；`chunk_count` 已回填 |
| 2 | 老行（迁移前 KB）触发 `revectorize` | 走 `downloadAndParseContent` 回退并回写 `parsed_content` |
| 3 | `rerank.enabled=true` 提问 | 日志：`similaritySearch(candidateTopK=30)` → `RerankService.rerank` → `topN=6` |
| 4 | `rerank.enabled=false` | 走原 first-hit-wins，行为与优化前一致 |
| 5 | `hybrid.enabled=true` | 日志：`hybrid 融合: dense=?, keyword=?, 融合后=?` |
| 6 | 断掉 BM25（模拟 paradedb 缺失） | 自动降级走 dense，不报错 |
| 7 | 上下文扩展 | 命中 chunk 的 `chunk_index` 邻块出现在 prompt；带 `## {source_heading}` 标记；超 `maxContextChars` 截断 |
| 8 | `history.enabled=false` | `sanitizeHistory` 返回空；`maxMessages` 超额时截断为最近 N 条 |

---

## 6. 核心文件速查

**建立层**
- `modules/knowledgebase/service/KnowledgeBaseChunkService.java` — 切分核心
- `modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 向量化 + 两阶段提交
- `modules/knowledgebase/service/KnowledgeBaseUploadService.java` — 上传 / revectorize 入口

**检索层**
- `modules/knowledgebase/service/KnowledgeBaseQueryService.java` — **检索编排中枢，每个 Stage 都改到它**
- `common/ai/RerankService.java` — rerank 封装
- `common/ai/LlmProviderRegistry.java` — provider 解析 + 三类缓存
- `modules/knowledgebase/repository/KeywordSearchRepository.java` — BM25 通道
- `modules/knowledgebase/repository/VectorRepository.java` — 向量生命周期 SQL + 邻块扩展

**配置**
- `modules/knowledgebase/service/KnowledgeBaseQueryProperties.java` — `app.ai.rag.*` 全部检索参数
- `modules/knowledgebase/service/KnowledgeBaseChunkProperties.java` — `app.ai.rag.chunk.*`
- `resources/application.yml`
