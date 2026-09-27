# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计 v5

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅（v5 整合四轮审阅修正）
> 版本变更：
> - v1→v2：整合首轮 R1-R3 / M1-M7。
> - v2→v3：XML→@Select、b 降观察项改 ignoreTable、dataset_fingerprint、F1 退化修正、显式租户、同源校验。
> - v3→v4：schema 由服务端推导防 IDOR、rule_version、空样本不落快照、doEvaluate 三维、三维一致率公式、online-enabled 前置。
> - v4→v5：整合四轮审阅——①schema 改取 `TenantContext.getSchema()`（**引用既有 JwtAuthenticationFilter 服务端反查机制**，废止错误的 `tenant_`+tenantId 推导）；②doEvaluate 新增指定 record `EvalDecision`（布尔维度），落库侧沿用 `dimensionScores(Map<String,Double>)`；③SchemaMigrationConfig 明确新增同范式 runner + init.sql 位置约束；④rule_version 改为配置项；⑤30s 锁语义钉死；⑥limit 缺省/透传；⑦指纹改 Java 侧计算；⑧补 persisted_pass_agree；⑨s.tenant_id 断言；⑩/history 分页+二级排序；⑪多项细节。

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答动态筛出评测样本；回归对当批样本生成**指纹**并以 `rule_version` 锁定规则，支持可复现归因。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall/负类召回/F1 + 三维一致率 + persisted_pass_agree），**落快照**（含指纹+规则版本）支持跨版本趋势与正确归因。

**约束：**
- **最小可行（MVP）**：数据集为动态视图 + 每批回归落**含指纹+规则版本的聚合快照**；硬门禁留可选开关占位（默认关）。
- **反馈路径零改动**：`updateFeedback`、`rag_session.feedback` 不动；反馈联动通过读取侧 join。
- **回归重跑不污染线上缓存**（R3）：走 `evaluateNoCache`（无 Redis 写）。
- **最小化全局插件影响**：不加改租户插件 append；用 `ignoreTable` + 手写租户断言。
- **数据源前置依赖**：`rag.eval.online-enabled` 默认 `false`（ChatController:56）且仅 `result.isRagUsed()` 行落评估——生产需开启才有在线样本沉淀（见 §3.6）。
- **支持续演进边界**：本期回归测的是**固定历史 toolContext** 下的判定规则，不覆盖检索链路/知识库更新变化。

## 2. 现状回顾（真实机制）

多租户隔离的真实主次顺序：
- **主防线 = Schema 物理隔离**：`TenantSchemaInterceptor` 每次查询前 `SET search_path TO <schema>, public`（:96）。
- **辅助防线 = RLS（best-effort）**：`SET app.tenant_id`（:98）；两表 RLS 用 `tenant_id = current_tenant_id()`；`TenantSchemaInterceptor:45`、`TenantMyBatisPlusConfig:24-25` 承认 best-effort / 连接池跨连接风险。
- **结论**：SQL 必须**显式携带租户断言**，不依赖 RLS。

Schema 与租户映射（四轮核实）：
- schema 按 **tenantCode** 生成：`TenantServiceImpl.normalizeSchemaName(tenantCode) = "tenant_" + tenantCode.toLowerCase()`（:54-55），**非 tenantId**。
- **服务端反查现成机制**：`JwtAuthenticationFilter:80-88` 通过 `tenantService.getById(currentTenantId).getSchemaName()` 把 schema 写入 `TenantContext.setSchema()`；:52-58 已校验 X-Tenant-Id 必须属于 JWT 的 tenantIds（"不接受客户端任意传入"）。
- `TenantSchemaInterceptor` 已在消费 `TenantContext.getSchema()` 设 search_path。**dataset/regression 应取同一值，避免 SQL 与 search_path 错位。**

MyBatis XML 事实：用 `mybatis-plus-spring-boot3-starter`，`mapperLocations` 默认 `classpath*:/mapper/**/*.xml`；本方案数据集 join 用 **@Select** 避免 namespace/路径歧义。`map-underscore-to-camel-case: true`（application.yml:145）→ DTO 隐式映射。

