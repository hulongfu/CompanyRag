# MCP 服务器动态探活、失败重连与工具集自愈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 CompanyRag 补齐 MCP Server 的启动失败重连、失联按源移除工具、调用失败即时自愈（参数错误忽略 / 非参数错误远端同步），并暴露状态查询 API 与 admin 管理页面。

**Architecture:** 独立 `McpHealthScheduler`（fixedDelay 防并发）负责周期探活与失败重连；`McpFailureHandler` 在 MCP 调用边界即时自愈；二者统一复用 `McpClientRegistry` 的「按 clientId 归属追踪」与 `syncTools`/`removeToolsFor` 能力；`AgentToolRegistry` 补 `remove`/`removeAll` 作为移除落点；`getToolCallbacks()` 的实时生成保证增删下轮自动生效。

**Tech Stack:** Java 17 / Spring Boot 3.4 (Spring AI 1.1.3, Alibaba ReactAgent) / MyBatis-Plus / JUnit 5 / Mockito。

**与设计文档的一处偏离（经实现核实的正确化）：**
设计 §3.2 ⑤ 原定在 `AggregatedToolCallbackProvider.call()` 失败分支挂钩子。但核对代码后发现 `ExternalMcpTool.execute()`（`company-rag-mcp-client/.../ExternalMcpTool.java:81-84`）把 `clientRegistry.callTool(...)` 的异常 **catch 并转换为返回字符串**，不会抛出到 `call()` 的 catch 分支。因此自愈钩子实际放在 **`McpClientRegistry.callTool()`**（唯一同时持有 `clientId` 与原始异常 `McpToolException` 的边界），通过**字段注入** `@Autowired McpFailureHandler` 打破与 handler 的构造循环依赖。行为与设计意图完全一致。

---

## 文件结构总览

**company-rag-agent（agent 模块）**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java`（并发容器 + remove/removeAll）

**company-rag-mcp-client（mcp-client 模块，包 `com.company.rag.mcp.client`）**
- Create: `McpToolException.java`（携带 JSON-RPC 错误码）
- Modify: `McpClient.java`（+ping/+listToolsRemote 接口）与 `HttpMcpClient.java`（实现 + 错误码模型）
- Modify: `McpClientRegistry.java`（归属追踪 / failedClients / syncTools / removeToolsFor / status）
- Create: `McpEndpointStatus.java`（可观测性状态模型）
- Create: `McpFailureHandler.java`（自愈编排）
- Create: `McpHealthScheduler.java`（定时探活 + 重连）
- Modify: `McpClientProperties.java`（+healthCheckIntervalMs/+reconnectIntervalMs）

**company-rag-bootstrap**
- Modify: `CompanyRagApplication.java`（+@EnableScheduling）

**company-rag-web（web 模块）**
- Modify: `PageController.java`（+/mcp-status 路由）
- Create: `McpStatusController.java`（GET /api/mcp/status，admin）
- Create: `src/main/resources/templates/mcp-status.html`
- Modify: `src/main/resources/templates/index.html`（admin 「🌐 MCP」图标入口）

**Test files（与主类同模块同包）**
- `company-rag-agent/.../tool/AgentToolRegistryTest.java`
- `company-rag-mcp-client/.../client/McpClientRegistrySelfHealTest.java`
- `company-rag-mcp-client/.../client/McpFailureHandlerTest.java`
- `company-rag-web/.../controller/McpStatusControllerTest.java`

---

## Task 1: `AgentToolRegistry` 支持运行期移除

- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java`
- Test: `company-rag-agent/src/test/java/com/company/rag/agent/tool/AgentToolRegistryTest.java`

**背景：** 当前 `tools` 是 `HashMap`（非并发），只有 register 无 remove。动态移除工具是自愈机制的落点。

- [ ] **Step 1: 写失败测试**

```java
package com.company.rag.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {

    private static AgentTool tool(String name) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return "desc"; }
            public Map<String, Object> getParameterSchema() { return Map.of(); }
            public String execute(Map<String, Object> params) { return "ok"; }
        };
    }

    @Test
    void remove_existing_tool_returns_true_and_bumps_version() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        registry.register(tool("a"));
        int before = registry.getVersion();
        assertTrue(registry.remove("a"));
        assertFalse(registry.hasTool("a"));
        assertTrue(registry.getVersion() > before);
    }

    @Test
    void remove_missing_tool_returns_false_and_keeps_version() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        int before = registry.getVersion();
        assertFalse(registry.remove("nope"));
        assertEquals(before, registry.getVersion());
    }

    @Test
    void removeAll_removes_only_present_names_and_returns_count() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("a"), tool("b"), tool("c")));
        int removed = registry.removeAll(List.of("a", "missing", "c"));
        assertEquals(2, removed);
        assertTrue(registry.hasTool("b"));
        assertFalse(registry.hasTool("a"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-agent -am test -Dtest=AgentToolRegistryTest`
Expected: FAIL（编译错误——`remove`/`removeAll` 不存在）。

- [ ] **Step 3: 实现**

把 `tools` 改为 `ConcurrentHashMap`，并新增两个方法（关键业务逻辑加中文注释）：

```java
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
```

将 `private final Map<String, AgentTool> tools = new HashMap<>();` 改：
```java
    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
```

