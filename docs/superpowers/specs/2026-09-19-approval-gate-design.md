# 审批门（Approval Gate）强制阻塞审批设计

> 日期：2026-09-19
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案 A（同步等待审批）**——对命中审批判定工具调用采用**强制挂起等待人工审批**，审批通过后执行并把结果经该次 `ToolCallback.call()` 返回值回填给黑盒 `ReactAgent`。
> 演进关系：本 spec 是 `2026-09-14-human-in-the-loop-design.md` 中"暂缓的方案①（强制阻塞审批）"的落地。该既有 spec 采用方案②（提示式、非阻塞）；本 spec 在其基础上为其补上**强制拦截**能力，二者保留并存（提示式负责"告知"，审批门负责"强制确认"）。
> HARD-GATE：本 spec 获批前不写任何实现代码。

## 1. 目标

在同步 chat 主模型不重构的前提下，对**工具执行路径**插入一道"人肉确认"闸门：命中审批判定的工具调用，在真正执行前**挂起等待人工 approve/deny**；审批通过才执行，deny 或超时则拒绝并给出文案。用于对**有外部副作用 / 业务敏感**的工具调用提供强制人工确认。

**约束：**
- **不改** `ReactAgent` 黑盒编排（不引入 graphs human-interrupt / 断点恢复）。
- **不破坏** `ChatController` 同步返回契约与 `CompletableFuture.get(5min)` + AbortPolicy 主模型（审批等待内嵌于该次工具调用的同步等待，含在 5 分钟总超时内）。
- **不削弱**任何既有硬性安全校验（见 §6）：审批门是叠加层，不 bypass `SqlSecurityValidator`、命令白名单、脱敏、租户隔离。
- 判定策略**不**采用 per-tool 配置项（不可扩展），改为**工具自我声明**（见 §4）。

## 2. 现状回顾

- 工具执行链路：`ReactAgent` → Spring AI `ToolCallbackProvider`(= `AggregatedToolCallbackProvider`) → `createToolCallback(...).call(String)` → 解析 JSON→`Map` → **直接 `agentTool.execute(params)`**。**不经过** `AgentToolRegistry.executeTool`。
- 故**强制拦截/回填点唯一且必需放在 `AggregatedToolCallbackProvider.call()` 内**、`execute()` 之前。
- `AgentTool` 接口：`getName/getDescription/getParameterSchema/execute(Map)`。所有工具在此，改造可向后兼容。
- `DatabaseQueryTool`：只读（`SqlSecurityValidator.validateSelectSql` + 危险关键字 + `startsWith(SELECT)` 三重校验），有脱敏 + 租户隔离 + 行数限制。
- `ExecuteTool`：命令白名单（python 技能脚本 + 只读诊断）+ 无 shell + canonical 路径/信任根守卫 + 进程环境最小化。
- 同步超时：`RagAgentService.callAgentWithTimeout`，`future.get(5min)`。
- 租户建表惯例：`TenantServiceImpl.buildCreateTableSql`（tenant.xxx schema 下建 `answer_eval_result` 等 + RLS + grant）；既有租户走 `SchemaMigrationConfig` 启动迁移补齐。
- 已有 `2026-09-14-human-in-the-loop-design.md` 方案②（提示式、非阻塞）。

## 3. 架构设计

**核心思路：** 在工具执行边界（`ToolCallback.call()`）插一道同步等待的审批闸门。命中判定的调用 → 落库 `PENDING` → 在 `call()` 内轮询数据库等待人工结果 → approve 执行并回填结果 / deny 拒绝 / 超时自动 DENIED。审批结果即 `call()` 返回值，黑盒 `ReactAgent` 天然拿到，无需改编排。

### 3.1 流程（方案 A）

