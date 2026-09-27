# TRD：用户系统与全域数据归属设计

> 状态：设计评审稿，尚未实现
> 日期：2026-09-27
> 目标：建立可信用户身份，并将用户产生或保存的业务数据隔离归属到用户；作为题目学习闭环的前置阶段

## 1. 设计结论

本项目先按“单用户即一个数据租户”实现，不引入团队、组织或共享工作区。所有用户私有数据必须有可验证的归属路径；请求参数、路径参数、消息体、`X-User-Id` 等客户端自报值都不能作为身份依据。

建议第一版采用 Spring Security 的服务端会话认证：浏览器使用 `HttpOnly` 会话 Cookie，状态变更请求保留 CSRF 防护；应用从认证上下文取得内部 `userId`，再用带 owner 条件的仓储查询访问资源。移动端或第三方 API 尚不在当前范围；将来确有需要时再增加 OAuth2/OIDC 客户端或访问令牌，不在浏览器中自行实现 JWT 生命周期。

用户私有数据的归属采用两种方式：独立资源根表直接保存 `owner_user_id`；明细数据通过外键沿父资源继承所有者。异步任务、独立检索索引等脱离父请求执行的记录也要保存用户归属快照。平台共享字典、题目知识树和平台级设置是 `SYSTEM` 所有，不伪造一个普通用户作为所有者；如需审计修改人，另记 `created_by_user_id` / `updated_by_user_id`。

## 2. 当前实现盘点

代码与迁移显示当前尚无用户表、登录/注册接口、Spring Security 依赖或安全过滤链；前端请求层也没有登录态、认证或 CSRF 处理。除语音面试外，主要业务表没有用户归属列。

现有语音面试仅有 `voice_interview_sessions.user_id VARCHAR`：创建时服务端固定写入 `"default"`，查询接口允许客户端传 `userId`，这不是认证身份。限流切面还会读取可伪造的 `X-User-Id` 请求头作为用户维度键。两者在用户系统落地时都必须移除。

现有跨域配置允许带凭证，开发前端默认在 `localhost:5173`；认证落地需要在此基础上加入精确来源、Cookie 和 CSRF 配置，而不是把 CORS 当作授权。当前不存在覆盖所有接口的资源所有权检查。

## 3. 用户与认证模型

### 3.1 核心实体

建议使用内部递增 `BIGINT id` 作为数据库外键，并提供不连续的 `public_id UUID` 作为公开 API 标识。公开 ID 不替代 owner 校验；每次访问仍必须验证资源归属。

```text
users
  id, public_id, email_normalized, display_name,
  status, role, created_at, updated_at, deleted_at

user_password_credentials
  user_id, password_hash, password_updated_at, failed_attempts, locked_until

user_external_identities (预留，可后续启用)
  user_id, provider, provider_subject, created_at
  UNIQUE(provider, provider_subject)
```

`users.status` 至少包含 `INVITED`、`ACTIVE`、`LOCKED`、`DISABLED`、`DELETED`；第一版角色为 `USER`、`ADMIN`。密码只保存使用 Spring Security `PasswordEncoder` 生成的带算法标识哈希，绝不保存明文或可逆密文。采用可升级的密码编码器，并对登录失败做限速和短期锁定。[密码存储文档](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) [PasswordEncoder 文档](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/password-encoder.html)

初始运维账号由显式部署密钥/一次性初始化流程创建；仓库和数据库迁移中不得写入默认密码。推荐第一版默认关闭公开注册，由管理员邀请/创建账号。若产品决定开放注册，需要同时明确邮箱验证、密码找回和邮件服务，不能仅增加一个匿名注册接口。

### 3.2 会话与浏览器安全

- 登录成功后由服务端创建会话并轮换会话 ID；登出、禁用账号、密码重置时撤销对应会话。
- 会话 Cookie 使用 `HttpOnly`、生产环境 `Secure`、适当的 `SameSite` 和短空闲/绝对有效期；生产多实例部署使用共享会话存储，优先复用现有 Redis 基础设施并配置 TTL。当前项目使用 Redis/Redisson 不等于已启用 Spring Session；实施时需新增 Spring Session Redis 集成，并验证其连接配置、序列化、TTL 和现有 Redis 用法互不冲突。
- 对 SPA 提供 CSRF token 获取方式；前端在 `POST/PUT/PATCH/DELETE` 请求中带 CSRF header。认证成功和登出后按 Spring Security 的 SPA 流程重新获取 token。
- CORS 仅允许部署配置中的精确 Origin；跨站 Cookie 如确有必要才使用 `SameSite=None; Secure`，首选同站点反向代理部署。
- 语音 WebSocket 握手使用已认证 HTTP 会话；连接建立时检查该 `sessionId` 属于当前用户。不得把认证凭证或 `userId` 放进 WebSocket URL。
- `/api/auth/me` 返回当前用户公开信息；认证来源是 Spring Security `Authentication`，而非客户端传来的 ID。

建议接口：

```text
GET    /api/auth/csrf
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/auth/me
POST   /api/admin/users                 管理员创建/邀请用户（第一版）
PATCH  /api/admin/users/{publicId}/status
```

