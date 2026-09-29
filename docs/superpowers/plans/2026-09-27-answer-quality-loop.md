# 实现计划：答案质量闭环（Answer Quality Loop）

| 属性 | 值 |
| --- | --- |
| 状态 | 待批准（重写版，对齐 spec v5.15） |
| 日期 | 2026-09-28 |
| 来源 spec | `docs/superpowers/specs/2026-09-27-answer-quality-loop-design.md`（v5.15） |
| 领域 | 反馈联动 / 数据集抽取 / 回归重跑 / 历史快照 |
| 涉及模块 | common / tenant / bootstrap / rag / web |
| HARD-GATE | 已解除。本阶段**只产实现计划，不落实现代码** |

> 说明：上一版 plan 按 v1/v5 早期 spec 编写，与 v5.15 严重不同步（表结构、`selectDataset`、`EvalDecision`、方法签名、`EvalProperties` 键全过时）。本版重写对齐 v5.15。

---

## 1. 目标

把「评估 → 反馈 → 修正判定 → 回归验证」的答案质量闭环落到代码。核心交付：

1. **反馈联动**：复用 `rag_session.feedback`（读取侧 join，反馈路径零改动，DB 约束不变）。
2. **数据集抽取**：新增 `/api/eval/dataset`（@Select 会话去重 + 时间倒序截断）返回 `List<LabelledEvalSample>`。
3. **回归重跑**：`/api/eval/regression`（Service 层 tenant 键锁 + `doEvaluate` 唯一评估入口 + `evaluateNoCache` 不写 Redis）+ 落含**指纹 + rule_version** 的快照。
4. **历史查询**：`/api/eval/history`（回归报告历史，手写 LIMIT/OFFSET + 独立 count 分页）。

---

## 2. 明确不做（范围边界）

- **存量接口不动**：`/run`、`/result`、`/results`、`/stats`、`updateFeedback`、`rag_session.feedback` 列、`evaluateAllPersisted` 均不触碰（spec §3.5）。
- 不新增 fasthtml 前端页面；仅补 REST 接口（含聚合字段）。
- 不加改租户插件 append；不新增全局 `PaginationInnerInterceptor`（spec §3.4/§3.5）。
- **单实例假设**：进程内 `ConcurrentHashMap` 锁；多副本升级 Redisson `RLock`（spec §3.4，本期不实现）。

---

## 3. 前提与现状核实结论（已现场确认，行号对应当前代码）

