# DatabaseQueryTool NL2SQL 小升级设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：**待实现**（2026-09 复审确认实施，见下复审说明）
> 前置决策：**nl2sql-A**——只对既有 `DatabaseQueryTool` 做增量加固（补 schema 自校验 + 友好纠错提示），**不引入**闭源 NL2SQL 引擎，也**不在工具内调用 LLM**。
>
> ## 复审说明（2026-09，确认实施）
>
> - **实施理由**：`SqlSchemaValidator` 至今未实现，LLM 生成 SQL 写错表名/列名时工具只回传 PG 原始报错，ReAct 自愈成功率低，属高频且用户可见的坏体验；改动封闭（新增 1 个校验类 + 工具内插一处调用），安全底座零改动。
> - **合并约束已解除**：原设计第 3.1/5/7/8 节要求"与 human-in-the-loop 共用 `ToolResult` 封装、阶段 2 同文件合并实现"。该 spec（`2026-09-14-human-in-the-loop-design.md`）已于 2026-09-19 复审后**归档不实现**，故本约束**不再适用**：本次**不引入 `ToolResult` 富载体**，缺失清单与提示直接内联进现有 `String` 返回（即原 🟡3 结论的取值），实现范围进一步收窄。
> - **不改动项保持不变**：工具对外签名、只 SELECT/危险词拦截、租户 schema 前缀 + RLS、`SENSITIVE_COLUMNS` 脱敏、`MAX_ROWS` 上限、审计落库、`SqlSecurityValidator` 既有逻辑。
> 修订说明：修复 🔴4（`DatabaseQueryTool` 无 `ChatModel`，工具内调 LLM 不可行，改为走现有 ReAct 错误重试）、修正文件路径（该工具在 `agent` 模块，非 rag）与校验类名（既有安全校验为 `SqlSecurityValidator`，本次新增的是 `SqlSchemaValidator`）；🟡3 返回结构的最终取值为**保留 `execute`/`queryDatabase` 的 `String` 对外签名，缺失清单直接内联进返回文本，不引入 `ToolResult` 内部载体**。

## 1. 目标

在不改动工具签名、租户隔离、脱敏、审计安全底座的前提下，增强 `DatabaseQueryTool` 的 SQL 生成质量与自愈能力，减少"表名/列名写错导致查询失败"的坏体验。

**约束：**
- **不在工具内调用 LLM**：`DatabaseQueryTool` 是被动被 Agent 调用的工具，无 `ChatModel`；自纠必须改走外部 ReAct 循环，而非工具反向调 LLM。
- 不动 `com.alibaba.cloud.ai.*` 闭源 NL2SQL starter。
- 不改工具对外签名（LLM 看到的方法名/入参/返回结构不变）。
- **安全底座零膨胀**：租户 schema 前缀、RLS、敏感列脱敏、`LIMIT` 上限、审计落库全部保留，一行不改。
- **不引入富载体**：错误提示直接内联进现有 `String` 返回，不新建 `ToolResult`/`WarningItem`（原合并约束随 human-in-the-loop spec 归档而解除）。
- **宁漏不误**：校验自身异常一律降级放行，绝不因加固阻断查询主链路。

## 2. 现状回顾（已核实）

- 真实路径：`company-rag-agent/.../agent/tool/DatabaseQueryTool.java`（非 rag 模块）。
- 依赖注入：构造 `JdbcTemplate`；`@Autowired AuditLogService`；**无 ChatModel/ChatClient**。
- SQL 校验：`com.company.rag.agent.security.SqlSecurityValidator`（JSqlParser 语法 + 白名单）。
- 既有安全措施：只允许 SELECT、危险词拦截、结果行数限制 `MAX_ROWS=100`、租户 schema 自动前缀 + 禁止显式 schema、敏感列脱敏（`SENSITIVE_COLUMNS` 含 password/email/phone 等）、审计落库（`AuditLogService.recordAsync`）。

**待增强点（方案A 增量）：**
- SQL 校验后、执行前，补充**表名/列名存在性自校验**（`information_schema`）。
- 校验失败时错误信息含"缺失表/列"清单，**作为工具返回错误文本**让 ReAct 自然重试，而非工具内调 LLM。

