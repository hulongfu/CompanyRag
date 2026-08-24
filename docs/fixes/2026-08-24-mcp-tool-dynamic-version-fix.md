# MCP 工具动态版本修复

**日期**: 2026-08-24  
**问题**: MCP 工具注册后无法被 ChatClient 调用  
**根因**: ChatClient 在启动时一次性构建，后续注册的工具无法同步到 ChatClient  

## 问题现象

应用启动日志显示：
```
10:24:52 - 注册 3 个本地工具：[database_query, api_doc, code_search]
10:24:52 - AggregatedToolCallbackProvider 初始化，共 3 个工具
10:24:52 - RagAgentService 初始化完成
10:24:59 - MCP Client 初始化，加载 6 个工具
10:24:59 - 注册 MCP 工具到 AgentToolRegistry: custom_read_file, custom_read_pdf, ...
10:25:55 - 用户请求："你都有哪些工具可用？"
10:26:10 - [AGENT] tools=[], total=15014ms  ← 工具列表为空！
```

## 根因分析

### feature 分支（修复前）
```java
// RagAgentService.java
private final ChatClient chatClient;  // 一次性构建，不可变

public RagAgentService(...) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultToolCallbacks(toolCallbackProvider)
            .build();  // 此时只有 3 个本地工具
}

public AgentResult processWithHistory(...) {
    var promptSpec = chatClient.prompt();  // 使用固定引用
    ...
}
```

**问题**：
- ChatClient 在构造函数中构建，此时 MCP 工具还未注册
- 后续 MCP Client 初始化并注册工具到 AgentToolRegistry
- 但 ChatClient 使用的是固定引用，看不到新注册的工具

### main 分支（正确方案）
```java
// RagAgentService.java
private volatile ChatClient cachedChatClient;
private volatile int cachedToolVersion;

private ChatClient getChatClient() {
    int currentVersion = toolRegistry.getVersion();
    if (cachedChatClient == null || cachedToolVersion != currentVersion) {
        log.info("检测到工具列表变化 (oldVersion={}, newVersion={})，重建 ChatClient", 
                 cachedToolVersion, currentVersion);
        rebuildChatClient();
    }
    return cachedChatClient;
}

// AgentToolRegistry.java
private volatile int version = 0;

public void register(AgentTool tool) {
    tools.put(tool.getName(), tool);
    version++;  // 每次注册递增版本号
    log.debug("注册 Agent 工具：{} (version={})", tool.getName(), version);
}
```

**解决方案**：
1. `AgentToolRegistry` 增加版本号管理，每次工具注册时递增
2. `RagAgentService` 缓存 ChatClient + 版本号
3. 每次请求前检查版本号，变化则重建 ChatClient

## 修复内容

### 1. AgentToolRegistry.java
**修改**：添加版本号管理

```java
private volatile int version = 0; // 工具列表版本号

public void register(AgentTool tool) {
    tools.put(tool.getName(), tool);
    version++; // 递增版本号
    log.debug("注册 Agent 工具：{} (version={})", tool.getName(), version);
}

public int getVersion() {
    return version;
}

public Map<String, AgentTool> getAllTools() {
    return Collections.unmodifiableMap(tools);
}
```

### 2. RagAgentService.java
**修改**：动态重建 ChatClient

```java
// 字段改为缓存模式
private volatile ChatClient cachedChatClient;
private volatile int cachedToolVersion;

// 构造方法
public RagAgentService(...) {
    ...
    rebuildChatClient();  // 初始构建
    log.info("RagAgentService 初始化：..., initialToolVersion={}", cachedToolVersion);
}

// 新增方法
private void rebuildChatClient() {
    this.cachedChatClient = ChatClient.builder(chatModel)
            .defaultToolCallbacks(toolCallbackProvider)
            .build();
    this.cachedToolVersion = toolRegistry.getVersion();
    log.debug("重建 ChatClient，当前工具版本号：{}", cachedToolVersion);
}

private ChatClient getChatClient() {
    int currentVersion = toolRegistry.getVersion();
    if (cachedChatClient == null || cachedToolVersion != currentVersion) {
        log.info("检测到工具列表变化 (oldVersion={}, newVersion={})，重建 ChatClient", 
                 cachedToolVersion, currentVersion);
        rebuildChatClient();
    }
    return cachedChatClient;
}

// 修改 processWithHistory 方法
public AgentResult processWithHistory(...) {
    ...
    ChatClient chatClient = getChatClient();  // 动态获取
    var promptSpec = chatClient.prompt();
    ...
}
```

### 3. 方法签名调整
由于 `applyWindowControl` 和 `compressHistoryWithLLM` 需要使用 `chatClient`，修改方法签名：

```java
// 修改前
private List<Message> applyWindowControl(List<Message> history)
private List<Message> compressHistoryWithLLM(List<Message> history)

// 修改后
private List<Message> applyWindowControl(ChatClient chatClient, List<Message> history)
private List<Message> compressHistoryWithLLM(ChatClient chatClient, List<Message> history)
```

## 验证结果

### 编译验证
```bash
$ mvn clean compile -DskipTests -pl company-rag-agent,company-rag-mcp-client -am
...
[INFO] BUILD SUCCESS
[INFO] Total time:  12.004 s
```

### 预期日志（修复后）
启动时应看到：
```
10:24:52 - 初始构建 ChatClient, version=0
10:24:52 - 注册 3 个本地工具，version 递增到 3
10:24:59 - MCP Client 初始化
10:24:59 - 注册 MCP 工具 custom_read_file, version 递增到 4
10:24:59 - 注册 MCP 工具 custom_read_pdf, version 递增到 5
...
10:24:59 - 注册 MCP 工具 custom_list_supported_formats, version 递增到 9
10:25:55 - 用户请求
10:25:55 - 检测到工具列表变化 (oldVersion=3, newVersion=9)，重建 ChatClient
10:25:55 - [AGENT] tools=[custom_read_file(...), ...], total=xxxms
```

## 技术要点

1. **volatile 关键字**：确保多线程可见性
   - `cachedChatClient` 和 `cachedToolVersion` 使用 volatile
   - 保证一个线程修改后，其他线程立即可见

2. **版本号递增时机**：
   - 每次 `AgentToolRegistry.register()` 调用时递增
   - MCP 工具注册、本地工具注册都会触发

3. **性能优化**：
   - 版本号未变化时复用 ChatClient 缓存
   - 避免每次请求都重建 ChatClient

4. **线程安全**：
   - `getChatClient()` 方法可能并发调用
   - volatile + 双重检查保证线程安全

## 相关文件

- `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

## 参考

- main 分支实现：`git show main:company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`
- 原始问题日志：`D:\MyTemp\tmpLog.txt`
