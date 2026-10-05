# RAG 文档入库（ETL）健壮性改造 — 实施计划

> 对应 Spec：`docs/superpowers/specs/2026-09-14-rag-etl-hardening-design.md`（修订版）
> 日期：2026-09-16
> 类型：实施计划（Plan）
> 状态：待审核（审核通过前不修改任何代码）

## 0. 目标与验收

把 `DocumentParseServiceImpl` 的同步单事务管线改为异步、分步可重试、状态可观察的健壮管线，同时：
- 保持 `DocumentParseService` 接口与 `/api/document/upload` 契约不变（上传仍同步返回 taskId + PENDING）。
- 不引入外部 ETL starter，复用本模块异步 + Resilience4j retry。
- 多租户隔离由 `TenantSchemaInterceptor` + 下沉后的 `TenantContextSnapshot` 保证，fail-closed（ThreadLocal + `current_schema()` 双重校验）。

**验收铁律（DoD）**：
1. 并发多租户文档处理时，任何一步落库 `SELECT current_schema()` 均命中本租户 schema（集成测试断言）。
2. 向量化失败重试后 SUCCESS，且不产生重复 chunk / 重复向量（幂等 key 生效）。
3. 进程重启后 `DocumentPipelineCompensation` 能补偿非终态文档到终态。
4. `/document/status` 越权（伪造其他租户 taskId）返回空或 403，不泄露他租户任务。

**实施顺序**：T1（下沉）→ T2（迁移）→ T3（状态模型 + executor 骨架）→ T4（四步骤 + recover）→ T5（补偿）→ T6（Controller 两接口）→ T7（测试收集 + 回归）。

---

## T1 下沉 `TenantContextSnapshot` 至 `company-rag-tenant`

**目的**：解除 document→rag 反向依赖，为 pipeline 复用同一捕获/恢复机制。rag 与 document 共用。

**改动（以下为确认后的精确清单）**：

- **① 新增 tenant 版** `company-rag-tenant/src/main/java/com/company/rag/tenant/context/TenantContextSnapshot.java`（包名 `com.company.rag.tenant.context`），内容从 rag 原类搬运，语义 `captureNow()/apply()/clear()` 不变，**新增 getter** `getTenantId()` / `getSchema()`（供 pipeline 的 `assertTenantContextRestored` 读取 expected 值）。
- **② 删除 rag 原类** `company-rag-rag/.../workflow/TenantContextSnapshot.java`（下沉即删除，由 tenant 版取代；不采用"tenant 新建 + rag 保留"两套并存）。
- **③ 补/改 import**（关键：8 个 workflow 节点与 2 个测试与原类**同包 `com.company.rag.rag.workflow`**，是裸类名同包引用（无 import），下沉后需**新增** import；仅 `ChatController` 原本就是跨包 **import**，需**改**。所有文件的**类型名与 `captureNow()/apply()/clear()` 及泛型 `state.<TenantContextSnapshot>value(...)` 均不变**）：
  - **新增 import 的 10 个文件**（皆为 `import com.company.rag.tenant.context.TenantContextSnapshot;`）：
    - rag 主代码 6 个：`FullTextRetrieveNode`、`VectorRetrieveNode`、`FuzzyRetrieveNode`、`NormalizeFuseNode`、`FilterNode`、`HybridRetrievalWorkflow`
    - rag 测试 2 个：`RetrieveNodeTest`、`NormalizeFuseNodeTest`
    - （同包节点：`FilterNode`/`FuzzyRetrieveNode`/`NormalizeFuseNode`/`VectorRetrieveNode`/`FullTextRetrieveNode` 均在各自方法体内用 `TenantContextSnapshot snapshot = state.<TenantContextSnapshot>value(...)`；`HybridRetrievalWorkflow` L76 用 `TenantContextSnapshot.captureNow()`。测试用 `TenantContextSnapshot.captureNow()`。）
  - **改 import 的 1 个文件**：`web/controller/ChatController.java` L16 `import com.company.rag.rag.workflow.TenantContextSnapshot;` → `import com.company.rag.tenant.context.TenantContextSnapshot;`（该文件为跨包调用，与上述同包不同源）。

**验证（最窄范围）**：
```bash
mvn -pl company-rag-rag -am test -Dtest=RetrieveNodeTest,NormalizeFuseNodeTest -DfailIfNoTests=false
```
（`-am` 带上游依赖，确保 tenant 模块一并编译。）

**风险**：下沉后若某处 import 漏改会编译失败（Fail-fast，易发现）。不加缓存态、不退行为。

---

## T2 数据库迁移脚本 `V4__rag_etl_pipeline.sql`

**目的**：落地 spec §5.3 三处最小必要 DDL。脚本为**手动执行**（Flyway 禁用，放 `sql/migrations/`）。

