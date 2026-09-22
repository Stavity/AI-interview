# 多轮面试受控 ADK 动态工作流目标设计

> 状态：设计评审稿  
> 日期：2026-09-22  
> 适用基线：`redesign-kafka` 当前代码；旧 `origin/main` 的 ADK 实现仅作为可复用设计资产，不代表当前已具备的能力。

## 1. 结论

多轮面试应建设为**可恢复、可审计、可降级的个性化动态工作流**。Google ADK 的职责是受控地生成规划建议，领域服务的职责是校验、持久化、出题、评估、状态推进和异步投影。

```text
简历 / JD / 目标 / Skill
          ↓
受控 ADK：证据归纳 → 策略制定 → Root 决策
          ↓
WorkflowPolicyGate：Schema、预算、权限、状态约束
          ↓
领域工作流：节点持久化 → 出题 → 作答/评估 → 自动推进
          ↓
报告事实与学习证据投影 → 个性化建议
```

**面试节点不是 Agent。** 节点是可持久化的业务阶段；Agent 是少量、固定、受限的规划角色。不得按“算法节点一个 Agent、项目节点一个 Agent”的方式建模。

## 2. 用户可感知目标

完成后，用户应能感知到以下能力：

1. 创建面试后，根据简历、JD、目标岗位、Skill 和预算生成 3–6 个不同的面试节点，而非总是固定四轮。
2. 每个安全检查点根据真实表现调整**未来尚未开始**的节点：方向、难度、题数与追问策略可变化。
3. 用户只提交答案或代码；后端自动评估、准备下一节点并推进流程，不暴露“开始下一轮”“结束流程”“跳到下一轮”等流程控制。
4. 刷新、断线、关闭页面或服务重启后，可恢复到同一节点、同一题目、同一已接受决策；不得重复调用 Root Agent 或重复出题。
5. 最终报告展示实际面试路径、能力证据、策略调整与学习建议，而非仅显示完成轮数。
6. Provider、ADK、Tool 或结构化输出失败时，在安全检查点自动切换到可靠静态流程，用户仍可完成面试。

## 3. 现有代码审查结论

### 3.1 当前 `redesign-kafka` 的可保留资产

| 资产 | 当前位置 | 保留要求 |
| --- | --- | --- |
| 固定四轮初始化 | `MultiRoundInterviewService`、`MultiRoundStateMachine` | 改为静态 workflow definition 的兜底实现，不再作为唯一流程模型。 |
| 生命周期一致性 | `MultiRoundLifecycleService` | 保留“LLM 在事务外、题目齐全后才进入 IN_PROGRESS”的边界。 |
| 幂等与并发控制 | `MultiRoundOperationService`、`MultiRoundIdempotencyService`、Redis 锁 | 动态节点的创建、推进和决策接受必须复用这套语义。 |
| 上下文聚合 | `MultiRoundPlanningContextFactory` | 演进为受限只读数据门面，不允许 Agent 直接访问 Repository。 |
| 当前结构化规划 | `InterviewAgentOrchestrator`、`AgentPlanValidator` | 作为 `LEGACY` 策略，并复用校验、审计和降级理念。 |
| 题目/算法评审 | `MultiRoundQuestionGenerationService`、算法提交与静态评审服务 | 继续由领域服务执行；不改为 Agent 的写入能力。 |
| 运行审计 | `AgentRunAuditService`、`AgentRunStepAuditService`、`AgentRunTraceService` | 扩展 runtime、session、policy、decision 与降级信息。 |
| 异步可靠性 | Async Task、Kafka Outbox | 用于报告、学习证据等异步投影；不新建第二套任务基础设施。 |

### 3.2 当前限制与改造点

| 当前事实 | 目标改造 |
| --- | --- |
| `defaultRounds()` 固定算法、技术一面、技术二面、HR | 从版本化 workflow definition 读取节点；静态定义仅作兜底。 |
| `currentRoundNo + 1` 决定下一轮 | 从 workflow 的下一个 `PENDING` / `READY` 节点决定下一步。 |
| 前端调用 `start`、`finish`、`next-round` | 用户动作仅保留回答、提交代码、必要的恢复；流程推进由服务端完成。 |
| Agent 每轮输出 `InterviewPlan` | Root 在创建期输出初始节点图；安全检查点输出未来节点 mutation。 |
| 报告为确定性轮次汇总 | 建立事实报告、工作流路径和证据投影；叙述型模型输出仅作为可选增强。 |
| 学习建议按全局掌握度排序 | 以本次面试的弱项、分数、置信度和覆盖情况生成 session 级学习优先级。 |

### 3.3 旧 `origin/main` ADK 实现的复用边界

旧分支的 `AdkInterviewPlanner` 真实实现过 `SequentialAgent`：`evidence_agent → adaptation_agent → root_interview_agent`，具备只读 FunctionTool、步骤上限、禁止 Agent 转移、Session、决策 checkpoint、`LEGACY / SHADOW / GOOGLE_ADK` 和静态回退。

可复用：ADK Provider Adapter、只读 Tool 策略、Session 命名、审计字段、运行模式、checkpoint 去重与回退原则。

不可直接整体恢复：旧的 workflow 服务、迁移和多轮调用路径。当前分支已经有新的生命周期、幂等和 Kafka Outbox 改造，必须以当前服务为承载点重新接入，不能 wholesale cherry-pick。

## 4. 不可变架构边界

