# 实现计划：答案质量闭环（Answer Quality Loop）

| 属性 | 值 |
| --- | --- |
| 状态 | 待批准 |
| 日期 | 2026-09-27 |
| 来源 spec | `docs/superpowers/specs/2026-09-27-answer-quality-loop-design.md`（v5.15） |
| 领域 | 评估反馈兜底 / 数据集抽取 / 回归重跑 / 双写收敛 |
| 涉及模块 | common / tenant / bootstrap / rag / web |
| HARD-GATE | 已解除。本阶段**只产实现计划，不落实现代码** |

---

## 1. 目标

把「评估 → 反馈 → 修正判定 → 回归验证」的答案质量闭环落到代码。核心交付：

1. **反馈联动**：`rag_session.feedback` 列持久化，供在线评估侧与质检侧使用（促成 #1 修复判断）。
2. **数据集抽取**：新增 `eval_regression_report` 表 + `/api/eval/dataset` 接口，从历史在线会话抽取评估样本。
3. **回归重跑**：`/api/eval/regression` 接口 + 唯一评估入口 `doEvaluate`，对抽取样本重跑，产出 rule_version 回归报告。
4. **历史查询**：`/api/eval/history` 接口查看回归报告历史。

## 2. 明确不做（范围边界）

- **方案B（模糊路中文恒不命中修复 / tsquery→ILIKE 改造）本期不落地**。spec 已冻结（commit `cda0b97`）。实现完 A 后，再视流量与基线数据决定是否落地 B。
- **C9（模糊路中文恒不命中）本期不修**，仅按 R7 记观察项，不改向量/检索排序逻辑。
- 不新增 fasthtml 前端页面；仅补 REST 接口（含前端便利的聚合返回）。

## 3. 前提与现状核实结论（已现场确认）

| 项目 | 现状 | 影响 |
| --- | --- | --- |
| `TenantServiceImpl.buildCreateTableSql` | `:%s` 占位 23 个，`.formatted` 实参 23 个（:302-309） | 补 feedback 列(1)+回归表渲染段后需同步增占位 |
| `TenantServiceImpl.buildCreateIndexSql` | 11 行索引 ×2 = 22 占位，实参 22 个（:336-342） | 加新索引后同步增参 |
| `rag_session` 建表 (:209-221) | 无 `feedback` 列 | 需补列 + 索引 |
| feedback runner `migrateRagSessionFeedbackColumn` (:32-98) | 循环体 `:65 continue` 位于列存在分支内 | 修复：`continue` 移到 CREATE INDEX 之外，保证跳过列时索引也要建 |
| answerEval runner `migrateAnswerEvalResultTable` (:175-228) | 有正则白名单 `^[a-zA-Z_]\w*$` (:187-189) + 索引式占位 `%1$s` | 第 6 份 runner 参照此写法 |
| `AnswerEvaluationService` | 构造器注入 5 依赖（redisson/relevancy/correctness/faithfulness/mapper）；`evaluate`/`evaluateAndPersist` 各自写 Redis | 新增 `doEvaluate` 唯一评估入口 + `evaluateNoCache` |
| `AnswerEvalResultMapper` | 仅 `BaseMapper`，无 `@Select` | 新增 `selectDataset` |
| `TenantMyBatisPlusConfig.ignoreTable` (:42-52) | 白名单 sys_tenant/sys_user/sys_user_tenant_rel/audit_log | 追加 `answer_eval_result`、`rag_session` |
| `EvalController` | 4 接口，`@RequestHeader X-Tenant-Id` 判空 + `@PreAuthorize` | 新增 3 接口沿用同模式 |
| `EvalProperties` | **不存在** | 新建 `config` 包 |
| `application.yml` rag 段 (:121) | 有 agent/memory，**无 eval 子段** | 新建 `rag.eval` 子段 |
| `application-dev.yml` rag.eval (:78-82) | enabled/online-enabled/async-enabled | 补 `rule-version` |
| `EvalControllerTest` | `@ExtendWith(MockitoExtension)` + `@Mock`/`@InjectMocks` 直调方法 | 新接口测试沿用 |
| `RagConstant` (`com.company.rag.common.constant`) | 存在 | `EvalRegressionReportDdl` 可复用包路径 |
| `ApprovalProperties` | `@Data @Component @ConfigurationProperties` + 字段初值 | `EvalProperties` 参照此范式 |

