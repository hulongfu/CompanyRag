# Spring AI Alibaba Skill Engine 设计文档

**日期**: 2026-08-23  
**分支**: feature/spring-ai-alibaba-skill-engine  
**状态**: 设计中  

---

## 1. 概述

### 1.1 项目背景

原 OpenClaw Skill Engine 双轨制方案（MCP Hosted + Agent Native）设计复杂，实施成本高。经评估，Spring AI Alibaba Agent Framework 已提供成熟的 Skill 发现和调用机制，可大幅简化架构。

### 1.2 目标

引入 **Spring AI Alibaba Agent Framework**，实现：
1. Agent 自主发现外部文件系统中的 Skill（基于 SKILL.md）
2. Agent 自主决定调用 Skill 或 Tool（两者平级）
3. 保留现有 DashScope 配置（通过 spring-ai-openai 兼容）

### 1.3 核心原则

- **最小改动**：保留现有 spring-ai-openai-spring-boot-starter，仅添加 Agent Framework
- **平级调用**：Skill 和 Tool 对 Agent 是平级的，都由 Agent 根据 description 自主决定
- **外部存储**：Skill 存放在外部 `./agent_skills` 目录，避免 JAR 打包加载问题
- **自动发现**：使用 FileSystemSkillRegistry 自动扫描 Skill

### 1.4 废弃方案

❌ **不再实施**：
- 将 Skill 封装为 MCP Server
- 实现双轨制 Skill Engine（MCP_HOSTED / AGENT_NATIVE）
- 自定义 SkillEngine、SkillRegistry、SkillExecutor

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         CompanyRag Application                   │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    RagAgentService                         │ │
│  │                                                             │ │
│  │  ┌──────────────────────────────────────────────────────┐ │ │
│  │  │  ReactAgent (Spring AI Alibaba Agent Framework)     │ │ │
│  │  │                                                      │ │ │
│  │  │  ┌──────────────────┐   ┌────────────────────────┐  │ │ │
│  │  │  │    Skills        │   │    Tools (MCP)         │  │ │ │
│  │  │  │                  │   │                        │  │ │ │
│  │  │  │  FileSystem      │   │  AgentToolRegistry     │  │ │ │
│  │  │  │  SkillRegistry   │   │  - Local Tools         │  │ │ │
│  │  │  │                  │   │  - MCP Tools           │  │ │ │
│  │  │  │  - 扫描 ./agent_skills   │  - Tool Callbacks   │  │ │ │
│  │  │  │  - 解析 SKILL.md │   │                        │  │ │ │
│  │  │  └──────────────────┘   └────────────────────────┘  │ │ │
│  │  └──────────────────────────────────────────────────────┘ │ │
│  │                          │                                  │ │
│  │                          │ 自主决策                          │ │
│  │                          ▼                                  │ │
│  │         ┌────────────────────────────────┐                 │ │
│  │         │  ChatModel (OpenAI Compatible) │                 │ │
│  │         │  - DashScope API (qwen3.7-max) │                 │ │
│  │         └────────────────────────────────┘                 │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  ./agent_skills/ (外部文件系统路径)                         │ │
│  │    ├── calculator/                                         │ │
│  │    │   ├── SKILL.md                                        │ │
│  │    │   └── scripts/                                        │ │
│  │    ├── web-search/                                         │ │
│  │    │   ├── SKILL.md                                        │ │
│  │    │   └── scripts/                                        │ │
│  │    └── ...                                                 │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件

#### 2.2.1 ReactAgent（Spring AI Alibaba）

**职责**：
- 提供 ReAct（Reasoning + Acting）模式的 Agent 实现
- 自主分析用户问题，决定调用 Skill 或 Tool
- 管理多轮对话和工具调用历史

**接口定义**：
```java
public interface Agent {
    /**
     * 处理用户请求，自主决定调用 Skill 或 Tool
     * @param userMessage 用户消息
     * @return Agent 处理结果
     */
    AgentResult process(String userMessage);
    
    /**
     * 处理用户请求，带会话历史
     * @param history 历史消息列表
     * @param userMessage 当前用户消息
     * @return Agent 处理结果
     */
    AgentResult processWithHistory(List<Message> history, String userMessage);
}
```

#### 2.2.2 FileSystemSkillRegistry（Spring AI Alibaba）

**职责**：
- 自动扫描指定目录下的所有 Skill
- 解析 SKILL.md 文件的 Front Matter 元数据
- 将 Skill 注册为可调用的能力

**Skill 元数据结构**（从 SKILL.md 解析）：
```yaml
---
name: calculator
description: 计算器技能，支持加减乘除四则运算
read_when:
  - User wants to calculate mathematical expressions
  - User asks for arithmetic operations
---
```

#### 2.2.3 SKILL.md 文件格式

