# TRD：题目知识树与出题标签工具设计

> 状态：设计评审稿
> 日期：2026-09-26
> 适用范围：普通模拟面试、简历驱动面试、知识库题目生成、后续学习进度与学习路线
> 当前阶段：只定义题目知识分类、题目元数据、标签查询工具与出题集成，不在本阶段实现学习进度计算

## 1. 背景

当前题目只有较粗粒度的 `skillId`、`type`、`category` 和 `topicSummary`：

- `skillId` 表示面试方向，例如 `java-backend`。
- `type` 通常由 Skill 的一级分类 key 驱动，例如 `JAVA`、`MYSQL`、`REDIS`。
- `category` 是展示名称或模型生成的自由文本。
- `topicSummary` 主要用于历史题目去重。

普通模拟面试在 `InterviewQuestionService` 中一次性生成结构化问题列表；知识库题目在 `KnowledgeBaseQuestionGenerationService` 中根据检索上下文异步生成。两条链路都没有稳定、版本化、可校验的细粒度知识点标识。

现有学习模块虽然已经有 `learning_skill_sources`、`learning_progress` 和路径推荐接口，但题目、答题评分与学习进度之间缺少稳定映射。直接使用自由文本 `category` 更新进度，会产生同义标签、粒度漂移和历史数据不可比较的问题。

因此必须先建立统一题目知识树，并要求所有新生成题目明确携带标准知识节点、难度、题型和来源。

## 2. 设计评审结论

### 2.1 结论

采用“全局知识多叉树 + 模型 Tool Calling 查询标签”的方向可行，适合当前项目，但必须满足以下约束：

1. 知识树只描述“题目考什么”，难度、题型、来源是正交元数据，不进入知识树。
2. 模型只能查询和选择标签，不能创建、修改、删除标签，也不能通过工具直接保存题目。
3. Tool Calling 不是最终可信边界。模型最终输出必须再次经过后端领域服务校验。
4. 工具必须受当前出题上下文限制。普通面试按 `skillId` 限定子树，知识库出题按任务配置和知识库上下文限定候选范围。
5. 标签目录必须版本化。异步题目生成任务提交时固定 `taxonomyVersion`，避免执行期间目录变化造成结果漂移。
6. 不应把整棵知识树放进 Prompt，也不应默认每道题遍历整棵树。服务端先给出允许的一级领域，模型只在局部子树中按需查询。
7. 当前 `StructuredOutputInvoker` 面向无工具的一次性 JSON 输出。新增能力应使用专用出题客户端，不能把标签工具全局挂到所有 `getPlainChatClient()` 调用上。
8. 对不支持 Tool Calling 或调用失败的 Provider，必须有受控降级；不能静默保存无标准标签的 ACTIVE 题目。

### 2.2 本次评审对原设计的修正

| 原始设想 | 评审后设计 |
|---|---|
| 模型查询整棵标签树 | 服务端先限定 Skill/知识库范围，模型查询局部子树 |
| 查询工具与校验工具都作为最终依据 | 查询工具帮助模型选择；Java 领域校验器才是最终依据 |
| 每题可能调用多次工具 | 支持批量主题查询，并限制单次任务最大工具调用次数 |
| 标签直接放在 Prompt | Prompt 只放一级允许范围和工具规则，细粒度标签按需查询 |
| 模型可以发现新标签并直接使用 | 模型只能提交“缺失标签建议”，题目进入待审核，不能污染正式目录 |
| 标签目录变化后直接使用最新版本 | 异步任务和题目快照固定 taxonomy version |
| 复用现有 plain client | 新增只服务于出题的 Tool-Calling client |

## 3. 目标与非目标

### 3.1 目标

- 建立覆盖当前 Skill 的统一、多层、版本化知识树。
- 普通模拟面试和知识库题目生成共用同一目录。
- 每道新题必须包含一个主知识节点、可选次知识节点、难度、题型和来源。
- 模型可通过只读工具查询知识树，不再自由创造标签 code。
- 后端可确定性校验、规范化并持久化标签。
- 历史面试保存当时的标签快照，不因目录调整而改变语义。
- 为后续“面试评分 → 学习证据 → 学习进度 → 学习路线”提供稳定主键。

### 3.2 非目标

- 本阶段不定义最终学习进度算法。
- 本阶段不让模型写入标签目录。
- 本阶段不把知识树改造成通用图数据库。
- 本阶段不承诺一次性穷举所有技术知识；先覆盖当前产品已支持的面试方向，并建立可持续扩展机制。
- 本阶段不要求所有旧题目自动达到与新题相同的标注置信度。

## 4. 核心概念

### 4.1 Skill 与知识树的关系

`skillId` 是面试方向或岗位视图，不是知识树根节点。

例如 `java-backend` 可以引用以下知识子树：