```
ToolCallback.call(input):
  ① 解析 input → Map params
  ② approvalService.gate(toolName, params, ctx)
       ├─ 判定不需审批 → 直接调用 agentTool.execute(params) 并返回
       └─ 判定需审批:
            ├─ 落库 PENDING(toolName, argsJson, sessionId, requesterUserId)
            ├─ await(req, waitTimeout=agent.approval.timeout-seconds)
            │     ├─ 被 approve → 执行 agentTool.execute(params)，
            │     │               结果回填 result + 置 EXECUTED，返回结果给 LLM
            │     ├─ 被 deny   → 置 DENIED，返回"调用被审批拒绝"文案给 LLM
            │     └─ 超时      → 置 DENIED(自动收敛)，返回"审批超时已拒绝"文案
```

- **回填零侵入**：审批通过后结果就是 `call()` 返回值，黑盒直接拿到。
- **超时收敛**：等待达上限自动转 DENIED，不留孤儿 PENDING（与用户已确认策略一致）。
- **并发协作**：approve/deny（web 线程）与同步等待者（agent 线程）通过**数据库状态轮询**协作，无显式锁；状态机做幂等校验防重复决策。

### 3.2 模块归属与依赖

- **审批逻辑**放 `company-rag-agent`（与 `AgentTool`、`TenantContext` 同层）：`ToolApprovalService`、`ToolApprovalRequest` 实体 + Mapper、`ApprovalProperties`。
- `AggregatedToolCallbackProvider`（`company-rag-rag`）已构造注入 `AgentToolRegistry`（agent 模块），无循环依赖；新增构造注入 `ToolApprovalService`。

## 4. 判定策略（工具自我声明）

**不用 per-tool 配置项**（不可扩展）。改为每个工具类自我声明是否需审批：

```
public interface AgentTool {
    ...现有 getName/getDescription/getParameterSchema/execute(Map) ...

    /**
     * 该工具调用是否需要经过人类审批门。
     * 默认 false；高风险 / 有外部副作用的工具重写返回 true。
     */
    default boolean requiresApproval() { return false; }
}
```

- **向后兼容**：默认方法，现有 5 个工具不改签名不破坏。
- **落地（统一原则）**：**所有工具一律看 `requiresApproval()` 自声明，不内置任何 toolName 特判。**`ExecuteTool.requiresApproval()` → `true`（动作类、外部副作用）；其余工具保持默认 `false`（如 `DatabaseQueryTool` 只读 + 脱敏 + 租户隔离已够）。未来某工具是否需要审批，由该工具类自身声明决定，不在审批服务里写死。
- **新增工具体验**：未来加工具只要让 `requiresApproval()` 返回 `true` 即自动纳入审批，**不需要改审批服务/配置/策略**。
- **唯一全局配置**（均非 per-tool）：
  - `agent.approval.enabled`（总开关，默认 `false` 关闭）
  - `agent.approval.timeout-seconds`（同步等待上限，默认 `300`）
- **参数级策略（扩展点，hermes `ToolArgsApprovalPolicy` 思路）**：保留函数式接口 `(toolName, argsJson) -> boolean`，供"同工具因参数不同审批要求不同"，由相应工具自身注册。本期不强制全工具实现。CompanyRag 参数为 `Map`，判定前先 `ObjectMapper` 序列化为 JSON。

**判定优先级**：`AgentTool.requiresApproval()` == true 或命中该工具注册的参数级策略 → 走审批门；否则直接执行。

## 5. 数据模型 `tool_approval_request`

每租户 schema 下建表 + RLS + 索引（仿 `answer_eval_result` 建表惯例）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| tenant_id | BIGINT NOT NULL | 租户隔离（RLS 依据） |
| tool_name | VARCHAR(64) NOT NULL | 工具名，如 execute |
| args_json | TEXT | 参数快照（供审批人复核） |
| session_id | VARCHAR(128) | 关联会话 |
| requester_user_id | BIGINT | 发起调用的用户 |
| status | VARCHAR(16) | PENDING / EXECUTED / DENIED |
| result | TEXT | 审批通过后执行结果回填 |
| requested_at | TIMESTAMP | 发起时间 |
| decided_at | TIMESTAMP | 决策时间 |