**目录结构**：
```
./agent_skills/
└── {skill-name}/
    ├── SKILL.md              # 必需：Skill 定义文件（YAML Front Matter + Markdown）
    └── scripts/              # 可选：脚本目录
        └── {script-name}.py  # Python/Shell 脚本
```

**SKILL.md 示例**：
```markdown
---
name: calculator
description: 计算器技能，支持加减乘除四则运算
read_when:
  - User wants to calculate mathematical expressions
  - User asks for arithmetic operations
---

# Calculator Skill

## Usage

当用户请求计算时，使用以下命令：
```bash
python scripts/calculator.py [expression]
```

## Examples

**User:** "What is 50 + 50?"

**Agent Thought:**
- I need to calculate 50 + 50
- I will use the calculator skill
- Command: `python scripts/calculator.py 50 + 50`

**Tool Call:**
- Name: `execute`
- Args: `{"command": "python scripts/calculator.py 50 + 50"}`

**Result:** "100"
```

**Skill 目录结构说明**：

```
./agent_skills/
└── {skill-name}/
    ├── SKILL.md              # 必需：Skill 定义文件（YAML Front Matter + Markdown）
    ├── scripts/              # 可选：脚本目录（Python/Shell 脚本）
    │   └── {script-name}.py
    ├── references/           # 可选：参考文件目录（API 文档、示例等）
    │   └── api-docs.md
    └── assets/               # 可选：资源与模板目录
        └── template.md
```