评估链路：
- `AnswerEvaluationService.evaluate()`：三维评估 + 无条件写 Redis（:68）。
- `evaluateAndPersist()`：写 Redis + 强制落库（tenantId=null 拒绝，:152-155 铁律）。
- 三个 `AnswerEvaluator` 各返回**单布尔** `boolean evaluate(query, context, answer)`；维度布尔存于 `passes`（LinkedHashMap<String,Boolean>），分数存于 `dimensionScores`（Map<String,Double>，键全名 relevancy/correctness/faithfulness）。

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

通过读取侧 join 按 `answer_eval_result.session_row_id = rag_session.id` 关联 `rag_session.feedback`：
- `feedback = 1` → humanLabel = 1；`feedback = -1` → humanLabel = -1；`feedback = 0` 或无关联 → **不纳入**。

### 3.2 数据集抽取（@Select + TenantContext schema + 租户断言）

#### 3.2.1 租户隔离方案

- **schema 取 `TenantContext.getSchema()`（四轮阻断项1 修正）**：由服务端在 JWT 过滤器经 `tenantId→tenant→getSchemaName()` 反查写入（JwtAuthenticationFilter:80-88），**不接受客户端传入、客户端不可控**；null/blank → **400**；再过白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`。与运行时 `search_path` 同一值，杜绝"SQL 指向 A / 拦截器设 B"错位。
- **ignoreTable 豁免**：`TenantMyBatisPlusConfig.ignoreTable` 追加 `answer_eval_result`、`rag_session`（同租户 schema，零全局影响，已核实两表既有查询均带显式租户断言）。
- **@Select 手写租户断言**：`e.tenant_id = #{tenantId}` **且 `s.tenant_id = #{tenantId}`**（四轮细节：rag_session 进入 ignoreTable 后无插件过滤器，显式断言让不变量可读，防未来删 schema 前缀）。

#### 3.2.2 数据集抽取方法

`dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
- `schema = TenantContext.getSchema()`（null/blank→400，白名单）。
- **from/to 均非 null 校验**：缺任一 → 400。
- **limit 缺省 `50`，越界收敛 ≤ model.maxDatasetLimit(200)**（四轮：对标 listResults:190 规则）。
- 输出 `List<LabelledEvalSample>`。

**@Select（`AnswerEvalResultMapper`）：**
```java
@Select(
  "SELECT DISTINCT ON (e.session_row_id) " +
  " e.query, e.context, e.answer, e.pass AS auto_pass, e.score AS auto_score, " +
  " s.feedback AS human_label, e.tenant_id AS tenant_id, " +
  " e.session_row_id, e.id AS eval_id, e.create_time " +
  "FROM ${schema}.answer_eval_result e " +
  "JOIN ${schema}.rag_session s ON s.id = e.session_row_id " +
  "WHERE e.tenant_id = #{tenantId} " +
  "  AND s.tenant_id = #{tenantId} " +
  "  AND e.session_row_id IS NOT NULL " +
  "  AND s.feedback <> 0 " +
  "  AND e.create_time BETWEEN #{from} AND #{to} " +
  "ORDER BY e.session_row_id, e.id DESC " +
  "LIMIT #{limit}")
List<LabelledEvalSample> selectDataset(@Param("schema") String schema,
    @Param("tenantId") Long tenantId, @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to, @Param("limit") int limit);
```
- `${schema}` 仅存 `TenantContext.getSchema()` + 白名单；其余 `#{}` 绑定。
- `ORDER BY e.session_row_id, e.id DESC`：`e.id` 二级排序保证 DISTINCT ON 取最新稳定。
- DTO 列→属性依赖 `map-underscore-to-camel-case`（隐式映射，不写 @Results）。

**`LabelledEvalSample`（含 tenantId）：**
```
query, context, answer, tenantId(Long),
autoPass(Boolean), autoScore(double), humanLabel(Short: 1/-1),
sessionRowId(Long), createTime(LocalDateTime)
```

#### 3.2.3 回归报告快照表

新增每租户表 `eval_regression_report`（幂等 DDL，含 `DROP POLICY IF EXISTS`）：