1. PostgreSQL 中的领域表是面试、题目、答案、评估、报告与学习证据的唯一事实来源。
2. ADK Agent 只能调用服务端提供的只读 Tool，并返回严格结构化建议。
3. Agent 不得执行数据库写入、HTTP、文件、进程、任意 MCP、反射或 Provider 配置/密钥读取。
4. `WorkflowPolicyGate` 是唯一接受或拒绝 Agent 决策的入口；领域状态机是唯一状态转换和数据库写入方。
5. LLM/ADK/外部调用全部在事务外执行；决策接受、节点物化、状态推进在独立短事务中完成。
6. 已展示、已开始、已回答、已完成的节点与题目绝不被动态决策替换。
7. 失败回退对单场面试不可逆，防止同一会话在 ADK 与静态策略间来回混用。

## 5. 目标领域模型

### 5.1 Workflow、节点与决策

```text
multi_round_interviews
  └── multi_round_workflow_instances (1:1)
        ├── multi_rounds                 (动态节点，保留表名)
        ├── multi_round_workflow_decisions
        ├── multi_round_workflow_events
        └── interview_learning_evidence
```

`multi_rounds` 演进为节点，新增字段至少包括：

| 字段 | 用途 |
| --- | --- |
| `node_key` | 同一 workflow 内稳定且唯一的节点标识。 |
| `node_type` | `ALGORITHM`、`TECHNICAL_FOUNDATION`、`PROJECT`、`SYSTEM_DESIGN`、`LEADERSHIP`、`HR` 等白名单类型。 |
| `display_order` | 前端排序；不再依赖 `round_no + 1`。 |
| `difficulty_band` | 服务端允许的难度区间。 |
| `coverage_json` | 能力目标、权重、已覆盖情况。 |
| `origin` | `STATIC`、`ADK`、`FALLBACK`。 |
| `decision_id` | 创建或最近调整该节点的已接受决策。 |
| `workflow_revision` | 乐观并发控制与前端快照版本。 |

`round_no`、`round_type` 在迁移期保留给历史数据和兼容 DTO，但不得再被用作推进规则。

### 5.2 状态

```text
节点：PENDING → READY → IN_PROGRESS → EVALUATING → COMPLETED
                                  └──────────────→ FAILED_RECOVERABLE
未来节点：PENDING → SKIPPED / REPLACED

工作流：READY → RUNNING → PAUSED → RUNNING → COMPLETED
                               └──────────→ STATIC_FALLBACK
```

`PAUSED` 表示租约、连接或进程中断，不等同于业务失败。恢复必须从持久化 checkpoint 和已接受 decision 继续。

## 6. ADK Agent 图

一期只运行三个固定角色：

```text
EvidenceAgent
  输入：既往回答、算法评审、轮次报告、覆盖度
  输出：优势、薄弱点、不确定项、可引用证据摘要
        ↓
StrategyAgent
  输入：证据摘要、简历/JD/Skill、剩余预算、当前 workflow
  输出：后续覆盖目标、难度、题数、追问预算
        ↓
RootAgent
  输入：证据与策略、服务端规则快照
  输出：InitialWorkflowDecision 或 WorkflowAdaptationDecision
```

`Question`、`Evaluation`、`Learning`、`Report` 不应在一期被强制设计为独立 ADK Agent：题目生成、评估与持久化已有领域服务；报告和学习先形成确定性事实，后续只增加叙述型 Agent。

所有 Agent 禁止开放式 Agent-to-Agent 委派。Root 只能编排服务端固定注册的三个子 Agent 与预定义 checkpoint。

## 7. 决策契约与 Policy Gate

### 7.1 Agent 输出

```java
record InitialWorkflowDecision(
    String policyVersion,
    List<WorkflowNodeDefinition> nodes,
    int totalQuestionBudget,
    String rationaleSummary) {}

record WorkflowAdaptationDecision(
    long baseRevision,
    List<WorkflowMutation> mutations,
    String evidenceSummary,
    String rationaleSummary) {}
```

允许的 mutation：`KEEP`、`CHANGE_DIFFICULTY_BAND`、`CHANGE_QUESTION_COUNT`、`INSERT_AFTER_CURRENT`、`SKIP_FUTURE_NODE`、`REPLACE_FUTURE_NODE`、`MERGE_FUTURE_NODES`。

### 7.2 `WorkflowPolicyGate` 的硬校验

- JSON Schema、未知字段、枚举与必填字段校验；
- 节点数、总题数、单节点题数、追问数、输入输出长度、Tool 次数、运行时长与成本预算；
- 节点类型白名单、唯一 `node_key`、无环、显示顺序与业务规则；
- 算法节点、HR 节点和最低技术覆盖等业务约束；
- `baseRevision` CAS 校验；
- mutation 只能影响 `PENDING` 节点；
- 输出中不得出现 SQL、HTTP、工具名、状态迁移命令或持久化指令。

校验通过后，事务内写入 accepted decision、事件和节点变更；失败则写 `REJECTED` 审计并走静态决策。

## 8. 只读 Tool、Session 与审计

允许的 Tool 仅包括：

| Tool | 返回内容 |
| --- | --- |
| `read_interview_snapshot` | 截断后的简历、JD、Skill、难度、目标和预算。 |
| `read_prior_answers` | 当前会话、当前节点之前的有限回答摘要。 |
| `read_round_reports` | 既往轮次的结构化报告摘要。 |
| `read_question_coverage` | 已覆盖、缺失和剩余预算，不返回不必要的题目正文。 |
| `read_workflow_snapshot` | 当前节点、未来节点、revision 与允许 mutation 范围。 |

