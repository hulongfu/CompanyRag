# DatabaseQueryTool NL2SQL 小升级设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅（已根据 2026-09-14-design-review 修订）
> 前置决策：**nl2sql-A**——只对既有 `DatabaseQueryTool` 做增量加固（补 schema 自校验 + 友好纠错提示），**不引入**闭源 NL2SQL 引擎，也**不在工具内调用 LLM**。
> 修订说明：修复 🔴4（`DatabaseQueryTool` 无 `ChatModel`，工具内调 LLM 不可行，改为走现有 ReAct 错误重试）、修正文件路径（该工具在 `agent` 模块，非 rag）、校验类名（`SqlSecurityValidator` 而非 `SqlSchemaValidator`）；并修复 🟡3 返回结构矛盾——**保留 `execute`/`queryDatabase` 的 `String` 对外签名（LLM 可见结构不变），`warning`/缺失清单内联进返回文本；`ToolResult(data/error/warning)` 仅作工具内部解析载体，不改变 LLM 可见返回类型**；明确与 human-in-the-loop 同文件合并实现。

## 1. 目标

在不改动工具签名、租户隔离、脱敏、审计安全底座的前提下，增强 `DatabaseQueryTool` 的 SQL 生成质量与自愈能力，减少"表名/列名写错导致查询失败"的坏体验。

**约束：**
- **不在工具内调用 LLM**：`DatabaseQueryTool` 是被动被 Agent 调用的工具，无 `ChatModel`；自纠必须改走外部 ReAct 循环，而非工具反向调 LLM。
- 不动 `com.alibaba.cloud.ai.*` 闭源 NL2SQL starter。
- 不改工具对外签名（LLM 看到的方法名/入参/返回结构不变）。
- **安全底座零膨胀**：租户 schema 前缀、RLS、敏感列脱敏、`LIMIT` 上限、审计落库全部保留，一行不改。
- **归档**：与 human-in-the-loop 的 `warning` 结构共用一套结构化结果封装，同一文件同批改（见编排总览阶段 2）。

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
| `SqlSchemaValidator`（`agent/security/` 下新增） | 对解析后的 SQL，比对 `information_schema` 校验表名/列名存在性；产出缺失清单 + 脱敏 schema 摘要 | `SqlSecurityValidator`(解析)、`information_schema`、`SENSITIVE_COLUMNS` |
| `ToolResult` 结构化封装（与 human 共用，**内部载体**） | 统一工具**内部**解析结果：`data / error? / warning[]`；对外仍序列化为既有的 `String` 返回（LLM 可见结构不变） | 见 human-in-the-loop spec |

> **归档要点（评审 🟡）：** `SqlSchemaValidator` 与 human-in-the-loop 的 `warning` 落在**同一个 `DatabaseQueryTool` 文件**及**同一套 `ToolResult` 封装**，必须**阶段 2 合并实现**，避免两处独立改动冲突、返回格式碎片化。

### 3.2 增量执行流程（🔴4 已修复：不再工具内调 LLM）

```
现状：
  SQL 生成 → SqlSecurityValidator(语法+白名单) → schema前缀+RLS → 脱敏 → LIMIT → 审计 → 执行

方案A（插入 ★，自纠靠 ReAct 而非工具内调 LLM）：
  SQL 生成
    → SqlSecurityValidator(语法+白名单)
    → ★ SqlSchemaValidator：表名/列名存在性自校验
         ├─ 通过 → 走既有安全执行段
         └─ 失败 → 返回错误文本 = "缺失表/列清单 + 脱敏 schema 摘要"
                      └─ ▶ 现有 ReAct 循环读到错误，用该提示重新生成 SQL（再次进入本工具）
  ← 不新增 ChatModel、不新增工具内 LLM 重试
```

- **自纠由 ReAct 完成，零新增 LLM 调用**：本工具只在失败时把可纠错信息返回给 LLM，由 Agent 编排层自然触发二次生成。符合 `DatabaseQueryTool` 定位。
- 若担心 ReAct 循环次数，`SqlSecurityValidator`/Agent 的既有最大迭代上限仍生效，无额外风险。

