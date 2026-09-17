# PRD：自适应学习、普通 Chat 与多轮面试能力补齐

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 目标项目 | `D:\WorkPlace\Work\interview-guide\interview-guide` |
| 参考实现 | `D:\WorkPlace\Work\Work-inflow\interview-guide` |
| 文档版本 | v1.0 |
| 日期 | 2026-08-17 |
| 范围 | 自适应学习、普通 Chat、多轮面试 |

## 2. 背景与问题定义

当前项目已具备简历分析、单轮模拟面试、知识库问答、知识库面试、语音面试、面试安排及多 Provider 管理能力，但缺少以下闭环能力：

1. 面试结果不能转化为持续学习任务，用户无法按薄弱项进行定向练习。
2. 当前仅提供绑定知识库的 RAG 会话，缺少不依赖知识库的通用 AI 对话入口。
3. 当前模拟面试是单会话问答，不支持“算法笔试 → 技术一面 → 技术二面 → HR 面”的分轮状态流转、恢复和汇总评估。

旧项目已实现上述三类能力，但其包结构、单 Provider 调用方式、Hibernate 自动建表模式与当前项目不兼容。本需求以旧项目的业务行为为参考，在当前项目中重新实现，不直接迁移旧实体、SQL 或 Service。

## 3. 现状与功能缺口

| 能力 | 旧项目实现 | 当前项目现状 | 本期结论 |
| --- | --- | --- | --- |
| 自适应学习 | 知识树、节点讲解、节点问答、代码练习、进度、学习路径 | 无 `learning` 模块、无学习表、无学习页 | 新增 |
| 普通 Chat | 会话创建、列表、置顶、改标题、删除、SSE 流式消息 | 有 RAG Chat，但会话必须关联知识库 | 新增，复用 RAG 会话基础模式 |
| 多轮面试 | 4 个固定轮次、算法代码提交、轮次状态、恢复、分轮报告、汇总报告 | 仅单轮 `interview_sessions` / `interview_answers` | 新增，扩展既有面试会话 |

## 4. 产品目标与边界

### 4.1 产品目标

1. 将面试评估结果映射为覆盖全部预设 Skill 的知识点掌握度、学习计划、每日任务与提醒。
2. 提供独立于知识库的通用 AI 对话，保留完整会话记录。
3. 支持可中断、可恢复、可评估的多轮面试流程。
4. 所有新增 AI 调用接入当前 `LlmProviderRegistry` 和 `StructuredOutputInvoker`，支持当前 Provider 配置体系。
5. 所有新增表通过 Flyway 管理；不依赖 Hibernate 自动建表。
6. 多轮面试由受控 Agent 自主编排问题、追问、评估和学习建议，但不得越过后端状态机、数据范围和调用预算。

### 4.2 非目标

1. 本期不新增用户注册、登录和多租户隔离。按当前本地单用户部署模型处理数据，数据模型预留未来 `user_id` 扩展位置。
2. 本期算法题不执行真实代码编译、沙箱运行或隐藏用例判题；代码评估采用 LLM 静态评估，结果必须标注为“AI 评估”。
3. 本期不迁移旧 `interview_guide` 数据库中的学习、普通 Chat 或多轮面试记录。
4. 本期不替换已有 RAG Chat、知识库面试、语音面试和单轮面试行为。

## 5. 通用设计约束

1. Controller 只承担路由、参数校验和委托；业务编排位于 Service。
2. 响应统一使用 `Result<T>`；请求使用不可变 `record`。
3. LLM、S3 和外部 HTTP 调用不得位于数据库事务内。
4. AI Provider 从请求指定 Provider 或会话快照读取；未指定时使用默认 Provider。
5. 流式接口使用 SSE。用户消息和助手占位消息必须先持久化；流式完成或异常时更新助手消息内容与完成状态。
6. 写操作、AI 调用、代码评估需要使用现有 `@RateLimit`。
7. 前端 API 集中在 `frontend/src/api/`，共享类型位于 `frontend/src/types/`。

## 6. 功能一：自适应学习

### 6.1 用户流程

1. 用户打开“学习中心”，查看知识树和当前总体进度。
2. 用户选择知识节点，查看知识说明、关键点、前置知识点、关联节点和练习列表。
3. 用户请求 AI 讲解或围绕当前节点提问。
4. 用户提交代码练习，系统返回 AI 评估、问题清单和改进版本。
5. 用户标记学习状态或提交练习后，系统更新掌握度、学习次数和最近学习时间。
6. 用户查看学习路径；系统基于学习进度和已有面试报告中的低分分类推荐优先节点。