- `PROGRAMMING_LANGUAGE.JAVA`
- `RUNTIME_FRAMEWORK.JVM`
- `RUNTIME_FRAMEWORK.SPRING`
- `DATABASE_STORAGE.MYSQL`
- `DATABASE_STORAGE.REDIS`
- `DISTRIBUTED_MIDDLEWARE`
- `SYSTEM_DESIGN`

公司方向 Skill，例如 `ali-backend`、`java-backend-tencent`，只定义知识树节点的覆盖范围、出题权重和必考领域，不复制知识节点。

### 4.2 主树与关联边

知识分类的主体是一棵严格多叉树，每个节点只有一个主父节点。跨领域关系通过额外关联表达：

- `RELATED_TO`：相关知识。
- `PREREQUISITE_OF`：前置知识。
- `SUPERSEDES`：新节点替代旧节点。

例如 `REDIS.DISTRIBUTED_LOCK` 的主父节点是 `DATABASE_STORAGE.REDIS`，同时可以与 `DISTRIBUTED_SYSTEM.IDEMPOTENCY`、`DISTRIBUTED_SYSTEM.CONSISTENCY` 建立关联，但不在多个父节点下复制 Redis 分布式锁。

### 4.3 可挂载节点

目录节点使用 `assignable` 标记是否允许直接挂到题目：

- `DATABASE_STORAGE`：聚合节点，不可挂载。
- `DATABASE_STORAGE.MYSQL.TRANSACTION`：可以允许宽泛事务题挂载。
- `DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC`：具体知识点，可挂载。

每道题必须有且只有一个 `PRIMARY` 节点，可以有 0～4 个 `SECONDARY` 节点。

## 5. 知识树第一版结构

第一版以当前 Skill 配置中的 42 个去重一级分类为种子，归并为以下全局根节点：

```text
TECH_KNOWLEDGE
├── PROGRAMMING_LANGUAGE
│   ├── JAVA
│   ├── PYTHON
│   └── JAVASCRIPT_TYPESCRIPT
├── DATA_STRUCTURE_ALGORITHM
│   ├── ARRAY_LINKED_LIST
│   ├── HASH_HEAP_STACK_QUEUE
│   ├── TREE_GRAPH
│   ├── GREEDY_BACKTRACKING
│   ├── DYNAMIC_PROGRAMMING
│   └── ALGORITHM_DESIGN
├── COMPUTER_FUNDAMENTALS
│   ├── OPERATING_SYSTEM
│   ├── NETWORK
│   ├── COMPUTER_ARCHITECTURE
│   └── SECURITY
├── RUNTIME_FRAMEWORK
│   ├── JVM
│   ├── SPRING
│   ├── PYTHON_WEB
│   └── FRONTEND_FRAMEWORK
├── DATABASE_STORAGE
│   ├── DATABASE_FUNDAMENTALS
│   ├── MYSQL
│   ├── REDIS
│   ├── NOSQL
│   ├── SEARCH_ENGINE
│   └── OBJECT_STORAGE
├── DISTRIBUTED_MIDDLEWARE
│   ├── DISTRIBUTED_SYSTEM
│   ├── MESSAGE_QUEUE
│   ├── RPC
│   ├── SERVICE_DISCOVERY
│   ├── CONFIG_CENTER
│   └── SCHEDULING
├── WEB_FRONTEND
│   ├── BROWSER
│   ├── HTML_CSS
│   ├── FRONTEND_ENGINEERING
│   ├── FRONTEND_PERFORMANCE
│   └── ACCESSIBILITY
├── SYSTEM_DESIGN
│   ├── REQUIREMENT_ANALYSIS
│   ├── CAPACITY_ESTIMATION
│   ├── API_DESIGN
│   ├── DATA_MODEL_DESIGN
│   ├── CACHE_DESIGN
│   ├── MESSAGE_DESIGN
│   ├── HIGH_AVAILABILITY
│   ├── OBSERVABILITY
│   ├── SECURITY_DESIGN
│   └── TRADEOFF_ANALYSIS
├── TESTING_QUALITY
│   ├── TESTING_BASICS
│   ├── TEST_CASE_DESIGN
│   ├── UNIT_INTEGRATION_TEST
│   ├── API_TEST
│   ├── AUTOMATION_TEST
│   ├── PERFORMANCE_TEST
│   ├── SECURITY_TEST
│   └── CODE_REVIEW
├── DEVOPS_OBSERVABILITY
│   ├── LINUX
│   ├── CONTAINER
│   ├── CI_CD
│   ├── DEPLOYMENT
│   ├── LOGGING
│   ├── METRICS
│   ├── TRACING
│   └── INCIDENT_RESPONSE
└── AI_AGENT_APPLICATION
    ├── LLM_CALLING
    ├── PROMPT_ENGINEERING
    ├── CONTEXT_ENGINEERING
    ├── RAG
    ├── MCP
    ├── MULTI_AGENT
    ├── MEMORY
    ├── AGENT_WORKFLOW
    ├── EVALUATION
    └── AI_SECURITY
```

