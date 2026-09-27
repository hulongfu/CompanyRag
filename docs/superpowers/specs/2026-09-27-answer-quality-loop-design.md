# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计 v4

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅（v4 整合三轮审阅修正）
> 版本变更：
> - v1→v2：整合首轮审阅 R1-R3 / M1-M7。
> - v2→v3：XML→@Select、b 降观察项改 ignoreTable、dataset_fingerprint、F1 退化修正、显式租户、同源校验。
> - v3→v4：整合三轮审阅——①schema 由服务端从 tenantId **推导（`tenant_` + tenantId），不接受客户端传入**，同源校验即该推导的断言（钉死 IDOR）；②快照补 **rule_version**（规则维度，锁住"规则/上下文"变化）；③from/to 非空校验 + 空样本不落快照；④doEvaluate 返回三维；⑤三维一致率公式明确；⑥补 (tenant_id,dataset_fingerprint) 索引；⑦fingerprint 口径（完整输入决定批次）；⑧online-enabled 数据源依赖前置声明；⑨多项细节。
> 前置能力（均已落地）见 v1。

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答中动态筛出评测样本；回归对当批样本生成**指纹**并以 `rule_version` 锁定规则，支持可复现归因。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall/负类召回/F1 + 分维度一致率），**落快照**（含样本指纹 + 规则版本）支持跨版本趋势与正确归因。

**约束：**
- **最小可行（MVP）**：数据集为动态视图 + 每批回归落一份**含指纹+规则版本的聚合快照**；硬门禁仅留**可选开关占位（默认关）**。
- **反馈路径零改动**：`updateFeedback`、`rag_session.feedback` 不动；反馈联动通过读取侧 join。
- **回归重跑不污染线上缓存**（R3）：走 `evaluateNoCache`（无 Redis 写）。
- **最小化全局插件影响**：不加改租户插件 append；用 `ignoreTable` + 手写租户断言。
- **数据源前置依赖（三轮）**：`rag.eval.online-enabled` 默认 `false`（ChatController:56），且仅 `result.isRagUsed()` 的行才落评估 —— 生产需开启才有在线样本沉淀，否则闭环首跑为空报告。**此为本方案可行性的前置条件，声明见 §3.6。**

## 2. 现状回顾（真实机制）

多租户隔离的真实主次顺序：
- **主防线 = Schema 物理隔离**：`TenantSchemaInterceptor` 每次查询前 `SET search_path TO <schema>, public`（:96）。
- **辅助防线 = RLS（best-effort）**：`SET app.tenant_id`（:98）；两表 RLS 用 `tenant_id = current_tenant_id()`，`COALESCE(...,0)` 兜底；`TenantSchemaInterceptor:45`、`TenantMyBatisPlusConfig:24-25` 均承认 best-effort / 连接池跨连接风险。
- **结论**：SQL 必须**显式携带租户断言**，不依赖 RLS。

评估链路现状：
- `AnswerEvaluationService.evaluate()`：三维评估 + 无条件写 Redis（:68 → writeToRedis, key=md5(query), TTL 24h）。
- `evaluateAndPersist()`：写 Redis + 强制落库（tenantId=null 拒绝，:152-155 铁律）。
- 三个 `AnswerEvaluator`（Relevancy/Correctness/Faithfulness）各返回**单个布尔** `boolean evaluate(query, context, answer)`；`dimensionScores` map 存于 `EvaluationResult`。
- 用户反馈只写 `rag_session.feedback`。
- 租户插件 `ignoreTable` 豁免 sys_tenant/sys_user/sys_user_tenant_rel/audit_log；`getTenantIdColumn()` 返回裸 `tenant_id`（join 会 ambiguous）。
- **MyBatis XML**：项目用 `mybatis-plus-spring-boot3-starter`，`mapperLocations` 默认 `classpath*:/mapper/**/*.xml`（现有 `RagSessionMetaMapper.xml` 被 `RagSessionServiceImpl` 调用证明生效）；本方案数据集 join 用 **@Select** 避免 namespace/路径歧义。
- `map-underscore-to-camel-case: true`（application.yml:145）→ DTO 列→属性依赖隐式映射，无需 `@Results`。

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