```sql
CREATE TABLE IF NOT EXISTS <schema>.eval_regression_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    rule_version VARCHAR(64) NOT NULL,          -- rag.eval.rule-version 配置项（四轮）
    dataset_fingerprint VARCHAR(64) NOT NULL,   -- md5(排序后 session_row_id 拼接)（Java 侧计算）
    dataset_from TIMESTAMP,
    dataset_to TIMESTAMP,
    sample_count INT NOT NULL,
    pass_rate DOUBLE PRECISION NOT NULL,
    avg_score DOUBLE PRECISION NOT NULL,
    avg_relevancy DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_correctness DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_faithfulness DOUBLE PRECISION NOT NULL DEFAULT 0,
    persisted_pass_agree DOUBLE PRECISION NOT NULL DEFAULT 0,  -- 重跑 pass vs 落库 pass 一致率（四轮新增）
    tp INT NOT NULL DEFAULT 0,
    tn INT NOT NULL DEFAULT 0,
    fp INT NOT NULL DEFAULT 0,
    fn INT NOT NULL DEFAULT 0,
    accuracy DOUBLE PRECISION NOT NULL DEFAULT 0,
    precision DOUBLE PRECISION NOT NULL DEFAULT 0,
    recall DOUBLE PRECISION NOT NULL DEFAULT 0,
    f1 DOUBLE PRECISION NOT NULL DEFAULT 0,
    negative_recall DOUBLE PRECISION NOT NULL DEFAULT 0,
    relevancy_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
    correctness_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
    faithfulness_agree DOUBLE PRECISION NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_time
    ON <schema>.eval_regression_report (tenant_id, run_time DESC);
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_fp
    ON <schema>.eval_regression_report (tenant_id, dataset_fingerprint);
ALTER TABLE <schema>.eval_regression_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.eval_regression_report FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_eval_rep ON <schema>.eval_regression_report;
CREATE POLICY tenant_isolation_eval_rep ON <schema>.eval_regression_report
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT SELECT, INSERT ON <schema>.eval_regression_report TO company_rag_app;
GRANT USAGE, SELECT ON SEQUENCE <schema>.eval_regression_report_id_seq TO company_rag_app;
```
> `eval_regression_report_id_seq` 为 BIGSERIAL 默认 sequence 名（四轮细节，DDL 注明）。后续如需保留/清理策略，需补 `DELETE` 授权。

**快照 INSERT 显式租户**：insert 前显式 `setTenantId(tenantId)` + null 拒绝（对齐铁律）。

**迁移语义（四轮阻断项3）**——三处落位：
1. `TenantServiceImpl.createTenantSchema`：（新租户）新增表 + 索引 + RLS。
2. `SchemaMigrationConfig`：**新增 `migrateEvalRegressionReportTable` runner**，同既有 `migrateAnswerEvalResultTable`（:175-186）范式——遍历 `information_schema` 中 `tenant_%` schema 逐个幂等建表。**存量租户借此补表**（否则老租户 /regression → relation 不存在）。
3. `sql/init.sql`：存档同步。**位置约束（四轮）**：新表的 `CREATE POLICY ... USING(tenant_id = current_tenant_id())` 建策略时校验函数存在，须排在 `current_tenant_id()` 定义之后；**或**仅作为存档说明，实际建表由 runner 执行（runner 执行时函数必已定义），两种方式文档需写明，避免顺序之谜。

### 3.3 回归重跑报告

`regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
- `limit` **原样透传给 dataset**（四轮：保证"同一批样本"口径，否则 limit 差异即不同批）。
1. `dataset(...)` 取当批样本；**0 样本 → 不落快照，返回明确提示**；
2. **Java 侧计算指纹**（四轮阻断项3）：对返回列表 `sessionRowId` 排序后 `md5(join(",", ids))`——与返回集天然一致，免 0 行 `string_agg` NULL；取 `rule_version = rag.eval.rule-version`；
3. 逐个 `evaluateNoCache()` 重跑（R3，无 Redis 写）；
4. 与 humanLabel + 落库 pass 比对生成报告；
5. **落快照**（显式租户 + 指纹 + rule_version + from/to + persisted_pass_agree），返回含 `reportId`。

**四格**（autoPass vs humanLabel，human=1 正类）：TP/TN/FP/FN。

**指标公式（三维一致率 + persisted_pass_agree）：**
```
n=sampleCount; accuracy=(tp+tn)/n; passRate=autoPass占比; avgScore=均分
precision=tp/(tp+fp)  分母0→1.0
recall=tp/(tp+fn)     分母0→1.0
f1=2pr/(p+r)          p=r==0→0.0       -- 灾难场景不可显示为满分
negative_recall=tn/(tn+fp)  分母0→1.0
relevancy_agree   = |{i: relevancy_i == (humanLabel_i>0)}| / n
correctness_agree = |{i: correctness_i == (humanLabel_i>0)}| / n
faithfulness_agree= |{i: faithfulness_i == (humanLabel_i>0)}| / n
   映射：humanLabel +1→正 / -1→负；维度布尔 vs 正负同向；分母固定 n。
