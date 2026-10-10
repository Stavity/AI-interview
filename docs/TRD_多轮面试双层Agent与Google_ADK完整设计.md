# 多轮面试双层 Agent 与 Google ADK 完整设计

> 状态：目标设计，待评审冻结
> 版本：1.1
> 日期：2026-10-10
> 适用基线：[后续开发基线与阶段计划](后续开发基线与阶段计划.md)
> 决策级别：本功能后续数据库、后端、Agent、前端和测试实现的上位设计；与历史多轮实现冲突时，以本文和当前代码为准。

## 1. 结论与关键决策

多轮面试适合使用 Agent，但不适合让一个长生命周期 Agent 独占整个业务流程。目标架构采用两层 Agent：

1. `WorkflowRootAgent`：低频工作流编排 Agent。只在创建、轮次完成后的检查点和 HR 完成后的最终收口时运行，负责提出初始轮次计划、调整尚未开始的可选轮次、判断专业能力覆盖是否已足够。
2. `InterviewAgent`：高频轮次 Agent。复用同一份定义和代码，每个轮次使用全新的 ADK Session，只处理本轮出题、答案判断、有限追问和轮次总结。

这里的“复用单轮 Agent”是能力和代码复用，不是 Session、消息历史或可变内存复用。节点也不是 Agent：一个面试通常有 2～6 个动态节点，但同一时刻只由一个隔离的 `InterviewAgent` 执行当前节点。算法笔试由 JD 条件触发，`HR_FINAL` 是不可删除、不可替换、不可重排的末轮锚点。

最终权责边界如下：

| 能力 | Agent | Spring 应用 | PostgreSQL |
| --- | --- | --- | --- |
| 提出题目、追问和流程建议 | 候选输出 | 校验、裁决、执行 | 保存最终事实 |
| 状态机推进 | 不可直接推进 | 唯一推进者 | 真相源、版本与约束 |
| 权限与租户 | 不可信 | 从认证上下文强制注入 | 同 owner 约束 |
| 幂等与并发 | 不负责 | 幂等键、CAS、租约 | 唯一键、revision |
| 预算 | 提供使用量事件 | 预留、核销、熔断 | 持久化账本 |
| 学习证据 | 提供结构化评分材料 | 权威评估后写入 | 可追溯证据与进度 |
| 恢复 | 不保存业务真相 | 从数据库重建上下文 | 持久化检查点 |

本文明确不恢复旧多轮代码，也不把当前普通文本面试表强行扩展为动态工作流聚合根。新功能建立独立、规范化的多轮领域模型，复用当前已经验证的模型调用、标签、异步任务、缓存、学习证据和个性化路线能力。

## 2. 目标、范围与非目标

### 2.1 业务目标

- 候选人创建面试后，不再手工点击“开始本轮、结束本轮、进入下一轮”。系统自动准备当前轮次、接受回答、判断追问、完成权威评估并推进。
- 首轮计划由 JD 岗位需求画像、简历、目标岗位、主 Skill、面试偏好和确定性产品约束共同生成。
- 后续轮次可根据已经完成轮次的结构化报告调整，但不能回写历史事实。
- 每一轮必须独立审计、独立恢复、独立计费，并能证明上下文没有跨轮泄漏。
- 面试完成后形成总报告，并把可信知识标签和权威评分沉淀为学习证据，最终刷新个性化学习路线。

### 2.2 首版范围

- 文字多轮面试。
- 单场通常为 2～6 个轮次节点，具体数量由岗位覆盖需求、级别、用户时长和预算共同确定。
- 主 Skill 在创建时冻结，整场不变。
- 算法笔试不是固定轮次：JD 明确需要时加入，明确不需要时跳过；需要时使用岗位相关、难度为 Medium 的标准题池，首版不允许 Root Agent 任意改变该约束。
- 至少保留一个核心专业能力轮；技术深入、项目、系统设计和管理能力轮按需求浮动。
- `HR_FINAL` 必须存在且永远是最后一轮；动态调整和预算收缩都不能删除、替换或跨过该轮。
- 自动流转、暂停/恢复、幂等提交、SSE 进度、静态降级。
- Google ADK Java 作为 Agent 运行框架，具体依赖版本经 `G0` POC 后锁定。

### 2.3 非目标

- 不在首版实现语音多轮、多人面试官协作、实时视频、情绪识别或第三方招聘系统写回。
- 不允许 Agent 自主增删数据库记录、直接调用任意业务写接口或读取其他用户数据。
- 不将 ADK SessionService、Redis 或模型对话历史作为业务真相源。
- 不允许无限追问、无限工具循环、面试中途更换主 Skill，或在面试开始后修改已经开始/完成的轮次。
- 不把账号运营阶段 F 混入本阶段。

## 3. 当前代码事实与接轨原则

当前代码已经具备以下可复用基础：

- `AsyncTaskSubmissionService`、Outbox、Kafka、租约、心跳、超时与重试构成统一异步任务底座。
- 普通文字面试使用 `InterviewEvaluateTaskHandler` 在事务外完成模型评估，再由 `InterviewPersistenceService` 短事务保存报告。
- `LearningEvidenceIngestionService` 已实现文本/语音证据的幂等写入、知识进度重算和路线刷新。
- `InterviewSessionCache` 已采用数据库为准、Redis cache-aside、缓存失败不阻塞业务的模式。
- `VoiceContextCompressor` 已有窗口与摘要思路，可作为上下文压缩原则参考。
- `QuestionKnowledgeTagToolCallbackFactory` 已提供标签工具范围限制、调用次数和输出长度控制。
- `StructuredOutputInvoker` 已提供结构化输出校验、有限修复和指标记录。
- `LlmProviderRegistry` 已支持租户自有或系统内置的 OpenAI 兼容 Provider。
- `TenantContext` 和异步 owner 恢复机制已存在。

接轨时遵循四条原则：

1. 复用能力，不复用错误的聚合边界。普通 `interview_sessions` 假设题目集合预先冻结且只有一份报告，不适合作为动态多轮聚合根。
2. 不另造异步平台、缓存真相源或模型密钥体系。
3. 先抽取稳定接口，再让现有单轮与多轮共同调用，避免多轮模块复制整套实现。
4. 数据库迁移只通过 Flyway；新表全部带 `owner_user_id` 和同 owner 外键。

## 4. 术语与不变量

### 4.1 术语

- 多轮面试 `MultiRoundInterview`：用户可见的一场完整面试，也是业务聚合根。
- 工作流 `Workflow`：该面试当前有效的执行图、revision、运行模式和检查点。
- 轮次节点 `RoundNode`：一个具有目标、类型、预算和状态的执行单元；不是 Agent 实例。
- 轮次运行 `RoundRun`：某节点的一次实际执行尝试，失败恢复会增加 attempt。
- Agent 运行 `AgentRun`：一次 Root 或 Interview Agent invocation。
- 轮次报告 `RoundReport`：权威评估后形成的、可跨轮传递的结构化摘要。
- 候选决策 `CandidateDecision`：Agent 输出、尚未通过策略门禁的建议。
- 策略门禁 `WorkflowPolicyGate`：确定性校验、裁决、修正或拒绝 Agent 建议的服务。
- 检查点 `Checkpoint`：节点完成后，Root Agent 可以调整未来节点的唯一时刻。

### 4.2 强制不变量

1. PostgreSQL 是唯一业务真相源。
2. 每个写命令都必须从服务端认证上下文取得 owner，客户端和工具参数不得指定任意 owner。
3. 同一时刻一场面试最多一个 `IN_PROGRESS` 或 `EVALUATING` 节点。
4. 已进入 `IN_PROGRESS` 的节点，其题型、目标和预算冻结；`COMPLETED` 节点不可修改。
5. Root Agent 只可增删、替换或重排未来 `PENDING` 可选节点，且总节点数通常保持在 2～6；核心专业能力轮和 `HR_FINAL` 锚点不在可变更集合中。
6. 同一题的正式答案只接受一次；修改答案必须是显式新版本，而不是覆盖历史。
7. 每次状态变更必须包含预期 revision；CAS 失败只重读，不盲目重试写入。
8. 每轮使用独立 Session；不同轮次、不同用户、Root 与 Interview Agent 均不得共享可变 Session。
9. 所有 Agent 输出在落库前经过 JSON Schema、语义规则、预算规则和权限范围校验。
10. 超预算、模型失败或 ADK 故障不能产生半完成状态；必须重试、暂停或进入静态降级。

## 5. 总体架构

