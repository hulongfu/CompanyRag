# Spring AI Alibaba Skill Engine 设计文档

**日期**: 2026-08-22  
**分支**: feature/openclaw-skill-engine  
**状态**: 设计中  

---

## 1. 概述

### 1.1 项目背景

当前 CompanyRag 项目使用 Spring AI 1.0.4，仅支持基础的工具调用（Tool Calling），缺少**Skill（技能）系统**。为了实现 Agent 能够自主发现并使用本地 Skill，需要引入 **Spring AI Alibaba Agent Framework**。

### 1.2 目标

通过引入 Spring AI Alibaba Agent Framework，实现：
1. **Skill 自动发现**：Agent 能自动扫描并加载外部文件系统中的 Skill
2. **自主决策**：Agent 根据用户问题，自主决定调用 Tool 还是 Skill
3. **渐进式披露**：通过 `read_skill` 工具加载完整 Skill 指令
4. **工具协同**：Skill 与 MCP 工具平级，Agent 可组合使用

### 1.3 核心原则

- **最小改动**：保留现有项目结构，仅替换必要的依赖和配置
- **外部 Skill**：Skill 存放在外部文件系统，避免 JAR 打包问题
- **OpenAI 协议兼容**：使用 `spring-ai-openai-spring-boot-starter`，保持现有 DashScope 配置
- **渐进式迁移**：先验证核心功能，再完善其他特性

---

## 2. 架构设计

### 2.1 整体架构对比

#### 当前架构（Spring AI 1.0.4）
```
┌─────────────────────────────────────┐
│         RagAgentService             │
│                                     │
│  ┌───────────────────────────────┐ │
│  │  AgentToolRegistry            │ │
│  │  - Local Tools                │ │
│  │  - MCP Tools (via MCP Client) │ │
│  └───────────────────────────────┘ │
│                                     │
│  LLM → Tool Calling → Tool Execution
└─────────────────────────────────────┘
```

#### 目标架构（Spring AI Alibaba Agent Framework）
```
┌─────────────────────────────────────────────────────────┐
│                   RagAgentService                       │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │              ReactAgent (Spring AI Alibaba)       │ │
│  │                                                   │ │
│  │  ┌─────────────────┐  ┌────────────────────────┐ │ │
│  │  │ SkillsAgentHook │  │  AgentToolRegistry     │ │ │
│  │  │                 │  │                        │ │ │
│  │  │ - read_skill    │  │  - Local Tools         │ │ │
│  │  │ - Skill 发现     │  │  - MCP Tools           │ │ │
│  │  └─────────────────┘  └────────────────────────┘ │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  LLM → 自主决策 → Tool/Skill Calling → Execution
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │  FileSystemSkillReg   │
              │  (外部技能目录)        │
              │  ./agent_skills/      │
              └───────────────────────┘
```

### 2.2 核心组件

#### 2.2.1 ReactAgent（反应式 Agent）
- **职责**：Spring AI Alibaba 提供的 Agent 实现，支持多轮工具调用和自主决策
- **替换**：替换当前 `RagAgentService` 中的 `ChatClient` 逻辑
- **关键能力**：
  - 支持 Tool Calling 和 Skill Calling
  - 自动管理会话历史
  - 支持渐进式工具披露

#### 2.2.2 SkillsAgentHook（Skill 钩子）
- **职责**：提供 `read_skill` 工具，让 Agent 能加载 Skill 指令
- **来源**：Spring AI Alibaba Agent Framework 内置
- **使用方式**：在构建 ReactAgent 时注入

#### 2.2.3 FileSystemSkillRegistry（文件系统 Skill 注册中心）
- **职责**：扫描外部文件系统中的 Skill 目录
- **配置**：通过配置文件指定 Skill 根目录
- **优势**：避免 JAR 打包后无法加载 resources 的问题

#### 2.2.4 Skill 结构
```
./agent_skills/
└── {skill-name}/
    ├── SKILL.md              # 必需：Skill 定义（Front Matter + 指令）
    ├── scripts/              # 可选：Python 脚本
    ├── references/           # 可选：参考文件
    └── assets/               # 可选：资源模板
```