不写回 `answer_eval_result`；通过读取侧 join 按 `answer_eval_result.session_row_id = rag_session.id` 关联 `rag_session.feedback`：
- `feedback = 1` → humanLabel = 1；`feedback = -1` → humanLabel = -1；`feedback = 0` 或无关联 → **不纳入**。

### 3.2 数据集抽取（@Select + 服务端推导 schema + 租户断言）

#### 3.2.1 租户隔离方案

- **schema 由服务端推导（必钉，防 IDOR）**：dataset/regression 的 schema **不接受客户端传入**，由服务端用 `tenant_` + tenantId 推导（复用现有租户 schema 命名规则）。**同源校验 = 对这条推导的断言**（推导出的 schema 必须匹配既有租户 schema），而非两个入参的字符串比对。攻击者无法通过客户端构造 schema 名指向他租户。
- **ignoreTable 豁免**：`TenantMyBatisPlusConfig.ignoreTable` 追加 `answer_eval_result`、`rag_session`（同租户 schema，零全局影响；对齐既有豁免先例）。
- **@Select 手写租户断言**：`e.tenant_id = #{tenantId}` 显式断言；schema 前缀 `${schema}`（服务端推导 + 白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`）。

> 处置 b（改租户插件 append）**降为观察项**，本方案不做。

#### 3.2.2 数据集抽取方法

`dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
- `schema = "tenant_" + tenantId`（服务端推导）；白名单校验。
- **from/to 均非 null 校验（三轮）**：缺任一 → **400**，不允许静默空集。
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
  "  AND e.session_row_id IS NOT NULL " +
  "  AND s.feedback <> 0 " +
  "  AND e.create_time BETWEEN #{from} AND #{to} " +
  "ORDER BY e.session_row_id, e.id DESC " +
  "LIMIT #{limit}")
List<LabelledEvalSample> selectDataset(@Param("schema") String schema,
    @Param("tenantId") Long tenantId, @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to, @Param("limit") int limit);
```
- `${schema}` 服务端推导 + 白名单；其余 `#{}` 绑定。
- `ORDER BY e.session_row_id, e.id DESC`：`e.id` 二级排序保证 DISTINCT ON 取最新稳定（create_time 同秒同值问题）。
- DTO 列→属性依赖 `map-underscore-to-camel-case: true`（隐式，不写 @Results）。

**`LabelledEvalSample`（含 tenantId，R2）：**
```
query, context, answer, tenantId(Long),
autoPass(Boolean), autoScore(double), humanLabel(Short: 1/-1),
sessionRowId(Long), createTime(LocalDateTime)
```

#### 3.2.3 回归报告快照表（含指纹 + 规则版本）

新增每租户表 `eval_regression_report`（幂等 DDL，含 `DROP POLICY IF EXISTS`）：

```sql
CREATE TABLE IF NOT EXISTS <schema>.eval_regression_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    rule_version VARCHAR(64) NOT NULL,          -- 三维评估器类名+版本 hash（如 relevancy:1|correctness:1|faithfulness:1）（三轮）
    dataset_fingerprint VARCHAR(64) NOT NULL,   -- md5(string_agg(session_row_id::text,','))（二轮）
    dataset_from TIMESTAMP,
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
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_fp
    ON <schema>.eval_regression_report (tenant_id, dataset_fingerprint);   -- 三轮：按指纹分组免全表扫
ALTER TABLE <schema>.eval_regression_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.eval_regression_report FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_eval_rep ON <schema>.eval_regression_report;   -- 三轮：幂等
CREATE POLICY tenant_isolation_eval_rep ON <schema>.eval_regression_report
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT SELECT, INSERT ON <schema>.eval_regression_report TO company_rag_app;   -- 对象级授权不需列清单（三轮）
GRANT USAGE, SELECT ON SEQUENCE <schema>.eval_regression_report_id_seq TO company_rag_app;
```
> 补充说明：后续如需保留/清理策略，需补 `DELETE` 授权（三轮）。

**快照 INSERT 显式租户**：insert 前显式 `setTenantId(tenantId)` + null 拒绝，对齐铁律（避免隐式落 0 被 RLS 拒）。
> 三处同步：`SchemaMigrationConfig` / `TenantServiceImpl.createTenantSchema` / `sql/init.sql`。