Tool 内部必须从服务端的当前 `interviewId` 推导数据范围，模型不得传入任意会话 ID。

ADK Session 仅保存逻辑会话标识、策略版本、上下文哈希和恢复辅助信息；不得让 ADK 内存成为事实来源。审计仅保存哈希、长度、分类、耗时、Tool 名称、状态、安全摘要和决策 JSON；不记录密钥、完整简历、完整回答、完整提示词或 Chain-of-Thought。

## 9. 运行模式与降级

| 模式 | 行为 |
| --- | --- |
| `LEGACY` | 使用当前受控单轮规划与静态节点定义。 |
| `SHADOW` | ADK 产出初始图/调整建议并审计，但真实流程仍使用静态策略。 |
| `GOOGLE_ADK` | 接受通过 Policy Gate 的 ADK 决策。 |
| `STATIC_FALLBACK` | 当前会话发生 ADK 故障后的不可逆静态执行模式。 |

回退触发：Provider 不可用、超时、Tool 失败、超预算、结构化输出失败、Policy Gate 拒绝、checkpoint CAS 冲突无法安全重试。回退只在安全检查点发生，并记录错误码和对应 revision。

## 10. 接口与前端目标

### 10.1 服务端视图

```java
record WorkflowView(
    long workflowRevision,
    String runtimeMode,
    String status,
    WorkflowNodeView currentNode,
    List<WorkflowNodeView> nodes,
    String lastDecisionSummary,
    String degradedReason,
    String reportStatus) {}
```

创建、恢复与查询接口返回 `WorkflowView`。SSE 事件至少包含 `NODE_READY`、`NODE_STARTED`、`NODE_COMPLETED`、`WORKFLOW_MUTATED`、`WORKFLOW_FALLBACK`、`REPORT_READY`；断线后以快照接口恢复。

### 10.2 用户动作

保留：创建、恢复、提交答案、提交算法代码、重试明确失败的用户提交。  
逐步废弃：用户显式 `startRound`、`finishRound`、`nextRound`。

前端从固定四格时间线改为按 `WorkflowView.nodes[]` 渲染：当前节点显示题目与进度，未来节点仅显示已接受目标；被跳过或替换的节点保留为历史路径，不得消失或被改写。

## 11. 报告与学习闭环

先生成可解释的确定性事实：

```text
回答 / 代码评审 / 轮次报告
          ↓
LearningEvidenceProjection(category, score, confidence, sourceNode)
          ↓
确定性优先级计算
          ↓
面试报告、学习建议、学习计划草稿
          ↓
可选的 ReportNarrator / LearningNarrator 生成解释性文案
```

模型不得直接修改全局掌握度、激活学习计划或覆盖已有事实。报告必须展示实际 workflow 路径、节点 mutation、证据来源、薄弱项和建议原因。

## 12. 实施阶段

| 阶段 | 交付内容 | 核心验收 |
| --- | --- | --- |
| P0：规格收敛 | 本文作为主设计；统一旧 TRD 的表、接口、状态与术语。 | 不再把旧 `origin/main` 误写成当前已实现能力。 |
| P1：静态动态化 | workflow definition/instance/event；节点模型；服务端自动推进。 | 关闭 Provider 后仍可创建、恢复并完成静态面试。 |
| P2：ADK POC | Provider Adapter、只读 Tool、三个 Agent、Policy Gate、审计。 | 非法输出、Tool 越权、超时均无法影响领域状态。 |
| P3：Shadow | ADK 对比静态计划，不写真实节点。 | 采集合法率、耗时、回退率、策略差异。 |
| P4：初始图灰度 | ADK 生成初始节点图，静态定义可回退。 | 不同简历/JD 可得到不同节点数量或类型。 |
| P5：动态调整 | 安全检查点 mutation、revision CAS、恢复协议。 | 只改未来节点；恢复不重复 Root 调用。 |
| P6：报告/学习/前端 | 证据投影、动态时间线、自动流程、报告。 | 用户无需手动推进；报告包含实际路径与学习证据。 |

## 13. 测试与完成标准

1. Flyway 在空库和现有升级库均成功；历史会话被标记为 `LEGACY_STATIC`，不重新编排、不重写题目。
2. 任意重复 HTTP 请求、并发提交、重复事件或重试不会创建重复节点、决策、题目或报告。
3. 已接受 decision 在恢复后复用，Root Agent 不被再次调用。
4. Policy Gate 覆盖非法类型、超预算、改写已开始节点、revision 冲突、未知字段和敏感指令。
5. Tool 范围隔离：无法读取其他面试、Provider 密钥、文件或任意数据库数据。
6. ADK 故障、Provider 故障和 Kafka 延迟不会留下不可恢复的 `IN_PROGRESS + 0 题` 状态。
7. 前端刷新、SSE 断线与恢复后，节点顺序、题目、提交状态与 workflow revision 一致。
8. `SHADOW` 不改变真实节点和题目；`STATIC_FALLBACK` 后不再执行 ADK 决策。
9. 最终报告能关联实际节点路径、评分事实、证据和学习建议；学习建议不能静默改写掌握度或激活计划。

## 14. 对既有文档的处理

本文是当前分支的目标设计入口。以下文档保留为依据，但后续实现必须以本文的边界和阶段顺序为准：

