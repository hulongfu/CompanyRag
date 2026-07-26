# Rerank 性能优化设计文档

**创建日期：** 2026-07-26  
**作者：** CompanyRag Team  
**状态：** 待评审  
**优先级：** P0

---

## 1. 概述

### 1.1 优化目标

当前 RAG 系统中的重排序（Rerank）功能使用 LLM（通义千问）进行 Cross-Encoder 评分，存在以下问题：
- **延迟高**：每次重排序需要 500-1000ms
- **成本高**：每次调用消耗约 500 tokens
- **维护复杂**：需要构建复杂的 Prompt

本次优化目标：
- ✅ **降低延迟**：将重排序耗时降至 200ms 以内
- ✅ **降低成本**：消除 Token 消耗
- ✅ **提升扩展性**：支持多厂商模型切换

### 1.2 技术方案

采用硅基流动的 **BAAI/bge-reranker-v2-m3** 模型：
- 免费 API，零 Token 成本
- 轻量级多语言模型，推理速度快
- 专为重排序任务设计，精度高

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    RagSearchServiceImpl                      │
│                           ↓                                  │
│                    CrossEncoderReranker                      │
│                           ↓                                  │
│                    ┌─────────────┐                           │
│                    │ RerankModel │ (接口)                    │
│                    └──────┬──────┘                           │
│                           ↓                                  │
│              SiliconFlowRerankClient (实现)                   │
│                           ↓                                  │
│                    RestClient 调用                            │
│                           ↓                                  │
│         https://api.siliconflow.cn/v1/rerank                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件

| 组件 | 职责 | 依赖 |
|------|------|------|
| `RerankModel` | 重排序模型标准接口 | 无 |
| `SiliconFlowRerankClient` | 实现硅基流动 API 调用 | `RerankConfigProperties` |
| `RerankConfig` | 配置类，从 `spring.ai.openai.rerank` 读取配置 | 无 |
| `CrossEncoderReranker` | 业务编排，使用 `RerankModel` | `RerankModel` |

### 2.3 模块依赖

```
company-rag-rag
├── model/
│   ├── RerankResponse.java      # 响应对象
│   ├── RerankResult.java        # 结果项
│   └── RerankMeta.java          # 元数据
├── rerank/
│   ├── RerankModel.java         # 接口定义
│   ├── SiliconFlowRerankClient.java  # 实现
│   └── CrossEncoderReranker.java     # 业务编排（改造）
└── config/
    └── RerankConfig.java        # 配置类
```

---

## 3. 配置设计

### 3.1 配置结构调整

**调整前：**
```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen3.7-max-2026-05-20
      embedding:
        api-key: ${SILICONFLOW_API_KEY}
        base-url: https://api.siliconflow.cn
        options:
          model: BAAI/bge-large-zh-v1.5
```

**调整后：**
```yaml
spring:
  ai:
    openai:
      chat:
        api-key: ${DASHSCOPE_API_KEY}
        base-url: https://dashscope.aliyuncs.com/compatible-mode
        options:
          model: qwen3.7-max-2026-05-20
          temperature: 0.7
      embedding:
        api-key: ${SILICONFLOW_API_KEY}
        base-url: https://api.siliconflow.cn
        options:
          model: BAAI/bge-large-zh-v1.5
      rerank:
        api-key: ${SILICONFLOW_API_KEY}
        base-url: https://api.siliconflow.cn
        options:
          model: BAAI/bge-reranker-v2-m3
```

### 3.2 配置类结构

```java
@Configuration
@ConfigurationProperties(prefix = "spring.ai.openai")
public class OpenAiConfigProperties {
    private ChatConfig chat;
    private EmbeddingConfig embedding;
    private RerankConfig rerank;
    
    // getters/setters
    
    public static class ChatConfig {
        private String apiKey;
        private String baseUrl;
        private Options options;
    }
    
    public static class EmbeddingConfig {
        private String apiKey;
        private String baseUrl;
        private Options options;
    }
    
    public static class RerankConfig {
        private String apiKey;
        private String baseUrl;
        private Options options;
    }
}
```

---

## 4. 接口设计

### 4.1 RerankModel 接口