## 3. 架构设计

**核心思路：** 在 `DatabaseQueryTool` 既有执行管线中插入一处增量：表/列存在性校验。校验失败产出"缺失清单 + 可用 schema 摘要（脱敏）"，拼入工具返回的错误文本 → 现有 ReAct 循环看到错误后用该提示重新生成 SQL（零新增 LLM 调用、零 ChatModel 耦合）。

### 3.1 新增组件（封闭）

| 组件 | 职责 | 复用/依赖 |
|---|---|---|
| `SqlSchemaValidator`（`agent/security/` 下新增） | 对解析后的 SQL，比对 `information_schema` 校验表名/列名存在性；产出缺失清单 + 脱敏 schema 摘要 | `SqlSecurityValidator.extractTableNames`(解析)、`information_schema`、`SENSITIVE_COLUMNS` |
| `SchemaValidationProperties`（`agent/config/` 下新增，或复用既有 `@ConfigurationProperties` 风格） | 提供 `agent.nl2sql.schema-validation.enabled` 开关 | Spring Boot 配置绑定 |

> **实现要点（2026-09 确认）：** 不引入 `ToolResult`/`WarningItem` 富载体。`SqlSchemaValidator` 的产出以纯文本形式由 `DatabaseQueryTool` 拼进既有 `String` 返回值，LLM 可见结构不变，改动面最小。

### 3.2 增量执行流程（🔴4 已修复：不再工具内调 LLM）

```
现状（DatabaseQueryTool.queryDatabase 实际行号）：
  removeComments → SqlSecurityValidator.validateSelectSql(L151)
    → 只 SELECT 双重检查(L159) → 危险词(L164) → 租户上下文非空(L169)
    → containsExplicitSchema(L176) → addSchemaPrefix(L182) → 补 LIMIT(L186)
    → executeQueryInTenantContext(L191) → recordDatabaseAudit(L193) → formatResult

方案A（插入 ★，自纠靠 ReAct 而非工具内调 LLM）：
  ... containsExplicitSchema(L176) 通过后
    → ★ SqlSchemaValidator.validate(cleanSql, currentSchema)
         ├─ 通过 / 未启用 / 内部异常降级 → 继续走 addSchemaPrefix 及既有安全执行段
         └─ 失败 → 直接 return 错误文本（不执行 SQL）
              = "缺失表/列清单 + 当前租户可用表列表 + 近似候选（前 3）"
              └─ ▶ 现有 ReAct 循环读到该文本，据此重新生成 SQL（再次进入本工具）
  ← 不新增 ChatModel、不新增工具内 LLM 重试、不改 addSchemaPrefix 及之后任何一步
```

**插入点选择理由：** 放在 `containsExplicitSchema` 之后、`addSchemaPrefix` 之前——此时表名仍为**裸名**（无 schema 前缀），可直接用 `TenantContext.getSchema()` 作为 `information_schema.table_schema` 的过滤条件，与执行段 `SET search_path TO {schema}, public` 同源，天然限定在当前租户内。放在前缀之后则需额外剥离前缀，属多余工作。

- **自纠由 ReAct 完成，零新增 LLM 调用**：本工具只在失败时把可纠错信息返回给 LLM，由 Agent 编排层自然触发二次生成。符合 `DatabaseQueryTool` 定位。
- 若担心 ReAct 循环次数，`SqlSecurityValidator`/Agent 的既有最大迭代上限仍生效，无额外风险。

### 3.3 校验范围（先窄后宽，控误报为第一优先）

**表存在性（全量校验）**
- 用 `SqlSecurityValidator.extractTableNames(sql)` 取全部表名（已递归覆盖子查询内的表）。
- 比对 `SELECT table_name FROM information_schema.tables WHERE table_schema = ?`。
- **跳过条件 1（系统 schema）**：表名命中系统 schema 白名单（与 `addSchemaPrefix` 现有白名单同源，如 `pg_catalog`/`information_schema`）时不校验。
- **跳过条件 2（CTE 名，必须实现）**：`extractTableNames` 会把 `WITH x AS (...) SELECT ... FROM x` 里的 CTE 名 `x` 当成普通表提取。若不剔除，所有带 CTE 的合法 SQL 都会被误判为"表不存在"。实现时需先从 `PlainSelect.getWithItemsList()` 收集全部 CTE 别名，**在待校验表名集合中减去这批别名**；同时**只要 SQL 含 CTE，就整体跳过列存在性校验**（派生列无法从 `information_schema` 判定）。
- 同理，FROM 中的派生表（子查询）别名不会进入 `extractTableNames` 结果，无需额外处理。