新增方法（放在 `getVersion()` 之前）：
```java
    /**
     * 移除单个工具。仅当工具真实存在时移除并递增版本号，返回是否移除成功（幂等，避免重复计数）。
     */
    public boolean remove(String name) {
        AgentTool removed = tools.remove(name);
        if (removed != null) {
            version++; // 工具移除也属于变更，递增版本号供下游重建感知
            log.debug("移除 Agent 工具：{} (version={})", name, version);
            return true;
        }
        return false;
    }

    /**
     * 批量移除工具，仅统计"实际存在的被移除数"。常用于按 MCP 来源(clientId)移除整机工具集。
     */
    public int removeAll(Collection<String> names) {
        int removed = 0;
        if (names != null) {
            for (String name : names) {
                if (remove(name)) {
                    removed++;
                }
            }
        }
        return removed;
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-agent -am test -Dtest=AgentToolRegistryTest`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java company-rag-agent/src/test/java/com/company/rag/agent/tool/AgentToolRegistryTest.java
git commit -m "feat(agent): AgentToolRegistry 支持运行期 remove/removeAll"
```

---

## Task 2: 新增 `McpToolException` 携带 JSON-RPC 错误码

- Create: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpToolException.java`

**背景：** 现 `HttpMcpClient.callTool`/`listTools` 把所有失败统一 `RuntimeException`，丢失 JSON-RPC error code（-32602 也被吞）。为让 `McpFailureHandler` 能区分「参数错误 vs 服务器错误」，引入此异常。

- [ ] **Step 1: 创建类**

```java
package com.company.rag.mcp.client;

import lombok.Getter;

/**
 * MCP 调用异常，保留远端返回的 JSON-RPC 错误码。
 * 用于区分"参数错误(-32602)"与"服务器/网络错误"，供自愈逻辑决策。
 */
@Getter
public class McpToolException extends RuntimeException {

    /** JSON-RPC 标准错误码：无效参数 */
    public static final int CODE_INVALID_PARAMS = -32602;

    private final int errorCode;

    public McpToolException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpToolException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 是否为参数错误（严格协议判定 + 消息关键词兜底） */
    public boolean isParamError() {
        if (errorCode == CODE_INVALID_PARAMS) {
            return true;
        }
        if (getMessage() == null) {
            return false;
        }
        String msg = getMessage().toLowerCase();
        return msg.contains("argument") || msg.contains("parameter")
                || msg.contains("schema") || msg.contains("invalid params");
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpToolException.java
git commit -m "feat(mcp-client): 新增 McpToolException 携带 JSON-RPC 错误码"
```

---

## Task 3: `McpClient` 接口与 `HttpMcpClient` 实现探活与远端列表

- Modify: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClient.java`
- Modify: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/HttpMcpClient.java`

**改动：** 接口增加 `ping()` 与 `listToolsRemote()`；`HttpMcpClient` 实现二者，并把 JSON-RPC 错误改为抛 `McpToolException`。

- [ ] **Step 1: 扩展 `McpClient` 接口**

在 `callTool` 之后追加两个方法：

```java
    /**
     * 探活：复用现有连接发起一次 tools/list，成功（无 JSON-RPC error）即视为可达，失败返回 false。
     * 不重建 HttpClient，规避重连风暴。
     */
    boolean ping();

    /**
     * 直连远端获取最新工具列表（绕过本地工具缓存），用于按源全量同步。
     */
    List<McpToolDefinition> listToolsRemote();
```

- [ ] **Step 2: 修改 `HttpMcpClient` 错误模型**

在 `listTools()`（:92）把 JSON-RPC 错误改为抛 `McpToolException`：
```java
            if (response.getError() != null) {
                throw new McpToolException(response.getError().getCode(),
                        "获取工具列表失败：" + response.getError().getMessage());
            }
```
在 `callTool()`（:119）：
```java
            if (response.getError() != null) {
                throw new McpToolException(response.getError().getCode(),
                        "调用工具 " + toolName + " 失败：" + response.getError().getMessage());
            }
```

- [ ] **Step 3: 实现 `ping()` 与 `listToolsRemote()`**

在 `callTool` 之后追加：

```java
    @Override
    public boolean ping() {
        try {
            // 复用现有连接探活：能成功拉取工具列表即视为可达
            listTools();
            return true;
        } catch (Exception e) {
            log.warn("MCP Client [{}] 探活失败：{}", clientId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<McpToolDefinition> listToolsRemote() {
        // HttpMcpClient 无本地工具缓存，listTools() 每次均直连远端，故直接复用
        return listTools();
    }
```

- [ ] **Step 4: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClient.java company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/HttpMcpClient.java
git commit -m "feat(mcp-client): McpClient 增加 ping/listToolsRemote 并暴露 JSON-RPC 错误码"
```

---

## Task 4: `McpEndpointStatus` 状态模型

- Create: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpEndpointStatus.java`

- [ ] **Step 1: 创建类**

```java
package com.company.rag.mcp.client;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 端点运行状态（内存态，用于管理员状态查询）。
 */
@Data
@Builder
public class McpEndpointStatus {

    private String clientId;
    private String url;
    private boolean enabled;
    private boolean connected;
    private int toolCount;
    /** 记录进入失败态的时间；null 表示当前不在失败态 */
    private LocalDateTime failedSince;
    private LocalDateTime lastProbeTime;
    /** 最近一次探活/操作的简要结果描述 */
    private String lastProbeResult;
    /** 该源已注册进 Agent 的工具名 */
    private List<String> registeredToolNames;
}
```

- [ ] **Step 2: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpEndpointStatus.java
git commit -m "feat(mcp-client): 新增 McpEndpointStatus 状态模型"
```

---

## Task 5: `McpClientRegistry` 归属追踪、失败清单、按源移除与同步

- Modify: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientRegistry.java`
- Test: `company-rag-mcp-client/src/test/java/com/company/rag/mcp/client/McpClientRegistrySelfHealTest.java`

**设计要点：** 新增 `agentToolNamesByClient`（clientId → 已注册工具名集合）、`failedClients`（失败清单）、`syncTools`/`removeToolsFor`、`statusSnapshots()`。

- [ ] **Step 1: 写失败测试**