| 项目 | 现状 | 影响 |
| --- | --- | --- |
| `TenantServiceImpl.buildCreateTableSql` | 位置模板 `%s` 占位 **23** 个、`.formatted` 在 :302、实参 :303-309 计 **23** 个；`rag_session` 建表 :209-221 **无 feedback 列** | feedback 是 `rag_session` 块内的字面量列，增列**不增加 `%s`**（23 保持 23）；只有回归表渲染段（任务 0.3）才 +1 → ①=24/24 或 ②=23/23 追加 |
| `TenantServiceImpl.buildCreateIndexSql` | :317-344，`.formatted` 在 :336、实参 :337-342 计 **22** 个 | 加 `idx_%s_session_feedback`（2 个 `%s`）后实参去 22→**24** |
| feedback runner `migrateRagSessionFeedbackColumn` | SchemaMigrationConfig:32-98；循环体 `if (columnExists){}` 内有 `continue;`(:65)，`CREATE INDEX idx_%s_session_feedback`(:76-80)**在 else 路径内** | 存量租户（列已存在）→ :62 true → :64 skippedCount++ → :65 continue → **索引永不建**（spec 九/十/十二轮阻断项） |
| answerEval runner `migrateAnswerEvalResultTable` | :175-228，用 `%1$s` 索引式占位 + 正则白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`(:187-190) | 第 6 份 runner 参照此写法 |
| `AnswerEvaluationService` | 构造注入 5 依赖(:32-36)；`evaluate`(:46) 与私有 `evaluateAndPersist`(:111) 各写 Redis、维度逻辑重复(:54-69 / :119-133)；**无 `doEvaluate`/`evaluateNoCache`/`dataset`/`regression`** | 抽 `doEvaluate` 唯一入口 + `evaluateNoCache` 无缓存 |
| `AnswerEvalResultMapper` | 仅 `extends BaseMapper`(:10)，无 `@Select` | 新增 `selectDataset` @Select（显式租户断言） |
| `TenantMyBatisPlusConfig.ignoreTable` | :42-52，豁免 `sys_tenant/sys_user/sys_user_tenant_rel/audit_log(+.audit_log)` | 追加 `answer_eval_result`、`rag_session`（**spec §3.2 :83 既定意图，非顺手扩大化**：两表同租户 schema，改成 ignoreTable 后既有查询靠 search_path + 显式断言兜底；`selectDataset` 已有 e/s 双显式租户断言故安全） |
| `EvalController` | :33 注入 `AnswerEvaluationService`；4 接口（run/result/results/stats），`@RequestHeader X-Tenant-Id` 判空 + `@PreAuthorize`；类级 `@ConditionalOnProperty("rag.eval.enabled")`(:30) | 新增 3 接口沿用校验 + ADMIN/USER；锁不落在 Controller |
| `EvalProperties` | **不存在** | 新建 `com.company.rag.rag.eval.config` 包 |
| `application.yml` rag 段 | :121-138，仅 agent/memory，**无 eval 子节点** | 新建 `rag.eval`（须含 enabled + rule-version，spec §3.6） |
| `application-dev.yml` rag.eval | :79-82（enabled/online-enabled/async-enabled），无 rule-version | 补 `rule-version: v1.0` |
| `EvalControllerTest` | 沿用 | 新接口用例沿用 |
| `RagConstant` (common.constant) | 存在 | `EvalRegressionReportDdl` 复用包路径 |
| `ApprovalProperties` | `@Data @Component @ConfigurationProperties` + 字段初值 | `EvalProperties` 参照此范式 |

---

## 4. 阶段划分与落地顺序（对齐 spec §3-§4）

依赖：`EvalProperties`(0.1) 独立；`EvalRegressionReportDdl`(0.3) 独立；`doEvaluate`(P2) 纯重构独立；P1 依赖 P0 DDL；P3 依赖 P0 DDL + P2；P4 依赖 P3 产出报告。

| 阶段 | 名称 | 依赖 |
| --- | --- | --- |
| P0 | 表结构与配置基线（EvalProperties + DDL + 迁移 runner + 配置段） | — |
| P1 | 数据集抽取（selectDataset + dataset + /api/eval/dataset） | P0 DDL |
| P2 | `doEvaluate` 唯一评估入口 + `evaluateNoCache` | —（纯重构） |
| P3 | 回归重跑（tenant 键锁 + 报告落库 + /api/eval/regression） | P0 DDL + P2 |
| P4 | 回归历史查询（/api/eval/history） | P3 产出报告 |

> 建议 P0→P4 串行 TDD 红绿；P1/P2 互不依赖可并行。验证命令见 §9，窄测试、不跑全量。

---

## 4.1 阶段 P0：表结构、迁移 runner 与配置基线

### 任务 0.1 新建 `EvalProperties`（spec §3.4 配置绑定）
- **new** `company-rag-rag/src/main/java/com/company/rag/rag/eval/config/EvalProperties.java`
- **必须 `@Component`**（全项目无 `@ConfigurationPropertiesScan`/`@EnableConfigurationProperties`，属性类靠 `@Component` + `@ComponentScan("com.company.rag")` 注册）；`@Data @Component @ConfigurationProperties(prefix = "rag.eval")`，参照 `ApprovalProperties`。
- **不加 `@ConditionalOnProperty`**（对齐 ApprovalProperties；装配门控在 EvalController/Service/evaluator 类级）。
- **禁用 `@DefaultValue`**（`@Target({PARAMETER,RECORD_COMPONENT})` 不含 FIELD）。
- 字段与**字段初始值**（数值/布尔用初值兜底；**字段名必须与键 relaxed 对齐**）：
  - `private boolean enabled = false;`（绑定 `rag.eval.enabled`，仅供 `@PostConstruct` 决定是否断言 rule-version，**不参与装配**）
  - `private String ruleVersion;` → `rag.eval.rule-version`，**保留 null**（绝不给初始值）
  - `private long regressionLockTimeoutMs = 30000;` → `rag.eval.regression-lock-timeout-ms`（**不可写成 `lockTimeoutMs`**，否则 relaxed 不匹配、显式配置静默失效）
  - `private int datasetLimitDefault = 50;` → `rag.eval.dataset-limit-default`
  - `private int datasetLimitMax = 200;` → `rag.eval.dataset-limit-max`
  - `private boolean regressionGateEnabled = false;` → `rag.eval.regression-gate-enabled`（占位，默认关）
  - `private int historyPageMax = 200;` → `rag.eval.history-page-max`（**独立于 datasetLimitMax**）
- **`@PostConstruct`（spec §3.6/§5）**：`if (enabled && (ruleVersion == null || ruleVersion.isBlank())) throw new IllegalStateException(...)`——`enabled=false` 停用评估放行不抛；`enabled=true` 缺 version 抛（防 prod 静默 500）。

### 任务 0.2 `rag_session.feedback` 列（两路径，spec §3.1）
**落点 A：`TenantServiceImpl.buildCreateTableSql`**（新建租户补列）
- 在 `rag_session` 建表 SQL（:209-221）中、`create_time` 前插入 `feedback SMALLINT NOT NULL DEFAULT 0,`。
- **feedback 是字面量列，不增加 `%s` 占位**：`buildCreateTableSql` 仍 **23 `%s` / 23 实参**，**.formatted 实参（:303-309）不动**。占位只随任务 0.3 回归表增加（选①=24/24、选②=23/23）。
- **严禁把 feedback 索引塞进本方法**（见任务 0.2 落点 B 与任务 0.3 红线）。

**落点 B：runner `SchemaMigrationConfig.migrateRagSessionFeedbackColumn`（:32-98）修复（spec 九/十/十二轮）**
- 当前 bug：CREATE INDEX(:76-80) 在 `if (columnExists)` else 路径内，存量租户在 :65 `continue` 直接跳走 → 索引永不建。
- 修复：**把 `if (columnExists != null && columnExists) { ... continue; }`(:62-66) 改写为 `if/else`，删除 `:65` 的 `continue;`**（`skippedCount++` 保留在 if 分支），`ALTER ADD COLUMN`(:69-73) 移到 else 分支，**`CREATE INDEX idx_<schema>_session_feedback`(现 :76-80，IF NOT EXISTS 幂等) 移出到 `if/else` 语句之后（if/else 块之外、两分支共用）**——未迁移租户先 ALTER 再建索引，已迁移租户跳过 ALTER 但仍建索引。
- **`log.info("...成功添加 feedback 列")`(:82) 与 `migratedCount++`(:83) 仅留在 else 分支内、无共享尾**（防已迁移 schema 被误计且打印「成功添加」，spec 十三轮）。**已知语义漂移（可接受，勿改）**：存量租户走 if 分支 `skippedCount++` 记为「跳过 1」，但其后仍执行 CREATE INDEX 建索引——**:91 日志「成功 0 个，跳过 1 个」对"已记跳过却已建索引"措辞失真（"跳过"仅指跳过 ALTER ADD COLUMN，不含索引）**；不影响正确性，索引是否建成以 :190 的 pg_indexes 存在断言为准，不因日志措辞改 runner 分支结构。
- **参照 answerEval runner 补正则可逻辑白名单** `schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")` 防 SQL 注入（spec 十七轮：循环体内白名单，查询条件无白名单）。
- 建索引 SQL `idx_%s_session_feedback` 用 `String.format`（与 runner 自身现有写法 :76-79 一致），勿改索引式 `%1$s`。

