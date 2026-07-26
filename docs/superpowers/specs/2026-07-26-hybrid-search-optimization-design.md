# 混合检索优化设计文档

**日期:** 2026-07-26  
**状态:** 已批准  
**实施优先级:** P0

---

## 1. 背景与目标

### 1.1 现状问题

当前混合检索实现（`RagSearchServiceImpl.hybridRetrieve()`）存在以下缺陷：

1. **关键词检索过于简陋**
   - 仅使用简单字符串匹配（`String.contains()`）
   - 没有 TF-IDF 或 BM25 算法
   - 忽略词频、逆文档频率、字段长度归一化
   - 未利用 PostgreSQL 的全文检索能力

2. **加权融合策略不科学**
   - 固定权重（0.5/0.5）无法适应不同查询类型
   - 没有动态调整权重的机制
   - 向量分数和关键词分数量纲不一致

3. **检索流程单一**
   - 只有向量检索 + 简单关键词匹配
   - 没有利用 PostgreSQL 的 `tsvector` 全文检索
   - 没有利用 `pg_trgm` 模糊匹配能力
   - 缺乏多路召回（Multi-Retrieval）策略

### 1.2 优化目标

| 指标 | 当前值 | 目标值 | 提升幅度 |
|------|--------|--------|----------|
| 召回率 | 基准 | +20-25% | 多路召回 |
| 准确率 | 基准 | +15-20% | Rerank 精排 |
| 响应时间 | 基准 | +30-80ms | 可接受开销 |
| Token 成本 | 基准 | -10% | 精准检索减少冗余 |

---

## 2. 架构设计

### 2.1 整体架构

```
用户查询 (Query)
   │
   ├──────────────────────────────────────┐
   │                                      │
   ▼                                      ▼
┌─────────────────────────────────────────────────────────┐
│                    多路检索层                            │
├─────────────────┬─────────────────┬─────────────────────┤
│  路 1: 向量检索   │  路 2: 全文检索   │  路 3: 模糊匹配      │
│  (PGVector)     │  (tsvector)     │  (pg_trgm)          │
│  topK=50        │  topK=50        │  topK=30            │
└────────┬────────┴────────┬────────┴─────────┬───────────┘
         │                 │                   │
         ▼                 ▼                   ▼
┌─────────────────────────────────────────────────────────┐
│                  归一化层 (排名归一化)                    │
│  normalized_score = 1.0 / (rank + 1)                    │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              融合层 (动态权重调整)                        │
│  finalScore = w_vector * score_v                        │
│             + w_fulltext * score_f                      │
│             + w_fuzzy * score_t                         │
│                                                         │
│  短查询 (<5 词): w=[0.7, 0.2, 0.1]                      │
│  长查询 (≥5 词): w=[0.4, 0.4, 0.2]                      │
│  含专有名词：w=[0.5, 0.4, 0.1]                          │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│                  筛选层                                  │
│  1. 阈值过滤：finalScore >= 0.3                         │
│  2. 多样性去重：同文档多 chunk 只保留最佳                 │
│  3. 候选集扩大：取 Top-50 进入 Rerank                     │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              Rerank 精排层                               │
│  Cross-Encoder (BAAI/bge-reranker-v2-m3)               │
│  输入：Top-50 候选                                       │
│  输出：按相关性重排序，保留 Top-10                        │
└─────────────────────────────────────────────────────────┘
         │
         ▼
    最终结果 (Top-10)
```

### 2.2 设计哲学

1. **充分利用现有能力**：基于 PostgreSQL 已有功能（PGVector、tsvector、pg_trgm），无需新增组件
2. **多路召回提升召回率**：三路检索互补，覆盖不同查询场景
3. **动态权重自适应**：根据 Query 特征自动调整，无需历史数据
4. **排名归一化鲁棒性强**：不依赖绝对分数，只关心相对排名
5. **多级筛选节省成本**：阈值过滤 + 去重，减少 Rerank 调用开销

---

## 3. 核心组件设计

### 3.1 多路检索器（Multi-Retriever）

**职责：** 并行执行三路检索，返回各自的结果列表