---

## 3. 实施方案

### 方案 1：最小改动方案（推荐）

**核心思路**：仅替换必要的依赖和配置，保留现有业务逻辑

**实施步骤**：
1. 在 `pom.xml` 中引入 Spring AI Alibaba Agent Framework BOM
2. 添加 `spring-ai-alibaba-agent-framework` 依赖
3. 保留 `spring-ai-openai-spring-boot-starter`（当前使用 DashScope）
4. 配置 `FileSystemSkillRegistry` 指向外部 Skill 目录
5. 修改 `RagAgentService`，使用 `ReactAgent` 替代 `ChatClient`
6. 将现有 Tool 注册到 ReactAgent

**优点**：
- ✅ 改动最小，风险低
- ✅ 保留现有 DashScope 配置
- ✅ 快速验证核心功能

**缺点**：
- ❌ 依赖两个框架（Spring AI + Spring AI Alibaba），需确保版本兼容
- ❌ 部分高级特性可能无法使用

**适用场景**：快速验证可行性，后续再优化

---

### 方案 2：完全迁移方案

**核心思路**：完全迁移到 Spring AI Alibaba 生态

**实施步骤**：
1. 替换 `spring-ai-openai-spring-boot-starter` 为 `spring-ai-alibaba-starter`
2. 引入 `spring-ai-alibaba-agent-framework`
3. 修改模型配置（DashScope 配置方式可能不同）
4. 重写 `RagAgentService` 使用 ReactAgent
5. 适配现有 Tool 到 Spring AI Alibaba 的 Tool 接口

**优点**：
- ✅ 单一框架依赖，版本管理简单
- ✅ 可使用 Spring AI Alibaba 全部特性
- ✅ 长期维护性更好

**缺点**：
- ❌ 改动较大，风险高
- ❌ 需要修改现有模型配置
- ❌ 可能需要调整现有业务逻辑

**适用场景**：长期演进，全面拥抱 Spring AI Alibaba 生态

---

### 方案 3：混合架构方案

**核心思路**：保留当前 Spring AI，额外引入 Skill 发现机制

**实施步骤**：
1. 保留现有 Spring AI 1.0.4
2. 自行实现 `SkillRegistry` 和 `SkillExecutor`
3. 实现 `read_skill` 工具
4. 在 `RagAgentService` 中手动集成 Skill 调用逻辑

**优点**：
- ✅ 不依赖 Spring AI Alibaba，控制力更强
- ✅ 可定制 Skill 执行逻辑

**缺点**：
- ❌ 重复造轮子，工作量大
- ❌ 缺少框架支持，稳定性未知
- ❌ 长期维护成本高

**适用场景**：对框架控制力要求极高，或有特殊需求

---

## 4. 推荐方案

### 推荐：方案 1（最小改动方案）

**理由**：
1. **快速验证**：能在最短时间内验证"Agent 自主调用 Skill"的核心功能
2. **风险可控**：保留现有配置和业务逻辑，改动范围小
3. **可回退**：如果方案不可行，可轻松回退
4. **渐进式**：验证成功后，可选择继续优化或迁移到方案 2

**关键决策**：
- 使用 `spring-ai-openai-spring-boot-starter`（保留现有 DashScope 配置）
- 使用 `FileSystemSkillRegistry`（外部 Skill 目录）
- Skill 目录：`./agent_skills`（项目根目录下）

---

## 5. 技术细节

### 5.1 依赖配置

```xml
<!-- dependencyManagement 中引入 BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-extensions-bom</artifactId>
            <version>1.1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- dependencies 中添加 -->
<dependencies>
    <!-- Agent Framework -->
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    </dependency>
    
    <!-- 保留现有的 OpenAI Starter -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>
    
    <!-- 保留现有的 MCP Client -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-client</artifactId>
    </dependency>
</dependencies>
```

### 5.2 配置文件