### 任务 0.3 `eval_regression_report` 表（单一 DDL 源，spec §3.2.3）
- **new** `company-rag-common/src/main/java/com/company/rag/common/constant/EvalRegressionReportDdl.java`
- 唯一静态方法 **`public static String build(String schemaName)`**（**类名方法名全文统一，废旧称 `buildEvalRegressionReportSql`**），返回整段：建表 + 2 索引 + RLS + policy + grant。只装 `eval_regression_report`，不承载 rag_session 补丁。
- **DDL 列（对齐 spec §3.2.3，勿用 dataset_name/total_pass/total_avg_score/detail_json/created_at）：**
  ```sql
  CREATE TABLE IF NOT EXISTS %s.eval_regression_report (
      id BIGSERIAL PRIMARY KEY,
      tenant_id BIGINT NOT NULL,
      run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      rule_version VARCHAR(64) NOT NULL,
      dataset_fingerprint VARCHAR(64),          -- md5(排序后 session_row_id 拼接)
      dataset_from TIMESTAMP,
      dataset_to TIMESTAMP,
      sample_count INT NOT NULL,
      pass_rate DOUBLE PRECISION NOT NULL,
      avg_score DOUBLE PRECISION NOT NULL,
      avg_relevancy DOUBLE PRECISION NOT NULL DEFAULT 0,
      avg_correctness DOUBLE PRECISION NOT NULL DEFAULT 0,
      avg_faithfulness DOUBLE PRECISION NOT NULL DEFAULT 0,
      persisted_pass_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
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
  CREATE INDEX IF NOT EXISTS idx_%s_eval_rep_tenant_time
      ON %s.eval_regression_report (tenant_id, run_time DESC);
  CREATE INDEX IF NOT EXISTS idx_%s_eval_rep_tenant_fp
      ON %s.eval_regression_report (tenant_id, dataset_fingerprint);
  ALTER TABLE %s.eval_regression_report ENABLE ROW LEVEL SECURITY;
  ALTER TABLE %s.eval_regression_report FORCE ROW LEVEL SECURITY;
  DROP POLICY IF EXISTS tenant_isolation_eval_rep ON %s.eval_regression_report;
  CREATE POLICY tenant_isolation_eval_rep ON %s.eval_regression_report
      FOR ALL TO company_rag_app
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());
  GRANT SELECT, INSERT ON %s.eval_regression_report TO company_rag_app;
  GRANT USAGE, SELECT ON SEQUENCE %s.eval_regression_report_id_seq TO company_rag_app;
  ```
  - `EvalRegressionReportDdl.build(schemaName)` **实现为单参 Java：`String.format(ddlTemplate, schemaName)`**——内部**全部 `%s` 占位（共 11 个，全部等于同一 `schemaName`）**，`String.format` 会用同一个实参填充全部占位，**不要误写成 11 个实参**。方法体内写死模板（含 11 个 `%s`）+ 单参 `String.format(template, schemaName)`——**无需也不应数清/展开实参列表**，单参自动填充即可。
  - 下方两条 grant 只是冗余加固；真正让两路径都有 DML（含 DELETE）的是 `createTenantSchema` 的 blanket `GRANT ON ALL TABLES IN SCHEMA`(:149) + `ALTER DEFAULT PRIVILEGES`(:151)，存量/新建对该表均已具 DELETE。

**落点 A：`TenantServiceImpl.createTenantSchema`（新建租户，spec §3.2.3-2）**
- 二选一安全落法（**与任务 0.2 的 feedback 列解耦，feedback 列不占位）**：
  - **①** `buildCreateTableSql` 文本**末尾（紧跟在现有最后一条 `;` 之后、同一行/下一行前）**再补 **1 个 `%s`**，把已渲染的 `EvalRegressionReportDdl.build(schemaName)` 结果（渲染后内部**无 `%` 字符**）作为**第 24 个实参** → 占位 **24 / 实参 24**。**必须保证该 `%s` 前是完整 DDL 结尾的 `;`，否则与上文拼出的 SQL 段缺分隔符**。
  - **②** 不动占位实参数，把渲染后整段直接追加：`sql += EvalRegressionReportDdl.build(schemaName)` → 仍 **23 / 23**；追加段自带语句分隔（模板内每条以 `;` 结尾），无需额外拼接。
- **务必选其一且只算回归表这一次**：`buildCreateTableSql` 占位唯一合法的 +1 就是回归表渲染段。feedback 列是本方法内字面量、已另行处理（任务 0.2，不占位）——**不得把「24」同时算给 feedback 列再算给回归表**（那样会得 25 占位/24 实参 → `MissingFormatArgumentException`）。
- **红线（spec 九轮阻断项，勿混淆）**：feedback **索引** `idx_%s_session_feedback` 严禁写进 `buildCreateTableSql`——真正理由：**建表 SQL 只放列/表定义，索引统一归 `buildCreateIndexSql`**，且写进建表串会额外引入 2 个 `%s`，与任务 0.3 回归表的 +1 占位叠加后占位/实参配对混乱（反馈 #5 纠偏：**「会先于 ALTER 导致列缺失」只适用于存量 runner 场景**，新建租户走落点 A 已含列，二者不可混同）——索引固定落 `buildCreateIndexSql`（22→24）。

**落点 B：runner `SchemaMigrationConfig`（存量租户补表，spec §3.2.3-2）**
- 新增第 6 份 runner **`migrateEvalRegressionReportTable`**，参照 `migrateAnswerEvalResultTable`(:175-228) 范式。
- 新增**私有** `forAllTenantSchemas(Consumer<String>)` helper：直取 `SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'tenant_%'`(:38-42)，**循环体内**做正则白名单 + per-schema `try/catch`（**对齐 feedback/userId 容错语义：消费体内 `try/catch + log.error + continue 下一个`**，spec §3.2.3/十七轮）——**非重构既有 5 份**，既有 5 份保持查询内联、一行不动。
- 每个 schema **`jdbcTemplate.execute(EvalRegressionReportDdl.build(schemaName))`** 执行（`build` 返回 String，须经 `jdbcTemplate.execute(...)` 落库执行；幂等，`DROP POLICY IF EXISTS` + `IF NOT EXISTS`，重复跑自动跳过已建 object）。
- 时序：`current_tenant_id()` 必已由 init.sql(:208) 定义，无顺序问题。

**落点 C：`sql/init.sql`**
- 仅以**注释形式**存 DDL 供参考，不参与执行（含 `<schema>` 占位符会建到 public 且 sequence 授权冲突，spec §3.2.3-3）。

### 任务 0.4 配置段（spec §3.4/§3.6 前置条件）
- **application.yml 基段** `rag:` 段（:121-138）新增 `eval:` 子段：
  ```yaml
  rag:
    eval:
      enabled: true          # 装配开关，基段必须有（缺失→/api/eval/** 全 404）
      rule-version: v1.0     # 必须给死值，不能留空（三合一必崩，spec 十三轮）
      regression-lock-timeout-ms: 30000
      dataset-limit-default: 50
      dataset-limit-max: 200
      regression-gate-enabled: false
      history-page-max: 200
  ```
- **application-dev.yml** `rag.eval`（:79-82）补 `rule-version: v1.0`。
- **两级前置核对（spec §3.6 结论）**：① 端点存在靠 `rag.eval.enabled: true`（基段已配）；② 在线样本沉淀靠 `rag.eval.online-enabled: true`（prod 需开启，否则数据集恒空）。
- 按 Spring **property 级合并**（非 map 覆盖）：dev/prod/test 未书写的 `rule-version` 等从基段继承，三 profile 均拿到 v1.0、启动不崩。

