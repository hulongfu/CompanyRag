# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计 v2

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅（v2 整合审阅修正）
> 版本变更：v2 按审阅意见 R1-R3 / M1-M7 修正，并采纳决策——R1 处置 b+c，M6 报告快照落库纳入本版 MVP。
> 前置能力（均已落地）：
> - `answer_eval_result` 每租户表（query/context/answer/pass/score/三维分/source/session_row_id/create_time）
> - `AnswerEvaluationService`（evaluate 纯评估+Redis / evaluateAllPersisted 落库 / findByQuery / listResults / stats）
> - `EvalController`（/api/eval/run|result|results|stats，@PreAuthorize + X-Tenant-Id 隔离）
> - 用户反馈：`/api/chat/feedback` → `rag_session.feedback`(-1/0/1)，按 tenantId+userId+sessionId+sessionRowId 定位
> - 多租户插件：`TenantMyBatisPlusConfig`（TenantLineInnerInterceptor + TenantSchemaInterceptor）

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答中动态筛出评测样本，并支持**固定样本快照**以保可复现。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall/F1 + 分维度一致率），并**落一份报告快照**以便跨版本对比。

**约束：**
- **最小可行（MVP）**：数据集以动态视图为基础 + **新增一份回归报告快照表**（M6 决策）；硬门禁仅留**可选开关占位（默认关）**，本期不实现阻断逻辑。
- **反馈路径零改动**：`updateFeedback`、`rag_session.feedback` 列不动（避免给主链反馈加耦合）；反馈联动通过读取侧 join 实现。
- **回归重跑不污染线上缓存**（R3）：为 `evaluate` 增加 `evaluateNoCache` 重载，回归走无缓存路径。
- **多租户隔离**：新增的 join 数据集查询必须解决租户插件 ambiguous 问题（R1，处置 b+c），并显式断言租户。

## 2. 现状回顾（真实机制，M1 修正）

多租户隔离的**真实主次顺序**（依据代码）：
- **主防线 = Schema 物理隔离**：`TenantSchemaInterceptor.java:96` `SET search_path TO <schema>, public`。每个租户独立 schema，物理隔离，100% 可靠。
- **辅助防线 = RLS（best-effort）**：`:98` `SET app.tenant_id`；`answer_eval_result`（SchemaMigrationConfig:209-215）与 `rag_session` 的 RLS 策略用 `tenant_id = current_tenant_id()`，其值经 `COALESCE(...,0)` 兜底（init.sql）。`TenantSchemaInterceptor:45` 明示"RLS 隔离：best-effort"；`TenantMyBatisPlusConfig:24-25` 承认连接池跨连接时 search_path 可能不落当前连接。
- **结论**：应用层 SQL 必须**显式携带租户断言**，不能依赖 RLS 兜底。

已存在的评估链路：
- `AnswerEvaluationService.evaluate()`：三维评估 + **无条件写 Redis**（:68 → writeToRedis，key=md5(query)，TTL 24h）。
- `evaluateAllPersisted()`：写 Redis + 强制落库（tenantId=null 拒绝抛出）。
- `findByQuery / listResults / stats` 读取能力已具备。
- 用户反馈只写 `rag_session.feedback`，`answer_eval_result` 不知道用户评价。

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

**不**在 `updateFeedback` 里同步写 `answer_eval_result`。反馈联动通过**读取侧 join**实现：按 `answer_eval_result.session_row_id = rag_session.id` 关联回 `rag_session.feedback` 得到人工标签（M2 注意：须按 sessionRowId 取最新一条，避免同轮多行重复计入）。

- `feedback = 1` → 人工正样本（humanLabel = 1）
- `feedback = -1` → 人工负样本（humanLabel = -1）
- `feedback = 0` 或无关联行 → **无标签，不纳入**

### 3.2 数据集抽取（动态视图 + 快照）

#### 3.2.1 动态数据集抽取（实时查询）

在 `AnswerEvaluationService` 新增 `dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`，输出 `List<LabelledEvalSample>`（按 sessionRowId 去重取最新，M2）。