### 3.3 回归重跑报告

`regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：
1. `dataset(...)` 取当批样本；**0 样本 → 不落快照，返回明确提示**（funding：`string_agg` 0 行 → NULL → `md5(NULL)` NULL 撞 NOT NULL）；
2. 计算指纹 `dataset_fingerprint`（完整输入：from/to/limit/排序/批量决定批次）+ 取 `rule_version`；
3. 逐个 `evaluateNoCache()` 重跑（R3，无 Redis 写）；
4. 与 humanLabel 比对生成报告；
5. **落快照**（显式租户 + 指纹 + rule_version + from/to），返回含 `reportId`。

**四格**（autoPass vs humanLabel，human=1 正类）：TP/TN/FP/FN。

**指标公式（三维一致率明确，三轮）：**
```
n=sampleCount; accuracy=(tp+tn)/n; passRate=autoPass占比; avgScore=均分
precision=tp/(tp+fp)  分母0→1.0
recall=tp/(tp+fn)     分母0→1.0
f1=2pr/(p+r)          p=r==0→0.0   -- 修正：灾难场景（全正判负）不可显示为满分
negative_recall=tn/(tn+fp)  分母0→1.0
relevancy_agree   = |{i: rel_i == (humanLabel_i>0)}| / n
correctness_agree = |{i: corr_i == (humanLabel_i>0)}| / n
faithfulness_agree= |{i: faith_i == (humanLabel_i>0)}| / n
   映射：humanLabel +1→正 / -1→负；维度布尔 vs 正负 是否同向；分母固定为 n（含无正样本情形）。
```

**`evaluateNoCache` 与 `doEvaluate`（三轮）**：
- 抽私有 `doEvaluate(query, context, answer)` 返回**三维完整结果** `EvaluationResult(pass, score, dimensionScores{rel/corr/faith})` —— 仅返回聚合 pass 无法算三维一致率。
- `evaluate()`/`evaluateAndPersist()`/`evaluateNoCache()` 三处统一走 `doEvaluate`，仅缓存写与落库行为不同。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 | 并发/校验 |
|---|---|---|---|
| POST | `/api/eval/dataset` | 抽样数据集 `List<LabelledEvalSample>`（from/to/limit） | from/to 非空，缺→400 |
| POST | `/api/eval/regression` | 跑回归 + 落快照（reportId/指纹/rule_version） | Service 按 tenantId 键锁 + 超时 30s；0 样本返回明确提示 |
| GET | `/api/eval/history` | 历史快照**分页**(page/pageSize)，按指纹归因 | 只读 |

口径与边界（M4/M5 + 三轮）：
- 统一 `@PreAuthorize("hasAnyRole('ADMIN','USER')")`（M5：防 viewer 读 context/answer 原文）。
- `/regression` **POST**（M4）；**Service 层按 tenantId 键锁**（`ConcurrentHashMap<tenantId, ReentrantLock>`，不跨租户互阻），**超时 30s**（见 §7 基线）。
- schema 由服务端推导，**不接受客户端传入**（必钉）。

### 3.5 与既有机制边界

- 不触碰：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- 新增 `evaluateNoCache`/`doEvaluate`/`dataset`/`regression`；`evaluate()`/`evaluateAll()` 契约不变。
- 租户插件仅追加 ignoreTable 豁免两表；不改 append。
- 新增表三处同步（D2）。

### 3.6 数据源前置依赖（三轮）

| 项 | 现状 | 影响 |
|---|---|---|
| `rag.eval.online-enabled` | 默认 `false`（ChatController:56）；dev 显式 `true`（application-dev.yml:81） | 生产需开启才有在线样本沉淀 |
| 落库条件 | 仅 `result.isRagUsed()` 行评估落库（ChatController:173） | 非 RAG 回答不产生样本 |

**结论**：接入闭环须将生产 `rag.eval.online-enabled` 置 `true`（否则反馈攒再多数据集恒空、首跑空报告）。此为本方案**可行性前置条件**，部署/启用时核对；不依赖冷启动种子样本（后续演进）。

## 4. 数据流

```
用户反馈 → rag_session.feedback（已有，不变）
   ↓
