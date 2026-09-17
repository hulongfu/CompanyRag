# 回答评估接入生产调用方 + 落库 + 在线/手动/查看接口设计

> 日期：2026-09-16
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置：`2026-09-14-answer-evaluator-design.md`（阶段 0 打通 toolContext、三维评估器、Redis 缓冲层）与 `2026-09-15-answer-evaluator.md`（实现 10 Task）已完成。本 spec 在此基础上，将评估能力从「无生产调用方 + 仅 Redis」升级为「可落库 + 在线自动评估 + 手动/查看接口」。
> 已确认决策：在线自动评估（配置开关 + 异步）+ 手动/查看接口两者都要；并采纳「在线评估同时落库 + Redis 双写」方向（仅 Redis 时查看接口无意义）。

## 1. 目标

为回答评估链路补上生产调用方与可见性，形成完整闭环：

1. **落库持久化**：新增按租户 schema 的评估结果表 `answer_eval_result`，双写（Redis 即时缓冲 + PG 正式落库），供查看接口与后续反馈信号源读取。
2. **在线自动评估**：`chat` 主链路（受开关控制、默认关）在回答生成后**异步**触发评估，不阻塞/不影响主回复。
3. **手动评估与查看**：提供手动批量评估、按查询/时间范围查询、统计接口，便于质检与调试。

**约束：**
- `chat` 主链路默认行为**不变**；在线自动评估默认关闭，开启后也须**异步**执行，异常/失败不回抛、不阻断主回复。
- 复用既有二维评估实现与 `AnswerEvaluationService` 聚合逻辑，不重写判定规则。
- 多租户隔离沿用既有 `tenant_%` schema 隔离 + `X-Tenant-Id` 头；查询/写入均按租户隔离，防越权。
- 对外返回统一 `R<T>` 结构；接口鉴权沿用 `@PreAuthorize("isAuthenticated()")`。

## 2. 现状回顾

- 评估链路已具备：`AnswerCase(query, context, answer)`、`AnswerEvaluator` 接口、三个维度评估器（`AnswerRelevancyEvaluator` / `AnswerCorrectnessEvaluator` / `AnswerFaithfulnessEvaluator`）、共享 `FaithfulnessChecker`、`AnswerEvaluationService.evaluate/evaluateAll`。
- `AnswerEvaluationService.writeToRedis` 仅写 Redis（`RMapCache`，键 `company:rag:eval:{tenantId}:{queryHash}`，TTL 24h），**无任何生产调用方**，评估结果只能在 Redis 里看到临时的值，无法按时间/查询检索历史。查看接口若只读 Redis 将无意义。
- `ChatController.chat()` 已持有回答与上下文：`result.getAnswer()`、`result.getToolContext()`（真实检索上下文）、`savedRowId`（`saveConversation` 返回的 `rag_session` 行主键）、`verifiedTenantId`、`verifiedUserId`。在线触发所需的 query/context/answer/sessionRowId/tenantId 全部可得。
- 多租户 DDL：新租户在 `TenantServiceImpl.createTenantSchema` 建表，存量租户在 `SchemaMigrationConfig` ApplicationRunner 迁移补列。
- 反馈机制：用户 👍/👎 写 `rag_session.feedback` 列，按 `tenantId + userId + sessionId + sessionRowId` 定位单行。

## 3. 架构设计

### 3.1 落库表结构（关键决策）

在**每个租户 schema** 下新增 `answer_eval_result` 表（对齐 `rag_session` 的位置）：

```sql
CREATE TABLE IF NOT EXISTS %s.answer_eval_result (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    session_row_id BIGINT,               -- 关联 rag_session.id（在线评估来源，可空）
    query TEXT,
    context TEXT,                        -- 评估时使用的检索上下文快照
    answer TEXT,
    pass BOOLEAN NOT NULL,               -- 综合是否通过
    score DOUBLE PRECISION NOT NULL,     -- 综合评分 0~1
    relevancy_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    correctness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    faithfulness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL,         -- online / manual
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_%s_answer_eval_tenant_time
    ON %s.answer_eval_result (tenant_id, create_time DESC);
```

