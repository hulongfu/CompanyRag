# RAG 核心链路

## 概述

RAG（Retrieval-Augmented Generation）核心链路包括：混合检索 → 重排序 → Prompt 构建 → LLM 生成 → 缓存 → 指标记录。

## 检索流程

`RagSearchServiceImpl.search()` 完整流程：

1. **缓存检查** — 以 `tenantId:query` 为 key 查询 Redis 缓存
2. **混合检索** — 向量检索（PGVector HNSW）+ 关键词检索（简单词匹配），加权融合
3. **重排序** — Cross-Encoder Rerank 精排 Top-K
4. **Prompt 构建** — 拼接参考资料，调用 `PromptTemplate.buildChatPrompt()`
5. **LLM 调用** — 通义千问 qwen-max 生成回答
6. **对话记录** — 保存至 `rag_session` 表（异步容错）
7. **写入缓存** — 写入 Redis，TTL 5 分钟（热点 30 分钟）
8. **记录指标** — Prometheus 埋点

## 混合检索

`hybridRetrieve()` 方法：

```
向量检索 (PGVector HNSW, topK, similarityThreshold=0.5)
    ↓
关键词匹配 (简单词频评分)
    ↓
加权融合: finalScore = vectorWeight * vectorScore + keywordWeight * keywordScore
    ↓
按 finalScore 降序排序
```

## 重排序 (Cross-Encoder Reranker)

`CrossEncoderReranker` 使用 LLM 批量评分：

- **批量评分**：一次 LLM 调用对所有候选块评分（O(1) 而非 O(n)）
- **截断策略**：每个 chunk 最多保留 300 字符
- **批量上限**：最多 20 个 chunk
- **熔断降级**：重排序失败时跳过，直接使用原始排序

## 流式回答

`streamAnswer()` 方法：

1. 检索 + Rerank（同 search 流程）
2. 构建 Prompt
3. 流式调用 LLM（`chatModel.stream()`）
4. 30 秒超时保护
5. 异常时返回降级文本

## 缓存策略

`RagCacheManager` 基于 Redisson RMapCache：

| 策略 | 说明 |
|------|------|
| 默认 TTL | 5 分钟 |
| 热点 TTL | 30 分钟（访问次数 > 3） |
| 失效方式 | 按文档 ID 或租户 ID 模式匹配删除 |
| 缓存键 | `rag:doc:vector:{tenantId}:{query}` |

## 熔断限流

`RagCircuitBreakerConfig` 配置：

| 组件 | 参数 | 说明 |
|------|------|------|
| CircuitBreaker | 失败率 50%、半开 30s、滑动窗口 10 | 保护 LLM 调用 |
| RateLimiter | 每秒 5 次、超时 500ms | 限流保护 |
| TimeLimiter | 超时 30s | 防止 LLM hang 住 |

## 可观测性指标

`RagMetricsRecorder` 通过 Micrometer 暴露 Prometheus 指标：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| rag.request.total | Timer | 请求总耗时 |
| rag.retrieval.duration | Timer | 检索阶段耗时 |
| rag.rerank.duration | Timer | Rerank 阶段耗时 |
| rag.llm.duration | Timer | LLM 调用耗时 |
| rag.requests.total | Counter | 请求总数 |
| rag.cache.hits | Counter | 缓存命中次数 |
| rag.cache.misses | Counter | 缓存未命中次数 |
| rag.tokens.input/output/total | Counter | Token 消耗 |
| rag.recall.rate | Gauge | 召回率 |

## AI 客户端配置

`AiClientConfig`：
- **Chat 模型**：通义千问 qwen-max（OpenAI 兼容模式）
- **Embedding 模型**：硅基流动 BAAI/bge-large-zh-v1.5（可独立配置 apiKey 和 baseUrl）
- **VectorStore**：PGVector（HNSW 索引、COSINE_DISTANCE、1024 维）
- **租户感知**：`TenantAwareJdbcTemplate` 包装 JdbcTemplate，支持动态 Schema 切换

## 会话历史管理

`RagSessionServiceImpl`：
- 会话元信息（`rag_session_meta`）：标题、标签、创建/更新时间
- 对话明细（`rag_session`）：query、answer、context、Token 消耗、延迟
- 混合保存：首次实时落库，后续异步批量更新