```yaml
# application.yml
spring:
  ai:
    # 现有 DashScope 配置保持不变
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus
    
    # 新增 Skill Registry 配置
    alibaba:
      agent:
        skill:
          enabled: true
          type: filesystem  # 或 classpath
          filesystem:
            root-path: ./agent_skills  # 外部文件系统路径
```

### 5.3 Agent 配置

```java
@Configuration
public class AgentConfig {
    
    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, 
                                  AgentToolRegistry agentToolRegistry,
                                  FileSystemSkillRegistry skillRegistry) {
        // 创建 SkillsAgentHook，提供 read_skill 工具
        SkillsAgentHook skillsHook = new SkillsAgentHook(skillRegistry);
        
        // 获取现有工具
        List<ToolCallback> toolCallbacks = convertToToolCallbacks(agentToolRegistry);
        
        // 构建 ReactAgent
        return ReactAgent.builder()
            .model(chatModel)
            .tools(skillsHook.getTools())  // 注入 Skill 工具
            .tools(toolCallbacks)          // 注入 MCP/本地工具
            .build();
    }
    
    private List<ToolCallback> convertToToolCallbacks(AgentToolRegistry registry) {
        // 转换现有 Tool 为 Spring AI Alibaba 的 ToolCallback 格式
        // ...
    }
}
```

### 5.4 RagAgentService 修改

```java
@Service
public class RagAgentService {
    
    @Autowired
    private ReactAgent reactAgent;  // 替换 ChatClient
    
    @Autowired
    private ChatMemory chatMemory;
    
    public Flux<ServerSentEvent<String>> processWithHistory(...) {
        // 使用 ReactAgent 处理消息
        // ReactAgent 会自动管理 Tool/Skill 调用
        // ...
    }
}
```

---

## 6. 数据模型

### 6.1 Skill 定义（SKILL.md）

```markdown
---
name: calculator
description: 数学计算技能，支持加减乘除四则运算
read_when:
  - User needs to calculate mathematical expressions
  - User asks "calculate X + Y" or similar
---

# Calculator Skill

## Usage

When user asks for calculation:
1. Extract the mathematical expression
2. Call: `python skills/calculator/scripts/calculator.py [expression]`

## Example

User: "Calculate 100 * 25"
→ Execute: `python skills/calculator/scripts/calculator.py 100 * 25`
→ Result: "2500"
```

### 6.2 工具/Skill 对比

| 维度 | Tool | Skill |
|------|------|-------|
| **定义方式** | Java 代码 / MCP 协议 | SKILL.md 文件 |
| **加载方式** | 启动时注册 | 按需加载（read_skill） |
| **执行方式** | 直接调用 | Agent 按指令执行 |
| **复杂度** | 单一功能 | 复杂工作流 |
| **示例** | read_file, search_database | calculator, web-search |

---

## 7. 执行流程

### 7.1 Agent 处理用户请求的完整流程

```
用户提问："帮我计算 100 * 25"
    │
    ▼
RagAgentService.processWithHistory()
    │
    ▼
ReactAgent.chat()
    │
    ▼
LLM 分析问题意图
    │
    ├── 需要计算 → 发现 calculator Skill
    │                │
    │                ▼
    │         调用 read_skill("calculator")
    │                │
    │                ▼
    │         FileSystemSkillRegistry 加载 SKILL.md
    │                │
    │                ▼
    │         LLM 读取指令 → 执行 Python 脚本
    │                │
    │                ▼
    │         返回计算结果
    │
    └── 需要读取文件 → 调用 MCP 工具 read_file
                         │
                         ▼
                  McpClient.callTool()
                         │
                         ▼
                  返回文件内容
```

### 7.2 渐进式工具披露

```
1. 初始状态：Agent 只知道基础工具 + read_skill
2. 用户提问 → LLM 决定使用 calculator Skill
3. 调用 read_skill("calculator") → 加载完整指令
4. 指令中提到底层工具（如 execute） → 动态添加到可用工具列表
5. Agent 调用底层工具执行任务
```

---

## 8. 错误处理

### 8.1 Skill 加载失败

