# TRD：用户系统与全域数据归属设计

> 状态：用户认证和核心业务数据归属已落地；`interview_guide_redesign` 已迁移至 `20260927.4` 并通过核心跨账户读取验收；自动学习证据闭环仍未实现
> 日期：2026-10-08
> 目标：建立可信用户身份，并将用户产生或保存的业务数据隔离归属到用户；作为题目学习闭环的前置阶段

## 1. 设计结论

本项目先按“单用户即一个数据租户”实现，不引入团队、组织或共享工作区。所有用户私有数据必须有可验证的归属路径；请求参数、路径参数、消息体、`X-User-Id` 等客户端自报值都不能作为身份依据。

第一版采用 Spring Security 的服务端会话认证：浏览器使用 `HttpOnly`、`SameSite=Lax` 会话 Cookie，状态变更请求保留 CSRF 防护；会话存入 PostgreSQL 的 Spring Session JDBC 表，登录后轮换会话 ID。应用从认证上下文取得内部 `userId`，后续必须通过带 owner 条件的仓储查询访问私有资源。移动端或第三方 API 尚不在当前范围；将来确有需要时再增加 OAuth2/OIDC 客户端或访问令牌，不在浏览器中自行实现 JWT 生命周期。

用户私有数据的归属采用两种方式：独立资源根表直接保存 `owner_user_id`；明细数据通过外键沿父资源继承所有者。异步任务、独立检索索引等脱离父请求执行的记录也要保存用户归属快照。平台共享字典、题目知识树和平台级设置是 `SYSTEM` 所有，不伪造一个普通用户作为所有者；如需审计修改人，另记 `created_by_user_id` / `updated_by_user_id`。

## 2. 当前实现盘点

阶段 1 已增加 `users`、`user_password_credentials`、`auth_action_tokens` 及 Spring Session JDBC 表；后端提供公开注册、邮箱验证、登录、退出、当前用户、重发验证邮件、忘记密码和重置密码接口。前端有对应页面、Cookie 请求、CSRF header 和受保护路由。公开账号默认仅授予 `USER` 角色；暂无管理员引导/用户管理接口。

当前工作树已为用户私有实体增加 Hibernate tenant discriminator，并补齐向量检索、异步任务、对象存储下载和 WebSocket 的 owner 传播。目标数据库已由 Flyway 执行归属迁移，将既有数据回填给唯一 ACTIVE 账号并强制非空；临时第二账户的真实登录、空列表和跨账户简历/面试读取拒绝已经通过。对象存储匿名访问、向量检索以及语音 WebSocket 的跨账户专项验收仍需在上线前完成，因此目前可以继续开发，但还不能把阶段 B 标记为全部安全场景验收完毕。

语音面试历史列 `voice_interview_sessions.user_id VARCHAR` 仍保留作兼容数据，但不作为鉴权来源；当前工作树改为使用 `owner_user_id`，查询依据认证上下文，WebSocket 握手验证目标会话属于当前用户。限流切面已停止读取可伪造的 `X-User-Id` 请求头；用户维度改为使用 Spring Security 的已认证名称，匿名请求退回远端 IP 桶。

跨域配置从部署的 `CORS_ALLOWED_ORIGINS` 读取精确来源并允许凭证，开发前端默认在 `localhost:5173`；已将该配置接入 Spring Security 的 CORS 过滤链。CORS 不作为授权；工作树中的数据访问由租户过滤与外部存储 owner 校验保护。

## 3. 用户与认证模型

### 3.1 核心实体

使用内部递增 `BIGINT id` 作为数据库外键，并提供随机 `public_id UUID` 作为公开 API 标识。公开 ID 不替代 owner 校验；每次访问仍必须验证资源归属。

```text
users
  id, public_id, email_normalized, display_name,
  status, role, created_at, updated_at

user_password_credentials
  user_id, password_hash, password_updated_at

auth_action_tokens
  user_id, token_hash, purpose, expires_at, consumed_at, created_at

user_external_identities (预留，可后续启用)
  user_id, provider, provider_subject, created_at
  UNIQUE(provider, provider_subject)
```

当前状态为 `PENDING_EMAIL_VERIFICATION`、`ACTIVE`、`DISABLED`，注册账号固定授予 `USER`；`ADMIN` 保留给后续显式引导流程。密码使用 BCrypt `PasswordEncoder` 存储，原文不落库；认证入口使用统一错误消息并按 IP 限速。验证/重置 token 使用密码学安全随机数，只保存 SHA-256 摘要，并设置有效期、用途及一次性消费状态。[密码存储文档](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) [PasswordEncoder 文档](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/password-encoder.html)