**接口定义：**
```java
public interface MultiRetriever {
    /**
     * 多路检索
     * @param query 用户查询
     * @return 三路检索结果（可能某路为空表示失败）
     */
    MultiRetrieveResult retrieve(RagQuery query);
}

@Data
public class MultiRetrieveResult {
    private List<ChunkResult> vectorResults;     // 向量检索结果
    private List<ChunkResult> fullTextResults;   // 全文检索结果
    private List<ChunkResult> fuzzyResults;      // 模糊匹配结果
}
```

**实现细节：**

**3.1.1 向量检索路（Vector Retriever）**
- 复用现有 `VectorStore.similaritySearch()`
- 使用 PGVector 的 HNSW 索引，余弦距离
- 返回 topK=50

```java
SearchRequest request = SearchRequest.builder()
    .query(query.getQuery())
    .topK(50)
    .similarityThreshold(0.0)  // 不过滤，后续归一化处理
    .build();
List<Document> results = vectorStore.similaritySearch(request);
```

**3.1.2 全文检索路（Full-Text Retriever）**
- 使用 PostgreSQL 的 `tsvector` + `tsquery`
- 支持词干提取、同义词扩展
- 返回 topK=50

```java
String tsQuery = buildTsQuery(query.getQuery()); // 转换为 PostgreSQL tsquery 语法
String sql = """
    SELECT id, content, metadata,
           ts_rank(content_tsv, to_tsquery('pg_catalog.simple', ?)) AS score
    FROM vector_store
    WHERE content_tsv @@ to_tsquery('pg_catalog.simple', ?)
    ORDER BY score DESC
    LIMIT 50
""";
// 执行查询并转换为 ChunkResult
```

**3.1.3 模糊匹配路（Fuzzy Retriever）**
- 使用 `pg_trgm` 的三 gram 模糊匹配
- 适合拼写错误、部分匹配场景
- 返回 topK=30

```java
String sql = """
    SELECT id, content, metadata,
           similarity(content, ?) AS score
    FROM vector_store
    WHERE similarity(content, ?) > 0.1
    ORDER BY score DESC
    LIMIT 30
""";
// 执行查询并转换为 ChunkResult
```

### 3.2 归一化器（Score Normalizer）

**职责：** 将三路检索的原始分数归一化到 [0,1] 区间

**算法：** 排名归一化（Reciprocal Rank）
```java
normalized_score = 1.0 / (rank + 1)
```

**实现：**
```java
public class RankNormalizer {
    public List<NormalizedResult> normalize(List<ChunkResult> results) {
        List<NormalizedResult> normalized = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            NormalizedResult nr = new NormalizedResult(results.get(i));
            nr.setNormalizedScore(1.0 / (i + 1));  // rank 从 0 开始
            normalized.add(nr);
        }
        return normalized;
    }
}
```

**优势：**
- 不依赖原始分数的量纲
- 鲁棒性强，对异常值不敏感
- 计算简单，性能开销小

### 3.3 融合器（Result Fusion）

**职责：** 根据动态权重融合三路归一化结果

**权重策略（基于 Query 特征）：**

| Query 类型 | 特征 | vector_weight | fulltext_weight | fuzzy_weight |
|-----------|------|---------------|-----------------|--------------|
| 短查询 | 词数 < 5 | 0.7 | 0.2 | 0.1 |
| 长查询 | 词数 ≥ 5 | 0.4 | 0.4 | 0.2 |
| 含专有名词 | 包含大写字母/数字/特殊符号 | 0.5 | 0.4 | 0.1 |