```text
前端
  │ REST 命令 + SSE 状态
  ▼
MultiRoundInterviewController
  │ owner / idempotency / validation
  ▼
MultiRoundApplicationService ───────────────► WorkflowViewCache (Redis，仅视图)
  │
  ├─► WorkflowStateMachine / WorkflowPolicyGate / BudgetGovernor
  │        │
  │        └─► PostgreSQL（聚合、节点、答案、决策、预算、事件）
  │
  ├─► AdkAgentGateway
  │      ├─► WorkflowRootAgent（独立 Root Session）
  │      └─► InterviewAgent（每轮全新 Session）
  │             └─► 只读、服务端定域工具
  │
  └─► 现有 AsyncTask + Outbox + Kafka
          ├─► MultiRoundRoundEvaluateTaskHandler
          └─► MultiRoundFinalizeTaskHandler
                    │
                    └─► 学习证据 → 知识进度 → 路线一次性刷新
```

ADK 的 `Runner`、Session 和事件循环只负责一次 Agent invocation 的运行与事件采集。业务服务在调用前构造白名单上下文，调用后只接收结构化候选输出。ADK 事件可存入独立审计表，但不能反向覆盖领域事实。

## 6. 双层 Agent 设计

### 6.1 JD 驱动的可变轮次规划

轮次规划分成两个步骤，不能让 Root Agent 直接阅读一段 JD 后自由决定全部流程：

```text
JD 原文
  ↓ 结构化提取 + 规则校验
JobRequirementProfile（冻结、可解释、带置信度）
  ↓ 硬约束模板生成器
WorkflowConstraints（必选锚点、可选轮次、禁止轮次、数量/时长范围）
  ↓
WorkflowRootAgent 提出候选顺序与侧重点
  ↓
WorkflowPolicyGate 校验并物化最终计划
```

#### 6.1.1 JobRequirementProfile

创建多轮面试前，先把 JD 转换为版本化、可审计的岗位需求画像：

```json
{
  "roleFamily": "BACKEND_ENGINEER",
  "seniority": "MID",
  "coreSkillCodes": ["java", "spring", "database"],
  "algorithmRequirement": "PREFERRED",
  "algorithmConfidence": 0.84,
  "systemDesignRequirement": "REQUIRED",
  "projectDepthRequirement": "REQUIRED",
  "leadershipRequirement": "NOT_REQUIRED",
  "reasonCodes": ["JD_MENTIONS_DATA_STRUCTURES", "ROLE_POLICY_BACKEND_MID"],
  "evidenceSpans": [
    {"field": "algorithmRequirement", "start": 126, "end": 151, "contentHash": "sha256:..."}
  ],
  "extractorVersion": 1,
  "policyVersion": 1
}
```

JD 原文可以由结构化模型提取，但枚举、证据位置、岗位规则和置信度必须由服务端校验。画像随面试冻结；JD 后续修改不改变已创建面试。

`algorithmRequirement` 使用四态而不是布尔值：

| 判定 | 含义 | 独立算法笔试轮 |
| --- | --- | --- |
| `REQUIRED` | JD 明确要求算法、数据结构、在线编程、复杂度分析，或岗位规则强制要求 | 必须加入 |
| `PREFERRED` | JD 把问题求解/编码能力列为加分项，或岗位规则建议但非硬要求 | 时长与预算满足时加入 |
| `NOT_REQUIRED` | JD/岗位明确以业务、产品、实施等能力为主，没有独立算法考察必要 | 禁止加入 |
| `UNKNOWN` | JD 缺失、内容不足或证据冲突 | 默认不加入；可在核心专业轮内做轻量问题求解，不创建独立笔试轮 |

判定优先级为：JD 明确硬要求/明确排除 > 已维护的岗位族策略 > 模型推断。模型不得只因为岗位名称含“开发工程师”就把算法提升为 `REQUIRED`。

#### 6.1.2 轮次类型与锚点

| 轮次类型 | 约束 | 主要用途 |
| --- | --- | --- |
| `ALGORITHM_WRITTEN` | 条件轮；存在时通常排在第一轮 | 代码、数据结构、复杂度和测试用例 |
| `TECHNICAL_CORE` | 必选锚点，至少 1 轮 | JD 核心专业能力 |
| `TECHNICAL_DEEP_DIVE` | 可选，最多 2 轮 | 对关键能力加深或补证据 |
| `PROJECT_DEEP_DIVE` | JD 要求项目经验或简历有可验证项目时加入 | 设计决策、职责与复盘 |
| `SYSTEM_DESIGN` | JD/级别要求架构能力时加入 | 约束分析、方案与权衡 |
| `LEADERSHIP` | 管理/负责人岗位条件加入 | 协作、决策、团队影响力 |
| `HR_FINAL` | 必选且唯一，永远最后 | 动机、沟通、职业期望、风险和双向匹配 |

`TECHNICAL_CORE` 与 `HR_FINAL` 是锚点。前者保证专业评价不被动态压缩掉，后者保证流程完整收口。`HR_FINAL` 不产生技术知识点学习证据，也不参与技术掌握度聚合；其结果只进入总报告的 HR/匹配度维度。

#### 6.1.3 轮次数量规则

系统先根据可用时长生成容量档位，再在容量内覆盖 JD：

| 可用时长 | 建议轮次 | 典型结构 |
| --- | ---: | --- |
| 30～45 分钟 | 2 轮 | 核心专业 → HR |
| 45～75 分钟 | 3 轮 | 核心专业 → 项目/系统设计 → HR；算法 `REQUIRED` 时改为算法 → 核心专业 → HR，或提升容量保留其他必选维度 |
| 75～120 分钟 | 4～5 轮 | 算法（条件）→ 核心专业 → 深入/项目/系统设计 → HR |
| 120 分钟以上 | 最多 6 轮 | 在覆盖必要维度后增加一次技术深入或领导力轮，禁止为凑数量重复考察 |

硬规则：

1. 首版最少 2 轮、最多 6 轮；取消中的面试除外。
2. 第一轮不得是 HR；末轮必须且只能是 `HR_FINAL`。
3. `ALGORITHM_WRITTEN=REQUIRED` 时最低为 3 轮（算法笔试、核心专业、HR）；即使用户选择的时间偏短也不能静默删除，应提示并把时长提升到最低可执行档位，用户不接受时不启动该方案。
4. `ALGORITHM_WRITTEN=NOT_REQUIRED/UNKNOWN` 时，Root 不得自行新增独立算法轮。
5. 同一类型的 `TECHNICAL_DEEP_DIVE` 最多两轮，并且目标代码不能完全相同。
6. 轮次增加必须带来新的 JD 覆盖目标；只因候选人表现好或差不能无限加轮。
7. 预算收缩时按“额外技术深入 → 项目/系统设计中的非硬要求项 → `PREFERRED` 算法轮”顺序裁剪，锚点与 `REQUIRED` 轮不可裁剪。

#### 6.1.4 典型方案

```text
JD 不要求算法，45 分钟：
TECHNICAL_CORE → HR_FINAL

JD 不要求算法，75 分钟，强调项目：
TECHNICAL_CORE → PROJECT_DEEP_DIVE → HR_FINAL

JD 要求算法，90 分钟：
ALGORITHM_WRITTEN → TECHNICAL_CORE → PROJECT_DEEP_DIVE → HR_FINAL

高级后端，JD 要求架构但不要求算法，120 分钟：
TECHNICAL_CORE → SYSTEM_DESIGN → PROJECT_DEEP_DIVE → TECHNICAL_DEEP_DIVE → HR_FINAL

算法/平台岗位，120 分钟：
ALGORITHM_WRITTEN → TECHNICAL_CORE → TECHNICAL_DEEP_DIVE → SYSTEM_DESIGN → HR_FINAL
```

#### 6.1.5 动态调整边界

- Root 可以替换、合并、跳过或细化尚未开始的可选轮次。
- `REQUIRED` 算法轮一旦计划生成即成为条件锚点；除非用户取消或系统明确转入“不完整面试”终态，否则不能跳过。
- `PREFERRED` 算法轮可在尚未开始时因预算/时长不足被策略门禁裁剪，必须记录 `SKIPPED_BUDGET_POLICY`。
- `HR_FINAL` 始终保持最大 sequence。插入新轮次只能发生在 HR 之前，且必须重排未来节点后再次验证 HR 为末轮。
- Root 不能根据技术表现跳过 HR，也不能把 HR 提前用于节省预算。
- 如果前序轮次已足够覆盖某项能力，可缩短或跳过对应可选轮；如果证据不足，只能在总上限内增加/替换可选轮。

### 6.2 WorkflowRootAgent

触发频率：

- 创建时一次：提出初始工作流。
- 每个轮次完成并生成权威 `RoundReport` 后至多一次：提出未来节点调整。
- 最后一个节点完成后一次：提出总报告的叙事结构和收口建议。

输入只包含：

- 冻结后的 JD `JobRequirementProfile`、岗位、主 Skill、年限档位和偏好。
- 简历的脱敏结构化摘要，不包含原文件、联系方式或无关个人信息。
- 产品策略：锚点、节点类型白/黑名单、节点数、算法轮判定、HR 末轮约束、总时长和剩余预算。
- 已完成节点的 `RoundReport` 列表。
- 未来 `PENDING` 节点的当前计划。
- 当前 workflow revision 和 checkpoint 序号。

