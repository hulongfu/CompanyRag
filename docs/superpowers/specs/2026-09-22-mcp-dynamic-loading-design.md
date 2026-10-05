# MCP 服务器动态探活、失败重连与工具集自愈设计

> 日期：2026-09-22
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案 A（独立探活调度器 + 增强现有注册中心）**。为 CompanyRag 补齐 MCP 服务器的动态探活、失败重连与工具集自愈能力，规避目标项目（hermes-openclaw-agent）的 4 个已知缺点。
> HARD-GATE：本 spec 获批前不写任何实现代码。

## 1. 目标

让 CompanyRag 对外部 MCP Server 具备以下能力：

1. **启动失败可恢复**：启动时某 MCP Server 连接失败，不再仅打日志，而是登记进失败清单，由定时任务持续重连。
2. **失联可移除**：定时探活已连接服务器，失联自动移除该源全部已注册工具。
3. **调用失败即时自愈**：调用工具失败时，区分参数错误（忽略）与非参数错误（去远端同步/比对）。
4. **可观测**：日志 + 审计 + 状态查询 API + 管理员状态页面。

**约束：**
- **不改** `ReactAgent` 黑盒编排。
- **不破坏** 现有 `AggregatedToolCallbackProvider` 的审批门与动态 `getToolCallbacks()` 实时生成机制。
- **不引入多实例一致性**（本需求面向单实例，失败/重连状态保持内存态）。
- 沿用目标项目优点（`fixedDelay` 防并发重连闭环），规避其缺点（探活开销、跨来源误删、0 工具算失败、内存不持久）。

## 2. 现状回顾

- 工具执行链路：`ReactAgent` → Spring AI `ToolCallbackProvider`(= `AggregatedToolCallbackProvider`) → `createToolCallback(...).call(String)` → 解析 JSON→`Map` → **直接 `agentTool.execute(params)`**，失败被 catch 后返回 `"工具调用失败：..."` 字符串（软失败，不抛出）。
- `AggregatedToolCallbackProvider.getToolCallbacks()` 每次调用**实时**从 `AgentToolRegistry.listTools()` 生成 `ToolCallback[]` —— 这是动态增删能生效的枢纽，也是本设计能"移除/新增后下轮调用自动生效"的前提。
- MCP 注册链路：`McpClientAutoConfig` @PostConstruct 遍历配置 → `McpClientRegistry.registerClient(clientId, client)` → `connect()` + `listTools()` 缓存到 `toolCache` + `registerToolsToAgent`（把每个工具 new `ExternalMcpTool(clientId, tool, this)` 注册进 `AgentToolRegistry`）。连接失败仅 log，无重连。
- `McpClient` (`HttpMcpClient`)：`connect/disconnect/isConnected/listTools/callTool`；`callTool`/`listTools` 失败统一封装 `RuntimeException`，**丢失 JSON-RPC error code**。
- `ExternalMcpTool`：工具名 = `clientId_toolName`，天然避免跨源同名覆盖（优于目标项目别名方案）。
- `AgentToolRegistry`：普通 `HashMap<String, AgentTool>` + volatile `version`，仅 `register/getTool/listTools/executeTool/hasTool/getVersion/getAllTools`，**无 remove**。
- 旧版已用 version 机制解决 ChatClient 动态同步（`2026-08-24-mcp-tool-dynamic-version-fix.md`），现 `AggregatedToolCallbackProvider.getToolCallbacks()` 实时读取，不再需要重建 ChatClient。

## 3. 架构设计

**核心思路：** 新增一个 `@Scheduled(fixedDelay)` 调度器（`McpHealthScheduler`）专职探活与重连；新增 `McpFailureHandler` 处理调用失败即时自愈；二者统一复用 `McpClientRegistry` 的归属追踪与按源移除/同步能力。`AgentToolRegistry` 补 `remove`/`removeAll` 作为移除落点。`getToolCallbacks()` 的实时性确保移除/同步后下轮调用自动生效。

### 3.1 模块归属与依赖

| 模块 | 所属改动 |
|---|---|
| `company-rag-agent` | `AgentToolRegistry` 补 remove 能力（改并发容器 + 新增方法） |
| `company-rag-mcp-client` | `McpClient`/`HttpMcpClient`（探活 + 错误码暴露）、`McpClientRegistry`（归属追踪 + 按源移除/同步）、`McpHealthScheduler`、`McpFailureHandler`、`McpProperties` 扩展 |
| `company-rag-rag` | `AggregatedToolCallbackProvider` 失败分支挂 `McpFailureHandler` |
| `company-rag-web` | `McpStatusController` + 路由 + 模板页 + 图标入口 |

