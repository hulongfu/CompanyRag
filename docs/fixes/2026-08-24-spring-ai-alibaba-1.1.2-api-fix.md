# Spring AI Alibaba 1.1.2.0 API 变更修复

## 修复日期
2026-08-24

## 问题描述
升级到 Spring AI Alibaba 1.1.2.0 版本后，编译失败，出现以下错误：
1. `ReactAgentResult` 类不存在
2. `Builder.chatModel()` 方法不存在
3. `Builder.toolCallbacks()` 方法不存在

## 根本原因
Spring AI Alibaba 1.1.2.0 版本中 API 发生了变更：
- 移除了 `ReactAgentResult` 类
- `ReactAgent.call()` 方法返回类型从 `ReactAgentResult` 改为 `AssistantMessage`
- `Builder` 类的方法名发生了变化

## 修复内容

### 1. company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java

**变更 1：导入语句**
```java
// 删除
import com.alibaba.cloud.ai.graph.agent.ReactAgentResult;

// 新增
import org.springframework.ai.chat.messages.AssistantMessage;
```

**变更 2：方法调用**
```java
// 原来 (1.0.x 版本)
ReactAgentResult agentResult = reactAgent.call(messages);
String response = agentResult.getOutput().getContent();

// 现在 (1.1.2.0 版本)
AssistantMessage agentResult = reactAgent.call(messages);
String response = agentResult.getText();
```

### 2. company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java

**变更：Builder 方法名**
```java
// 原来 (1.0.x 版本)
return ReactAgent.builder()
        .chatModel(chatModel)
        .toolCallbacks(toolCallbackProvider)
        .build();

// 现在 (1.1.2.0 版本)
return ReactAgent.builder()
        .model(chatModel)
        .toolCallbackProviders(toolCallbackProvider)
        .build();
```

## API 变更对照表

| 旧版本 (1.0.x) | 新版本 (1.1.2.0) | 说明 |
|---------------|-----------------|------|
| `ReactAgentResult` | `AssistantMessage` | 返回类型变更 |
| `Builder.chatModel(ChatModel)` | `Builder.model(ChatModel)` | 方法名简化 |
| `Builder.toolCallbacks(ToolCallbackProvider)` | `Builder.toolCallbackProviders(ToolCallbackProvider...)` | 方法名更准确，支持可变参数 |
| `ReactAgentResult.getOutput().getContent()` | `AssistantMessage.getText()` | 获取响应文本更直接 |

## 验证结果
```bash
cd D:/tmp/CompanyRag
mvn clean compile -pl company-rag-agent -am -DskipTests
```

编译成功，所有模块编译通过。

## 影响范围
- ✅ `company-rag-agent` 模块
- ✅ `RagAgentService` 服务类
- ✅ `AgentConfig` 配置类

## 注意事项
1. `AssistantMessage.getText()` 直接返回文本内容，无需再链式调用
2. `toolCallbackProviders()` 支持可变参数，可以传入多个 `ToolCallbackProvider`
3. 所有使用 `ReactAgent.call()` 的地方都需要修改返回类型

## 相关文档
- Spring AI Alibaba 官方文档：https://sca.aliyun.com/docs/
- ReactAgent 源码：`com.alibaba.cloud.ai.graph.agent.ReactAgent`
- Builder 源码：`com.alibaba.cloud.ai.graph.agent.Builder`

## 修复人员
AI Assistant
