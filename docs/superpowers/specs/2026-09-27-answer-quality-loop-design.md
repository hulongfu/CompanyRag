# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计 v3

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅（v3 整合二轮审阅修正）
> 版本变更：
> - v1→v2：整合首轮审阅 R1-R3 / M1-M7。
> - v2→v3：整合二轮审阅——①数据集 join 由 XML 改为 **@Select 注解 SQL**（消除 XML 加载歧义）；②移除处置 b（公共插件改动），改为 **ignoreTable 豁免 + @Select 手写租户断言**，b 降为观察项；③快照表补 **dataset_fingerprint**（样本批可复现/归因）；④F1 退化分支修正；⑤快照 INSERT 显式租户；⑥schema/tenantId 同源校验；⑦多组口径与细节补缺。
> 前置能力（均已落地）见 v1。

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答中动态筛出评测样本；回归时对当批样本生成**指纹**以保可复现归因。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall/F1 + 负类召回 + 分维度一致率），并**落快照**（含样本批指纹）支持跨版本趋势与正确归因。

**约束：**
- **最小可行（MVP）**：数据集为动态视图 + 每批回归落一份**含指纹的聚合快照**；硬门禁仅留**可选开关占位（默认关）**，本期不实现阻断逻辑。
- **反馈路径零改动**：`updateFeedback`、`rag_session.feedback` 列不动；反馈联动通过读取侧 join 实现。
- **回归重跑不污染线上缓存**（R3）：回归走 `evaluateNoCache`（无 Redis 写）。
- **最小化全局插件影响**（二轮阻断项2）：不修改租户插件 append；用 **ignoreTable 豁免 + 手写租户断言** 解决 join，避免波及既有单表查询与在线 INSERT 路径。

## 2. 现状回顾（真实机制）

多租户隔离的真实主次顺序：
- **主防线 = Schema 物理隔离**：`TenantSchemaInterceptor` 每次查询前 `SET search_path TO <schema>, public`（:96）。
- **辅助防线 = RLS（best-effort）**：`SET app.tenant_id`（:98）；两表 RLS 用 `tenant_id = current_tenant_id()`，`current_tenant_id()` 经 `COALESCE(...,0)` 兜底；`TenantSchemaInterceptor:45` 明示 best-effort，`TenantMyBatisPlusConfig:24-25` 承认连接池跨连接风险。
- **结论**：应用层 SQL 必须**显式携带租户断言**，不依赖 RLS。

评估链路现状：
- `AnswerEvaluationService.evaluate()`：三维评估 + 无条件写 Redis（:68 → writeToRedis, key=md5(query), TTL 24h）。
- `evaluateAndPersist()`：写 Redis + 强制落库（tenantId=null 拒绝）。
- 用户反馈只写 `rag_session.feedback`，`answer_eval_result` 不知道用户评价。
- 租户插件 `TenantLineInnerInterceptor`：`ignoreTable` 豁免 sys_tenant/sys_user/sys_user_tenant_rel/audit_log；`getTenantIdColumn()`返回裸 `tenant_id`（join 会 ambiguous）。

**MyBatis XML 加载事实（二轮核实）**：项目用 `mybatis-plus-spring-boot3-starter`，无显式 `mapper-locations`；MyBatis-Plus 的 `MybatisPlusProperties.mapperLocations` 字段默认值为 `classpath*:/mapper/**/*.xml`，未覆盖即生效（现有 `RagSessionMetaMapper.xml` 的 `selectSessionList` 被 `RagSessionServiceImpl` 调用即证明默认扫描生效）。因此**新增 XML 需要 namespace 与接口全限定名严格一致 + 置于 `classpath*:/mapper/**/`**，否则静默匹配失败。**为彻底消除该歧义，本方案数据集 join 改用 `@Select` 注解 SQL（见 3.2），不新增 XML。**

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

**不**在 `updateFeedback` 里同步写 `answer_eval_result`。反馈联动通过**读取侧 join**实现：按 `answer_eval_result.session_row_id = rag_session.id` 关联回 `rag_session.feedback` 得到人工标签。

- `feedback = 1` → humanLabel = 1；`feedback = -1` → humanLabel = -1；`feedback = 0` 或无关联 → **不纳入**。