依赖方向：`company-rag-rag` → `company-rag-mcp-client`(handler) + `company-rag-agent`(registry)，均为既有/同向依赖，无循环。

### 3.2 组件设计

**① `AgentToolRegistry`（agent 模块）**
- `tools` 由 `HashMap` 改为 `ConcurrentHashMap`（运行期并发 register/remove 安全）。
- 新增 `boolean remove(String name)`：仅当 `hasTool(name)` 为真时移除并 `version++`，返回是否移除成功（幂等，避免误移除/重复计数）。
- 新增 `int removeAll(Collection<String> names)`：批量移除，仅统计"实际存在的被移除数"。

**② `McpClient` 接口 / `HttpMcpClient`（mcp-client 模块）**
- `ping()`：**复用现有连接**发起一次 `tools/list` 请求，成功（无 JSON-RPC error 且 HTTP 2xx）即视为可达；失败返回 false。**不重建 HttpClient**（规避目标项目探活开销缺点）。
- 新增 `List<McpToolDefinition> listToolsRemote()`：直连远端获取**最新**工具列表（绕过本地 `toolCache`，用于同步）。
- `HttpMcpClient` 扩展错误模型：在封装异常中**保留 JSON-RPC error code**（如 `-32602`），供 `McpFailureHandler` 判定参数错误。

**③ `McpClientRegistry`（mcp-client 模块）**
- 新增 `Map<String, Set<String>> agentToolNamesByClient`：维护 `clientId → 已注册进 Agent 的工具名集合`（`registerToolsToAgent` 时加入，全量同步/按源移除时更新）。
- 新增 `void syncTools(String clientId)`：调用该 client `listToolsRemote()` → 计算本源**应存在**的工具名集合 → 从 `AgentToolRegistry` 移除已不再存在的名字（用 `agentToolNamesByClient` 比对）+ 注册新增的 → 更新 `toolCache` 与 `agentToolNamesByClient`。
- 新增 `void removeToolsFor(String clientId)`：取 `agentToolNamesByClient.get(clientId)` → `agentToolRegistry.removeAll(...)` → 清空该 client 的归属集与 `toolCache` → `client.disconnect()`。
- 新增失败清单登记：`registerClient` 连接失败时记录失败 clientId（供调度器重连）。

**④ `McpHealthScheduler`（mcp-client 模块）**
- `@Scheduled(fixedDelayString = "${mcp.health-check-interval-ms:600000}")`：探活已连接 client。`fixedDelay` 保证单轮不重叠（防重连风暴）。
  - 对每个已连接 client 调 `ping()`；失败 → `removeToolsFor` + 登记失败清单 + 审计。
  - `fixedDelay` 缺点已确认：探活间隔调大（默认 10 分钟），实时性由调用失败钩子承担。
- `@Scheduled(fixedDelayString = "${mcp.reconnect-interval-ms:180000}")`：重连失败清单中 client。
  - 恢复配置（`McpClientProperties`）→ `connect()` → 成功则 `listTools()` + 重新注册回 Agent + 移出失败清单 + 审计；仍失败留在清单。

**⑤ `McpFailureHandler`（mcp-client 模块）**
- 挂载于 `AggregatedToolCallbackProvider.call()` 失败 catch 分支。
- 核心逻辑（对应用户最终确认的规则）：

```
工具调用失败
  ├─ 参数错误(JSON-RPC -32602 / 关键词兜底)？ → 不处理，返回失败信息
  └─ 非参数错误？
       ├─ 工具名命中某 MCP 源(clientId_ 前缀)？ → 否：不处理(本地工具)
       └─ 是 → 该 client 可达性
            ├─ 不可达 → removeToolsFor(clientId)  # 移除该源全部工具
            └─ 可达   → syncTools(clientId)       # 用远端最新列表替换该源工具集
```

- **参数错误判定**：先严格按 JSON-RPC error code `-32602 (invalid params)`；识别不了时再按错误信息关键词（schema/参数/parameter/arguments 等）兜底。此为已在会话中确认的策略。
- 处理全程 try-catch 包裹，绝不因自愈逻辑影响原 `call()` 返回。

**⑥ 配置（`McpProperties`）**
- 新增 `healthCheckIntervalMs`（默认 600000，10 分钟）与 `reconnectIntervalMs`（默认 180000，3 分钟）。