- **1. `document_pipeline_state` 表**（每个租户 schema 一张，initial + 循环存量）：
  ```sql
  CREATE TABLE document_pipeline_state (
      task_id          UUID PRIMARY KEY,
      document_id      BIGINT      NOT NULL,
      tenant_id        BIGINT      NOT NULL,
      step             VARCHAR(32) NOT NULL,
      status           VARCHAR(32) NOT NULL,       -- PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING/SUCCESS/FAILED
      error_step       VARCHAR(32),
      error_msg        TEXT,
      retry_count      INT   NOT NULL DEFAULT 0,
      create_time      TIMESTAMP NOT NULL DEFAULT now(),
      update_time      TIMESTAMP NOT NULL DEFAULT now()
  );
  ALTER TABLE document_pipeline_state ENABLE ROW LEVEL SECURITY;
  ALTER TABLE document_pipeline_state FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation_pipeline ON document_pipeline_state
      FOR ALL USING (tenant_id = current_setting('app.tenant_id')::bigint)
      WITH CHECK (tenant_id = current_setting('app.tenant_id')::bigint);
  CREATE INDEX idx_pipeline_tenant ON document_pipeline_state(tenant_id);
  ```
- **2. `doc_chunk` 唯一约束**：
  ```sql
  -- 先按 (document_id, chunk_index) 去重，保留最小 id
  DELETE FROM doc_chunk a USING doc_chunk b
   WHERE a.document_id = b.document_id AND a.chunk_index = b.chunk_index AND a.id > b.id;
  ALTER TABLE doc_chunk
      ADD CONSTRAINT uq_doc_chunk_doc_idx UNIQUE (document_id, chunk_index);
  ```
- **3. `vector_store` 加 `chunk_id` + 唯一索引**：
  ```sql
  ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS chunk_id BIGINT;
  -- 存量回填：按 metadata->>'chunkId' 映射；重复取最小 id
  UPDATE vector_store v SET chunk_id = (
      SELECT MIN((m->>'chunkId')::bigint) FROM ... ) ...;   -- 见注
  CREATE UNIQUE INDEX IF NOT EXISTS uq_vector_store_chunk ON vector_store(chunk_id)
      WHERE chunk_id IS NOT NULL;
  ```
  > 回填细节：`chunk_id` 从 `metadata` json 的 `chunkId` 字段取。因历史为随机 UUID id，需逐行从 metadata 提取并去重。建议先备份。

- **授权与循环存量 schema**：参照 V1 用 `DO $$ ... plpgsql` 遍历 `tenant_*` schema，为 `company_rag_app` 授予新表 SELECT/INSERT/UPDATE/DELETE 及新列的权限（否则 `company_rag_app` 无法访问新建表/列）。

**验证**：在测试库手动执行后，`\d tenant_default.document_pipeline_state` 确认 RLS 策略与唯一约束存在；`SELECT conname FROM pg_constraint` 确认识别唯一约束。

**风险**：#2/#3 涉及存量数据去重/回填，**必须先备份**（`pg_dump`），且生产重复执行需 `IF NOT EXISTS` / 幂等。

---

## T3 状态模型 + 异步执行器骨架

**新增（`company-rag-document/.../pipeline/`）**：
- `PipelineStatus`（枚举：PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING/SUCCESS/FAILED）。
- `DocumentPipelineState`（实体/POJO，映射 `document_pipeline_state` 表，含 getter/setter；或直接用 MyBatis-Plus 实体）。
- `DocumentPipelineStateMapper`（`BaseMapper`，操作独立表）。
- `AsyncDocumentPipelineExecutor`：
  - 注入 `ThreadPoolTaskExecutor`（配置 `document.pipeline.*`，见 T3 配置）。
  - `submit(task)` 接收任务入队；`shutdown()`/退出钩子（`@PreDestroy` 等待在跑任务）。
  - 持有 front 接口，任务流转到四步骤。
- `DocumentPipelineConfig`（`@Configuration`）：定义 `ThreadPoolTaskExecutor` bean（core/max/queue/AbortPolicy），从 `document.pipeline.*` 读取。

**任务载荷 `PipelineTask`**：
- 字段：`taskId` / `documentId` / `tenantId` / `expectedSchema` / `fileRef`（临时文件路径）/ `tenantSnapshot`（下沉后的 tenant 版 `TenantContextSnapshot`）。
- `tenantSnapshot` 在上传请求线程 `captureNow()` 捕获后随任务传递。

**配置（`application*.yml`）**：
```yaml
document:
  pipeline:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100
    retry-delay-ms: 1000
    retry-max-attempts: 3
    file-temp-dir: ./data/upload-tmp
```

**验证**：单测 `AsyncDocumentPipelineExecutorTest`——mock 步骤，验证任务能按顺序入队执行、队列满 AbortPolicy、`shutdown()` 等待。最窄命令：`company-rag-document` 模块内该测试类。