- `TRD_Google_ADK_受控面试编排升级.md`：复用 Tool 白名单、运行模式、审计、灰度与回退设计。
- `TRD_ADK动态面试流程编排改造方案.md`：复用动态节点、决策模型、前端时间线和验收场景。
- `TRD_多轮面试持久化工作流与ADK编排优化方案.md`：复用 workflow/checkpoint/恢复原则，但收缩一期 Agent 数量。
- `TRD_多轮面试全流程现状与动态ADK升级方案.md`：复用全流程、快照、事件、兼容与验收思路。

实施前需在四份旧文档头部标注“历史/参考”，并删除或更正其与 `redesign-kafka` 当前代码不一致的“已实现”描述。

## 15. 可执行服务边界与职责

### 15.1 新增或演进的核心服务

| 服务 | 职责 | 禁止承担的职责 |
| --- | --- | --- |
| `WorkflowDefinitionService` | 提供版本化静态 definition；将通过 Gate 的初始 decision 转为 definition。 | 不生成模型决策，不推进节点。 |
| `WorkflowInstanceService` | 创建实例、持有 revision/lease/checkpoint、读取快照、暂停与恢复。 | 不直接生成题目或执行 LLM。 |
| `WorkflowExecutor` | 在安全检查点选择静态或 ADK 决策器，并提交候选 decision。 | 不绕过 Policy Gate 写节点。 |
| `AdkWorkflowPlanner` | 将三个固定 Agent 串成一次受限 ADK run。 | 不能直接访问 Repository 或产生副作用。 |
| `StaticWorkflowPlanner` | 在无模型、失败或回退时生成同 schema 的静态 decision。 | 不依赖 ADK Session。 |
| `WorkflowPolicyGate` | 校验、拒绝或接受 decision，并在短事务内物化节点 mutation。 | 不调用 LLM，不执行 Kafka 消费。 |
| `WorkflowProgressService` | 根据题目、答案、评估和节点状态，决定可否自动推进。 | 不以浏览器事件作为唯一事实。 |
| `WorkflowRecoveryService` | 以 sessionId、checkpoint、accepted decision、节点和题目恢复。 | 不重新生成已接受的初始图。 |
| `LearningEvidenceProjectionService` | 将评估事实投影为带来源和置信度的学习证据。 | 不直接激活学习计划或覆盖掌握度。 |
| `WorkflowViewAssembler` | 聚合数据库事实输出前端快照。 | 不让前端的本地状态成为流程依据。 |

### 15.2 建议的包结构

```text
modules/multiround/
  workflow/
    model/          WorkflowDefinition, WorkflowDecision, WorkflowMutation
    service/        WorkflowExecutor, WorkflowPolicyGate, WorkflowRecoveryService
    repository/     WorkflowInstanceRepository, WorkflowDecisionRepository, WorkflowEventRepository
    dto/            WorkflowView, WorkflowNodeView
  service/          保留现有生命周期、题目、评估、报告服务
common/ai/adk/
  AdkWorkflowPlanner, AdkPlanningReadFacade, AdkToolPolicy,
  AdkSessionFactory, AdkProviderAdapter, AgentRuntimeProperties
```

领域模块不得直接调用其他领域的 Repository；只读 Tool 应依赖 `AdkPlanningReadFacade` 这类服务接口，避免将 JPA Entity、完整敏感字段或跨会话查询能力暴露给模型。

## 16. 详细状态转换与自动推进协议

### 16.1 创建期

```text
POST create/resume
  → 锁定或读取 multi_round_interview
  → 若 workflow instance 已存在：直接返回 WorkflowView
  → 否则创建 workflow instance（状态 INITIALIZING）
  → 事务提交
  → 根据 runtime 生成 InitialWorkflowDecision
  → PolicyGate 接受 decision，物化节点和 WORKFLOW_CREATED event
  → 将第一个节点置为 READY
  → 返回 WorkflowView
```

创建请求必须使用 `Idempotency-Key`。同一 `sessionId + CREATE + key` 的重试返回相同 workflow；不同 key 的并发创建通过 `UNIQUE(multi_round_interview_id)` 与锁内复查收敛为同一实例。

`GOOGLE_ADK` 初始化失败时，不将 workflow 留在 `INITIALIZING`：写失败审计与 `WORKFLOW_FALLBACK` 事件，然后使用 `StaticWorkflowPlanner` 生成固定 definition。静态初始化也失败才可返回明确的可重试服务错误。

### 16.2 当前节点准备与出题

```text
READY
  → 取得节点级 operation lease
  → 读取 accepted decision、节点配置和历史证据
  → 事务外生成题目草稿
  → 短事务二次校验 revision / node status / 题目数量
  → 批量持久化问题
  → 节点置为 IN_PROGRESS
  → 写 NODE_STARTED event
```

必须保持现有不变量：**一个节点要么是 `READY/PENDING` 且没有部分物化题目，要么是 `IN_PROGRESS` 且题目达到已接受的数量。**

若题目生成超时、内容不合法或保存冲突：删除或不提交本次草稿，节点回到 `READY`，记录可重试错误；不得出现 `IN_PROGRESS + 0 题` 或仅部分题目且无法完成的中间态。

### 16.3 作答、评估与推进