### 3.2 数据集抽取（@Select 注解 SQL + 租户断言）

#### 3.2.1 租户隔离方案（二轮阻断项2，替代处置 b）

**否决 v2 的处置 b（改租户插件 append）** —— 会波及所有无别名的既有单表查询（missing FROM-clause entry 风险）与在线评估 INSERT 路径（ChatController:190-192）。改为更轻的两条：
- **ignoreTable 豁免**：在 `TenantMyBatisPlusConfig.ignoreTable` 追加 `answer_eval_result` 与 `rag_session`——同属租户 schema，豁免后靠 schema 隔离 + SQL 手写租户断言，**零全局影响**（对齐既有 sys_tenant/audit_log 豁免先例）。
- **@Select 手写租户断言**：数据集查询在注解 SQL 里显式写 `e.tenant_id = ?`（带别名），schema 前缀字符串拼接（标识符不可参数化）。

> **处置 b 降级为观察项**：仅当未来出现跨表别名需要插件级 join 租户自动追加时再评估，不属本方案范围。

#### 3.2.2 数据集抽取方法

在 `AnswerEvaluationService` 新增 `dataset(Long tenantId, String schema, LocalDateTime from, LocalDateTime to, int limit)`，输出 `List<LabelledEvalSample>`。

- **schema 与 tenantId 同源校验（二轮口径）**：`schema` 由调用方显式传入，`tenantId` 显式传入；方法入口校验二者同源（schema 对应的租户与 tenantId 一致，如 schema 名含租户标识或由 `TenantContext` 同一来源取得），**不一致即拒绝**，防止断言失效与越权。
- **schema 白名单**：`^[a-zA-Z_][a-zA-Z0-9_]*$`（复用 `TenantServiceImpl` 既有校验），防 SQL 注入。
- **@Select 注解 SQL**（写在 `AnswerEvalResultMapper` 上，二者 id 绑定，无 XML 加载歧义）：

```java
@Select(
  "SELECT DISTINCT ON (e.session_row_id) " +
  " e.query, e.context, e.answer, e.pass AS auto_pass, e.score AS auto_score, " +
  " s.feedback AS human_label, e.tenant_id AS tenant_id, " +
  " e.session_row_id, e.id AS eval_id, e.create_time " +
  "FROM ${schema}.answer_eval_result e " +
  "JOIN ${schema}.rag_session s ON s.id = e.session_row_id " +
  "WHERE e.tenant_id = #{tenantId} " +
  "  AND e.session_row_id IS NOT NULL " +
  "  AND s.feedback <> 0 " +
  "  AND e.create_time BETWEEN #{from} AND #{to} " +
  "ORDER BY e.session_row_id, e.id DESC " +
  "LIMIT #{limit}")
List<LabelledEvalSample> selectDataset(@Param("schema") String schema,
    @Param("tenantId") Long tenantId, @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to, @Param("limit") int limit);
```
- `schema` 用 `${}`（标识符），**必须**经白名单校验后传入；其余值用 `#{}` 参数绑定。
- `ORDER BY e.session_row_id, e.id DESC`：**二级排序用 `e.id`**（二轮细节——create_time 为 DEFAULT CURRENT_TIMESTAMP，同轮多行可能同秒同值，DISTINCT ON 结果不确定，加 `e.id DESC` 保证取最新稳定）。

**DTO `LabelledEvalSample`（含 tenantId，R2）：**
```
query, context, answer,
tenantId(Long), autoPass(Boolean), autoScore(double),
humanLabel(Short: 1/-1), sessionRowId(Long), createTime(LocalDateTime)
```

#### 3.2.3 回归报告快照表（含样本批指纹）

新增每租户表 `eval_regression_report`（`GRANT` 列清单补全，二轮细节）：