POST /api/eval/dataset（ADMIN/USER, from/to 非空）
   → schema = tenant_+tenantId（服务端推导）
   → @Select join（ignoreTable 豁免 + 显式租户断言 + DISTINCT ON 取最新）
   ↓
POST /api/eval/regression（POST, tenant 键锁 + 30s 超时）
   → dataset → 指纹 + rule_version → evaluateNoCache 逐个重跑(doEvaluate)
   → 四格 + accuracy/precision/recall/负类召回/F1/三维一致率
   → 0 样本不落快照; 否则显式租户落快照(含 fingerprint+rule_version) → 返回 reportId
   ↓
GET /api/eval/history（分页, 按指纹归因）
```

## 5. 测试策略（最窄范围）

- **rag `AnswerEvaluationServiceTest`**：
  - `dataset`：feedback≠0 纳入；DISTINCT ON + `e.id DESC` 取最新（同轮多行同秒用例）；显式租户过滤；**schema 服务端推导**（客户端不可传，越权 schema 名被拒）；schema 白名单；from/to 非空校验；limit 上限 200；
  - `regression`：TP/TN/FP/FN + 各指标数值；F1 p=r==0→0（构造全 FN）；分母 0 退化；**0 样本不落快照**（mock 验证 return，不落库）；`evaluateNoCache` 不触发 Redis 写（R3）；快照显式 tenantId(null 拒绝) + fingerprint/rule_version 正确写入；
  - `doEvaluate` 返回三维，三路径结果一致。
- **web `EvalControllerTest`**：ADMIN/USER、viewer 拒、租户头缺拒、越权过滤、/history 分页、/regression 并发（同租户串行/异租户并行）、from/to 缺→400。
- **跨租户 IT（M1）**：两租户数据互不可见，锁死"主防线=显式租户断言 + RLS best-effort"。
- **schema 建表测试**：`TenantServiceImplSchemaTest` 补 `eval_regression_report` 建表 + 索引 + RLS + 幂等断言（含 DROP POLICY 重跑）。
- 验证命令：`mvn test -Dtest=AnswerEvaluationServiceTest -pl company-rag-rag`、`mvn test -Dtest=EvalControllerTest -pl company-rag-web`（根 reactor 联合编译）。

## 6. 改动清单

- **租户插件**：Modify `TenantMyBatisPlusConfig.java`：`ignoreTable` 追加 `answer_eval_result`、`rag_session`。
- **数据库（三处同步）**：Modify `TenantServiceImpl.createTenantSchema` / `SchemaMigrationConfig` / `sql/init.sql`：新增 `eval_regression_report`（含 rule_version + fingerprint 两索引 + DROP POLICY 幂等 + GRANT 及 sequence）。
- **rag 模块**：
  - Create `LabelledEvalSample.java`（含 tenantId）
  - Create `EvalRegressionReport.java`（**报告 DTO**，含指标表单 + fingerprint/ruleVersion）+ `EvalRegressionReportEntity.java`（**快照实体**，列对齐）
  - Create `EvalRegressionReportMapper.java`（`@Select` 插入 + 历史分页查询）
  - Modify `AnswerEvalResultMapper.java`：新增 `@Select selectDataset`
  - Modify `AnswerEvaluationService.java`：抽 `doEvaluate`（返回三维 EvaluationResult）；新增 `evaluateNoCache`、`dataset`、`regression`（schema 推导 + 指纹 + rule_version + 空样本不落 + 显式租户落库）
- **web 模块**：Modify `EvalController.java`：新增 `POST /dataset`、`POST /regression`、`GET /history`（ADMIN/USER；from/to 校验；tenant 键锁 + 30s；分页）。
- **配置**：Modify `application-dev.yml`（`application.yml` 若含 `rag` 段则同步）：
  - `rag.eval.regression-gate-enabled: false`（占位）
  - `rag.eval.regression-timeout-ms: 30000`
  - `rag.eval.dataset-limit-max: 200`
  - `rag.eval.regression-concurrency-keys: tenant`
  - 生产核对 `rag.eval.online-enabled: true`（数据源前置，见 §3.6）
- **测试**：Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`、`TenantServiceImplSchemaTest`；新增跨租户 IT。