### 6.2 功能需求

| 编号 | 功能 | 要求 |
| --- | --- | --- |
| L-01 | 知识树 | 返回树形节点，支持分类、难度、排序、节点状态和掌握度。 |
| L-02 | 节点详情 | 返回正文、关键点、代码示例、前置节点、关联节点、当前进度。 |
| L-03 | AI 讲解 | 支持针对节点生成解释；可携带用户问题作为讲解侧重点。 |
| L-04 | 节点问答 | 问题必须绑定节点；回答返回关联知识点，避免退化为普通 Chat。 |
| L-05 | 代码练习 | 按节点查询练习；提交代码后返回分数、反馈、问题项、改进代码。 |
| L-06 | 学习进度 | 状态为 `NOT_STARTED`、`LEARNING`、`MASTERED`；记录掌握度 0–100、学习次数、备注和更新时间。 |
| L-07 | 学习路径 | 按“未开始、低掌握度、面试低分分类、前置依赖”生成有序步骤，返回推荐理由和优先级。 |
| L-08 | 面试联动 | 面试报告生成后，允许用户一键将低分分类映射为推荐学习节点；未配置映射时不自动写入进度。 |
| L-09 | 全 Skill 覆盖 | 从 `classpath:skills/*/SKILL.md` 与 `skill.meta.yml` 同步学习目录，覆盖当前全部预设 Skill 及其分类。 |
| L-10 | 学习计划 | 用户确认推荐路径后生成学习计划，按目标日期拆分为每日任务；任务可调整日期、完成、跳过或重新排序。 |
| L-11 | 提醒 | 对当日、逾期和即将到期任务提供应用内提醒；浏览器通知仅在用户授权且页面打开时触发。 |

### 6.3 数据模型

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `learning_nodes` | `node_code`、`parent_code`、`title`、`category`、`difficulty`、`content_text`、`key_points_json`、`prerequisites_json`、`related_nodes_json` | 知识树和节点内容。`node_code` 唯一。 |
| `learning_progress` | `node_code`、`status`、`mastery_level`、`study_count`、`last_studied_at`、`notes` | 单用户学习进度；对 `node_code` 建唯一约束。 |
| `code_exercises` | `node_code`、`title`、`description`、`template_code`、`reference_solution`、`test_cases_json`、`difficulty` | 练习定义。 |
| `exercise_submissions` | `exercise_id`、`submitted_code`、`score`、`feedback`、`issues_json`、`improved_code`、`created_at` | 每次提交的评估快照。 |
| `learning_category_mappings` | `source_category`、`node_code`、`priority` | 面试分类到学习节点的可配置映射。 |
| `learning_skill_sources` | `skill_id`、`category_key`、`node_code`、`content_hash`、`synced_at` | Skill 文件与学习节点的来源映射，用于全量覆盖和幂等同步。 |
| `learning_plans` | `name`、`source_session_id`、`target_date`、`status`、`created_at` | 用户确认后的学习计划。 |
| `learning_plan_tasks` | `plan_id`、`node_code`、`task_date`、`status`、`priority`、`estimated_minutes`、`completed_at` | 按日期拆分的学习任务。 |
| `learning_reminders` | `task_id`、`remind_at`、`channel`、`status`、`sent_at` | 应用内提醒记录与幂等状态。 |