- **SKILL.md**：必需，包含 Skill 元数据（name、description、read_when）和执行说明
- **scripts/**：可选，存放 Skill 执行所需的脚本文件（Python、Shell 等）
- **references/**：可选，存放参考文件（API 文档、示例代码、规范等）
- **assets/**：可选，存放模板文件和资源文件（报告模板、配置文件等）

### 2.3 调用流程

#### 2.3.1 Agent 自主决策流程

```
用户提问
    │
    ▼
ReactAgent.processWithHistory()
    │
    ▼
LLM 分析问题意图（基于 ChatModel）
    │
    ├─ 匹配 Skill description → 调用 FileSystemSkillRegistry → 执行 Skill
    │
    └─ 匹配 Tool description → 调用 AgentToolRegistry → 执行 Tool
                                    │
                                    ▼
                            ┌───────┴───────┐
                            │               │
                    Local Tools      MCP Tools
                            │               │
                            ▼               ▼
                    直接执行         MCP Client 调用
```

#### 2.3.2 Skill 执行流程（以 calculator 为例）

```
用户："计算 100 * 25"
    │
    ▼
ReactAgent 分析意图
    │
    ▼
匹配 calculator Skill 的 description
    │
    ▼
读取 SKILL.md，解析执行指令
    │
    ▼
调用 execute Tool（Shell 命令）
    │
    ▼
执行：python scripts/calculator.py 100 * 25
    │
    ▼
返回结果："2500"
    │
    ▼
LLM 生成最终回答："计算结果是 2500"
```

---

## 3. 技术选型

### 3.1 依赖配置

| 组件 | 选型 | 说明 |
|------|------|------|
| **Agent Framework** | spring-ai-alibaba-agent-framework | 提供 ReactAgent 和 FileSystemSkillRegistry |
| **ChatModel** | spring-ai-starter-model-openai | 保留现有 DashScope 配置（OpenAI 兼容模式） |
| **Skill Registry** | FileSystemSkillRegistry（内置） | 自动扫描外部文件系统中的 Skill |
| **Skill 存储** | 外部文件系统路径 `./agent_skills` | 避免 JAR 打包加载问题 |

### 3.2 版本兼容性

| 依赖 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.4.4 | 现有版本 |
| Spring AI | 1.0.4 | 现有版本 |
| Spring AI Alibaba | 1.0.0.4+ | 新增（与 Spring AI 1.0.4 兼容） |
| JDK | 17 | 现有版本 |

---

## 4. 配置说明

### 4.1 pom.xml 改动

在 `company-rag-bootstrap/pom.xml` 中添加：

```xml
<!-- Spring AI Alibaba Agent Framework -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    <version>1.0.0.4</version>
</dependency>
```

### 4.2 application.yml 配置

在 `application-dev.yml` 中添加：

```yaml
spring:
  ai:
    alibaba:
      agent:
        # ReactAgent 配置
        enabled: true
        # Skill Registry 配置
        skill:
          registry:
            # 文件系统扫描路径
            file-system:
              enabled: true
              paths:
                - ./agent_skills
```

### 4.3 AgentConfig 配置类

创建 `company-rag-agent` 模块中的配置类：

```java
@Configuration
@EnableToolExecutions  // 启用工具执行
public class AgentConfig {

    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, 
                                  List<ToolCallbackProvider> toolProviders) {
        return ReactAgent.builder()
                .chatModel(chatModel)
                .toolCallbacks(toolProviders)
                .build();
    }
}
```

### 4.4 RagAgentService 改造

改造现有 `RagAgentService`：

```java
@Service
@Slf4j
public class RagAgentService {

    private final ReactAgent reactAgent;
    private final ToolCallRecorder recorder;

    public RagAgentService(ReactAgent reactAgent, 
                           ToolCallRecorder recorder) {
        this.reactAgent = reactAgent;
        this.recorder = recorder;
    }

    public AgentResult processWithHistory(List<Message> history, 
                                          String userMessage) {
        String traceId = recorder.generateTraceId();
        recorder.setTraceId(traceId);
        long requestStart = System.currentTimeMillis();
        
        log.info("[AGENT] traceId={}, userMsg=\"{}\", historySize={}", 
                traceId, userMessage, 
                history != null ? history.size() : 0);
        
        try {
            AgentResult result = reactAgent.processWithHistory(history, userMessage);
            
            // 输出结构化日志
            long totalMs = System.currentTimeMillis() - requestStart;
            List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
            String toolsSummary = records.stream()
                    .map(r -> String.format("%s(%dms,%s)", 
                            r.getToolName(), r.getDurationMs(), r.getStatus()))
                    .collect(Collectors.joining(", "));
            log.info("[AGENT] traceId={}, tools=[{}], total={}ms", 
                    traceId, toolsSummary, totalMs);
            
            return result;
            
        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - requestStart;
            log.error("[AGENT] traceId={}, total={}ms, error={}", 
                    traceId, totalMs, e.getMessage(), e);
            return new AgentResult("抱歉，系统繁忙，请稍后重试。", 
                    "error:" + e.getMessage());
        } finally {
            recorder.clearTraceId();
        }
    }
}
```

---

## 5. 数据模型

### 5.1 AgentResult（Agent 处理结果）

```java
public class AgentResult {
    private String content;           // Agent 回答内容
    private String traceId;           // 追踪 ID
    private List<ToolCallRecord> toolCalls; // 工具调用记录
    private Duration duration;        // 耗时
    private Instant timestamp;        // 时间戳
}
```

### 5.2 ToolCallRecord（工具调用记录）

```java
public class ToolCallRecord {
    private String toolName;          // 工具名称
    private String status;            // 状态：SUCCESS / FAILED
    private Duration durationMs;      // 耗时
    private String input;             // 输入参数
    private String output;            // 输出结果
    private String error;             // 错误信息（如果有）
}
```

---

## 6. 错误处理

### 6.1 异常类型

```java
// Skill 未找到
public class SkillNotFoundException extends RuntimeException {
    private String skillName;
}

// Skill 执行失败
public class SkillExecutionException extends RuntimeException {
    private String skillName;
    private Throwable cause;
}

// Tool 调用失败
public class ToolCallException extends RuntimeException {
    private String toolName;
    private Throwable cause;
}
```

### 6.2 重试策略

复用现有 Resilience4j 配置：

```java
@Configuration
public class AgentRetryConfig {

    @Bean
    public RetryTemplate agentExecutionRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2.0, 5000)
                .retryOn(SkillExecutionException.class)
                .retryOn(ToolCallException.class)
                .build();
    }
}
```

---

## 7. 可观测性

### 7.1 指标埋点

复用现有 Micrometer + Prometheus：

```java
@Component
public class AgentMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // Agent 调用次数
    private final Counter agentCallCounter;
    
    // Agent 执行成功率
    private final Gauge agentSuccessRate;
    
    // Agent 执行耗时
    private final Timer agentExecutionTimer;
    
    // Skill vs Tool 调用分布
    private final DistributionSummary skillToolDistribution;
    
    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.agentCallCounter = Counter.builder("agent.calls.total")
                .description("Agent 调用总次数")
                .register(meterRegistry);
        // ... 其他指标
    }
}
```

### 7.2 日志规范

```
[AGENT] traceId=xxx, userMsg="计算 100*25", historySize=0
[AGENT] traceId=xxx, tools=[calculator(150ms,SUCCESS)], total=200ms
```

### 7.3 Grafana 面板

**建议面板**：
1. Agent 调用趋势（按 Skill/Tool 分类）
2. Agent 执行成功率
3. Agent 执行耗时分布（P50、P90、P99）
4. Skill 调用 Top 10
5. Tool 调用 Top 10

---

## 8. 测试策略

### 8.1 单元测试

**测试范围**：
- `ReactAgent` 配置
- `FileSystemSkillRegistry` 扫描
- `RagAgentService` 调用逻辑

**示例**：
```java
@SpringBootTest
class RagAgentServiceTest {
    
    @Autowired
    private RagAgentService agentService;
    
    @Test
    void testProcessWithSkill() {
        AgentResult result = agentService.process("计算 100 * 25");
        
        assertTrue(result.getContent().contains("2500"));
        assertFalse(result.getToolCalls().isEmpty());
    }
    
    @Test
    void testProcessWithTool() {
        AgentResult result = agentService.process("查询数据库");
        
        assertTrue(result.getContent().isNotBlank());
    }
}
```

### 8.2 集成测试

**测试范围**：
- Skill 文件扫描和解析
- ReactAgent 自主决策
- Skill 和 Tool 协同工作

### 8.3 端到端测试

**测试范围**：
- 完整用户请求链路
- 错误处理和重试
- 性能测试

---

## 9. 实施计划

### 阶段 1：环境准备（0.5 天）

**目标**：添加依赖，验证兼容性

**任务**：
1. 在 `pom.xml` 中添加 `spring-ai-alibaba-agent-framework` 依赖
2. 运行 `mvn dependency:tree` 验证无冲突
3. 创建 `./agent_skills` 目录

**交付物**：
- ✅ 依赖添加成功
- ✅ 无版本冲突

### 阶段 2：配置 ReactAgent（0.5 天）

**目标**：配置 ReactAgent Bean

**任务**：
1. 创建 `AgentConfig` 配置类
2. 配置 `FileSystemSkillRegistry` 扫描路径
3. 在 `application.yml` 中添加 Skill Registry 配置

**交付物**：
- ✅ `AgentConfig.java` 创建完成
- ✅ ReactAgent Bean 可注入

### 阶段 3：改造 RagAgentService（1 天）

**目标**：改用 ReactAgent 实现 Agent 服务

**任务**：
1. 修改 `RagAgentService` 使用 ReactAgent
2. 保留现有日志和追踪逻辑
3. 适配 AgentResult 返回格式

**交付物**：
- ✅ `RagAgentService` 改造完成
- ✅ 单元测试通过

### 阶段 4：创建示例 Skill（0.5 天）

**目标**：验证 Skill 发现和调用

**任务**：
1. 创建 `./agent_skills/calculator` 目录
2. 编写 `SKILL.md` 和 `scripts/calculator.py`
3. 测试 Agent 调用 Skill

**交付物**：
- ✅ 示例 Skill 可被 Agent 发现
- ✅ Agent 能成功调用 Skill

### 阶段 5：集成测试（0.5 天）

**目标**：验证 Skill 和 Tool 协同工作

**任务**：
1. 编写集成测试
2. 测试 Agent 自主决策（Skill vs Tool）
3. 性能测试和优化

**交付物**：
- ✅ 集成测试通过
- ✅ 性能指标达标

### 总计：3 天

---

## 10. 风险与缓解

### 风险 1：Spring AI Alibaba 版本兼容性

**风险**：Spring AI Alibaba 与现有 Spring AI 1.0.4 版本不兼容

**缓解措施**：
- 选择与 Spring AI 1.0.4 兼容的 Spring AI Alibaba 版本（1.0.0.4+）
- 先在开发环境验证，再推广到生产

### 风险 2：Skill 执行安全

**风险**：执行外部 Python 脚本可能存在安全风险

**缓解措施**：
- 限制脚本访问权限（沙箱环境）
- 禁止访问文件系统、网络
- 使用 `subprocess` 隔离执行
- 代码审查和签名机制

### 风险 3：Agent 决策准确性

**风险**：Agent 可能错误选择 Skill 或 Tool

**缓解措施**：
- 优化 SKILL.md 的 description 和 read_when 字段
- 提供清晰的 Tool description
- 监控和日志分析，持续优化

---

## 11. 未来扩展

### 11.1 Skill 市场

- 类似 ClawHub 的社区生态
- 用户上传和分享 Skill
- 评分和评论机制

### 11.2 Skill 组合

- Skill 可以调用其他 Skill
- 支持 Skill 工作流编排
- 可视化编排界面

### 11.3 自适应学习

- 记录 Skill 执行历史
- 基于反馈优化执行策略
- 自动推荐 Skill

---

## 12. 总结

**核心价值**：
1. ✅ **简化架构**：废弃双轨制，使用 Spring AI Alibaba 内置能力
2. ✅ **最小改动**：保留现有 DashScope 配置，仅添加 Agent Framework
3. ✅ **平级调用**：Skill 和 Tool 对 Agent 平级，自主决策
4. ✅ **外部存储**：Skill 存放在外部文件系统，便于管理和更新

**下一步**：
- ✅ 评审设计文档
- ✅ 创建实施计划
- ✅ 开始阶段 1 开发

---

**文档版本**: 1.0  
**状态**: 待评审  
**审批**: 待用户确认