```text
用户提交答案/代码
  → 幂等写入答案或代码快照
  → 同步或异步产生评估事实
  → 所有必需题目进入已回答/已评估状态
  → 节点置为 EVALUATING
  → 生成 round report 与 learning evidence
  → 节点置为 COMPLETED
  → 安全检查点：请求后续 WorkflowAdaptationDecision
  → 接受 mutation 或静态决策
  → 下一个可执行节点置为 READY；若没有则 workflow COMPLETED
```

“节点结束”不是由前端按钮决定，而由 `WorkflowProgressService` 校验以下事实后自动触发：

- 必答题已经提交；
- 算法题的必要评审已完成或已经按照可重试策略明确失败；
- 允许的追问次数已经耗尽或追问条件不再满足；
- 当前节点题数、预算和状态均满足 accepted decision；
- 当前节点没有处于执行中的关键异步任务。

### 16.4 安全检查点

允许触发 ADK 的 checkpoint 仅为：

1. 新工作流初始化；
2. 节点完成并生成结构化报告后；
3. 算法静态评审完成且后续节点尚未物化时；
4. 用户恢复 `PAUSED` workflow 且当前 checkpoint 从未有 accepted decision；
5. 管理员允许的人工重算请求，且没有已展示/已开始节点会被影响。

禁止在流式回答中、题目正在展示时、节点出题事务中或当前节点状态未知时执行 mutation。

## 17. 并发、幂等与一致性设计

### 17.1 幂等键规范

| 操作 | 幂等范围 | 成功重试结果 |
| --- | --- | --- |
| 创建 workflow | `sessionId + CREATE` | 同一 workflow view。 |
| 回答题目 | `sessionId + ANSWER + questionId` | 同一答案状态，不重复触发评估。 |
| 提交代码 | `sessionId + ALGORITHM_SUBMIT + questionId` | 同一 submission。 |
| 生成当前节点题目 | `workflowId + nodeKey + revision + MATERIALIZE` | 同一题目集合。 |
| 接受初始 decision | `workflowId + INIT + policyVersion` | 同一 accepted decision。 |
| 接受 mutation | `workflowId + checkpointKey + decisionType` | 同一 accepted decision 或明确 revision 冲突。 |
| 报告/学习投影 | `workflowId + nodeKey + projectionVersion` | 同一投影，允许安全重放。 |

HTTP `Idempotency-Key` 不能替代领域唯一约束；消息重投、后台恢复与多实例并发仍需依赖数据库的唯一约束、条件更新与事件 key 去重。

### 17.2 乐观锁与悲观锁的使用

- workflow instance 使用 `revision` 或条件更新保证 mutation 只作用于读取时的版本；
- 节点物化与状态转换使用短时间的数据库行锁，避免同一节点同时出题或完成；
- 外部 LLM、ADK、Kafka、SSE 调用绝不放在行锁或数据库事务中；
- Redis 锁只用于降低并发冲突，不作为正确性的唯一保障；Redis 丢失、过期或网络分区后仍必须由数据库约束兜底。

### 17.3 revision 冲突

当 `WorkflowAdaptationDecision.baseRevision` 不等于当前 revision：

1. 不接受该 decision，不写节点；
2. 记录 `DECISION_REJECTED_REVISION_CONFLICT`；
3. 重新读取 workflow；
4. 若当前 checkpoint 仍无 accepted decision，可在有限次数内重新规划；
5. 超过重试次数或节点已变化时使用静态决策，而非无限重试模型。

## 18. 异常、恢复与补偿矩阵

| 场景 | 检测点 | 处理 | 对用户可见结果 |
| --- | --- | --- | --- |
| Provider 禁用/密钥失效 | ADK 或出题前 | 当前会话进入 `STATIC_FALLBACK`；静态题目策略继续。 | 提示“智能规划暂不可用，面试将按稳定策略继续”。 |
| ADK 超时 | Runner 超时 | 记录 `ADK_TIMEOUT`，不接受 candidate decision，执行静态 decision。 | 不丢失当前进度。 |
| Tool 超预算/越权 | Tool policy | 拒绝本次 run，审计失败；不执行任何 Tool 的写操作。 | 自动降级或稍后重试。 |
| JSON 不可解析/未知字段 | Schema 校验 | 记录拒绝原因，使用静态 planner。 | 无需用户手动修复。 |
| Policy Gate 拒绝 | 领域校验 | 保存 REJECTED decision 和安全摘要，走静态路径。 | 正常继续。 |
| 题目生成部分成功 | 草稿事务前/后 | 草稿不提交或依据 materialization key 读取完整已提交集；节点保持 READY。 | 显示“正在准备题目”，可自动恢复。 |
| 节点完成后进程崩溃 | 事件/状态不一致检查 | 用 event、节点状态、题目与报告事实重放投影；不得再出当前节点的题。 | 恢复后继续下一安全点。 |
| Kafka 重复投递 | task handler | handler 以 resourceId + task type + version 幂等；重复消息不重复写事实。 | 用户无感。 |
| Kafka 长时间不可用 | Outbox backlog | 工作流事实先在 PostgreSQL 提交；非关键投影稍后补偿。关键同步评估应有明确降级。 | 报告可能显示“生成中”，面试不丢失。 |
| SSE 断开 | 客户端重连 | 不以 SSE 作为命令通道；重新 GET WorkflowView。 | 进度恢复一致。 |
| 浏览器重复点击/网络重试 | 写接口 | 幂等记录 + 唯一约束 + 状态校验。 | 不重复作答、出题或推进。 |
| 用户退出很久 | lease 过期 | `RUNNING → PAUSED`；恢复时从持久化状态继续。 | 看到“可继续面试”。 |
| 用户在评估期间恢复 | task 状态查询 | 已完成则投影；进行中返回进度；超时则按失败策略重新投递一次。 | 不会重复提交答案。 |
| DB 写入失败 | 短事务失败 | 不发布成功事件；外部调用结果丢弃或按安全幂等重试。 | 可重试，不出现假成功。 |
| 数据库死锁/序列化失败 | 事务异常 | 有界退避重试条件更新；超过限制返回可恢复错误。 | 不静默吞错。 |
| 模型产生敏感/违规文本 | 输出审核/日志前 | 拒绝或脱敏，不写完整输出；走静态路径或安全文案。 | 显示通用降级提示。 |
| 报告生成失败 | 投影任务失败 | 面试状态仍为 COMPLETED；报告状态为 PENDING/FAILED_RETRYABLE。 | 可稍后自动生成。 |