## 4. 数据流

1. LLM 调 `DatabaseQueryTool` → 传入 SQL。
2. `SqlSecurityValidator` 语法 + 白名单校验（既有）。
3. `SqlSchemaValidator` 用 `information_schema`（当前租户 schema 内）核对表/列存在性：
   - 缺失 → 生成"缺失清单 + 可用 schema 摘要（脱敏）"。
4. 有缺失 → 工具返回该错误文本；ReAct 读取后用脱敏 schema 提示重新生成 SQL，再次走步骤 2/3。
5. 无缺失 → 走既有安全执行段（RLS/脱敏/LIMIT/审计 不变）。

## 5. 安全与兼容性

| 关注点 | 策略 |
|---|---|
| 无 ChatModel 耦合（🔴4） | 工具内不调 LLM；自纠委托 ReAct，零新增 LLM 调用 |
| 敏感信息 | schema 摘要只含脱敏元数据；`SENSITIVE_COLUMNS` 列仅以"[脱敏列]"标记，不泄露真实数据/列值 |
| 租户隔离 | `information_schema` 查询复用既有"租户 schema 前缀 / SET search_path"机制，与执行段同源，限定当前租户 |
| 执行安全 | 增量只做"只读元数据比对"，危险词/RLS/脱敏/LIMIT/审计执行段一行不改 |
| 返回格式 | 与 human 共用 `ToolResult`（data/error/warning）作为工具**内部载体**，统一组装后 `toString()` 序列化为既有 `String` 返回（LLM 可见结构不变，🟡3），不碎片化 |

## 6. 测试策略

- **单测（`SqlSchemaValidator`）**：mock schema 元数据，验证表名不存在、列名不存在、全部存在三分支；脱敏 schema 摘要不泄露敏感信息。
- **工具集成（ReAct 自纠）**：`DatabaseQueryTool` 收到写错表名 SQL → 校验失败返回缺失清单文本 → mock ReAct 用该信息二次生成合法 SQL（验证"零工具内 LLM"路径）；二次仍错返回含明确缺失信息的错误。
- **安全回归**：原有危险词拦截、RLS、脱敏、LIMIT、审计测试保持通过；校验类名 `SqlSecurityValidator` 不被破坏。
- **合并回归（阶段 2）**：与 human `warning` 共用 `ToolResult` 时，两类字段互不干扰。
- 验证命令采用最窄范围：`company-rag-agent` 模块 `DatabaseQueryTool`/`SqlSchemaValidator` 相关测试。

## 7. 改动清单

- **修改**：`company-rag-agent/.../agent/tool/DatabaseQueryTool.java`（查询方法中插入 `SqlSchemaValidator` 调用 + 失败返回缺失清单错误文本）。
- **新增**：`company-rag-agent/.../agent/security/SqlSchemaValidator.java`（表/列存在性校验 + 脱敏 schema 摘要）；与 human 共用 `ToolResult` 封装结构。
- **不动**：工具对外签名、安全执行段、闭源 NL2SQL 引擎、数据层 schema、`SqlSecurityValidator` 既有逻辑。

## 8. 风险与观察项

- **校验性能**：每次校验多一次 `information_schema` 查询；可缓存表/列元数据（按 tenant schema + 变更失效）。
- **误报**：排除系统表 / 函数 / 子查询别名等场景，避免误拦截合法 SQL；先窄后宽。
- **ReAct 依赖性**：自纠依赖现有 ReAct 循环能读取并利用错误文本；需在 plan 阶段确认 Agent 重试策略，必要时在工具描述中明示"返回缺失信息时可据其修正重试"。
- **安全底座零膨胀**：验收铁律，任何方案不得削弱既有 RLS/脱敏/审计。
- **合并实现**：与 human `warning` 落同一文件/同一 `ToolResult`，必须阶段 2 一起改（见编排总览）。