抽取 SQL **必须规避租户插件 ambiguous（R1）**——采用处置 b+c：
- **处置 c（落地）**：数据集 join 查询写在**自定义 XML**（新增 `AnswerEvalResultMapper.xml` 或独立 Mapper），SQL 中**写死 schema 前缀**（schema 由 service 层传入，经白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$` 校验），并对两张表使用**显式别名 + 带别名的 `tenant_id` 断言**。
- **处置 b（插件层）**：`TenantMyBatisPlusConfig` 的 `TenantLineHandler` 增加**自定义 append 逻辑**：当 SQL 含 join 到本插件需隔离的表时，按表别名拼接带前缀的 `tenant_id`（而非裸列名），解决 `column reference "tenant_id" is ambiguous`。落地时以 b 为通用方案、c 为数据集查询的双保险。

**动态抽取 SQL（示意）：**
```sql
SELECT DISTINCT ON (e.session_row_id)
       e.query, e.context, e.answer,
       e.pass              AS auto_pass,
       e.score             AS auto_score,
       s.feedback          AS human_label,
       e.tenant_id         AS tenant_id,
       e.session_row_id, e.create_time
FROM <schema>.answer_eval_result e
JOIN <schema>.rag_session s ON s.id = e.session_row_id
WHERE e.tenant_id = ?           -- 显式租户断言（M1：主防线，不依赖 RLS）
  AND e.session_row_id IS NOT NULL
  AND s.feedback <> 0           -- 只取人工标签
  AND e.create_time BETWEEN ? AND ?
ORDER BY e.session_row_id, e.create_time DESC   -- DISTINCT ON 取最新一条（M2）
LIMIT n
```
> `<schema>` 使用参数绑定前的静态校验 + 字符串拼接（非 SQL 参数位，因 PG 不可参数化标识符），schema 名经白名单防注入。

**DTO `LabelledEvalSample`（补 tenantId，R2）：**
```
query, context, answer,
tenantId(Long),      -- 来自 e.tenant_id 显式带出（R2：回归建 AnswerCase 必需）
autoPass(Boolean),  autoScore(double),
humanLabel(Short: 1/-1),
sessionRowId(Long), createTime(LocalDateTime)
```

#### 3.2.2 回归报告快照表（MVP，M6）

新增每租户表 `eval_regression_report`，供跨版本趋势对比与可复现（M6/M7）：

```sql
CREATE TABLE IF NOT EXISTS <schema>.eval_regression_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,   -- 本次回归时刻（样本快照）
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
    f1 DOUBLE PRECISION NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_<schema>_eval_rep_tenant_time
    ON <schema>.eval_regression_report (tenant_id, run_time DESC);
ALTER TABLE <schema>.eval_regression_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.eval_regression_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_eval_rep ON <schema>.eval_regression_report
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT SELECT, INSERT ... ON <schema>.eval_regression_report TO company_rag_app;
GRANT USAGE, SELECT ON SEQUENCE <schema>.eval_regression_report_id_seq TO company_rag_app;
```
> 快照存**聚合指标**（非逐样本原文），兼顾可复现趋势与体积。逐样本样本集快照（M7 完整复现）留作后续 `evalset` 演进。新增此表需同步三处：`SchemaMigrationConfig`(存量迁移)、`TenantServiceImpl.createTenantSchema`(新租户)、`sql/init.sql`(存档) —— 遵循项目 D2 同步规范。

### 3.3 回归重跑报告

新增 `regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`，语义：
1. 取数据集样本 `dataset(...)`；
2. 逐个用**当前三维评估器**经 `evaluateNoCache()` 重跑（R3：不写 Redis、不落库），得新 `autoPass/autoScore`；
3. 与人工标签比对，生成 `EvalRegressionReport`；
4. **落一份快照**到 `eval_regression_report`（M6），返回含 `reportId` 的结果。

**四格判定**（autoPass vs humanLabel）：TP 一致正 / TN 一致负 / FP 误报 / FN 漏报（同 v1 定义，以 human=1 为正类）。

**`EvalRegressionReport` 字段（补强 M3）：**
```
sampleCount, runTime,
passRate, avgScore,
avgRelevancyScore, avgCorrectnessScore, avgFaithfulnessScore,
tp, tn, fp, fn,
accuracy  (tp+tn)/n
precision tp/(tp+fp)
recall    tp/(tp+fn)
f1        2*precision*recall/(precision+recall)      -- 新增（M3）
negativeRecall  tn/(tn+fp)                           -- 新增：负类召回（M3）
relevancyAgree  relevancy 侧与 humanLabel 的一致率   -- 新增（M3）
correctnessAgree 同理
faithfulnessAgree 同理
note      报告语义说明（M3：pass=三维硬与门 vs humanLabel=主观评价，二者非同一语义）
```
> 分母为 0 时 precision/recall/f1 取 1.0（无该类样本则视为无性能问题）。
> **快照落库是本版 MVP 一部分**（M6 决策）：`regression` 每次调用将聚合指标写入 `eval_regression_report`，供趋势查询；后续可加历史对比接口。

**`evaluateNoCache` 重载（R3 落地）：**
- 在 `AnswerEvaluationService` 增加 `evaluateNoCache(AnswerCase)`：与 `evaluate()` 相同的三维判定，但**跳过 writeToRedis**。
- `regression` 用 `evaluateNoCache`；既有 `evaluate()`/`evaluateAll()` 契约不变。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/eval/dataset` | 抽样有标签数据集（`List<LabelledEvalSample>`），参数 `from/to/limit`；**限 ADMIN/USER**（M5） |
| POST | `/api/eval/regression` | 跑回归报告 + 落快照（返回含 reportId），参数 `from/to/limit`；**限 ADMIN/USER**（M5）；**改用 POST**（M4） |
| GET | `/api/eval/history` | 查询历史回归报告快照列表/单条（用于趋势对比，M6）；`ADMIN/USER` |

