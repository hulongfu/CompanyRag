# RAG 文档入库（ETL）纯健壮性改造设计

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案A**——对本项目自有入库流水线做纯健壮性改造（异步线程池 + 分步状态机 + 每步独立落库/重试），不引入 Spring AI Alibaba 官方 ETL API。
> 修订说明：修复评审 🔴5（异步线程池每一步必须重设 `search_path`/RLS，防止跨租户写库）、🟡 状态查询必须按租户过滤、补跑入口与幂等跳过依据；并修正 🔴B/🟡4 引用的真实 API（`TenantContextSnapshot` 只有 `captureNow()/apply()/clear()`，无 `restore()`/`TenantContextScope`）——伪代码改用 `apply()`+`clear()`，且澄清"复用 RagAgentService 模式"指其上下文传播思想，而非直接复用其 `TenantContextSnapshot`（`callAgentWithTimeout` 实际用 Micrometer `ContextSnapshot` + 手动 `TenantContext.setXxx`，与 ETL 场景同源不同载体）。

## 1. 目标

在不改变对外接口与安全边界的前提下，把当前 `DocumentParseServiceImpl` 同步单事务的「解析→切分→入库→向量化」流水线，改为**异步、分步可重试、状态可观察**的健壮管线。解决单个大文件解析占满请求线程、长流程一个失败整体回滚、无法观测中间态等问题。

**约束：**
- 不引入官方 `ETL` API / starter，只用本项目现有异步能力与既有存储。
- 保持 `DocumentService` 接口与上层 Controller 调用不变（上传接口仍同步返回任务，解析改后台）。
- 保留现有多租户隔离、文件大小 iron-rule（`MAX_FILE_SIZE`）、日志安全（不落敏感内容）。
- 向量化维度 1024 / COSINE / HNSW 等铁律天然满足，不触碰数据层 schema。

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
| `DocumentPipelineState` | 文档入库进度状态（PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING/SUCCESS/FAILED），含当前步骤与错误信息 | 复用文档实体状态字段或新增列 |
| `AsyncDocumentPipelineExecutor` | 异步线程池包装，接收解析任务并逐阶段推进 | `ThreadPoolTaskExecutor`，配置即 `document.pipeline.*` |
| `DocumentStepProcessor`（接口 + 各步骤实现） | 每步的执行器：`ParseStep` / `ChunkStep` / `IngestStep` / `VectorizeStep`，各自 try/catch + 状态落库 + **执行前调用 `TenantContextSnapshot.apply()` 恢复 TenantContext**（由 `TenantSchemaInterceptor` 自动续上 search_path，执行完 `clear()`） | Tika、切分器、向量库、`TenantContextSnapshot` |
| `DocumentPipelineRecover` | 每步失败后的重试策略与最终降级（重试次数、退避） | Resilience4j `Retry` |

### 3.2 分步状态机

```
PENDING ─▶ PARSING ─▶ CHUNKING ─▶ RAG_INGEST ─▶ VECTORIZING ─▶ SUCCESS
            │ (step 失败)                    │ (可重试 N 次)
            └──────────── retry ─────────────┘
            └───────────── 超出重试 ▶ FAILED（记录错误步骤与信息）
```

- 每步完成后持久化当前状态（**每步独立提交，不再是单事务**）。
- 失败重试只重放失败步骤，已成功的步骤（如已落库的 chunk）通过幂等 key 跳过。

### 3.3 同步接口保持不变

- 上传接口同步返回 `taskId` 与 `PENDING` 状态，解析在后台线程池推进。
- 查询接口通过状态轮询返回当前进度（`/document/status/{taskId}`）。

### 3.4 异步租户隔离（🔴5）

多租户隔离完全由 `TenantSchemaInterceptor`（MyBatis 拦截器，**每条 SQL 执行前读 `TenantContext` 自动 `SET search_path` / `SET app.tenant_id`**）驱动。**注意 `TenantContextHelper` 已废弃（全方法 `@Deprecated` + no-op），且不存在 `resetSqlContext()` 方法**——不能在 worker 里手动调它重设 search_path。正确做法是：每步 `DocumentStepProcessor` 执行前**恢复自定义 `TenantContext` ThreadLocal**，拦截器据此自动为当前连接续上本租户 search_path：