**阶段 P0 验证**（落在改动模块，见 §9 命令）：
- `TenantServiceImplSchemaTest`：buildCreateTableSql 渲染后含 `feedback` 列与 `eval_regression_report` 表；buildCreateIndexSql 渲染后含 `idx_<schema>_session_feedback` 索引，且 **`%s` 占位数 == `.formatted` 实参数**（防空参 MissingFormatArgumentException）；新建 + 存量 schema 的 `rag_session` 均含 feedback 列 + 同名索引；新表已具 DELETE 权限（blanket GRANT 口径）。
- `EvalRegressionReportDdlTest`（new，common）：`build("tenant_a")` 幂等可重复执行，RLS/policy/grant 齐备。
- `EvalPropertiesTest`（new，rag）：缺省场景 ruleVersion==null、regressionLockTimeoutMs==30000、datasetLimitDefault/Max==50/200、historyPageMax==200、regressionGateEnabled==false；**`enabled=true` 且 ruleVersion=blank → `@PostConstruct` 抛；`enabled=false` 缺 ruleVersion → 不抛**；relaxed 绑定断言（`rag.eval.regression-lock-timeout-ms: 5000` → `getRegressionLockTimeoutMs()==5000`）。
- runner 测试：`migrateEvalRegressionReportTable` 对存量 schema 幂等补表；`migrateRagSessionFeedbackColumn` 修复断言（见任务 0.2，spec §5 Observer 用 ListAppender 捕 :91 日志文本，逐条断言 `成功 0 个，跳过 1 个` / `成功 1 个，跳过 0 个` / 幂等重跑不加）。**核心修复点必须显式断言索引真存在**：对存量 schema（列已存在、走 if 分支 skippedCount++）修复后仍须建索引，故验证**补一条 DB 断言** `SELECT 1 FROM pg_indexes WHERE indexname='idx_<schema>_session_feedback'` 存在（否则若实现者漏把 CREATE INDEX 移出 if/else——被 catch 吞或幂等跳过——日志断言仍可能通过而索引缺失无人发现）。

---

## 4.2 阶段 P1：数据集抽取（spec §3.2）

**依赖**：P0 DDL 就绪 + ignoreTable 豁免。

### 任务 1.1 `selectDataset` Mapper
- **改** `AnswerEvalResultMapper.java` 追加 `@Select`（**`init.sql`/XML 不改**，join 用 @Select 避免 namespace/路径歧义）。
- **SQL（对齐 spec §3.2.2，勿用 DISTINCT ON (query)/source 过滤）:**
  ```
  SELECT * FROM (
    SELECT DISTINCT ON (e.session_row_id)
      e.query, e.context, e.answer, e.pass AS persisted_pass, e.score AS persisted_score,
      s.feedback AS human_label, e.tenant_id AS tenant_id,
      e.session_row_id, e.id AS eval_id, e.create_time
    FROM ${schema}.answer_eval_result e
    JOIN ${schema}.rag_session s ON s.id = e.session_row_id
    WHERE e.tenant_id = #{tenantId}
      AND s.tenant_id = #{tenantId}
      AND e.session_row_id IS NOT NULL
      AND s.feedback <> 0
      AND e.create_time BETWEEN #{from} AND #{to}
    ORDER BY e.session_row_id, e.id DESC
  ) t
  ORDER BY t.create_time DESC
  LIMIT #{limit}
  ```
- **去重键 = `e.session_row_id`**（非 query）：内层按会话取最新评估（`e.id DESC` 处理同秒多行），外层 `ORDER BY t.create_time DESC LIMIT` 取**最新 n 会话**（防数据集冻结在最早样本，spec 五轮阻断项）。
- `${schema}` 仅存 `TenantContext.getSchema()` + 白名单；其余 `#{}` 绑定。
- 参数：`@Param("schema") String`、`@Param("tenantId") Long`、`@Param("from")/("to") LocalDateTime`、`@Param("limit") int`。

### 任务 1.2 `LabelledEvalSample` DTO（new，spec §3.2.2）
- **new** `company-rag-rag/.../eval/answer/LabelledEvalSample.java`
- 字段：`query, context, answer, tenantId(Long), persistedPass(Boolean), persistedScore(double), humanLabel(Short: 1/-1), sessionRowId(Long), evalId(Long), createTime(LocalDateTime)`。
- **SELECT 列与 DTO 属性一一对应（含 `evalId`←`eval_id`，防静默丢列）**；映射靠 `map-underscore-to-camel-case(true)`(:145)，不写 @Results。
- **命名二分（spec 十一轮）**：`persistedPass`/`persistedScore` 专指落库 `e.pass`/`e.score`；重跑均分供报告用 `avgScore`/`avg_score`。

### 任务 1.3 Service 方法 `dataset`
- **改** `AnswerEvaluationService` 新增：`List<LabelledEvalSample> dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`。**签名 `tenantId` 形参实际会被 context 覆盖（死参）——保留形参仅为对齐 spec 签名，但方法体以 `TenantContext` 为唯一真实来源**；实现时建议加注释「形参忽略、以 context 为准」或改为不定参，防误用。
- 校验（spec §3.4 统一 schema/tenant 校验，由 Controller 前置或 Service 内 resolveTenant/resolveSchema 共用）：`schema = TenantContext.getSchema()` null/blank → **400** + 白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`；`tenantId = TenantContext.getTenantId()` null → **400**（**单源自 TenantContext，不走 @RequestHeader**，spec 六轮）；`from`/`to` **均非 null** → 缺任一 400。
- **Mapper 传参单源钉死**：`dataset` 方法**唯一的 `tenantId` 值 = `TenantContext.getTenantId()`**（方法形参即赋予该解析结果，与 schema 同源），把**这个解析值**作为 `@Param("tenantId")` 传给 `selectDataset`——**不得从别处二次取值**（防调用方手写形参与上下文分裂，spec 六轮）；`@Param("schema")` = `TenantContext.getSchema()`（白名单后）。调用方传入的 raw tenantId **不可直通**，须先过 resolveTenant。
- limit 口径（**新增实现，勿复用 `listResults`:190 的回落 50**，spec §3.2.2/十一轮）：`0<limit<=200` 透传；`limit<=0` 回落 `datasetLimitDefault(50)`；`limit>200` 收敛 `datasetLimitMax(200)`。

### 任务 1.4 `/api/eval/dataset` 接口
- **改** `EvalController` 追加 `POST /api/eval/dataset`：
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")`；缺省 limit=50、from/to 缺任一 → 400。
  - **不加锁（锁只在 Service/regression）**；Controller 做校验 + 转发。
- **改** `EvalControllerTest` 补用例：成功取数（limit 透传/收敛）、无租户头拒绝、from/to 缺失 400、越权 schema 名 400、ADMIN/USER 通过 + viewer 拒。

**阶段 P1 验证**：`AnswerEvaluationServiceTest` 补 dataset 用例（feedback≠0 纳入、按会话去重 + 时间倒序、显式租户断言、limit 回落/收敛、schema 白名单、`TenantContext.getTenantId()`/`getSchema()` null→400）；手动 `POST /api/eval/dataset` 返回非空样本。

---

## 4.3 阶段 P2：`doEvaluate` 唯一评估入口 + `evaluateNoCache`（spec §3.3）