```java
package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpClientRegistrySelfHealTest {

    /** 可控的假客户端：可设置远端工具列表与可达性 */
    static class FakeMcpClient implements McpClient {
        private final String id;
        private boolean reachable = true;
        private List<McpToolDefinition> remoteTools;

        FakeMcpClient(String id, List<McpToolDefinition> remoteTools) {
            this.id = id;
            this.remoteTools = remoteTools;
        }
        public String getClientId() { return id; }
        public void connect() {}
        public void disconnect() {}
        public boolean isConnected() { return true; }
        public List<McpToolDefinition> listTools() { return remoteTools; }
        public Object callTool(String n, Map<String,Object> p) { return null; }
        public boolean ping() { return reachable; }
        public List<McpToolDefinition> listToolsRemote() { return remoteTools; }
        void setReachable(boolean r) { this.reachable = r; }
        void setRemoteTools(List<McpToolDefinition> t) { this.remoteTools = t; }
    }

    private static AgentToolRegistry newRegistry() {
        return new AgentToolRegistry(List.of());
    }

    private static McpClientRegistry.McpClientRegistryBuilder registryBuilder(AgentToolRegistry ar) {
        return McpClientRegistry.builder(ar);
    }

    @Test
    void syncTools_replaces_whole_source_tool_set() {
        AgentToolRegistry ar = newRegistry();
        McpClientRegistry.McpClientRegistryBuilder builder = McpClientRegistry.builder(ar);
        FakeMcpClient client = new FakeMcpClient("a", List.of(
                new McpToolDefinition("foo", "d", Map.of(), null),
                new McpToolDefinition("bar", "d", Map.of(), null)));
        McpClientRegistry registry = builder.build();
        registry.registerClient("a", client);

        // 模拟远端下架 foo、新增 baz
        client.setRemoteTools(List.of(
                new McpToolDefinition("bar", "d", Map.of(), null),
                new McpToolDefinition("baz", "d", Map.of(), null)));
        registry.syncTools("a");

        assertTrue(ar.hasTool("a_bar"));
        assertTrue(ar.hasTool("a_baz"));
        assertFalse(ar.hasTool("a_foo"), "已下架工具应被移除");
    }

    @Test
    void removeToolsFor_removes_all_tools_of_source() {
        AgentToolRegistry ar = newRegistry();
        McpClientRegistry registry = McpClientRegistry.builder(ar).build();
        FakeMcpClient client = new FakeMcpClient("a", List.of(
                new McpToolDefinition("x", "d", Map.of(), null)));
        registry.registerClient("a", client);

        registry.removeToolsFor("a");

        assertFalse(ar.hasTool("a_x"));
        assertNull(registry.getClient("a"), "按源移除后应释放 client 引用");
    }
}
```

> 说明：设计上提供 `McpClientRegistry.builder(AgentToolRegistry)` 静态工厂以简化测试构造（等价于 `new McpClientRegistry(ar)`）。

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am test -Dtest=McpClientRegistrySelfHealTest`
Expected: FAIL（编译错误——`builder`/`syncTools`/`removeToolsFor` 不存在）。

- [ ] **Step 3: 实现**

新增字段与构造（用静态 builder 工厂保持既有 `new McpClientRegistry(AgentToolRegistry)` 兼容）：

```java
    private final Map<String, Set<String>> agentToolNamesByClient = new ConcurrentHashMap<>();
    private final Set<String> failedClients = ConcurrentHashMap.newKeySet();

    // 保留原构造函数，供既有调用/测试使用
    public McpClientRegistry(AgentToolRegistry agentToolRegistry) {
        this.agentToolRegistry = agentToolRegistry;
    }

    // 静态 builder 工厂，等价于 new，便于测试
    public static McpClientRegistryBuilder builder(AgentToolRegistry agentToolRegistry) {
        return new McpClientRegistryBuilder(agentToolRegistry);
    }

    public static class McpClientRegistryBuilder {
        private final AgentToolRegistry agentToolRegistry;
        McpClientRegistryBuilder(AgentToolRegistry ar) { this.agentToolRegistry = ar; }
        public McpClientRegistry build() { return new McpClientRegistry(agentToolRegistry); }
    }
```

需要 import：`java.util.Set`、`java.util.HashSet`、`java.util.LinkedHashSet`、`java.time.LocalDateTime`、`java.util.stream.Collectors`。

**重构 `registerToolsToAgent` 以记录归属**（:58）：

```java
    private void registerToolsToAgent(String clientId, List<McpToolDefinition> tools) {
        if (tools == null || agentToolRegistry == null) {
            return;
        }
        Set<String> owned = agentToolNamesByClient.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet());
        for (McpToolDefinition tool : tools) {
            try {
                ExternalMcpTool externalTool = new ExternalMcpTool(clientId, tool, this);
                agentToolRegistry.register(externalTool);
                owned.add(externalTool.getName());
                log.info("注册外部 MCP 工具到 Agent: {}", externalTool.getName());
            } catch (Exception e) {
                log.error("注册外部工具失败：{}", clientId + "_" + tool.getName(), e);
            }
        }
    }
```

**修改 `registerClient` 登记失败清单**（:37-53）：连接失败时加入 `failedClients`，成功后移除：

```java
    public void registerClient(String clientId, McpClient client) {
        clients.put(clientId, client);
        log.info("注册 MCP Client: {}", clientId);
        try {
            client.connect();
            List<McpToolDefinition> tools = client.listTools();
            toolCache.put(clientId, tools);
            log.info("MCP Client [{}] 加载了 {} 个工具", clientId, tools.size());
            registerToolsToAgent(clientId, tools);
            failedClients.remove(clientId); // 连接成功，移出失败清单
        } catch (Exception e) {
            failedClients.add(clientId); // 登记失败清单供调度器重连
            log.error("MCP Client [{}] 初始化失败，已加入失败清单", clientId, e);
        }
    }