```
DocumentStepProcessor.execute(task):
  // 提交时从请求线程快照捕获（复用 TenantContextSnapshot 模式：请求线程捕获 → worker 恢复）
  TenantContextSnapshot snapshot = task.getTenantSnapshot();   // 提交时已捕获
  snapshot.apply();                                             // worker 线程恢复 TenantContext 五字段（非空字段写回）
  try {
      // ⚠️ fail-closed 断言（防拦截器 fallback 到 public 掩盖漏恢复）：
      //   若 apply 后 TenantContext 仍未恢复出本租户 schema/tenantId，直接抛错终止本步，
      //   绝不依赖拦截器静默路由到 public（那会把数据写错 schema，掩盖异步隔离回归）。
      assertTenantContextRestored(task.expectedTenantId);       // 未恢复 → 抛 IllegalStateException
      // 不再手动 resetSqlContext / SET search_path：
      // TenantSchemaInterceptor 会在每条 SQL 前自动读到刚恢复的 TenantContext 并 SET search_path/app.tenant_id
      ...本步骤事务+落库...   // 拦截器据此路由本租户 schema
  } finally {
      snapshot.clear();      // 清理 TenantContext 与本次写回的 trace/span，确保不污染池中下一个任务
  }
```

- **拦截器行为要顺**：拦截器对「TenantContext 已设置」设租户 schema、对「未设置」fallback 到 `search_path TO public`（fail-open 于应用视角 = 未设置时只碰 public，是安全的默认）。**必须"先恢复 `TenantContext` 再碰 DB"**（worker 中调用 `snapshot.apply()` 后立即执行 SQL），否则 SQL 会落在 public 或错误租户 schema。**但勿把"public 兜底"当免死金牌**：对漏恢复的 worker，public 兜底会静默把数据写错 schema、掩盖异步隔离回归，故每步落库前必须加 **fail-closed 断言**（`TenantContext` 未恢复则抛错，不依赖 public 兜底，见上方伪代码 `assertTenantContextRestored`）。
- **提交时捕获**（请求线程，受信任身份）→ **worker 每步 `apply()` 恢复 + `clear()` 清理**，与 `RagAgentService.callAgentWithTimeout` 的上下文传播思路同源（其载体为 Micrometer `ContextSnapshot` + 手动 `TenantContext.setXxx`，此处用项目自有的 `TenantContextSnapshot`，两者复用同一「捕获→恢复→清理」模式）。
- 解析文本/敏感内容一律不落日志（既有铁律）。

### 3.5 状态查询租户过滤（🟡）

新增 `/document/status/{taskId}` 必须按租户/用户过滤：查询条件 `eq(tenantId, TenantContext.getTenantId()) + eq(taskId)`，**taskId 不可被跨租户枚举**（伪造 taskId 不得返回他租户任务）。返回字段含：状态、当前步骤、错误步骤（若有）、失败原因摘要。

## 4. 数据流

1. Controller 接收上传 → 写入 `PENDING` 记录 + 返回 `taskId`。
2. `AsyncDocumentPipelineExecutor` 收到任务后 order 执行：
   - `ParseStep`：Tika 解析 → 状态 `PARSING`，成功转 `CHUNKING`。
   - `ChunkStep`：切分 → 写 chunk（幂等 key = 文档id+序号）→ 状态 `CHUNKING`。
   - `IngestStep`：chunk 落库 → 状态 `RAG_INGEST`。
   - `VectorizeStep`：Embedding → PGVector 入库 → 状态 `SUCCESS`。
3. 任一步失败 → `DocumentPipelineRecover` 按策略重试 N 次；仍失败标记 `FAILED(step, error)`，不再回滚已成功步骤。
4. 查询接口据状态字段轮询进度；日志输出 `[PIPELINE] task=... step=... cost=...` 结构化日志。

**租户/安全：** 每步执行前由任务携带的 `TenantContextSnapshot` 在 worker 线程 `apply()` 恢复 `TenantContext`（**不手动 resetSqlContext**），`TenantSchemaInterceptor` 据此自动 `SET search_path`/`SET app.tenant_id`（见 3.4，🔴5）；状态查询按当前租户过滤（见 3.5）；解析文本敏感内容一律不落日志。

## 5. 错误处理与重试