```java
/**
 * 重排序模型接口
 */
public interface RerankModel {
    /**
     * 对文档列表进行重排序
     * 
     * @param query 查询文本
     * @param documents 待排序的文档列表
     * @param topN 返回前 N 个结果
     * @return 重排序结果
     */
    RerankResponse rerank(String query, List<String> documents, int topN);
}
```

### 4.2 响应对象

```java
/**
 * 重排序响应
 */
public record RerankResponse(
    String id,                    // 请求 ID
    List<RerankResult> results,   // 结果列表
    RerankMeta meta               // 元数据
) {}

/**
 * 重排序结果项
 */
public record RerankResult(
    int index,                    // 原始文档索引
    String document,              // 文档内容
    double relevanceScore         // 相关性分数 (0-1)
) {}

/**
 * 元数据
 */
public record RerankMeta(
    RerankTokens tokens,
    RerankBilledUnits billedUnits
) {}

/**
 * Token 统计
 */
public record RerankTokens(
    int inputTokens,
    int outputTokens,
    int imageTokens
) {}

/**
 * 计费单位
 */
public record RerankBilledUnits(
    int inputTokens,
    int outputTokens,
    int imageTokens,
    int searchUnits,
    int classifications
) {}
```

---

## 5. 实现设计

### 5.1 SiliconFlowRerankClient

**核心职责：**
- 实现 `RerankModel` 接口
- 使用 `RestClient` 调用硅基流动 API
- 处理认证、超时、错误响应
- 转换 API 响应为 `RerankResponse`

**API 调用示例：**
```java
POST https://api.siliconflow.cn/v1/rerank
Headers:
  Authorization: Bearer {api-key}
  Content-Type: application/json
Body:
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "用户查询",
  "documents": ["文档 1", "文档 2", ...],
  "return_documents": true,
  "top_n": 10
}
```

**关键代码结构：**
```java
@Component
public class SiliconFlowRerankClient implements RerankModel {
    
    private final RestClient restClient;
    private final RerankConfigProperties config;
    
    @Override
    public RerankResponse rerank(String query, List<String> documents, int topN) {
        // 1. 构建请求体
        RerankRequest request = buildRequest(query, documents, topN);
        
        // 2. 调用 API
        return restClient.post()
                .uri("/v1/rerank")
                .body(request)
                .retrieve()
                .body(RerankResponse.class);
    }
    
    private RerankRequest buildRequest(String query, List<String> documents, int topN) {
        return new RerankRequest(
            config.getModel(),
            query,
            documents,
            true,  // return_documents
            topN
        );
    }
}
```

### 5.2 CrossEncoderReranker 改造

**改造前：**
```java
@Slf4j
@Component
public class CrossEncoderReranker {
    private final OpenAiChatModel chatModel;  // ❌ 使用 LLM
    
    public List<RagResult.ChunkResult> rerank(...) {
        // 构建复杂 Prompt
        // 调用 LLM
        // 解析响应
    }
}
```

**改造后：**
```java
@Slf4j
@Component
public class CrossEncoderReranker {
    private final RerankModel rerankModel;  // ✅ 使用专用接口
    
    @CircuitBreaker(name = "rerank", fallbackMethod = "rerankFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "rerankFallback")
    public List<RagResult.ChunkResult> rerank(...) {
        // 1. 提取文档内容
        List<String> documents = chunks.stream()
                .map(RagResult.ChunkResult::getContent)
                .collect(Collectors.toList());
        
        // 2. 调用 RerankModel
        RerankResponse response = rerankModel.rerank(query, documents, topK);
        
        // 3. 将结果映射回 ChunkResult
        return mapToChunkResults(response, chunks);
    }
    
    /**
     * 降级方法：按 finalScore 排序返回
     */
    public List<RagResult.ChunkResult> rerankFallback(...) {
        log.warn("重排序服务降级 | 原因：{}", t.getMessage());
        return chunks.stream()
                .sorted(Comparator.comparingDouble(RagResult.ChunkResult::getFinalScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }
}
```

---

## 6. 数据流

### 6.1 正常流程