`PROJECT` 不进入知识树。项目经历是题型或场景，具体项目题仍必须关联被考察的知识节点。

## 6. 知识节点编码规范

### 6.1 code

- 使用大写英文和下划线。
- 使用点号表示完整路径。
- code 一经发布保持稳定；中文名称和父节点展示调整不能随意修改 code。
- 禁止使用 Provider、公司或当前 UI 名称作为知识 code。

示例：

```text
DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC
DATABASE_STORAGE.REDIS.PERSISTENCE.AOF
RUNTIME_FRAMEWORK.JVM.GARBAGE_COLLECTION.G1
AI_AGENT_APPLICATION.RAG.RETRIEVAL.HYBRID
```

### 6.2 节点字段

```yaml
code: DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC
name: MVCC
parentCode: DATABASE_STORAGE.MYSQL.TRANSACTION
nodeKind: CONCEPT
description: MySQL 多版本并发控制、Read View、Undo Log 与快照读
aliases:
  - 多版本并发控制
  - Multi-Version Concurrency Control
  - Read View
assignable: true
status: ACTIVE
introducedVersion: 1
deprecatedVersion: null
```

### 6.3 节点状态

- `DRAFT`：仅目录维护者可见，不能用于 ACTIVE 题目。
- `ACTIVE`：可以查询和挂载。
- `DEPRECATED`：历史可解析，新题禁止使用；必须配置替代节点或迁移说明。
- `ARCHIVED`：只保留历史快照，不出现在常规查询结果中。

## 7. 题目元数据模型

知识分类、难度、题型和来源互相独立。

### 7.1 题目知识节点

```json
{
  "primaryKnowledgeCode": "DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC",
  "secondaryKnowledgeCodes": [
    "DATABASE_STORAGE.MYSQL.TRANSACTION.ISOLATION_LEVEL",
    "DATABASE_STORAGE.MYSQL.TRANSACTION.UNDO_LOG"
  ],
  "taxonomyVersion": 1
}
```

### 7.2 难度

内部统一使用 1～5：

| 等级 | 名称 | 定义 |
|---|---|---|
| 1 | 基础识别 | 记忆定义、基础 API、直接事实 |
| 2 | 基础应用 | 在简单场景中正确使用知识点 |
| 3 | 深度原理 | 解释机制、边界条件和常见问题 |
| 4 | 综合分析 | 跨知识点设计、排障或方案权衡 |
| 5 | 专家场景 | 生产级复杂约束、容量与可靠性权衡 |

现有 `junior/mid/senior` 继续作为面试人群配置，映射为题目难度区间：

- `junior`：主要使用 1～2。
- `mid`：主要使用 2～4。
- `senior`：主要使用 3～5。

题目必须保存实际 `difficultyLevel`，不能只保存人群名称。

### 7.3 题型

第一版题型枚举：

- `CONCEPT`：概念定义。
- `PRINCIPLE`：原理机制。
- `COMPARISON`：对比、选型和权衡。
- `CODING`：编程实现。
- `SQL`：SQL 编写与分析。
- `DESIGN`：系统、接口、数据或组件设计。
- `SCENARIO`：业务或技术场景分析。
- `DEBUGGING`：故障定位和修复。
- `CODE_REVIEW`：代码审查。
- `PROJECT`：项目经历，但必须关联具体知识节点。
- `OPERATION`：部署、运维和配置。
- `BEHAVIORAL`：非技术行为题；不直接更新技术知识进度。

每道题有一个主题型；如需更细分类，使用 `questionSubtype`，不允许用自由文本扩展主枚举。

### 7.4 来源

来源类型：

- `SYSTEM_SEEDED`
- `MANUAL`
- `KNOWLEDGE_BASE`
- `MOCK_INTERVIEW_GENERATED`
- `IMPORTED`
- `USER_CREATED`

来源必须能够追溯：

```json
{
  "sourceType": "KNOWLEDGE_BASE",
  "sourceRef": {
    "knowledgeBaseId": 12,
    "documentHash": "sha256...",
    "chunkIds": ["chunk-31", "chunk-32"]
  },
  "generator": {
    "providerId": "dashscope",
    "model": "qwen...",
    "promptVersion": "kb-question-v2"
  }
}
```

知识库题目的原始证据定位不能只保存在不可检索的自由文本中。最小实现应保存知识库 ID、内容哈希和引用 chunk 标识。

## 8. 数据模型

### 8.1 目录表

