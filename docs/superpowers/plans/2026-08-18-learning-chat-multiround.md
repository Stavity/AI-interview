# 学习、普通 Chat 与多轮面试实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不影响已有单轮面试、RAG、Provider 和语音面试的前提下，实现学习、Provider 固定的普通 Chat 和受控多轮面试。

**Architecture:** 新领域以独立模块、独立 Flyway 表和 Service 层边界实现。LLM 只提供结构化建议，状态转换和持久化由确定性服务执行；所有会话按创建时 Provider 固定。

**Tech Stack:** Java 25、Spring Boot 4.1、Spring AI 2、Spring Data JPA、Flyway、PostgreSQL、Redis、React 18、TypeScript、Vite。

**Spec:** `docs/TRD_学习对话多轮面试技术需求.md`、`docs/技术升级实施方案.md`、`TRD_实库核验更新.md`

## 全局约束

- 只新增 Flyway，不能编辑 V1 或已执行迁移；当前实库至少保留 1 条面试会话和 5 条 Provider 配置。
- 外部 LLM/RustFS/HTTP 调用不得在数据库事务中。
- 对外响应使用 `Result<T>`，业务失败使用 `BusinessException`。
- 普通 Chat 和多轮会话 Provider 固定，绝不保存 API Key、密文或 nonce。
- 一期不得执行、编译或解释用户代码。
- 新功能以测试先行实现，前端调用必须通过 `frontend/src/api/`。

---

### Task 1: 学习域 Schema 与 Skill 目录同步

**Files:**
- Create: `app/src/main/resources/db/migration/V20260818__add_learning_schema.sql`
- Create: `app/src/main/java/interview/guide/modules/learning/model/LearningSkillSourceEntity.java`
- Create: `app/src/main/java/interview/guide/modules/learning/repository/LearningSkillSourceRepository.java`
- Create: `app/src/main/java/interview/guide/modules/learning/service/LearningSkillCatalogSyncService.java`
- Create: `app/src/test/java/interview/guide/modules/learning/service/LearningSkillCatalogSyncServiceTest.java`

**Interfaces:**
- Consumes: `InterviewSkillService.getAllSkills()` 的 `SkillDTO` 和分类列表。
- Produces: `syncCatalog()`，幂等同步所有可解析的非 `_shared` Skill 分类，并对消失来源标记不可用。

- [ ] 写目录同步的失败测试：首次生成 ACTIVE 条目、重复同步不产生重复条目、缺失条目标记 UNAVAILABLE。
- [ ] 运行目标测试，确认因生产类缺失而失败。
- [ ] 新增 Flyway 表、Entity、Repository 和最小同步服务；使用 SHA-256 内容哈希。
- [ ] 运行目标测试及后端编译，确认通过。

### Task 2: 学习进度、计划、任务和提醒后端

**Files:**
- Modify: `V20260818__add_learning_schema.sql` 或新增同日后续学习迁移
- Create: `modules/learning/model/*Plan*, *Task*, *Reminder*, *Progress*`
- Create: `modules/learning/service/LearningProgressService.java`
- Create: `modules/learning/service/LearningPlanService.java`
- Create: `modules/learning/controller/LearningController.java`
- Test: `modules/learning/service/*Test.java`

- [ ] 先测试掌握度 clamp、乐观锁更新、草稿计划确认、到期提醒 `PENDING -> READY`。
- [ ] 实现实体、Repository、短事务服务和 API DTO。
- [ ] 验证不向 `interview_schedule` 写入任何学习任务。

### Task 3: Provider 固定的普通 Chat 后端

**Files:**
- Create: `db/migration/V20260819__add_chat_schema.sql`
- Create: `modules/chat/{model,repository,service,controller}`
- Create: `modules/chat/service/ProviderFingerprintService.java`
- Test: `modules/chat/service/ChatSessionServiceTest.java`

- [ ] 先测试会话固化默认 Provider、禁止 Provider 更新、消息序号唯一、Provider 禁用时不回退。
- [ ] 实现消息占位、SSE 完成/失败回写和重试幂等。
- [ ] 通过真实 `LlmProviderRegistry` 边界测试和 Controller 测试。

### Task 4: 多轮确定性状态机与算法模板

**Files:**
- Create: `db/migration/V20260820__add_multi_round_schema.sql`
- Create: `modules/multiround/{model,repository,service,controller}`
- Test: `modules/multiround/service/MultiRoundInterviewServiceTest.java`

- [ ] 先测试合法四轮流程、跳轮拒绝、重复操作幂等、三种模板及静态评审提示。
- [ ] 实现状态机、题目/提交持久化和 Java/Python/C++ 模板。
- [ ] 明确禁止任何代码执行路径。

### Task 5: 受控 Agent 计划与审计

**Files:**
- Create: `db/migration/V20260821__add_interview_agent_audit.sql`
- Create: `common/ai/agent/{InterviewPlan,AgentPlanValidator,AgentBudget,InterviewAgentOrchestrator}`
- Create: `modules/multiround/service/AgentRunAuditService.java`
- Test: `common/ai/agent/AgentPlanValidatorTest.java`

- [ ] 先测试非法轮次、越界追问、非法类别和超预算被拒绝。
- [ ] 使用 plain ChatClient 生成结构化计划并由确定性服务写库。
- [ ] 实现模型故障时的固定轮次降级策略和审计记录。

### Task 6: 前端学习与普通 Chat

**Files:**
- Create: `frontend/src/api/{learning,chat}.ts`
- Create: `frontend/src/pages/{LearningPage,ChatPage}.tsx`
- Modify: `frontend/src/constants/routes.ts`, `frontend/src/App.tsx`
- Test: `frontend/src/pages/*test.ts`

- [ ] 先测试路由和 Provider 切换控件不存在。
- [ ] 实现学习目录、任务、提醒状态和 Chat SSE UI。
- [ ] 运行 `pnpm run build`。

### Task 7: 前端多轮面试与回归

**Files:**
- Create: `frontend/src/api/multiRound.ts`
- Create: `frontend/src/pages/{MultiRoundInterviewPage,MultiRoundReportPage}.tsx`
- Create: `frontend/src/components/multiround/AlgorithmEditor.tsx`
- Test: `frontend/e2e/multi-round.spec.ts`

- [ ] 先测试三语言模板和“未执行代码”文案。
- [ ] 实现服务端状态驱动的轮次交互、草稿和报告。
- [ ] 运行后端全量测试、前端构建和关键 E2E。
