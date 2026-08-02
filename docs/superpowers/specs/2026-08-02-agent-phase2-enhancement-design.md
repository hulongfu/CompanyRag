# CompanyRag Agent 第二阶段增强设计

**日期**: 2026-08-02
**作者**: AI Assistant
**状态**: 待审批
**关联**: 基于 `docs/superpowers/specs/2026-07-31-agent-rag-integration-design.md` 的后续演进

---

## 1. 背景与目标

### 1.1 背景

第一阶段已完成 RAG 工具化（`KnowledgeBaseTool`）和统一 Agent 入口（`/api/chat`），当前架构为纯 Agent 模式——LLM 通过 Spring AI Function Calling 自动决策调用哪个工具。

Router 模式、灰度路由、对比监控已确认不需要实现。

### 1.2 目标

第二阶段聚焦三个方向，提升 Agent 模式的实用性：

1. **工具描述完善** — 优化 `@Tool` description 和 `@ToolParam` description，加入调用示例，帮助 LLM 更准确地选择工具
2. **可解释性日志** — 记录 LLM 的工具调用链路（调了哪个工具、耗时、结果），便于调试和监控
3. **性能优化** — 引入 Caffeine 内存缓存，减少重复 RAG 查询的延迟

---

## 2. 工具描述完善

### 2.1 修改原则

- 每个工具的 `@Tool` description 增加适用场景说明和 2-3 个调用示例
- 明确工具边界（什么场景该用、什么场景不该用）
- `@ToolParam` description 增加参数示例值

### 2.2 修改详情

#### 2.2.1 KnowledgeBaseTool

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`

```java
@Tool(
    name = "searchKnowledgeBase",
    description = """
        在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。
        
        适用场景：
        - 查询 README、设计文档、使用手册、FAQ、流程规范、项目说明
        - 例如："怎么申请测试环境？"、"公司请假流程是什么？"、"项目架构是怎样的？"
        
        不适用场景（请调用其他工具）：
        - 代码检索 -> 使用 code_search
        - 数据库查询 -> 使用 database_query
        - API 文档 -> 使用 api_doc
        """
)
```

#### 2.2.2 DatabaseQueryTool

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java`

```java
@Tool(
    name = "database_query",
    description = """
        查询企业业务数据库，仅支持 SELECT 查询，返回表格格式结果。
        自动限制最多返回 100 行，禁止 DDL/DML 操作。
        
        适用场景：
        - 查询用户、订单、产品等业务数据
        - 例如："查询最近 7 天注册的用户"、"本月订单总数是多少？"、"库存低于 10 的产品有哪些？"
        
        不适用场景：
        - 知识库文档查询 -> 使用 searchKnowledgeBase
        """
)
```

#### 2.2.3 CodeSearchTool

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java`

```java
@Tool(
    name = "code_search",
    description = """
        在项目源码目录中搜索代码片段，支持按关键词和文件类型过滤。
        自动排除 target/ 和 .git/ 目录。
        
        适用场景：
        - 搜索函数定义、类定义、注释、配置
        - 例如："搜索 PaymentService 类"、"查找所有 @RestController 注解"、"搜索日志相关的配置"
        
        不适用场景：
        - 文档/README 查询 -> 使用 searchKnowledgeBase
        """
)
```

#### 2.2.4 ApiDocTool

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ApiDocTool.java`

```java
@Tool(
    name = "api_doc",
    description = """
        扫描 Spring MVC 端点生成 API 文档，返回当前系统的 REST 接口信息。
        支持按关键字过滤端点。
        
        适用场景：
        - 查看系统有哪些 REST 接口、接口的请求方法和路径
        - 例如："生成 API 文档"、"查看用户相关的接口"、"有哪些 GET 接口？"
        
        不适用场景：
        - 接口的详细业务逻辑 -> 使用 code_search 搜索对应 Controller
        """
)
```

---

## 3. 可解释性日志

### 3.1 设计思路

Spring AI 的 Function Calling 是黑盒——`ChatClient.prompt().call().content()` 内部完成了 LLM 推理→工具选择→工具执行→结果生成的全流程，中间决策过程不可见。

因此，可解释性日志采用**工具执行链路追踪**的方式：

1. 在 `RagAgentService` 层生成全局 traceId
2. 在每个工具入口记录：工具名、输入参数摘要、开始时间
3. 在每个工具出口记录：耗时、成功/失败
4. 在 `RagAgentService` 聚合输出结构化日志

### 3.2 组件设计

#### 3.2.1 增强 ToolCallRecorder

**位置**: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`

```java
/**
 * 工具调用记录
 */