**列存在性（仅在能可靠归属时校验）**
- **单表且无子查询**：校验 SELECT 列表、WHERE、GROUP BY、ORDER BY、HAVING 中的裸列名。
- **多表**：仅校验带限定符（`表名.列名` 或 `别名.列名`）且限定符能映射到真实表的列；裸列名一律跳过（无法判定归属，易误报）。
- **一律跳过**：`SELECT *`、函数调用与表达式内部、`AS` 产出的别名、CTE 名、子查询派生表的列、`information_schema`/系统表相关列。

> 该取舍的目的：列校验的误报代价（合法 SQL 被拦）高于漏报代价（退化为让 PG 报错，与今天行为一致）。第一版宁可少校验，不可错拦。

### 3.4 失败返回文本格式

沿用工具既有 `"错误：..."` 前缀风格，纯文本，示例：

```
错误：SQL 引用了不存在的表或列，请修正后重试。
缺失表：doc_chunk
缺失列：rag_document.creat_time
当前租户可用表：rag_document, doc_chunk_v2, rag_knowledge_base, sys_user
近似候选：doc_chunk → doc_chunk_v2；creat_time → create_time
```

- 只列**当前租户 schema 内**真实存在的表名（本身即 RLS 边界内的元数据，与执行段可见范围一致）。
- **可用列提示仅在"单表列缺失"场景输出**（此时列归属明确）；表缺失与多表场景只给可用表列表，避免输出无关信息。提示中的列名若命中 `SENSITIVE_COLUMNS`，只以 `[脱敏列]` 标记出现，不输出真实列名与列值。
- 近似候选用简单编辑距离取前 3，无候选则省略该行。
- `SENSITIVE_COLUMNS` 目前为 `DatabaseQueryTool` 的 `private static` 常量（在 `agent.tool` 包，与校验类不同包）。为避免为校验功能扩大其可见性，**由 `DatabaseQueryTool` 在调用时把该集合作为参数传入** `SqlSchemaValidator`，校验类自身不持有敏感列清单。

### 3.5 降级与开关

| 情形 | 行为 |
|---|---|
| `agent.nl2sql.schema-validation.enabled=false` | 完全跳过校验，行为与今日逐字节一致 |
| 元数据查询抛异常（超时/连接失败等） | `log.warn` 后**放行**，继续执行原 SQL |
| schema 摘要查询返回空集 | 视为元数据不可用，**放行**（避免误判"所有表都不存在"） |
| 列提取解析不确定 | 跳过该列，不判失败 |

配置项默认 `true`。理由：校验失败即放行，误伤面已被 3.3 的窄范围与上表降级压到很低；保留开关是为了线上出现误报时**无需发版即可关停**。元数据缓存本期不做（每次多一次 `information_schema` 只读查询，量级可接受，见第 8 节）。

## 4. 数据流

1. LLM 调 `DatabaseQueryTool` → 传入 SQL。
2. `removeComments` + `SqlSecurityValidator` 语法/白名单 + 只 SELECT + 危险词 + 租户上下文 + 显式 schema 检查（全部既有，顺序不变）。
3. ★ `SqlSchemaValidator` 用 `information_schema`（限定 `TenantContext.getSchema()`）核对表/列存在性：
   - 缺失 → 生成"缺失清单 + 可用表列表 + 近似候选"文本。
4. 有缺失 → 工具 `return` 该错误文本（**不执行 SQL**）；ReAct 读取后据此重新生成 SQL，再次走步骤 2/3。
5. 无缺失 / 开关关闭 / 校验内部异常 → 走既有安全执行段（`addSchemaPrefix` → LIMIT → RLS 执行 → 脱敏 → 审计，一行不改）。

## 5. 安全与兼容性