**依赖**：独立（纯重构，不改变 `evaluate`/`evaluateAll` 契约）。

### 任务 2.1 `EvalDecision` record（new）
- **new** `com.company.rag.rag.eval.answer.EvalDecision`，**覆盖 toString 供日志**。
- 字段：`boolean relevancy, boolean correctness, boolean faithfulness, boolean pass, double score`（**三维布尔 + pass + score**，供三维一致率与四格；不是 query/answer/persistedPass/ruleVersion）。

### 任务 2.2 `doEvaluate` 唯一评估入口
- **改** `AnswerEvaluationService`：新增私有 `EvalDecision doEvaluate(String query, String context, String answer)`。
- 顺序保持 `relevancy → correctness → faithfulness`（对齐 :54-58 / :119-123，不改乱）。
- **唯一还原规则（spec 四条，实现不设分叉）**：`dimensionScores` 键**全名** `relevancy/correctness/faithfulness`，值 `true→1.0/false→0.0`；`score` = 三维均值 = `((r ? 1.0 : 0.0) + (c ? 1.0 : 0.0) + (f ? 1.0 : 0.0)) / 3.0`（**必须 double 字面量 + 每个三元表达式各自加括号**，参照 :61 `v ? 1.0 : 0.0` + :64 `.average()` 的现有 double 写法）。**硬雷**：① 整数形式 `(r?1:0 + c?1:0 + f?1:0)/3` 做整数除法 → 恒 0.0 或 1.0，0.33/0.67 均分丢失、avg_score 失真；② 无内层括号 `(r?1.0:0.0 + c?1.0:0.0 + f?1.0:0.0)/3.0` 时，`+` 优先级高于 `?:`，会被解析成 `r ? 1.0 : (0.0 + c?1.0:...)` 且 `0.0 + c`（boolean）**bad operand types 编译失败**——必须每组独立加括号，正例 `((r?1.0:0.0)+(c?1.0:0.0)+(f?1.0:0.0))/3.0`。`pass` = `relevancy && correctness && faithfulness`（与 `AnswerEvalResult.allPass(passes)` 等价）。
- **改** `evaluate`(:46) 与 `evaluateAndPersist`(:111) **统一改走 `doEvaluate`**，消除两处重复评估逻辑。**落库衔接（关键）**：`EvalDecision` **不含 `dimensionScores` map**（五字段仅三维布尔+pass+score）；落库侧 `AnswerEvalResult.toEntity` 现取 `result.dimensionScores().getOrDefault("relevancy",0.0)` 写实体三列，因此 **`evaluateAndPersist` 重构后须从 `EvalDecision` 三维布尔派生**：`dimensionScores = Map.of("relevancy", r?1.0:0.0, "correctness", c?1.0:0.0, "faithfulness", f?1.0:0.0)` 再入 `toEntity`（或改 toEntity 直接收三维布尔）——**保证落库三列与 doEvaluate 布尔一致，不另存维度分**（spec §3.3「落库侧继续用 dimensionScores」指用其键/值约定，值由布尔派生而非独立评估）。

### 任务 2.3 `evaluateNoCache`
- **改** `AnswerEvaluationService` 新增：`EvalDecision evaluateNoCache(String query, String context, String answer)` = `doEvaluate(...)`（**不写 Redis、不落库**），仅供回归重跑逐样本评估（spec R3，不污染在线缓存）。

**阶段 P2 验证**：`AnswerEvaluationServiceTest` 补：`evaluateNoCache` 不触发 `writeToRedis`（verify redisson put 0 次）；`doEvaluate` 保持维度 pass 顺序；`EvalDecision.pass == AnswerEvalResult.allPass(passes)`（测试断言而非各自推导）；既有 `evaluate_allPass_*` 等回归用例仍绿。

---

## 4.4 阶段 P3：回归重跑（spec §3.3）

**依赖**：P0 DDL + P2 doEvaluate。

### 任务 3.1 报告实体 / Mapper
- **new** `EvalRegressionReportEntity.java`（快照实体，列对齐任务 0.3 DDL）。
- **new** `EvalRegressionReportMapper.java`（extends BaseMapper，供插入）。

### 任务 3.2 Service 层回归（**落在 `AnswerEvaluationService` 内，对齐 spec §6:351**）
- **职责回归 spec**：spec §6 明确写「Modify `AnswerEvaluationService`：新增 …`dataset`、`regression`（锁唯一落点在此）」。因此 **`regression`（及锁、指纹）放在 `AnswerEvaluationService` 内新增**，**不另建独立 `AnswerRegressionService`**（上版建议单独 service 偏离 spec，已撤销——避免跨 service 调 `dataset()`、Controller 注入双改、锁落点散到两处）。
- 新增 `regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit)`：**透传给本 service 的 `dataset(...)`**（保证"同一批样本"口径），缺省 limit=`datasetLimitDefault(50)`。
- **执行顺序（spec 三/十一轮）」**：① `dataset` 取批 → **0 样本 → 不落快照、不抢锁，直接返回明确提示**；② 样本非空才 `tryLock(regressionLockTimeoutMs)` → 抢到重跑+落快照，抢不到 → **409**（不重跑）。
- **锁（spec §3.4，唯一落点）**：`AnswerEvaluationService` 内 `ConcurrentHashMap<Long, ReentrantLock>`（**锁键 `tenantId` 必须与本 service `dataset()` 使用的 `tenant_id` 同源 = `TenantContext.getTenantId()` 解析值**，与任务 1.3 同源规则一致——否则 param 与 context 口径分裂会让锁失效/锁错租户）+ `tryLock(regressionLockTimeoutMs=30s)` 失败 → 409；Controller 不自行加锁，仅转发 409。锁键类型 `Long`，用 `computeIfAbsent` 取/建锁。本期单实例；多副本升级 Redisson RLock（本期不实现）。
- **指纹（spec 四轮阻断项3，Java 侧）**：对 `dataset` 返回列表的 `sessionRowId` **排序后** `md5(join(",", ids))`——与返回集天然一致，免 0 行 `string_agg` NULL；取 `rule_version = EvalProperties.ruleVersion`。
- **重跑**：逐样本 `evaluateNoCache(...)` 得到 `re_run_pass` + 三维布尔。
- **报告指标（spec §3.3 公式，逐字对齐）**：
  - **四格映射（显式钉死）**：`humanLabel ∈ {1,-1}`（`s.feedback` 原值 -1/0/1，WHERE <>0 后剩 {1,-1}，**非 0/1 二值**）。以 re_run_pass vs humanLabel（human=1 为正类）：
    - **TP** = `re_run_pass && humanLabel==1`
    - **FP** = `re_run_pass && humanLabel==-1`
    - **TN** = `!re_run_pass && humanLabel==-1`
    - **FN** = `!re_run_pass && humanLabel==1`
  - `n=sampleCount; accuracy=((double)(tp+tn))/n; passRate=((double)passCount)/n; avgScore=sum(score)/n`（`passCount` 为 re_run_pass=true 条数；**accuracy/passRate 分子分母皆 int，须 `(double)` cast**，否则整数除法商恒 0/1；`avgScore` 中 `score` 本身已是 double、`sum(score)` 为 double，`/n` 自动双精度，**无需 cast**——若按 `sum(int)/n` 则需 cast）。
  - `precision=((double)tp)/(tp+fp)`、`recall=((double)tp)/(tp+fn)`、`negative_recall=((double)tn)/(tn+fp)`——**一律显式 `(double)` cast（tp/fp/tn/fn 均为 int，不 cast 会整数除法 → tp≤tp+fp 商恒 0，核心指标全 0）**；**分母=0 → 1.0**。
  - `f1=2pr/(p+r)` **`p==0 && r==0` → 0.0**（防灾难场景显示满分）。
  - **指标退化边界（显式决策，消除 spec 内部张力）**：spec 同时规定 precision/recall 分母 0→1.0 与 F1 `p=r==0→0.0`。二者在**「仅 TN」退化场景**（`tp+fp=0` 且 `tp+fn=0` → precision=recall=1.0 → F1 公式算出 1.0）**不一致**——此为该公式集的已知边界，**按 spec 字面照算、不做额外特判**（F1 守护 `p==0&&r==0→0.0` 只在「tp=0 且两分母>0」即"预测全负的极端反向灾难"触发，见 §5 陷阱 #15）。实现者不得自行把 precision/recall 分母 0 改成 0 或给「仅 TN」场景特判 F1=0，以免与 spec 公式/意图分叉。
  - `relevancy_agree = ((double)agreeCount_relevancy)/n`（correctness/faithfulness 同理；`agreeCount_*` 为 int，须 `(double)` cast），分母固定 n、非 0。
  - **`persisted_pass_agree = ((double)agreeCount_persisted)/n`**：`agreeCount_persisted` 为 int 须 `(double)` cast；`persisted_pass_i` 同直接取 `dataset()` 返回的每条 `LabelledEvalSample.persistedPass`（=读库 `e.pass`），**不按 eval_id 回查**（spec §3.3/十轮；无需第二次读库）。