口径（M5 修正）：
- `/dataset`、`/regression`、`/history` 统一 `@PreAuthorize("hasAnyRole('ADMIN','USER')")`，与既有 `/run` 对齐；
- `/dataset` 返回 `context/answer`（企业知识库原文），仅对 `ADMIN/USER` 可见；如需进一步收敛可增加脱敏或按角色字段过滤（观察项）。

超时/并发语义（M4）：
- `regression` 为同步重计算 + 落快照，声明**超时上限**（如 30s）与**并发限制**（复用受限 thread pool 或串行化，拒绝则返回 429/繁忙提示，不静默丢弃）。

### 3.5 与既有机制边界

- **不触碰**：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- `evaluateNoCache` 仅新增；`evaluate()`/`evaluateAll()` 契约与既有测试保持兼容。
- 新增 `eval_regression_report` 表遵循三处同步规范（D2）。

## 4. 数据流

```
用户交互 → /api/chat/feedback → rag_session.feedback（已有，不变）
   ↓
POST /api/eval/dataset → 租户内 join（别名+显式 tenant 断言，去重取最新）抽有标签样本
   ↓
POST /api/eval/regression → dataset(...) → 逐样本 evaluateNoCache() 重跑
   → 四格 + accuracy/precision/recall/F1 + 负类召回 + 分维度一致率
   → 落快照 eval_regression_report → 返回含 reportId 的报告
   ↓
GET /api/eval/history → 查询历史快照（趋势对比）
```

## 5. 测试策略（最窄范围）

- **rag 模块 `AnswerEvaluationServiceTest`**：
  - `dataset`：仅纳入 feedback=1/-1；按 sessionRowId 去重取最新（M2 构造同轮多行用例）；显式租户过滤；时间范围；limit 上限 200；**打印最终 SQL 断言无 ambiguous（R1）**；
  - `regression`：已知样本构造 TP/TN/FP/FN 与 accuracy/precision/recall/F1/负类召回/三维一致率数值；分母为 0 退化分支；空数据（sampleCount=0）；`evaluateNoCache` **不触发 Redis 写**（mock 验证 writeToRedis 未调用，R3）；快照落库成功（M6）。
- **web 模块 `EvalControllerTest`**：新端点 `ADMIN/USER` 鉴权、`viewer` 拒绝（M5）、租户头缺失拒绝、越权（跨租户）过滤、`regression` 并发限制。
- **跨租户集成测试（M1 决策）**：同库两租户 schema，验证回归/数据集结果互不可见——锁死"主防线=显式租户断言 + RLS best-effort"行为。
- 验证命令：`mvn test -Dtest=AnswerEvaluationServiceTest -pl company-rag-rag`、`mvn test -Dtest=EvalControllerTest -pl company-rag-web`（根 reactor 联合编译避免读到本地仓库旧 common 快照）。

## 6. 改动清单