```sql
CREATE TABLE knowledge_taxonomy_version (
  version              INTEGER PRIMARY KEY,
  status               VARCHAR(20) NOT NULL,
  content_hash         VARCHAR(64) NOT NULL,
  published_at         TIMESTAMP(6),
  created_at           TIMESTAMP(6) NOT NULL
);

CREATE TABLE knowledge_node (
  id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code                 VARCHAR(255) NOT NULL UNIQUE,
  name                 VARCHAR(255) NOT NULL,
  parent_id            BIGINT REFERENCES knowledge_node(id),
  node_kind            VARCHAR(32) NOT NULL,
  description          TEXT,
  assignable           BOOLEAN NOT NULL DEFAULT FALSE,
  status               VARCHAR(20) NOT NULL,
  introduced_version   INTEGER NOT NULL,
  deprecated_version   INTEGER,
  sort_order           INTEGER NOT NULL DEFAULT 0,
  created_at           TIMESTAMP(6) NOT NULL,
  updated_at           TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_knowledge_node_parent_status
  ON knowledge_node(parent_id, status, sort_order);
```

### 8.2 别名与关系

```sql
CREATE TABLE knowledge_node_alias (
  id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  node_id              BIGINT NOT NULL REFERENCES knowledge_node(id),
  alias                VARCHAR(255) NOT NULL,
  normalized_alias     VARCHAR(255) NOT NULL,
  UNIQUE(node_id, normalized_alias)
);

CREATE TABLE knowledge_node_relation (
  source_node_id       BIGINT NOT NULL REFERENCES knowledge_node(id),
  target_node_id       BIGINT NOT NULL REFERENCES knowledge_node(id),
  relation_type        VARCHAR(32) NOT NULL,
  PRIMARY KEY(source_node_id, target_node_id, relation_type)
);
```

### 8.3 Skill 视图

```sql
CREATE TABLE skill_knowledge_scope (
  skill_id             VARCHAR(64) NOT NULL,
  node_id              BIGINT NOT NULL REFERENCES knowledge_node(id),
  include_descendants  BOOLEAN NOT NULL DEFAULT TRUE,
  priority             VARCHAR(20) NOT NULL,
  weight               NUMERIC(6,3) NOT NULL DEFAULT 1,
  PRIMARY KEY(skill_id, node_id)
);
```

当前 `skill.meta.yml` 中的分类用于初始化这张映射，而不是继续作为最终知识节点目录。

### 8.4 知识库题目标签

```sql
CREATE TABLE knowledge_base_question_node (
  question_id          BIGINT NOT NULL REFERENCES knowledge_base_questions(id),
  node_id              BIGINT NOT NULL REFERENCES knowledge_node(id),
  relation_type        VARCHAR(20) NOT NULL,
  relevance_weight     NUMERIC(5,4) NOT NULL DEFAULT 1,
  tagging_source       VARCHAR(20) NOT NULL,
  confidence           NUMERIC(5,4),
  taxonomy_version     INTEGER NOT NULL,
  PRIMARY KEY(question_id, node_id)
);
```

约束由领域服务保证：每道 ACTIVE 题恰好有一个 PRIMARY 节点。

### 8.5 普通面试题目快照

普通面试当前把题目列表保存在 `interview_sessions.questions_json`。第一阶段不强制拆出独立题目表，但 `InterviewQuestionDTO` 必须增加：

```text
primaryKnowledgeCode
secondaryKnowledgeCodes
difficultyLevel
questionType
sourceType
sourceRef
taxonomyVersion
taggingStatus
```

这些字段随会话问题列表一起保存，形成不可变历史快照。

## 9. 标签目录的配置与发布

### 9.1 权威来源

第一阶段以仓库内版本化配置为权威来源：

```text
app/src/main/resources/question-tags/tag-catalog.yml
app/src/main/resources/question-tags/skill-scopes.yml
```

应用启动时通过同步服务写入数据库，前端和工具只读数据库中的已发布版本。

使用仓库配置作为权威来源的原因：

- 目录修改经过代码评审。
- 可以审计差异和回滚。
- 不允许生产后台误删根节点。
- 可以在测试中校验 code 唯一、父节点存在、树无环和别名冲突。

后续如果增加后台管理，后台只创建变更草稿，最终仍需发布为新的 taxonomy version。

### 9.2 发布规则

- 修改名称或描述不改变 code。
- 节点移动需要评估历史语义；不能仅修改 `parent_id` 后无版本记录。
- 节点拆分时旧节点先进入 DEPRECATED，并配置替代关系。
- 已被历史题目引用的节点禁止物理删除。
- 每次发布计算整个目录的 `content_hash`。

## 10. Tool Calling 设计

### 10.1 工具职责

工具只负责：

- 查看允许的根节点或子节点。
- 按关键词、别名和描述搜索候选知识点。
- 读取单个节点的完整路径、定义和关系。
- 帮助模型检查一次候选选择。

工具不负责：