```sql
CREATE TABLE IF NOT EXISTS <schema>.eval_regression_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    dataset_fingerprint VARCHAR(64) NOT NULL,   -- md5(string_agg(session_row_id::text,','))：样本批指纹（二轮阻断项3）
    dataset_from TIMESTAMP,                     -- 数据集时间窗（样本可复现归因）
    dataset_to TIMESTAMP,
    sample_count INT NOT NULL,
    pass_rate DOUBLE PRECISION NOT NULL,
    avg_score DOUBLE PRECISION NOT NULL,
    avg_relevancy DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_correctness DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_faithfulness DOUBLE PRECISION NOT NULL DEFAULT 0,
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
ALTER TABLE <schema>.eval_regression_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.eval_regression_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_eval_rep ON <schema>.eval_regression_report
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT SELECT, INSERT ON <schema>.eval_regression_report TO company_rag_app;
GRANT USAGE, SELECT ON SEQUENCE <schema>.eval_regression_report_id_seq TO company_rag_app;
```

**快照 INSERT 显式租户（二轮口径）**：`EvalRegressionReportMapper.insert()` 仍走租户插件，会按 `TenantContext.getTenantId()` 追加 `tenant_id` 列；本方案在写入前**显式 `setTenantId(tenantId)` 且 null 拒绝**，对齐 `evaluateAndPersist`（AnswerEvaluationService:149-155）铁律，避免隐式落 0 被新表 RLS `WITH CHECK` 拒绝抛错。

> 该表三处同步：`SchemaMigrationConfig`（存量）/ `TenantServiceImpl.createTenantSchema`（新租户）/ `sql/init.sql`（存档）。

**指纹计算**（三则阻断项3）：
```
fingerprint = md5(string_agg(session_row_id::text, ','))  -- 由回归内部对当批样本 sessionRowId 排序后聚合计算；样本集识别号。
```
> 口径说明（M3 / 二轮）：
> - `pass = allPass(三维)` 是**硬与门机制判定**，`humanLabel` 是**用户主观评价**，二者**非同一语义**；accuracy 天然偏低属预期。该说明置于**文档**，不放入 API 数据字段。
> - 报告字段含 `f1 / negativeRecall / relevancyAgree / correctnessAgree / faithfulnessAgree`，供调参者定位维度不一致。
> - **F1 退化修正（二轮口径）**：precision/recall 分母为 0 取 1.0（该类样本缺失的惯例）；但 **f1=2pr/(p+r) 在 p=r=0 时是 0/0，恰为"所有正样本被误判为负"的灾难场景，必须取 f1=0**，与 precision/recall 分开处理。

### 3.3 回归重跑报告

新增 `regression(Long tenantId, String schema, LocalDateTime from, LocalDateTime to, int limit)`：
1. `dataset(...)` 取当批样本；
2. 计算样本批 `dataset_fingerprint`；
3. 逐个用 `evaluateNoCache()` 重跑（R3：不写 Redis、不落库），得新 autoPass/autoScore；
4. 与 humanLabel 比对，生成 `EvalRegressionReport`；
5. **落一份快照**（含指纹 + run_time + from/to）到 `eval_regression_report`，返回含 `reportId` 的报告。

**四格**（autoPass vs humanLabel，human=1 为正类）：TP/TN/FP/FN（同 v2）。

**指标公式：**
```
sampleCount=n; passRate=autoPass 占比; avgScore=均分
accuracy=(tp+tn)/n
precision=tp/(tp+fp), 分母0→1.0
recall=tp/(tp+fn),    分母0→1.0
f1=2pr/(p+r),         p=r=0→0.0（修正）
negativeRecall=tn/(tn+fp), 分母0→1.0
relevancyAgree/correctnessAgree/faithfulnessAgree = 各维度判定与 humanLabel 的一致率
```

**`evaluateNoCache` 重载（R3）**：
- 在 `AnswerEvaluationService` 增加 `evaluateNoCache(AnswerCase)`：与 `evaluate()` 相同的三维判定，但**跳过 writeToRedis**。
- **避免第三份重复代码**（二轮细节）：抽取**私有 `doEvaluate(query, context, answer)`** 返回共享判定（pass/scores），`evaluate()`、`evaluateAndPersist()`、`evaluateNoCache()` 三处统一走它，仅缓存写与落库行为不同。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 | 并发 |
|---|---|---|---|
| POST | `/api/eval/dataset` | 抽样有标签数据集 `List<LabelledEvalSample>`（from/to/limit） | 只读级 |
| POST | `/api/eval/regression` | 跑回归 + 落快照（含 reportId 与指纹） | **Service 层按 tenantId 键锁** |
| GET | `/api/eval/history` | 查询历史快照（**page/pageSize 分页**，二轮细节），按指纹可分组 | 只读级 |