```

**新增 `syncTools`、`removeToolsFor`、`statusSnapshots`**（放在 `disconnectAll` 之前）：

```java
    /**
     * 用远端最新工具列表替换该源(clientId)已注册到 Agent 的工具集：
     * 移除已下架的工具，注册新增的工具，并更新归属追踪与工具缓存。
     * 幂等且并发安全（依赖 ConcurrentHashMap 与 AgentToolRegistry.removeAll 幂等）。
     */
    public void syncTools(String clientId) {
        McpClient client = clients.get(clientId);
        if (client == null) {
            log.warn("syncTools 跳过：未找到 MCP Client: {}", clientId);
            return;
        }
        List<McpToolDefinition> remoteList;
        try {
            remoteList = client.listToolsRemote();
        } catch (Exception e) {
            log.error("MCP Client [{}] 远端获取工具列表失败，无法同步：{}", clientId, e.getMessage());
            return;
        }
        Set<String> remoteNames = new HashSet<>();
        for (McpToolDefinition t : remoteList) {
            remoteNames.add(clientId + "_" + t.getName());
        }
        // 移除已不在远端的工具
        Set<String> owned = agentToolNamesByClient.getOrDefault(clientId, Collections.emptySet());
        List<String> toRemove = owned.stream()
                .filter(n -> !remoteNames.contains(n))
                .toList();
        int removed = agentToolRegistry.removeAll(toRemove);
        // 注册远端新增的工具
        Set<String> newOwned = ConcurrentHashMap.newKeySet(remoteNames);
        for (McpToolDefinition t : remoteList) {
            String name = clientId + "_" + t.getName();
            if (!owned.contains(name)) {
                try {
                    agentToolRegistry.register(new ExternalMcpTool(clientId, t, this));
                } catch (Exception e) {
                    log.error("syncTools 注册新增工具失败：{}", name, e);
                }
            }
        }
        agentToolNamesByClient.put(clientId, newOwned);
        toolCache.put(clientId, remoteList);
        log.info("MCP Client [{}] 同步完成：移除 {} 个，活跃工具 {} 个", clientId, removed, remoteNames.size());
    }

    /**
     * 移除指定 MCP 来源的全部已注册工具，并释放连接、登记失败清单。
     * 用于探活失败 / 调用失败且远端不可达时按源整机移除。
     */
    public void removeToolsFor(String clientId) {
        Set<String> owned = agentToolNamesByClient.remove(clientId);
        if (owned != null && !owned.isEmpty()) {
            agentToolRegistry.removeAll(owned);
            log.info("MCP Client [{}] 已移除 {} 个工具", clientId, owned.size());
        }
        toolCache.remove(clientId);
        McpClient client = clients.remove(clientId);
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("MCP Client [{}] 断开失败：{}", clientId, e.getMessage());
            }
        }
        failedClients.add(clientId); // 移除后进入失败清单，等待重连
        log.info("MCP Client [{}] 已按源移除全部工具并标记失败", clientId);
    }

    /**
     * 采集各 MCP 端点当前状态快照（内存态），供管理员状态查询。
     */
    public List<McpEndpointStatus> statusSnapshots() {
        return clients.keySet().stream().map(clientId -> {
            McpClient client = clients.get(clientId);
            Set<String> owned = agentToolNamesByClient.getOrDefault(clientId, Collections.emptySet());
            boolean connected = client != null && client.isConnected();
            return McpEndpointStatus.builder()
                    .clientId(clientId)
                    .connected(connected)
                    .toolCount(owned.size())
                    .registeredToolNames(List.copyOf(owned))
                    .build();
        }).collect(Collectors.toList());
    }

    /** 当前失败清单（只读快照），供调度器重连 */
    public Set<String> getFailedClients() {
        return Set.copyOf(failedClients);
    }
```

> 注：`statusSnapshots()` 中未在 `clients` 中的失败端点（启动失败暂未入 `clients`）可由 `McpHealthScheduler` 补充。实现在 Task 7。

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am test -Dtest=McpClientRegistrySelfHealTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientRegistry.java company-rag-mcp-client/src/test/java/com/company/rag/mcp/client/McpClientRegistrySelfHealTest.java
git commit -m "feat(mcp-client): McpClientRegistry 归属追踪/失败清单/按源移除与同步"
```

---

## Task 6: `McpFailureHandler` 调用失败即时自愈

