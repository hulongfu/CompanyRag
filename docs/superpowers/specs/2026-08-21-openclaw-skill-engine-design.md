# OpenClaw Skill Engine 设计文档

**日期**: 2026-08-21  
**分支**: feature/openclaw-skill-engine  
**状态**: 设计中  

---

## 1. 概述

### 1.1 项目背景

当前 CompanyRag 项目已具备基础的 Agent 能力（工具调用、会话记忆、RAG 检索），但缺少**Skill（技能）系统**。OpenClaw 哲学强调"执行力"，即 Agent 不仅能调用单一工具，还能执行复杂的 Skill（多步骤工作流、领域知识、思维链组合）。

### 1.2 目标

构建**双轨制 Skill 引擎**，支持两类 Skill：

1. **MCP Hosted Skill**：高频、稳定、逻辑固定的 SOP（标准作业程序），封装为 MCP Server
2. **Agent Native Skill**：创造性任务、需要反思拆解、强上下文耦合的任务，由 Agent 直接执行

### 1.3 核心原则

- **统一接口**：Agent 通过统一接口调用 Skill，无需关心底层实现
- **灵活扩展**：支持新增 Skill 类型，不影响现有架构
- **稳定可靠**：Skill 执行过程可追踪、可恢复、可重试
- **渐进式演进**：从简单到复杂，逐步完善

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      RagAgentService                            │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ 工具调用     │  │ Skill 调用   │  │ 会话记忆管理         │ │
│  │              │  │              │  │                      │ │
│  │ ToolRegistry │  │ SkillEngine  │  │ ChatMemory           │ │
│  └──────┬───────┘  └──────┬───────┘  └──────────────────────┘ │
│         │                 │                                     │
│         │                 │                                     │
└─────────┼─────────────────┼─────────────────────────────────────┘
          │                 │
          │                 │
┌─────────▼───────┐  ┌──────▼────────────────────────────────────┐
│ AgentToolRegistry│  │           SkillEngine                     │
│                 │  │                                           │
│ ┌─────────────┐ │  │  ┌─────────────────┐  ┌────────────────┐ │
│ │ Local Tool  │ │  │  │ SkillRegistry   │  │ SkillExecutor  │ │
│ │ MCP Tool    │ │  │  │                 │  │                │ │
│ └─────────────┘ │  │  │ - MCP_HOSTED    │  │ - 解析 SKILL.md│ │
└─────────────────┘  │  │ - AGENT_NATIVE  │  │ - 执行脚本     │ │
                     │  │                 │  │ - 组合工具     │ │
                     │  └─────────────────┘  └────────────────┘ │
                     └───────────┬───────────────────────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
      ┌─────────▼───────┐ ┌─────▼──────┐ ┌──────▼────────┐
      │ MCP Hosted Skill│ │ Agent      │ │ MCP Client    │
      │                 │ │ Native     │ │ (复用现有)    │
      │ company-rag-    │ │ Skill      │ │               │
      │ mcp-skill-xxx   │ │ Executor   │ │               │
      │                 │ │            │ │               │
      │ - SKILL.md      │ │ - 解析     │ │               │
      │ - scripts/      │ │ - 执行     │ │               │
      │ - references/   │ │ - 组合     │ │               │
      │ - assets/       │ │            │ │               │
      └─────────────────┘ └────────────┘ └───────────────┘
```

### 2.2 核心组件

#### 2.2.1 SkillEngine（Skill 引擎）

**职责**：
- 统一管理所有 Skill 的注册、发现、执行
- 提供统一接口：`executeSkill(skillName, context)`
- 根据 Skill 类型路由到不同的执行器

**接口定义**：
```java
public interface SkillEngine {
    /**
     * 执行 Skill
     * @param skillName Skill 名称
     * @param context 执行上下文（包含用户消息、会话历史、工具调用记录等）
     * @return Skill 执行结果
     */
    SkillResult executeSkill(String skillName, SkillContext context);
    
    /**
     * 注册 Skill
     */
    void registerSkill(SkillDefinition definition);
    
