# RAG 文档入库（ETL）健壮性改造设计（修订版）

> 日期：2026-09-14（修订于 2026-09-16）
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案A**——对本项目自有入库流水线做健壮性改造（异步线程池 + 分步状态机 + 每步独立落库/重试），不引入 Spring AI Alibaba 官方 ETL API。
> 修订说明 v2：按评审落地两个必决决策——(1) **模块边界**：pipeline 不引用 rag 模块的 `TenantContextSnapshot`，将跨线程租户捕获/恢复能力下沉到 `company-rag-tenant`（document 已依赖 tenant），rag 与 document 共用；(2) **schema 边界**：放弃绝对化「不触碰 schema」，明确最小必要 DDL（`document_pipeline_state` 表 + `doc_chunk(document_id, chunk_index)` 唯一约束 + `vector_store.chunk_id` 列），并补迁移/回填/RLS 说明。同时落实评审项 #3 中间状态落点、#4 崩溃恢复、#5 独立事务实现、#6 fail-closed 校验、#7 文件生命周期与删除竞态、#8 事件时机。

## 1. 目标

在不改变对外接口与安全边界的前提下，把当前 `DocumentParseServiceImpl` 同步单事务的「解析→切分→入库→向量化」流水线，改为**异步、分步可重试、状态可观察**的健壮管线。解决单个大文件解析占满请求线程、长流程一个失败整体回滚、无法观测中间态等问题。

**约束：**
- 不引入官方 `ETL` API / starter，只用本项目现有异步能力与既有存储。
- 保持 `DocumentService` 接口与上层 Controller 调用不变（上传接口仍同步返回任务，解析改后台）。
- 保留现有多租户隔离、文件大小 iron-rule（`MAX_FILE_SIZE`）、日志安全（不落敏感内容）。
- 向量化维度 1024 / COSINE / HNSW 等铁律天然满足。
- **schema 允许最小必要变更**：仅三处 DDL（`document_pipeline_state` 表、`doc_chunk` 唯一约束、`vector_store.chunk_id` 列），且必须以迁移脚本落地并处理存量数据（见 §5.3），其余数据层结构不动。

## 2. 现状回顾

`DocumentParseServiceImpl.uploadAndParse` 当前为一条**同步、单 `@Transactional`** 顺序流水线：

```
接收上传
  → Tika 解析文本
  → 智能切分（三种策略）
  → chunk 逐条落库
  → 向量化（Embedding → PGVector 入库）
  → 更新文档状态 / 触发事件回调
  → 返回
```

**问题：**
- 大文件 Tika 解析 + 向量化（调用 LLM Embedding）同步占用 HTTP 请求线程，首字节响应慢。
- 单事务：切分后任一步失败（尤其向量化网络抖动）整体回滚，已解析文本白扔，重试成本高。
- 无中间状态可观测：只有最终成功/失败，无法定位卡在解析、切分还是向量化。
- 无重试：向量化偶发失败直接导致整个文档入库失败。

## 3. 架构设计

**核心思路：** 把原单事务流水线拆成**独立步骤**，每步用状态机驱动、独立落库、支持幂等重试；长流程交给异步线程池执行，请求线程只负责接收并返回任务 id。

### 3.1 新增组件

| 组件 | 职责 | 依赖 |
|---|---|---|
| `TenantContextSnapshot`（**下沉至 `company-rag-tenant`**） | 跨线程捕获/恢复租户五字段 + 日志 MDC，由 rag 与 document 共用 | `TenantContext`、日志 MDC（模块本身在 tenant，无反向依赖） |
| `DocumentPipelineState` | 文档入库进度状态（PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING/SUCCESS/FAILED），含当前步骤、错误步骤与错误信息 | **独立持久化表 `document_pipeline_state`**（不再复用/挤占 `rag_document.status`） |
| `AsyncDocumentPipelineExecutor` | 异步线程池包装，接收解析任务并逐阶段推进 | `ThreadPoolTaskExecutor`，配置即 `document.pipeline.*`；含优雅关闭钩子 |
| `DocumentStepProcessor`（接口 + 各步骤实现） | 每步的执行器：`ParseStep` / `ChunkStep` / `IngestStep` / `VectorizeStep`，各自 try/catch + 状态落库 + **执行前调用 `TenantContextSnapshot.apply()` 恢复 TenantContext**（由 `TenantSchemaInterceptor` 自动续上 search_path，执行完 `clear()`） | Tika、切分器、向量库、`company-rag-tenant` 的 `TenantContextSnapshot` |
| `DocumentPipelineRecover` | 每步失败后的重试策略与最终降级（重试次数、退避），状态表落 FAILED | Resilience4j `Retry` |
| `DocumentPipelineCompensation` | 应用启动时扫描「非终态」文档做崩溃补偿（复用与任务相同的幂等 key 与 fail-closed 校验） | 状态表、`TenantContextSnapshot` |