---

## T4 四步骤（Parse/Chunk/Ingest/Vectorize）+ 重试恢复

**新增接口**：`DocumentPipelineStep`（`execute(PipelineTask, PipelineContext)`）。
**新增实现（各自独立事务 + 租户恢复 + fail-closed）**：
- `ParseStepFileRef`：从 `fileRef` 读临时文件 → Tika 解析 → 状态 PARSING → 成功转 CHUNKING。**不重试**（文件损坏直接 FAILED）。（复用现有 `extractText` 逻辑）
- `ChunkStep`：切分 → 写 `doc_chunk`（幂等 key = `documentId+chunkIndex`，靠 T2 唯一约束 + `ON CONFLICT DO NOTHING` / 先查后写）→ 状态 CHUNKING。
- `IngestStep`：chunk 落库（幂等重放）→ 状态 RAG_INGEST。
- `VectorizeStep`：Embedding → PGVector 入库 → 幂等写（`insert into vector_store(chunk_id, ...) on conflict (chunk_id) do nothing`）→ 状态 SUCCESS。失败重试 3 次（Resilience4j Retry，退避更长）。**保留已入库 chunk**。

**每个步骤执行模板（统一封装 `PipelineStepExecutorTemplate`）**：
```java
TenantContextSnapshot s = task.tenantSnapshot;
s.apply();
try {
    assertTenantContextRestored(task);   // ThreadLocal + current_schema() == expectedSchema 双校验
    ItemStatusOrResult r = step.execute(task);
    persistState(task, r.status);
} catch (NotFoundAfterDelete e) {
    cleanupState(task);                  // 文档已删除竞态：终止 + 清理
} finally {
    s.clear();
}
```

**`assertTenantContextRestored`** 落到 `TenantContextSnapshot` 或独立 util（tenant 模块，或 document 内）：
- 读 `TenantContext.getTenantId()/getSchema()` 与 `task` expected 一致；
- 事务内 `SELECT current_schema()` 等于 `expectedSchema`；任一不满足抛 `IllegalStateException`。

**删除竞态**：每步事务内先查 `rag_document` 该 `documentId` 仍存在且 `tenant_id` 归属，已删则走 `NotFoundAfterDelete` 分支清理 `document_pipeline_state`。

**`DocumentPipelineRecover`**：Resilience4j `Retry`——Tika 失败不重试；切分/入库重试 2 次（退避 1s/2s）；向量化重试 3 次（退避更长）。超限仍失败 → 状态 FAILED 落 `error_step/error_msg`。

**验证（最窄）**：
- 单测：`PipelineStepExecutorTemplateTest`（fail-closed 断言：mock 拦截器场景）、`ChunkStepTest`/`VectorizeStepTest`（幂等跳过、失败即 FAILED）。
- 该模块相关测试类。

---

## T5 崩溃补偿 `DocumentPipelineCompensation`

启动时扫描 `document_pipeline_state` 中 **status ∈ {PENDING, PARSING, CHUNKING, RAG_INGEST, VECTORIZING}**（非终态）的记录，逐条走 `PipelineStepExecutorTemplate` 重放（从记录 `step` 续跑）。
- 补偿线程同样 carry tenant snapshot（`apply` → 断言 → `clear`），避免补偿写错租户 schema。
- 复用 T4 的幂等 key，已成功的 chunk/向量跳过。
- 实现 `ApplicationRunner`（或 `@EventListener(ApplicationReadyEvent)`）执行，带并发上限，避免启动风暴。

**验证**：单测 `DocumentPipelineCompensationTest`——插入若干非终态记录，验证启动补偿后推进到 SUCCESS/FAILED，且补偿线程 current_schema 正确。

---

## T6 Controller 新接口（`company-rag-web`）

在 `DocumentController`（`/api/document`）新增：
- **`GET /api/document/status/{taskId}`**：
  - 从 `document_pipeline_state` 查询 `eq(tenantId, TenantContext.getTenantId()) + eq(taskId)`（叠加 RLS）。
  - 返回 `R<PipelineStatusVO>`：`status / step / errorStep / errorMsg(摘要，截断 + 脱敏) / retryCount`。
  - **越权**：非本租户 taskId 返回成功但无数据（或空），绝不泄露他租户记录。`@PreAuthorize("hasAnyRole('ADMIN','USER','VIEWER')")`。
- **`POST /api/document/{taskId}/retry-step`**：
  - 校验 taskId 归属当前租户 + 当前 status == FAILED，否则拒绝。
  - 从 `error_step` 续跑（复用 T4 流程）。
  - 幂等：已向量化的 chunk 按 `chunk_id` 跳过。
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")` + `@AuditLog`。