**实现：**
```java
public class ResultFuser {
    private static final double[] WEIGHTS_SHORT = {0.7, 0.2, 0.1};
    private static final double[] WEIGHTS_LONG = {0.4, 0.4, 0.2};
    private static final double[] WEIGHTS_PROPER = {0.5, 0.4, 0.1};
    
    public List<FusedResult> fuse(
        List<NormalizedResult> vector,
        List<NormalizedResult> fulltext,
        List<NormalizedResult> fuzzy,
        String query
    ) {
        double[] weights = determineWeights(query);
        
        // 合并所有结果（按 chunkId 分组）
        Map<String, FusedResult> fusedMap = new HashMap<>();
        
        // 融合向量路结果
        for (NormalizedResult r : vector) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[0], r.getNormalizedScore());
        }
        
        // 融合全文路结果
        for (NormalizedResult r : fulltext) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[1], r.getNormalizedScore());
        }
        
        // 融合模糊路结果
        for (NormalizedResult r : fuzzy) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[2], r.getNormalizedScore());
        }
        
        // 按最终分数排序
        return fusedMap.values().stream()
            .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
            .collect(Collectors.toList());
    }
    
    private double[] determineWeights(String query) {
        String[] terms = query.split("\\s+");
        
        // 判断是否含专有名词
        boolean hasProperNoun = query.matches(".*[A-Z].*") || 
                                query.matches(".*\\d.*") ||
                                query.contains("-");
        
        if (hasProperNoun) {
            return WEIGHTS_PROPER;
        } else if (terms.length < 5) {
            return WEIGHTS_SHORT;
        } else {
            return WEIGHTS_LONG;
        }
    }
}
```

### 3.4 筛选器（Result Filter）

**职责：** 对融合结果进行多级筛选

**筛选步骤：**

1. **阈值过滤**：丢弃 `finalScore < 0.3` 的低质量结果
2. **多样性去重**：同文档的多个 chunk 只保留分数最高的
3. **候选集扩大**：取 Top-50 进入 Rerank

**实现：**
```java
public class ResultFilter {
    private static final double SCORE_THRESHOLD = 0.3;
    
    public List<FusedResult> filter(List<FusedResult> results, int topK) {
        return results.stream()
            // 1. 阈值过滤
            .filter(r -> r.getFinalScore() >= SCORE_THRESHOLD)
            // 2. 多样性去重（同文档只保留最佳）
            .collect(Collectors.groupingBy(FusedResult::getDocumentId))
            .values().stream()
            .map(list -> list.stream()
                .max(Comparator.comparingDouble(FusedResult::getFinalScore))
                .orElseThrow())
            // 3. 取 Top-K
            .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
}
```

### 3.5 Rerank 精排层

**职责：** 对筛选后的候选集进行 Cross-Encoder 重排序

**配置：**
- 模型：BAAI/bge-reranker-v2-m3（硅基流动）
- 输入：Top-50 候选
- 输出：按相关性重排序，保留 Top-10

**实现：** 复用现有的 `CrossEncoderReranker.rerank()` 方法

---

## 4. 数据库 Schema 变更

### 4.1 启用全文检索支持

```sql
-- 1. 添加 tsvector 列
ALTER TABLE vector_store 
ADD COLUMN content_tsv tsvector;

-- 2. 创建 GIN 索引
CREATE INDEX idx_vector_store_content_tsv 
ON vector_store USING GIN (content_tsv);

-- 3. 创建触发器自动更新 tsvector
CREATE TRIGGER tsvectorupdate 
BEFORE INSERT OR UPDATE ON vector_store
FOR EACH ROW EXECUTE FUNCTION
tsvector_update_trigger(
    content_tsv, 'pg_catalog.simple', content
);

-- 4. 初始化现有数据
UPDATE vector_store 
SET content_tsv = to_tsvector('pg_catalog.simple', content);
```

### 4.2 启用模糊匹配支持

```sql
-- 1. 启用 pg_trgm 扩展（只需执行一次）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. 为 content 列创建 trgm 索引
CREATE INDEX idx_vector_store_content_trgm 
ON vector_store USING GIN (content gin_trgm_ops);
```

### 4.3 索引维护

```sql
-- 定期检查索引状态
SELECT indexname, pg_size_pretty(pg_relation_size(indexname::regclass))
FROM pg_indexes
WHERE tablename = 'vector_store';

-- 如需重建索引
REINDEX INDEX idx_vector_store_content_tsv;
REINDEX INDEX idx_vector_store_content_trgm;
```

---

## 5. 接口变更

### 5.1 RagQuery 新增字段