persisted_pass_agree = |{i: re_run_pass_i == persisted_pass_i}| / n（四轮新增）
   -- 重跑 pass vs 落库 pass 一致率；不比 rule_version 更早暴露"规则被悄悄改"，无需额外元数据。
```

**`doEvaluate` 与 `EvalDecision`（四轮阻断项2）**：
- 新增私有 record:
  ```java
  record EvalDecision(boolean relevancy, boolean correctness, boolean faithfulness,
                      boolean pass, double score) {}
  ```
- 私有 `doEvaluate(query, context, answer)` → `EvalDecision`：由各 `AnswerEvaluator` 的布尔 + `passes` 映射得来。
- `evaluate()`/`evaluateAndPersist()`/`evaluateNoCache()` 三处统一走 `doEvaluate`；落库侧继续用 `dimensionScores`（Map<String,Double>，键**全名** relevancy/correctness/faithfulness）传分数。三维一致率用 `EvalDecision` 布尔维度。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 | 并发/校验 |
|---|---|---|---|
| POST | `/api/eval/dataset` | 抽样数据集 `List<LabelledEvalSample>`（from/to/limit） | from/to 非空→缺 400；limit 缺省 50、越界≤200 |
| POST | `/api/eval/regression` | 跑回归 + 落快照（reportId/指纹/rule_version） | Service 按 tenantId 键锁 + tryLock(30s) 失败→409；0 样本返回明确提示 |
| GET | `/api/eval/history` | 历史快照**分页**(page/pageSize)，按指纹归因 | 只读；page/pageSize 缺省（对标 /results 50） |

口径与边界：
- 统一 `@PreAuthorize("hasAnyRole('ADMIN','USER')")`（防 viewer 读 context/answer 原文）。
- `/regression` **POST**；**Service 层按 tenantId 键锁**（`ConcurrentHashMap<tenantId, ReentrantLock>`，不跨租户互阻）。**超时语义（四轮钉死）**：**tryLock(30s) 失败 → 409，不做任何重跑**（附录基线：全流程 600 次毫秒级几乎不超时，30s 仅在抢锁时触发，勿与全流程超时混淆）。
- schema 取 `TenantContext.getSchema()`（服务端反查，客户端不可控）。

### 3.5 与既有机制边界

- 不触碰：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- 新增 `evaluateNoCache`/`doEvaluate`/`dataset`/`regression`；`evaluate()`/`evaluateAll()` 契约不变。
- 租户插件仅追加 ignoreTable 豁免两表；不改 append。
- 新增表三处同步（见 §3.2.3 迁移语义）。

### 3.6 数据源前置依赖

| 项 | 现状 | 影响 |
|---|---|---|
| `rag.eval.online-enabled` | 默认 `false`（ChatController:56）；dev 显式 `true`（application-dev.yml:81） | 生产需开启才有在线样本沉淀 |
| 落库条件 | 仅 `result.isRagUsed()` 行评估落库（ChatController:173） | 非 RAG 回答不产生样本 |

**结论**：接入闭环须将生产 `rag.eval.online-enabled` 置 `true`（否则反馈攒再多数据集恒空、首跑空报告）。此为本方案**可行性前置条件**，部署时核对。

## 4. 数据流

```
用户反馈 → rag_session.feedback（已有，不变）
   ↓
POST /api/eval/dataset（ADMIN/USER, from/to 非空, limit缺省50）
   → schema = TenantContext.getSchema()（服务端反查）
   → @Select join（ignoreTable 豁免 + e/s 显式租户断言 + DISTINCT ON + e.id 排序）
   ↓
POST /api/eval/regression（POST, tenant 键锁, tryLock 30s 失败→409）
   → dataset（limit 透传）→ Java 侧指纹 + rule_version
   → evaluateNoCache 逐个重跑(doEvaluate→EvalDecision)
   → 四格 + accuracy/precision/recall/负类召回/F1/三维一致率/persisted_pass_agree
   → 0 样本不落快照; 否则显式租户落快照 → 返回 reportId
   ↓
