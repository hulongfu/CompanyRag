# 方案A：答案质量闭环（反馈 → 数据集 → 评测回归）设计

> 日期：2026-09-27
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置能力（均已落地）：
> - `answer_eval_result` 每租户表（query/context/answer/pass/score/三维分/source/session_row_id/create_time）
> - `AnswerEvaluationService`（evaluate 纯评估+Redis / evaluateAllPersisted 落库 / findByQuery / listResults / stats）
> - `EvalController`（/api/eval/run|result|results|stats，@PreAuthorize + X-Tenant-Id 隔离）
> - 用户反馈：`/api/chat/feedback` → `rag_session.feedback`(-1/0/1)，按 tenantId+userId+sessionId+sessionRowId 定位

## 1. 目标

把已存在的三块能力（自动评估、落库查看、用户反馈）串成完整闭环，让"答得准"从一次性调参变成**可量化、可持续迭代**的能力：

1. **反馈联动**：把用户 👍/👎 变成评测样本的人工标签，与自动评估结果对齐。
2. **数据集抽取**：从有标签的问答中动态筛出高质量评测数据集。
3. **评测回归**：用当前判定规则对数据集重新评估，产出准确度报告（TP/TN/FP/FN + accuracy/precision/recall）。

**约束：**
- **最小可行（MVP）**：数据集用**动态视图**（不新增表、不新增 Mapper 以外的持久化结构），硬门禁仅留**可选开关占位（默认关）**，本期不实现阻断逻辑。
- 反馈路径（`updateFeedback`、`rag_session.feedback` 列）**零改动、零风险**——反馈联动通过读取侧动态 join 实现。
- 回归报告**仅内存返回、不落库**（反映"当前规则"准确度的按需质检视图）。
- 多租户隔离沿用既有 `tenant_%` schema + `X-Tenant-Id`；所有抽取断言 `tenant_id = headerTenantId`。
- 回归用于**有标签小样本（≤200）**离线计算，三维均为本地规则判定、无额外 LLM 调用，同步执行可接受。

## 2. 现状回顾（与本方案的关系）

- 自动评估已落库并支持查看/统计：`answer_eval_result` 每租户表 + `AnswerEvaluationService` + `EvalController` 均已存在（来源：`2026-09-16-answer-evaluator-production-design.md` 已实现）。
- **缺失点**：
  1. `answer_eval_result` 记录不知道用户评价（`session_row_id` 关联 `rag_session.id` 但未用于反馈联动）。
  2. 没有"有标签数据集"的抽取视图。
  3. 没有基于数据集重跑当前规则的回归报告。

## 3. 架构设计

### 3.1 反馈联动（原则：反馈路径零改动）

**不**在 `updateFeedback` 里同步写 `answer_eval_result`——避免给主链路反馈路径加耦合与失败面。反馈联动通过**动态 join 读取**实现：查询/回归时按 `answer_eval_result.session_row_id = rag_session.id` 关联回 `rag_session.feedback`，得到人工标签。

- `rag_session.feedback = 1` → 人工正样本（humanLabel = 1）
- `rag_session.feedback = -1` → 人工负样本（humanLabel = -1）
- `feedback = 0` 或无关联行 → **无标签，不纳入数据集**

### 3.2 数据集抽取（动态视图）

在 `AnswerEvaluationService` 新增方法 `dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`，输出"有标签样本集合"（`List<LabelledEvalSample>`）。

**抽取 SQL（租户 schema 内 join）：**
```sql
SELECT e.*, s.feedback AS human_feedback
FROM answer_eval_result e
JOIN rag_session s ON s.id = e.session_row_id
WHERE e.tenant_id = ?          -- 显式租户过滤，防越权
  AND e.session_row_id IS NOT NULL
  AND s.feedback <> 0          -- 只取有人工评价的样本
  AND e.create_time BETWEEN ? AND ?
LIMIT n
```

**返回复合 DTO `LabelledEvalSample`：**
```
query, context, answer,
autoPass(Boolean),  autoScore(double),      -- 来自 answer_eval_result
humanLabel(Short: 1/-1),                   -- 来自 rag_session.feedback
sessionRowId(Long), createTime(LocalDateTime)
```

**实现落地**：在 `AnswerEvalResultMapper` 侧新增一个带 join 的查询方法（MyBatis-Plus，注解 SQL：`@Select`+两表 join+`@Results` 映射，或自定义 XML；沿用项目既有 MyBatis-Plus 风格）。`schemaName` 经 `^[a-zA-Z_][a-zA-Z0-9_]*$` 白名单校验（复用 `TenantServiceImpl` 既有校验），防 SQL 注入。

### 3.3 回归重跑报告

新增 `regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`，语义：
1. 取数据集样本 `dataset(...)`；
2. 逐个用**当前三维评估器**重跑 `evaluate()`（纯评估、不落库），得到新 `autoPass/autoScore`；
3. 与人工标签比对，生成 `EvalRegressionReport`。

**四格判定**（autoPass vs humanLabel）：
- TP 一致正：autoPass=TRUE 且 human=1
- TN 一致负：autoPass=FALSE 且 human=-1
- FP 误报：autoPass=TRUE 且 human=-1（规则过于乐观）
- FN 漏报：autoPass=FALSE 且 human=1（规则过于保守）