---

## 4. 阶段划分与落地顺序

依赖关系：EvalProperties(任务 0.1) 自身独立；DDL（任务 0.2/0.3）独立；样本与回归（P1-P3）依赖 DDL 与 doEvaluate；history（P4）依赖 P3 产出报告。

| 阶段 | 名称 | 依赖 |
| --- | --- | --- |
| P0 | 表结构与配置基线（EvalProperties + DDL + 配置段） | — |
| P1 | 数据集抽取（selectDataset + dataset + /api/eval/dataset） | P0 DDL |
| P2 | `doEvaluate` 唯一评估入口 + `evaluateNoCache` | —（纯重构） |
| P3 | 回归重跑（锁 + 报告落库 + /api/eval/regression） | P0 DDL + P2 doEvaluate |
| P4 | 回归历史查询（/api/eval/history） | P3 产出报告 |

> 阶段编号语义：先基线配置与表结构，再业务逻辑，最后接口与验证。因各阶段改动彼此独立（DDL 与 Service 不强耦合、Service 自含 doEvaluate），可并行推进，但 P3 依赖 P0+P2、P4 依赖 P3。落地时建议按 P0→P4 串行走 TDD 红绿，避免一半改动未验证。

---

## 4.1 阶段 P0：表结构与配置基线

**目标**：把 DDL 与配置绑定基建铺好，所有后续阶段以此为依托。

### 任务 0.1 新建 `EvalProperties`
- **new** `company-rag-rag/src/main/java/com/company/rag/rag/eval/config/EvalProperties.java`
- 参照 `ApprovalProperties` 范式：`@Data @Component @ConfigurationProperties(prefix = "rag.eval")`
- 字段与初始值（**非 null 用初始值缺省，不依赖 Spring 填充**）：
  - `enabled = false`（装配开关；注意 `@ConditionalOnProperty` 读到 `rag.eval.enabled=true` 才装配，此处字段初值不影响装配判断，仅作 bean 内引用）
  - `ruleVersion` → `rule-version`，**保留 null**（`@PostConstruct` 中仅当 `enabled=true` 时 `Assert` 非空；缺省 null 以区分「未配置」）
  - `historyPageMax = 200` → `history-page-max`（独立键，默认 200，防超大页打爆）
- **关键设计约束**：
  - **禁用 `@DefaultValue`**（铁律：配置缺省与显式配置须可区分，避免默认值掩盖配置缺失）。
  - `enabled` 两处并存：装配用 `@ConditionalOnProperty` 注解值（类级），`@PostConstruct` 断言用字段值 `this.enabled`。二者读同一键，但语义不同，务必区分。

### 任务 0.2 `rag_session.feedback` 列（两路径）
**落点 A：`TenantServiceImpl.buildCreateTableSql` (:209-221)**
- 在 `rag_session` 建表 SQL 的 `create_time` 前插入：
  ```sql
  feedback SMALLINT NOT NULL DEFAULT 0,
  ```
- 占位增加 1 个（现 23 → 24），`:formatted` 实参 (:302-309) 末尾补一个 `schemaName`。

**落点 B：feedback runner `SchemaMigrationConfig.migrateRagSessionFeedbackColumn` (:32-98) 修复**
- 当前 bug：`:65 continue` 位于 `if (columnExists)` 分支内，导致**跳过列时索引 (`:76-80`) 也不执行**。
- 修复：将 `CREATE INDEX IF NOT EXISTS idx_%s_session_feedback` 移到 `if/else` 之外（无论列是否已存在都执行建索引，幂等），并删掉分支内 `continue`。
- 参照 answerEval runner 补一个正则白名单（`^[a-zA-Z_]\w*$`）防 SQL 注入。
- **提示**：建索引 SQL 里的 `idx_%s_session_feedback` 用 `String.format` 拼接 schemaName，与 runner 自身（:76-77）现有写法一致，勿改用索引式占位。