### 6.4 API 契约

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/learning/knowledge-tree` | 获取知识树。 |
| GET | `/api/learning/nodes/{nodeCode}` | 获取节点详情。 |
| POST | `/api/learning/nodes/{nodeCode}/explain` | 生成节点讲解。 |
| POST | `/api/learning/nodes/{nodeCode}/ask` | 节点定向问答。 |
| GET | `/api/learning/exercises?nodeCode=` | 获取练习。 |
| POST | `/api/learning/exercises/{exerciseId}/submissions` | 提交代码练习。 |
| GET | `/api/learning/progress` | 获取总体进度和节点进度。 |
| PUT | `/api/learning/progress/{nodeCode}` | 更新节点状态、掌握度、备注。 |
| GET | `/api/learning/path-recommendation` | 获取推荐学习路径。 |
| POST | `/api/learning/recommendations/from-interview/{sessionId}` | 基于指定面试报告生成推荐预览，不直接覆盖进度。 |
| POST | `/api/learning/plans` | 确认路径并创建带目标日期的学习计划。 |
| GET | `/api/learning/plans` | 获取学习计划及每日任务。 |
| PUT | `/api/learning/plans/{planId}/tasks/{taskId}` | 调整任务日期、状态和顺序。 |
| GET | `/api/learning/reminders` | 获取当前待展示的提醒。 |

### 6.5 前端需求与验收

1. 新增学习中心入口及 `/learning` 路由，覆盖全部现有 Skill，不限定 Java 后端方向。
2. 页面包含知识树、节点详情、进度概览、练习区和学习路径区。
3. 节点状态、掌握度和推荐理由必须可见。
4. 讲解、问答、代码评估应显示处理中、成功和失败状态；失败不改变学习进度。
5. 用户完成一个节点并刷新页面后，状态和掌握度保持一致。
6. 用户确认学习路径后，可查看按日期分组的任务；任务到期和逾期状态可见。

## 7. 功能二：普通 Chat

### 7.1 用户流程

1. 用户进入普通 Chat 页面，创建或选择会话。
2. 用户发送问题，立即出现用户消息和助手占位消息。
3. 前端通过 SSE 接收并逐段渲染回答。
4. 流结束后，完整回答持久化；异常时持久化已接收内容和失败状态。
5. 用户可重命名、置顶、删除会话。

### 7.2 功能需求

| 编号 | 功能 | 要求 |
| --- | --- | --- |
| C-01 | 会话管理 | 创建、列表、详情、重命名、置顶、删除。列表按置顶、最近更新时间排序。 |
| C-02 | 流式问答 | 使用 SSE；创建会话时固化默认 Provider，用户界面和 API 不提供会话内 Provider 切换。 |
| C-03 | 上下文 | 默认携带最近 N 轮完整消息；超出阈值时生成或更新会话摘要。摘要和原消息均需可追溯。 |
| C-04 | 消息一致性 | 发送前创建助手占位消息；完成时标记 `completed=true`；异常时记录错误标记，不丢失已生成内容。 |
| C-05 | 会话标题 | 创建时可传标题；未传时使用默认标题；用户可手动改名。本期不要求 AI 自动标题。 |
| C-06 | 安全边界 | 普通 Chat 不自动注入知识库内容，不执行工具调用，不访问本地文件或 S3 对象。 |

### 7.3 数据模型

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `chat_sessions` | `title`、`status`、`is_pinned`、`message_count`、`provider_id`、`provider_snapshot_json`、`summary`、`created_at`、`updated_at` | 普通 Chat 会话；不与知识库关联，Provider 在创建时锁定。 |
| `chat_messages` | `session_id`、`role`、`content`、`message_order`、`completed`、`error_code`、`created_at` | 用户、助手和摘要消息；按会话和序号唯一。 |

### 7.4 API 契约

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/chat/sessions` | 创建会话。 |
| GET | `/api/chat/sessions` | 获取会话列表。 |
| GET | `/api/chat/sessions/{sessionId}` | 获取会话与消息历史。 |
| PUT | `/api/chat/sessions/{sessionId}/title` | 修改标题。 |
| PUT | `/api/chat/sessions/{sessionId}/pin` | 切换置顶。 |
| DELETE | `/api/chat/sessions/{sessionId}` | 删除会话及消息。 |
| POST | `/api/chat/sessions/{sessionId}/messages/stream` | SSE 流式发送消息。 |

### 7.5 复用与验收

1. 复用当前 `RagChatController`、`RagChatSessionService` 的会话持久化和 SSE 生命周期模式；不要通过“空知识库”模拟普通 Chat。
2. 普通 Chat 必须使用 `LlmProviderRegistry.getChatClientOrDefault(provider)`，不能依赖旧项目的单例 `ChatClient` 注入；会话创建后不得切换 Provider。
3. 新增 `/chat` 路由、会话侧栏和消息区；Markdown、代码块及流式渲染复用现有聊天 UI 组件。
4. 创建、重命名、置顶、删除、流式成功、流式失败六类场景均需有自动化测试。

## 8. 功能三：多轮面试

### 8.1 默认轮次与状态

默认预设为 4 轮：

| 轮次索引 | 轮次类型 | 核心行为 |
| --- | --- | --- |
| 0 | `ALGORITHM` | 算法代码题、草稿保存、AI 静态评估、倒计时。 |
| 1 | `TECH_R1` | 基础技术面，结合简历、Skill、知识库题目。 |
| 2 | `TECH_R2` | 系统设计、项目深度和架构追问。 |
| 3 | `HR` | 行为面试、沟通、动机与岗位匹配。 |

轮次状态：`PENDING → IN_PROGRESS → COMPLETED → EVALUATED`。仅当前轮次可开始；上一轮未完成时，下一轮不可开始。会话状态必须与当前轮次状态一致。

### 8.2 功能需求