**上传入口改动（决策已确认：新增方法 + 保留旧契约）**：
- **`DocumentController.upload`** 保留原入口，但**不再同步解析**：改为调用新增的 `DocumentPipelineService.submitUpload(...)`，即校验 `MAX_FILE_SIZE` → **落临时文件**（`document.pipeline.file-temp-dir`）→ `captureNow()` 捕获 tenant snapshot → 构造 `PipelineTask` 提交 → 写 PENDING → 返回 `taskId + PENDING`（不再同步解析）。
- **（已确认）新增独立方法替代返回语义变更**：不改变 `DocumentParseService.uploadAndParse` 的既有契约（仍返回 `Document`，status=待处理；其内部逻辑改为委派给 executor）。Controller 改用新增的 `submitUpload` 拿 `taskId`，避免破坏 `listDocuments`/`deleteDocument` 等既有内部调用——**不修改 `uploadAndParse` 的签名/返回值**。

**新增 service 接口/实现**（`company-rag-document`）：
- `DocumentPipelineService` / `impl`：`submitUpload(file, tenantId)`（返回 taskId）、`getStatus(taskId, tenantId)`、`retryStep(taskId, tenantId)`。
- `DocumentParseServiceImpl` 解析逻辑拆分为步骤委派给 executor（`uploadAndParse` 契约不变）。

> ✅ **行为变化确认点（已由用户确认）**：`uploadAndParse` 契约**保持不变**（仍返回 `Document`），新增独立 `submitUpload` 供 Controller 使用并返回 `taskId+PENDING`。前端配合点见 §8。

**验证**：`company-rag-web` 侧 Controller/MVC 测试（mock service），校验状态查询越权返回、retry 仅 FAILED 可跑、上传落盘。

---

## T7 测试收集 + 回归（整体验证）

- **新增集成测试**（`company-rag-document` 或 `company-rag-web`）：
  - 全链 `/api/document/upload` → 轮询 `/status` 到 SUCCESS。
  - 注入向量化失败 → 验证重试 + 保留 chunk + 手动 retry。
  - 并发多租户 → 每步 `current_schema()` 断言。（最窄：该集成测试类）
- **回归**：现有 `DocumentParseServiceImplEventTest`、splitter 测试等保持通过。
- **验证命令（最窄范围）**：
  ```bash
  cd company-rag-document && mvn test -Dtest='Pipeline*,ChunkStepTest,VectorizeStepTest,AsyncDocumentPipelineExecutorTest,DocumentParseServiceImplEventTest'
  cd company-rag-rag && mvn test -Dtest='RetrieveNodeTest,NormalizeFuseNodeTest'
  ```
  避免全仓 `mvn test`。

---

## 变更清单汇总

| 类型 | 模块 | 内容 |
|---|---|---|
| 移动+增强 | company-rag-tenant | `TenantContextSnapshot`（搬运 + 增 getter `getTenantId`/`getSchema`），包改 `tenant.context` |
| 删除 | company-rag-rag | 删除原 `workflow/TenantContextSnapshot.java` |
| 改 import | rag / web | 同包节点/测试 `FullTextRetrieveNode/VectorRetrieveNode/FuzzyRetrieveNode/NormalizeFuseNode/FilterNode/HybridRetrievalWorkflow/RetrieveNodeTest/NormalizeFuseNodeTest` **新增** import；`ChatController` **改** import |
| 新增迁移 | sql/migrations | `V4__rag_etl_pipeline.sql` |
| 新增 | document | `pipeline/`：PipelineStatus / DocumentPipelineState / Mapper / PipelineTask / AsyncDocumentPipelineExecutor / PipelineStepExecutorTemplate / ParseStepFileRef / ChunkStep / IngestStep / VectorizeStep / DocumentPipelineRecover / DocumentPipelineCompensation / DocumentPipelineConfig / DocumentPipelineService(+impl) |
| 新增接口 | web | `/api/document/status/{taskId}`、`/api/document/{taskId}/retry-step`；`upload` 改调新增 `submitUpload`（落盘+提交任务，返回 taskId） |
| 新增配置 | bootstrap | `document.pipeline.*` |
| 不动 | — | `DocumentParseService`/`DocumentService` 接口与 `uploadAndParse` 契约、租户/权限/size 铁律、向量维度 |

## 风险与缓释

- **下沉 import 漏改** → 编译 Fail-fast；T7 回归兜底。
- **迁移去重/回填损坏存量** → 执行前 `pg_dump` 备份；脚本幂等。
- **异步租户隔离回归** → 并发 `current_schema()` 集成测试 + fail-closed 断言（T4）。
- **前端轮询缺失** → §8 行为变化已在 spec 标注，plan 落地时需同步前端任务（新增 `/status` 轮询）。

## 未纳入范围（后续）

- 官方 Spring AI Alibaba ETL API（方案A 排除）。
- 分片上传 / 超大文件秒传（非本节目标）。
- Flyway 启用（当前手动迁移）。