    /**
     * 获取所有已注册的 Skill
     */
    List<SkillDefinition> listSkills();
}
```

#### 2.2.2 SkillRegistry（Skill 注册中心）

**职责**：
- 管理所有 Skill 的元数据
- 支持按名称、标签、分类查询
- 支持动态注册和注销

**Skill 元数据结构**：
```java
public class SkillDefinition {
    private String name;              // Skill 名称
    private String displayName;       // 显示名称
    private String description;       // 描述
    private SkillType type;           // 类型：MCP_HOSTED / AGENT_NATIVE
    private List<String> tags;        // 标签
    private String version;           // 版本
    private String author;            // 作者
    private Map<String, Object> metadata; // 元数据
    
    // MCP_HOSTED 特有
    private String mcpServerId;       // MCP Server ID
    
    // AGENT_NATIVE 特有
    private String skillMdPath;       // SKILL.md 文件路径
    private String scriptsPath;       // scripts 目录路径
    private String referencesPath;    // references 目录路径
    private String assetsPath;        // assets 目录路径
}
```

#### 2.2.3 SkillExecutor（Skill 执行器）

**职责**：
- 解析 `SKILL.md` 文件
- 执行 Python 脚本
- 组合多个工具调用
- 管理执行状态（成功、失败、重试）

**执行流程**：
```
1. 加载 SKILL.md → 解析提示词、思维链、执行步骤
2. 准备上下文 → 用户消息、会话历史、工具调用记录
3. 执行脚本 → 调用 Python 脚本（可选）
4. 组合工具 → 按 SKILL.md 定义的流程调用工具
5. 返回结果 → 结构化结果 + 执行日志
```

#### 2.2.4 McpSkillClient（MCP Skill 客户端）

**职责**：
- 复用现有 `company-rag-mcp-client` 模块
- 调用 MCP Hosted Skill
- 处理协议转换

---

## 3. Skill 结构设计

### 3.1 Skill 目录结构

```
agent_skills/
└── {skill-name}/
    ├── SKILL.md              # 必需：Skill 定义文件
    ├── scripts/              # 可选：Python 脚本
    │   ├── step1.py
    │   └── step2.py
    ├── references/           # 可选：参考文件
    │   ├── api-docs.md
    │   └── examples.md
    └── assets/               # 可选：资源模板
        └── template.md
```

### 3.2 SKILL.md 文件格式

```markdown
---
name: code-review
displayName: 代码审查
description: 对提交的代码进行自动化审查，提供改进建议
version: 1.0.0
author: Your Name
tags: [code, review, quality]
type: AGENT_NATIVE  # 或 MCP_HOSTED

# MCP_HOSTED 特有配置（仅当 type=MCP_HOSTED 时）
mcp:
  server: company-rag-mcp-skill-code-review
  endpoint: /review

# AGENT_NATIVE 特有配置（仅当 type=AGENT_NATIVE 时）
execution:
  # 执行步骤（可选，如果不指定则使用默认流程）
  steps:
    - name: parse_code
      script: scripts/parse_code.py
      description: 解析代码结构
      
    - name: analyze_quality
      script: scripts/analyze_quality.py
      description: 分析代码质量
      
    - name: generate_report
      prompt: assets/report-template.md
      description: 生成审查报告

# 输入参数定义
inputs:
  - name: code
    type: string
    required: true
    description: 待审查的代码
    
  - name: language
    type: string
    required: false
    default: java
    description: 编程语言

# 输出格式定义
outputs:
  - name: issues
    type: array
    description: 发现的问题列表
    
  - name: suggestions
    type: array
    description: 改进建议列表
    
  - name: score
    type: number
    description: 代码质量评分（0-100）
---

# Skill 核心提示词（Prompt）

你是一个专业的代码审查专家。请按照以下步骤执行代码审查：

## 思维链（Chain of Thought）

1. **理解代码**：首先阅读代码，理解其功能和结构
2. **识别问题**：检查代码中的潜在问题（性能、安全、可读性等）
3. **提供建议**：针对每个问题提供具体的改进建议
4. **评分**：基于问题严重程度给出总体评分

## 执行流程