### 任务 0.3 `eval_regression_report` 表（单一 DDL 源）
- **new** `company-rag-common/src/main/java/com/company/rag/common/constant/EvalRegressionReportDdl.java`
  - 静态方法 `public static String build(String schemaName)` 返回整份建表 DDL（表 + RLS + policy + grant）。
  - 结构（对齐 answer_eval_result 的 RLS 范式）：
    ```sql
    CREATE TABLE IF NOT EXISTS %s.eval_regression_report (
        id BIGSERIAL PRIMARY KEY,
        tenant_id BIGINT NOT NULL,
        dataset_name VARCHAR(128) NOT NULL,
        sample_count INTEGER NOT NULL,
        rule_version VARCHAR(64) NOT NULL,
        total_pass INTEGER NOT NULL,
        total_avg_score DOUBLE PRECISION NOT NULL,
        detail_json JSONB,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    -- RLS + tenant_isolation_regression policy + FORCE ROW LEVEL SECURITY
    -- GRANT SELECT/INSERT/UPDATE/DELETE ON table + USAGE,SELECT ON SEQUENCE id_seq
    ```
- **落点 A：`TenantServiceImpl.createTenantSchema`** 在 :71-72 建表后追加 `jdbcTemplate.execute(EvalRegressionReportDdl.build(schemaName));`（或并入 buildCreateTableSql）。
- **落点 B：`SchemaMigrationConfig`** 新增第 6 份 runner `migrateEvalRegressionReportTable`，调用新私有 helper `forAllTenantSchemas(fn)` 遍历所有 `tenant_%` schema（含正则白名单），对每个 schema 执行 `EvalRegressionReportDdl.build(schemaName)`。
- **落点 C：`init.sql`** 仅加注释存档，不重复 DDL（单一权威源）。
- **关键约束**：`EvalRegressionReportDdl` 放 **common** 模块（tenant + bootstrap 两模块都依赖），避免循环依赖。

### 任务 0.4 配置段
- **application.yml** `rag:` 段新增 `eval:` 子段：
  ```yaml
  rag:
    eval:
      enabled: true
      rule-version: v1.0
      history-page-max: 200
  ```
- **application-dev.yml** `rag.eval` (:78-82) 补 `rule-version: v1.0`。
- **记录** dev/prod 环境是否覆盖 `enabled`。dev 已 `enabled: true`，prod 需复核（当前 prod :89 有 `enabled: true`，需确认归属段）。

**阶段 P0 验证**：
- `TenantServiceImplSchemaTest` 补断言：buildCreateTableSql 渲染后含 `feedback` 列与 `eval_regression_report`；buildCreateIndexSql 渲染后占位实参数量与 `%s` 数一致（防少参 NullPointerException）。
- `EvalRegressionReportDdlTest`（new，common）：`build("tenant_a")` 幂等可执行，RLS/policy/grant 齐备。
- `EvalPropertiesTest`（new，rag）：缺省场景 `ruleVersion` 为 null、`historyPageMax=200`；`enabled=true` 且 ruleVersion=null 时 `@PostConstruct` 抛异常。

---

## 4.2 阶段 P1：数据集抽取

**依赖**：P0 的 DDL 就绪。

### 任务 1.1 `selectDataset` Mapper
- **改** `AnswerEvalResultMapper.java` 追加 `@Select`：
  - 内层 `SELECT DISTINCT ON (query) * FROM answer_eval_result WHERE tenant_id=#{tenantId} AND session_row_id IS NOT NULL AND source != 'manual' ORDER BY query, create_time DESC`
  - 外层再 `ORDER BY t.create_time DESC LIMIT #{limit}` 防冻结最老样本
  - **关键**：DISTINCT ON 属性列必须出现在内层 `ORDER BY` 首列（query）——否则 PG 报错。
- **new** `LabelledEvalSample` DTO（字段：sessionRowId/query/answer/pass/score/evalId/persistedPass）。
- **new** `@Select selectDataset` 返回值 `List<LabelledEvalSample>`，含 `evalId`（回归表 id）与 `persistedPass`（指征样本是否已存在于历史）。

