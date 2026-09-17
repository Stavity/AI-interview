# Kafka 后台任务改造

分支：`redesign-kafka`。本次替换简历分析、知识库向量化、普通面试评估、语音评估、知识库题目生成五条 Redis Streams 链路。Redis 会话缓存和锁继续使用。聊天流、语音实时 ASR/TTS 不进入 Kafka。

## 运行方式

本地依赖仍需 PostgreSQL/pgvector、Redis、RustFS 和现有 AI 配置。增加 Kafka：

```bash
docker compose -f docker/compose.kafka.yml up -d
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./scripts/run-backend-java25.sh
```

Compose 只供宿主机启动后端的本地环境使用；容器间部署必须改 advertised listeners，不能让后端容器连接自己的 localhost。生产环境使用已有集群、认证和 ACL，不使用本地单节点配置。

Flyway 自动执行 `V20260911__add_async_task_and_outbox.sql`，新增 `async_task`、`task_outbox` 和索引。不要手动执行迁移绕过 Flyway。未对现有业务记录、Redis 数据或向量数据做部署时的自动删除。

## 运行协议

1. 业务状态、输入快照、任务、Outbox 在同一 PostgreSQL 事务提交。API 成功表示任务已可靠接受；Kafka 故障不影响已入库任务的后续恢复。
2. Outbox 投递器用短事务和 `FOR UPDATE SKIP LOCKED` 领取通知；事务外发送。收到 Kafka Broker 确认后才标记 SENT。投递租约到期可接管，确认后的进程崩溃允许重复发送。
3. Kafka 每条记录同步调用统一执行器，关闭自动提交 offset，record ACK。实际 AI 工作在可中断的虚拟线程上运行，Listener 等待最终任务状态落库后返回。
4. 领取任务时锁定任务行、增加执行次数和执行版本。重复消息遇到有效 RUNNING/终态直接返回。数据库不可用则抛异常，不提交 offset。
5. 心跳续租受最大执行期限限制。恢复或超时使旧执行失效；最终写入必须同时验证任务状态、执行版本、租约和期限。
6. AI 调用不持有数据库事务。结果写入、业务状态和任务 SUCCEEDED 在同一短事务提交。题目替换和向量激活也在这个边界内。
7. 初次执行加三次重试，共最多四次。失败事务同时登记 RETRY_WAIT 与未来可投递的 Outbox，退避为 30s/120s/600s 加 0–9s 抖动。取消/参数错误/确定的鉴权及资源不存在错误不持续重试。
8. 重试耗尽时业务 FAILED 与死信通知 Outbox 同事务提交。最终失败通知不包含正文和原始异常。协议非法、JSON 错误由 Kafka 错误处理器发送 DLT，DLT 发送失败不确认原消息。其他基础设施异常持续退避，不能因重试耗尽丢任务。
9. 每分钟恢复过期 RUNNING；对于超过五分钟没有进展的 QUEUED/RETRY_WAIT，仅在没有未发送业务通知时补发。所有重试保留同一个 taskId，用户重新执行会生成新的 taskId。

### 输入与副作用

- 简历/向量化正文仅保存于任务表 JSONB，不进入 Kafka。
- 普通面试保存问题、答案、简历文本和 Provider 名称快照；语音评估保存问答、参考材料、角色、开始时间和 Provider 名称。
- 题目生成保存配置快照；检索上下文仍从当前知识库读取，不能承诺知识库变更前后的检索结果完全相同。
- Provider 名称固定，Provider 的管理配置仍可调整，不复制 API key 到任务表。
- 向量化临时 jobId 使用 taskId + executionVersion，成功激活受统一事务保护。失败仅清理本次执行的临时数据；崩溃遗留数据增加时间戳便于后续核查，本次不启用定时删除。
- Kafka 至少一次投递不保证外部模型只计费一次。超时或网络不确定时可能重复 AI 调用，但旧执行不能覆盖数据库结果。

## 配置

| 配置 | 默认 | 含义 |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Broker 地址 |
| `TASK_CONSUMER_ENABLED` | true | 启用五类 Listener |
| `TASK_DISPATCHER_ENABLED` | true | 启用 Outbox 投递 |
| `TASK_RECOVERY_ENABLED` | true | 启用任务恢复 |
| `KAFKA_TASK_PARTITIONS` | 3 | 每个任务 Topic 分区数 |
| `KAFKA_TASK_REPLICAS` | 1 | 开发默认；生产有三个 Broker 时设 3 |
| `app.tasks.concurrency.<route>` | 1 | 各类消费者每实例并发 |
| `app.tasks.execution-timeout` | 20m | 每次任务最大执行时长 |
| `app.tasks.execution-timeouts.<TASK_TYPE>` | 继承全局 | 按任务类型覆盖超时 |
| `app.tasks.lease-duration` | 2m | 执行租约 |
| `app.tasks.heartbeat-interval` | 20s | 心跳周期 |
| `app.tasks.max-attempts` | 4 | 总执行次数 |