**⑦ 可观测性**
- **状态模型** `McpEndpointStatus`：`clientId/url/enabled/connected/toolCount/failedSince/lastProbeTime/lastProbeResult/registeredToolNames`。
- **审计**：复用 `AuditLogService`，动作类型 `MCP_RECONNECT` / `MCP_REMOVE_SOURCE` / `MCP_SYNC_TOOLS`，异步落库，失败不影响主流程。
- **API**：`GET /api/mcp/status`（admin 权限）返回各 MCP 服务器状态。
- **页面**：`GET /mcp-status`（新增路由 + `mcp-status.html` 模板）；`index.html` header 加 `v-if="role === 'admin'"` 的「🌐 MCP」图标按钮进入。

## 4. 流程

### 4.1 定时探活（每 10 分钟，兜底）

```
McpHealthScheduler.probeConnectedClients():
  for each connected client:
    if !client.ping():
      registry.removeToolsFor(clientId)   # 移除该源全部工具
      登记失败清单
      audit(MCP_REMOVE_SOURCE)

McpHealthScheduler.reconnectFailedClients():  # 每 3 分钟
  for each failed client:
    恢复配置 → connect()
    ├─ 成功 → listTools() → 重新注册 → 移出清单 → audit(MCP_RECONNECT)
    └─ 失败 → 留在清单
```

### 4.2 调用失败即时自愈（实时）

```
ToolCallback.call() 失败 catch 分支:
  McpFailureHandler.handle(toolName, exception)
    ├─ 判定参数错误? → return (不处理)
    ├─ 非 MCP 源? → return (本地工具)
    └─ MCP 源 → 该 client 可达性
         ├─ 不可达 → removeToolsFor(clientId) + audit(MCP_REMOVE_SOURCE)
         └─ 可达   → syncTools(clientId) + audit(MCP_SYNC_TOOLS)
```

## 5. 与其他机制的协同

- **`getToolCallbacks()` 实时性**：`syncTools`/`removeToolsFor` 移除后，**下一次 `getToolCallbacks()` 调用的 Agent 轮询**即看不到已移除工具。这是本设计的动态生效保证。
- **风险项（实现时验证）**：需核实 ReactAgent 每次执行是否都重新调用 `getToolCallbacks()` 读取最新工具。当前架构为利好（实时生成），但若发现 Agent 初始化时固化工具快照，需在工具变更时触发重建（方案见 §6 风险缓解）。

## 6. 规避目标项目缺点

| 目标项目缺点 | 本项目规避方式 |
|---|---|
| 探活开销大（每轮重建 HttpClient + 完整握手） | `ping()` 复用现有连接发一次 `tools/list` |
| 跨来源误删（别名为同名工具） | `ExternalMcpTool` 用 `clientId_toolName` 命名，按 `clientId` 归属精确移除 |
| 0 工具算失败 | 本设计 `ping()` 只看连通性，不以工具数判失败 |
| 内存态不持久、多实例不一致 | 明确单实例范围，不持久化；重启重新尝试连接全部配置 client |

## 7. 风险与缓解

1. **【待验证】ReactAgent 动态传播**：若 Agent 固化工具快照则需版本重建。缓解：设计预留 `version` 触发重建钩子。
2. **`syncTools` 并发**：`ConcurrentHashMap` + 幂等 `remove` 保证并发安全；`syncTools`/`removeToolsFor` 内部对同一 client 的归属集操作用 `compute` 原子化。
3. **`syncTools` 与失败清单冲突**：调用失败探测到不可达已 `removeToolsFor` 并登记失败清单，调度器重连成功后走"重新注册"而非 `syncTools`，避免双轨。
4. **审计风暴**：探活失败重连频繁时审计可能较多。设计上审计按动作聚合（一次 REQUEST 一条），必要时可加降噪阈值。
5. **admin 页面权限**：`/api/mcp/status` 与页面入口均以 `role === 'admin'` 守卫，遵循既有 `SecurityConfig` 规则。

## 8. 验收标准

1. 断开某 MCP Server 后，定时探活（10 分钟内，或下一次调用失败即时）触发，该源全部工具从 Agent 移除。
2. 恢复服务器后，重连任务（3 分钟内）自动重新连接并注册工具。
3. 工具调用因参数错误失败时，工具不被移除、源不被处理。
4. 工具调用因远端不可达失败时，该源全部工具被移除。
5. 工具调用因远端工具下架（可达）失败时，该源工具集被同步为远端最新列表。
6. `GET /api/mcp/status` 仅 admin 可访问，返回各源 connected/toolCount/registeredToolNames。
7. 审计表出现 `MCP_RECONNECT` / `MCP_REMOVE_SOURCE` / `MCP_SYNC_TOOLS` 记录。
8. mcp-status 页面展示各 MCP 服务器状态，仅 admin 首页有入口。