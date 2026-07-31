# 修复会话记录丢失问题

## 问题描述

用户反馈：在聊天框输入"生成 API 文档"，AI 响应正常，但刷新页面后再次进入该会话，看不到这次的聊天记录。

**根因分析**：
- DOCUMENT 意图（RAG 检索）：会保存会话记录（在 `RagSearchServiceImpl.search()` 中）
- CODE/DATABASE 意图（Agent 工具调用）：**不会保存会话记录**（`RagAgentService.process()` 中没有保存逻辑）
- CHAT 意图：也不会保存会话记录

## 解决方案

采用**方案 A 的变体**：在 `ChatRouter` 层统一保存会话记录

**架构考虑**：
- 避免循环依赖：`company-rag-agent` 不能依赖 `company-rag-rag`
- 在 `ChatRouter.processAgent()` 中调用 `RagSessionService.saveConversation()`
- `RagAgentService.process()` 返回 `AgentResult`，包含答案和工具上下文

## 修改内容

### 1. 新增 `AgentResult` 类

**文件**：`company-rag-agent/src/main/java/com/company/rag/agent/service/AgentResult.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    private String answer;      // Agent 生成的回答
    private String toolContext; // 工具上下文信息（如：tool:api_doc）
}
```

### 2. 修改 `RagAgentService.process()`

**文件**：`company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**变更**：
- 方法签名：`process(String userMessage)` → 返回 `AgentResult`
- 移除会话保存逻辑（由上层 `ChatRouter` 处理）
- 记录工具调用上下文

```java
public AgentResult process(String userMessage) {
    // ... LLM 分析和工具调用 ...
    
    String answer = "...";
    String toolContext = "tool:" + toolName;  // 或 null（如果没有调用工具）
    
    return new AgentResult(answer, toolContext);
}
```

### 3. 修改 `ChatRouter.processAgent()`

**文件**：`company-rag-rag/src/main/java/com/company/rag/rag/router/ChatRouter.java`

**变更**：
- 注入 `RagSessionService`
- 调用 `ragAgentService.process()` 获取 `AgentResult`
- 在返回响应前保存会话记录

```java
private ChatResponse processAgent(ChatRequest request, IntentType intent, String routePath) {
    long startTime = System.currentTimeMillis();
    
    // 调用 Agent 服务
    AgentResult agentResult = ragAgentService.process(request.getQuery());
    String agentAnswer = agentResult.getAnswer();
    String toolContext = agentResult.getToolContext();

    // 保存对话记录（如果有 sessionId）
    if (request.getSessionId() != null && request.getTenantId() != null) {
        try {
            Long userId = request.getUserId() != null ? request.getUserId() : 1L;
            ragSessionService.saveConversation(
                    request.getTenantId(), request.getSessionId(), userId,
                    request.getQuery(), agentAnswer, toolContext,
                    0, 0, (int) (System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            log.warn("保存对话记录失败", e);
        }
    }

    // 返回响应...
}
```

### 4. 修改 `ChatRequest` 添加 userId 字段

**文件**：`company-rag-rag/src/main/java/com/company/rag/rag/response/ChatRequest.java`

```java
/**
 * 用户 ID
 */
private Long userId;
```

### 5. 修改 `AgentController`

**文件**：`company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java`

**变更**：适配新的 `process()` 方法签名

```java
@PostMapping("/chat")
public R<String> chat(@RequestBody Map<String, String> request) {
    String message = request.get("message");
    // context 参数不再使用，忽略
    return R.ok(agentService.process(message).getAnswer());
}
```

### 6. 修改 `company-rag-agent/pom.xml`

**变更**：回退添加的 `company-rag-rag` 依赖，避免循环依赖

## 验证结果

✅ 编译成功：`mvn clean compile -DskipTests`

## 预期效果

修复后，所有意图类型都会保存会话记录：

| 意图类型 | 处理流程 | 是否保存会话 |
|---------|---------|------------|
| DOCUMENT | `ChatRouter.processDocument()` → `RagSearchServiceImpl.search()` | ✅ 是（RagSearchServiceImpl 内部保存） |
| CODE | `ChatRouter.processAgent()` → `RagAgentService.process()` | ✅ 是（ChatRouter 保存） |
| DATABASE | `ChatRouter.processAgent()` → `RagAgentService.process()` | ✅ 是（ChatRouter 保存） |
| CHAT | `ChatRouter.processChat()` → `directLLMAnswer()` | ❌ 否（待后续修复） |

**后续优化建议**：
- CHAT 意图也应该保存会话记录（在 `ChatRouter.processChat()` 或 `directLLMAnswer()` 中）
- 可以考虑在 `ChatRouter.route()` 方法统一保存，避免重复代码

## 测试建议

重启应用后测试以下场景：

1. **生成 API 文档**（CODE 意图）
   - 输入："生成 API 文档"
   - 预期：刷新页面后可以看到聊天记录

2. **数据库查询**（DATABASE 意图）
   - 输入："查询最近五天的聊天记录"
   - 预期：刷新页面后可以看到聊天记录

3. **代码搜索**（CODE 意图）
   - 输入："在哪里调用了 UserService？"
   - 预期：刷新页面后可以看到聊天记录

4. **RAG 检索**（DOCUMENT 意图）
   - 输入："需求文档中关于登录的描述"
   - 预期：刷新页面后可以看到聊天记录（原有功能，应该正常）