1. 调用 `parse_code` 脚本解析代码结构
2. 调用 `analyze_quality` 脚本分析代码质量
3. 使用 `report-template.md` 模板生成审查报告
4. 返回结构化的审查结果

## 注意事项

- 重点关注：安全性、性能、可读性、可维护性
- 避免过于苛刻的建议
- 提供具体的代码示例
```

### 3.3 Skill 类型对比

| 维度 | MCP_HOSTED | AGENT_NATIVE |
|------|-----------|--------------|
| **适用场景** | 高频、稳定、逻辑固定的 SOP | 创造性、反思性、强上下文耦合 |
| **部署方式** | 独立 MCP Server | Agent 内部执行 |
| **执行方式** | MCP 协议调用 | 解析 SKILL.md + 执行脚本 |
| **上下文传递** | 请求 - 响应模式 | 直接访问 Agent 会话历史 |
| **扩展性** | 可独立部署、扩展 | 依赖 Agent 运行环境 |
| **开发成本** | 中（需要适配 MCP 协议） | 低（直接编写 SKILL.md） |
| **示例** | 代码审查、文档生成、数据报表 | 文章创作、基于对话总结、创意写作 |

---

## 4. 执行流程

### 4.1 Agent 调用 Skill 的完整流程

```
用户提问
    │
    ▼
RagAgentService.processWithHistory()
    │
    ▼
LLM 分析问题意图
    │
    ├── 需要调用工具 → AgentToolRegistry → Tool Execution
    │
    └── 需要执行 Skill → SkillEngine.executeSkill()
                            │
                            ▼
                    SkillRegistry.lookup(skillName)
                            │
                            ▼
                    ┌───────┴───────┐
                    │               │
            MCP_HOSTED      AGENT_NATIVE
                    │               │
                    ▼               ▼
            McpSkillClient    SkillExecutor
                    │               │
                    ▼               ▼
            MCP Server      解析 SKILL.md
            (独立部署)          │
                            ▼
                    执行 Python 脚本
                            │
                            ▼
                    组合工具调用
                            │
                            ▼
                    返回 SkillResult
```

### 4.2 AGENT_NATIVE Skill 执行流程

```
SkillExecutor.execute(skillName, context)
    │
    ├─ 1. 加载 Skill 定义
    │     └─ SkillRegistry.getDefinition(skillName)
    │
    ├─ 2. 解析 SKILL.md
    │     ├─ 解析 Front Matter（元数据）
    │     ├─ 解析提示词
    │     └─ 解析执行步骤
    │
    ├─ 3. 准备执行上下文
    │     ├─ 用户消息
    │     ├─ 会话历史（从 RagAgentService 获取）
    │     ├─ 工具调用记录
    │     └─ 外部资源（references/ 目录）
    │
    ├─ 4. 执行步骤（按 SKILL.md 定义）
    │     ├─ 执行 Python 脚本（scripts/ 目录）
    │     ├─ 调用工具（通过 AgentToolRegistry）
    │     └─ 渲染模板（assets/ 目录）
    │
    └─ 5. 返回结果
          ├─ 结构化数据
          ├─ 执行日志
          └─ 引用资源
```

### 4.3 MCP_HOSTED Skill 执行流程

```
McpSkillClient.execute(skillName, context)
    │
    ├─ 1. 查找 MCP Server
    │     └─ McpServerRegistry.getServer(skillName)
    │
    ├─ 2. 构建 MCP 请求
    │     ├─ JSON-RPC 2.0 格式
    │     ├─ 方法名：skill/execute
    │     └─ 参数：context（序列化）
    │
    ├─ 3. 发送请求（WebClient 异步调用）
    │     └─ POST /mcp/{serverId}/execute
    │
    ├─ 4. 等待响应
    │     ├─ 超时处理（默认 30 秒）
    │     └─ 错误处理（重试、降级）
    │
    └─ 5. 解析响应
          ├─ 成功：返回 SkillResult
          └─ 失败：抛出 SkillExecutionException