### 18.1 恢复算法

恢复只接收 `sessionId`，严格按以下顺序读取：

1. 面试主记录和 workflow instance；
2. 当前 revision、checkpoint、执行模式和 lease；
3. 已接受 decision；
4. 节点、题目、回答、算法提交与评估任务状态；
5. 尚未完成的 projection/outbox task；
6. 组装 `WorkflowView`。

若 checkpoint 已有 accepted decision，恢复绝不重新调用 Root Agent。若没有 accepted decision 且安全检查点允许规划，才可通过幂等的 executor 重新申请一次决策。历史会话在迁移后使用 `LEGACY_STATIC`，不尝试重新编排。

### 18.2 不可恢复错误

以下情况必须停止自动推进、记录告警并给出受控人工恢复入口，而非继续猜测：

- workflow 指向不存在的节点或 decision JSON 无法读取；
- 已完成节点的题目事实与 accepted budget 不可调和；
- 数据迁移后关键外键/唯一约束损坏；
- 同一节点存在互相冲突的两个已接受 decision；
- 发现跨会话数据泄露或 Tool 范围异常。

人工恢复只允许选择“按静态定义补全未来节点”或“标记终止并保留审计”，不得手工改写既有题目、答案和评估事实。

## 19. 数据迁移、兼容与回滚

### 19.1 Flyway 原则

- 只新增迁移，不编辑已共享或已执行的迁移；
- 迁移版本必须晚于当前仓库的最新版本，并在预发升级库演练；
- 每张新表有外键、唯一约束、索引、状态 CHECK 和必要的审计时间；
- Hibernate 保持 `ddl-auto=validate`；不得依靠 JPA 自动补齐 schema；
- 迁移前备份实库，迁移后核验 Flyway、表、列、索引、约束与历史会话数量。

### 19.2 建议的迁移拆分

| 迁移主题 | 内容 |
| --- | --- |
| workflow runtime | `multi_round_workflow_instances`、`multi_round_workflow_events`、执行模式、revision、lease、checkpoint。 |
| dynamic nodes | 为 `multi_rounds` 增加 node 字段、origin、decision 引用和兼容索引。 |
| decision journal | `multi_round_workflow_decisions`、唯一 checkpoint key、输入哈希、策略版本、接受/拒绝状态。 |
| evidence/report | session 级学习证据、报告状态、投影版本与幂等键。 |
| audit extension | ADK runtime、session、模型、拒绝原因、降级原因、脱敏 Tool 轨迹。 |

### 19.3 历史数据策略

1. 已存在的 `multi_round_interviews` 创建对应 static workflow instance 与固定节点映射；
2. 已完成/进行中的历史会话标记 `LEGACY_STATIC`；
3. 保留原 `round_no` 和 `round_type` 以支持旧 API 和旧报告；
4. 不为历史会话调用 ADK，不补生成初始图，不替换题目；
5. 新会话默认从 `LEGACY` 或 `SHADOW` 开始，只有灰度条件满足才选择 `GOOGLE_ADK`。

### 19.4 回滚

数据库迁移采用加性演进，应用回滚只关闭 `GOOGLE_ADK` 入口并切换 `LEGACY`；已持久化的 workflow、decision、event 和 evidence 保留可读。不得通过删除新表或重写历史节点来回滚。

## 20. Provider、模型与成本控制

1. ADK Adapter 必须复用 `LlmProviderRegistry` 的 Provider、模型名、超时和密钥解密结果；业务代码不得另行读取 Gemini 或其他厂商密钥。
2. POC 需验证目标 ADK 版本能否稳定对接当前 OpenAI-compatible Provider。若不能，设计独立 `AdkProviderAdapter` 并通过安全评审，不能绕过配置中心。
3. Provider 在创建会话时固化 `providerId` 和无密钥 fingerprint；中途默认 Provider 变化不影响已有会话。
4. 每个 Agent、每次 run 和每场面试设置输入长度、输出长度、最大步骤、最大 Tool 调用数、超时、重试数和成本预算。
5. 达到日/会话成本上限时，自动切换为静态决策，并在审计中记录 `BUDGET_EXHAUSTED`。
6. `SHADOW` 采样比例必须可配置，并有独立成本上限，避免测试流量意外放大调用费用。

## 21. 安全、隐私与提示词防护

### 21.1 数据最小化