- 创建、修改、删除标签。
- 保存题目。
- 更新题目状态。
- 修改知识库。
- 更新学习进度。
- 决定最终是否接受题目。

### 10.2 可信上下文

每次出题建立服务端可信的 `QuestionGenerationScope`：

```java
public record QuestionGenerationScope(
    String skillId,
    String sourceType,
    Integer taxonomyVersion,
    Set<String> allowedRootCodes,
    int maxToolCalls
) {}
```

`skillId`、`taxonomyVersion`、`allowedRootCodes` 由应用创建，不接受模型覆盖。工具参数中的查询条件只能在这个范围内进一步缩小，不能扩大权限。

### 10.3 查询工具

工具名：

```text
query_question_knowledge_tags
```

输入：

```json
{
  "parentCode": "DATABASE_STORAGE.MYSQL.TRANSACTION",
  "queries": [
    "MVCC 隔离级别",
    "Undo Log"
  ],
  "assignableOnly": true,
  "limitPerQuery": 10
}
```

约束：

- `queries` 最多 10 项。
- `limitPerQuery` 最大 20。
- 无 `query` 且有 `parentCode` 时返回直接子节点。
- 无 `query` 且无 `parentCode` 时只返回当前 scope 的根节点。
- 返回结果不包含 DRAFT、ARCHIVED 或当前版本不可用节点。
- 查询必须限制在 `allowedRootCodes` 后代范围内。

输出：

```json
{
  "taxonomyVersion": 1,
  "results": [
    {
      "query": "MVCC 隔离级别",
      "nodes": [
        {
          "code": "DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC",
          "name": "MVCC",
          "path": "数据库与存储 / MySQL / 事务 / MVCC",
          "description": "多版本并发控制、Read View、Undo Log 与快照读",
          "assignable": true,
          "matchedBy": "ALIAS"
        }
      ]
    }
  ]
}
```

### 10.4 模型辅助校验工具

工具名：

```text
validate_question_knowledge_tags
```

输入：

```json
{
  "selections": [
    {
      "clientQuestionId": "q1",
      "primaryCode": "DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC",
      "secondaryCodes": [
        "DATABASE_STORAGE.MYSQL.TRANSACTION.ISOLATION_LEVEL"
      ]
    }
  ]
}
```

输出：

```json
{
  "valid": true,
  "taxonomyVersion": 1,
  "items": [
    {
      "clientQuestionId": "q1",
      "valid": true,
      "errors": [],
      "warnings": []
    }
  ]
}
```

该工具只帮助模型在同一轮中自我修正。模型返回最终 JSON 后，应用仍必须调用同一个底层 `QuestionKnowledgeValidator` 做最终校验。

### 10.5 缺失标签建议

如果模型无法找到合适节点，可以在最终输出中返回：

```json
{
  "taggingStatus": "REVIEW_REQUIRED",
  "missingTagSuggestion": {
    "name": "建议的新知识点名称",
    "suggestedParentCode": "...",
    "reason": "当前目录中没有精确节点"
  }
}
```

这不会修改目录，也不能生成 ACTIVE 题目。建议进入人工审核队列。

## 11. 专用出题客户端

### 11.1 当前约束

当前普通面试和知识库出题使用 `LlmProviderRegistry.getPlainChatClient()`，该客户端不挂工具，`StructuredOutputInvoker` 假设模型直接返回 JSON。项目已有 `ToolCallingManager`、`ToolCallingAdvisor` 和 `ToolCallback` 基础，可复用，但不能改变所有 plain client 的语义。

### 11.2 目标客户端

新增概念接口：

```java
ChatClient getQuestionGenerationChatClient(
    String providerId,
    QuestionGenerationScope scope
)
```

客户端只挂载：

- `query_question_knowledge_tags`
- `validate_question_knowledge_tags`
- `ToolCallingAdvisor`
- 现有安全 Advisor

不挂载通用 SkillsTool、会话记忆和具有写副作用的工具。

由于 scope 是每次任务不同的可信上下文，不能简单按 `providerId` 缓存一个包含可变 scope 的全局客户端。可选实现：

1. 缓存底层 `ChatModel`，每次任务构建轻量 `ChatClient` 与 scope-bound callbacks。
2. 使用 Spring AI 支持的 tool context，在每次调用中传入不可被模型修改的 scope。

实现时优先采用项目当前 Spring AI 版本已验证的 API；不得将 scope 存在单例可变字段或 ThreadLocal 中跨虚拟线程传播。

### 11.3 结构化输出兼容

出题调用保留最终 `BeanOutputConverter` JSON 契约。执行序列为：

```text
Prompt
  → 模型发起 Tool Call
  → ToolCallingAdvisor 执行只读工具并返回结果
  → 模型输出最终 JSON
  → BeanOutputConverter 解析
  → QuestionKnowledgeValidator 最终校验
```