```

---

## 5. 数据模型

### 5.1 SkillContext（Skill 执行上下文）

```java
public class SkillContext {
    private String conversationId;      // 会话 ID
    private String userId;              // 用户 ID
    private String tenantId;            // 租户 ID
    private String userMessage;         // 用户消息
    private List<Message> history;      // 会话历史
    private List<ToolCallRecord> toolCallRecords; // 工具调用记录
    private Map<String, Object> variables; // 自定义变量
    private Instant timestamp;          // 时间戳
}
```

### 5.2 SkillResult（Skill 执行结果）

```java
public class SkillResult {
    private boolean success;            // 是否成功
    private String skillName;           // Skill 名称
    private Object data;                // 结构化数据
    private String message;             // 人类可读的消息
    private List<Citation> citations;   // 引用来源
    private ExecutionLog log;           // 执行日志
    private Duration duration;          // 执行耗时
    private Instant timestamp;          // 完成时间
}
```

### 5.3 ExecutionLog（执行日志）

```java
public class ExecutionLog {
    private List<StepLog> steps;        // 步骤日志
    private List<String> errors;        // 错误列表
    private Map<String, Object> metadata; // 元数据
}

public class StepLog {
    private String stepName;            // 步骤名称
    private String status;              // 状态：SUCCESS / FAILED / SKIPPED
    private String message;             // 步骤消息
    private Duration duration;          // 步骤耗时
    private Object output;              // 步骤输出
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
    private ExecutionLog log;
}

// Skill 定义无效
public class InvalidSkillDefinitionException extends RuntimeException {
    private String skillName;
    private List<String> errors;
}

// MCP 调用失败
public class McpSkillCallException extends RuntimeException {
    private String serverId;
    private String errorCode;
}
```

### 6.2 重试策略

```java
@Configuration
public class SkillRetryConfig {
    
    @Bean
    public RetryTemplate skillExecutionRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2.0, 5000) // 初始 1 秒，指数退避，最大 5 秒
                .retryOn(SkillExecutionException.class)
                .build();
    }
}
```

### 6.3 降级策略

```java
@Service
public class SkillFallbackHandler {
    
    /**
     * Skill 执行失败时的降级处理
     */
    public SkillResult fallback(String skillName, SkillContext context, Throwable ex) {
        log.error("Skill 执行失败：{}", skillName, ex);
        
        // 1. 记录失败
        recordFailure(skillName, context, ex);
        
        // 2. 尝试降级
        if (ex instanceof McpSkillCallException) {
            // MCP 调用失败，尝试切换到 AGENT_NATIVE 模式（如果有）
            return tryNativeFallback(skillName, context);
        }
        
        // 3. 返回友好错误消息
        return SkillResult.failure(skillName, "Skill 执行失败，请稍后重试");
    }
}
```

---

## 7. 可观测性

### 7.1 指标埋点

```java
@Component
public class SkillMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // Skill 调用次数
    private final Counter skillCallCounter;
    
    // Skill 执行成功率
    private final Gauge skillSuccessRate;
    
    // Skill 执行耗时
    private final Timer skillExecutionTimer;
    
    // Skill 类型分布
    private final DistributionSummary skillTypeDistribution;
    
    public SkillMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.skillCallCounter = Counter.builder("skill.calls.total")
                .description("Skill 调用总次数")
                .register(meterRegistry);
        // ... 其他指标
    }
    
    public void recordSkillCall(String skillName, SkillType type) {
        skillCallCounter.increment();
        // ... 记录其他指标
    }
}
```

### 7.2 追踪日志

```java
@Slf4j
@Service
public class SkillEngineImpl implements SkillEngine {
    
    @Override
    public SkillResult executeSkill(String skillName, SkillContext context) {
        log.info("开始执行 Skill: name={}, type={}, conversationId={}", 
                skillName, definition.getType(), context.getConversationId());
        
        try {
            // ... 执行逻辑
            
            log.info("Skill 执行成功：name={}, duration={}ms", skillName, duration);
            return result;
            
        } catch (Exception e) {
            log.error("Skill 执行失败：name={}, error={}", skillName, e.getMessage());
            throw e;
        }
    }
}
```

### 7.3 Grafana 面板

**建议面板**：
1. Skill 调用趋势（按类型、按名称）
2. Skill 执行成功率（按类型、按名称）
3. Skill 执行耗时分布（P50、P90、P99）
4. Skill 错误类型分布
5. MCP Hosted vs Agent Native 调用对比

---

## 8. 测试策略

### 8.1 单元测试

**测试范围**：
- `SkillEngineImpl`：执行逻辑
- `SkillExecutor`：脚本执行、工具组合
- `McpSkillClient`：MCP 协议调用
- `SkillRegistry`：注册和查询

**示例**：
```java
@SpringBootTest
class SkillEngineTest {
    
