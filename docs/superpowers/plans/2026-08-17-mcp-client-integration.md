# MCP Client 集成计划

**日期:** 2026-08-17  
**阶段:** 阶段 3/3（现状评估 → MCP Server 实现 → MCP Client 集成）  
**状态:** 待实施  

---

## 1. 概述

### 1.1 目标

让当前 CompanyRag Agent 能够调用**外部 MCP Server**提供的工具，扩展 Agent 能力边界。

**首期集成:** 文件系统 MCP Server（本地文件操作）

### 1.2 核心功能

- [ ] 实现 `McpClient`：HTTP + JSON-RPC 2.0 协议客户端
- [ ] 实现 `ExternalMcpTool`：封装外部 MCP 工具为内部 `AgentTool`
- [ ] 实现 `McpClientRegistry`：管理多个外部 MCP Server 连接
- [ ] 集成到现有 `AgentToolRegistry`，Agent 可透明调用外部工具
- [ ] 配置文件系统 MCP Server 连接示例
- [ ] 编写完整测试（单元测试 + 集成测试）

### 1.3 技术栈

- **JDK 17** + **Spring Boot 3.4.4** + **Spring AI 1.0**
- **HTTP Client:** Spring `WebClient`（异步、非阻塞）
- **JSON-RPC 2.0:** 复用阶段 2 的 `JsonRpcHandler`、`JsonRpcRequest`、`JsonRpcResponse`
- **配置管理:** Spring `@ConfigurationProperties`
- **测试:** JUnit 5 + Mockito + WireMock（模拟外部 MCP Server）

---

## 2. 架构设计

### 2.1 高层次架构

```
┌─────────────────────────────────────────────────────────────┐
│                    CompanyRag Agent                         │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           AgentToolRegistry (现有)                     │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────────┐  │  │
│  │  │ RAG Tool    │  │ DB Tool     │  │ ExternalMcpTool│  │  │
│  │  │ (内部)      │  │ (内部)      │  │ (新)           │  │  │
│  │  └─────────────┘  └─────────────┘  └───────┬───────┘  │  │
│  └─────────────────────────────────────────────┼───────────┘  │
└────────────────────────────────────────────────┼──────────────┘
                                                 │
                                    ┌────────────▼────────────┐
                                    │   McpClientRegistry     │
                                    │  (管理多个 MCP Client)   │
                                    └────────────┬────────────┘
                                                 │
                      ┌──────────────────────────┼──────────────────────┐
                      │                          │                      │
             ┌────────▼────────┐      ┌──────────▼───────┐   ┌─────────▼────────┐
             │  McpClient #1   │      │  McpClient #2    │   │  McpClient #N    │
             │ (文件系统 MCP)  │      │  (GitHub MCP)    │   │  (其他 MCP)      │
             └────────┬────────┘      └──────────┬───────┘   └──────────────────┘
                      │                          │
         ┌────────────▼────────────┐   ┌─────────▼─────────┐
         │  文件系统 MCP Server    │   │  GitHub MCP Server│
         │  (本地/远程 HTTP)       │   │  (远程 HTTP)      │
         └─────────────────────────┘   └───────────────────┘
```

### 2.2 模块结构

**新增模块：** `company-rag-mcp-client`（独立模块，与 `company-rag-mcp` 并列）

```
company-rag-mcp-client/
├── pom.xml
├── src/main/java/com/company/rag/mcp/client/
│   ├── McpClient.java              # MCP 客户端核心（HTTP + JSON-RPC）
│   ├── McpClientRegistry.java      # 管理多个 MCP Client
│   ├── McpClientProperties.java    # 配置属性类
│   ├── tool/
│   │   ├── ExternalMcpTool.java    # 外部 MCP 工具适配器
│   │   └── McpToolRegistry.java    # 外部工具注册中心
│   └── model/
│       ├── McpConnectionConfig.java    # 连接配置（URL、超时等）
│       └── McpToolMetadata.java        # 工具元数据（缓存用）
├── src/test/java/com/company/rag/mcp/client/
│   ├── McpClientTest.java
│   ├── McpClientRegistryTest.java
│   ├── ExternalMcpToolTest.java
│   └── integration/
│       └── McpClientIntegrationTest.java  # 真实 MCP Server 集成测试
└── src/test/resources/
    └── wiremock/                     # WireMock 模拟外部 MCP Server
        └── mappings/
            ├── tools-list.json
            └── tools-call.json
```