```
用户查询
  ↓
RagSearchServiceImpl.search(query)
  ↓
hybridRetrieve(query) → 向量检索 + 关键词融合 → List<ChunkResult>
  ↓
reranker.rerank(query, chunks, topK)
  ↓
RerankModel.rerank(query, documents, topN)
  ↓
SiliconFlowRerankClient
  ↓
RestClient 调用硅基流动 API
  ↓
返回 RerankResponse
  ↓
映射为 List<ChunkResult>（设置 rerankScore，更新 finalScore）
  ↓
按 finalScore 降序排列，返回 topK
  ↓
构建 Prompt → 调用 LLM 生成答案
  ↓
返回 R<SearchResult>
```

### 6.2 降级流程

```
SiliconFlowRerankClient 调用失败
  ↓
触发 CircuitBreaker 熔断
  ↓
执行 rerankFallback 方法
  ↓
按 finalScore 降序排列（finalScore = vectorWeight * vectorScore + keywordWeight * keywordScore）
  ↓
返回 topK
  ↓
记录降级日志：WARN "重排序服务降级 | 原因：xxx"
  ↓
继续执行后续流程
```

---

## 7. 错误处理

### 7.1 异常分类

| 异常类型 | HTTP 状态码 | 处理方式 | 日志级别 |
|----------|------------|----------|----------|
| 网络异常 | - | 降级为跳过重排序 | WARN |
| 认证失败 | 401 | 降级，记录 ERROR | ERROR |
| 模型不存在 | 404 | 降级，记录 ERROR | ERROR |
| 参数错误 | 400 | 降级，记录 ERROR | ERROR |
| 限流 | 429 | 降级，记录 WARN | WARN |
| 服务端错误 | 500/503 | 降级，记录 ERROR | ERROR |

### 7.2 降级行为

```java
public List<RagResult.ChunkResult> rerankFallback(String query, List<RagResult.ChunkResult> chunks,
                                                   int topK, Throwable t) {
    log.warn("重排序服务降级 | query={} | 候选数={} | 原因：{}", 
             query, chunks.size(), t.getMessage());
    
    // 按 finalScore 降序排列
    return chunks.stream()
            .sorted(Comparator.comparingDouble(RagResult.ChunkResult::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
}
```

---

## 8. 测试策略

### 8.1 单元测试

| 测试类 | 测试内容 |
|--------|----------|
| `SiliconFlowRerankClientTest` | Mock RestClient 测试 API 调用逻辑 |
| `RerankConfigTest` | 验证配置绑定正确 |
| `CrossEncoderRerankerTest` | 集成测试重排序流程 |
| `RerankModelIntegrationTest` | 使用真实 API 测试端到端流程 |

### 8.2 测试用例

**正常场景：**
- ✅ 输入 10 个文档，返回重排序后的 top 5
- ✅ 验证 relevanceScore 在 0-1 范围内
- ✅ 验证响应时间 < 200ms

**边界场景：**
- ✅ 空文档列表 → 返回空列表
- ✅ 文档数 < topN → 返回所有文档
- ✅ 单个文档 → 正常返回

**异常场景：**
- ✅ API 调用超时 → 触发降级
- ✅ API Key 无效 → 触发降级
- ✅ 网络异常 → 触发降级

### 8.3 集成测试

```java
@IntegrationTest
class SiliconFlowRerankClientIntegrationTest {
    
    @Autowired
    private RerankModel rerankModel;
    
    @Test
    void testRerank_withRealApi() {
        String query = "什么是微服务架构？";
        List<String> documents = List.of(
            "微服务是一种架构风格...",
            "单体架构是传统的软件架构...",
            "容器技术促进了微服务的发展..."
        );
        
        RerankResponse response = rerankModel.rerank(query, documents, 3);
        
        // 验证响应结构
        assertThat(response.results()).hasSize(3);
        assertThat(response.results().get(0).relevanceScore()).isBetween(0.0, 1.0);
        
        // 验证性能
        // 响应时间应 < 200ms
    }
}
```

---

## 9. 预期收益

### 9.1 性能对比

| 指标 | 优化前（LLM） | 优化后（SiliconFlow） | 改善 |
|------|--------------|----------------------|------|
| 重排序延迟 | 500-1000ms | 50-150ms | ⬇️ 80% |
| Token 成本 | ~500 tokens/次 | 0 | ⬇️ 100% |
| API 成本 | ¥0.02/次 | ¥0 | ⬇️ 100% |
| 代码复杂度 | 高（Prompt 构建） | 低（直接 API 调用） | ⬇️ 50% |