| 编号 | 功能 | 要求 |
| --- | --- | --- |
| M-01 | 创建会话 | 创建多轮会话和 4 条轮次记录；保存面试配置、Provider 快照、Skill/JD/知识库选择。 |
| M-02 | 轮次启动与恢复 | 获取当前轮次；用户中断后可从最后一个 `IN_PROGRESS` 轮次恢复。 |
| M-03 | 分轮出题 | 每轮首次进入时生成并固化题目；重复进入不得重复生成。算法、技术、HR 使用不同提示词与结构化输出模型。 |
| M-04 | 算法代码题 | 提供代码编辑、草稿保存、提交评估、复杂度分析和参考解法。明确展示“AI 静态评估，不执行代码”。 |
| M-05 | 技术/HR 问答 | 复用当前单轮面试的答题、追问、评估能力，但结果必须按轮次隔离。 |
| M-06 | 完成与流转 | 完成当前轮次后生成该轮报告；仅成功保存报告后允许进入下一轮。 |
| M-07 | 汇总报告 | 返回每轮得分、优势、改进项、轮次摘要、总分、最终建议和结果等级。V1 总分采用 4 轮等权平均，权重字段预留配置化。 |
| M-08 | 历史记录 | 面试历史应显示 `MULTI_ROUND`、当前轮次、进度和最终状态，并可跳转恢复或查看汇总报告。 |
| M-09 | 自主编排 Agent | Agent 根据简历、Skill、JD、知识库范围、当前轮次、已答内容和评估结果，自主决定分类配比、下一题、是否追问、何时结束当前轮和学习建议。 |
| M-10 | Agent 约束 | 后端状态机负责轮次进入/退出；Agent 只可调用白名单工具，单轮调用次数、追问次数、Token 和执行时间必须受限。 |
| M-11 | 三语言模板 | 算法轮提供 Java、Python、C++ 模板；提交时保存语言、模板版本和代码快照，AI 评估必须使用对应语言提示词。 |

### 8.3 数据模型与迁移

1. 对 `interview_sessions` 新增：`session_mode`、`current_round_index`、`interview_result`、`provider_snapshot_json`。
2. 新增 `interview_rounds`：会话外键、轮次类型、索引、状态、题目 JSON、当前题序、开始/完成时间、算法截止时间、分数、反馈、优势、改进项、评估状态与错误信息。
3. 新增 `algorithm_answers`：轮次外键、题目序号、代码、语言、评估分数、复杂度、反馈、参考解法、AI 评估元数据。
4. 对 `interview_answers` 增加 `round_id` 可空外键；旧单轮数据保持 `round_id = null`。
5. 新增 `interview_agent_runs`、`interview_agent_steps`：记录 Agent 计划、工具调用、输入摘要、输出摘要、耗时、终止原因和错误，供恢复、审计和调试使用。
6. Flyway 迁移须可在已存在的 `interview_guide_v2` 目标 schema 上执行；不得编辑 `V1__init_schema.sql`。

### 8.4 API 契约

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/interview/multi-round/sessions` | 创建多轮会话。 |
| GET | `/api/interview/multi-round/sessions/{sessionId}/current-round` | 获取当前轮次和可执行动作。 |
| POST | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/start` | 启动轮次。 |
| GET | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/questions` | 获取或生成固化题目。 |
| PUT | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/answers` | 保存技术/HR 答案草稿。 |
| POST | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/answers` | 提交技术/HR 答案。 |
| PUT | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/algorithm-answers/{questionIndex}` | 保存算法代码草稿。 |
| POST | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/algorithm-answers/{questionIndex}/evaluate` | AI 评估算法代码。 |
| POST | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/complete` | 完成并评估当前轮次。 |
| POST | `/api/interview/multi-round/sessions/{sessionId}/next-round` | 校验并进入下一轮。 |
| GET | `/api/interview/multi-round/sessions/{sessionId}/rounds/{roundIndex}/report` | 获取轮次报告。 |
| GET | `/api/interview/multi-round/sessions/{sessionId}/report` | 获取汇总报告。 |
| GET | `/api/interview/multi-round/sessions/{sessionId}/agent-trace` | 获取可展示的 Agent 编排轨迹，不暴露系统提示词和密钥。 |

### 8.5 前端需求与验收