禁止输入原始跨轮聊天记录。禁止工具直接写库。

输出契约：

```json
{
  "decisionId": "uuid",
  "expectedWorkflowRevision": 3,
  "reasonCodes": ["WEAK_CONCURRENCY", "COVERAGE_GAP"],
  "operations": [
    {
      "type": "REPLACE_PENDING_NODE",
      "targetNodeKey": "tech-2",
      "newNode": {
        "nodeType": "TECHNICAL_DEEP_DIVE",
        "goalCodes": ["java.concurrency"],
        "difficulty": "MEDIUM",
        "questionBudget": 3,
        "timeBudgetSeconds": 900
      }
    }
  ],
  "optionalCoverageComplete": false,
  "rationale": "..."
}
```

Agent 不得输出 SQL、类名、任意 Prompt 或自由形式工具链。`reasonCodes`、操作类型、节点类型、难度和目标代码均使用枚举或当前目录中的合法标识。

Root 输出的每个节点还必须带 `requirementSource`（JD、岗位策略、用户时长或前轮证据）和 `mandatoryAnchor`。服务端重新计算这两个字段，不信任模型提交值。任何缺少唯一 `HR_FINAL`、把 HR 放在非末位、违反算法判定或缺少 `TECHNICAL_CORE` 的计划直接拒绝并使用确定性计划生成器修复。`optionalCoverageComplete` 只表示可以裁剪剩余可选专业轮，绝不表示可以跳过 HR 或直接完成整场。

### 6.3 InterviewAgent

每个轮次创建全新 Session，Session key 至少包含不可猜测的 interview public id、node public id、attempt 和随机 nonce。Session 完成后标记封存，禁止供下一轮继续运行。

职责：

- 根据轮次目标和题目预算生成首题。
- 针对当前回答做低延迟结构化判断。
- 在预算允许时提出追问或下一题。
- 满足完成条件时输出轮次完成候选和结构化摘要草稿。

同一 `InterviewAgent` 根据冻结的 `roundMode` 使用不同策略，不创建多套有状态 Agent：

| roundMode | 适用节点 | 交互规则 |
| --- | --- | --- |
| `DIALOGUE` | 技术、项目、系统设计、领导力 | 问答为主，允许有限追问 |
| `WRITTEN_CODE` | 算法笔试 | 标准题库取题、代码一次或显式版本化提交，原则上不做引导性追问 |
| `HR_DIALOGUE` | HR 末轮 | 动机、协作、期望和匹配度；不查询技术题库、不生成技术学习证据 |

算法笔试首版规则：

- 每轮 1～2 道岗位相关的 Medium 标准题，题目和测试用例版本冻结，不由 Agent 临场自由创作。
- Agent 可以生成不泄露解法的澄清回复，但不得提示关键算法、逐步纠错或因答错而换一道更简单的题。
- 代码作为不可变提交保存；允许用户显式提交新版本时，保留全部版本并以最后一次按时提交作为正式评分输入。
- 若执行代码，必须进入独立受限沙箱，限制 CPU、内存、墙钟时间、进程数、文件和网络；严禁在 Spring 应用 JVM、AsyncTask worker 或宿主机直接运行不可信代码。
- 权威评分组合编译/测试结果、复杂度和代码质量；模型不能覆盖确定性测试事实。
- 若首版尚未具备安全代码沙箱，`ALGORITHM_WRITTEN` 只能以代码文本、样例推演和复杂度说明完成，并在报告中标记 `CODE_NOT_EXECUTED`，不得伪装成测试已通过。

输入只包含：

- 当前轮次冻结配置。
- 当前轮内已确认的问题、答案和短摘要。
- 允许的知识标签范围与题目去重指纹。
- 从历史轮次提取的最小 `HandoffContext`，不含原始文本。
- 当前轮和全局剩余预算。

推荐动作契约：

```json
{
  "action": "ASK_FOLLOW_UP",
  "question": {
    "content": "...",
    "questionType": "TECHNICAL",
    "difficulty": "MEDIUM",
    "primaryTagCode": "java.concurrency",
    "secondaryTagCodes": [],
    "expectedEvidence": ["..."],
    "dedupFingerprint": "sha256:..."
  },
  "assessment": {
    "coverage": 0.68,
    "confidence": 0.81,
    "observedSignals": ["..."],
    "missingSignals": ["..."]
  },
  "reasonCode": "CLARIFY_MISSING_TRADEOFF"
}
```

允许的 `action` 只有 `ASK_FOLLOW_UP`、`ASK_NEXT_QUESTION`、`COMPLETE_ROUND`。是否执行仍由服务端依据问题数、追问数、时长、去重、标签合法性和预算裁决。

### 6.4 为什么不采用一个长生命周期 Agent

- 整场原始历史会使 token 随轮次无界增长。
- Session 混用会导致上一轮 Prompt、工具结果和候选人回答泄漏到下一轮。
- 长 invocation 中的失败难以从确定性检查点恢复。
- 一个 Agent 同时承担流程、出题、评分和落库，无法清晰审计责任。
- 独立 Session 可按轮计费、限流、重放和降级，风险边界更小。

### 6.5 为什么不保留三个串行 Agent

历史设计曾拆分证据、策略和 Root 三个 Agent。当前目标将确定性证据提取、Schema 校验和策略门禁收回 Spring，把模型角色收敛为 Root 与 Interview 两层。这样减少跨 Agent 传输、模型调用次数和结果漂移，同时保留工作流决策与轮内交互的必要隔离。

## 7. 完整业务流程

### 7.1 创建与初始化

1. 前端提交创建命令和 `Idempotency-Key`，内容包括 JD/JD 引用、简历、目标岗位、主 Skill、期望时长和可选偏好。
2. 服务端从 `TenantContext` 取得 owner，校验 JD、简历归属、Skill 和目录版本。
3. 在受控提取与规则校验后生成 `JobRequirementProfile`，冻结算法、系统设计、项目深度和领导力要求；无法确认算法要求时采用 `UNKNOWN`，不自动增加算法笔试。
4. 短事务创建 `MultiRoundInterview(INITIALIZING)`、JD 画像快照、预算账本、工作流 revision 0 和创建事件。
5. 硬约束模板生成器先计算锚点、允许/禁止轮次和 2～6 轮容量；服务端再构造脱敏、定长的 Root 输入并预留预算。
6. 事务外运行 `WorkflowRootAgent`；成功后核销实际使用量。
7. `WorkflowPolicyGate` 校验节点数、类型、算法判定、HR 末轮、时长、覆盖范围和总预算。非法建议可做确定性修正；不可修正则使用与 JD 画像匹配的静态模板。
8. 短事务以 revision CAS 物化节点，将第一个节点置为 `READY`，面试置为 `RUNNING`，写入决策和事件。
9. 自动准备首题；前端通过 SSE 收到 `ROUND_READY` 或轮询恢复。

创建请求超时不代表创建失败。客户端必须用同一幂等键查询结果，不能生成第二场面试。

### 7.2 开始轮次与生成问题

1. 协调器取得当前 `READY` 节点并创建 `RoundRun`。
2. 数据库 CAS 将节点变为 `IN_PROGRESS`，冻结节点配置、标签目录版本和预算。
3. 创建本轮独立 ADK Session。
4. 构造 `RoundContextEnvelope`，运行 `InterviewAgent` 生成首题。
5. 标签工具只可在冻结 Skill 子树和当前目录版本内查询；问题通过标签合法性、敏感内容、重复度和长度检查后落库。
6. 前端收到问题。没有用户“开始本轮”按钮；节点准备完成即进入答题态。

### 7.3 提交回答与有限追问

1. 客户端提交 `answerId`、问题 public id、答案和 `Idempotency-Key`。
2. 服务端校验 owner、问题为当前问题、未正式作答、答案长度和 workflow revision。
3. 短事务追加不可变答案，更新活动时间，不调用模型。
4. 事务外运行一次受预算控制的快速判断。该判断只决定交互动作，不作为学习证据的权威评分。
5. 策略门禁根据本题追问上限、本轮问题上限、剩余时长、重复度和预算执行以下之一：
   - 接受追问并保存下一问题；
   - 忽略追问建议，生成下一主问题；
   - 结束本轮。
6. 每题最多 2 次追问；每轮默认最多 5 个主问题、总问题事件最多 9 个，均可按节点类型配置但不能超过系统硬上限。

重复提交相同幂等键返回原结果；不同幂等键重复回答同一不可重复题返回冲突，不触发第二次 Agent 调用。

#### 7.3.1 算法笔试分支

`WRITTEN_CODE` 不走普通“回答后让 Agent 决定追问”的循环：