- **RLS**：`tenant_id = current_tenant_id()` + `FORCE ROW LEVEL SECURITY`，并 `GRANT USAGE, SELECT ON SEQUENCE ..._id_seq`。
- **同步点**：新增表须在 `TenantServiceImpl.buildCreateTableSql`（新租户）+ `SchemaMigrationConfig`（存量租户）两处补齐，防"漏建导致 insert 静默失败、审批零落库"。
- **索引**：`(tenant_id, status)` 查询待审批；`(tenant_id, requested_at DESC)` 列表。

## 6. 安全说明

- **审批门是叠加人肉确认层，不削弱硬校验**：`DatabaseQueryTool` 仍只 SELECT、`ExecuteTool` 仍走命令白名单，Approval 不 bypass 它们。审批判断的是"调用是否业务恰当/必要"，非"是否危险"。
- **参数快照落库风险**：`args_json` 含命令 / SQL 原文，属功能需要（供审批人复核），但存在**敏感信息落库风险**。当前白名单边界下 execute 命令无密钥，可接受；预留未来脱敏扩展点。
- **多租户隔离**：审批记录按租户 schema + RLS 隔离，**审批人只能看到本租户 pending**。
- **并发安全**：state 机 PENDING→EXECUTED/DENIED 幂等校验，防重复决策/二次执行。
- **鉴权**：approve/deny 接口走现有 Spring Security；建议本期复用现有权限模型（审批动作沿用当前登录用户身份）。

## 7. REST API + 简版 HTML 审批面板

- `GET  /api/tool-approval/pending`  待审批列表（租户参数快照）
- `POST /api/tool-approval/{id}/approve`  批准（唤醒同步等待者）
- `POST /api/tool-approval/{id}/deny`  拒绝
- 简版 HTML 审查面板（web 模块静态页）：轮询 pending → 复核参数 → approve/deny。

## 8. 边界与取舍

- **同步等待占用工作线程**：一个 Agent 调用在审批等待期间占用一个工作线程（最长 `agent.approval.timeout-seconds`，默认 300，内含于 5 分钟总超时）。多并发审批会占用线程池——需留意 `AgentThreadPoolProperties` 容量。
- **与方案②提示式并存**：提示式（告知风险）与审批门（强制确认）保留两套；未来可让"命中提示式高风险"的工具再配置是否需要强制审批。
- **本期不做**：异步审批会话模型 / 图形断点恢复 / 审批人指定角色多级审批（预留）。

## 9. 测试策略

- **判定单测**：`requiresApproval()` 命中、该工具参数级策略命中、默认放行三类。
- **状态机单测**：PENDING→EXECUTED、PENDING→DENIED、重复 approve/deny 幂等、超时自动 DENIED。
- **同步等待单测**：mock 工具 + 数据库状态流转，验证 approve 后执行并回填结果、deny 返回拒绝文案、超时返回超时文案。
- **租户隔离**：审批表 RLS 生效、跨租户不可见。
- **回归**：`AgentTool` 现有 5 工具有 `requiresApproval()` 默认 `false` 时结构/行为不变。
- 验证命令采用最窄范围：审批门相关单测类。

## 10. 改动清单（待 Phase 批准后细化）
- **新增（agent）**：`ToolApprovalService`、`ToolApprovalRequest` 实体 + Mapper、`ApprovalProperties`、状态机常量。
- **修改（agent）**：`AgentTool` 接口加默认 `requiresApproval()`；`ExecuteTool` 重写返回 `true`。
- **修改（rag）**：`AggregatedToolCallbackProvider` 构造注入 `ToolApprovalService`，在 `call()` 中 `execute()` 前插入 `gate(...)` + `await(...)` 逻辑。
- **新增（web）**：`ToolApprovalController` + 简版 HTML 审批面板。
- **修改（sql）**：建表（新租户 `TenantServiceImpl.buildCreateTableSql` / 存量 `SchemaMigrationConfig`）+ RLS + 索引。
- **修改（配置）**：`application*.yml` 加 `agent.approval.*`。