必须增加集成测试验证“工具调用消息完成后，最终响应仍能被 entity/schema validation 解析”。如果当前 Provider 或 Advisor 组合无法稳定支持，使用“两阶段调用”降级：

1. 第一阶段 Tool-Calling client 只生成题目规划和候选标签。
2. 第二阶段 plain client 根据已校验候选标签输出最终结构化题目 JSON。

两阶段方案调用成本更高，但边界更清晰，作为兼容兜底保留。

## 12. 普通模拟面试出题流程

```text
CreateInterviewRequest
  → 解析 skillId、难度和题目数量
  → 加载 Skill 对应的知识树 scope 与分类权重
  → 固定 taxonomyVersion
  → 构建 QuestionGenerationScope
  → 模型按需调用标签查询工具
  → 模型返回带标准标签的题目 JSON
  → 后端校验知识节点、难度、题型与数量分配
  → 写入 questionsJson 历史快照
```

普通题最终结构：

```json
{
  "questionIndex": 0,
  "question": "MySQL RR 隔离级别下 MVCC 如何工作？",
  "type": "MYSQL",
  "category": "MySQL",
  "topicSummary": "RR 下的 MVCC",
  "primaryKnowledgeCode": "DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC",
  "secondaryKnowledgeCodes": [
    "DATABASE_STORAGE.MYSQL.TRANSACTION.ISOLATION_LEVEL"
  ],
  "difficultyLevel": 3,
  "questionType": "PRINCIPLE",
  "sourceType": "MOCK_INTERVIEW_GENERATED",
  "taxonomyVersion": 1,
  "taggingStatus": "VERIFIED"
}
```

`type/category` 在兼容期保留，但不再作为学习进度主键。

## 13. 知识库题目生成流程

```text
提交题目生成任务
  → 固定 config + taxonomyVersion + allowedRootCodes
  → Outbox/Kafka 可靠投递
  → Worker 检索知识库上下文
  → 模型从上下文提取主题并调用标签查询工具
  → 模型生成带标签、难度、题型和来源证据的题目
  → 后端校验标签合法性与知识库证据
  → 在短事务中替换题目并保存标签关系
  → 完成任务
```

任务输入快照必须新增：

```text
taxonomyVersion
allowedRootCodes
taggingPolicyVersion
```

知识库当前内容仍可能在任务执行前变化，因此题目来源还必须记录实际执行时使用的 `kbContentHash` 和 chunk 引用。标签目录版本与知识库内容版本是两个独立版本，不能混用。

## 14. 后端最终校验

`QuestionKnowledgeValidator` 是最终可信边界，至少校验：

1. taxonomy version 存在且为任务固定版本。
2. PRIMARY code 存在、可挂载、状态允许。
3. PRIMARY 位于当前 Skill/任务 scope 中。
4. SECONDARY 不超过 4 个、不重复、不能等于 PRIMARY。
5. 所有 SECONDARY 都存在且状态允许。
6. 难度在 1～5。
7. questionType 是已发布枚举。
8. 行为题不得伪装成技术知识进度题。
9. 知识库题必须包含有效来源引用和内容哈希。
10. taxonomyVersion、标签结果和题目快照一起持久化。

校验失败策略：

- 第一次失败：把具体错误加入修复 Prompt，允许一次结构化修复。
- 修复后仍失败：整批任务失败，或将单题标记 `REVIEW_REQUIRED`；第一版建议整批失败，避免题库部分更新。
- 不允许自动把未知标签变成新节点。
- 不允许把非法题目静默降级为 `GENERAL` 后进入 ACTIVE 题库。

## 15. Tool Call 成本与调用上限

标签工具设计不应导致“一道题一次甚至多次全树查询”。建议：

- 服务端先根据 Skill 分配确定一级领域。
- 查询工具支持一次提交多个主题关键词。
- 每个生成批次最多 8 次标签工具调用，配置化且记录指标。
- 每次最多 10 个 query，每个 query 最多返回 20 个节点。
- 优先命中 code、标准名称和别名；描述全文检索作为次级匹配。
- 工具返回内容设置字符上限，不返回整段学习资料。

需要记录：

- 每次生成的 tool call 数量。
- 标签查询延迟。
- 无结果率。
- 非法标签率。
- 修复重试率。
- REVIEW_REQUIRED 题目比例。

## 16. Provider 兼容与降级

不同 OpenAI-compatible Provider 对 Tool Calling 的兼容程度可能不同。启动或 Provider 测试阶段需要验证：

- 是否接受 tool schema。
- 是否能正确返回 tool call arguments。
- ToolCallingAdvisor 是否能完成多轮调用。
- 工具调用后是否能返回合法结构化 JSON。
- 是否支持项目需要的最大工具调用和响应大小。

降级顺序：