**修改模块：**
- `company-rag-agent/pom.xml` — 添加对 `company-rag-mcp-client` 的依赖
- `company-rag-agent/src/main/java/.../AgentToolRegistry.java` — 集成外部工具（可选，通过 SPI 或自动扫描）

---

## 3. 核心接口设计

### 3.1 McpClient

```java
/**
 * MCP 协议客户端
 * 负责与外部 MCP Server 通信（HTTP + JSON-RPC 2.0）
 */
public interface McpClient {
    
    /**
     * 获取客户端 ID（对应一个 MCP Server 连接）
     */
    String getClientId();
    
    /**
     * 连接 MCP Server（初始化）
     */
    void connect();
    
    /**
     * 断开连接
     */
    void disconnect();
    
    /**
     * 检查连接状态
     */
    boolean isConnected();
    
    /**
     * 获取工具列表
     * @return MCP 工具定义列表
     */
    List<McpToolDefinition> listTools();
    
    /**
     * 调用工具
     * @param toolName 工具名称
     * @param params 工具参数
     * @return 工具执行结果
     */
    Object callTool(String toolName, Map<String, Object> params);
}
```

### 3.2 ExternalMcpTool

```java
/**
 * 外部 MCP 工具适配器
 * 将外部 MCP 工具封装为内部 AgentTool 接口
 */
public class ExternalMcpTool implements AgentTool {
    
    private final String mcpClientId;      // MCP Client ID
    private final McpToolDefinition toolDefinition;  // 工具定义
    private final McpClientRegistry clientRegistry;  // Client 注册中心
    
    @Override
    public String getName() {
        return toolDefinition.getName();
    }
    
    @Override
    public String getDescription() {
        return toolDefinition.getDescription();
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        return toolDefinition.getInputSchema();
    }
    
    @Override
    public String execute(Map<String, Object> params) {
        // 通过 McpClientRegistry 调用外部 MCP Server
        Object result = clientRegistry.callTool(mcpClientId, getName(), params);
        return convertToString(result);
    }
}
```

### 3.3 McpClientRegistry

```java
/**
 * MCP Client 注册中心
 * 管理多个外部 MCP Server 连接
 */
@Component
public class McpClientRegistry {
    
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolDefinition>> toolCache = new ConcurrentHashMap<>();
    
    /**
     * 注册 MCP Client
     */
    public void registerClient(String clientId, McpClient client);
    
    /**
     * 获取 Client
     */
    public McpClient getClient(String clientId);
    
    /**
     * 列出所有已注册 Client 的工具（合并）
     */
    public List<Map<String, Object>> listAllTools();
    
    /**
     * 调用指定 Client 的工具
     */
    public Object callTool(String clientId, String toolName, Map<String, Object> params);
    
    /**
     * 断开所有连接
     */
    public void disconnectAll();
}
```

---

## 4. 实现任务分解

### Task 1: 创建 company-rag-mcp-client 模块骨架

**Files:**
- Create: `company-rag-mcp-client/pom.xml`
- Modify: `pom.xml` (root) — 添加模块引用
- Test: 无

**Steps:**
- [ ] 创建 `company-rag-mcp-client/pom.xml`
  - 父 POM: `com.company:company-rag:1.0.0-SNAPSHOT`
  - 依赖：`company-rag-agent`、`company-rag-mcp`（复用模型）、Spring Boot Web、Spring WebClient、JUnit
- [ ] 更新根 `pom.xml`，添加 `<module>company-rag-mcp-client</module>`
- [ ] 验证编译：`mvn clean compile -pl company-rag-mcp-client`

---

### Task 2: 实现 MCP Client 核心

**Files:**
- Create: `McpClient.java`（接口）
- Create: `HttpMcpClient.java`（基于 WebClient 的实现）
- Create: `McpClientProperties.java`（配置属性）
- Create: `McpConnectionConfig.java`（连接配置）
- Test: `McpClientTest.java`（Mock 测试）

**Steps:**
- [ ] 定义 `McpClient` 接口
- [ ] 实现 `HttpMcpClient`：
  - 使用 `WebClient` 发送 HTTP 请求
  - 复用 `JsonRpcHandler` 构建请求/解析响应
  - 实现连接管理（connect/disconnect/isConnected）
  - 实现 `listTools()` 和 `callTool()`
- [ ] 实现配置类 `McpClientProperties`（支持多实例配置）
- [ ] 编写单元测试（Mock WebClient）

---

### Task 3: 实现 ExternalMcpTool 适配器