1. 新增面试模式选择页、多轮配置页、算法轮页面、技术/HR 轮页面、汇总报告页。
2. 页面必须显示 4 轮状态、当前轮次、可进入轮次、倒计时和恢复入口。
3. 页面刷新后，题目、答案草稿、当前题序、轮次状态保持一致。
4. 任何未完成轮次不得通过前端路由或 API 跳过。
5. 算法题提交结果包含总分、复杂度、反馈、参考解法和 AI 评估提示。
6. 汇总报告必须同时显示分轮结果和总结果；总分为空时不得显示为 0 分。
7. 算法编辑器必须能在 Java、Python、C++ 模板之间选择；切换语言不覆盖用户已保存的其他语言草稿。
8. Agent 每次编排必须可追溯到轮次、计划、工具调用结果和终止原因；前端只展示面向用户的计划与理由。

## 9. 技术落地与复用映射

| 目标能力 | 当前项目可复用能力 | 不可直接复用的旧实现 |
| --- | --- | --- |
| 自适应学习 | `LlmProviderRegistry`、`StructuredOutputInvoker`、面试报告分类、Flyway | 旧 `LearningService` 的单一 `ChatClient`、Hibernate 自动建表、旧包名。 |
| 普通 Chat | `RagChatController` SSE 生命周期、RAG 会话持久化模式、前端 Markdown 渲染 | 旧 Chat 的单 Provider 调用方式。 |
| 多轮面试 | 当前单轮会话、题目生成、答题评估、Skill/JD、知识库面试、Provider 配置、`spring-ai-agent-utils` | 旧控制器中的 `Map<String,Object>` 请求体、固定题型映射、旧数据库 schema。 |

## 10. 交付顺序与依赖

| 优先级 | 交付项 | 依赖 |
| --- | --- | --- |
| P0 | Agent 工具契约、执行预算、审计轨迹和 Flyway 基础迁移 | `LlmProviderRegistry`、`spring-ai-agent-utils`。 |
| P0 | 普通 Chat 后端与前端，Provider 创建时锁定 | LLM Provider、SSE 基础设施。 |
| P0 | 多轮面试会话/轮次状态机、受控 Agent、分轮报告、汇总报告 | 当前单轮面试服务、Flyway。 |
| P1 | Java/Python/C++ 模板、算法草稿与 AI 静态评估 | 多轮面试状态机、提示词模板、前端编辑器依赖。 |
| P1 | 全 Skill 学习目录、进度、路径推荐 | Flyway、Skill 同步服务。 |
| P1 | 学习计划、每日任务、应用内提醒 | 学习模块、定时任务。 |
| P2 | 面试报告到学习路径的映射、代码练习 | 学习模块、面试分类标准化。 |

## 11. 质量与验收标准

1. 所有新增数据库对象由版本化 Flyway 脚本创建；在空库和已有目标库上均可迁移。
2. 普通 Chat、学习 AI 调用、多轮出题和算法评估均能选择当前已启用 Provider。
3. 流式 Chat 在异常时不会丢失用户消息和已生成内容。
4. 多轮会话不能跳轮；刷新或重启后可从当前轮次恢复。
5. 单轮面试、RAG Chat、知识库面试、语音面试的回归测试通过。
6. 新增后端 Service、状态机和迁移覆盖单元测试；新增前端路由、状态恢复和 SSE 完成/异常覆盖前端测试。
7. 改动完成后执行 `./gradlew :app:test --no-daemon` 与 `cd frontend && pnpm run build`。

## 12. 风险与决策

| 风险 | 影响 | 决策 |
| --- | --- | --- |
| LLM 静态评估不等同代码运行 | 算法题分数可能不稳定 | V1 明确标注 AI 评估；真实判题列为后续独立能力。 |
| 当前无用户系统 | 多用户部署时进度和会话会混用 | V1 采用单用户模型；表设计和 Service 接口预留 `user_id`。 |
| Provider 在会话过程中被修改 | 面试/Chat 可复现性下降 | 创建会话时保存 Provider 快照或 Provider ID。 |
| 多轮题目生成耗时 | 首次进入轮次等待较长 | 首次请求生成并固化；后续访问只读已保存题目。 |
| Agent 自主决策越权或循环 | 跳轮、重复追问、Token 失控 | 状态机不交给 Agent；工具白名单、最大步骤数、最大追问数、预算和终止状态由后端强制执行。 |
| Skill 文件更新后目录陈旧 | 学习内容覆盖不完整 | 用内容 Hash 幂等同步 Skill 与学习节点，并保留来源映射。 |
| 应用关闭后无法显示提醒 | 用户错过任务 | V1 仅提供应用内与前台浏览器通知；离线系统通知、短信、邮件不在本期范围。 |
| 旧库表结构不完整 | 错误基线化或校验失败 | 仅在 `interview_guide_v2` 目标库实施，使用新 Flyway 迁移。 |