### 9.2 质量提升

- ✅ **精度提升**：专用 Rerank 模型比通用 LLM 更准确
- ✅ **稳定性提升**：减少 LLM 调用链路的故障点
- ✅ **可维护性提升**：代码结构更清晰，职责更单一

---

## 10. 实施计划

### 10.1 任务分解

1. **配置类调整** - 重构 `OpenAiConfigProperties`，将 apiKey/baseUrl 下移到各子配置
2. **定义 RerankModel 接口** - 创建接口和响应对象
3. **实现 SiliconFlowRerankClient** - 实现 API 调用逻辑
4. **创建 RerankConfig 配置类** - 从配置文件读取配置
5. **改造 CrossEncoderReranker** - 使用 RerankModel 替代 LLM
6. **更新配置文件** - 在 application-dev.yml 中添加 rerank 配置
7. **编写单元测试** - 覆盖正常、边界、异常场景
8. **编写集成测试** - 使用真实 API 验证端到端流程
9. **性能测试** - 验证响应时间 < 200ms
10. **文档更新** - 更新 README 和 API 文档

### 10.2 依赖关系

```
任务 1 (配置类) → 任务 2 (接口) → 任务 3 (实现)
                              ↓
                        任务 4 (配置类)
                              ↓
                        任务 5 (改造)
                              ↓
                        任务 6 (配置文件)
                              ↓
                        任务 7-9 (测试)
                              ↓
                        任务 10 (文档)
```

### 10.3 预计工期

| 阶段 | 工期 | 产出物 |
|------|------|--------|
| 配置重构 | 0.5 天 | OpenAiConfigProperties 重构完成 |
| 接口实现 | 1 天 | RerankModel + SiliconFlowRerankClient |
| 业务改造 | 0.5 天 | CrossEncoderReranker 改造完成 |
| 测试编写 | 1 天 | 单元测试 + 集成测试 |
| 验证部署 | 0.5 天 | 性能测试报告 |
| **总计** | **3.5 天** | - |

---

## 11. 风险与应对

### 11.1 技术风险

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| 硅基流动 API 不稳定 | 低 | 高 | 熔断降级 + 本地缓存 |
| 配置重构影响现有功能 | 中 | 中 | 充分测试 + 回滚方案 |
| 响应时间未达标 | 低 | 中 | 优化网络配置 + 调整超时策略 |

### 11.2 回滚方案

如优化后出现问题，可通过以下方式快速回滚：
1. 修改 `CrossEncoderReranker`，切换回 LLM 实现（代码保留）
2. 注释掉 `spring.ai.openai.rerank` 配置
3. 重启应用即可恢复原状

---

## 12. 验收标准

### 12.1 功能验收

- ✅ Rerank API 调用正常，返回正确的排序结果
- ✅ 降级策略生效，API 失败时按 finalScore 排序返回
- ✅ 配置可从 `application.yml` 正确读取
- ✅ 支持多租户场景（不同租户使用相同配置）

### 12.2 性能验收

- ✅ 重排序响应时间 P95 < 200ms
- ✅ 重排序响应时间 P99 < 500ms
- ✅ 并发 10 QPS 下无错误
- ✅ 熔断机制正常工作

### 12.3 质量验收

- ✅ 单元测试覆盖率 ≥ 80%
- ✅ 集成测试通过
- ✅ 代码审查通过
- ✅ 无严重/警告级别代码问题

---

## 13. 后续优化方向

### 13.1 短期优化

- **缓存优化**：对相似 query 缓存 Rerank 结果
- **批量优化**：合并多个查询的 Rerank 请求
- **监控告警**：增加 Rerank 服务的监控指标

### 13.2 长期优化

- **多模型支持**：支持配置切换不同厂商的 Rerank 模型
- **本地部署**：使用 ONNX Runtime 本地部署模型，零网络延迟
- **自适应策略**：根据 query 复杂度动态选择是否 Rerank

---

## 14. 参考文档

- [硅基流动 Rerank API 文档](https://docs.siliconflow.cn/api-reference/rerank)
- [BAAI/bge-reranker-v2-m3 模型介绍](https://huggingface.co/BAAI/bge-reranker-v2-m3)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Resilience4j 熔断器文档](https://resilience4j.readme.io/docs)

---

**文档结束**