口径与边界（M4/M5 + 二轮）：
- 统一 `@PreAuthorize("hasAnyRole('ADMIN','USER')")`（M5，防 viewer 读知识库原文 context/answer）。
- `/regression` 用 **POST**（M4：重计算 + 副作用，避免 GET 缓存/预取）；**Service 层按 tenantId 键锁**（`ConcurrentHashMap<tenantId, ReentrantLock>`，二轮：避免全局串行跨租户互阻），声明**超时上限 30s**，超时/繁忙返回明确提示而非静默丢弃。
- schema 与 tenantId 由调用方经请求头 `X-Tenant-Id` + 会话/上下文显式传入并**同源校验**（见 3.2.2）。

### 3.5 与既有机制边界

- **不触碰**：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- `evaluateNoCache`/`doEvaluate`/`dataset`/`regression` 为新增；`evaluate()`/`evaluateAll()` 契约不变。
- 租户插件仅**追加 ignoreTable 豁免**两条表（零行为变更于既有查询）；不改 append。
- 新增 `eval_regression_report` 表遵循三处同步（D2）。

## 4. 数据流

```
用户反馈 → rag_session.feedback（已有，不变）
   ↓
POST /api/eval/dataset → @Select join（ignoreTable 豁免 + 手写租户断言 + DISTINCT ON 取最新）
   ↓
POST /api/eval/regression → dataset → 指纹 → evaluateNoCache 逐个重跑
   → 四格 + accuracy/precision/recall/F1/负类召回/维一致率
   → 显式租户落快照(eval_regression_report 含 fingerprint) → 返回含 reportId
   ↓
GET /api/eval/history → 分页查历史快照（按指纹归因）
```

## 5. 测试策略（最窄范围）

- **rag 模块 `AnswerEvaluationServiceTest`**：
  - `dataset`：仅纳入 feedback≠0；DISTINCT ON + `e.id DESC` 取最新（构造同轮多行同秒用例）；显式租户过滤；schema 白名单校验（非法 schema 拒绝）；schema/tenantId 不同源拒绝；limit 上限 200；
  - `regression`：已知样本构造 TP/TN/FP/FN 与 accuracy/precision/recall/F1/负类召回/维一致率；**F1 在 p=r=0 → 0.0**（构造全 FN 用例）；分母为 0 退化分支；空数据；`evaluateNoCache` **不触发 Redis 写**（mock 验证，R3）；快照落库显式 tenantId（null 拒绝）+ 指纹正确写入；
  - `doEvaluate` 抽取后三路径结果一致。
- **web 模块 `EvalControllerTest`**：`ADMIN/USER` 鉴权、viewer 拒绝（M5）、租户头缺失拒绝、越权过滤、`/history` 分页参数校验、`/regression` 并发锁（同 tenant 串行、异 tenant 并行）。
- **跨租户集成测试（M1）**：两租户 schema 数据互不可见，锁死"主防线=显式租户断言 + RLS best-effort"。
- **schema 建表测试**：`TenantServiceImplSchemaTest` 补 `eval_regression_report` 建表 + RLS 断言（二轮细节）。
- 验证命令：`mvn test -Dtest=AnswerEvaluationServiceTest -pl company-rag-rag`、`mvn test -Dtest=EvalControllerTest -pl company-rag-web`（根 reactor 联合编译避免旧 common 快照）。

## 6. 改动清单

- **租户插件**
  - Modify `TenantMyBatisPlusConfig.java`：`ignoreTable` 追加 `answer_eval_result`、`rag_session`（豁免，无行为变更于既有查询；替代 v2 处置 b）。
- **数据库（三处同步）**
  - Modify `TenantServiceImpl.createTenantSchema`：新增 `eval_regression_report` 建表 + 索引 + RLS（新租户）。
  - Modify `SchemaMigrationConfig`：存量 `tenant_%` 幂等建表 + 索引 + RLS。
  - Modify `sql/init.sql`：同步存档。