### 3.2 分步状态机

```
PENDING ─▶ PARSING ─▶ CHUNKING ─▶ RAG_INGEST ─▶ VECTORIZING ─▶ SUCCESS
            │ (step 失败)                    │ (可重试 N 次)
            └──────────── retry ─────────────┘
            └───────────── 超出重试 ▶ FAILED（记录错误步骤与信息）
```

- 每步完成后持久化当前状态（**每步独立提交，不再是单事务**）。
- 失败重试只重放失败步骤，已成功的步骤（如已落库的 chunk）通过幂等 key 跳过。
- **状态落点**：状态写入独立表 `document_pipeline_state`（含 `task_id`、`tenant_id`、`step`、`status`、`error_step`、`error_msg`、`retry_count`、`create_time/update_time`）。`rag_document.status` 仅保留粗粒度对外语义（0-待处理/处理中 2-已完成 -1-失败），由 worker 在关键节点回写，避免挤占细粒度状态。`document_pipeline_state` 表带 `tenant_id` 且启用 RLS，与 `doc_chunk` 一致（见 §5.3）。

### 3.3 同步接口保持不变

- 上传接口同步返回 `taskId` 与 `PENDING` 状态，解析在后台线程池推进。
- 查询接口通过状态轮询返回当前进度（`/document/status/{taskId}`）。

### 3.4 异步租户隔离（🔴5）

多租户隔离完全由 `TenantSchemaInterceptor`（MyBatis 拦截器，**每条 SQL 执行前读 `TenantContext` 自动 `SET search_path` / `SET app.tenant_id`**）驱动。**注意 `TenantContextHelper` 已废弃（全方法 `@Deprecated` + no-op），且不存在 `resetSqlContext()` 方法**——不能在 worker 里手动调它重设 search_path。正确做法是：每步 `DocumentStepProcessor` 执行前**恢复 `TenantContext` ThreadLocal**，拦截器据此自动为当前连接续上本租户 search_path：

```
# 公共快照：从 company-rag-rag/workflow 下沉至 company-rag-tenant（document 已依赖 tenant，无新增/反向依赖）
# rag 模块现有用法可保留，或改为复用下层同一实现；document pipeline 一律用 tenant 版本，避免两套复制。
DocumentStepProcessor.execute(task):
  // 提交时从请求线程快照捕获（请求线程捕获 → worker 恢复，跨模块同一快照类型）
  TenantContextSnapshot snapshot = task.getTenantSnapshot();   // 提交时已捕获（tenant 模块类型，含 tenantId/schema getter）
  snapshot.apply();                                             // worker 线程恢复 TenantContext 五字段（非空字段写回）
  try {
      // ⚠️ fail-closed 断言（防拦截器 fallback 到 public 掩盖漏恢复）：
      //   仅查 ThreadLocal 只能证明 apply() 成功、不能证明拦截器真把当前连接 SET 到正确 schema，
      //   故每步事务内用 SQL 校验 current_schema() == expectedSchema，
      //   任一不满足 → 抛 IllegalStateException 终止本步，绝不依赖拦截器静默路由 public（防跨租户写错库）。
      assertTenantContextRestored(task.expectedTenantId, task.expectedSchema);  // ThreadLocal + current_schema() 双重校验
      ...本步骤独立事务+落库...   // 拦截器据此路由本租户 schema
  } finally {
      snapshot.clear();      // 清理 TenantContext 与本次写回的 trace/span，确保不污染池中下一个任务
  }
```