| 关注点 | 策略 |
|---|---|
| 无 ChatModel 耦合（🔴4） | 工具内不调 LLM；自纠委托 ReAct，零新增 LLM 调用 |
| 敏感信息 | 摘要只含元数据（表名/列名）；`SENSITIVE_COLUMNS` 命中的列仅以"[脱敏列]"标记，不泄露任何列值 |
| 租户隔离 | `information_schema` 查询以 `table_schema = TenantContext.getSchema()` 过滤，与执行段 `SET search_path` 同源；`information_schema` 本身不受 RLS 约束，故**必须**显式带 `table_schema` 条件，禁止无过滤全库扫描 |
| 注入面 | schema 名来自 `TenantContext`（非用户输入），且沿用执行段既有拼接方式；不新增任何用户可控字符串进入 SQL |
| 执行安全 | 增量只做"只读元数据比对"，危险词/RLS/脱敏/LIMIT/审计执行段一行不改 |
| 返回格式 | 保持既有 `String` 返回与 `"错误：..."` 前缀风格，LLM 可见结构不变（🟡3） |
| 可用性 | 校验异常/元数据不可用一律降级放行，加固不会引入新的失败模式 |

## 6. 测试策略

- **单测（`SqlSchemaValidator`）**：mock 元数据源，覆盖——表不存在、列不存在（单表裸列）、全部存在放行、`SELECT *`/函数/别名/CTE 跳过不误报、多表裸列跳过、系统 schema 跳过、元数据异常降级放行、元数据空集放行、开关关闭放行、敏感列只出 `[脱敏列]` 标记。
- **工具集成（`DatabaseQueryTool`）**：写错表名 → 返回含缺失清单与近似候选的 `"错误：..."` 文本且**未执行 SQL**；合法 SQL → 校验通过后结果与改动前一致。
- **安全回归**：危险词拦截、显式 schema 拒绝、RLS 会话变量设置、脱敏、LIMIT 上限、审计落库的既有测试全部保持通过。
- **验证范围（最窄）**：仅 `company-rag-agent` 模块的 `SqlSchemaValidatorTest` 与 `DatabaseQueryTool` 相关测试类，不跑全仓测试。

> **行为差异说明：** 校验失败提前 `return` 时不会执行 SQL，因此**不落 `recordDatabaseAudit`**——与今日"语法/危险词校验失败"路径完全一致（那些分支同样在审计之前返回），不新增审计语义。

## 7. 改动清单

- **新增**：`company-rag-agent/.../agent/security/SqlSchemaValidator.java`（表/列存在性校验 + 缺失清单文本 + 降级放行）。
- **修改**：`company-rag-agent/.../agent/tool/DatabaseQueryTool.java`（在 `containsExplicitSchema` 之后、`addSchemaPrefix` 之前插入一处校验调用 + 失败返回错误文本）。
- **新增配置**：`agent.nl2sql.schema-validation.enabled`（默认 `true`），按 `ApprovalProperties` 同款 `@ConfigurationProperties` 风格落在 `agent/config/`。
- **不动**：工具对外签名、`addSchemaPrefix` 及之后的安全执行段、闭源 NL2SQL 引擎、数据层 schema、`SqlSecurityValidator` 既有逻辑。

## 8. 风险与观察项

- **校验性能**：每次查询多一次 `information_schema` 只读查询。本期不做缓存；若线上观测到开销，再按 `schema` 维度缓存表/列元数据并在 DDL 变更后失效。
- **误报**：已通过 3.3 窄范围 + 3.5 降级放行双重压制。残余风险是多表 JOIN 场景下 PG 自身报错仍走旧路径（等价于今日行为，不算回退）。
- **ReAct 依赖性**：自纠依赖现有 ReAct 循环能读取并利用错误文本。plan 阶段需在工具的 `@Tool` 描述中补一句"返回缺失表/列信息时可据此修正后重试"，提高模型利用率。
- **安全底座零膨胀**：验收铁律，任何实现不得削弱既有 RLS/脱敏/审计/LIMIT。
- **已移除的耦合**：原"与 human-in-the-loop 共用 `ToolResult`、阶段 2 合并实现"约束随该 spec 归档而作废，本次不引入任何富载体。