1. 服务端从标准题库选定并冻结题目、语言范围、样例、隐藏测试版本和截止时间。
2. 用户可保存草稿，草稿不进入评估；正式提交需要独立幂等键并生成不可变 submission version。
3. 有安全沙箱时提交 `MULTIROUND_CODE_JUDGE`，先产生确定性编译/测试/资源使用结果；没有沙箱时明确标记未执行。
4. 代码评测完成后，权威轮次评估结合测试事实、代码、复杂度说明和岗位目标生成结构化报告。
5. Root 只接收算法轮报告中的通过率、复杂度结论、能力/差距代码和置信度，不接收完整代码。

算法题超时后保留最后一次按时正式提交；没有正式提交则记录 `NO_SUBMISSION`，仍进入权威评估和后续 HR，不让流程永久卡住。

### 7.4 轮次结束与异步权威评估

1. 当完成条件满足，CAS 将节点从 `IN_PROGRESS` 变为 `EVALUATING`，封存本轮 ADK Session。
2. 提交 `MULTIROUND_ROUND_EVALUATE` 异步任务，业务 id 为 round run id，active key 保证同轮只有一个活动任务。
3. Handler 在事务中读取并生成不可变快照：问题、答案、冻结标签、节点目标、评估规则版本和内容指纹。
4. 事务外完成权威结构化评估；模型调用失败由统一异步任务执行重试。
5. 完成回调在短事务中保存题级评估、`RoundReport`、评估版本和 token 使用量，并以 execution version 保证重复消费不重复写入。
6. 学习证据按“答案/评估/冻结标签”幂等写入，知识进度在本轮完成后重算。
7. 节点 CAS 变为 `COMPLETED`，产生 `ROUND_COMPLETED` 事件。

在权威评估完成前不得进入下一个节点，以免 Root 使用快速判断代替正式事实。

### 7.5 Root 检查点与下一轮

1. 协调器读取新 `RoundReport`、已完成报告摘要、未来节点和剩余预算。
2. 若动态调整已禁用、预算不足或 Root 已熔断，直接使用当前未来节点。
3. 否则预留一次 Root checkpoint 预算并调用 `WorkflowRootAgent`。
4. 策略门禁只接受针对 `PENDING` 节点的白名单操作，并检查 expected revision。
5. 事务内记录原始候选输出摘要、校验结果、最终执行操作与 reason code；更新 workflow revision。
6. 将下一个节点置为 `READY`，自动开始准备。

### 7.6 整场收口

1. 唯一 `HR_FINAL` 节点完成后，面试进入 `FINALIZING`；普通完成路径中 HR 之前不得收口。
2. 提交 `MULTIROUND_FINALIZE` 异步任务，汇总已持久化的各轮报告，不重新读取原始全量对话给 Root。
3. 确定性服务先计算覆盖度、题量、标签掌握和总分，再由模型仅生成解释性叙事；模型不能修改分数。
4. 短事务保存总报告，将面试置为 `COMPLETED`。
5. 本场已写入的学习证据已用于知识进度；此时只触发一次 `PersonalizedLearningPathService` 路线刷新，触发原因为 `MULTI_ROUND_COMPLETED`。
6. 发布完成事件并更新缓存，前端展示总报告和路线变化入口。

### 7.7 暂停、恢复与重新进入页面

- 用户可暂停 `IN_PROGRESS` 节点；暂停只冻结交互，不取消已提交的权威评估。
- 页面刷新后从 `GET /api/multi-round-interviews/{id}` 返回的 WorkflowView 恢复，不依赖浏览器内存或 ADK Session。
- 若当前节点为 `IN_PROGRESS`，服务端从数据库中的轮内摘要和最近 N 个问答重建一个新 invocation；不需要恢复旧网络流。
- 若 Agent 调用中断且没有候选输出落库，释放或等待租约到期后可用相同 run key 重试。
- 恢复时发现预算账本与 Agent 事件不一致，按“预留仍有效则继续、过期则释放”的规则修复，绝不重复核销。

## 8. 状态机

### 8.1 面试状态

```text
INITIALIZING ──► RUNNING ◄──► PAUSED
      │             │
      │             ├──► FINALIZING ──► COMPLETED
      │             │          │
      └─────────────┴──► STATIC_FALLBACK
                               │
                               └──► RUNNING / FINALIZING

任意非终态 ──► CANCELLED
不可自动恢复错误 ──► FAILED
```

- `STATIC_FALLBACK` 是运行模式标记与可观察状态，不表示丢失现有进度。
- 一旦同一面试因兼容性、预算或重复 Agent 故障进入静态降级，首版不自动切回动态模式；需要显式恢复命令和审计记录。

### 8.2 节点状态

```text
PENDING ──► READY ──► IN_PROGRESS ──► EVALUATING ──► COMPLETED
   │          │              │               │
   ├──► SKIPPED              └──► PAUSED     └──► FAILED_RECOVERABLE
   └──► REPLACED
```

- 只有 `PENDING` 可 `SKIPPED` 或 `REPLACED`。
- `FAILED_RECOVERABLE` 必须保留冻结快照和 attempt，可恢复到 `EVALUATING`，不能回到可编辑的 `PENDING`。

### 8.3 问题状态

```text
PREPARED ──► ASKED ──► ANSWERED ──► EVALUATED
    │           │
    └──► REJECTED_BY_POLICY
                └──► EXPIRED（仅未回答且节点关闭）
```

状态迁移表在代码中采用显式枚举和允许边集合，不使用散落的字符串判断。

## 9. 上下文隔离设计

### 9.1 隔离层级

| 层级 | 隔离键 | 可见内容 | 生命周期 |
| --- | --- | --- | --- |
| 用户 | `owner_user_id` | 用户自己的资源 | 账号生命周期 |
| 面试 | `interview_public_id` | 冻结画像、总预算 | 整场 |
| Root | `root_session_id + workflow_revision` | 结构化报告和未来节点 | 单次 invocation |
| 轮次 | `node_public_id + attempt` | 本轮配置与摘要 | 单轮 |
| 调用 | `agent_run_id` | 本次输入、临时工具结果 | 单 invocation |
| 工具 | server-side scope token | 当前允许的数据切片 | 单工具调用 |

### 9.2 ADK Session 规则

- Root 和 Interview 使用不同的 session namespace。
- 每轮新建 Session；严禁把上一轮 session id 传给下一轮。
- Session state 只保存运行便利信息。即使使用 ADK 的 `temp:` invocation state，也只用于本次调用，业务恢复仍从数据库构造。
- 若采用持久化 SessionService，其表与领域表分离，并设置短保留期；删除 Session 不影响业务恢复。
- 不启用跨用户或跨面试的 ADK memory 自动检索。需要的简历与学习信息由受控服务显式装配。
- Prompt 中的所有用户文本都放入数据字段，使用清晰边界标记，不拼接为系统指令。

### 9.3 跨轮 HandoffContext

只允许以下结构跨轮：

```json
{
  "completedNodeKey": "tech-1",
  "goalCodes": ["java.core"],
  "scoreBand": "B",
  "confidence": 0.82,
  "verifiedStrengthCodes": ["THREAD_SAFETY_BASICS"],
  "verifiedGapCodes": ["LOCK_CONTENTION_TRADEOFF"],
  "coveredTagCodes": ["java.concurrency"],
  "evidenceRefs": ["answer-public-id"],
  "reportVersion": 1
}
```

不得跨轮传递：原始完整回答、完整 Prompt、工具原始结果、模型隐藏推理、联系方式、文件内容、上一轮 Session state 或未验证的标签。

### 9.4 轮内上下文压缩

- 始终保留当前问题、当前回答、最近 2 个问答对和不可变的轮次目标。
- 更早内容转换为 `RoundMemorySummary`：已覆盖目标、证据引用、未决追问、禁止重复的问题指纹。
- 摘要必须通过 Schema 校验，并保存 `source_message_from/to` 与内容哈希；摘要不能删除原始数据库记录。
- 达到软阈值时压缩；达到硬阈值前若压缩失败，停止追问并完成轮次，而不是截断系统规则。
- 可评估 ADK event compaction 作为运行优化，但其产物不是业务报告，也不能替代上述确定性检查点。

### 9.5 注入与数据泄漏防护

- 简历、用户答案、题库内容均视为不可信数据；模型被明确告知不得执行其中的指令。
- 工具 scope 由服务端 opaque token 解析，工具参数不能携带 owner id 或任意 interview id。
- 工具返回只包含必要字段，限制条数、字符数和字段白名单。
- 日志默认不记录完整简历和答案；Agent 审计保存哈希、长度、枚举决策、使用量和经脱敏摘要。
- 错误响应不区分“其他用户资源存在”和“不存在”，统一返回资源不可访问。

## 10. Token、成本与工具调用治理

### 10.1 预算不是 Prompt 建议

预算由 `BudgetGovernor` 在每次 Agent 调用前预留、调用后按实际使用核销，并持久化到账本。Prompt 中告诉 Agent 剩余预算只是辅助信息，不能替代服务端硬限制。