### 任务 1.2 抽取 Service 方法
- **改** `AnswerEvaluationService` 新增 `List<LabelledEvalSample> dataset(Long tenantId, int limit)`，内部限 1~200，调 `evalResultMapper.selectDataset`。

### 任务 1.3 `/api/eval/dataset` 接口
- **改** `EvalController` 追加 `POST /api/eval/dataset`：
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")`，`@RequestHeader X-Tenant-Id` 判空（沿用 :40 模式）。
  - 请求体可选 `limit`（默认 50，上限 200）。
- **改** `EvalControllerTest` 补 3 用例：成功取数含 tenant/limit、无租户头拒绝、limit 超界被 clamp。

**阶段 P1 验证**：
- `AnswerEvaluationServiceTest` 补 `dataset` 用例（含 tenantId 校验、limit clamp）。
- 手动 `POST /api/eval/dataset` 返回非空样本。

---

## 4.3 阶段 P2：`doEvaluate` 唯一评估入口 + `evaluateNoCache`

**依赖**：独立（本阶段只重构，不影响外部行为）。

### 任务 2.1 `EvalDecision` record
- **new** `com.company.rag.rag.eval.answer.EvalDecision`（class + @Override `toString` 供日志）。字段：query/answer/pass/score/persistedPass/ruleVersion。

### 任务 2.2 唯一评估入口 `doEvaluate`
  - **改** `AnswerEvaluationService` 新增 `EvalDecision doEvaluate(AnswerCase c, boolean persist, boolean noCache)` 作为所有评估的唯一汇聚点：
  - 顺序仍：relevancy → correctness → faithfulness（保持 :54-58 顺序，避免规律被打乱）。
  - 负责：维度评估 → 综合 pass/score → 是否写 Redis（noCache=false 写）→ 是否落库（persist=true 落）。
  - **回归重跑必须走 `doEvaluate` + noCache=true：** 只评估并返回 `EvalDecision`，**不写 Redis**（不污染在线缓冲层）。
- **改** `evaluate`(:46) 与 `evaluateAndPersist`(:111) 收敛到 `doEvaluate`，消除重复评估逻辑（现两处逻辑重复，见 :54-69/:19-31）。

### 任务 2.3 `evaluateNoCache`
- **改** `AnswerEvaluationService` 新增 `EvalDecision evaluateNoCache(AnswerCase c)`：等价 `doEvaluate(c, false, true)` 的便捷包装——**不写 Redis、不落库**，仅供回归重跑按样本评估。

**阶段 P2 验证**：
- `AnswerEvaluationServiceTest` 补：`evaluateNoCache` 不触发 `writeToRedis`（verify redisson put 次数为 0）；`doEvaluate` 保持维度 pass 顺序。
- 现有 `evaluate_allPass_whenAllDimensionsPass` 等回归用例仍绿，证明收敛未破坏行为。

---

## 4.4 阶段 P3：回归重跑

**依赖**：P0 DDL + P2 doEvaluate。

### 任务 3.1 Service 层回归方法
- **改** `AnswerEvaluationService` 或新增 `AnswerRegressionService`（建议单独 service，职责单一）：
  - 输入 `datasetName`, `tenantId`, `ruleVersion`, 样本列表。
  - **并发防护**：`ConcurrentHashMap<Long, ReentrantLock>` 键锁（按 tenantId），`tryLock(30s)` 失败返回 409（避免同一租户并发回归覆盖）。
  - **顺序**：①先判空样本（空则返回明确错误）②再抢锁 ③执行业务。抢锁在判空之后，避免无意义锁。
  - **TTL/指纹一致性**：每条样本 Java 侧算 md5 指纹 + 记录 ruleVersion + `persistedPass` 汇总到报告。
  - 落库回归报告 `eval_regression_report`（source 用 P2 doEvaluate + evaluateNoCache）。
- **快照显式租户**：回归写库一律用入参 `tenantId` 显式值，不依赖评估线程 ThreadLocal（沿用 :49-55 铁律注释）。

### 任务 3.2 `/api/eval/regression` 接口
- **改** `EvalController` 追加 `POST /api/eval/regression`：
  - `@PreAuthorize("hasAnyRole('ADMIN','USER')")`，`@RequestHeader X-Tenant-Id` 判空。
  - 请求体：`datasetName`, `sampleCount`, 可选 `sampleIds`。
  - 返回：回归报告摘要（含 ruleVersion、pass、avgScore、detailJson）。
