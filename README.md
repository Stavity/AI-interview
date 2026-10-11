# AI Interview Platform

一个面向求职者的智能面试训练平台。项目将大模型能力与结构化面试流程结合，支持简历与知识库驱动的题目生成、对话式面试、语音面试、结果评估，以及个性化学习路径。

平台的目标不是只生成一组孤立的面试题，而是帮助用户完成“准备—模拟—复盘—提升”的闭环：用户上传简历、选择目标岗位与技能方向后，可进行多轮模拟面试；系统会结合回答内容、知识库资料和题目标签评估表现，并据此提供后续学习建议、学习路径和进度跟踪。

## 核心使用流程

```text
注册 / 登录
    ↓
上传简历、选择岗位或技能方向
    ↓
生成并开始模拟面试（文字或语音）
    ↓
实时追问、保存作答与评估结果
    ↓
查看面试记录、能力短板与学习建议
    ↓
按个性化学习路径练习并跟踪进度
```

## 功能概览

- 用户注册、登录、邮箱激活与密码重置
- 简历上传、解析与面试题生成
- 多轮对话式面试与实时反馈
- 语音面试：语音识别、语音播报及作答评估
- 知识库上传、向量化检索与 RAG 问答
- 技能标签、学习进度、个性化学习路径和面试日程
- 可配置的大模型、Embedding 与 Rerank 服务提供商

## 面试与学习能力

### 智能模拟面试

系统支持围绕岗位、简历内容和技能范围生成面试问题，并以多轮对话形式推进面试。面试记录会保存问题、用户答案、评分、评价及会话摘要，便于用户回看表现并持续练习。

### 语音面试

语音面试模块集成语音识别（ASR）和语音合成（TTS）能力，模拟真实口头面试环境。用户可通过语音作答，系统将语音转写为文本后交给评估链路处理，并可语音播报问题或反馈。

### 知识库与 RAG 问答

用户可上传与岗位、技术栈或面试主题相关的资料。后端使用 Apache Tika 解析文档，将内容切分并向量化保存到 pgvector；提问时通过语义检索获取相关片段，再为模型生成回答或面试题提供可靠上下文。

检索链路支持按配置启用候选召回、关键词检索、RRF 融合、Rerank 精排与相邻内容扩展，并在外部能力不可用时回退到基础向量检索。

### 个性化学习

学习模块维护技能目录、知识点进度、学习资源和个性化学习路径。系统可以把面试表现与技能标签关联起来，帮助用户从一次面试复盘延续到可执行的学习计划。

## 技术栈

- 后端：Java 25、Spring Boot、Spring AI、Spring Data JPA、WebSocket、Kafka
- 前端：React 18、TypeScript、Vite、Tailwind CSS
- 数据与存储：PostgreSQL、pgvector、Redis、RustFS（S3 兼容）
- 文档解析与 AI：Apache Tika、DashScope / OpenAI 兼容接口

## 系统架构

前端通过 REST API、WebSocket 与后端交互；后端负责鉴权、业务编排、任务处理和 AI 服务适配。耗时任务可通过 Kafka 异步执行，向量检索数据持久化在 PostgreSQL 的 pgvector 扩展中，对象文件存储在兼容 S3 的 RustFS 中。

```text
React + Vite 前端
        │ REST / WebSocket
        ▼
Spring Boot 后端
 ├── 认证、简历、面试、日程、学习等业务模块
 ├── LLM / Embedding / Rerank Provider 适配层
 ├── Kafka 异步任务与任务状态管理
 └── 知识库解析、向量化与 RAG 检索
        │
        ├── PostgreSQL + pgvector
        ├── Redis
        ├── Kafka
        └── RustFS（S3 兼容对象存储）
```

## 项目结构

```text
.
├── app/                 # Spring Boot 后端
├── frontend/            # React 前端
├── docker/              # 数据库初始化与部署辅助文件
├── gradle/              # Gradle Wrapper 与版本目录
├── scripts/             # 本地开发、测试脚本
└── docs/                # 本地设计与过程文档（不纳入 Git）
```

后端代码按业务领域组织在 `app/src/main/java/interview/guide/modules/` 下，主要包括：

- `auth`：账号认证、会话和密码相关能力
- `interview`、`voiceinterview`：文字与语音面试流程、作答和评估
- `knowledgebase`：资料上传、解析、向量化与检索
- `learning`：学习路径、知识点与进度跟踪
- `llmprovider`：模型服务商与默认模型配置
- `interviewschedule`、`questiontag`、`chat`：面试日程、标签和对话等配套能力

## 本地启动

### 前置条件

- JDK 25
- Node.js（项目使用 pnpm 10）
- PostgreSQL（需安装 `vector` 扩展）
- Redis、Kafka，以及 S3 兼容对象存储（如需完整功能）

### 1. 配置环境变量

在项目根目录创建 `.env`，按需填写数据库、Kafka、对象存储、邮件和 AI 服务配置。`.env` 已被 Git 忽略，请勿提交密钥。

后端默认连接：

- PostgreSQL：`localhost:5432/interview_guide_redesign`
- Kafka：`localhost:9092`
- 服务端口：`8080`

### 2. 启动后端

```powershell
.\gradlew.bat :app:bootRun
```

Windows 以外环境可使用：

```bash
./gradlew :app:bootRun
```

启动后可访问 Swagger：<http://localhost:8080/swagger-ui.html>。

健康检查地址：<http://localhost:8080/actuator/health>。

### 3. 启动前端

```powershell
cd frontend
pnpm install
pnpm dev
```

## 测试与构建

```powershell
# 后端测试
.\gradlew.bat test

# 前端生产构建
cd frontend
pnpm build
```

## 配置说明

主要应用配置位于 `app/src/main/resources/application.yml`。所有敏感配置应通过环境变量或本地 `.env` 注入；请勿将 API Key、数据库密码或邮箱凭据写入版本库。

常见配置项包括：

- `POSTGRES_*`：PostgreSQL 连接信息
- `KAFKA_BOOTSTRAP_SERVERS`：Kafka 地址
- `APP_MAIL_*`：邮箱激活与密码重置所需的 SMTP 配置
- AI Provider 相关配置：模型服务地址、API Key、聊天模型、Embedding 模型与 Rerank 模型
- `APP_SESSION_COOKIE_SECURE`：生产环境 Cookie 的 Secure 标记

## 文档约定

`docs/` 用于保存本地设计稿、调研记录和交接材料，已在 `.gitignore` 中忽略。如需发布长期维护的项目文档，请将其整理到适合纳入版本控制的位置后再提交。