- **落快照（spec §3.2.3）**：insert 前显式 `setTenantId(tenantId)` + null 拒绝（对齐铁律，防跨线程 ThreadLocal null → tenant_id=0）；SQL 带显式租户断言作主防线，RLS 仅兜底。写 `fingerprint + rule_version + from/to + 各指标`，返回含 `reportId`。

### 任务 3.3 `/api/eval/regression` 接口
- **改** `EvalController` 追加 `POST /api/eval/regression`：
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")`；参数 `from`/`to`/`limit`（缺省 50、越界收敛 200，透传给 dataset）。
  - 0 样本 → **HTTP 200 + `return R.fail(200, "无匹配样本，未落快照")`**（返回 `R`，`code=200`、`msg` 已设、`data=null`——`R` 的 `setMsg` 是 Lombok void setter，**不可写 `R.ok(null).setMsg(...)` 作返回值（会 returning void where R expected 编译失败）**；**`code=200` 是本计划显式选定的「空结果/未落快照」约定**——笑为成功码但 `data=null` 可区分"有数据"与"未落快照"，前端约定**当 `data==null && msg=="无匹配样本，未落快照"` 即判定未落快照**，见 §7 Q3；R 仅 code/msg/data 三字段，勿写 `code=0`/`message=`——v5.3 旧措辞残留，`message` 非 R 字段）。
  - 不加锁（锁在 Service）；tryLock 失败 409 只转发。

### 任务 3.4 回归 + 并发锁单测（**并入 `AnswerEvaluationServiceTest`，沿用既有范式**）
- 正常运行（非空样本）生成报告（含 reportId、fingerprint==配置 Java 侧计算值、rule_version==配置值、persisted_pass_agree 正确）。
- 0 样本 → 返回明确失败、**不落库、未调用 tryLock/未持锁**（把"先判空后抢锁"钉死）。
- 同 tenant 并发第二次 `tryLock` 超时 → 409（mock 抢锁失败）；异租户并行互不阻塞。
- 锁在 `AnswerEvaluationService` 层（经端点到 Service 断言进程内锁生效，Controller 不自行加锁）。

**阶段 P3 验证**：`AnswerEvaluationServiceTest` 回归用例全绿；手动 `POST /api/eval/regression` 生成首份报告并核对 DB 落库；并发双击一个 409、一个成功。

---

## 4.5 阶段 P4：回归历史查询（spec §3.4）——数据口径**回归报告历史**

**依赖**：P3 产出报告（查 `eval_regression_report`，**不是** answer_eval_result）。

### 任务 4.1 `EvalRegressionReportMapper` 分页 @Select
- **new** 两条手写 `@Select`（**不依赖 MP 分页插件**，`TenantMyBatisPlusConfig` 无 `PaginationInnerInterceptor`；MP 分页需 IPage+Page 参数，且与 schema 拦截器叠加非预期）：
  - ① 查记录：`SELECT ... FROM ${schema}.eval_regression_report WHERE tenant_id=#{tenantId} ORDER BY run_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}`（**仅 tenant_id 一个条件再排序分页，无时间过滤**；`limit=pageSize`，`offset=(page-1)*pageSize`）。
  - ② 独立 count：`SELECT COUNT(*) FROM ${schema}.eval_regression_report WHERE tenant_id=#{tenantId}`（**仅 tenant_id 一个条件，history 无时间过滤**——末尾不加 `...` 也不要臆加无关 WHERE 条件；与查记录同 WHERE 基、无 LIMIT/OFFSET）。
  - **两条均声明 `@Param("schema")`, `@Param("tenantId")`, 查记录另加 `@Param("limit")`, `@Param("offset")`**（对齐任务 1.1 selectDataset 的传参约定）；`${schema}` **必须来自调用方显式传入的 `TenantContext.getSchema()`（Service 层传硬值 + 白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$` 校验通过后才可作 `${schema}` 插值，严禁裸传/未校验即插值）**，**不能省略 schema 形参**，否则 MyBatis 运行期报 `Parameter 'schema' not found`。`#{}` 绑定 tenantId/limit/offset。
  - 两语句经 `TenantLineInnerInterceptor` 各追加 tenant_id（`eval_regression_report` **不在 ignoreTable**）。