- **改** `EvalControllerTest` 补 3 用例：成功回归、空样本拒、持锁 409（mock 抢锁失败）。

### 任务 3.3 并发锁单元测试
- **new** `AnswerRegressionServiceTest`：
  - 正常回归生成报告。
  - 空样本返回明确失败。
  - 同 tenant 并发第二次 tryLock 超时返回 409。
  - 指纹重复时合并到同一 report（或按配置）。

**阶段 P3 验证**：
- 手动 `POST /api/eval/regression` 生成第一份回归报告。
- 并发双击请求一个 409、一个成功。

---

## 4.5 阶段 P4：回归历史查询

**依赖**：P3 产出报告。

### 任务 4.1 `/api/eval/history` 接口
- **改** `EvalController` 追加 `GET /api/eval/history`：
  - `@RequestParam(required=false) datasetName`, `page`, `size`；`size` 受 `EvalProperties.historyPageMax`（默认 200）约束。
  - **手写 LIMIT/OFFSET + 独立 count**（不依赖 MyBatis-Plus 分页，避免与 schema 拦截器叠加非预期）。
  - `@RequestHeader X-Tenant-Id` 判空 + `@PreAuthorize("isAuthenticated()")`。
- **改** `EvalControllerTest` 补用例：page/size 边界、size 超 historyPageMax 被 clamp、无租户头拒绝。

**阶段 P4 验证**：
- 手动 `GET /api/eval/history` 分页返回默认 `answer_eval_result` 历史（需确认历史数据来源口径，见 §6 风险 R2）。

---

## 5. 关键实现陷阱清单（落地时逐条核对）

1. **buildCreateTableSql / buildCreateIndexSql 的占位数与实参必须严格一致**：加 1 列/表/索引 → 加占位 + 实参各 1。漏参会抛 `java.util.MissingFormatArgumentException`，属于编译期不报、运行期炸的坑。**用测试断言覆盖**。
2. **feedback runner 的 `continue` 位置**：删除分支内 `continue`，建索引提到 if/else 之外——否则新库（列不存在）会跳过索引，与旧库差异。
3. **feedback 索引与正则白名单**：新库无列 → 需先建列再建索引。若列存在跳过，索引仍要建（幂等）。
4. **索引式占位 `%1$s` vs 递增 `%s`**：同一方法内统一。TenantServiceImpl 用递增 `%s`（多段），runner 用 `%1$s` 单段。混用会导致占位错乱。**条件渲染段（EvalRegressionReportDdl）用 `%s` 单实参**，独立方法避免与主 `.formatted` 冲突。
5. **不能在 `if/else` 里塞 `continue` 后仍想执行公共代码**：把公共逻辑（index）放出分支。
6. **didConfigureClassName 教训（回归）**：EvalRegressionReportDdl 放 **common 模块**，否则 tenant 与 bootstrap 无法同时引用（依赖方向倒置，违反铁律 #1）。
7. **selectDataset 的 DISTINCT ON 属性顺序**：内层 `ORDER BY query` 必须出现在首；否则 PG `SELECT DISTINCT ON expressions must match initial ORDER BY` 报错。
8. **回归写库显式租户**：不依赖评估线程 ThreadLocal（主线程 finally 已 clear，异步线程恒 null → 落 tenant_id=0 永久不可见）。回归用 `tenantId` 入参显式设置。
9. **0 样本先判空后抢锁**：先 `if (samples.isEmpty()) throw ...`，再 `tryLock`，避免无意义锁竞争。
10. **EvalProperties 禁用 `@DefaultValue`**：ruleVersion 缺省 null，`@PostConstruct` 仅在 enabled=true 时断言非空。默认值会掩盖配置缺失（铁律：缺省与显式可区分）。
11. **history 分页不依赖 MP 分页**：手写 LIMIT/OFFSET + 独立 count；MP 分页与 schema 拦截器叠加会生成非预期 SQL。

---

## 6. 风险复核