- **rag 模块**
  - Create `LabelledEvalSample.java`（含 tenantId）
  - Create `EvalRegressionReport.java`、`EvalRegressionReportEntity.java`、`EvalRegressionReportMapper.java`（含 fingerprint/from/to/负类召回/维一致率字段）
  - Modify `AnswerEvalResultMapper.java`：新增 `selectDataset`（@Select，schema 白名单 + 租户断言 + DISTINCT ON 取最新）
  - Modify `AnswerEvaluationService.java`：抽 `doEvaluate`；新增 `evaluateNoCache`、`dataset`、`regression`（含指纹计算 + 显式租户快照落库）
- **web 模块**
  - Modify `EvalController.java`：新增 `POST /dataset`、`POST /regression`、`GET /history`（统一 ADMIN/USER；tenantId 键锁 + 超时 30s；分页）
- **配置**
  - Modify `application-dev.yml`（`application.yml` 若含 `rag` 段则同步）：
    - `rag.eval.regression-gate-enabled: false`（占位）
    - `rag.eval.regression-timeout-ms: 30000`
    - `rag.eval.dataset-limit-max: 200`
    - `rag.eval.regression-concurrency-keys: tenant`（按租户键锁）
- **测试**
  - Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`、`TenantServiceImplSchemaTest`；新增跨租户隔离 IT。

## 7. 风险与观察项（正确口径）

| 风险 | 说明 | 缓解 |
|---|---|---|
| join 租户插件 ambiguous | TenantLineInnerInterceptor 会为 join 两表各追加裸 `tenant_id` | 两表加入 `ignoreTable`（零全局影响）+ @Select 手写带别名 `e.tenant_id` 断言 |
| RLS 非兜底（M1） | 主防线是 schema 隔离；RLS 依赖连接级 app.tenant_id，有连接池跨连接风险 | SQL 显式租户断言为主；跨租户 IT 锁死 |
| 样本批不可复现（二轮阻断项3） | 纯动态视图下下次样本集变化，趋势落差归因错误 | 快照带 `dataset_fingerprint` + from/to；/history 按指纹归因 |
| 同轮多行重复计入（M2） | 在线同轮多入口可成多行 | `DISTINCT ON (session_row_id)` + `e.id DESC` 取最新稳定 |
| 回归污染在线缓存（R3） | evaluate() 无条件写 Redis | 回归走 `evaluateNoCache`（跳过 writeToRedis） |
| 租户丢失（R2） | 样式集 DTO 无 tenantId 会落 tenant_id=0 | DTO 带 tenantId；快照 insert 显式 setTenantId + null 拒绝 |
| schema/tenantId 不同源 | 断言失效、越权 | 方法入口同源校验，不一致即拒绝 |
| 数据外泄（M5） | dataset 返回 context/answer 原文 | /dataset、/regression、/history 统一 ADMIN/USER |
| GET 重计算副作用（M4） | 重计算 + 落库 + 缓存风险 | /regression 改 POST；Service 层 tenantId 键锁 + 超时 30s |
| 指标语义错位（M3） | pass 硬与门 vs humanLabel 主观评价 | 报告补 F1/负类召回/维一致率；语义说明入文档 |
| F1 退化误报（二轮） | p=r=0 时 f1 是 0/0，取 1.0 会把灾难场景显示成满分 | 修正为 p=r==0 → f1=0，与 precision/recall 分开处理 |
| 快照 INSERT 隐式租户 | MP 插件追加 tenant 列，隐式落 0 被 RLS 拒 | insert 前显式 setTenantId + null 拒绝 |
| 快照表膨胀 | 只增不删 | 观察项；后续加清理/保留策略 |
| config 潜在误配 | 新增 @MapperScan 不含新 Mapper 包时扫描不到 | 新 Mapper 已落在既有 `com.company.rag.rag.eval.answer` 包（已扫描）；建单测断言 |

## 8. 后续演进（本期不做）

- 固化**逐样本**数据集快照（`evalset` 表 + 版本）实现完整可复现评测（当前以指纹 + 时间窗近似识别样本批）。
- 硬门禁真正接入关键路径（发布/上线），按指标阈值阻断或告警（`regression-gate-enabled` 占位）。
- 冷启动种子样本、反馈量不足时采样降级。
- 将回归报告/历史趋势接入可观测看板。
- 观察项：如未来出现跨表别名需插件级 join 租户自动追加，再评估处置 b（append）。