- **拦截器行为要顺**：拦截器对「TenantContext 已设置」设租户 schema、对「未设置」fallback 到 `search_path TO public`（fail-open 于应用视角 = 未设置时只碰 public，是安全的默认）。**必须"先恢复 `TenantContext` 再碰 DB"**（worker 中调用 `snapshot.apply()` 后立即执行 SQL），否则 SQL 会落在 public 或错误租户 schema。**但勿把"public 兜底"当免死金牌**：对漏恢复的 worker，public 兜底会静默把数据写错 schema、掩盖异步隔离回归，故每步落库前必须加 **fail-closed 断言**（`ThreadLocal` + `current_schema()` 双重校验，见上方伪代码 `assertTenantContextRestored`）。
- **提交时捕获**（请求线程，受信任身份）→ **worker 每步 `apply()` 恢复 + `clear()` 清理**，与 `RagAgentService.callAgentWithTimeout` 的上下文传播思路同源；载体统一为下沉至 tenant 的 `TenantContextSnapshot`（含 getter，供断言读取 expected schema/tenantId）。
- **补偿线程同样须恢复 TenantContext**：`DocumentPipelineCompensation` 扫描重放的任务，也走同一步骤（捕获→apply→断言→clear），避免补偿本身写错租户 schema。
- **删除竞态**：`deleteDocument` 与后台 pipeline 并发时，`VectorizeStep`/`IngestStep` 可能在文档已删除后继续写库。每步事务内须校验目标文档仍存在且归属本租户，否则终止该步（见 §5），并以 `document_pipeline_state` 冗余存在的孤立记录由删除流程负责清理。
- 解析文本/敏感内容一律不落日志（既有铁律）。

### 3.5 状态查询租户过滤（🟡）

新增 `/document/status/{taskId}` 必须按租户/用户过滤：从独立表 `document_pipeline_state` 查询，条件 `eq(tenantId, TenantContext.getTenantId()) + eq(taskId)`（叠加 RLS），**taskId 不可被跨租户枚举**（伪造 taskId 不得返回他租户任务）。返回字段含：状态、当前步骤、错误步骤（若有）、失败原因摘要。

## 4. 数据流

1. Controller 接收上传 → 校验 `MAX_FILE_SIZE` → **原始文件落临时目录/对象存储，任务只携带文件引用**（避免大文件 byte[] 长期驻留堆内存）→ 写入 `PENDING` 记录 + 返回 `taskId` 与文件引用路径。
2. `AsyncDocumentPipelineExecutor` 收到任务后 order 执行：
   - `ParseStep`：Tika 解析 → 状态 `PARSING`，成功转 `CHUNKING`。
   - `ChunkStep`：切分 → 写 chunk（幂等 key = 文档id+序号）→ 状态 `CHUNKING`。
   - `IngestStep`：chunk 落库 → 状态 `RAG_INGEST`。
   - `VectorizeStep`：Embedding → PGVector 入库 → 状态 `SUCCESS`。
3. 任一步失败 → `DocumentPipelineRecover` 按策略重试 N 次；仍失败标记 `FAILED(step, error)`，不再回滚已成功步骤。
4. 查询接口据状态字段轮询进度；日志输出 `[PIPELINE] task=... step=... cost=...` 结构化日志。
5. `SUCCESS` 步骤发布文档 `ADDED` 事件触发缓存失效（事件时机从"上传同步完成"调整到"后台异步成功"，见 §5）；`FAILED` 可发布失败事件供前端提示。临时文件在 `SUCCESS`/`FAILED` 终态后清理。
6. 应用启动时 `DocumentPipelineCompensation` 扫描「非终态」文档执行崩溃补偿重放（见 §3.1）。

**租户/安全：** 每步执行前由任务携带的 `TenantContextSnapshot` 在 worker 线程 `apply()` 恢复 `TenantContext`（**不手动 resetSqlContext**），`TenantSchemaInterceptor` 据此自动 `SET search_path`/`SET app.tenant_id`（见 3.4，🔴5）；状态查询按当前租户过滤（见 3.5）；解析文本敏感内容一律不落日志。

## 5. 错误处理与重试

| 场景 | 策略 |
|---|---|
| Tika 解析失败（文件损坏/不支持格式） | 不重试，直接 `FAILED`，返回可读原因 |
| 切分/入库异常 | 重试 2 次（指数退避 1s/2s） |
| 向量化网络抖动（超时/限流） | 重试 3 次（退避更长），仍失败 `FAILED` 且**保留已入库 chunk**，可手动补跑向量化 |
| 单步事务实现 | **本步骤独立事务**：每步注入独立 bean（或 `TransactionTemplate`），避免跨步长事务；禁止步骤内部 `this` 自调用 mapper（`@Transactional` 代理不生效），否则事务边界失效 |