| 编号 | 风险 | 等级 | 缓解 |
| --- | --- | --- | --- |
| R1 | `eval_regression_report` 两路径（createTenantSchema + runner）DDL 不一致 | 高 | 单一 DDL 源 `EvalRegressionReportDdl.build()` 复用；`EvalRegressionReportDdlTest` 断言幂等 |
| R2 | `/api/eval/history` 数据口径：是回归报告历史还是在线评估历史，spec 未 100% 定死 | 中 | 实现前与用户确认口径（见 §7 待确认 Q1） |
| R3 | 回归重跑并发覆盖同一租户报告 | 中 | 键锁 `ConcurrentHashMap<Long,ReentrantLock>` + tryLock(30s)→409 |
| R4 | `doEvaluate` 收敛重构引入行为回归 | 中 | `evaluateNoCache` 独立 + 现有 `evaluate_allPass` 等用例回归 |
| R5 | 新增列/表漏改 createTenantSchema，新租户缺表/缺列 | 高 | P0 阶段 `TenantServiceImplSchemaTest` 断言 DDL 完整性 |
| R6 | prod `enabled` 误开导致在线评估意外生效 | 中 | 复核 prod 配置段归属；按线上流量灰度 |
| R7 | C9 模糊路中文恒不命中（本期不修） | 观察 | 记录基线数据，B 落地后再评估 |

---

## 7. 待确认（实现前向用户澄清）

- **Q1** `/api/eval/history` 返回口径：**回归报告历史**（eval_regression_report 表）还是**在线评估历史**（answer_eval_result）？本计划默认「回归报告历史」，若需在线评估历史则 P4 改查 answer_eval_result。
- **Q2** 回归重跑时，样本是否同时落入 `answer_eval_result`（用于「在线评估历史」口径统一）？本计划默认**只写 eval_regression_report**（detail_json 存全量明细），answer_eval_result 保持在线/手动来源写入，不重复落回归样本——避免同一 query 双写造成 stats 重复计数。若需在线历史包含回归样本，则 P2 `doEvaluate` 回归分支需 `persist=true`，并在报告与 answer_eval_result 间避免重复计数。

---

## 8. 交付物汇总

| 类别 | 文件 | 动作 |
| --- | --- | --- |
| 配置 | `company-rag-rag/.../eval/config/EvalProperties.java` | new |
| DDL | `company-rag-common/.../constant/EvalRegressionReportDdl.java` | new |
| DDL | `company-rag-tenant/.../TenantServiceImpl.java` | 改（feedback 列 + 回归表 + 索引占位） |
| DDL | `company-rag-bootstrap/.../SchemaMigrationConfig.java` | 改（feedback runner 修复 + 第 6 份 runner + helper） |
| Mapper | `company-rag-rag/.../AnswerEvalResultMapper.java` | 改（@Select selectDataset） |
| Service | `company-rag-rag/.../AnswerEvaluationService.java` | 改（doEvaluate/evaluateNoCache/dataset） |
| Service | `company-rag-rag/.../AnswerRegressionService.java` | new |
| DTO | `company-rag-rag/.../LabelledEvalSample.java`、`EvalDecision.java` | new |
| Web | `company-rag-web/.../EvalController.java` | 改（dataset/regression/history） |
| Tenant 隔离 | `company-rag-tenant/.../TenantMyBatisPlusConfig.java` | 改（追加 answer_eval_result/rag_session） |
| 配置 | `application.yml` / `application-dev.yml` | 改（rag.eval 段） |
| 测试 | 各模块 *_Test.java | 改/新增 |
| SQL | `sql/.../init.sql` | 改（仅注释存档） |

---

## 9. 验证策略

- **TDD**：P0→P4 每阶段先写红测、再实现、再绿测。命令范围到改动抛 `-Dtest=` 的目标测试类，跑单模块的 `mvn -pl <module> -am test -Dtest=<Class>`。
- **单测**：见各阶段「验证」小节。
- **手动**：启动 bootstrap，逐一 curl `/api/eval/dataset`、`/api/eval/regression`、`/api/eval/history`，核对返回值与 DB 落库。
- **不跑**：不跑整库全量 `mvn test`/`mvn install`。每个阶段只跑触及模块的窄测试。