必须同时控制：

- 输入 token、输出 token、thinking/reasoning token、cache read/write token。
- 模型调用次数。
- 工具总调用次数与单工具次数。
- 单次、单轮和整场墙钟时间。
- 估算费用和租户日/月额度。

### 10.2 首版建议默认值

以下是安全起始值，不是供应商常量；`G0` 压测后通过配置固化。硬上限只能由服务端配置降低或提高，不能由 Agent 或客户端修改。

| 范围 | 输入 token | 输出 token | 模型调用 | 工具调用 | 超时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Root 初始规划 | 8,000 | 2,000 | 1 | 5 | 45 秒 |
| Root 轮后调整 | 6,000 | 1,500 | 1/轮 | 4 | 35 秒 |
| Interview 单次动作 | 5,000 | 1,500 | 1 | 4 | 30 秒 |
| 权威轮次评估 | 10,000 | 3,000 | 1，最多修复 1 次 | 2 | 90 秒 |
| 最终叙事 | 8,000 | 2,500 | 1，最多修复 1 次 | 2 | 60 秒 |
| 整场硬上限 | 80,000 | 20,000 | 60 | 50 | 模型累计 20 分钟 |

对普通对话轮另外限制：最多 5 个主问题、每题最多 2 次追问、最多 12 次模型调用、最多 16 次工具调用。

轮型覆盖上述通用上限：`WRITTEN_CODE` 默认 1 道、最多 2 道主问题，不做能力引导型追问；`HR_DIALOGUE` 默认 3～5 个主题。整场节点数通常为 2～6。创建时必须为 `HR_FINAL` 预留最低执行额度，该额度不能被算法、技术追问或 Root 动态调整借用。

### 10.3 预算分配

创建时按整场预算预留以下比例：

- Root 初始规划与检查点：15%。
- 轮内 Interview Agent：60%。
- 权威轮次评估：20%。
- 最终叙事和修复：5%。

未使用额度可回收到全局池，但评估保留额度不得被轮内追问占用。开始一个节点前必须保证该节点的最低执行额度与最终收口额度仍可用，否则缩短未来节点或进入静态模板。

### 10.4 预估、预留和核销算法

1. 使用与实际模型匹配的 tokenizer；无可靠 tokenizer 时采用字符/语言启发式并乘 1.25 安全系数。
2. `reserve = estimatedInput + maxOutput + maxThinking + toolResultAllowance`。
3. 数据库原子更新 `remaining >= reserve` 才允许创建 AgentRun。
4. 收到 ADK 使用量事件后，按输入、输出、思考和缓存 token 分类核销；未知字段归入 `unclassified_tokens`，不得按 0 处理。
5. 调用失败但供应商已计费时照常核销；只有确认未发出请求才全额释放。
6. 租约超时的预留由恢复任务对照 provider request id 和 AgentRun 状态释放或人工审计。

### 10.5 水位策略

- 低于总预算 20%：进入节约模式，禁止非必要 Root 调整，减少追问，优先完成当前目标。
- 低于 10%：禁止新的动态节点和叙事性工具调用，只保留权威评估和最终确定性报告。
- 预测下一步会越过硬上限：调用前拒绝，不依赖供应商截断。
- 任一调用达到输出上限且结构不完整：最多一次结构修复；修复额度必须预先保留。

### 10.6 工具调用控制

每个工具声明：允许 Agent、输入 Schema、scope、单次超时、最大结果大小、每 invocation 次数、是否可缓存。

首版工具白名单建议：

| 工具 | Root | Interview | 性质 |
| --- | --- | --- | --- |
| `get_resume_profile` | 允许 | 不允许 | 只读脱敏摘要 |
| `get_completed_round_reports` | 允许 | 不允许 | 只读结构化报告 |
| `get_pending_workflow` | 允许 | 不允许 | 只读未来节点 |
| `search_allowed_knowledge_tags` | 可选 | 允许 | 当前 Skill 定域 |
| `get_question_candidates` | 不允许 | 允许 | 去重后的有限题库 |
| `get_round_budget` | 允许 | 允许 | 只读预算视图 |

禁止通用 SQL、HTTP、文件系统、Shell 和任意 URL 工具。连续两次相同参数调用直接返回缓存结果并计数；连续三次工具错误终止 invocation。工具结果最大默认 12 KB，超出必须分页或截断并标注。

### 10.7 缓存与模型缓存

- 静态系统指令、标签目录摘要等稳定前缀可以使用供应商 context cache，但缓存 key 必须包含模型、Prompt 版本、目录版本和租户数据级别。
- 用户简历和答案默认不进入跨 invocation 共享缓存；若供应商缓存语义无法证明租户隔离，则关闭该功能。
- 缓存 token 仍进入预算和审计，不把 cache hit 当作零成本。

## 11. 工具与策略门禁

### 11.1 工具实现规则

- 所有 ADK function tool 都是 Spring service 的窄适配层。
- scope 在创建 `AgentRun` 时由服务端签发，包含 owner、interview、node、目录版本、过期时间和允许操作；工具调用只接收 scope token。
- 工具只读。需要写入的 Agent 输出必须回到应用服务，经策略门禁后在事务中执行。
- 每次工具调用写入 `multi_round_tool_call_audit`：run、tool、参数哈希、结果哈希、耗时、大小、状态和错误分类。
- 工具异常返回结构化可恢复错误，不把堆栈、SQL 或其他租户信息暴露给模型。

### 11.2 WorkflowPolicyGate

门禁按顺序执行：

1. JSON Schema 与字段大小。
2. 枚举、目录代码和引用存在性。
3. owner、interview、node、revision 与运行模式。
4. 状态机允许性。
5. 节点数、锚点、HR 末轮、JD 算法判定、轮次类型、题量、追问与时长限制。
6. 标签范围、题目重复度和敏感内容。
7. token、调用数、成本和全局保留额度。
8. 产品覆盖策略，例如必须保留的技术/项目/行为维度。

结果只能是：

- `ACCEPTED`：原样执行。
- `NORMALIZED`：做可证明等价的确定性修正，例如截断超长解释、去除重复次级标签。
- `REJECTED_WITH_FALLBACK`：拒绝并执行静态策略。
- `REJECTED_PAUSE`：无法安全继续，暂停并等待重试或人工处理。

所有结果保存 reason code。不得静默吞掉非法操作后假装 Agent 成功。

## 12. 数据模型

所有表使用 `bigint` 内部主键、不可枚举的 public id、`owner_user_id`、时间戳和必要的 `version/revision`。API 只暴露 public id。

### 12.1 聚合表

`multi_round_interviews`

- `id/public_id/owner_user_id`
- `resume_id` 与同 owner 外键
- `target_role/main_skill_id/taxonomy_version`
- `job_description_id/job_description_hash`
- `job_requirement_profile_json/profile_snapshot_json/policy_snapshot_json`
- `status/runtime_mode/current_node_id`
- `workflow_revision`
- `created_at/started_at/completed_at/last_activity_at`

`multi_round_workflows`

- `interview_id/owner_user_id`
- `revision/checkpoint_seq`
- `min_nodes/max_nodes`
- `fallback_reason/dynamic_adjustment_enabled`
- `lease_owner/lease_expires_at`

`multi_round_nodes`

- `public_id/interview_id/owner_user_id/node_key`
- `sequence/node_type/round_mode/status/origin`
- `mandatory_anchor/requirement_source/lock_policy`
- `goal_codes_json/difficulty`
- `question_budget/follow_up_budget/time_budget_seconds`
- `config_snapshot_json/replaced_by_node_id`
- `started_at/completed_at/version`

约束：`owner + interview + node_key` 唯一；一个面试至多一个活动节点，可用部分唯一索引实现。

### 12.2 交互与评估表

`multi_round_round_runs`

- node、attempt、ADK session reference、状态、开始/结束时间、摘要版本。
- `owner + node + attempt` 唯一。

`multi_round_questions`

- run、ordinal、parent_question_id、content、type、difficulty。
- 冻结 `taxonomy_version/primary_tag_code/secondary_tags_json/tag_validation_status`。
- Prompt 版本、生成来源、去重指纹、状态。

`multi_round_answers`

- question、answer public id、content、content hash、client idempotency key、submitted at。
- 正式答案不可覆盖；`owner + question + idempotency_key` 唯一。

`multi_round_code_submissions`

- question、round run、language、source code/object reference、content hash、submission version。
- `DRAFT/SUBMITTED/JUDGING/JUDGED/TIMED_OUT/FAILED` 状态、提交时间和是否按时。
- judge backend/version、编译状态、通过/总测试数、CPU/内存/墙钟用量、结果摘要和日志的脱敏对象引用。
- `owner + question + submission_version` 唯一；正式提交不可覆盖，源码不写入普通应用日志或 Agent 工具审计。

`multi_round_answer_evaluations`