| 场景 | 策略 |
|---|---|
| Tika 解析失败（文件损坏/不支持格式） | 不重试，直接 `FAILED`，返回可读原因 |
| 切分/入库异常 | 重试 2 次（指数退避 1s/2s） |
| 向量化网络抖动（超时/限流） | 重试 3 次（退避更长），仍失败 `FAILED` 且**保留已入库 chunk**，可手动补跑向量化 |
| 单步 `@Transactional` 粒度 | 降为"本步骤独立事务"，避免跨步长事务 |

**幂等补跑（🟡）：**
- 提供补跑入口 `POST /document/{taskId}/retry-step`（校验 taskId 归属当前租户），从 `FAILED` 记录的错误步骤续跑。
- 向量化补跑跳过依据：**同一 chunk id 已在向量表存在即跳过**（`INSERT ... ON CONFLICT DO NOTHING` 或先查后写），避免重复向量化。
- chunk 幂等 key = `文档id + 序号`，已落库 chunk 在重放时不重复插入。

## 6. 测试策略

- **步骤单测**：mock 每步依赖，验证状态流转、幂等跳过、失败即 `FAILED(step,error)`。
- **流水线集成测试**：整链 `/document/upload` → 轮询状态到 SUCCESS；注入向量化失败验证重试与保留 chunk。
- **回归**：现有文档上传/解析相关测试保持通过。
- 验证命令采用最窄范围：单模块相关测试类。

## 7. 改动清单

- **修改**：`company-rag-document/.../service/impl/DocumentParseServiceImpl.java`（解析逻辑拆分为步骤委派给 executor；上传时捕获 `TenantContextSnapshot` 随任务传递）。
- **新增**：`company-rag-document/.../pipeline/` 下 `DocumentPipelineState` / `AsyncDocumentPipelineExecutor` / `DocumentStepProcessor`（及 Parse/Chunk/Ingest/Vectorize 四实现，每步含租户上下文重建）/ `DocumentPipelineRecover`；Controller 查询接口 `/document/status/{taskId}` 与补跑 `/document/{taskId}/retry-step`。
- **新增配置**：`document.pipeline.core-pool-size / max-pool-size / queue-capacity / retry-delay-ms / retry-max-attempts`。
- **不动**：`DocumentService` 接口、上传同步入口契约、数据层 schema（如需状态列则新增而非改既有）、租户/权限/size 铁律。

## 8. 风险与观察项

- **异步租户隔离（最高优先级，🔴5）**：拆分线程池后每步必须**先用 `TenantContextSnapshot.apply()` 恢复 `TenantContext`，再碰 DB**，由 `TenantSchemaInterceptor` 自动续上本租户 `search_path`。**不要调用已废弃的 `TenantContextHelper`（无 `resetSqlContext`）**。若顺序反了/上下文未恢复，拦截器读到空上下文会把 SQL 路由到 public，导致**跨租户漏写/写错 schema**。**验收铁律 + 并发隔离测试**约束：并发处理多租户文档时，任何一步向量化落库的 chunk 必须命中本租户 schema。复用 `RagAgentService.callAgentWithTimeout` 的**上下文传播思路**（捕获→恢复→清理；其载体为 Micrometer `ContextSnapshot` + 手动 `TenantContext.setXxx`），ETL 场景用项目自有的 `TenantContextSnapshot` 实现同一模式，避免另起一套。
- **状态查询越权（🟡）**：`/document/status` 必须按当前租户过滤，伪造 taskId 不得返回他租户任务。
- **补跑一致性（🟡）**：`/retry-step` 需幂等（按 chunk id 跳过已向量化），补跑入口校验 taskId 归属。

- **行为变化**：上传从"同步解析完返回"变为"返回 taskId 后台解析"，**前端需轮询进度**。属接口行为变化，需在 plan 中给出前端配合点（新增 `/document/status`）。
- **幂等写入**：多步独立提交后，重试需靠幂等 key 防重复 chunk；需在实现时保证 key 唯一且稳定。
- **线程池隔离**：与 Agent 线程池（`rag.agent.executor`）分离配置，避免相互挤占；队列满按 AbortPolicy 降级返回。
- **内存驻留**：大文件解析常驻后台线程，需限制并发上传任务数，避免线程池被大文件占满。