**`EvalRegressionReport` 字段：**
```
sampleCount         样本总数
passRate            当前规则重跑通过率（autoPass=TRUE 占比）
avgScore            当前规则重跑平均分
avgRelevancyScore, avgCorrectnessScore, avgFaithfulnessScore   三维均分
tp, tn, fp, fn      四格计数
accuracy            (tp+tn)/sampleCount —— 与人工标签一致率
precision           tp/(tp+fp)          —— 判“通过”中人工认可比例
recall              tp/(tp+fn)          —— 人工正样本被规则识别比例
```

> precision/recall 以 human=1 为正类。**分母为 0 时取 1.0**（无该类样本则视为无性能问题）。

**不落库**：软报告按需触发，重复跑反映规则改动前后差异，落库无对比意义。

**硬门禁（占位）**：配置 `rag.eval.regression-gate-enabled` 默认 `false`，本期保留配置位、**不实现阻断逻辑**，后端接口在门禁开启时可据此报错，留作后续演进。

### 3.4 新增接口（EvalController 扩展）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/eval/dataset` | 抽样有标签数据集 `List<LabelledEvalSample>`，参数 `from/to/limit` |
| GET | `/api/eval/regression` | 跑回归报告 `EvalRegressionReport`，参数 `from/to/limit`（内部先抽数据集再重跑） |

均沿用 `@PreAuthorize("isAuthenticated()")` + `@RequestHeader X-Tenant-Id`（缺失即拒绝），租户隔离断言 `tenant_id == headerTenantId`。

### 3.5 与既有机制边界

- **不触碰**：`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted`、既有 `/api/eval/run|result|results|stats`。
- `evaluate()` 保持"纯评估 + Redis"，回归重跑仅用其计算语义（不落库、不改写）。
- 无新增数据库表，**不存在** D2 三处同步（init.sql / TenantServiceImpl / V系列 SQL）问题。

## 4. 数据流

```
用户交互 → /api/chat/feedback → rag_session.feedback（已有，不变）
   ↓
GET /api/eval/dataset → 租户内 join 抽有标签样本（feedback<>0）
   ↓
GET /api/eval/regression → dataset(...) → 逐样本 evaluate() 重跑
   → 四格 TP/TN/FP/FN + accuracy/precision/recall → 返回报告（不落库）
```

## 5. 测试策略（最窄范围）

- **rag 模块 `AnswerEvaluationServiceTest`**：
  - `dataset`：仅纳入有人工标签样本（feedback=1/-1）、无标签剔除、显式租户过滤、时间范围、limit 上限 200；
  - `regression`：给定已知样本断言 TP/TN/FP/FN 与 accuracy/precision/recall 数值；无 TP 时 precision=1.0 退化分支；空数据（sampleCount=0）报告。
- **web 模块 `EvalControllerTest`**：新端点鉴权、租户头缺失拒绝、越权（跨租户 id/查询）过滤。
- 验证命令：`mvn test -Dtest=AnswerEvaluationServiceTest -pl company-rag-rag`、`mvn test -Dtest=EvalControllerTest -pl company-rag-web`（根 reactor 联合编译避免读到本地仓库旧 common 快照）。

## 6. 改动清单

- **rag 模块**
  - Create `.../rag/eval/answer/LabelledEvalSample.java`（复合样本 DTO）
  - Create `.../rag/eval/answer/EvalRegressionReport.java`（报告 DTO）
  - Modify `.../rag/eval/answer/AnswerEvalResultMapper.java`（新增 join 查询方法）
  - Modify `.../rag/eval/answer/AnswerEvaluationService.java`（新增 `dataset` / `regression`）
- **web 模块**
  - Modify `.../web/controller/EvalController.java`（新增 `/dataset`、`/regression`）
- **配置**
  - Modify `application-dev.yml`（`application.yml` 若含 `rag` 段则同步）：`rag.eval.regression-gate-enabled: false`
- **测试**
  - Modify `AnswerEvaluationServiceTest`、`EvalControllerTest`

## 7. 风险与观察项

| 风险 | 说明 | 缓解 |
|---|---|---|
| join 跨表隔离 | `answer_eval_result` 与 `rag_session` 同租户 schema，join 由 RLS 兜底；但抽取 SQL 必须显式带租户过滤 | 抽取 SQL 强制 `tenant_id = headerTenantId` + schema 白名单 |
| 数据集依赖反馈量 | 冷启动无反馈时数据集为空，回归样本数不足 | 报告对 sampleCount=0 明示；后续可加"启动种子样本"旗标 |
| 规则重跑成本误判 | 回归当前同步执行；若未来维度引入 LLM 判别会拖慢 | 本期三维均为本地规则（无 LLM）；相关位预留熔断钩子注释 |
| SQL 注入 | from/to/limit 参数化绑定；schema 需白名单 | 沿用 `TenantServiceImpl` 既有 schema 校验 |
| 表/视图同步 | 无新增表，规避 D2 三处同步问题 | — |

## 8. 后续演进（本期不做）

- 固化数据集快照（`evalset` 表 + 版本）实现可复现评测。
- 硬门禁真正接入关键路径（发布/上线），按指标阈值阻断或告警。
- 冷启动种子样本、反馈量不足时的采样降级。
- 将回归报告接入可观测看板。
