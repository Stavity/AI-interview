# TRD 实库核验更新

> 目标数据库：`interview_guide_v2`  
> 核验日期：2026-08-18  
> 核验方式：Docker `my_pgvector` 容器内只读 `psql` 查询  
> 本文是学习、普通 Chat、多轮面试升级 TRD 的实库补充与修订依据。

## 1. 核验结论

需要更新 TRD 的数据库前提描述，但不需要改变总体升级架构。

数据库并非空库：目前已有 1 条 `interview_sessions` 会话数据和 5 条 `llm_provider_config` Provider 配置。因此新功能必须按增量 Flyway 迁移实施，不能用重建库、清空表或重新初始化 Provider 的方式部署。

当前 Flyway 已完整执行到 `V20260803`，5 条迁移均成功。学习、普通 Chat、多轮面试及 Agent 审计表都尚不存在，可以从该版本之后安全新增迁移。

## 2. 运行环境实测

| 组件 | 容器 | 状态 | 端口 | 对升级的结论 |
| --- | --- | --- | --- | --- |
| PostgreSQL + pgvector | `my_pgvector` | Up | 5432 | 新表与迁移的目标数据库 |
| Redis Stack | `redis-stack` | Up | 6379、8001 | 可用于限流、幂等和临时流状态 |
| RustFS | `rustfs_local` | Up | 9000、9001 | 本期不新增依赖；代码执行也不得借用该容器 |

PostgreSQL 已安装：`hstore 1.8`、`uuid-ossp 1.1`、`vector 0.8.2`。`vector_store.embedding` 为 `vector` 类型，已有 `spring_ai_vector_index` HNSW cosine 索引。学习、普通 Chat、多轮面试不需要修改该向量表或索引。

## 3. Flyway 实际历史

| 版本 | 描述 | 成功 | 结论 |
| --- | --- | --- | --- |
| 1 | `init schema` | 是 | 建立当前基础表 |
| 20260722 | `add interview category to interview sessions` | 是 | 已具备知识库面试方向字段 |
| 20260723 | `ensure pgvector store` | 是 | pgvector 与向量表已被保证 |
| 20260724 | `add question gen status` | 是 | 知识库异步题目生成状态已具备 |
| 20260803 | `add interview request id` | 是 | 普通面试创建幂等键已具备 |

修订要求：后续迁移版本必须大于 `20260803`，不得编辑上述已执行文件。建议按领域拆分为：

```text
VyyyyMMdd__add_learning_schema.sql
VyyyyMMdd__add_chat_schema.sql
VyyyyMMdd__add_multi_round_schema.sql
VyyyyMMdd__add_interview_agent_audit.sql
VyyyyMMdd__add_upgrade_indexes.sql
```

## 4. 实际表与数据状态

public schema 中有 17 张表（包含 `flyway_schema_history`），分别覆盖简历、单轮面试、知识库、RAG Chat、LLM Provider、语音面试、面试日程和向量库。

| 表 | 当前记录数 | 与升级的关系 |
| --- | ---: | --- |
| `interview_sessions` | 1 | 需要保留；多轮面试扩展表通过其内部 `id` 关联 |
| `llm_provider_config` | 5 | 需要保留；Chat/Agent 只保存 `provider_id` 和非敏感配置指纹 |
| `rag_chat_sessions` | 0 | 可作为 SSE 与消息状态实现参考，不能作为普通 Chat 的数据表 |
| `knowledge_bases` | 0 | Agent 可选的知识库输入当前无数据，仍需保留为空时的降级逻辑 |
| `vector_store` | 0 | 不属于普通 Chat；不得为了新功能填充或改写 |
| 其余业务表 | 未纳入本次行数统计 | 不受新增表影响，保持现有 Schema |

因此，原 TRD 中“空库执行迁移”仍是测试要求，但生产/当前开发库发布要求应修改为“已有当前版本库上的增量迁移”。

## 5. 关键 Schema 实测与设计修订

### 5.1 单轮面试表

`interview_sessions` 实际包含 `request_id`，并拥有唯一索引 `uk_interview_sessions_request_id`；`session_id` 也有唯一索引。状态约束为：`CREATED`、`IN_PROGRESS`、`COMPLETED`、`EVALUATED`。

`interview_answers` 实际具有：

```text
UNIQUE(session_id, question_index)
INDEX(session_id, question_index)
```

TRD 保持以下设计：

- `multi_round_interviews.interview_session_id` 必须外键关联 `interview_sessions.id`，并设置唯一约束，确保一条单轮基座会话最多扩展为一场多轮面试。
- 多轮题目不得写入 `interview_sessions.questions_json` 或 `interview_answers`，应建立 `multi_round_questions`，以免轮次和题目序号与现有单轮语义冲突。
- 所有多轮写操作复用“请求键 + 数据库唯一约束 + Redis 短期缓存”的幂等模式；不能依赖单轮已有 `request_id` 覆盖所有新操作。

### 5.2 RAG Chat 表

`rag_chat_messages` 有 `(session_id, message_order)` 普通索引，但没有唯一约束；`completed` 允许为空。当前服务以助手占位消息和流式结束回写实现对话持久化。