1. 正常 Tool Calling + 最终结构化输出。
2. 两阶段：工具规划后使用 plain client 输出最终 JSON。
3. 后端基于 Skill scope 和别名检索生成候选标签，注入小型候选集后使用 plain client。
4. 无法生成可验证标签时任务失败或进入待审核，不保存为 ACTIVE。

降级不能放宽目录合法性，只能改变标签候选如何提供给模型。

## 17. 安全边界

知识库内容属于不可信输入。即使文档中包含“调用某工具”“创建标签”等指令，也不能改变工具权限。

- 工具只读且只返回目录数据。
- scope 由服务端构造。
- 工具不接收 SQL、类名或任意资源路径。
- 查询字符串限制长度并规范化。
- 工具结果不包含内部数据库 ID 之外的敏感信息。
- 最终持久化只接受后端验证后的 code。
- Prompt 保留现有 anti-injection 指令，并明确知识库内容只是参考资料。

## 18. 目录覆盖与质量治理

“非常完善”不等于一次性创建大量未经验证的标签。第一版建议先建立 150～300 个高质量可挂载节点，覆盖当前产品真实支持的方向。

每个节点发布前应满足：

- 有唯一稳定 code。
- 有明确父节点。
- 有可判定的定义和边界。
- 至少有一个适用 Skill，或明确为全局基础节点。
- 与兄弟节点不重复。
- 常见中文、英文和缩写作为 aliases。
- 如有前置知识，建立关系。
- 至少有一条正向题目示例；易混淆节点增加反例说明。

目录质量检查：

- 无环。
- 无孤儿节点。
- code、别名无冲突。
- assignable 节点不能没有定义。
- Skill scope 引用节点必须存在。
- DEPRECATED 节点必须有迁移说明。
- 目录版本 hash 可复现。

## 19. 历史数据兼容

旧题没有标准标签，只能通过 `skillId/type/category/topicSummary` 回填。

回填流程：

```text
读取历史 questionsJson / knowledge_base_questions
  → 使用 type 映射到允许的一级子树
  → 使用 category、topicSummary 和题干搜索候选节点
  → 高置信度规则匹配自动写入
  → 中低置信度进入人工审核
  → 保存 BACKFILLED taggingSource 与 confidence
```

规则：

- 回填不覆盖历史题干和评分。
- 低置信度结果不参与精细学习进度计算。
- 历史自由文本 `category` 保留用于展示和审计。
- 新题从功能上线起强制标准标签，不以历史回填完成作为上线前置条件。

## 20. API 草案

标签目录查询接口供前端管理和人工标注使用：

```text
GET /api/question-tags/versions/current
GET /api/question-tags/roots?skillId=java-backend
GET /api/question-tags/{code}/children
GET /api/question-tags/search?q=mvcc&skillId=java-backend
GET /api/question-tags/{code}
```

题目返回增加：

```json
{
  "primaryKnowledge": {
    "code": "DATABASE_STORAGE.MYSQL.TRANSACTION.MVCC",
    "name": "MVCC",
    "path": "数据库与存储 / MySQL / 事务 / MVCC"
  },
  "secondaryKnowledge": [],
  "difficultyLevel": 3,
  "questionType": "PRINCIPLE",
  "sourceType": "KNOWLEDGE_BASE",
  "taxonomyVersion": 1,
  "taggingStatus": "VERIFIED"
}
```

创建或修改知识库题目时，ACTIVE 状态必须通过同一领域校验器。

## 21. 前端影响

### 21.1 题目编辑

- 使用树形选择器选择一个主知识点。
- 次知识点使用可搜索多选，最多 4 个。
- 展示完整路径，避免同名节点混淆。
- 难度使用 1～5 级选择器。
- 题型使用固定枚举。
- 来源只读展示；手工创建时自动为 MANUAL。
- REVIEW_REQUIRED 题目不能直接启用，必须完成标注。

### 21.2 题目列表

支持按以下条件过滤：

- 知识节点及其全部后代。
- 难度区间。
- 题型。
- 来源。
- 标注状态。
- taxonomy version。

## 22. 测试策略

### 22.1 目录测试

- code 唯一。
- 父节点存在。
- 树无环。
- 别名规范化后无非法冲突。
- Skill scope 引用有效。
- 版本 hash 稳定。

### 22.2 工具测试

- 只能返回 scope 内节点。
- DRAFT/ARCHIVED 节点不可见。
- parent 查询和关键词查询正确。
- 批量限制、长度限制和最大返回量生效。
- 模型不能通过参数切换 skillId 或 taxonomyVersion。
- 工具无写副作用。

### 22.3 出题测试

- 模型调用工具后最终 JSON 可解析。
- 每题恰好一个 PRIMARY。
- 非法 code 会触发修复或整批失败。
- Tool Calling 不支持时按降级顺序执行。
- Kafka 重试使用相同 taxonomyVersion。
- 同一任务重试不会保存不同版本标签。
- 知识库题保存来源内容哈希和 chunk 引用。