- 简历、JD、回答、代码和报告都是不可信输入；进入模型前经过截断、分隔符包裹与提示注入防护；
- Tool 返回摘要、分页片段和结构化统计，不默认返回完整正文；
- 日志、Trace API 和前端只展示安全摘要，禁止返回完整 Prompt、Tool 参数、密钥、完整简历或 Chain-of-Thought；
- 若未来引入多用户，所有 workflow、节点、决策、题目、证据和任务查询必须加 user/tenant 约束与复合索引。

### 21.2 输出防护

`rationaleSummary` 仅用于解释流程调整，长度受限，禁止包含：SQL、URL、请求头、文件路径、工具名、密钥形态、系统指令、状态迁移命令和未经脱敏的个人信息。出现违规模式时 Gate 拒绝并记录分类错误码。

### 21.3 权限

- 普通用户只能读取自己的 `WorkflowView`、报告和安全 Agent trace；
- runtime 切换、策略版本更新、重算、人工恢复和完整审计检索仅对管理员开放；
- 管理操作同样写入 append-only event，且不可通过前端页面任意切换 runtime。

## 22. 观测、告警与运营

### 22.1 指标

按 runtime、Provider、模型、Skill、节点类型和策略版本统计：

- workflow 创建/完成/暂停/恢复数量；
- ADK 成功率、超时率、Policy Gate 拒绝率、静态回退率；
- Shadow 与静态策略的节点类型/预算一致率；
- Agent/Tool 延迟、步骤数、输入输出长度与估算成本；
- 节点出题耗时、`READY` 停留时长、`EVALUATING` 堆积量；
- outbox 积压、任务重试、死信与投影延迟；
- 恢复成功率、重复决策拦截次数、revision 冲突次数；
- 报告生成时延、学习证据覆盖率。

### 22.2 告警建议

- ADK 超时或 fallback 比率持续超过阈值；
- `READY` 或 `EVALUATING` 节点超过允许时长；
- outbox backlog 或失败任务持续增长；
- Policy Gate 拒绝率突增；
- 跨会话 Tool 访问、审计完整性或 decision 唯一约束异常；
- 数据库死锁、乐观锁冲突和恢复失败超阈值。

### 22.3 审计关联

每条 workflow event、decision、agent run、Tool step、任务执行和报告投影应具备可关联的 `workflowId`、`nodeKey`、`checkpointKey`、`decisionId`、`taskId` 或 trace correlation id，方便从用户报告反查完整但脱敏的执行轨迹。

## 23. 前端细节与体验降级

1. 创建期显示“正在依据简历和目标生成面试路径”；超时后改为“正在使用稳定策略准备面试”，不要暴露底层异常堆栈。
2. 动态时间线只展示服务端已接受的节点；未来节点不展示具体题目、参考答案或内部 rationale。
3. 节点被替换/跳过时保留路径标记和安全原因摘要，例如“为避免重复考察，已合并到项目深挖”。
4. 当前节点题目准备中应禁用重复提交，但刷新后必须从服务端真实状态恢复，不依赖本地 loading 标记。
5. `STATIC_FALLBACK` 显示一次非阻断提示；不要求用户重建会话或重新作答。
6. 报告异步生成期间，显示已完成面试和报告生成状态；允许后续刷新查询，不能因报告未完成将面试伪装为失败。
7. 页面不以本地 `roundNo` 推断下一节点，也不拼接固定节点标签/颜色；所有展示由 node type、display order、status 和 server-provided title 驱动。

## 24. 测试矩阵

### 24.1 单元测试

- Static/ADK decision 的 JSON schema、预算、枚举、白名单、mutation 和 Policy Gate；
- 已开始节点不可变、HR 末尾、算法规则、节点排序、无环和 revision CAS；
- Tool 的 session 隔离、截断、分页、预算、审计脱敏；
- ADK timeout、Provider 缺失、Tool 失败、非法 JSON 和 fallback；
- workflow 事件去重、lease 暂停、恢复选择、幂等键和任务重放；
- 学习 evidence 的来源、置信度、重复投影与掌握度不被静默修改。

### 24.2 集成测试

- 空库与升级库 Flyway；
- 创建、出题、回答、评估、自动推进、最终报告全链路；
- 断点分别注入到 ADK 返回前、Gate 写入前后、题目保存前后、节点完成前后、Kafka 投递前后；
- 多线程重复提交、两实例恢复、Redis 锁失效、DB 唯一冲突和 CAS 冲突；
- `SHADOW` 不改真实节点，`STATIC_FALLBACK` 不再调用 ADK；
- Kafka 重投、outbox 延迟、任务幂等与报告补偿。

### 24.3 端到端测试

- 不同简历/JD 在灰度 ADK 模式下得到不同初始节点图；
- 低分算法评审后，后续技术节点难度或覆盖方向发生且仅发生在未来节点；
- 浏览器刷新、断网重连、关闭再打开后路径与题目一致；
- 用户没有手动推进入口，提交最后一题后自动进入下一节点或报告；
- Provider 关闭、超时或 Gate 拒绝时，用户可继续并完成静态流程；
- 前端安全 trace 不泄露 Prompt、简历、密钥、Tool 参数或推理过程。

## 25. 上线门槛与发布顺序

1. P1 静态动态化通过完整回归后，才允许引入 ADK 依赖；
2. P2 仅本地/测试环境验证 Adapter、Tool、Gate 和审计；
3. P3 `SHADOW` 先小比例采样，观察合法率、延迟、成本、回退率和人工抽样结果；
4. P4 仅白名单 Provider/Skill/用户进入 `GOOGLE_ADK` 初始图；
5. P5 在 ADK 初始图稳定后才开启 checkpoint mutation；
6. 每一步保留一键切换到 `LEGACY` 的配置，单场会话在触发 fallback 后固定为 `STATIC_FALLBACK`；
7. 上线后完成恢复演练、Provider 故障演练、Kafka 延迟演练和数据库升级回滚演练，再扩大灰度。