GET /api/eval/history（分页, 按指纹归因）
```

## 5. 测试策略（最窄范围）

- **rag `AnswerEvaluationServiceTest`**：
  - `dataset`：feedback≠0 纳入；DISTINCT ON + `e.id DESC` 取最新（同轮多行同秒）；显式 `e`/`s` 租户断言；`TenantContext.getSchema()` null/blank→400；schema 白名单；from/to 非空；limit 缺省 50 / 越界≤200；**越权 schema 名被拒**；
  - `regression`：TP/TN/FP/FN + 各指标；F1 p=r==0→0（全 FN）；分母 0 退化；**0 样本不落快照**（mock 验证 return 不落库）；`evaluateNoCache` 不触发 Redis（R3）；快照显式 tenantId(null 拒绝)+fingerprint+rule_version+persisted_pass_agree 正确写入；**指纹 = Java 侧排序拼接后 md5，与返回集一致**；**rule_version == 配置值**；
  - `doEvaluate`→`EvalDecision`：`evaluate`/`evaluateAndPersist`/`evaluateNoCache` 三路径结果一致；落库侧 dimensionScores 用全名键。
- **web `EvalControllerTest`**：ADMIN/USER、viewer 拒、租户头缺拒、越权过滤、/history 分页（page/pageSize 缺省 + `run_time DESC, id DESC` 不漏行）、/regression 并发（同租户串行/异租户并行）、tryLock 失败→409、from/to 缺→400。
- **跨租户 IT（M1）**：两租户数据互不可见。
- **schema 建表测试**：`TenantServiceImplSchemaTest` 补新表建表 + 索引 + RLS + 幂等（DROP POLICY 重跑）。
- **迁移 runner 测试**：存量 schema 补表幂等（对齐 migrateAnswerEvalResultTable 用例）。
- 验证命令：`mvn -pl company-rag-rag -am test -Dtest=AnswerEvaluationServiceTest`、`mvn -pl company-rag-web -am test -Dtest=EvalControllerTest`（**-am 编译依赖模块**，四轮）。

## 6. 改动清单

- **租户插件**：Modify `TenantMyBatisPlusConfig.java`：`ignoreTable` 追加 `answer_eval_result`、`rag_session`。
- **数据库**：Modify `TenantServiceImpl.createTenantSchema` / **Modify `SchemaMigrationConfig`（新增 `migrateEvalRegressionReportTable` runner，同 migrateAnswerEvalResultTable 范式，覆盖存量 schema）** / Modify `sql/init.sql`（存档，标注 policy 依赖 `current_tenant_id()` 的位置约束）。
- **rag 模块**：
  - Create `LabelledEvalSample.java`（含 tenantId）
  - Create `EvalRegressionReport.java`（报告 DTO，含地位字段）+ `EvalRegressionReportEntity.java`（快照实体，列对齐）
  - Create `EvalRegressionReportMapper.java`（`@Select` 插入 + 历史分页）
  - Modify `AnswerEvalResultMapper.java`：新增 `@Select selectDataset`（含 `s.tenant_id` 断言）
  - Modify `AnswerEvaluationService.java`：抽 `doEvaluate`→`EvalDecision`；新增 `evaluateNoCache`、`dataset`、`regression`（TenantContext schema + 指纹 + rule_version + persisted_pass_agree + 空样本不落 + 显式租户落库）
- **web 模块**：Modify `EvalController.java`：新增 `POST /dataset`、`POST /regression`、`GET /history`（ADMIN/USER；from/to 校验；limit 缺省 50 越界 200；tenant 键锁 + tryLock 30s→409；分页 + 二级排序）。
- **配置**：Modify `application-dev.yml`（`application.yml` 若含 `rag` 段则同步）：
  - `rag.eval.regression-gate-enabled: false`（占位）
  - `rag.eval.rule-version: ...`（真实版本源，四轮；改规则须同步更新）
  - `rag.eval.regression-lock-timeout-ms: 30000`
  - `rag.eval.dataset-limit-max: 200`、`rag.eval.dataset-limit-default: 50`
  - `rag.eval.regression-concurrency-keys: tenant`
  - 生产核对 `rag.eval.online-enabled: true`（§3.6）
- **测试**：Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`、`TenantServiceImplSchemaTest`；新增跨租户 IT + migrate runner 用例。