@Data
@Builder
public class ToolCallRecord {
    private String traceId;
    private String toolName;
    private String inputSummary;    // 输入参数摘要（前 50 字符）
    private long startTimeMs;
    private long durationMs;
    private String status;          // success / failed
    private String errorMessage;    // 失败时的错误信息
}
```

在 `ToolCallRecorder` 中新增：
- `recordStart(traceId, toolName, args)` → 返回当前时间戳
- `recordEnd(traceId, toolName, startTime, status, error)` → 计算耗时，存入 ThreadLocal 列表
- `getAndClearRecords(traceId)` → 获取本次请求的所有工具调用记录

#### 3.2.2 修改 RagAgentService

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

在 `process()` 方法中：
1. 生成 traceId（UUID 前 8 位）
2. 记录整体开始时间
3. 调用 `chatClient.prompt()...content()`
4. 获取 `toolCallRecorder.getAndClearRecords(traceId)`
5. 输出结构化日志：

```
[AGENT] traceId=a1b2c3, userMsg="怎么申请测试环境？", tools=[searchKnowledgeBase(423ms,success)], total=1250ms
```

### 3.3 日志格式

```json
// 结构化日志（JSON 格式，便于日志系统解析）
{
  "type": "agent_decision",
  "traceId": "a1b2c3",
  "userMessage": "怎么申请测试环境？",
  "toolsCalled": [
    {"name": "searchKnowledgeBase", "durationMs": 423, "status": "success"}
  ],
  "totalDurationMs": 1250,
  "timestamp": "2026-08-02T10:30:00"
}
```

使用 Logstash/Markers 输出 JSON 格式，便于 ELK 等日志系统聚合分析。

---

## 4. 性能优化：Caffeine 缓存

### 4.1 设计思路

采用精确字符串匹配缓存，避免重复调用 RAG 引擎。

**适用场景**: 短时间内用户反复询问相同问题（如多人同时查看同一份文档）。

**不适用场景**: 语义相似但字符串不同的问题（如"怎么申请测试环境" vs "测试环境申请流程"）——语义缓存留到第三阶段。

### 4.2 缓存配置

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/config/RagCacheConfig.java`

```java
@Configuration
@EnableCaching
public class RagCacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("ragResults");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)  // 5 分钟过期
                .maximumSize(100)                        // 最多缓存 100 条
                .recordStats());                         // 记录命中率
        return cacheManager;
    }
}
```

### 4.3 缓存应用

**位置**: `KnowledgeBaseTool.java`

```java
@Cacheable(value = "ragResults", key = "#question + ':' + #topK")
public KnowledgeBaseResult searchKnowledgeBase(String question, Integer topK) {
    // 原有逻辑不变
}
```

### 4.4 依赖变更

**位置**: `company-rag-rag/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

> Spring Boot 3.x 的 `spring-boot-starter-cache` 会自动引入 Caffeine，无需指定版本。

---

## 5. 文件变更清单

### 新增文件

| 文件 | 模块 | 说明 |
|------|------|------|
| `RagCacheConfig.java` | company-rag-rag | Caffeine 缓存配置 |

### 修改文件

| 文件 | 模块 | 改动内容 |
|------|------|---------|
| `KnowledgeBaseTool.java` | company-rag-rag | 完善 @Tool description + @Cacheable 注解 |
| `DatabaseQueryTool.java` | company-rag-agent | 完善 @Tool description |
| `CodeSearchTool.java` | company-rag-agent | 完善 @Tool description |
| `ApiDocTool.java` | company-rag-agent | 完善 @Tool description |
| `ToolCallRecorder.java` | company-rag-common | 增加耗时记录、traceId 追踪、记录聚合 |
| `RagAgentService.java` | company-rag-agent | 增加 traceId 生成、日志聚合输出 |
| `pom.xml` (company-rag-rag) | company-rag-rag | 添加 spring-boot-starter-cache + caffeine |

---

## 6. 测试策略

### 6.1 单元测试

| 测试类 | 测试内容 | 优先级 |
|-------|---------|-------|
| `ToolCallRecorderTest` | 新增的 traceId、耗时记录、记录聚合功能 | 高 |
| `KnowledgeBaseToolCacheTest` | 缓存命中/未命中场景 | 中 |

### 6.2 验证方式

- 编译验证：`mvn clean compile`
- 单元测试：`mvn test`
- 人工验证：启动服务后，发送相同问题两次，观察第二次是否命中缓存

---

## 7. 实施计划

| 任务 | 预计耗时 | 前置依赖 |
|------|---------|---------|
| Task 1: 完善 4 个工具的 @Tool description | 0.5 天 | 无 |
| Task 2: 增强 ToolCallRecorder（耗时、traceId） | 0.5 天 | 无 |
| Task 3: 修改 RagAgentService（日志聚合） | 0.5 天 | Task 2 |
| Task 4: 添加 Caffeine 缓存配置 + 注解 | 0.5 天 | 无 |
| Task 5: 更新单元测试 | 0.5 天 | Task 2, 4 |
| Task 6: 编译验证 + 集成测试 | 0.5 天 | Task 1-5 |

**总计**: 3 天

---

## 8. 验收标准

- [ ] 4 个工具的 `@Tool` description 均包含场景说明和调用示例
- [ ] Agent 每次请求输出结构化日志，包含 traceId、调用工具列表、耗时
- [ ] 相同 RAG 问题 5 分钟内第二次请求命中缓存，响应时间 < 50ms
- [ ] 所有单元测试通过
- [ ] 编译无错误

---

**文档版本**: 1.0
**创建日期**: 2026-08-02
**关联设计**: `docs/superpowers/specs/2026-07-31-agent-rag-integration-design.md`