**幂等补跑（🟡）：**
- 提供补跑入口 `POST /document/{taskId}/retry-step`（校验 taskId 归属当前租户），从 `FAILED` 记录的错误步骤续跑。
- **向量化幂等依据**：`vector_store` 新增 `chunk_id` 列（见 §5.3），对 `(tenant_schema, chunk_id)` 建唯一约束。补跑时目标 chunk 的向量已在向量表存在即跳过——`INSERT ... ON CONFLICT (chunk_id) DO NOTHING`（在正确 schema 上执行），避免重复向量化。
- chunk 幂等 key = `文档id + 序号`，`doc_chunk` 上建立 `(document_id, chunk_index)` 唯一约束（见 §5.3），重放时不重复插入。
- **删除竞态处理**：每步事务内先校验目标文档仍存在且归属本租户，若文档已删除则终止该步并清理对应的 `document_pipeline_state` 记录，不继续写 chunk/向量。

### 5.3 最小必要 schema 变更（迁移脚本 + 存量数据）

放弃"不触碰 schema"的绝对化表述，仅做三处必要 DDL，均以迁移脚本落地并与既有 RLS 模型对齐：

1. **`document_pipeline_state` 表**（新增）：
   - 字段：`task_id`(PK, UUID 或雪花) / `document_id` / `tenant_id` / `step` / `status` / `error_step` / `error_msg` / `retry_count` / `create_time` / `update_time`。
   - 隔离：带 `tenant_id` 列并 `ENABLE ROW LEVEL SECURITY` + `FORCE`，按租户建立 RLS 策略（参照 `doc_chunk` 的 `tenant_isolation_chunk` 模式）；`TenantSchemaInterceptor` 每条 SQL 前 SET，查询 `/document/status` 再叠加 `eq(tenantId, ...)`。
2. **`doc_chunk` 唯一约束**：`ALTER TABLE ... ADD CONSTRAINT uq_doc_chunk_doc_idx UNIQUE (document_id, chunk_index)`。
   - 存量：先按 `document_id, chunk_index` 去重（保留最小 id），再建约束，避免历史重复数据导致建约束失败。
3. **`vector_store.chunk_id` 列 + 唯一约束**：`ALTER TABLE vector_store ADD COLUMN chunk_id BIGINT`；`CREATE UNIQUE INDEX ON vector_store (chunk_id)`。
   - 存量回填：按 `metadata->>'chunkId'` 反查映射当前行 `chunk_id`（`vector_store` 为 UUID 主键 + 历史随机 id，需逐行回填），回填完成后再建唯一索引。
   - 注意：`vector_store` 不启用 RLS（schema 隔离，见 `V1` 迁移说明），chunk_id 唯一性以每租户 schema 内保证；幂等校验依赖 fail-closed 断言确保当前连接已路由到正确租户 schema。

## 6. 测试策略

- **步骤单测**：mock 每步依赖，验证状态流转、幂等跳过、失败即 `FAILED(step,error)`。
- **流水线集成测试**：整链 `/document/upload` → 轮询状态到 SUCCESS；注入向量化失败验证重试与保留 chunk。
- **并发租户隔离测试**：多租户文档并发处理，每步落库后用 `SELECT current_schema()` 断言命中本租户 schema（覆盖 §3.4 fail-closed / §5 幂等校验）。
- **回归**：现有文档上传/解析相关测试保持通过。
- 验证命令采用最窄范围：单模块相关测试类。

## 7. 改动清单