## 7. 风险与观察项（正确口径 + 四轮）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **schema/IDOR（阻断项1）** | 客户端可传 schema+tenantId 构造他租户 | schema 取 `TenantContext.getSchema()`（JWT 服务端反查，客户端不可控）+ 白名单；X-Tenant-Id 已被 JWT tenantIds 校验 |
| **回归上下文局限** | 回归测固定历史 toolContext（ChatController:178 快照），不覆盖检索/知识库更新 | 快照带 rule_version；文档明示口径边界 |
| **存量租户缺表（阻断项3）** | 仅 createTenantSchema 不覆盖存量 | `migrateEvalRegressionReportTable` runner 补存量 schema |
| **init.sql 顺序** | policy 依赖 current_tenant_id() 存在 | 存档标注位置约束，或仅存档、建表交 runner |
| join 租户 ambiguous | 插件为两表各追加裸 tenant_id | ignoreTable 豁免 + @Select 手写 `e`/`s` 显式租户断言 |
| RLS 非兜底（M1） | 主防线 schema 隔离；RLS best-effort | SQL 显式租户断言为主；跨租户 IT 锁死 |
| 样本批不可复现归因 | 动态视图下次样本集变化 | 快照带 fingerprint+from/to+索引；fingerprint=f(完整输入)；/history 按指纹归因 |
| 同轮多行重复（M2） | 在线多入口成多行 | DISTINCT ON + `e.id DESC` |
| 回归污染缓存（R3） | evaluate 无条件写 Redis | 回归走 evaluateNoCache |
| 租户丢失（R2） | DTO 无 tenantId 落 0 | DTO 带 tenantId；快照显式 setTenantId+null 拒绝 |
| from/to 缺失静默空 | BETWEEN null 恒空集+200 | 非空校验→400 |
| 空样本撞 NOT NULL | string_agg 0 行 null（现已 Java 侧计算） | 0 样本不落快照+明确提示 |
| 数据源空 | online-enabled 默认 false+仅 RAG 行 | §3.6 前置声明 |
| 数据外泄（M5） | dataset 返回 context/answer 原文 | /dataset /regression /history 统一 ADMIN/USER |
| GET 重计算副作用（M4） | 重计算+落库+缓存风险 | POST；tenant 键锁；tryLock 失败→409 |
| 指标语义错位（M3） | pass 硬与门 vs humanLabel 主观 | 补 F1/负类召回/三维一致率/persisted_pass_agree；语义说明入文档 |
| F1 退化误报 | p=r==0 是灾难场景 | f1→0，与 precision/recall 分开处理 |
| rule_version 漂移 | eval 包无版本概念 | 配为 `rag.eval.rule-version`；测试断言写入==当前值；"改规则同步改版本"进评审检查项 |
| SQL 注入 | schema 名/输入 | schema 取 TenantContext + 白名单；其余绑定 |
| 快照表膨胀 | 只增不删 | 观察项；保留/清理策略需补 DELETE 授权 |
| 统计噪声误判 | 200 样本 accuracy CI≈±7% | 附录公式；以统计显著差异为门槛 |
| config 误配 | Mapper 未扫描 | 新 Mapper 落既有 `com.company.rag.rag.eval.answer` 包（已 @MapperScan）；单测断言 |

## 8. 后续演进（本期不做）

- 固化逐样本数据集快照（`evalset` 表 + 版本）实现完整可复现评测。
- 硬门禁接入关键路径（`regression-gate-enabled` 占位）。
- 冷启动种子样本、反馈量不足采样降级。
- 回归报告/历史趋势接入可观测看板。
- 观察项：未来如需跨表别名插件级 join 租户自动追加，再评估处置 b（append）。

## 附录：超时与实际耗时基线 / 统计显著门槛

- **tryLock(30s) 语义（钉死）**：仅锁等待超时；**失败 → 409，不做任何重跑**。三个 `AnswerEvaluator` 均纯本地规则（无 HTTP/Embedding），600 次判定为内存字符串处理、毫秒级，**全流程几乎不可能超时**——30s 实际只在抢锁时触发。实现后补**实测 wall-time 基线**写回本附录。
- **最小可检测差异**：accuracy 95% CI ≈ `1.96 × √(p(1−p)/n)`，p=0.5 时取最大 ≈ `1.96×√(0.25/200) ≈ 6.9%`。**样本量越大区间越窄**（如 n=800 → ≈±3.5%）。团队对趋势对比应以统计显著差异为门槛，勿对 ±7% 内噪声调参。