TRD 更新为：普通 Chat **不**复用 RAG 表，但新 `chat_messages` 必须比旧表更严格：

```text
UNIQUE(session_id, message_order)
NOT NULL completed DEFAULT true
CHECK(role IN ('USER', 'ASSISTANT'))
INDEX(session_id, completed, message_order)
```

这样可避免同一会话的并发 SSE 请求生成重复消息顺序，也能稳定查询未完成消息。RAG 表不在本次迁移中补唯一约束，避免影响既有数据兼容性；若未来要补，应先执行重复序号审计。

### 5.3 Provider 表

`llm_provider_config` 的 API Key 以 `api_key_ciphertext` 和 `api_key_nonce` 保存，`enabled`、`builtin`、模型、嵌入模型、温度均为配置字段。实库已有 5 条配置。

修订后的 Chat / 多轮规则：

1. 创建 `chat_sessions`、`learning_plans`、`multi_round_interviews` 时保存 `provider_id`。
2. 保存由 `base_url`、`model`、`temperature` 规范化后计算的 SHA-256 配置指纹，只用于审计和配置漂移提示。
3. 绝不保存明文 Key、密文、nonce，也不复制完整 Provider 配置。
4. 会话执行时通过 `LlmProviderRegistry.getChatClientOrDefault(providerId)` 获取模型；Provider 被禁用或删除时返回业务错误，不自动切换默认 Provider。
5. 更改全局默认 Provider 只影响后续新建会话，不影响已有 Chat 或多轮会话。

### 5.4 知识库和向量表

`knowledge_bases` 已包含 `question_gen_status`、`question_gen_error`、`question_gen_task_id`、配置快照和计数字段，且题目生成状态具有 CHECK 约束。当前行数为 0。

Agent 的知识库工具必须是可选的只读工具：当用户未选择知识库、表内没有可用知识库，或知识库未完成向量化时，Agent 仍能依据 Skill、JD 和简历进行确定性降级，而不是将任务标记失败。

## 6. 新增表的最终要求

| 领域 | 新表 | 必要约束 |
| --- | --- | --- |
| 学习 | `learning_skill_sources` | `UNIQUE(skill_id, category_key)`；`ACTIVE/UNAVAILABLE` 状态 |
| 学习 | `learning_progress` | `UNIQUE(skill_source_id)`；掌握度范围 0–100；版本字段用于乐观锁 |
| 学习 | `learning_plans`、`learning_plan_tasks`、`learning_reminders` | 日期/状态索引；提醒仅支持 `IN_APP`、`BROWSER` |
| 普通 Chat | `chat_sessions`、`chat_messages` | Provider 固化；消息序号唯一；流式完成状态不可为空 |
| 多轮面试 | `multi_round_interviews`、`multi_rounds`、`multi_round_questions` | 轮次唯一、题目序号唯一、明确状态 CHECK |
| 算法 | `algorithm_submissions` | `JAVA/PYTHON/CPP` CHECK；保留模板版本与源码快照 |
| Agent | `interview_agent_runs`、`interview_agent_steps` | 按运行和步骤可审计；仅保存脱敏摘要/哈希 |

所有新增时间列采用与实库一致的 `TIMESTAMP(6)`。提醒 API 使用带 offset 的 ISO-8601 时间；服务端将其转换成统一时区后再落库和比较。现有系统没有用户/租户表，新表不新增伪 `user_id`。

## 7. 对实施顺序的影响

实库检查后，实施顺序不变，但每个阶段必须增加数据保护验证：

1. 备份 `interview_guide_v2`，并导出 `flyway_schema_history`、`interview_sessions`、`llm_provider_config` 的行数与校验信息。
2. 在当前已有版本的副本上执行新增 Flyway 迁移，验证原 1 条面试会话和 5 条 Provider 仍可读取。
3. 先实现学习与普通 Chat；Chat Provider 固定要使用真实的现有 Provider 记录做测试。
4. 再实现确定性多轮状态机和三语言静态评审；确认不改变已有单轮面试记录。
5. 最后接入受控 Agent；模型异常时必须可退回确定性流程。

## 8. 不需要修改的内容

本次实库核验未发现以下事项需要调整：

- 不需要重建 pgvector、`vector_store` 或 HNSW 索引。
- 不需要改动 RustFS 配置或创建新的对象存储桶。
- 不需要修改已有 `interview_sessions` 的状态 CHECK；多轮使用独立状态机。
- 不需要把学习任务写入 `interview_schedule`。
- 不需要迁移旧 `Work-inflow` 项目数据。

## 9. 发布前最终门禁

新增迁移执行后必须复查：Flyway 新记录全部成功；原 `interview_sessions=1` 与 `llm_provider_config=5` 仍可读取；所有新增表、外键、CHECK 和索引存在；Chat 消息顺序唯一；Provider 密钥未被写入新表；`vector_store` 结构和 HNSW 索引未变化。

只有在空库迁移和已有版本库迁移均成功、后端测试与前端构建通过、Provider 固定与 Agent 降级路径通过后，才允许开放新增功能入口。