## 7. 风险与观察项（正确口径 + 三轮）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **IDOR（必钉1）** | 客户端可传 schema+tenantId 构造他租户 schema | schema 由服务端从 tenantId 推导、不接受客户端传入；同源校验 = 推导断言 |
| **回归上下文局限（必钉2）** | 本期回归测的是**固定历史 toolContext**（ChatController:178 落地快照）下的判定规则，不覆盖检索链路/知识库更新变化 | 快照带 `rule_version`；文档明示口径边界 |
| join 租户 ambiguous | 插件为两表各追加裸 tenant_id | ignoreTable 豁免 + @Select 手写带别名 `e.tenant_id` 断言 |
| RLS 非兜底（M1） | 主防线 schema 隔离；RLS best-effort（连接级 app.tenant_id，跨连接风险） | SQL 显式租户断言为主；跨租户 IT 锁死 |
| 样本批不可复现归因（二轮） | 动态视图下次样本集变化 | 快照带 fingerprint + from/to + 索引；/history 按指纹归因；口径注明 fingerprint=f(完整输入) |
| 同轮多行重复（M2） | 在线多入口成多行 | DISTINCT ON + `e.id DESC` 取最新 |
| 回归污染缓存（R3） | evaluate 无条件写 Redis | 回归走 evaluateNoCache |
| 租户丢失（R2） | 样式集 DTO 无 tenantId 落 0 | DTO 带 tenantId；快照 insert 显式 setTenantId + null 拒绝 |
| from/to 缺失静默空（三轮） | BETWEEN null 恒空集 + 200 OK | 非空校验 → 400 |
| 空样本撞 NOT NULL（三轮） | string_agg 0 行→NULL→md5 NULL | 0 样本不落快照 + 明确提示 |
| 数据源空（三轮） | online-enabled 默认 false + 仅 RAG 行落库 | §3.6 前置声明；生产开启核对 |
| 数据外泄（M5） | dataset 返回 context/answer 原文 | /dataset /regression /history 统一 ADMIN/USER |
| GET 重计算副作用（M4） | 重计算+落库+缓存风险 | /regression 改 POST；tenant 键锁 + 30s |
| 指标语义错位（M3） | pass 硬与门 vs humanLabel 主观 | 报告补 F1/负类召回/三维一致率；语义说明入文档 |
| F1 退化误报（二轮） | p=r==0 是灾难场景 | f1→0，与 precision/recall 分开处理 |
| 快照 INSERT 隐式租户 | 插件追加 tenant 列落 0 被 RLS 拒 | 显式 setTenantId + null 拒绝 |
| SQL 注入 | schema 名 / 输入 | schema 服务端推导 + 白名单；其余绑定 |
| 快照表膨胀 | 只增不删 | 观察项；后续保留/清理策略**需补 DELETE 授权**（三轮） |
| 统计噪声误判（三轮） | 200 样本 accuracy 95% 区间约 ±7% | 文档写明最小可检测差异，避免对噪声调参 |

## 8. 后续演进（本期不做）

- 固化逐样本数据集快照（`evalset` 表 + 版本）实现完整可复现评测。
- 硬门禁接入关键路径（`regression-gate-enabled` 占位）。
- 冷启动种子样本、反馈量不足时采样降级。
- 回归报告/历史趋势接入可观测看板。
- 观察项：未来如需跨表别名插件级 join 租户自动追加，再评估处置 b（append）。

## 附录：30s 超时与统计基线（三轮承接）

- **30s 子预算**：200 样本 × 3 本地规则 = 600 次判定；三个 `AnswerEvaluator` 均为**纯本地规则**（无 HTTP/Embedding），单次判定为内存字符串处理，量级毫秒级。实现后补**实测耗时基线**（`regression` 实际 wall-time）写回文档，若超预算优先优化（并行化/抽样）。
- **最小可检测差异**：200 样本的 accuracy，95% 置信区间约 ±7%。文档要求团队在对比趋势时**以统计显著差异为门槛**，不对 ±7% 内的噪声调参。