默认 `max.poll.interval.ms=1800000`（30 分钟），每次 poll 一条。启动检查限制任务超时不超过 25 分钟并要求租约至少两倍心跳间隔。增加 Listener 并发前先确认 LLM/Embedding 的配额；多个实例的总消费并发受分区数限制。

任务 Topic 为 `ai-interview.<route>.v1`；route 分别为 `resume-analyze`、`kb-vectorize`、`interview-evaluate`、`voice-evaluate`、`question-generate`。消费组为 `ai-interview.<route>.worker.v1`。DLT 为 `ai-interview.task-dlt.v1`。Key 为任务类型 + 业务 ID。副本数至少 3 时 Topic 配置 min.insync.replicas=2，否则为 1。

生产需按峰值积压与回放窗口确定 Kafka 保留时间，配置 SASL/TLS 与 Topic ACL。历史任务包含业务输入快照；本次没有默认数据库清理，保留期限和清理范围需单独确定。

## API 兼容

原 API 路径和响应状态枚举不变。QUEUED/RETRY_WAIT 映射 PENDING，题目生成映射 QUEUED；RUNNING 映射 PROCESSING；SUCCEEDED 映射 COMPLETED。重复提交活跃任务拒绝，新任务不会重置旧任务的执行版本。题目生成沿用现有 questionGenTaskId 作为统一 taskId。

语音评估状态以数据库为准，避免缓存返回旧状态。旧的语音 PENDING/PROCESSING 恢复扫描已移除，超时会话结束仍保留并提交统一任务。

## 切换步骤

1. 部署 Kafka 并确认 Topic/权限；备份数据库，准备新版本和回滚版本。
2. 暂停新异步任务入口，旧版本继续消费。核对五个 Stream 的未消费记录、Pending 和业务 PROCESSING 状态，等待执行完成。
3. 停止旧消费者，确认所有旧执行者均已退出，再启动新版本。新版本不会自动导入旧 Stream 内容，因此**有遗留任务时不能直接切换**。
4. 若无法排空：先导出遗留任务清单并核对业务状态；逐项建立受控补投方案，正文缺失时从原文档重新提取。题目生成的旧 QUEUED/PROCESSING 必须迁移任务 ID 与配置，不能仅删除 Redis 消息或直接重置状态。此分支未提供自动遗留消息搬运器。
5. 使用五类业务各提交一个任务，核对 Outbox SENT、任务 SUCCEEDED、业务结果和前端状态，再开放流量。
6. 保留旧 Stream 数据供核对，本次代码不会自动清理 Redis。

禁止双写两套队列，也不能在 Kafka Worker 仍运行时启动旧消费者。回滚需暂停新提交和 Worker，先导出新任务表中的活跃任务并明确恢复方案；直接回滚旧版本会遗留它不认识的任务。

## 监控与排障

新增 Prometheus gauge：`async_tasks{state=...}`、`async_outbox_unsent`、`async_outbox_oldest_seconds`。同时观察 Spring Kafka 原生生产/消费指标和各消费组 lag。

- Outbox 最老可投递记录持续超过 60s：检查 Broker/ACL/投递器、SENDING 租约与日志 eventId。
- RUNNING 超过配置期限：检查恢复器是否启用、数据库锁和心跳；不要手工让旧 worker 绕过执行版本。
- FAILED/DLT：通过 taskId 查输入与执行次数，修复原因后经原业务入口重新提交；原终态消息重放不会强制重新执行。
- 日志仅记录 taskId、类型和异常类型。执行错误的详细分析需结合对应业务日志，避免向前端或 DLT 输出模型凭证及完整简历。

## 验证

```bash
JAVA_HOME=/usr/local/opt/openjdk@25 ./gradlew :app:test
# 有 Docker 时会实际运行 PostgreSQL + Kafka 的集成测试
JAVA_HOME=/usr/local/opt/openjdk@25 ./gradlew :app:test --tests '*KafkaTaskIntegrationTest'
```

测试覆盖事务回滚、输入快照、活跃任务防重、过期执行版本、心跳期限、重试/死信状态、Broker 发送失败和协议校验。真实 Kafka/PostgreSQL 测试验证迁移 SQL、并发领取、租约恢复和 Outbox → Kafka → 业务结果链路；没有 Docker 时明确跳过，不以 H2 代替 PostgreSQL 原生锁测试。