- Create: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpFailureHandler.java`
- Test: `company-rag-mcp-client/src/test/java/com/company/rag/mcp/client/McpFailureHandlerTest.java`

**规则（用户最终确认）：**
1. 参数错误（-32602 或关键词）→ 跳过，不处理。
2. 非参数错误 → 命中 MCP 源（外部调用即命中）→ 远端 `listToolsRemote`：
   - 不可达 → `removeToolsFor(clientId)`（移除该源全部工具）
   - 可达 → `syncTools(clientId)`（用远端最新列表替换该源工具集）

**用途：** 供 `McpClientRegistry.callTool` 在 MCP 调用异常分支调用（Task 7 挂钩）。

- [ ] **Step 1: 写失败测试**

```java
package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpFailureHandlerTest {

    static class FakeMcpClient implements McpClient {
        final String id; boolean reachable; List<McpToolDefinition> tools;
        FakeMcpClient(String id) { this.id = id; this.reachable = true; this.tools = List.of(new McpToolDefinition("t", "d", Map.of(), null)); }
        public String getClientId(){return id;}
        public void connect(){}
        public void disconnect(){}
        public boolean isConnected(){return true;}
        public List<McpToolDefinition> listTools(){return tools;}
        public Object callTool(String n,Map<String,Object> p){return null;}
        public boolean ping(){return reachable;}
        public List<McpToolDefinition> listToolsRemote(){return tools;}
    }

    private McpFailureHandler newHandler(AgentToolRegistry ar, McpClientRegistry reg) {
        AuditLogService audit = mock(AuditLogService.class);
        return new McpFailureHandler(reg, audit);
    }

    @Test
    void param_error_is_ignored() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = McpClientRegistry.builder(ar).build();
        FakeMcpClient client = new FakeMcpClient("a");
        registry.registerClient("a", client);
        McpFailureHandler h = newHandler(ar, registry);

        h.handle("a", new McpToolException(-32602, "invalid params"));

        assertTrue(ar.hasTool("a_t"), "参数错误不应移除任何工具");
        assertNotNull(registry.getClient("a"), "参数错误不应触发移除");
    }

    @Test
    void unreachable_source_has_all_tools_removed() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = McpClientRegistry.builder(ar).build();
        FakeMcpClient client = new FakeMcpClient("a");
        registry.registerClient("a", client);
        McpFailureHandler h = newHandler(ar, registry);

        client.reachable = false;
        h.handle("a", new McpToolException(-32000, "server internal error"));

        assertFalse(ar.hasTool("a_t"), "远端不可达应移除该源全部工具");
        assertNull(registry.getClient("a"));
    }

    @Test
    void reachable_source_is_synced() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = McpClientRegistry.builder(ar).build();
        FakeMcpClient client = new FakeMcpClient("a");
        registry.registerClient("a", client);
        McpFailureHandler h = newHandler(ar, registry);

        // 远端只保留下架前的 t，验证收到调用错误后仍保留（可达→同步，不整机移除）
        h.handle("a", new McpToolException(-32000, "server says tool gone"));

        assertTrue(ar.hasTool("a_t"), "可达远端应保留工具集（调 tools/list 同步，不整机移除）");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am test -Dtest=McpFailureHandlerTest`
Expected: FAIL（编译错误——`McpFailureHandler` 不存在）。

- [ ] **Step 3: 实现**

```java
package com.company.rag.mcp.client;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 调用失败即时自愈处理。
 * 规则：参数错误 → 忽略；非参数错误 → 命中 MCP 源 → 远端可达性：
 *   不可达 → 按源移除该 client 全部工具；可达 → 用远端最新列表替换该源工具集。
 * 全程 try-catch，绝不因自愈逻辑抛出影响原始调用返回。
 */
@Slf4j
@Component
public class McpFailureHandler {

    private final McpClientRegistry registry;
    private final AuditLogService auditLogService;

    public McpFailureHandler(McpClientRegistry registry, AuditLogService auditLogService) {
        this.registry = registry;
        this.auditLogService = auditLogService;
    }

    /**
     * 处理一次 MCP 工具调用失败。clientId 由调用边界（McpClientRegistry.callTool）传入。
     */
    public void handle(String clientId, RuntimeException ex) {
        try {
            if (isParamError(ex)) {
                // 参数错误：属调用方传参不当，不是服务器失联，不触发任何移除/同步
                log.info("MCP Client [{}] 调用失败但为参数错误，忽略自愈：{}", clientId, ex.getMessage());
                return;
            }
            McpClient client = registry.getClient(clientId);
            if (client == null) {
                log.warn("MCP Client [{}] 已不存在，跳过自愈", clientId);
                return;
            }
            boolean reachable = client.ping();
            if (!reachable) {
                // 远端不可达：移除该源全部已注册工具并登入失败清单，等待调度器重连
                log.warn("MCP Client [{}] 调用失败且远端不可达，按源移除全部工具", clientId);
                registry.removeToolsFor(clientId);
                audit("MCP_REMOVE_SOURCE", clientId, "远端不可达，移除该源全部工具：" + ex.getMessage());
            } else {
                // 远端可达：用最新工具列表替换该源工具集（应对工具下架/变更）
                log.info("MCP Client [{}] 调用失败但远端可达，执行工具集同步", clientId);
                registry.syncTools(clientId);
                audit("MCP_SYNC_TOOLS", clientId, "远端可达，按远端列表同步该源工具集：" + ex.getMessage());
            }
        } catch (Exception e) {
            log.error("MCP 自愈处理发生异常，不影响原始调用：clientId={}", clientId, e);
        }
    }

    /** 参数错误判定：严格协议(-32602) + 消息关键词兜底 */
    private boolean isParamError(RuntimeException ex) {
        if (ex instanceof McpToolException) {
            return ((McpToolException) ex).isParamError();
        }
        if (ex.getMessage() == null) {
            return false;
        }
        String msg = ex.getMessage().toLowerCase();
        return msg.contains("argument") || msg.contains("parameter")
                || msg.contains("schema") || msg.contains("invalid params");
    }