公开自主注册已确定为产品要求：注册账号先处于待验证状态，只有验证邮箱后可登录。邮件发送使用 Spring Mail；需部署配置 SMTP 并显式打开 `APP_AUTH_MAIL_ENABLED`，设置 `APP_MAIL_HOST`、端口、账号、密码、TLS 选项及 `APP_AUTH_MAIL_FROM`。密码找回同样通过一次性邮件链接完成，并在重置后撤销该用户已有会话。仓库和数据库迁移中不得写入默认账号口令。初始管理员的创建/恢复流程仍待设计，公开注册永远不能自助获得 `ADMIN`。

### 3.2 会话与浏览器安全

- 登录成功后由服务端创建会话并轮换会话 ID；登出、禁用账号、密码重置时撤销对应会话。
- 会话 Cookie 使用 `HttpOnly`、生产环境 `Secure`、`SameSite=Lax`，空闲超时 8 小时；会话由 Spring Session JDBC 存储于 PostgreSQL，因此可供多实例共享。生产环境必须设置 `APP_SESSION_COOKIE_SECURE=true` 并通过 HTTPS 提供服务。
- 对 SPA 提供 `/api/auth/csrf`；前端在 `POST/PUT/PATCH/DELETE` 请求中带 `X-CSRF-TOKEN` header。登录/退出后前端清除内存中的旧 token，并在下次写请求时重新获取。
- CORS 仅允许部署配置中的精确 Origin；跨站 Cookie 如确有必要才使用 `SameSite=None; Secure`，首选同站点反向代理部署。
- 语音 WebSocket 握手使用已认证 HTTP 会话；连接建立时检查该 `sessionId` 属于当前用户。不得把认证凭证或 `userId` 放进 WebSocket URL。
- `/api/auth/me` 返回当前用户公开信息；认证来源是 Spring Security `Authentication`，而非客户端传来的 ID。

已实现/规划接口：

```text
GET    /api/auth/csrf
POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/auth/me
POST   /api/auth/email/verify
POST   /api/auth/email/resend
POST   /api/auth/password/forgot
POST   /api/auth/password/reset
```