**Files:**
- Create: `ExternalMcpTool.java`
- Create: `McpToolRegistry.java`
- Test: `ExternalMcpToolTest.java`

**Steps:**
- [ ] 实现 `ExternalMcpTool`（实现 `AgentTool` 接口）
- [ ] 实现 `McpToolRegistry`（自动扫描并注册外部工具到 `AgentToolRegistry`）
- [ ] 编写单元测试

---

### Task 4: 实现 McpClientRegistry

**Files:**
- Create: `McpClientRegistry.java`
- Test: `McpClientRegistryTest.java`

**Steps:**
- [ ] 实现 `McpClientRegistry`（管理多个 Client）
- [ ] 实现工具缓存机制（启动时加载工具列表）
- [ ] 实现工具调用路由（根据 clientId 路由到对应 Client）
- [ ] 编写单元测试

---

### Task 5: 配置文件系统 MCP Server 示例

**Files:**
- Create: `application-mcp-example.yml`（示例配置）
- Create: `README.md`（使用文档）

**Steps:**
- [ ] 编写配置文件系统 MCP Server 的 YAML 示例
- [ ] 编写使用文档（如何配置、如何测试）
- [ ] 可选：提供 Docker Compose 配置（启动本地文件系统 MCP Server）

---

### Task 6: 编写集成测试

**Files:**
- Create: `McpClientIntegrationTest.java`
- Create: WireMock mappings（模拟 MCP Server）

**Steps:**
- [ ] 使用 WireMock 模拟外部 MCP Server
  - Mock `tools/list` 响应
  - Mock `tools/call` 响应
- [ ] 编写集成测试：
  - 测试工具列表获取
  - 测试工具调用
  - 测试错误处理（超时、404、500 等）
- [ ] 验证测试通过

---

### Task 7: 文档和验收

**Files:**
- Create: `company-rag-mcp-client/README.md`
- Modify: `docs/superpowers/specs/2026-08-16-mcp-status-assessment.md`（更新阶段 3 状态）

**Steps:**
- [ ] 编写 MCP Client 模块 README
- [ ] 更新阶段 3 Spec 文档状态
- [ ] 运行完整测试：`mvn test -pl company-rag-mcp-client`
- [ ] 提交代码

---

## 5. 配置文件示例

```yaml
# application.yml
mcp:
  clients:
    - id: filesystem
      name: 文件系统 MCP Server
      url: http://localhost:8081/mcp
      enabled: true
      timeout: 30s
      retry:
        max-attempts: 3
        backoff: 1s
    
    - id: github
      name: GitHub MCP Server
      url: https://github-mcp-server.example.com/mcp
      enabled: false  # 暂时禁用
      timeout: 60s
      headers:
        Authorization: Bearer ${GITHUB_TOKEN}
```

---

## 6. 验收标准

- [ ] **编译成功:** `mvn clean compile` - BUILD SUCCESS
- [ ] **测试通过:** `mvn test -pl company-rag-mcp-client` - 所有测试通过
- [ ] **功能验证:**
  - 配置文件系统 MCP Server 后可自动连接
  - Agent 可调用外部文件工具（如 `read_file`、`write_file`、`list_directory`）
  - 外部工具在 Agent 工具列表中可见
- [ ] **文档完整:** README 包含配置示例和使用说明

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 外部 MCP Server 不稳定 | 工具调用失败 | 实现重试机制、超时保护、熔断器 |
| 协议不兼容 | 无法通信 | 严格遵循 JSON-RPC 2.0 + MCP 规范，编写兼容性测试 |
| 性能问题 | 响应慢 | 实现工具缓存、连接池、异步调用 |
| 安全风险 | 恶意工具 | 白名单机制、权限控制、审计日志 |

---

## 8. 时间估算

| 任务 | 预计时间 |
|------|----------|
| Task 1: 模块骨架 | 15 分钟 |
| Task 2: MCP Client 核心 | 1 小时 |
| Task 3: ExternalMcpTool | 45 分钟 |
| Task 4: McpClientRegistry | 45 分钟 |
| Task 5: 配置示例 | 30 分钟 |
| Task 6: 集成测试 | 1 小时 |
| Task 7: 文档和验收 | 30 分钟 |
| **总计** | **约 4.5 小时** |

---

## 9. 下一步

1. **评审本 Spec 文档**（等待用户批准）
2. **使用 Subagent-Driven 开发**（每个任务独立执行 + 双重 review）
3. **逐步完成任务**（参考阶段 2 的执行方式）

---

**状态:** 等待用户批准 → 开始实施