- answer、task id、execution version、规则/模型/Prompt 版本。
- 分数、置信度、结构化反馈、评估时间。
- `owner + answer + evaluation_version` 唯一。

`multi_round_round_reports`

- node/run、report version、确定性指标、能力代码、差距代码、覆盖标签、evidence refs、叙事摘要。
- 只允许从已持久化题级评估生成。

`multi_round_final_reports`

- interview、report version、总分、覆盖、各维度结果、规则摘要、AI 叙事、生成依据。

### 12.3 Agent、预算与审计表

`multi_round_agent_runs`

- agent type、interview/node、invocation purpose、run key、status。
- provider/model/Prompt/schema 版本。
- input/output/thinking/cache/unclassified token、调用次数、耗时、错误分类。
- 输入/输出摘要哈希；敏感原文不默认保存。

`multi_round_budget_ledger`

- scope type/id、budget kind、allocated/reserved/consumed/released。
- reservation id、agent run、provider request id、状态。
- 原子约束保证消费不超过分配。

`multi_round_decisions`

- checkpoint、expected/applied revision、候选操作、门禁结果、最终操作、reason codes、fallback。

`multi_round_events`

- 聚合 revision、event type、payload version、payload、causation/correlation/idempotency key。
- append-only，用于审计和 SSE 重放，不承担事件溯源重建的首版职责。

`multi_round_tool_call_audit`

- agent run、tool、call index、scope hash、argument/result hash、size、duration、status。

### 12.4 学习证据扩展

现有 `learning_assessment_evidence.source_type` 检查约束只允许 `TEXT_INTERVIEW` 和 `VOICE_INTERVIEW`，迁移必须增加 `MULTI_ROUND_INTERVIEW`，并新增可为空但受来源约束的 `multi_round_answer_evaluation_id` 或建立独立来源关联表。

幂等键至少包含：

```text
owner_user_id
+ source_type
+ multi_round_answer_evaluation_id
+ evaluation_version
+ taxonomy_version
+ knowledge_node_id
+ tag_role
```

证据只接受题目生成时冻结且验证通过的标签，不使用评估时重新猜测的标签。

## 13. 事务、并发与幂等

### 13.1 事务边界

所有 LLM、ADK、工具网络调用必须在数据库事务外执行：

1. 短事务读取并冻结快照、预留预算、创建 run。
2. 事务外执行 Agent。
3. 短事务核销预算、策略校验、CAS 更新、写审计和 Outbox。

不得持有数据库锁等待模型响应。

### 13.2 命令幂等

以下命令必须要求或生成稳定幂等键：

- 创建面试。
- 提交答案。
- 完成轮次。
- 提交异步评估。
- Root checkpoint。
- 最终收口。
- 写学习证据和刷新路线。

HTTP 层重复请求返回第一次的资源或任务，不重复调用模型。异步层使用现有 `type:businessId` active key，并在业务表上增加 execution version 唯一约束。

### 13.3 并发控制

- 工作流与节点使用乐观锁 `revision/version`。
- 状态推进以 SQL 条件更新保证 `expected state + expected version`。
- Redisson 锁仅减少竞争，不作为正确性前提。
- 多实例协调使用数据库租约；租约包含 owner token 和到期时间，可安全接管。
- SSE 事件使用聚合 revision 排序，客户端按 event id 去重。

## 14. 异步评估接入

### 14.1 新任务类型

在现有 `AsyncTaskType` 中增加：

- `MULTIROUND_ROUND_EVALUATE`：权威轮次评估，是进入下一轮的前置条件。
- `MULTIROUND_CODE_JUDGE`：算法代码的受限沙箱编译与测试；完成后才允许算法轮权威评估。
- `MULTIROUND_FINALIZE`：总报告和学习路线收口。
- 可选的 `MULTIROUND_AGENT_RECOVERY` 不应首版新增；优先用定时恢复器扫描过期租约，避免把恢复本身变成重复队列任务。

### 14.2 快速判断与权威评估分离

轮内快速判断仅服务于“追问/下一题/结束”交互，需要低延迟，但可能不完整。权威评估读取整轮不可变快照，使用版本化规则和更完整预算，才允许：

- 生成正式分数和报告。
- 写学习证据。
- 供 Root 调整后续轮次。
- 进入总报告。

禁止把快速判断直接写成学习证据，避免一个模型瞬时判断同时控制对话、成绩和路线。

### 14.3 失败与重试

- 可重试：限流、网络、5xx、临时工具失败、结构化输出首次失败。
- 不可直接重试：Schema 连续失败、内容策略拒绝、预算不足、引用不存在、revision 冲突。
- 任务重试沿用统一退避；每次执行都读取原冻结快照和 execution version。
- 最终失败把节点置为 `FAILED_RECOVERABLE`，保留用户答案；用户不需要重答。

## 15. Redis 缓存设计

新增 `MultiRoundWorkflowViewCache`，不直接复用普通 `InterviewSessionCache` 的数据结构，但沿用其 cache-aside 和失败容忍原则。

建议 key：

```text
mr:view:{ownerHash}:{interviewPublicId}:{workflowRevision}
mr:active:{ownerHash}:{interviewPublicId}
mr:sse-cursor:{ownerHash}:{interviewPublicId}
```

- WorkflowView TTL 建议 30 分钟；完成态可延长到 24 小时。
- 写库提交后按 interview 删除 active pointer 或写新 revision，不修改旧 revision 内容。
- 缓存 miss 从数据库重建；Redis 故障不得阻止提交答案或状态推进。
- 不在 Redis 保存唯一预算、唯一幂等键、唯一 Session 归属或未落库答案。
- 分布式锁只作性能优化，数据库 CAS 是最终防线。

## 16. 自适应学习路线接入

### 16.1 证据产生时点

每轮权威评估成功后即写入学习证据并重算相关知识点进度，使证据尽早可靠落地。失败重试不重复累计。

### 16.2 路线刷新时点

现有 `LearningEvidenceIngestionService` 会在证据变化后立即刷新路线。如果多轮逐题沿用，会产生大量中间路线版本。因此需要扩展为可控策略：

- `persistEvidence(..., RefreshPolicy.DEFER_PATH_REFRESH)`：每轮写证据并更新知识进度，但延迟路线刷新。
- 整场 `COMPLETED` 后调用一次 `refreshAfterKnowledgeProgressChanged(..., "MULTI_ROUND_COMPLETED")`。
- 若面试长期暂停或最终失败，可在超过 24 小时后以 `MULTI_ROUND_PARTIAL` 最多刷新一次，避免已产生的可信证据永久不进入路线。

### 16.3 推荐可解释性

路线项继续由确定性规则选择知识点，模型只能润色固定路线的学习策略。多轮面试新增的解释依据包含：轮次、题目、冻结标签、评分、能力差距代码和报告版本。Root Agent 的建议不能直接改变路线优先级。

## 17. API 与事件设计

### 17.1 REST API

```text
POST   /api/multi-round-interviews
GET    /api/multi-round-interviews/{interviewId}
GET    /api/multi-round-interviews/{interviewId}/events?after={eventId}
POST   /api/multi-round-interviews/{interviewId}/answers
POST   /api/multi-round-interviews/{interviewId}/code-drafts
POST   /api/multi-round-interviews/{interviewId}/code-submissions
GET    /api/multi-round-interviews/{interviewId}/code-submissions/{submissionId}
POST   /api/multi-round-interviews/{interviewId}/pause
POST   /api/multi-round-interviews/{interviewId}/resume
POST   /api/multi-round-interviews/{interviewId}/cancel
POST   /api/multi-round-interviews/{interviewId}/retry-current-step
GET    /api/multi-round-interviews/{interviewId}/report
```

不提供普通用户可调用的“开始某轮、强制结束某轮、手工进入下一轮、任意修改节点”接口。管理员恢复接口如需增加，必须独立权限和审计。

### 17.2 WorkflowView

返回：

- 面试状态、runtime mode、workflow revision、预算水位级别。
- 动态时间线：节点 public id、类型、显示名、状态、序号、开始/完成时间。
- 当前问题、回答是否已接收、异步评估进度。
- 可执行动作：`ANSWER`、`PAUSE`、`RESUME`、`RETRY`、`VIEW_REPORT`。
- 降级或恢复提示，不返回内部错误和 Prompt。

### 17.3 SSE 事件

```text
INTERVIEW_INITIALIZING
WORKFLOW_READY
ROUND_READY
QUESTION_ASKED
ANSWER_ACCEPTED
ROUND_EVALUATING
ROUND_COMPLETED
WORKFLOW_ADAPTED
INTERVIEW_FINALIZING
INTERVIEW_COMPLETED
INTERVIEW_PAUSED
RECOVERY_REQUIRED
FALLBACK_ACTIVATED
```

事件只提示状态变化；前端收到后重新读取 WorkflowView，避免把 SSE payload 当完整真相。

## 18. 前端体验

### 18.1 创建页