## 26. 实施前待确认决策

以下决策必须在编码前确定，并写入配置与测试，而不是留给 Prompt：

1. 初始节点数、总题数、每类节点最小/最大题数、总时长预算；
2. 是否强制算法节点、HR 节点的进入条件与最低技术覆盖规则；
3. 算法静态评审失败时是否允许继续、重试次数和用户提示；
4. 报告与学习证据是同步生成还是通过 Outbox 异步投影；
5. `GOOGLE_ADK` 灰度维度：Provider、Skill、用户白名单或比例；
6. 目标 ADK Java 版本与现有 Java/Spring/Provider Adapter 的 POC 兼容结果；
7. 是否需要租约自动暂停；若需要，租约时长、心跳时机和用户恢复提示；
8. 单用户模型何时演进为多用户/租户隔离，以及对应的数据迁移边界。

## 27. 实施记录

### 2026-09-22：P1 持久化静态工作流基础已完成

已实现并验证：

- `V20260923__add_multi_round_workflow_runtime.sql`：workflow instance、append-only event、节点元数据、执行模式及历史静态节点回填；
- `MultiRoundWorkflowInstanceEntity`、`MultiRoundWorkflowEventEntity` 与对应 Repository；
- `MultiRoundWorkflowService`：静态 definition 持久化、历史会话惰性回填、`NODE_STARTED` / `NODE_COMPLETED` / `NODE_READY` / `WORKFLOW_COMPLETED` 事件与 `WorkflowView`；
- 新建静态四轮时固化稳定 `nodeKey`、`nodeType`、`displayOrder` 与 `origin`；
- `GET /api/multi-round/interviews/{sessionId}/workflow`；
- 多轮前端读取 `WorkflowView` 决定当前节点，不再以 `currentRoundNo + 1` 推断；
- workflow 单测、创建/生命周期单测、实体-迁移长度漂移测试与前端生产构建。

随后已接入服务端自动推进：新建/恢复到首节点的会话会自动出题；普通题全部完成或算法题静态评审成功后，系统以稳定的内部幂等键自动完成当前节点、进入下一节点并准备题目。旧 `startRound` / `finishRound` / `nextRound` 接口暂时保留为兼容与恢复入口，前端移除前需补齐后台恢复调度与节点准备状态展示。

### 2026-09-22：P2 受控 Google ADK 初始图主路径已完成

本分支按已确认的目标取消 Shadow，默认直接走 `GOOGLE_ADK`，但保留单场会话级 `STATIC_FALLBACK`：

- 引入 Google ADK Java Adapter，运行固定的 `EvidenceAgent → StrategyAgent → RootAgent` 顺序链路；三个 Agent 均禁止横向/向父 Agent 转移；
- Tool 仅绑定已截断的内存上下文，不能访问 Repository、网络、其他 session 或写入领域状态，并有全链路 Tool 调用上限；
- Root 在首节点开始前输出 `InitialWorkflowDecision`；`WorkflowPolicyGate` 强制节点数、算法节点、HR 末位、题量、难度、coverage 和 key 约束；
- `V20260924__add_multi_round_workflow_decisions.sql` 持久化每一份接受/拒绝的提案（含 input hash、policy version、拒绝原因和 JSON），只有被接受的决策会替换未开始的静态节点；
- 模型超时、Provider 不兼容、结构化输出异常或 Gate 拒绝都会写入事件并固定为 `STATIC_FALLBACK`，随后正常自动出题，不把异常暴露给候选人；
- 生命周期的终止条件改为真实最后节点而不是固定 `HR`，因此动态图可安全地省略 HR 或使用任意合法技术节点组合；
- 已通过 ADK 依赖编译、全量后端测试、policy gate、schema drift、workflow 和 lifecycle 回归。

下一实施块是 checkpoint mutation：由同一 Gate 只修改 `PENDING` 节点，并为每次 mutation 追加独立决策与 revision 约束；已开始、已完成节点和历史审计一律不可改写。

### 2026-09-22：P3 checkpoint mutation 与最终学习建议投影已完成

- 每个节点完成后、下一个节点出题前，Root 可提出绑定当前 workflow revision 的未来节点补丁；只允许修改 `PENDING` 节点的题量、难度与能力覆盖，节点身份、顺序、类型和已开始节点绝不改变；
- mutation 复用 ADK 三 Agent 链路、Policy Gate、decision journal 与 workflow revision；模型错误、超时、版本冲突或 Gate 拒绝均保留当前未来图并继续自动推进；
- 最后节点完成后，Root 输出 `FinalLearningAdvice` 并持久化为 `multi_round_learning_advice` 投影；Provider/ADK 不可用时保存确定性建议；
- 建议投影不会直接更新 `learning_progress`、创建学习计划或安排提醒，学习域仍是唯一的掌握度和任务写入方；报告 API 返回建议来源与 JSON，支持刷新恢复；
- 已完成后端编译与聚焦回归；完整测试套件曾出现一次既有 `AsyncTaskPersistenceTest` 空指针，隔离重跑与受影响模块测试均通过，仍建议在 CI 中进一步处理该测试的隔离问题。