要点：
- **`session_row_id`**：在线自动评估时关联 `rag_session.id`，便于与存量用户 `feedback` 在同一把 key 上关联对比（对齐既有 key 语义）。
- **`source`**：标识评估来源（`online` 在线自动 / `manual` 手动批量）。仅需二值，不引入 offline 值以免过度设计。
- **维度分数平铺三列**：便于直接 SQL 统计与查看，避免 JSON 解析；`dimensionScores` 内的 `relevancy/correctness/faithfulness` 落三列。
- **不设删除/归档策略**：本版为质检记录，量小，暂不引入分区/归档；观察表膨胀风险留作观察项。

### 3.2 持久化与数据流

采用与 `RagSession` 一致的 MyBatis-Plus 模式：

- `AnswerEvalResultEntity`（`@TableName("answer_eval_result")`）+ `AnswerEvalResultMapper extends BaseMapper`（`@Mapper`）。
- `AnswerEvaluationService` 改造：
  - `evaluate()` **保持纯评估 + 写 Redis 语义（不落库、不抛异常）**——与既有 0915 测试契约兼容（🔴2 修复）；需要落库的走 `evaluateAllPersisted()`（强制校验 `tenantId`，`null` 直接拒绝并计入失败告警，不抛给调用方）。DB 写失败仅记日志、**不回抛**（在线/手动路径不阻断主回复）。
  - 新增读取方法：`findByQuery(tenantId, query)`、`listResults(tenantId, from, to, limit)`、`stats(tenantId, from, to)`。
  - Redis 写维持在线上，作为即时缓冲（查看接口也可优先走 Redis 快速命中，但历史数据以 DB 为准）。

### 3.3 `AnswerCase` 扩展

**关键决策：`tenantId` 显式入参，绝不依赖跨线程 ThreadLocal。** 在线异步评估线程的 `TenantContext` ThreadLocal 恒为 null（`ChatController.chat()` 在 finally 已 `TenantContext.clear()`），若落库从 `TenantContext.getTenantId()` 取值将落到 `tenant_id=0`，且新表 `FORCE ROW LEVEL SECURITY + tenant_id=current_tenant_id()` 会使其对真实租户永久不可见（与 `TenantServiceImpl` L90-94 注释同机制）。因此 `AnswerCase` 同步扩展 `tenantId`，落库/写 Redis 一律取显式值。

`AnswerCase` record 扩展（带默认值，保持既有 3 参构造兼容）：

```java
public record AnswerCase(
    String query, String context, String answer,
    Long tenantId,       // 显式租户 ID（在线取 verifiedTenantId / 手动取 X-Tenant-Id），防跨线程丢失
    Long sessionRowId,   // 关联 rag_session.id，可空
    String source        // online / manual，默认 "manual"
) {
    public AnswerCase(String query, String context, String answer) {
        this(query, context, answer, null, null, "manual");
    }
}
```

> 注意：`AnswerEvalResult` 是纯数据，"tenantId/sessionRowId/source" 属**落库元数据**，不入 `AnswerEvalResult`；由 `AnswerEvaluationService` 落库时从 `AnswerCase` 取显式值。此举避免污染既有 `AnswerEvalResult` 结构，同时让跨线程落库不再依赖 ThreadLocal。

### 3.4 在线自动评估触发（ChatController）

在 `ChatController.chat()` 中，**`saveConversation` 之后、`return R.ok` 之前**插入（受配置控制）：

```
若 rag.eval.online-enabled == true 且 (request.getSessionId() != null 且 savedRowId != null)：
    // 在 finally TenantContext.clear() 之前，先捕获 verifiedTenantId 等作为显式入参
     构造 AnswerCase(query=request.getQuery(),
                    context=result.getToolContext(),
                    answer=result.getAnswer(),
                    tenantId=verifiedTenantId,   // 显式传入，不依赖异步线程 ThreadLocal
                    sessionRowId=savedRowId,
                    source="online")
     经专用有界线程池异步调用 answerEvaluationService.evaluateAllPersisted(List.of(case))（写 Redis + 落库，失败剔除不抛）
     异常仅记日志，不影响主回复
```