### 22.4 兼容测试

- 旧 questionsJson 缺少新字段仍能读取。
- 旧题展示不受影响。
- 新题 type/category 兼容旧前端期间仍有值。
- 历史题回填不会改变原评估结果。

## 23. 分阶段实施

### Phase 1：目录基础

- 建立 `tag-catalog.yml` 和 `skill-scopes.yml`。
- 完成 Java、JVM、Spring、MySQL、Redis、分布式、算法主干。
- 建立目录校验测试和同步服务。
- 提供只读标签查询 API。

### Phase 2：题目元数据与人工标注

- 增加数据库表和 Flyway 迁移。
- 扩展知识库题目 DTO、实体与编辑 UI。
- 扩展普通面试问题快照。
- 增加难度、题型、来源和标签领域校验。

### Phase 3：出题 Tool Calling

- 实现 scope-bound 标签查询工具。
- 新增专用出题 ChatClient。
- 修改普通模拟面试出题。
- 修改知识库异步题目生成。
- 增加 Provider 兼容探测和降级。

### Phase 4：历史回填与治理

- 回填旧知识库题和历史会话问题。
- 建立 REVIEW_REQUIRED 列表。
- 增加目录覆盖率、无结果率和非法标签率监控。

历史回填通过应用启动事件提供显式运维入口，默认不启用；启用后仍默认 dry-run。执行采用固定目录版本、按主键游标分页和逐批事务，面试快照使用原 JSON 比对更新，避免覆盖并发写入的答案或评分。可用配置如下：

```properties
app.question-tags.backfill.enabled=true
app.question-tags.backfill.dry-run=true
app.question-tags.backfill.batch-size=100
app.question-tags.backfill.max-records=0
app.question-tags.backfill.after-knowledge-base-id=0
app.question-tags.backfill.after-session-id=0
```

`max-records=0` 表示处理全部候选记录；先用 dry-run 查看汇总和游标，再将 dry-run 显式设为 false 执行写入。若设置单次上限，日志游标可配置为下一次运行的起始 ID。每批独立提交，失败批回滚且已提交批保留。回填仅写知识标签元数据，不更新历史题干、作答、评分及会话更新时间；匹配分数是规则排序置信度，不是经过校准的统计概率。

### Phase 5：学习闭环

- 将每题评分投影为标签学习证据。
- 按主、次标签权重聚合学习进度。
- 向父节点汇总。
- 根据薄弱节点、前置关系、置信度和复习时间生成学习路线。

## 24. 验收标准

第一至第三阶段完成后必须满足：

1. 普通模拟面试和知识库生成题共用同一知识树版本。
2. 所有新技术题都有一个合法 PRIMARY 知识节点。
3. 所有新题都有 1～5 难度、固定题型和可追溯来源。
4. 模型不能创建或保存标签，只能查询和选择。
5. Tool Calling 失败不会产生无标签 ACTIVE 题目。
6. 后端能拒绝不存在、越权、废弃或不可挂载的节点。
7. Kafka 重试和任务恢复不会跨 taxonomy version。
8. 历史会话保留创建时的标签快照。
9. 前端可以按知识树、难度、题型和来源筛选题目。
10. 目录变更可以审计，已引用节点不能物理删除。

## 25. 待实现时确认的技术点

以下事项不影响总体设计，但进入编码前需要用当前依赖版本做小型验证：

1. Spring AI 当前版本中 per-call tool context 的最佳接入方式。
2. `ToolCallingAdvisor` 与 `BeanOutputConverter.entity(...validateSchema())` 在当前 OpenAI-compatible Provider 上的组合行为。
3. 是否采用 PostgreSQL 普通 `parent_id` + 递归 CTE，还是增加物化 path；第一版建议普通邻接表并缓存常用 Skill 子树。
4. 知识库向量 chunk 的稳定标识目前是否足以作为题目来源定位；不足时需先补充 source chunk key。
5. 目录发布是否在启动期自动同步，还是由显式管理命令执行；生产环境建议显式发布，开发环境可自动同步。

## 26. 最终决策摘要

- 采用全局、多层、版本化知识多叉树。
- Skill 是知识树视图，不复制目录。
- 难度、题型、来源与知识节点正交。
- 每道题一个主知识节点，最多四个次知识节点。
- 出题模型通过只读 Tool Calling 查询局部标签树。
- Java 领域校验器是最终可信边界。
- 出题使用专用 Tool-Calling client，不改变全局 plain client。
- 异步任务固定 taxonomy version。
- Tool Calling 不可用时允许受控候选注入降级，但不放宽标签合法性。
- 标签目录稳定后，再实现面试结果到学习进度和个性化路线的闭环。