Spring Security 官方提供 HTTP Session 中的认证持久化机制，也为扩展到缓存/数据库会话存储保留了配置点；SPA 使用 Cookie 会话时必须保留 CSRF，并按认证/登出事件刷新 token。[会话管理文档](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) [SPA CSRF 文档](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

### 3.3 授权规则

1. 所有私有 API 必须认证；仅登录、登出、CSRF、静态资源和明确的健康检查匿名开放。
2. 私有 JPA 实体使用 Hibernate `@TenantId`。认证过滤器在 Spring Security 完成会话加载后把可信用户 ID 写入请求级 `TenantContext`；异步边界显式恢复该上下文；Hibernate resolver 只读取 `TenantContext`，不得反向读取延迟加载的 SecurityContext。无法确定身份时使用不可能匹配正常用户的保留租户 ID 并 fail closed。原生 SQL、向量库和对象存储访问必须显式使用 `owner_user_id` / 已授权资源 ID。
3. 不允许先 `findById(id)` 再假定调用者有权访问；所有 ORM 查询必须在可信租户上下文中执行，原生查询和外部存储访问显式校验 owner。
4. 建立资源关系时校验双方同属当前用户；不能把 A 用户的面试关联到 B 用户的简历、知识库或会话。
5. 使用 `public_id` 降低顺序 ID 暴露，但不得将“ID 不可猜”当成访问控制。
6. `/api/learning/**`、知识库、简历、面试、聊天、LLM Provider 配置以及语音 REST/WebSocket 全部应用同一认证与资源隔离规则。

## 4. 数据归属矩阵

| 领域/数据 | 归属方式 | 设计要求 |
|---|---|---|
| 简历、简历分析 | `resumes.owner_user_id`、`resume_analyses.owner_user_id` 并由复合外键保证一致 | 新对象 key 按用户前缀；前端只拿受认证 API 下载地址，不暴露对象存储 URL |
| 普通面试、面试日程 | `interview_sessions.owner_user_id`、`interview_schedule.owner_user_id` | 会话可以关联同一用户的简历/知识库；答案、报告、参考答案随会话继承 owner，数据库复合外键限制跨用户关联 |
| 知识库、知识库题目 | 知识库、题目、题目标签分别保存 owner 并由复合外键限制一致 | 文件、解析文本、题目生成任务、下载和向量检索都限定授权知识库 |
| RAG 聊天 | `rag_chat_sessions.owner_user_id`；消息、知识库关联经会话继承 | 每个被关联知识库必须与会话 owner 一致 |
| 普通聊天 | `chat_sessions.owner_user_id`；`chat_messages` 经会话继承 | Provider 配置只能选当前用户可用项或平台内置项 |
| 语音面试 | 会话、消息、评估分别保存 owner 并由复合外键保证一致 | 历史 `user_id` 列仅为兼容保留；鉴权来自会话认证 owner，列表不接受客户端 `userId` |
| 学习计划、任务、提醒 | `learning_plans.owner_user_id`；任务/提醒经计划继承 | 不同用户的计划、进度、提醒互不可见 |
| 学习进度、题目学习证据 | 每条进度/证据带 `owner_user_id` | 唯一键包含用户；面试结果只能更新当前面试所有者的学习数据 |
| 学习技能来源 | 系统共享目录 | 不是用户私有数据；用户掌握度与该目录项的关联由用户进度表表达 |
| 用户自定义 LLM Provider | `llm_provider_config.owner_user_id` | API Key 仍只存加密密文；内置 Provider 为系统所有、只读；读取和调用均校验 owner |
| 平台 LLM 全局设置 | 系统所有 | 仅管理员可改；记录 `updated_by_user_id` 审计操作者，不归属于普通用户 |
| 异步任务与 Outbox | `async_task.owner_user_id`；Outbox 经 `task_id` 继承 | 消费端使用任务中固化的 owner 和资源 ID；重试不得根据不可信 payload 扩权 |
| 向量索引、Redis 缓存、对象存储 | 由用户根资源确定 owner，并在 metadata/key 中保留 owner/resource 标识 | 搜索前先授权资源 ID；缓存 key 按用户/会话命名空间隔离 |
| 题目知识树、别名、Skill scope、版本目录 | 系统所有、全局只读 | 不是用户内容；用户标注关系仍通过所属题目间接归属用户 |

当前实现选择为私有实体行重复保存 `owner_user_id`，由 `(id, owner_user_id)` 复合唯一键和父子复合外键确保父子归属一致；Hibernate tenant discriminator 作为 ORM 查询的默认隔离层。跨根资源关联表（例如 RAG 会话—知识库）也通过数据库复合外键约束同 owner。异步任务保存独立 owner 快照；Outbox 仍由全局 dispatcher 管理，消费时从任务快照恢复租户上下文。

## 5. 关键数据模型与查询路径

```text
Authentication / SecurityContext or AsyncTask owner snapshot
          │ trusted userId
          ▼
TenantContext / Hibernate tenant resolver → JPA @TenantId filter
           │                                  │
           ├─ explicit owner predicates for raw SQL / vector search
           └─ resource-owned proxy download  → same-owner composite foreign keys
```

必须重点加固的访问链：

- 面试报告/答案：从 `interview_sessions.owner_user_id` 限定会话，再读写 `interview_answers`；评估任务保留会话 owner。
- 简历关联：创建/更新面试时验证 `resume_id` 属于当前用户；跨用户或不存在统一返回不可访问，不泄露资源是否存在。
- 知识库 RAG：客户端只提交知识库 ID；服务端先按当前用户取授权知识库，再用这些 ID 做向量检索。不能直接把任意请求 ID 交给向量库。
- 文件访问：新上传对象 key 使用用户前缀；业务响应不返回对象存储 URL/key，下载由 owner-scoped API 代理。历史对象不搬迁，仍通过已归属数据库记录授权后代理下载；上线前仍须确认 RustFS bucket 不允许匿名读取，否则应先收紧 bucket policy。
- 语音 WebSocket：会话 ID 解析后必须再验证 session owner；握手成功不代表路径中的另一个 session 自动授权。
- 用户限流：RateLimit key 从认证主体生成；移除读取 `X-User-Id` 的 fallback。未登录请求按 IP/匿名桶限流。

## 6. 旧数据迁移与上线顺序

目标数据库 `interview_guide_redesign` 在迁移前有 1 个用户且状态为 `ACTIVE`，有 1 份简历、1 条简历分析、1 个普通面试会话、12 条面试答案和 2 个异步任务。2026-10-08 已生成可恢复的 PostgreSQL custom-format 备份并验证目录可读；`V20260927.4` 先在单事务中完整试跑并回滚，随后由 Flyway 正式执行。当前 Flyway 版本为 `20260927.4`。

该数据库已有唯一 ACTIVE 账号，因此迁移脚本采用“只在唯一账号不变量成立时回填”的受控策略，而不是创建或猜测 Legacy Owner：脚本锁定 `users` 表，要求总用户数和 ACTIVE 用户数均恰好为 1；给私有表加 owner 并回填该用户；将向量 metadata 由知识库映射；若存在无法归属向量则整个迁移失败。必须先备份目标数据库，并确认迁移期间应用不会继续用旧 schema 写入，再通过 Flyway 执行迁移。禁止用手工 DDL 绕过 Flyway。

迁移后校验结果：原有业务记录数量未变；22 个归属表/关联已具备 owner 字段；私有 owner 列均为非空（平台内置 Provider 允许空 owner）；未校验归属约束、无 owner 向量、非法 Provider owner 和非法 RAG 关联均为 0。临时第二账户登录后，简历与面试列表均为空，直接读取原账户的简历 1 和面试会话 1 均按“不存在”拒绝；测试账户随后已删除，数据库恢复为 1 个 ACTIVE 正式账户。RustFS 对象不迁移、不删除；上线前仍需实测确认 bucket 无匿名读取权限，并完成向量、下载、写操作及 WebSocket 的专项越权验收。

迁移任务不得删除原数据，也不得清空 Redis/RustFS。用户删除采用“先禁用并撤销会话，再按数据保留策略异步清理数据库、对象存储、向量索引、缓存和异步任务”的流程；最终清理时必须有可重试的删除状态，不在单个数据库事务中假设外部存储原子性。

## 7. 端到端改造阶段

1. **用户系统基础（已实现，集成部署仍需验收）**：公开注册、邮件验证/重发、密码找回/重置、用户凭据与状态、Spring Security、JDBC 会话 Cookie、CSRF、当前用户 API、前端登录态与退出。管理员引导仍未实现。
2. **根资源归属（已迁移，核心读取已验收）**：简历、普通面试、面试日程、知识库、聊天、语音会话、学习计划及自定义 Provider 已增加 owner 字段/权限边界。
3. **子资源与异步隔离（代码已改，专项安全验收待补）**：答案、分析、消息、题目、提醒、向量、对象存储下载、AsyncTask 和 WebSocket owner 传递及同 owner 约束。Outbox 仍为系统调度数据，通过关联任务继承 owner。
4. **旧数据回填与强制隔离（已完成）**：`interview_guide_redesign` 已先备份，再由 Flyway `V20260927.4` 在唯一 ACTIVE 账户前提下完成回填；数据数量、owner 完整性、约束和核心跨账户读取已经验证。
5. **学习闭环（尚未实现）**：把面试评估的题目标签/知识点证据可靠写入用户学习进度，再基于进度生成或更新个性化学习路线；当前进度表只做 owner 隔离，不代表该自动链路已经打通。

每阶段独立提交。当前工作树包含多个阶段的未提交变更，提交时必须按本阶段文件清单精确选择，不能混入其他历史改动。身份/归属迁移尚未实际验收前，不实现或上线用户学习进度聚合，避免证据主键变化造成跨用户数据混合。后续执行顺序和上下文边界以《后续开发基线与阶段计划》为准。

## 8. 验收标准

- 未登录用户不能读取或修改任一用户私有资源；管理员以外用户不能操作平台级设置。
- 用户 A 知道用户 B 的数字 ID、public UUID、文件 key、session ID 或知识库 ID，也不能读取、修改、删除、下载或重试 B 的数据。
- 请求中的 `userId`、`X-User-Id`、任务 payload owner 与实际认证主体不一致时，不能切换身份。
- 面试/知识库/聊天/语音/学习的父子记录均能追溯唯一 owner；跨用户外键关联会被拒绝。
- RAG 和向量检索不会返回另一个用户知识库中的 chunk；S3/RustFS 文件无法绕过 API 授权直接读取。
- WebSocket 按会话 owner 隔离；限流用户键来自认证主体。
- 登录、登出、CSRF、邮箱验证、重复/过期/重放 token、密码重置撤销会话以及多实例 session 存储有覆盖测试。当前新增认证单元测试覆盖注册哈希、邮箱激活和认证状态；租户测试覆盖 Hibernate 不触发延迟 SecurityContext、请求级 owner 建立和异常清理；真实 SMTP 仍需集成验收。
- 旧数据映射到明确 Legacy Owner，回填幂等，迁移完成后 ownerless 私有根资源为零；共享目录仍为 SYSTEM 所有。
- 用户级学习证据唯一键包含 owner；重复评估任务不会重复累积掌握度，也不会跨用户聚合。

## 9. 待确认事项

尚待后续阶段确认：

1. 公开自主注册、邮箱验证、密码找回的产品选择已确认；SMTP 参数由部署环境提供。初始管理员引导及禁用/删除账号的后台流程仍待设计。
2. 目标数据库已在唯一 ACTIVE 账户条件下完成 ownership migration；后续环境部署仍必须各自备份并验证迁移前置不变量，不能复制本地验收结论代替环境验收。
3. 平台级 LLM 默认配置是否继续由管理员维护；用户自定义 Provider 是否仅本人可见。
4. 暂不支持组织/团队共享。若必须多人共享知识库或学习路线，应在上线前增加 Workspace/Member 模型，而不是让用户间直接共享 owner。