### 任务 4.2 `/api/eval/history` 接口
- **改** `EvalController` 追加 `GET /api/eval/history`：
  - `@RequestParam` page/pageSize 缺省 **1/50**（page≤0 回落 1；pageSize≤0 回落 50、`>historyPageMax(200)` 收敛 200——**独立键，不借 datasetLimitMax**）。
  - `page`, `pageSize` 值校验后随 `X-Tenant-Id`、`TenantContext.getTenantId()`/`getSchema()` 传给 `EvalRegressionReportMapper` 两条 @Select（**Service 显式把 `TenantContext.getSchema()` 白名单校验后作为 `@Param("schema")` 传入**，`${schema}` 插值前必经 resolveSchema 白名单——**防实现者裸传 schemaName 造成 SQL 注入/越权 schema，复用 trap #14**，见任务 4.1）。
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")`；无租户头 400、越权 schema 400（同 dataset 的 resolveTenant/resolveSchema 共用）。
- **响应结构（spec §3.4，对齐 IPage 约定）**：`data = { "records":[...EvalRegressionReportEntity], "total":N, "size":50, "current":1 }`；记录按 `run_time DESC, id DESC` 二级排序。

**阶段 P4 验证**：`EvalControllerTest` 补 page/pageSize 边界、size 超 historyPageMax 收敛、total/limit/offset 正确性、无租户头 400、越权 schema 400；手动 `GET /api/eval/history` 分页返回报告历史。

---

## 5. 关键实现陷阱清单（落地时逐条核对，对齐 spec 各轮）

1. **`buildCreateTableSql`/`buildCreateIndexSql` 占位数与实参必须严格一致**：`%s` 只随 **建索引目标** 增加——索引每个 `%s`（schema 表名对，如 `idx_%s_... ON %s.表` 每索引 2 个）；**列定义是字面量，增列不增加 `%s`**。漏参会抛编译期不报、运行期 `MissingFormatArgumentException`。**测试断言覆盖占位数==实参数**（spec 五/九轮）。
2. **feedback 索引落 `buildCreateIndexSql`（22→24 实参）**，严禁写进 `buildCreateTableSql`（否则多 2 个 `%s` 破坏配对 → 建租户整段失败）。
3. **feedback runner 必须删 `:65` 的 `continue;`**、改成 if/else，索引移到 if/else 之外**且不早于 ALTER**（spec 九/十/十二轮；只移语句不删 continue 对存量租户无效，提前建索引会报 column does not exist 被 catch 吞、静默失败）。
4. **runner 用 per-schema `try/catch + log.error + continue 下一个`**（对齐 feedback/userId 容错语义）：任一 schema DDL 失败不跳出循环、不静默连坐；整体 `log.error` 不带 schema 上下文不够（spec 十七轮）。
5. **抽公共 `forAllTenantSchemas(Consumer<String>)` 仅供第 6 份 runner 用**，非重构既有 5 份、不复制新查询；循环体内正则白名单（spec 十六/十七轮）。
6. **`selectDataset` 去重键 = `e.session_row_id`**（非 query），且 DISTINCT ON 表达式须匹配内层最左 ORDER BY（`e.session_row_id, e.id DESC`）；外层 `ORDER BY create_time DESC LIMIT` 防冻结（PG `DISTINCT ON must match initial ORDER BY`，spec 五轮）。
7. **指标命名二分**：`persistedPass/persisted_score`（落库 pass）vs `avgScore/avg_score`（重跑均分）；`re_run_pass`（本次重跑 pass）。不共名 `autoPass`（spec 十/十一轮）。
8. **`persisted_pass_agree` 的 `persisted_pass_i` 直接取 dataset 返回 `persistedPass`**，不按 eval_id 回查、不第二次读库（spec §3.3）。
9. **回归写库显式租户**：入参 `tenantId` 显式 set + null 拒绝，不依赖评估线程 ThreadLocal（主线程 finally 已 clear、异步恒 null → 落 tenant_id=0 永久不可见；spec :49-55 铁律）。
10. **0 样本先判空后抢锁**：`dataset` 空 → 返回明确响应、不落快照、不 tryLock（防 0 样本白占 tenant 锁最长 30s，并发后续全 409；spec 三/十一轮）。
11. **`EvalProperties` 禁用 `@DefaultValue`**、数值/布尔用字段初始值、**字段名与键 relaxed 对齐**（`regressionLockTimeoutMs` 非 `lockTimeoutMs`；`historyPageMax` 独立于 `datasetLimitMax`）；`ruleVersion` 保持 null，`@PostConstruct` 仅 `enabled=true` 时断言非空（spec 六/七/八/九/十二/十三轮）。
12. **history 分页不依赖 MP 分页**：手写 LIMIT/OFFSET + 独立 count；`eval_regression_report` 不在 ignoreTable（spec §3.4）。
13. **`EvalRegressionReportDdl` 放 common**、职责收敛只装 `eval_regression_report`（依赖方向铁律 #1 + spec 七/九轮）。
14. **三接口（dataset/regression/history）共用的 tenant/schema 校验放公共方法**：`TenantContext.getTenantId()` null→400 + `TenantContext.getSchema()` 服务端反查 null/blank→400 + 白名单——客户端不可控（spec §3.4/六轮）；新接口统一收敛到 context 单源，不走 `@RequestHeader`（spec 六轮）。
15. **指标退化边界（见任务 3.2 显式决策）**：`precision/recall/negative_recall` 分母=0→1.0 与 F1 守护 `p==0&&r==0→0.0` 并存是 spec 公式集的已知边界——「仅 TN」场景 F1 按公式=1.0，**照算、不特判**；F1 守护只在「tp=0 且两分母>0」触发。实现不得自行改分母 0 行为（spec §3.3）。
16. **tenantId 双源不可分裂**：`dataset`/`regression` 方法的 `tenantId` **唯一值 = `TenantContext.getTenantId()`**（经 resolveTenant 解析），作为 `@Param("tenantId")` 传给 Mapper；**不直通调用方 raw 参数、不从 `@RequestHeader` 取**（spec 六轮，防 schema 与 tenant 指向不一致）。任务 1.3 已钉死，此处复核。
17. **指标计算雷（全族 `(double)` cast 彻底覆盖，本阶段最易错）**：所有 **int ÷ int** 的均分/比率**必须显式 `(double)` cast**，否则商恒 0/1，**请逐条核对以下每个公式点**：① **score** 三元括号+double——`score=((r?1.0:0.0)+(c?1.0:0.0)+(f?1.0:0.0))/3.0`（无内层括号 `bad operand types` 编译失败：`+` 优先级高于 `?:`；整数形式恒 0/1 丢均分）；② **precision/recall/negative_recall** `((double)tp)/(tp+fp)` 等；③ **accuracy** `((double)(tp+tn))/n`；④ **passRate** `((double)passCount)/n`；⑤ **relevancy/correctness/faithfulness_agree** `((double)agreeCount)/n`；⑥ **persisted_pass_agree** `((double)agreeCount_persisted)/n`；⑦ **四格映射**（humanLabel∈{1,-1} 非 0/1）：TP=pass&label1、FP=pass&label-1、TN=¬pass&label-1、FN=¬pass&label1；⑧ **f1 安全（复核确认）**：`f1=2pr/(p+r)` 中 `p/r` 已是 double（来自 precision/recall 的 `(double)` cast），`2pr/(p+r)` 不整数除、**无需 cast**；f1 守护 `p==0&&r==0→0.0` 仅触发于「预测全负」极端反向场景。此条服务端覆盖任务 2.2/3.2 全部公式实现。
18. **`R` 链式 setter 陷阱（build 阻断）**：`R` 用 Lombok `@Data`，`setMsg`/`setCode`/`setData` 均返回 **void**，**不可嵌套进 `return`**。0 样本等场景**用 `return R.fail(200, "无匹配样本，未落快照")`（`fail(int,String)` 返回 R，code/msg/data 齐）**，勿写 `R.ok(null).setMsg(...)`（returning void where R expected 编译失败）。若需先构造再改字段，拆分两步：`R<T> r = R.ok(null); r.setMsg("..."); return r;`。