Spring Security 官方提供 HTTP Session 中的认证持久化机制，也为扩展到缓存/数据库会话存储保留了配置点；SPA 使用 Cookie 会话时必须保留 CSRF，并按认证/登出事件刷新 token。[会话管理文档](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) [SPA CSRF 文档](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

### 3.3 授权规则

1. 所有私有 API 必须认证；仅登录、登出、CSRF、静态资源和明确的健康检查匿名开放。
2. Controller 从认证主体取得 `userId` 并向 Service 显式传递；Service/Repository 查询包含 `owner_user_id`，例如 `findByPublicIdAndOwnerUserId(...)`。
3. 不允许先 `findById(id)` 再假定调用者有权访问。所有读取、更新、删除、下载、状态切换和异步重试都执行 owner 校验。
4. 建立资源关系时校验双方同属当前用户；不能把 A 用户的面试关联到 B 用户的简历、知识库或会话。
5. 使用 `public_id` 降低顺序 ID 暴露，但不得将“ID 不可猜”当成访问控制。
6. `/api/learning/**`、知识库、简历、面试、聊天、LLM Provider 配置以及语音 REST/WebSocket 全部应用同一认证与资源隔离规则。

## 4. 数据归属矩阵

| 领域/数据 | 归属方式 | 设计要求 |
|---|---|---|
| 简历、简历分析 | `resumes.owner_user_id`；分析经 `resume_id` 继承 | 上传文件对象 key 按用户/简历隔离；分析、导出和删除都校验 owner |
| 普通面试、面试日程 | `interview_sessions.owner_user_id`、`interview_schedule.owner_user_id` | 会话可以关联同一用户的简历；答案、报告、参考答案随会话继承 owner |
| 知识库、知识库题目 | `knowledge_bases.owner_user_id`；题目、题目标签经知识库/题目继承 | 文件、解析文本、题目生成任务、下载和向量检索都限定授权知识库 |
| RAG 聊天 | `rag_chat_sessions.owner_user_id`；消息、知识库关联经会话继承 | 每个被关联知识库必须与会话 owner 一致 |
| 普通聊天 | `chat_sessions.owner_user_id`；`chat_messages` 经会话继承 | Provider 配置只能选当前用户可用项或平台内置项 |
| 语音面试 | 会话直接归属用户；消息、评估经会话继承 | 替换字符串 `user_id` 和固定 `default`，列表查询不再接受客户端 `userId` |
| 学习计划、任务、提醒 | `learning_plans.owner_user_id`；任务/提醒经计划继承 | 不同用户的计划、进度、提醒互不可见 |
| 学习进度、题目学习证据 | 每条进度/证据带 `owner_user_id` | 唯一键包含用户；面试结果只能更新当前面试所有者的学习数据 |
| 学习技能来源 | 系统共享目录 | 不是用户私有数据；用户掌握度与该目录项的关联由用户进度表表达 |
| 用户自定义 LLM Provider | `llm_provider_config.owner_user_id` | API Key 仍只存加密密文；内置 Provider 为系统所有、只读；读取和调用均校验 owner |
| 平台 LLM 全局设置 | 系统所有 | 仅管理员可改；记录 `updated_by_user_id` 审计操作者，不归属于普通用户 |
| 异步任务与 Outbox | `async_task.owner_user_id`；Outbox 经 `task_id` 继承 | 消费端使用任务中固化的 owner 和资源 ID；重试不得根据不可信 payload 扩权 |
| 向量索引、Redis 缓存、对象存储 | 由用户根资源确定 owner，并在 metadata/key 中保留 owner/resource 标识 | 搜索前先授权资源 ID；缓存 key 按用户/会话命名空间隔离 |
| 题目知识树、别名、Skill scope、版本目录 | 系统所有、全局只读 | 不是用户内容；用户标注关系仍通过所属题目间接归属用户 |

子表不必机械重复 `owner_user_id`，但必须通过有外键约束的父子关系唯一追溯到一个 owner。跨根资源关联表（例如 RAG 会话—知识库）需要在 Service 校验同 owner，并在数据库可行时用复合外键约束防止跨用户关联。对于脱离父记录运行的异步任务/索引，必须保存明确 owner 快照。

## 5. 关键数据模型与查询路径

```text
Authentication / SecurityContext
          │ trusted userId
          ▼
Controller → Service(userId, resourceId) → Repository(resourceId AND owner_user_id)
                                             │
                                             ├─ owned root resource
                                             └─ child rows via FK / same-owner constraint
```

必须重点加固的访问链：

- 面试报告/答案：从 `interview_sessions.owner_user_id` 限定会话，再读写 `interview_answers`；评估任务保留会话 owner。
- 简历关联：创建/更新面试时验证 `resume_id` 属于当前用户；跨用户或不存在统一返回不可访问，不泄露资源是否存在。
- 知识库 RAG：客户端只提交知识库 ID；服务端先按当前用户取授权知识库，再用这些 ID 做向量检索。不能直接把任意请求 ID 交给向量库。
- 文件访问：对象存储不返回永久公开 URL；下载由 API 授权后代理/签发短期签名地址，key 使用不可猜随机标识和用户/资源前缀。
- 语音 WebSocket：会话 ID 解析后必须再验证 session owner；握手成功不代表路径中的另一个 session 自动授权。
- 用户限流：RateLimit key 从认证主体生成；移除读取 `X-User-Id` 的 fallback。未登录请求按 IP/匿名桶限流。

## 6. 旧数据迁移与上线顺序

历史数据当前没有可靠 owner，唯一例外是语音面试的固定 `"default"`。不能把它们自动归给“最先注册的新账号”，否则会造成数据被错误认领或泄露。

采用可回滚的 expand → backfill → enforce 顺序：

1. **备份与盘点**：备份 PostgreSQL、RustFS 对象和必要的向量数据；记录各根表数量、孤儿记录、语音 `user_id` 分布以及 ownerless 资源数量。
2. **扩展结构**：新增用户/凭据表；给用户私有根表加可空 `owner_user_id` 和索引；暂不强制 `NOT NULL`，现有接口行为不在这一步改变。
3. **明确旧数据接收者**：通过部署环境指定唯一 `APP_LEGACY_OWNER_EMAIL`，由一次性初始化流程创建/确认该账号。禁止 SQL 中硬编码密码或默认账号口令。
4. **幂等回填**：在维护窗口或受控后台任务中，把所有旧的无 owner 私有根数据归给该 Legacy Owner；子表沿父表继承；语音 `"default"` 映射到该用户。任务可重跑，输出各表迁移前后计数和未归属计数。
5. **校验后切换**：确认所有私有根资源均有有效 owner、关联没有跨 owner、S3 key/向量 metadata 均可追溯；启用登录和 owner-scoped API，再将必要 owner 列改为 `NOT NULL`。若校验失败则保持维护模式，不对外开放半迁移数据。
6. **后续账号注册**：新用户只能看到自己新建的数据。旧数据不会被开放注册用户自动领取；如需转交，走显式管理员审核和审计流程。

迁移任务不得删除原数据，也不得清空 Redis/RustFS。用户删除采用“先禁用并撤销会话，再按数据保留策略异步清理数据库、对象存储、向量索引、缓存和异步任务”的流程；最终清理时必须有可重试的删除状态，不在单个数据库事务中假设外部存储原子性。

## 7. 端到端改造阶段

1. **用户系统基础**：用户、凭据、状态/角色、管理员引导、Spring Security、会话 Cookie、CSRF、当前用户 API、前端登录态。
2. **根资源归属**：简历、普通面试、面试日程、知识库、聊天、语音会话、学习计划及自定义 Provider 加 owner；所有 API 切到 owner-scoped 查询。
3. **子资源与异步隔离**：答案、分析、消息、题目、提醒、向量、Redis、S3、AsyncTask/Outbox 和 WebSocket 的 owner 传递及同 owner 约束。
4. **旧数据回填与强制隔离**：Legacy Owner 导入、完整性校验、强制 owner、删除伪造 userId/header 的访问路径。
5. **学习闭环**：用户级学习证据/进度/推荐以认证主体 `owner_user_id` 为分区键；面试结果不再按简历、进程全局或匿名 `default` 混合。

每阶段独立提交。身份层尚未完成前，不实现或上线用户学习进度聚合，避免随后迁移学习证据主键并造成跨用户数据混合。

## 8. 验收标准

- 未登录用户不能读取或修改任一用户私有资源；管理员以外用户不能操作平台级设置。
- 用户 A 知道用户 B 的数字 ID、public UUID、文件 key、session ID 或知识库 ID，也不能读取、修改、删除、下载或重试 B 的数据。
- 请求中的 `userId`、`X-User-Id`、任务 payload owner 与实际认证主体不一致时，不能切换身份。
- 面试/知识库/聊天/语音/学习的父子记录均能追溯唯一 owner；跨用户外键关联会被拒绝。
- RAG 和向量检索不会返回另一个用户知识库中的 chunk；S3/RustFS 文件无法绕过 API 授权直接读取。
- WebSocket 按会话 owner 隔离；限流用户键来自认证主体。
- 登录、登出、CSRF、账号禁用、会话失效以及多实例 session 存储有覆盖测试。
- 旧数据映射到明确 Legacy Owner，回填幂等，迁移完成后 ownerless 私有根资源为零；共享目录仍为 SYSTEM 所有。
- 用户级学习证据唯一键包含 owner；重复评估任务不会重复累积掌握度，也不会跨用户聚合。

## 9. 待确认事项

本稿先采用可安全上线的默认值，编码前请确认：

1. 第一版账号创建是否维持“管理员邀请/创建”，而不是公开自助注册；若要自助注册，需同步确定邮件验证和密码找回渠道。
2. 既有本地数据是否全部归入一个显式指定的 Legacy Owner；如果数据库里原本已有多个使用者，需要先提供映射清单，不能用统一回填。
3. 平台级 LLM 默认配置是否继续由管理员维护；用户自定义 Provider 是否仅本人可见。
4. 暂不支持组织/团队共享。若必须多人共享知识库或学习路线，应在上线前增加 Workspace/Member 模型，而不是让用户间直接共享 owner。