要点：
- **异步**：使用独立线程池（有界 + `AbortPolicy` 丢弃拒绝任务并告警），避免评估耗时拖慢主回复。评估线程池与 `RagAgentService` 的 agent 线程池分开，避免共池被打爆。
- **`AnswerEvaluationService` 可选注入（🔴 冲突修正）**：`ChatController` 用 `@RequiredArgsConstructor`（`private final`=构造必填）。若把评估服务设为必填构造注入，则 `rag.eval.enabled=false`（Task 9 给评估服务加 `@ConditionalOnProperty`）时该 bean 不存在 → `ChatController` 构造失败 → 整个 `/api` 启动即死，违背「真正可关开关」目标。**故在 `ChatController` 中用 `@Autowired(required=false)` 字段注入评估服务**，在线分支 `answerEvaluationService != null` 才触发评估；`enabled=false` 时注入 null、主链路正常启动、仅在线评估不执行。
- **线程安全（铁律配套）**：评估线程的 `TenantContext` ThreadLocal 恒为 null（主线程 finally 已 clear）。**落库/写 Redis 一律取 `AnswerCase.tenantId()` 显式值**，不依赖评估线程 ThreadLocal。日志链路（traceId）如需保留，可在提交前捕获并用 `TenantContextSnapshot.captureNow()/apply()/clear()`（项目类，仅此三个方法）在任务内恢复与清理；**不要使用 Micrometer `ContextSnapshot.setThreadLocals()`**。
- **仅对带 sessionId 的会话触发**：无 sessionId 时 `savedRowId` 为 null，且 `saveConversation` 未落 rag_session，评估落库也缺乏定位语义，故跳过。
- 在线评估本身是**规则式**（三维均为本地规则判定，无额外 LLM 调用），成本低、适合在线路径。若未来某维度引入 LLM 判别，必须套熔断/降级（铁律）。

### 3.5 手动评估与查看接口（新增 EvalController）

新增 `EvalController`（web 模块，`@RequestMapping("/api/eval")`），全部 `@PreAuthorize("isAuthenticated()")` + 从 `X-Tenant-Id` 头取租户：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/eval/run` | 手动批量评估：请求体为 `query/context/answer` 列表，强制 `source="manual"`、`tenantId`=请求头、`sessionRowId=null`；返回落库后 `AnswerEvalResultEntity`（与查看接口类型一致） |
| GET | `/api/eval/result` | 按 `query`（精确文本匹配）查某 query 最新一条评估结果；`findByQuery` 不依赖 query 哈希，纯精确匹配 |
| GET | `/api/eval/results` | 按时间范围 + `limit` 分页查列表 |
| GET | `/api/eval/stats` | 按时间范围统计（总数 / pass 率 / 平均分 / 各维度均分） |

安全要点：
- **租户隔离**：所有读/写都强制带上端到端租户过滤；`X-Tenant-Id` 缺失直接拒绝（与 `ChatController` 一致）。
- **越权防护**：查询接口按 `tenant_id == headerTenantId` 过滤，防止跨租户用 `id`/`query` 探测他人评估数据。

### 3.6 配置开关

`application-dev.yml` / `application.yml` 的 `rag:` 段新增：

```yaml
rag:
  eval:
    enabled: ${RAG_EVAL_ENABLED:true}           # 评估组件总开关：@ConditionalOnProperty 控制评估器/Service 与 EvalController 是否装配
    online-enabled: ${RAG_EVAL_ONLINE_ENABLED:false} # 是否接入 chat 在线自动评估（默认关，先手动验证）
    async-enabled: true    # 在线评估是否异步
```

> **接线说明（消除死配置）**：`rag.eval.enabled` 必须真正接线——在装配评估组件处加 `@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true", matchIfMissing = true)`（评估器实现、`AnswerEvaluationService`、`EvalController`）。**注意例外（🔴 冲突修正）**：`AnswerEvaluationService` 加该注解后，`enabled=false` 时不再是 bean；因此 `ChatController` 中对其必须用 `@Autowired(required=false)` 可选注入（而非 `@RequiredArgsConstructor` 必填构造注入），并在在线分支判空，否则主链路会因缺少该 bean 而启动失败。`online-enabled` 由 `ChatController` 通过 `@Value` 读取，仅控制在线触发分支；`async-enabled` 控制在线触发是走异步线程池还是当前线程同步（默认 true，同步仅用于调试）。

### 3.7 与既有机制的边界

- **不触碰**既有 `/api/chat/feedback` 与 `rag_session.feedback` 列；自动评估分**不**写入 `feedback` 列（阶段 3 feedback 信号源再联动）。
- `AnswerEvaluationService.evaluateAll` 保留；手动接口经其批量执行。
- 保留既有 Redis 缓冲层（双写，不删）；查看接口以 DB 为最终数据源，Redis 仅作即时命中加速（可留待后续，本版可先只查 DB，避免双读复杂度）。