- **租户插件（R1 处置 b）**
  - Modify `TenantMyBatisPlusConfig.java`：TenantLineHandler 自定义 append 逻辑——join 场景按表别名拼接带前缀的 `tenant_id`（解决 ambiguous）。
- **数据库（R1 处置 c + M6，三处同步）**
  - Modify `TenantServiceImpl.createTenantSchema`：新增 `eval_regression_report` 建表 + 索引 + RLS（新租户）。
  - Modify `SchemaMigrationConfig`：为存量 `tenant_%` schema 幂等建 `eval_regression_report` + 索引 + RLS。
  - Modify `sql/init.sql`：同步存档新表。
- **rag 模块**
  - Create `.../rag/eval/answer/LabelledEvalSample.java`（含 tenantId，R2）
  - Create `.../rag/eval/answer/EvalRegressionReport.java`（补 F1/负类召回/三维一致率/note，M3）
  - Create `.../rag/eval/answer/EvalRegressionReportEntity.java` + `EvalRegressionReportMapper.java`（快照落库，M6）
  - Modify `AnswerEvalResultMapper.java` + 新增 XML：带 schema 前缀 + 别名的 join 数据集查询（R1 处置 c，R2 带出 tenant_id，M2 去重）
  - Modify `AnswerEvaluationService.java`：新增 `evaluateNoCache`（R3）、`dataset`、`regression`（含快照落库）
- **web 模块**
  - Modify `EvalController.java`：新增 `POST /dataset`、`POST /regression`、`GET /history`（统一 ADMIN/USER，M5；regression 改 POST + 并发/超时语义，M4）
- **配置**
  - Modify `application-dev.yml`（`application.yml` 若含 `rag` 段则同步）：`rag.eval.regression-gate-enabled: false`、回归并发/超时参数
- **测试**
  - Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`；新增跨租户隔离 IT（M1）

## 7. 风险与观察项（M1 修正后的正确口径）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **join 跨表隔离（R1）** | TenantLineInnerInterceptor 在 join 两表各追加裸 `tenant_id` 导致 PG ambiguous | 处置 b（插件 append 按别名）+ 处置 c（XML 写死 schema + 显式带别名 tenant 断言）；单测断言最终 SQL |
| **RLS 非兜底（M1）** | 主防线是 schema 隔离；RLS 依赖连接级 `app.tenant_id`，有连接池跨连接风险，仅 best-effort | SQL 显式 `tenant_id` 断言为主；补跨租户 IT 锁死行为 |
| **同轮多行重复计入（M2）** | 在线同轮可被多入口评估成多行，INNER JOIN 使一行 feedback 复制成 N 行 | `DISTINCT ON (session_row_id)` 取最新一条 |
| **回归污染在线缓存（R3）** | `evaluate()` 无条件写 Redis，key=md5(query) | 新增 `evaluateNoCache`，回归走无缓存路径 |
| **租户丢失（R2）** | `LabelledEvalSample` 无 tenantId 时回归建 AnswerCase 会落入 `tenant_id=0` 永久不可见 | DTO 补 `tenantId` 显式带出；null 拒绝 |
| **数据外泄（M5）** | `/dataset` 返回 `context/answer` 知识库原文，viewer 可读 | `/dataset`、`/regression`、`/history` 统一 ADMIN/USER；观察项：脱敏 |
| **GET 重计算副作用（M4）** | 同步重计算 + 写 Redis + 落库，GET 有缓存/预取风险 | 改 POST；声明超时(30s)与并发限制 |
| **指标语义错位（M3）** | pass=三维硬与门 vs humanLabel=主观评价，二者语义不同 | 报告补 F1/负类召回/分维度一致率 + note 说明 |
| **可复现性（M7）** | 纯动态视图下反馈变动改变历史回归结果 | 本期落聚合快照支持趋势对比；逐样本 evalset 快照留后续演进 |
| **SQL 注入** | schema 名 / from / to / limit | schema 经白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`；其余参数绑定 |
| **快照表膨胀** | 只增不删 | 观察项；后续可加清理/保留策略 |

## 8. 后续演进（本期不做）

- 固化**逐样本**数据集快照（`evalset` 表 + 版本）实现完整可复现评测。
- 硬门禁真正接入关键路径（发布/上线），按指标阈值阻断或告警。
- 冷启动种子样本、反馈量不足时的采样降级。
- 将回归报告/历史趋势接入可观测看板。