- **修改（模块依赖方向）**：`TenantContextSnapshot` 从 `company-rag-rag/.../workflow` **下沉到 `company-rag-tenant`**，rag 现有用法改为引用下层实现（或保留但文档 pipeline 一律用 tenant 版，避免两套复制）；`company-rag-rag` 相应调整 import，`DocumentParseServiceImpl` 解析逻辑拆分委派 executor、上传时经 tenant 版 `TenantContextSnapshot` 捕获随任务传递、原始文件先落盘携带文件路径。
- **新增**：`company-rag-tenant/.../context/TenantContextSnapshot.java`（下沉，含 tenantId/schema getter 供断言读取）；`company-rag-document/.../pipeline/` 下 `DocumentPipelineState` / `AsyncDocumentPipelineExecutor`（优雅关闭钩子）/ `DocumentPipelineStep`（接口）及 `ParseStep/ChunkStep/IngestStep/VectorizeStep`（每步独立事务 + 租户上下文重建 + fail-closed 断言）/ `DocumentPipelineRecover` / `DocumentPipelineCompensation`（启动补偿）。
- **新增接口**：`company-rag-web` 下 `DocumentController` 增加 `/document/status/{taskId}`（按租户过滤，返回状态/步骤/错误步骤/失败摘要）与 `/document/{taskId}/retry-step`（校验归属当前租户 + 仅 FAILED 可重跑）。
- **新增配置**：`document.pipeline.core-pool-size / max-pool-size / queue-capacity / retry-delay-ms / retry-max-attempts / file-temp-dir`。
- **Schema 迁移（最小必要）**：新增 `document_pipeline_state` 表（带 tenant_id + RLS）；`doc_chunk` 加 `(document_id, chunk_index)` 唯一约束（先去重）；`vector_store` 加 `chunk_id` 列 + 唯一索引（先按 metadata 回填）。以迁移脚本纳入变更（见 §5.3）。
- **不动**：`DocumentParseService`/`DocumentService` 接口、上传同步入口契约、租户/权限/size 铁律、向量维度 1024 / COSINE / HNSW。前端需配合新增 `/document/status` 轮询（行为变化，见 §8）。

## 8. 风险与观察项

- **异步租户隔离（最高优先级，🔴5）**：拆分线程池后每步必须**先用 `TenantContextSnapshot.apply()`（tenant 版）恢复 `TenantContext`，再碰 DB**，由 `TenantSchemaInterceptor` 自动续上本租户 `search_path`。**不要调用已废弃的 `TenantContextHelper`（无 `resetSqlContext`）**。若顺序反了/上下文未恢复，拦截器读到空上下文会把 SQL 路由到 public，导致**跨租户漏写/写错 schema**。**验收铁律 + 并发隔离测试**约束：并发处理多租户文档时，任何一步向量化落库的 chunk 必须命中本租户 schema（测试用 `current_schema()` 断言）。上下文传播复用 `RagAgentService.callAgentWithTimeout` 的**捕获→恢复→清理**思路，载体统一为下沉至 tenant 的 `TenantContextSnapshot`，避免另起一套。
- **崩溃恢复（🔴）**：任务仅驻留内存队列，进程重启会丢失；`DocumentPipelineCompensation` 启动时扫描「非终态」文档重放，且补偿线程同样须 apply/断言/clear，避免补偿写错租户 schema。
- **状态查询越权（🟡）**：`/document/status` 必须按当前租户过滤，伪造 taskId 不得返回他租户任务。
- **补跑一致性（🟡）**：`/retry-step` 需幂等（按 chunk id 跳过已向量化），补跑入口校验 taskId 归属。

- **行为变化**：上传从"同步解析完返回"变为"返回 taskId 后台解析"，**前端需轮询进度**。属接口行为变化，需在 plan 中给出前端配合点（新增 `/document/status`）。
- **幂等写入**：多步独立提交后，重试需靠幂等 key 防重复 chunk；依赖 `doc_chunk(document_id, chunk_index)` 与 `vector_store.chunk_id` 唯一约束（§5.3）保证 key 唯一稳定。
- **线程池隔离与优雅关闭**：与 Agent 线程池（`rag.agent.executor`）分离配置，避免相互挤占；队列满按 AbortPolicy 降级返回；`AsyncDocumentPipelineExecutor` 提供 `shutdown()`/WaitForTasksToComplete 退出钩子，重启前等待在跑任务结束，避免中断留中间态。
- **内存驻留**：原始文件先落盘（携带文件路径，见 §4），限制并发上传任务数，避免大文件 byte[] 长期驻留堆内存或线程池被大文件占满。
- **删除竞态**：`deleteDocument` 与后台处理并发时每步校验目标文档仍存在且归属本租户，已删除则终止步并清理 `document_pipeline_state` 记录（见 §5）。
- **事件时机调整**：`ADDED` 事件改由后台 `SUCCESS` 步发布，缓存失效时机改变；需在 plan 中同步调整依赖该事件的前端/缓存行为。