```java
@Bean
public SkillRegistry skillRegistry() {
    FileSystemSkillRegistry registry = new FileSystemSkillRegistry("./agent_skills");
    
    // 添加错误处理
    registry.setSkillLoadErrorHandler(error -> {
        log.error("Skill 加载失败：{}", error.getSkillName(), error.getException());
        // 可选：降级处理、告警等
    });
    
    return registry;
}
```

### 8.2 Skill 执行失败

- **重试机制**：使用 Resilience4j 配置重试策略
- **降级处理**：Skill 失败时，尝试使用替代方案
- **错误反馈**：返回友好的错误消息给用户

---

## 9. 测试策略

### 9.1 单元测试

- **测试范围**：
  - `FileSystemSkillRegistry`：Skill 扫描和加载
  - `ReactAgent` 配置：工具和 Skill 注入
  - `read_skill` 工具：Skill 指令加载

- **示例**：
```java
@Test
void testSkillAutoDiscovery() {
    // 验证 Agent 能自主发现并调用 calculator Skill
    String response = reactAgent.chat("计算 100 * 25");
    assertThat(response).contains("2500");
}
```

### 9.2 集成测试

- **测试范围**：
  - 完整的请求处理流程
  - Skill 与 MCP 工具协同
  - 渐进式工具披露

### 9.3 手动测试

- **测试场景**：
  1. 数学计算 → 调用 calculator Skill
  2. 网络搜索 → 调用 web-search Skill
  3. 文件读取 → 调用 MCP 工具 read_file
  4. 混合任务 → 组合使用 Skill 和工具

---

## 10. 部署考虑

### 10.1 Skill 目录部署

**开发环境**：
```
./agent_skills/  ← 项目根目录下的 Skill 目录
```

**生产环境**：
```yaml
# application-prod.yml
spring:
  ai:
    alibaba:
      agent:
        skill:
          filesystem:
            root-path: /opt/company-rag/agent_skills  # 外部配置路径
```

### 10.2 Skill 管理

- **版本控制**：Skill 目录可独立版本管理
- **热更新**：修改 SKILL.md 后无需重启应用（需实现监听机制）
- **权限控制**：限制 Skill 脚本的执行权限（沙箱环境）

---

## 11. 风险与缓解

### 风险 1：版本兼容性

**风险**：Spring AI 1.0.4 与 Spring AI Alibaba Agent Framework 版本不兼容

**缓解措施**：
- 在测试环境先验证依赖兼容性
- 准备回退方案（方案 3）
- 查阅官方文档确认兼容版本

### 风险 2：Skill 执行安全

**风险**：执行 Python 脚本可能存在安全风险

**缓解措施**：
- 限制脚本访问权限（沙箱环境）
- 禁止访问文件系统、网络
- 代码审查和签名机制

### 风险 3：性能问题

**风险**：Skill 加载和执行影响响应速度

**缓解措施**：
- 缓存已加载的 Skill 指令
- 异步执行耗时 Skill
- 超时控制和降级

---

## 12. 未来扩展

### 12.1 Skill 市场

- 类似 ClawHub 的社区生态
- 用户上传和分享 Skill
- 评分和评论机制

### 12.2 Skill 组合

- Skill 可以调用其他 Skill
- 支持 Skill 工作流编排
- 可视化编排界面

### 12.3 自适应学习

- 记录 Skill 执行历史
- 基于反馈优化执行策略
- 自动推荐 Skill

---

## 13. 总结

### 核心价值

1. ✅ **Agent 自主决策**：根据任务自主选择 Tool 或 Skill
2. ✅ **Skill 自动发现**：FileSystemSkillRegistry 扫描外部目录
3. ✅ **渐进式披露**：通过 read_skill 按需加载完整指令
4. ✅ **最小改动**：保留现有配置，快速验证核心功能

### 下一步

1. **评审设计文档**：确认方案 1 是否可行
2. **创建实施计划**：详细的任务分解和验收标准
3. **开始实施**：按阶段逐步完成

---

**文档版本**: 1.0  
**状态**: 待评审  
**审批**: 待用户确认