---

## 6. 风险与观察项（对齐 spec §3.4/§7）

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| schema/IDOR（阻断项1） | 客户端可传 schema+tenantId 构造他租户 | schema=`TenantContext.getSchema()`（JWT 服务端反查、客户端不可控）+ 白名单；tenantId 同源自 context（六轮） |
| 存量租户缺表（阻断项3） | 仅 createTenantSchema 不覆盖存量 | `migrateEvalRegressionReportTable` 第 6 份 runner 补存量 schema |
| 存量租户缺 feedback 索引（九/十/十二轮） | CREATE INDEX 在 `if(columnExists) continue` 分支内 → 存量永不建 | 改写 if/else + 删 `continue` + 索引移 if/else 之外、IF NOT EXISTS 幂等 |
| 回归上下文局限 | 测固定历史 toolContext（ChatController:178 快照），不覆盖检索/知识库更新 | 快照带 rule_version；文档明示口径边界 |
| 回归重跑并发覆盖 | 同一租户并发各落一条快照 | Service 层 tenant 键锁 + tryLock(30s)→409（单实例；多副本升级 RLock） |
| `doEvaluate` 收敛回归 | 重构破坏既有评估行为 | `evaluateNoCache` 独立 + 既有 `evaluate_allPass_*` 用例回归 |
| 配置段缺失/误删 | 基段缺 `enabled` → /api/eval/** 全 404；缺 rule-version → 落快照 NOT NULL 500 / 启动崩（三合一） | 基段 `enabled:true` + `rule-version:v1.0` 写死；`@PostConstruct` 仅 enabled=true 时断言 |

---

## 7. 待确认（实现前向用户澄清）

- **Q1** `/api/eval/history` 返回口径：本计划默认**回归报告历史**（`eval_regression_report` 表，对齐 spec §3.4 records 为 `EvalRegressionReportEntity`）。若需在线评估历史（answer_eval_result）则 P4 改查另一表。
- **Q2** `dataset`/`regression` 的 `from/to`：spec 要求**均非 null → 缺任一 400**。是否接受"缺省改为 0 样本返回/默认窗口"？本计划按 spec 缺任一 400 落地。
- **Q3** `/regression` 0 样本响应的业务码约定（:298）：本计划选 `R.fail(200, "无匹配样本，未落快照")`（code=200 但 `data=null`）。此为显式约定而非旧措辞残留：HTTP 200 + `data==null && msg=="无匹配样本，未落快照"` ⇒ 判定未落快照。是否改为非 200 业务码（如 204/404）由**前端配合约定**决定——不影响正确性，仅约定澄清（🟢 非阻断）。

---

## 8. 交付物汇总

| 类别 | 文件 | 动作 |
| --- | --- | --- |
| 配置类 | `company-rag-rag/.../eval/config/EvalProperties.java` | new |
| DDL 源 | `company-rag-common/.../constant/EvalRegressionReportDdl.java`（`build(schemaName)`） | new |
| 租户建表 | `company-rag-tenant/.../TenantServiceImpl.java`（feedback 列 + 回归表渲染 + 索引 22→24 实参） | new/modi |
| 迁移 runner | `company-rag-bootstrap/.../SchemaMigrationConfig.java`（feedback runner 修复 + 第 6 份 runner + `forAllTenantSchemas`） | 改 |
| Mapper | `company-rag-rag/.../eval/answer/AnswerEvalResultMapper.java`（@Select selectDataset） | 改 |
| Mapper/实体 | `EvalRegressionReportMapper` / `EvalRegressionReportEntity` | new |
| DTO | `LabelledEvalSample` / `EvalDecision` | new |
| Service | `AnswerEvaluationService`（doEvaluate/evaluateNoCache/dataset/regression，锁唯一落点在此） | 改 |
| Web | `company-rag-web/.../EvalController.java`（dataset/regression/history + resolveTenant/resolveSchema 共用） | 改 |
| 租户隔离 | `company-rag-tenant/.../TenantMyBatisPlusConfig.java`（ignoreTable 追加 answer_eval_result/rag_session） | 改 |
| 配置 | `application.yml` / `application-dev.yml`（rag.eval 段） | 改 |
| SQL | `sql/init.sql`（仅注释存档） | 改 |
| 测试 | 各模块 *_Test.java（ServiceTest/ControllerTest/SchemaTest/DdlTest/EvalPropertiesTest/跨租户 IT） | 改/新增 |

---

## 9. 验证策略（窄测试，不跑全量）

- **TDD**：P0→P4 每阶段先红测、再实现、再绿测。
- **命令（`-am` 编译依赖模块，不跑整库全量 `mvn test`/`mvn install`）**：
  - `mvn -pl company-rag-common -am test -Dtest=EvalRegressionReportDdlTest`
  - `mvn -pl company-rag-rag -am test -Dtest=AnswerEvaluationServiceTest,EvalPropertiesTest`
  - `mvn -pl company-rag-tenant -am test -Dtest=TenantServiceImplSchemaTest`
  - `mvn -pl company-rag-web -am test -Dtest=EvalControllerTest`
- **手动**：启动 bootstrap，逐一 curl `/api/eval/dataset`、`/api/eval/regression`、`/api/eval/history`，核对返回值、指纹/rule_version、DB 落库；并发双击 regression 一个 409、一个成功。