```java
@Data
public class RagQuery {
    // ... 现有字段 ...
    
    /**
     * 检索策略
     * HYBRID: 多路混合检索（默认）
     * VECTOR_ONLY: 仅向量检索
     * FULLTEXT_ONLY: 仅全文检索
     */
    private String retrievalStrategy = "HYBRID";
    
    /**
     * 融合后进入 Rerank 的候选数（默认 50）
     */
    private Integer fusionTopK = 50;
    
    /**
     * 分数阈值（默认 0.3）
     */
    private Double scoreThreshold = 0.3;
}
```

### 5.2 配置项新增

```yaml
# application.yml
rag:
  retrieval:
    # 多路检索配置
    vector-top-k: 50
    fulltext-top-k: 50
    fuzzy-top-k: 30
    
    # 融合配置
    score-threshold: 0.3
    fusion-top-k: 50
    
    # 权重策略（可配置覆盖默认值）
    weights:
      short-query: [0.7, 0.2, 0.1]
      long-query: [0.4, 0.4, 0.2]
      proper-noun: [0.5, 0.4, 0.1]
```

---

## 6. 错误处理与降级策略

### 6.1 单路检索失败

**场景：** 某路检索异常（如全文检索 SQL 失败）

**处理：**
- 记录错误日志，但不中断整体流程
- 降级为剩余路融合
- 如：全文检索失败 → 向量 + 模糊融合

```java
try {
    fullTextResults = fullTextRetriever.retrieve(query);
} catch (Exception e) {
    log.error("全文检索失败", e);
    fullTextResults = Collections.emptyList();
}
```

### 6.2 全部检索失败

**场景：** 三路检索全部异常

**处理：**
- 返回空结果列表
- 记录错误日志和告警
- 触发熔断机制

### 6.3 Rerank 失败

**场景：** Cross-Encoder API 调用失败

**处理：**
- 降级为按融合分数排序返回
- 记录错误日志
- 不影响用户请求

```java
try {
    return reranker.rerank(query, candidates, query.getRerankTopK());
} catch (Exception e) {
    log.warn("Rerank 失败，降级为融合分数排序", e);
    return candidates.stream()
        .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
        .limit(query.getRerankTopK())
        .collect(Collectors.toList());
}
```

---

## 7. 测试策略

### 7.1 单元测试

**测试对象：**
- `RankNormalizer.normalize()` - 验证归一化算法
- `ResultFuser.fuse()` - 验证权重融合逻辑
- `ResultFilter.filter()` - 验证筛选逻辑
- `determineWeights()` - 验证 Query 特征识别

**覆盖率要求：** ≥80%

### 7.2 集成测试

**测试场景：**
- 完整链路：多路检索 → 归一化 → 融合 → 筛选 → Rerank
- 降级场景：模拟某路检索失败
- 边界场景：空结果、单路结果、重复文档

### 7.3 性能测试

**对比指标：**
- 召回率（Recall@K）
- 准确率（Precision@K）
- 响应时间（P50/P95/P99）
- Token 消耗量

**基准对比：**
- 优化前 vs 优化后
- 不同 Query 类型的效果差异

### 7.4 A/B 测试（可选）

**方案：**
- 50% 流量走旧实现，50% 流量走新实现
- 收集用户反馈（点击率、满意度）
- 定期分析效果差异

---

## 8. 实施计划

实施计划将通过 `writing-plans` skill 另行创建。

---

## 9. 风险与缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| 数据库 Schema 变更影响现有数据 | 高 | 低 | 备份后执行，提供回滚脚本 |
| 多路检索增加响应时间 | 中 | 中 | 并行检索，设置超时，监控 P99 |
| 权重策略不适应某些场景 | 中 | 中 | 提供配置覆盖，支持 A/B 测试 |
| pg_trgm 索引占用空间大 | 低 | 中 | 定期监控，必要时调整相似度阈值 |

---

## 10. 验收标准

- [ ] 所有单元测试通过，覆盖率 ≥80%
- [ ] 集成测试验证完整链路和降级场景
- [ ] 性能测试显示召回率提升 ≥15%
- [ ] 响应时间增加 ≤100ms（P95）
- [ ] 数据库 Schema 变更脚本验证通过
- [ ] 配置项可正常覆盖默认权重
- [ ] 错误处理和降级策略验证通过

---

**文档状态:** 已完成自审，待用户审批  
**下一步:** 用户审批后，调用 `writing-plans` skill 创建实施计划