    private void audit(String actionType, String clientId, String detail) {
        try {
            auditLogService.recordAsync(AuditLogContext.builder()
                    .actionType(actionType)
                    .targetType("mcp")
                    .targetId(clientId)
                    .detail(detail)
                    .tenantId(TenantContext.getTenantId() != null ? String.valueOf(TenantContext.getTenantId()) : null)
                    .userId(TenantContext.getUserId())
                    .build());
        } catch (Exception e) {
            log.warn("MCP 自愈审计失败，不影响处理：{}", clientId, e);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am test -Dtest=McpFailureHandlerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpFailureHandler.java company-rag-mcp-client/src/test/java/com/company/rag/mcp/client/McpFailureHandlerTest.java
git commit -m "feat(mcp-client): McpFailureHandler 调用失败即时自愈"
```

> **依存:** `McpFailureHandler` 构造注入 `McpClientRegistry`；为使 `McpClientRegistry.callTool` 能调用 handler 而不产生构造循环，handler 在 registry 中**字段注入**（见 Task 7）。

---

## Task 7: `McpHealthScheduler` 定时探活与失败重连，并在 `McpClientRegistry.callTool` 挂钩自愈

- Create: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpHealthScheduler.java`
- Modify: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientRegistry.java`
- Modify: `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientProperties.java`
- Modify: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java`

**改动：** 新增调度器组件；为 `McpClientRegistry` 增加 `@Autowired @Setter McpFailureHandler` 字段并在 `callTool` 异常分支调用；配置加两个间隔字段；开启调度。

- [ ] **Step 1: 扩展 `McpClientProperties`**

在 `clients` 字段之后新增：

```java
    /**
     * 探活间隔（毫秒），默认 10 分钟。作为周期性兜底，实时性由调用失败钩子承担。
     */
    private long healthCheckIntervalMs = 600000L;

    /**
     * 失败重连尝试间隔（毫秒），默认 3 分钟。
     */
    private long reconnectIntervalMs = 180000L;
```

- [ ] **Step 2: 扩展 `McpClientRegistry` 挂钩子**

新增 import：
```java
import org.springframework.beans.factory.annotation.Autowired;
```
新增字段与 setter（放在 `agentToolRegistry` 之后）：
```java
    // 字段注入避免与 McpFailureHandler 构造相互依赖导致的循环
    @Autowired
    private McpFailureHandler mcpFailureHandler;
```
`getClient` 已有。在 `callTool`（:21）改为捕获异常并触发自愈后重抛：

```java
    public Object callTool(String clientId, String toolName, Map<String, Object> params) {
        McpClient client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("未找到 MCP Client: " + clientId);
        }
        log.info("MCP Client [{}] 调用工具：{}, 参数：{}", clientId, toolName, params);
        try {
            Object result = client.callTool(toolName, params);
            log.info("MCP Client [{}] 工具 {} 调用完成", clientId, toolName);
            return result;
        } catch (RuntimeException e) {
            // 调用失败即时自愈（参数错误忽略；非参数错误按远端可达性移除/同步）
            if (mcpFailureHandler != null) {
                mcpFailureHandler.handle(clientId, e);
            }
            throw e;
        }
    }
```

- [ ] **Step 3: 创建 `McpHealthScheduler`**

```java
package com.company.rag.mcp.client;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 健康调度器。
 * - 周期探活(healthCheckInterval-ms)已注册客户端，失联按源移除全部工具。
 * - 周期重连(reconnect-interval-ms)失败清单中的客户端，成功后重新注册。
 * fixedDelay 保证单方法串行执行、轮次不重叠，避免重连风暴。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpHealthScheduler {

    /** 启动失败端点（未进入 clients 但从不健康配置重建），供状态查询补全 */
    private final Map<String, LocalDateTime> startupFailures = new ConcurrentHashMap<>();

    private final McpClientRegistry registry;
    private final McpClientProperties properties;
    private final AuditLogService auditLogService;

    public McpHealthScheduler(McpClientRegistry registry, McpClientProperties properties, AuditLogService auditLogService) {
        this.registry = registry;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    /** 周期探活（默认 10 分钟，兜底） */
    @Scheduled(fixedDelayString = "${mcp.health-check-interval-ms:600000}")
    public void probeConnectedClients() {
        log.debug("MCP 周期探活开始");
        for (Map.Entry<String, McpClient> entry : registry.getClients().entrySet()) {
            String clientId = entry.getKey();
            McpClient client = entry.getValue();
            boolean ok;
            try {
                ok = client.ping();
            } catch (Exception e) {
                ok = false;
                log.warn("MCP Client [{}] 探活异常", clientId, e);
            }
            if (!ok) {
                log.warn("MCP Client [{}] 探活失败，按源移除全部工具", clientId);
                registry.removeToolsFor(clientId);
                audit("MCP_REMOVE_SOURCE", clientId, "周期探活失败，移除该源全部工具");
            } else {
                registry.markReachable(clientId);
                log.debug("MCP Client [{}] 探活正常", clientId);
            }
        }
        // 清理已恢复的启动失败标记
        startupFailures.keySet().retainAll(registry.getClients().keySet());
        log.debug("MCP 周期探活完成");
    }

    /** 重连失败清单（默认 3 分钟一次） */
    @Scheduled(fixedDelayString = "${mcp.reconnect-interval-ms:180000}")
    public void reconnectFailedClients() {
        Set<String> failed = registry.getFailedClients();
        if (failed.isEmpty()) {
            return;
        }
        log.info("MCP 尝试重连失败的客户端：{}", failed);
        for (String clientId : failed) {
            McpClientProperties.ClientConfig config = findConfig(clientId);
            if (config == null) {
                log.warn("MCP 找不到客户端 [{}] 的配置，跳过重连", clientId);
                continue;
            }
            try {
                HttpMcpClient client = new HttpMcpClient(
                        config.getId(), config.getUrl(), config.getTimeout(), config.getHeaders());
                registry.registerClient(config.getId(), client); // 成功后自动从失败清单移除并注册工具
                if (registry.getClient(config.getId()) != null) {
                    startupFailures.remove(config.getId());
                    audit("MCP_RECONNECT", config.getId(), "重连成功并重新注册工具，url=" + config.getUrl());
                }
            } catch (Exception e) {
                log.warn("MCP Client [{}] 重连失败，稍后重试：{}", clientId, e.getMessage());
            }
        }
    }

    /** 从配置定位客户端（兼容来自启动失败的端点） */
    private McpClientProperties.ClientConfig findConfig(String clientId) {
        for (McpClientProperties.ClientConfig c : properties.getClients()) {
            if (clientId.equals(c.getId()) && c.isEnabled()) {
                return c;
            }
        }
        return null;
    }

    /** 记录某个启动失败端点（由 McpClientAutoConfig 在连接异常时调用） */
    public void recordStartupFailure(String clientId) {
        startupFailures.put(clientId, LocalDateTime.now());
    }

    /** 供状态查询合并所有已知端点 */
    public Map<String, LocalDateTime> getStartupFailures() {
        return startupFailures;
    }

    private void audit(String actionType, String clientId, String detail) {
        try {
            auditLogService.recordAsync(AuditLogContext.builder()
                    .actionType(actionType).targetType("mcp").targetId(clientId).detail(detail)
                    .tenantId(TenantContext.getTenantId() != null ? String.valueOf(TenantContext.getTenantId()) : null)
                    .userId(TenantContext.getUserId()).build());
        } catch (Exception e) {
            log.warn("MCP 调度审计失败：clientId={}", clientId, e);
        }
    }
}
```

> `registry.markReachable(clientId)` 需在 Task 5 的 registry 中补充一个标记「恢复」的方法。若 `removeToolsFor` 已在该次探活移除客户端，`markReachable` 仅用于登记探活时间。为简化，将 `markReachable` 定义为幂等记录最近探活结果（见下方 Step 4 补丁）。

- [ ] **Step 4: 为 `McpClientRegistry` 补充 `markReachable`**

在 `McpClientRegistry` 中新增（配合 Task 5 的 `statusSnapshots`）：
```java
    /** 记录最近一次探活成功时间与结果（幂等）。 */
    private final Map<String, String> lastProbe = new ConcurrentHashMap<>();

    /** 标记某 client 探活正常（用于状态快照展示）。 */
    public void markReachable(String clientId) {
        lastProbe.put(clientId, "reachable@" + System.currentTimeMillis());
    }
```
并在 `statusSnapshots()` 的 builder 中带上 `lastProbeTime`（从 `lastProbe` 解析，简化为 `LocalDateTime.now()` 兜底展示）与 `lastProbeResult`：
```java
                    .lastProbeResult(lastProbe.getOrDefault(clientId, "unknown"))
```

- [ ] **Step 5: 开启调度**

`CompanyRagApplication.java` 增加 import：
```java
import org.springframework.scheduling.annotation.EnableScheduling;
```
在 `@EnableAsync` 下方新增注解：
```java
@EnableScheduling  // 启用定时调度（MCP 健康探活与失败重连）
```

> 注意：此注解会同时激活既有 `ToolApprovalConverger`/`AuditLogAsyncWriter` 等带 `@Scheduled` 的 bean（此前未开启调度而未运行）。属预期修正，不影响设计。

- [ ] **Step 6: 让启动失败也进入失败清单**

修改 `McpClientAutoConfig.init()` 的 catch 分支（:56-58），在失败时登记失败清单供 `reconnectFailedClients` 重连：

```java
            } catch (Exception e) {
                log.error("MCP Client [{}] 初始化失败", config.getId(), e);
                registry.registerFailures(clientIdMapping(config));
            }
```
> 为最小改动，改为在 catch 中直接调用 `registry.markFailed(config.getId())`。因此需在 `McpClientRegistry` 增加：
```java
    /** 将某 client 标记为失败（启动失败时调用），供重连任务处理。 */
    public void markFailed(String clientId) { failedClients.add(clientId); }
```
并把 `McpClientAutoConfig` catch 改为：
```java
            } catch (Exception e) {
                log.error("MCP Client [{}] 初始化失败，登记失败清单", config.getId(), e);
                registry.markFailed(config.getId());
            }
```
且删除 Step 3 示例里的 `clientIdMapping` 占位写法。

- [ ] **Step 7: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client,company-rag-agent,company-rag-web,company-rag-bootstrap -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 8: 提交**

```bash
git add company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/ company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java
git commit -m "feat(mcp-client): McpHealthScheduler 周期探活与失败重连，调用失败挂钩自愈"
```

---

## Task 8: 状态查询 API（web 模块）

- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/McpStatusController.java`
- Test: `company-rag-web/src/test/java/com/company/rag/web/controller/McpStatusControllerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.mcp.client.McpEndpointStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpStatusControllerTest {

    @Test
    void status_returns_ok_with_snapshots() {
        McpClientRegistry registry = mock(McpClientRegistry.class);
        when(registry.statusSnapshots()).thenReturn(List.of(
                McpEndpointStatus.builder().clientId("a").connected(false).build()));
        McpStatusController controller = new McpStatusController(registry);

        R<List<McpEndpointStatus>> result = controller.status();

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("a", result.getData().get(0).getClientId());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-web -am test -Dtest=McpStatusControllerTest`
Expected: FAIL（编译错误——controller 不存在）。

- [ ] **Step 3: 实现 Controller**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.mcp.client.McpEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 端点状态查询（仅平台管理员 ROLE_ADMIN 可访问）。
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpStatusController {

    private final McpClientRegistry mcpClientRegistry;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<McpEndpointStatus>> status() {
        return R.ok(mcpClientRegistry.statusSnapshots());
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-web -am test -Dtest=McpStatusControllerTest`
Expected: PASS。

> 若 `R` 接口没有 `isSuccess()`/`getData()`，用 `R.ok(...)` 的既有断言方式替换。实现时参考 `AuditLogController` 用法。

- [ ] **Step 5: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/McpStatusController.java company-rag-web/src/test/java/com/company/rag/web/controller/McpStatusControllerTest.java
git commit -m "feat(web): MCP 状态查询 API /api/mcp/status (admin)"
```

---

## Task 9: `/mcp-status` 页面与 admin 入口

- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/PageController.java`
- Create: `company-rag-web/src/main/resources/templates/mcp-status.html`
- Modify: `company-rag-web/src/main/resources/templates/index.html`

**模式遵循：** 参照既有 `/admin`、`/tool-approval` 页面与 `index.html` 的 `.hdr-btn` + emoji 图标 + `role==='admin'` 控制。

- [ ] **Step 1: 新增页面路由**

`PageController.java` 新增：
```java
    /** MCP 服务器状态管理页面 */
    @GetMapping("/mcp-status")
    public String mcpStatus() {
        return "mcp-status";
    }
```

- [ ] **Step 2: 创建 `mcp-status.html`**

模板依赖前端既有令牌注入与 `fetch('/api/mcp/status')`。参照 `tool-approval.html` 的结构实现一个简表：

```html
<!DOCTYPE html>
<html lang="zh" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>MCP 服务器状态</title>
    <style>
        body { font-family: sans-serif; margin: 24px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background: #f5f5f5; }
        .off { color: #c00; } .on { color: #080; }
    </style>
</head>
<body>
<button onclick="location.href='/admin'">返回管理后台</button>
<h2>MCP 服务器状态</h2>
<table id="statusTable">
    <thead>
    <tr><th>clientId</th><th>连接状态</th><th>工具数</th><th>已注册工具</th><th>最近探活</th></tr>
    </thead>
    <tbody></tbody>
</table>
<script>
    const token = localStorage.getItem('token') || sessionStorage.getItem('token');
    async function load() {
        const res = await fetch('/api/mcp/status', {
            headers: { 'Authorization': 'Bearer ' + token }
        });
        const body = await res.json();
        const tbody = document.querySelector('#statusTable tbody');
        tbody.innerHTML = '';
        (body.data || []).forEach(s => {
            const tr = document.createElement('tr');
            const cls = s.connected ? 'on' : 'off';
            tr.innerHTML = `<td>${s.clientId}</td>
                <td class="${cls}">${s.connected ? '已连接' : '未连接'}</td>
                <td>${s.toolCount}</td>
                <td>${(s.registeredToolNames || []).join(', ') || '—'}</td>
                <td>${s.lastProbeResult || '—'}</td>`;
            tbody.appendChild(tr);
        });
    }
    load();
</script>
</body>
</html>
```

- [ ] **Step 3: 在 `index.html` header 加 admin 图标入口**

参照既有 `.hdr-btn` + emoji + `v-if="role === 'admin'"` 模式，新增 MCP 图标按钮（在工具审批图标旁）：
```html
<button class="hdr-btn" v-if="role === 'admin'" @click="go('/mcp-status')" title="MCP 服务器状态">🌐</button>
```
并确保 `go(path)` 跳转方法与既有入口一致。

- [ ] **Step 4: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-web,company-rag-bootstrap -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/PageController.java company-rag-web/src/main/resources/templates/mcp-status.html company-rag-web/src/main/resources/templates/index.html
git commit -m "feat(web): MCP 状态管理页面 /mcp-status 及 admin 入口"
```

---

## 汇总验证（窄范围）

改动集中在 `company-rag-agent`、`company-rag-mcp-client`、`company-rag-web`、`company-rag-bootstrap`。最终以多模块定向 compile + 各新增单测验证：

```bash
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client,company-rag-agent,company-rag-web,company-rag-bootstrap -am compile
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-agent -am test -Dtest=AgentToolRegistryTest
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-mcp-client -am test -Dtest=McpClientRegistrySelfHealTest,McpFailureHandlerTest
cd /d/tmp/CompanyRag && mvn -q -pl company-rag-web -am test -Dtest=McpStatusControllerTest
```

EPLICIT:
- 各新增单测 PASS。
- 多模块 compile BUILD SUCCESS。
- 不跑全仓测试套件（按最小验证范围）。

---

## 自审结果

**1. 规格覆盖：**
- 启动失败重连 → Task 7（`McpHealthScheduler.reconnectFailedClients` + `AutoConfig` 登记失败）✅
- 探活移除 → Task 7 `probeConnectedClients` ✅
- 调用失败即时自愈 → Task 6 + Task 7 挂钩 `callTool` ✅
- 参数错误先严格后关键词 → `McpToolException.isParamError` + handler ✅
- 状态 API → Task 8 ✅（`McpFailureHandlerTest`/`McpStatusControllerTest`）
- admin 页面 → Task 9 ✅
- 规避目标项目 4 缺点：按 clientId 移除（非别名）、ping 复用连接、不以 0 工具判失败（ping 只看连通性）、单实例内存态 ✅

**2. 占位符扫描：** 已移除 `clientIdMapping` 占位（Task 7 Step 6 内说明用 `registry.markFailed` 取代）。

**3. 类型一致性：**
- `AgentToolRegistry.remove(String)→boolean`、`removeAll(Collection<String>)→int`（Task 1、5、6、7 一致）。
- `McpClient.ping()→boolean`、`listToolsRemote()→List<McpToolDefinition>`（Task 3、5、7 一致）。
- `McpClientRegistry.builder(AgentToolRegistry)`、`registerClient(String,McpClient)`、`syncTools(String)`、`removeToolsFor(String)`、`statusSnapshots()`、`getFailedClients()`、`markFailed(String)`、`markReachable(String)`（Task 5、6、7、8 一致）。
- `McpEndpointStatus` 字段名（clientId/connected/toolCount/registeredToolNames/lastProbeResult）在页面与测试中一致。
- `McpFailureHandler.handle(String, RuntimeException)` 与 `McpClientRegistry.callTool` 的调用形式一致。

> **实现注意（需在编码时按真实签名核对）：** `ExternalMcpTool` 构造 `new ExternalMcpTool(clientId, tool, this)` 已确认。`R<T>` 为 `@Data` 直读字段：断言用 `getCode()`（成功=200）/`getData()`，无 `isSuccess()`。

**执行交接：** 计划已保存到 `docs/superpowers/plans/2026-09-22-mcp-dynamic-loading.md`。可选执行方式：

1. **Subagent-Driven（推荐）** — 每个任务派发独立子代理，任务间评审，快速迭代
2. **Inline Execution** — 本会话内用 executing-plans 批量执行，带检查点

（按你的要求：**未经你审阅批准，我不会改动任何代码。**）你选择哪种方式？