## 4. 数据流

1. **在线**：`chat` → 生成 answer → `saveConversation` 得 `savedRowId` →（开关开）异步评估 → 写 Redis + PG 落库(`source=online`，带 `session_row_id`)。
2. **手动**：`POST /api/eval/run` → `evaluateAllPersisted(source=manual)` → 写 Redis + PG 落库（`tenantId` 强制校验，null 拒绝）。
3. **查看**：`GET /api/eval/result|results|stats` → 按租户查询 DB（Redis 可选加速）→ 返回 `R<T>`。

## 5. 测试策略

- **落库/双写单测**：mock `RedissonClient` 与 `AnswerEvalResultMapper`（service 构造器由 4 参 → 5 参，既有测试 `AnswerEvaluationServiceTest` 的 setUp 需同步补齐 mapper 参数——🔴2 回归修复），验证 `evaluateAllPersisted()` 落库、tenantId=null 剔除不计抛、DB 写失败不回抛、读取方法过滤租户；既有三参 `evaluate()` 用例按「纯评估 + Redis」契约原样通过。
- **控制层测试**：`EvalController` 各端点鉴权、租户头缺失拒绝、越权过滤。
- **在线触发测试**：`ChatController` 在开关开/关两态下行为（开则异步触发且不阻塞主回复；关则零影响），mock agent 各依赖。
- 验证命令最窄范围：`company-rag-rag` / `company-rag-web` 相关测试类；编译用根 reactor 联合编译（避免单模块读到本地仓库旧 common 快照误报）。

## 6. 改动清单

- **数据库**（首次向既有结构新增表）：
  - 修改 `TenantServiceImpl.createTenantSchema`：在 `%s.answer_eval_result` 建表 + 索引（新租户）。
  - 修改 `SchemaMigrationConfig`：新增 ApplicationRunner，为存量 `tenant_%` schema 幂等建 `answer_eval_result` 表 + 索引。
  - 修改 `sql/init.sql`（如存在同表定义）：同步存档。
- **rag 模块持久化**：
  - Create `.../rag/eval/answer/AnswerEvalResultEntity.java`（`@TableName("answer_eval_result")`）。
  - Create `.../rag/eval/answer/AnswerEvalResultMapper.java`（`BaseMapper`）。
  - Modify `.../rag/eval/answer/AnswerCase.java`：扩展 `sessionRowId`、`source` 字段（带默认变参构造，保持原三参构造）。
  - Modify `.../rag/eval/answer/AnswerEvaluationService.java`：`evaluate` 保持纯评估+Redis；新增强制落库 `evaluateAllPersisted` + 读取方法（`findByQuery` / `listResults` / `stats`）。
- **web 模块接口**：
  - Create `.../web/controller/EvalController.java`：4 个端点 + 租户头校验。
  - Modify `.../web/controller/ChatController.java`：在线触发（开关 + 异步线程池）。
- **配置**：修改 `application-dev.yml`（与 `application.yml` 若含 `rag` 段则同步）。
- **测试**：`AnswerEvaluationService` 落库/读取测试、`EvalController` 测试、（可选）`ChatController` 在线开关测试。

## 7. 风险与观察项

- **DB 写失败**：双写中 DB 失败仅记日志不回抛，可能导致结果只在 Redis 而查不到——可接受（在线路径绝不阻断主回复）；观察告警。
- **多租户隔离**：建表/索引必须 `schemaName` 白名单校验（`^[a-zA-Z_][a-zA-Z0-9_]*$`，复用 `TenantServiceImpl` 既有校验），防 SQL 注入；查询必须 `tenant_id == X-Tenant-Id`。
- **表膨胀**：供查看的质检记录只增不删，长期量大会膨胀；本期不设清理，留观察项。
- **跨线程租户（最高优先）**：在线异步评估必须**显式携带 `tenantId`（`AnswerCase.tenantId`）落库**，不依赖评估线程 `TenantContext` ThreadLocal（该值恒为 null，会导致落 `tenant_id=0` 且被 RLS 过滤永久不可见）。Redis 缓存键同样建议用显式 tenantId 构造，保持一致性。
- **context 一致性**：在线评估的 `context` 取 `result.getToolContext()`（生成回答所用的真实检索上下文），与回答一致，faithfulness 才不失真。
- **在线开关默认关**：先手动接口验证评估质量与稳定性，再手工打开在线开关灰度。