- 从简历详情、普通面试结果或学习页进入。
- 选择或粘贴 JD，并选择简历、目标岗位、主 Skill、期望时长和面试侧重。
- 在开始前展示岗位需求画像摘要，尤其明确“为什么需要/不需要独立算法笔试”、证据和置信度；`UNKNOWN` 不能伪装成“不需要”。
- 展示初始轮次预览、2～6 轮数量范围和预计时长；HR 以固定末轮显示，动态可变轮标记为“可能根据前序表现调整”。
- 若 JD 强制算法导致所选时长无法容纳“算法 + 核心专业 + HR”，页面要求提升时长或取消创建，不能悄悄删轮。

### 18.2 面试页

- 顶部是动态轮次时间线；已完成、当前、未来计划和被替换节点有明确视觉区别。
- `HR_FINAL` 始终固定在时间线末端；算法轮显示其来源是 JD 硬要求还是条件建议。
- 中部只显示当前问题、追问关系、回答输入和提交状态。
- 侧栏显示本轮目标、已用时间和粗粒度预算状态，不向用户暴露 token 细节。
- 提交后先显示“答案已保存”；快速判断/下一题失败时提供重试，不要求重输答案。
- `EVALUATING` 状态显示评估进度，禁止重复完成本轮。
- `STATIC_FALLBACK` 以非恐慌提示说明后续将按固定流程继续。

### 18.3 报告页

- 总览、逐轮报告、能力证据、薄弱知识点、覆盖情况和推荐学习路线入口。
- 明确区分事实评分、模型解释和未覆盖项。
- 每个学习建议可追溯到轮次和题目，但默认不展开内部 Agent 日志。

### 18.4 页面恢复

路由只保存 interview public id。所有当前状态由 WorkflowView 恢复；本地可暂存未提交草稿，但不得把草稿当已提交答案。

## 19. Google ADK 接入注意事项

### 19.1 版本与兼容性门槛

截至 2026-10-10，Google ADK Java 官方 release 已到 1.11.0，但当前项目使用 Java 25、Spring Boot 4.1、Spring AI 2.0 和 OpenAI 兼容 Provider。版本号不能直接等同于兼容结论。

`G0` 必须建立最小 POC，并满足：

1. Gradle 依赖可解析，`gradle :app:compileJava` 通过，无不可接受的 Jackson、gRPC、Guava、Reactor 或日志绑定冲突。
2. 可以从 Spring Bean 创建 Runner/Agent，并在虚拟线程与现有异步执行器中安全运行。
3. 现有 OpenAI 兼容 Provider 可通过受支持的模型适配层调用；若需自定义 `BaseLlm` 适配器，必须验证 streaming、tool call、usage 和取消语义。
4. SessionService 可以替换为显式实现或受控内存实现，且不会绕过 owner 隔离。
5. 能捕获 token 使用、工具调用、超时、取消和错误回调。
6. 结构化输出失败可以被现有 Schema 校验和一次修复逻辑接管。

通过后在 version catalog 锁定精确版本和依赖约束；升级必须重新执行契约测试。

### 19.2 不绑定框架的端口

业务层只依赖：

```java
interface MultiRoundAgentGateway {
    InitialWorkflowDecision plan(InitialWorkflowInput input, AgentRunBudget budget);
    RoundAction decide(RoundActionInput input, AgentRunBudget budget);
    WorkflowAdaptationDecision adapt(WorkflowCheckpointInput input, AgentRunBudget budget);
    FinalNarrative narrate(FinalNarrativeInput input, AgentRunBudget budget);
}
```

`GoogleAdkMultiRoundAgentGateway` 是基础设施实现。另提供 `StaticMultiRoundAgentGateway` 和测试 fake。领域服务不引用 ADK 的 Session、Event 或 Tool 类型。

### 19.3 Runner 与事件处理

- 每次业务 invocation 创建唯一 run key。
- 消费 ADK event stream 时，只提取最终候选内容、工具审计、usage 和错误；部分文本不写入领域状态。
- 调用取消后等待事件循环确认终止或租约过期，不能马上重复启动同 run key。
- 若使用并行 Agent/工具，仅允许彼此无写依赖的只读查询；首版 Root 与 Interview 不并行推进业务状态。

### 19.4 Prompt 与 Schema 版本化

- 系统指令、输出 Schema、工具描述和策略快照分别版本化。
- AgentRun 保存全部版本号与内容哈希。
- Prompt 更新只影响新 run；已冻结节点继续使用其快照版本，除非因安全修复强制升级并记录原因。
- 不保存或依赖模型隐藏推理。审计使用 reason code、简短 rationale 和确定性门禁结果。

## 20. 降级、故障与恢复矩阵

| 故障 | 当前状态 | 自动处理 | 用户可见结果 |
| --- | --- | --- | --- |
| Root 初始规划超时 | `INITIALIZING` | 重试后静态模板 | 稍后进入固定流程 |
| Root 调整失败 | 上一轮已完成 | 保留原未来节点 | 无需重答 |
| Interview 首题失败 | `READY` | 重试；再失败用题库 | 本轮稍后开始 |
| 快速判断失败 | 答案已保存 | 规则决定下一题/结束 | 不丢答案 |
| 权威评估临时失败 | `EVALUATING` | 统一异步重试 | 显示评估中 |
| 权威评估最终失败 | `FAILED_RECOVERABLE` | 暂停，允许恢复任务 | 提示稍后重试 |
| Redis 不可用 | 任意 | 查数据库、禁用缓存锁 | 功能继续，可能变慢 |
| Kafka/Outbox 延迟 | `EVALUATING` | Outbox 重发、租约恢复 | 进度延迟 |
| Schema 连续失败 | 调用中 | 拒绝并静态策略 | 流程继续或暂停 |
| token 低水位 | 任意 | 减少追问与动态调整 | 更精简的后续轮次 |
| 硬预算耗尽 | HR 前任意状态 | 禁止新模型调用，裁剪可选轮并用固定题库完成最小 `HR_FINAL` | 说明已转为精简流程及覆盖范围 |
| revision 冲突 | 任意 | 重读；不盲重放决策 | 无感或提示刷新 |
| 服务重启 | 任意非终态 | 扫描租约与 DB 状态恢复 | 页面可重新进入 |

静态模板不是异常兜底代码片段，而是一套由 JD 画像选择、版本化且可测试的合法工作流。例如：

- 算法 `REQUIRED`：算法笔试 → 核心专业 → HR。
- 算法 `NOT_REQUIRED/UNKNOWN`：核心专业 → 项目/系统设计（条件）→ HR。
- 算法 `PREFERRED` 且预算不足：核心专业中保留轻量问题求解观察，不创建独立算法笔试 → HR。

静态降级仍使用同一状态机、异步评估和学习闭环；`HR_FINAL` 使用固定合规题库也必须完成，除非用户主动取消整场面试。

## 21. 安全、隐私与合规

- 所有查询必须包含 owner；子表使用同 owner 复合外键防止应用遗漏造成串户。
- API 使用 public id，数据库 id 不暴露。
- 简历摘要、答案和报告设置数据保留策略；Agent 审计尽量保存哈希和结构化元数据。
- Provider 请求日志关闭原文或做脱敏；API key 继续由现有 Provider 配置管理，不写入 Prompt、事件或错误。
- 导出与删除未来接入账号运营阶段 F 时，必须覆盖领域表、ADK Session 审计和缓存键。
- Prompt injection、越权工具调用、超长输入、恶意 JSON、重复请求和 SSE 事件伪造纳入安全测试。
- 候选人的模型评分属于辅助评估，报告 UI 标注模型版本、覆盖不足和不确定性，不宣称绝对判断。

## 22. 可观测性与审计

### 22.1 指标

- 面试创建成功率、初始化 P50/P95/P99。
- 每轮问题数、追问数、完成时长、评估时长。
- Root 接受/规范化/拒绝/降级比例。
- 每场和每轮 token、模型调用、工具调用、费用估算。
- 上下文压缩次数和压缩失败率。
- 异步任务重试、租约接管和最终失败率。
- Redis hit rate，但不以 hit rate 作为正确性指标。
- 学习证据插入/去重数、知识进度重算和路线刷新次数。
- 越权拒绝、Schema 拒绝、重复问题拒绝和预算熔断次数。

标签维度不得包含用户 id、面试 id 或问题 id 等高基数字段；这些进入结构化日志和 trace baggage 的受控字段。

### 22.2 Trace 与关联

统一传递 `traceId/correlationId/interviewPublicIdHash/nodePublicIdHash/agentRunId/asyncTaskId`。从 HTTP → Outbox → Kafka → Handler → Provider 的链路保持关联，但日志不记录原始回答。

### 22.3 审计查询

内部支持按面试还原：

```text
创建输入快照
→ 初始候选计划
→ 门禁结果和实际计划
→ 每轮问题/答案/评估
→ 每个检查点候选调整与实际调整
→ 预算预留/核销
→ 最终报告
→ 学习证据与路线版本
```