    @Autowired
    private SkillEngine skillEngine;
    
    @Test
    void testExecuteNativeSkill() {
        SkillContext context = buildTestContext();
        SkillResult result = skillEngine.executeSkill("code-review", context);
        
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
    }
    
    @Test
    void testExecuteMcpSkill() {
        SkillContext context = buildTestContext();
        SkillResult result = skillEngine.executeSkill("data-report", context);
        
        assertTrue(result.isSuccess());
    }
}
```

### 8.2 集成测试

**测试范围**：
- 完整的 Skill 执行流程
- MCP Server 集成
- Python 脚本执行

### 8.3 端到端测试

**测试范围**：
- Agent 调用 Skill 的完整链路
- 错误处理和降级
- 性能测试

---

## 9. 实施计划

### 阶段 1：核心框架（3-5 天）

**目标**：实现 SkillEngine 基础框架

**任务**：
1. 创建 `company-rag-skill` 模块
2. 实现 `SkillEngine` 接口
3. 实现 `SkillRegistry`
4. 实现 `SkillExecutor`（基础版本）
5. 编写单元测试

**交付物**：
- `SkillEngine` 可运行
- 支持 AGENT_NATIVE Skill 执行
- 单元测试覆盖率 > 80%

### 阶段 2：MCP 集成（2-3 天）

**目标**：集成 MCP Hosted Skill

**任务**：
1. 扩展 `company-rag-mcp-client` 支持 Skill 调用
2. 实现 `McpSkillClient`
3. 创建示例 MCP Skill Server
4. 编写集成测试

**交付物**：
- MCP Hosted Skill 可调用
- 示例 Skill：`code-review`

### 阶段 3：完善功能（3-5 天）

**目标**：完善错误处理、可观测性、文档

**任务**：
1. 实现错误处理和重试机制
2. 添加指标埋点和 Grafana 面板
3. 编写 Skill 开发文档
4. 创建 Skill 模板

**交付物**：
- 完整的错误处理
- 可观测性面板
- Skill 开发指南

### 阶段 4：试点应用（2-3 天）

**目标**：在真实场景中验证

**任务**：
1. 创建 2-3 个实际 Skill
2. 集成到 `RagAgentService`
3. 用户测试和反馈
4. 性能优化

**交付物**：
- 3 个生产级 Skill
- 性能优化报告

---

## 10. 风险与缓解

### 风险 1：Python 脚本执行安全

**风险**：执行用户提供的 Python 脚本可能存在安全风险

**缓解措施**：
- 限制脚本访问权限（沙箱环境）
- 禁止访问文件系统、网络
- 使用 `subprocess` 隔离执行
- 代码审查和签名机制

### 风险 2：Skill 执行性能

**风险**：复杂 Skill 执行耗时长，影响用户体验

**缓解措施**：
- 异步执行（SSE 流式返回进度）
- 超时控制（默认 30 秒）
- 缓存机制（相同输入返回缓存结果）
- 性能监控和告警

### 风险 3：Skill 管理复杂度

**风险**：Skill 数量增长后管理困难

**缓解措施**：
- Skill 分类和标签
- 版本管理
- 依赖管理
- Skill 市场（未来）

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
1. ✅ **双轨制架构**：兼顾稳定性和灵活性
2. ✅ **统一接口**：Agent 无感知调用
3. ✅ **OpenClaw 哲学**：强化执行力，支持复杂工作流
4. ✅ **渐进式演进**：从简单到复杂，逐步完善

**下一步**：
- 评审设计文档
- 创建实施计划
- 开始阶段 1 开发

---

**文档版本**: 1.0  
**状态**: 待评审  
**审批**: 待用户确认