## 23. 测试策略

### 23.1 单元与契约测试

- 状态机所有允许与禁止转换。
- PolicyGate 的节点数、锚点、HR 末轮、JD 算法四态、轮次类型、预算、revision、标签和重复度规则。
- `REQUIRED/PREFERRED/NOT_REQUIRED/UNKNOWN` 的算法轮决策矩阵，以及 Root 尝试违规增删算法轮时的拒绝测试。
- 任意未来节点调整后 `HR_FINAL` 仍唯一且 sequence 最大；预算耗尽时固定 HR 仍可完成。
- 每个 Agent DTO 的 JSON Schema：合法、缺字段、额外字段、超长、未知枚举、恶意内容。
- BudgetGovernor 的预留、核销、释放、并发、低水位和未知 usage。
- HandoffContext 白名单，证明原始历史不能进入下一轮。
- 工具 scope 过期、串户、跨节点、超次数、超大小。

### 23.2 集成测试

- Testcontainers PostgreSQL、Kafka、Redis 下的创建到完成全链路。
- Outbox 重复发送、任务重复消费、服务在 Agent 调用前后崩溃。
- 两个账户尝试访问、提交、订阅和恢复对方的面试。
- 不同 JD 生成 2～6 轮计划：算法必选、算法禁止、未知、系统设计必选、领导力必选等组合。
- 算法代码执行沙箱的网络、文件、CPU、内存、超时和逃逸测试；没有沙箱时必须产生 `CODE_NOT_EXECUTED` 标记。
- Redis 全程不可用仍能完成。
- Provider 超时、限流、返回非法 JSON、usage 缺失。
- CAS 冲突、重复答案、双浏览器提交、两个 worker 抢租约。
- 证据幂等、同 owner 外键、路线仅在整场结束刷新一次。

### 23.3 Agent 离线评测

建立版本化场景集：

- 不同年限、岗位和简历质量。
- 回答正确、部分正确、跑题、拒答、Prompt 注入和超长回答。
- 技术薄弱点与未来节点调整预期。
- 重复题、非法标签、不合理结束和预算耗尽。

评测指标包括 Schema 成功率、门禁接受率、有效追问率、重复率、覆盖率、成本、延迟和静态策略一致性。Prompt 或模型升级必须与当前基线做回归对比。

### 23.4 性能与容量测试

- 预期并发下数据库活动节点唯一约束和预算账本争用。
- SSE 长连接、断线重连和事件追赶。
- 6 节点、最大问答和最大允许文本的 token/内存压力。
- 供应商慢响应时虚拟线程、连接池和任务队列不会耗尽。

### 23.5 验收门槛

- 无串户、无重复推进、无重复证据。
- 任何受控故障后答案不丢失，状态可恢复。
- 100% Agent 写入都经过 Schema 与 PolicyGate。
- 预算硬上限在并发条件下仍不超支。
- 静态模式可独立完成完整面试。
- `gradle :app:compileJava`、后端测试编译/相关测试和前端生产构建通过。

## 24. 分阶段实施计划

### G0：设计冻结与 ADK 兼容性 POC

交付：

- 本 TRD 评审记录与冻结版本。
- 独立 POC：依赖、Runner、Session、function tool、结构化输出、usage、取消、OpenAI 兼容 Provider。
- ADR：ADK 精确版本、模型适配方式、SessionService 方式。

退出条件：六项兼容性门槛全部通过；否则保留 `MultiRoundAgentGateway`，评估替代运行框架，不进入业务编码。

### G1：静态、持久化工作流骨架

交付：

- Flyway 表、状态机、幂等、预算账本基本结构。
- JD 画像与轮次约束生成器、2～6 轮条件静态模板、自动推进、暂停/恢复、SSE 和动态时间线。
- Fake/Static gateway，全程不依赖模型也可完成。

退出条件：并发、恢复和越权集成测试通过。

### G2：隔离的 InterviewAgent

交付：

- ADK gateway、每轮独立 Session、轮内上下文压缩。
- 只读标签/题库工具、Schema、PolicyGate、调用审计。
- `SHADOW` 模式比较 Agent 建议与静态执行，不改变用户流程。

退出条件：离线评测、预算、注入和上下文泄漏测试达到门槛。

### G3：WorkflowRootAgent 与动态未来节点

交付：

- 初始规划、检查点、未来节点白名单变更。
- 决策审计、revision CAS、低水位与静态降级。
- 先灰度只允许 `NORMALIZED/REJECTED` 观测，再开放 `ACCEPTED` 执行。

退出条件：不能修改活动/历史节点；重复检查点不重复变更；降级可恢复。

### G4：权威评估与学习闭环

交付：

- 新异步任务、轮次/总报告、证据来源扩展。
- 每轮证据与进度、整场一次路线刷新、部分面试延迟刷新。
- 报告页和学习路线追溯。

退出条件：异步重试不重复写证据，路线版本不爆炸，全部来源可审计。

### G5：灰度与生产验收

运行模式：

```text
STATIC_ONLY → SHADOW → GOOGLE_ADK_CANARY → GOOGLE_ADK
                          │
                          └────────► STATIC_FALLBACK
```

按租户/用户白名单和百分比灰度。观察成本、延迟、门禁拒绝率、失败率和用户完成率；达到阈值才扩大。降级开关必须可在不发版的情况下关闭动态决策。

## 25. 需要在 G0 冻结的参数

以下不是架构歧义，但必须通过 POC/产品评审形成配置基线：

- ADK Java 精确版本和 OpenAI 兼容模型适配器。
- 每种节点的默认题量、追问数和时间预算。
- 整场 token/调用/费用上限及不同用户套餐是否分级。
- JD 算法判定关键词/岗位策略阈值、`PREFERRED` 启用所需的最小时长，以及标准算法题库版本。
- 离线评测集阈值，例如 Schema 成功率、重复率和门禁接受率。
- 暂停超时、未完成面试保留期与部分学习路线刷新时间。

在这些参数冻结前可以开发 G1 的领域骨架，但不能把临时数值硬编码进 Agent Prompt 或业务表约束。

## 26. 明确否决的方案

- 否决“整场一个 Agent + 一条无限增长历史”：无法隔离、预算和恢复。
- 否决“每个节点创建不同业务 Agent 类型”：导致类和 Prompt 爆炸；节点是配置，同一 InterviewAgent 执行。
- 否决“Agent 直接写数据库或调用写工具”：绕过权限、事务和门禁。
- 否决“Redis/ADK Session 保存唯一状态”：缓存丢失会破坏流程。
- 否决“复用普通 interview_sessions 作为轮次子表”：其预生成题集与单报告假设不匹配动态多轮。
- 否决“每题立即刷新个性化路线”：制造大量中间版本和额外模型成本。
- 否决“Root 根据原始全量对话调整流程”：跨轮泄漏且 token 不可控。
- 否决“一次提交数据库、Agent、前端和学习闭环”：无法分层验证和安全回滚。

## 27. 官方参考

- [Google ADK Java Releases](https://github.com/google/adk-java/releases)
- [Google ADK Java Changelog](https://github.com/google/adk-java/blob/main/CHANGELOG.md)
- [ADK Runtime Event Loop](https://github.com/google/adk-docs/blob/main/docs/runtime/event-loop.md)
- [ADK Java Quickstart](https://github.com/google/adk-docs/blob/main/docs/get-started/java.md)
- [ADK Function Tools](https://github.com/google/adk-docs/blob/main/docs/tools-custom/function-tools.md)
- [ADK Java Agent API](https://google.github.io/adk-docs/api-reference/java/com/google/adk/agents/package-use.html)

## 28. 开发开始前检查清单

- [ ] 基线文档已把多轮面试列为阶段 G。
- [ ] TRD 已评审，双层 Agent、状态机和数据边界无未决架构问题。
- [ ] ADK POC 已通过 Java 25/Spring Boot 4.1/Provider/usage/tool/cancel 测试。
- [ ] 静态模式无需 ADK 即可端到端完成。
- [ ] 新表、同 owner 外键、唯一索引和迁移回滚验证完成。
- [ ] 所有 Agent 输出 DTO 和 JSON Schema 已版本化。
- [ ] PolicyGate 与 BudgetGovernor 先于真实 Agent 上线。
- [ ] 每轮 Session 隔离和跨轮 HandoffContext 白名单有自动化测试。
- [ ] 异步权威评估的幂等与失败恢复有集成测试。
- [ ] 学习证据来源约束已迁移，路线只在收口时刷新一次。
- [ ] Redis 故障、Kafka 延迟、服务重启和模型不可用均有演练。
- [ ] 前端不再提供手工推进按钮，刷新可从 WorkflowView 恢复。
- [ ] `SHADOW`、灰度、熔断和静态降级开关可观测、可审计。
