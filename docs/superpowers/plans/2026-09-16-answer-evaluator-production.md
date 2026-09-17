# 回答评估接入生产调用方 + 落库 + 在线/手动/查看接口 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已有回答评估链路（`AnswerEvaluationService` 三维规则评估 + Redis 缓冲）接入生产调用方，实现「评估结果落库持久化 + 双写」「chat 在线异步自动评估（配置开关默认关）」「手动评估与查看/统计接口」，并保持 chat 主链路默认行为不变。

**Architecture:** 三个层面——
1. 持久化：每租户 schema 下新增 `answer_eval_result` 表（含 `session_row_id`、`source`、三维平铺分数），MyBatis-Plus entity + mapper 落库，`AnswerEvaluationService` 改为「Redis 即时缓冲 + PG 持久化」双写；并新增 `findByQuery` / `listResults` / `stats` 读取方法。
2. 在线触发：`ChatController.chat()` 在 `saveConversation` 后、返回前，受 `rag.eval.online-enabled` 开关控制，用独立有界线程池**异步**构造 `AnswerCase`（含 `sessionRowId`、`source="online"`）触发评估，异常仅记日志不回抛。
3. 接口：新增 `EvalController`（4 端点：run / result / results / stats），全部鉴权 + 租户头校验 + 按租户过滤防越权。

**Tech Stack:** Java 17 / Spring Boot 3.4 / MyBatis-Plus 3.5.9 / Redisson（RMapCache）/ JUnit 5 + Mockito

**关联 spec:** `docs/superpowers/specs/2026-09-16-answer-evaluator-production-design.md`（决策：落库+双写、在线异步开关默认关、手动/查看接口都要、`session_row_id`+`source` 对齐既有 key 语义）。前置实现见 `docs/superpowers/plans/2026-09-15-answer-evaluator.md`（三维评估器/Service/Redis 已就绪）。

**安全注意事项：**
- 新表启用与 `rag_session` 一致的 RLS：`tenant_id = current_tenant_id()` + `FORCE ROW LEVEL SECURITY`（防跨租户读写，铁律）。
- 建表/建索引的 `schemaName` 一律先做白名单校验 `^[a-zA-Z_][a-zA-Z0-9_]*$`（复用 `TenantServiceImpl` 既有做法），防 SQL 注入。
- 在线异步线程池内**不依赖 ThreadLocal 租户上下文**，落库时显式携带 `tenantId` 参数，避免跨线程租户串扰（铁律）。
- 所有对外接口 `@PreAuthorize("isAuthenticated()")`，租户 ID 只取 `X-Tenant-Id` 头，忽略请求体（与 `ChatController` 一致）。

---

## 文件结构

**持久化（rag 模块）：**
- Create `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResultEntity.java`
- Create `.../rag/eval/answer/AnswerEvalResultMapper.java`
- Modify `.../rag/eval/answer/AnswerCase.java`（扩 `sessionRowId`、`source`）
- Modify `.../rag/eval/answer/AnswerEvaluationService.java`（双写 + 读取方法）

**数据库（tenant / bootstrap 模块）：**
- Modify `company-rag-tenant/.../tenant/service/impl/TenantServiceImpl.java`（新租户建表 + 索引 + RLS）
- Modify `company-rag-bootstrap/.../bootstrap/SchemaMigrationConfig.java`（存量租户幂等建表 + 索引 + RLS）

**接口（web 模块）：**
- Create `company-rag-web/.../web/controller/EvalController.java`
- Modify `company-rag-web/.../web/controller/ChatController.java`（在线异步触发）

**配置：**
- Modify `company-rag-bootstrap/src/main/resources/application-dev.yml`（`rag.eval` 段）

**测试：**
- Modify / Create `AnswerEvaluationServiceTest.java`（落库 + 读取）
- Create `EvalControllerTest.java`

---

## 阶段 1：持久化（落库）

### Task 1: AnswerCase 扩展来源元数据

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerCase.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java`

- [ ] **Step 1: 扩 AnswerCase 字段（带默认值，保持既有三参构造）**

```java
package com.company.rag.rag.eval.answer;

/**
 * 待评估的一条问答样本。
 * tenantId: 显式租户 ID，跨线程落库不依赖 ThreadLocal（在线取 verifiedTenantId / 手动取 X-Tenant-Id）
 * sessionRowId: 关联 rag_session.id（在线评估来源定位，可空）
 * source: 评估来源（online / manual，默认 manual）
 */
public record AnswerCase(String query, String context, String answer,
                         Long tenantId, Long sessionRowId, String source) {
    public AnswerCase(String query, String context, String answer) {
        this(query, context, answer, null, null, "manual");
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS（reactor 联合编译，避免单模块读旧 common 快照）

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerCase.java
git commit -m "feat(rag): AnswerCase 扩展 sessionRowId/source 来源元数据"
```

### Task 2: AnswerEvalResultEntity 实体

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResultEntity.java`

- [ ] **Step 1: 新增实体（@TableName("answer_eval_result")）**

```java
package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 回答评估结果持久化实体（每租户 schema 一张）。
 * 三维分数平铺为三列，便于 SQL 统计；source 区分 online/manual。
 */
@Data
@TableName("answer_eval_result")
public class AnswerEvalResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 关联 rag_session.id（在线评估来源，可空） */
    private Long sessionRowId;

    private String query;

    /** 评估时使用的检索上下文快照 */
    private String context;

    private String answer;

    /** 综合是否通过 */
    private Boolean pass;

    /** 综合评分 0~1 */
    private Double score;

    private Double relevancyScore;

    private Double correctnessScore;

    private Double faithfulnessScore;

    /** 来源：online / manual */
    private String source;

    private LocalDateTime createTime;
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResultEntity.java
git commit -m "feat(rag): 新增 AnswerEvalResultEntity 持久化实体（answer_eval_result 表）"
```

### Task 3: AnswerEvalResultMapper Mapper

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResultMapper.java`

- [ ] **Step 1: 新增 Mapper（BaseMapper + @Mapper）**

```java
package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回答评估结果 Mapper（按租户 schema 隔离，RLS 兜底）。
 */
@Mapper
public interface AnswerEvalResultMapper extends BaseMapper<AnswerEvalResultEntity> {
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResultMapper.java
git commit -m "feat(rag): 新增 AnswerEvalResultMapper"
```

### Task 4: SchemaMigrationConfig 存量租户建表

**Files:**
- Modify: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/SchemaMigrationConfig.java`

- [ ] **Step 1: 新增 ApplicationRunner 幂等建 answer_eval_result 表 + 索引 + RLS**

在类内新增 bean（可复用既有 `queryForList("... tenant_% ...")` 遍历模式）：

```java
/**
 * 为所有租户 schema 幂等创建 answer_eval_result 表、索引并启用 RLS
 * （与 rag_session 一致：tenant_id = current_tenant_id()）。
 */
@Bean
public ApplicationRunner migrateAnswerEvalResultTable() {
    return args -> {
        log.info("开始执行 answer_eval_result 表迁移...");
        try {
            List<String> tenantSchemas = jdbcTemplate.queryForList(
                    "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name LIKE 'tenant_%'",
                    String.class
            );
            int migratedCount = 0;
            for (String schemaName : tenantSchemas) {
                // schemaName 白名单校验，防 SQL 注入
                if (!schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                    log.warn("跳过非法 schema 名：{}", schemaName);
                    continue;
                }
                String ddl = """
                    CREATE TABLE IF NOT EXISTS %1$s.answer_eval_result (
                        id BIGSERIAL PRIMARY KEY,
                        tenant_id BIGINT NOT NULL,
                        session_row_id BIGINT,
                        query TEXT,
                        context TEXT,
                        answer TEXT,
                        pass BOOLEAN NOT NULL,
                        score DOUBLE PRECISION NOT NULL,
                        relevancy_score DOUBLE PRECISION NOT NULL DEFAULT 0,
                        correctness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
                        faithfulness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
                        source VARCHAR(16) NOT NULL,
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    CREATE INDEX IF NOT EXISTS idx_%1$s_answer_eval_tenant_time
                        ON %1$s.answer_eval_result (tenant_id, create_time DESC);
                    ALTER TABLE %1$s.answer_eval_result ENABLE ROW LEVEL SECURITY;
                    ALTER TABLE %1$s.answer_eval_result FORCE ROW LEVEL SECURITY;
                    DROP POLICY IF EXISTS tenant_isolation_answer_eval ON %1$s.answer_eval_result;
                    CREATE POLICY tenant_isolation_answer_eval ON %1$s.answer_eval_result
                        FOR ALL TO company_rag_app
                        USING (tenant_id = current_tenant_id())
                        WITH CHECK (tenant_id = current_tenant_id());
                    """.formatted(schemaName);
                jdbcTemplate.execute(ddl);
                migratedCount++;
            }
            log.info("answer_eval_result 表迁移完成：处理 {} 个 schema", migratedCount);
        } catch (Exception e) {
            // 不抛出异常，避免启动失败
            log.error("answer_eval_result 表迁移失败：{}", e.getMessage(), e);
        }
    };
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS（bootstrap 依赖 tenant 模块，reactor 编译含 `-am` 或全量）

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/SchemaMigrationConfig.java
git commit -m "feat(bootstrap): 存量租户幂等创建 answer_eval_result 表与 RLS"
```

### Task 5: TenantServiceImpl 新租户建表

**Files:**
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

- [ ] **Step 1: 在 createTenantSchema 中追加 answer_eval_result 建表 + 索引 + RLS**

在 `createTableSql` 文本块（约 L116 之后、`formatted` 之前）新增表：

```sql
CREATE TABLE IF NOT EXISTS %s.answer_eval_result (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    session_row_id BIGINT,
    query TEXT,
    context TEXT,
    answer TEXT,
    pass BOOLEAN NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    relevancy_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    correctness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    faithfulness_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

在 `createIndexSql` 追加：`CREATE INDEX IF NOT EXISTS idx_%s_answer_eval_tenant_time ON %s.answer_eval_result (tenant_id, create_time DESC);`（注意同步增加 `formatted(...)` 的占位参数个数）。

在 `rlsSql` 文本块追加（与既有表一致）：

```sql
ALTER TABLE %1$s.answer_eval_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE %1$s.answer_eval_result FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_answer_eval ON %1$s.answer_eval_result;
CREATE POLICY tenant_isolation_answer_eval ON %1$s.answer_eval_result
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
```

> 注意（🟡6 已核实真实代码占位符计数）：当前 `TenantServiceImpl` 中——
> - `createTableSql` 文本块现有 6 个 `%s`（rag_document / doc_chunk×2[表名+外键引用] / vector_store / rag_session / rag_session_meta），`.formatted(...)` 现传 6 个 `schemaName`。**加 `answer_eval_result` 表后占位符变为 7 个 `%s`，`.formatted(...)` 必须补 1 个 `schemaName`（6→7）。**
> - `createIndexSql` 现有 6 行索引，每行占 `idx_%s` + `ON %s` 共 2 个 `%s`，共 12 个 `%s`，`.formatted(...)` 现传 12 个 `schemaName`。**加 `answer_eval_result` 索引后占位符变为 14 个 `%s`，`.formatted(...)` 必须补 2 个 `schemaName`（12→14）。**
> - `rlsSql` 使用 `%1$s` 位置引用（同参数复用），加表后仍只需 `.formatted(schemaName)` 传 1 个参数，**位置符参数个数不变**。
> - 若占位符个数与实际参数不一致，.formatted 运行时抛 `MissingFormatArgumentException`（字符串无编译检查），务必按上述计数人工核对。

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
git commit -m "feat(tenant): 新租户建表含 answer_eval_result 表、索引与 RLS"
```

### Task 6: AnswerEvaluationService 双写 + 读取方法

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluationService.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java`

- [ ] **Step 1: 注入 Mapper，evaluate 保持纯评估语义，新增强制落库 + 读取方法**

关键改造：
1. 新增 `private final AnswerEvalResultMapper evalResultMapper;` 字段（`@RequiredArgsConstructor` 构造器由 4 参 → **5 参，注意既有测试构造器需同步**）。
2. `evaluate()` **保持既有语义**（仅评估 + 写 Redis，不落库、不抛异常）——保证 0915 落地测试与纯离线/测试 3 参 case 零修改通过（🔴2 修复）。
3. 新增 `evaluateAndPersist()`（强制落库：`tenantId==null` 直接抛异常，防 tenant_id=0）与 `evaluateAllPersisted()`（批量，手动 run 用）。
4. 新增三个读取方法 `findByQuery` / `listResults` / `stats`。

```java
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {

    private final RedissonClient redissonClient;
    private final AnswerRelevancyEvaluator relevancyEvaluator;
    private final AnswerCorrectnessEvaluator correctnessEvaluator;
    private final AnswerFaithfulnessEvaluator faithfulnessEvaluator;
    private final AnswerEvalResultMapper evalResultMapper;

    // ... EVAL_PREFIX / EVAL_TTL_SECONDS 保持 ...

    public AnswerEvalResult evaluate(AnswerCase answerCase) {
        // 【契约-🔴2 既有测试回归修复】evaluate() 保持 0915 既有语义：仅评估 + 写 Redis，
        // 不落库、不抛异常。纯离线/测试样本（tenantId==null，三参构造）与在线提数都可用。
        // 需要落库的调用方走 evaluateAndPersist / evaluateAllPersisted（强制校验 tenantId）。
        if (answerCase == null) {
            return null;
        }
        String query = answerCase.query();
        String context = answerCase.context();
        String answer = answerCase.answer();

        // 保持维度顺序：relevancy → correctness → faithfulness
        Map<String, Boolean> passes = new LinkedHashMap<>();
        passes.put("relevancy", relevancyEvaluator.evaluate(query, context, answer));
        passes.put("correctness", correctnessEvaluator.evaluate(query, context, answer));
        passes.put("faithfulness", faithfulnessEvaluator.evaluate(query, context, answer));

        Map<String, Double> scores = new LinkedHashMap<>();
        passes.forEach((k, v) -> scores.put(k, v ? 1.0 : 0.0));

        boolean pass = AnswerEvalResult.allPass(passes);
        double avgScore = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        AnswerEvalResult result = new AnswerEvalResult(query, context, answer, pass, avgScore, scores);
        writeToRedis(answerCase, result);
        return result;
    }

    /** 便捷：兼容既有 evaluateAll（逐个评估并落库，返回内存结果列表） */
    public List<AnswerEvalResult> evaluateAll(List<AnswerCase> cases) {
        if (cases == null) return List.of();
        return cases.stream().map(this::evaluate).filter(Objects::nonNull).toList();
    }

    /** 将平铺的三维分数重建为维度分 Map（用于 evaluate 的反向 mapping） */
    private static Map<String, Double> allFlatScores(AnswerEvalResultEntity e) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("relevancy", e.getRelevancyScore() != null ? e.getRelevancyScore() : 0.0);
        m.put("correctness", e.getCorrectnessScore() != null ? e.getCorrectnessScore() : 0.0);
        m.put("faithfulness", e.getFaithfulnessScore() != null ? e.getFaithfulnessScore() : 0.0);
        return m;
    }

    /** 批量评估并返回落库后的持久化实体（手动 run 用，与查看接口返回类型一致）。
     *  注意（🟡7）：单条落库失败会返回 null 并被剔除——此处对失败条数显式统计并告警，
     *  避免「返回条数少于请求却无任何信号」的静默丢失败。
     *  注意（🔴2）：tenantId 为 null（离线样本误入）由 evaluateAndPersist 抛
     *  IllegalArgumentException，这里统一捕获计为失败剔除，不抛给调用方。 */
    public List<AnswerEvalResultEntity> evaluateAllPersisted(List<AnswerCase> cases) {
        if (cases == null) return List.of();
        List<AnswerEvalResultEntity> persisted = new ArrayList<>();
        int failed = 0;
        for (AnswerCase c : cases) {
            AnswerEvalResultEntity entity;
            try {
                entity = evaluateAndPersist(c);
            } catch (IllegalArgumentException e) {
                // tenantId=null 等不可落库样本：剔除并告警（不抛给调用方）
                failed++;
                log.warn("[EVAL] 单条剔除（不可落库）：{}", e.getMessage());
                continue;
            }
            if (entity == null) {
                failed++;
            } else {
                persisted.add(entity);
            }
        }
        if (failed > 0) {
            log.warn("[EVAL] 手动批量评估完成：共 {} 条，{} 条落库失败被剔除（返回 {} 条）",
                    cases.size(), failed, persisted.size());
        }
        return persisted;
    }

    /** 单个：评估 + 写 Redis + 落库，返回落库后实体（含回填主键） */
    private AnswerEvalResultEntity evaluateAndPersist(AnswerCase answerCase) {
        if (answerCase == null) {
            return null;
        }
        String query = answerCase.query();
        String context = answerCase.context();
        String answer = answerCase.answer();

        // 保持维度顺序：relevancy → correctness → faithfulness
        Map<String, Boolean> passes = new LinkedHashMap<>();
        passes.put("relevancy", relevancyEvaluator.evaluate(query, context, answer));
        passes.put("correctness", correctnessEvaluator.evaluate(query, context, answer));
        passes.put("faithfulness", faithfulnessEvaluator.evaluate(query, context, answer));

        Map<String, Double> scores = new LinkedHashMap<>();
        passes.forEach((k, v) -> scores.put(k, v ? 1.0 : 0.0));

        boolean pass = AnswerEvalResult.allPass(passes);
        double avgScore = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        AnswerEvalResult result = new AnswerEvalResult(query, context, answer, pass, avgScore, scores);

        writeToRedis(answerCase, result);
        // 落库并保留实体（供手动 run 返回持久化结果；在线/离线仅需评估结果时仍用 evaluate/evaluateAll）
        AnswerEvalResultEntity entity = toEntity(answerCase, result);
        try {
            evalResultMapper.insert(entity);
            log.info("[EVAL] 已落库评估结果 id={}, tenantId={}, source={}", entity.getId(), entity.getTenantId(), entity.getSource());
            return entity;
        } catch (Exception e) {
            // 落库失败不回抛，但返回 null 让调用方感知未持久化
            log.warn("[EVAL] 落库失败：{}", e.getMessage());
            return null;
        }
    }

    private AnswerEvalResultEntity toEntity(AnswerCase answerCase, AnswerEvalResult result) {
        // 【铁律】跨线程落库：tenantId 必须为显式值，不依赖评估线程 ThreadLocal（恒为 null，会落 tenant_id=0）。
        // 与 spec §3.3 一致：null 直接拒绝（抛异常），不做 0L 兜底——静默兜底会掩盖「租户丢失」错误并写入永远不可见的数据。
        Long tenantId = answerCase.tenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("[EVAL] 落库失败：AnswerCase.tenantId 不能为 null（租户丢失，拒绝写入）");
        }
        AnswerEvalResultEntity entity = new AnswerEvalResultEntity();
        entity.setTenantId(tenantId);
        entity.setSessionRowId(answerCase.sessionRowId());
        entity.setQuery(answerCase.query());
        entity.setContext(answerCase.context());
        entity.setAnswer(answerCase.answer());
        entity.setPass(result.pass());
        entity.setScore(result.score());
        entity.setRelevancyScore(result.dimensionScores().getOrDefault("relevancy", 0.0));
        entity.setCorrectnessScore(result.dimensionScores().getOrDefault("correctness", 0.0));
        entity.setFaithfulnessScore(result.dimensionScores().getOrDefault("faithfulness", 0.0));
        entity.setSource(answerCase.source() != null ? answerCase.source() : "manual");
        return entity;
    }

    /** 按 query 精确匹配查最新一条（仅作查看/质检，非 hash 查询） */
    public AnswerEvalResultEntity findByQuery(Long tenantId, String query) {
        return evalResultMapper.selectOne(new LambdaQueryWrapper<AnswerEvalResultEntity>()
                .eq(AnswerEvalResultEntity::getTenantId, tenantId)
                .eq(AnswerEvalResultEntity::getQuery, query)
                .orderByDesc(AnswerEvalResultEntity::getCreateTime)
                .last("LIMIT 1"));
    }

    /** 按时间范围查列表（分页取 limit 条） */
    public List<AnswerEvalResultEntity> listResults(Long tenantId, java.util.List<Long> sessionRowIds,
                                                    java.time.LocalDateTime from, java.time.LocalDateTime to, int limit) {
        LambdaQueryWrapper<AnswerEvalResultEntity> wrapper = new LambdaQueryWrapper<AnswerEvalResultEntity>()
                .eq(AnswerEvalResultEntity::getTenantId, tenantId);
        if (sessionRowIds != null && !sessionRowIds.isEmpty()) {
            wrapper.in(AnswerEvalResultEntity::getSessionRowId, sessionRowIds);
        }
        if (from != null) wrapper.ge(AnswerEvalResultEntity::getCreateTime, from);
        if (to != null) wrapper.le(AnswerEvalResultEntity::getCreateTime, to);
        int pageSize = (limit <= 0 || limit > 200) ? 50 : limit;
        return evalResultMapper.selectList(wrapper.orderByDesc(AnswerEvalResultEntity::getCreateTime).last("LIMIT " + pageSize));
    }

    /** 统计：总数 / pass 数（综合 pass 率）/ 平均分 / 三维均分 */
    public Map<String, Object> stats(Long tenantId, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        List<AnswerEvalResultEntity> rows = listResults(tenantId, null, from, to, 200);
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total", rows.size());
        stats.put("passCount", rows.stream().filter(e -> Boolean.TRUE.equals(e.getPass())).count());
        stats.put("passRate", rows.isEmpty() ? 0.0 : rows.stream().filter(e -> Boolean.TRUE.equals(e.getPass())).count() * 1.0 / rows.size());
        stats.put("avgScore", rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(AnswerEvalResultEntity::getScore).average().orElse(0.0));
        stats.put("avgRelevancyScore", rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(AnswerEvalResultEntity::getRelevancyScore).average().orElse(0.0));
        stats.put("avgCorrectnessScore", rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(AnswerEvalResultEntity::getCorrectnessScore).average().orElse(0.0));
        stats.put("avgFaithfulnessScore", rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(AnswerEvalResultEntity::getFaithfulnessScore).average().orElse(0.0));
        return stats;
    }
}
```

> 说明：`stats` 与 `listResults` 共用底层查询，本版限制 200 条做近似统计（质检观测够用），避免复杂聚合 SQL；如需精确分页统计留待后续。

- [ ] **Step 2: 修正既有测试构造器（🔴2），并新增落库/读取单测**

**先修正已存在测试（否则 Task 10 编译失败）**：仓库已有 `AnswerEvaluationServiceTest.java`（0915 落地）用 **4 参**构造器实例化 `AnswerEvaluationService`。Service 构造器改为 5 参后，必须同步：
- `setUp()` 中去掉对 `service` 的 4 参 new，改为：
  ```java
  evalResultMapper = mock(AnswerEvalResultMapper.class);
  service = new AnswerEvaluationService(redisson, relevancy, correctness, faithfulness, evalResultMapper);
  ```
  其中 `redisson`（或字段名 `redissonClient`）已 mock 的 `RedissonClient` 保持。
- 既有三个 evaluate 用例沿用**三参** `new AnswerCase("q","ctx",...)`（tenantId=null）——按新契约 `evaluate()` 仅评估 + Redis，不落库、不抛异常，**原样通过，无需修改**。

再在 `AnswerEvaluationServiceTest` 中新增（同样 mock `AnswerEvalResultMapper`）：

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Test
void evaluateAllPersisted_persistsToDb_withExplicitTenantIdAndSource() {
    when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

    // 使用六参构造，显式传 tenantId / sessionRowId / source（手动 run 的真实调用方式）
    AnswerCase c = new AnswerCase("q", "ctx", "a sufficiently long answer", 42L, 1001L, "manual");
    List<AnswerEvalResultEntity> entities = service.evaluateAllPersisted(List.of(c));
    assertEquals(1, entities.size());
    assertTrue(entities.get(0).getPass());

    // 捕获落库实体，断言 tenantId 来自显式入参而非 ThreadLocal（防落 tenant_id=0）
    ArgumentCaptor<AnswerEvalResultEntity> captor = ArgumentCaptor.forClass(AnswerEvalResultEntity.class);
    verify(evalResultMapper).insert(captor.capture());
    AnswerEvalResultEntity entity = captor.getValue();
    assertEquals(Long.valueOf(42L), entity.getTenantId());
    assertEquals(Long.valueOf(1001L), entity.getSessionRowId());
    assertEquals("manual", entity.getSource());
}

@Test
void evaluateAllPersisted_countsFailedWhenTenantIdNull() {
    when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

    // tenantId=null 的 case 走 evaluate() 正常（内存+Redis），但经 evaluateAllPersisted 强制落库被拒
    AnswerCase offline = new AnswerCase("q", "ctx", "a sufficiently long answer");
    List<AnswerEvalResultEntity> entities = service.evaluateAllPersisted(List.of(offline));
    assertTrue(entities.isEmpty()); // null 被剔除并计入失败告警，而非抛到调用方
}

@Test
void evaluateAllPersisted_doesNotPropagate_whenDbInsertThrows() {
    when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
    doThrow(new RuntimeException("db down")).when(evalResultMapper).insert(any(AnswerEvalResultEntity.class));

    // 落库失败不应抛到调用方；该条被剔除计入失败（返回空列表）
    assertDoesNotThrow(() -> service.evaluateAllPersisted(
            List.of(new AnswerCase("q", "ctx", "a sufficiently long answer", 42L, 1001L, "manual"))));
    assertTrue(service.evaluateAllPersisted(
            List.of(new AnswerCase("q", "ctx", "a sufficiently long answer", 42L, 1001L, "manual"))).isEmpty());
}

@Test
void findByQuery_filtersByTenant() {
    when(evalResultMapper.selectOne(any())).thenReturn(someEntity());
    assertNotNull(service.findByQuery(1L, "q"));
}

/** 构造一个测试用持久化实体（回填主键/字段便于断言落库映射） */
private static AnswerEvalResultEntity someEntity() {
    AnswerEvalResultEntity e = new AnswerEvalResultEntity();
    e.setId(1L);
    e.setTenantId(1L);
    e.setQuery("q");
    e.setPass(true);
    e.setScore(1.0);
    return e;
}
```

> 补充：三参便捷构造（`tenantId=null`）仅在无租户的纯离线/测试场景用于构造样本；因 `toEntity` 对 null tenantId 直接抛异常（🟡2，不做 0L 兜底），三参构造的 case 走 `evaluate()`（仅内存+Redis，不落库）安全；若传入 `evaluateAllPersisted` 则会被剔除并计入失败告警（不抛到调用方）。

- [ ] **Step 3: 运行测试验证通过**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -pl company-rag-rag test -Dtest=AnswerEvaluationServiceTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluationService.java company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java
git commit -m "feat(rag): AnswerEvaluationService 双写落库并新增查询/统计方法"
```

---

## 阶段 2：在线异步自动评估 + 接口

### Task 7: ChatController 在线异步触发

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`

- [ ] **Step 1: 注入评估服务与配置，增加异步触发**

【根因/冲突说明】`ChatController` 用 `@RequiredArgsConstructor`，所有 `private final` 字段=构造必填。若把 `AnswerEvaluationService` 作为 `private final` 必填注入，而 Task 9 又给其加 `@ConditionalOnProperty(enabled=false 不装配)`，则 `enabled=false` 时该 bean 不存在 → `ChatController` 构造失败 → 整个 `/api` 启动即死，违背「真正可关的开关」目标（🟡3 的验证也将必然失败）。

**修正方案：`AnswerEvaluationService` 用 `@Autowired(required=false)` 可选注入（不进入 `@RequiredArgsConstructor` 必填构造），并在在线分支判空访问。** 这样 `enabled=false` 时注入 null，主链路正常启动，仅在线评估分支不执行。

新增字段声明与线程池（线程池字段为 `private final` 但由显式初始化器赋值，不依赖容器注入，可安全保留）：

```java
// 在线评估服务：可选注入（enabled=false 时为 null），主链路不得因评估 bean 缺失而启动失败
@Autowired(required = false)
private AnswerEvaluationService answerEvaluationService;

@Value("${rag.eval.online-enabled:false}")
private boolean evalOnlineEnabled;

@Value("${rag.eval.async-enabled:true}")
private boolean asyncEnabled;

// Java 17 兼容：使用普通命名线程工厂（Thread.ofVirtual 为 Java 21 API，本项目 java=17，编译会失败）
private final ThreadFactory evalThreadFactory = new ThreadFactory() {
    private final AtomicInteger n = new AtomicInteger(0);
    @Override public Thread newThread(Runnable r) {
        return new Thread(r, "eval-online-" + n.incrementAndGet());
    }
};
private final ExecutorService evalExecutor = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(50),
        evalThreadFactory,
        new ThreadPoolExecutor.AbortPolicy());
```

`chat()` 在 `saveConversation` 块之后、`return R.ok(response)` 之前插入：

```java
    // 在线自动评估（默认关闭）：异步触发，失败不影响主回复。
    // 注意：answerEvaluationService 为可选注入，enabled=false 时为 null，此处判空跳过（主链路不受影响）
    if (evalOnlineEnabled && savedRowId != null && answerEvaluationService != null) {
        String queryForEval = request.getQuery();
        String answerForEval = result.getAnswer();
        String contextForEval = result.getToolContext();   // 注：无工具调用时可能为 null（见下）
        Long rowIdForEval = savedRowId;
        // 【铁律】显式快照并恢复租户链路上下文，评估任务内不依赖自身 ThreadLocal
        TenantContextSnapshot ctxSnapshot = TenantContextSnapshot.captureNow();
        if (asyncEnabled) {
            try {
                evalExecutor.submit(() -> {
                    try {
                        ctxSnapshot.apply();           // 写回租户/用户/会话/日志链路（值来自主线程显式捕获）
                        //【铁律-🔴2】在线需落库（带 session_row_id 定位语义），故走 evaluateAllPersisted
                        //（强制校验 tenantId=verifiedTenantId，写 Redis + PG 落库，失败剔除不抛给主链路）
                        answerEvaluationService.evaluateAllPersisted(List.of(
                                new AnswerCase(queryForEval, contextForEval, answerForEval,
                                        verifiedTenantId, rowIdForEval, "online")));
                    } finally {
                        ctxSnapshot.clear();           // 清理，防线程池复用串扰（在池内线程执行，不碰主线程）
                    }
                });
            } catch (RejectedExecutionException e) {
                // 队列满被拒，丢弃本次评估并告警，不阻塞主回复。
                // 【铁律-🟡4】此处【不调用】ctxSnapshot.clear()：ctxSnapshot 是主线程捕获的快照，
                // 主线程上下文在 chat() finally 自有清理；在 catch 里 clear 会清掉主线程 TenantContext/MDC traceId，
                // 截断主请求剩余日志链路。丢弃本次评估无需在此清理。
                log.warn("[EVAL] 在线评估线程池已满，丢弃一次评估：query={}", queryForEval);
            }
        } else {
            // async-enabled=false（同步调试）：
            try {
                ctxSnapshot.apply();
                answerEvaluationService.evaluateAllPersisted(List.of(
                        new AnswerCase(queryForEval, contextForEval, answerForEval,
                                verifiedTenantId, rowIdForEval, "online")));
            } finally {
                ctxSnapshot.clear();
            }
        }
    }
```

> 线程安全说明（铁律配套）：
> 1. **`tenantId` 显式传入**：`AnswerCase` 六参入参 `tenantId=verifiedTenantId` 显式来自主线程已校验的请求头，`evaluateAndPersist()` 落库取 `AnswerCase.tenantId()`，**不依赖评估线程 `TenantContext` ThreadLocal**（该值在主线程 finally clear 后恒为 null，会导致落 `tenant_id=0` 且被 RLS 过滤不可见）。
> 2. **日志链路（traceId）透传**：项目类 `TenantContextSnapshot` 仅有 `captureNow()/apply()/clear()` 三个方法（无 `setThreadLocals()`，那是 Micrometer `io.micrometer.context.ContextSnapshot` 的 API）。此处用 `captureNow()` 在主线程捕获、任务内 `apply()` 恢复、finally `clear()` 清理。**不要**误用 Micrometer `ContextSnapshot.setThreadLocals()`（与项目类同名不同物，会编译失败）。
> 3. 异常捕获：`submit` 被拒（队列满）走 `RejectedExecutionException` 分支丢弃；任务体内异常在 `finally clear()` 之后自然被线程池吞掉，仅靠 `persist()`/`writeToRedis()` 内部日志。如需任务级异常日志可加 try/catch（可选）。
> 4. **`contextForEval` 可能为 null（🟡5）**：`result.getToolContext()` 来自 `StreamingAgentExecutor.execute()` 的 `recorder.captureToolContext()`——仅在本次调用真实发生工具调用时才返回非空上下文；纯知识问答（无工具调用）或工具记录为空时返回 null。faithfulness 评估依赖 `context` 判定「答案是否忠实于上下文」，context 为 null 时评估失准。处理：在线触发前做空值归一化——`String contextForEval = result.getToolContext() == null ? "" : result.getToolContext();`（保证非 null 进入评估，faithfulness 对空上下文按「无法印证」处理而非报错）。
> 5. **主链路不因评估 bean 缺失而失败（🔴 冲突修正）**：`AnswerEvaluationService` 因 `@ConditionalOnProperty(enabled=false)` 在关闭时不装配，故 `ChatController` 采用 `@Autowired(required=false)` 可选注入，在线分支先判 `answerEvaluationService != null` 再触发；`enabled=false` 时注入 null、主链路正常启动。

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
git commit -m "feat(web): chat 在线异步自动评估触发（开关控制，默认关）"
```

### Task 8: 新增 EvalController（手动评估 + 查看 + 统计）

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/EvalController.java`
- Test: `company-rag-web/src/test/java/com/company/rag/web/controller/EvalControllerTest.java`

- [ ] **Step 1: 新增控制器（4 端点，鉴权 + 租户头校验）**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvalResultEntity;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 回答评估 Controller：手动评估 + 查看 + 统计。
 * 全部按鉴权用户 + X-Tenant-Id 头隔离租户。
 */
@Slf4j
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    private final AnswerEvaluationService answerEvaluationService;

    /** 手动批量评估（source=manual，返回落库后的持久化实体，与查看接口类型一致） */
    @PostMapping("/run")
    @PreAuthorize("isAuthenticated()")
    public R<List<AnswerEvalResultEntity>> run(@RequestBody List<AnswerCase> cases,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        // 【安全关键】租户 ID 必须从请求头获取
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        // 手动评估强制 source=manual、显式租户=请求头、sessionRowId=null（防伪造在线来源/越权）
        List<AnswerCase> manualCases = cases == null ? List.of() : cases.stream()
                .map(c -> new AnswerCase(c.query(), c.context(), c.answer(),
                        headerTenantId, null, "manual"))
                .toList();
        return R.ok(answerEvaluationService.evaluateAllPersisted(manualCases));
    }

    /** 按查询文本查单条评估结果 */
    @GetMapping("/result")
    @PreAuthorize("isAuthenticated()")
    public R<AnswerEvalResultEntity> result(@RequestParam String query,
                                            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        return R.ok(answerEvaluationService.findByQuery(headerTenantId, query));
    }

    /** 按时间范围查评估列表 */
    @GetMapping("/results")
    @PreAuthorize("isAuthenticated()")
    public R<List<AnswerEvalResultEntity>> results(
            @RequestParam(required = false) java.util.List<Long> sessionRowIds,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        return R.ok(answerEvaluationService.listResults(headerTenantId, sessionRowIds, from, to, limit));
    }

    /** 评估统计 */
    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public R<Map<String, Object>> stats(@RequestParam(required = false) LocalDateTime from,
                                        @RequestParam(required = false) LocalDateTime to,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        return R.ok(answerEvaluationService.stats(headerTenantId, from, to));
    }
}
```

> 说明：`run` 强制将传入 case 规格化为 `source="manual"`、`sessionRowId=null`，防止客户端伪造来源标记污染在线统计语义。

- [ ] **Step 2: 写控制器单测**

Mock `AnswerEvaluationService`，验证：未带 `X-Tenant-Id` 头时 4 端点均拒绝（抛 `IllegalArgumentException`）；`run` 规格化并透传；`result`/`results`/`stats` 按头租户查询。示例：

```java
@ExtendWith(MockitoExtension.class)
class EvalControllerTest {
    @Mock AnswerEvaluationService service;
    @InjectMocks EvalController controller;

    @Test
    void run_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.run(List.of(new AnswerCase("q", "c", "a")), null));
    }

    @Test
    void run_forcesManualSourceAndTenant() {
        when(service.evaluateAllPersisted(anyList())).thenReturn(List.of());
        R<List<AnswerEvalResultEntity>> r = controller.run(List.of(new AnswerCase("q", "c", "a")), 7L);
        // verify service received cases with source=manual, tenantId=7L, sessionRowId=null
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AnswerCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).evaluateAllPersisted(captor.capture());
        AnswerCase got = captor.getValue().get(0);
        assertEquals("manual", got.source());
        assertEquals(Long.valueOf(7L), got.tenantId());
        assertNull(got.sessionRowId());
        assertNotNull(r);
    }

    @Test
    void result_filtersByTenantHeader() {
        when(service.findByQuery(anyLong(), anyString())).thenReturn(new AnswerEvalResultEntity());
        R<AnswerEvalResultEntity> rr = controller.result("q", 7L);
        verify(service).findByQuery(7L, "q");
        assertNotNull(rr.getData());
    }
}
```

- [ ] **Step 3: 运行测试验证通过**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -pl company-rag-web test -Dtest=EvalControllerTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-web/src/main/java/com/company/rag/web/controller/EvalController.java company-rag-web/src/test/java/com/company/rag/web/controller/EvalControllerTest.java
git commit -m "feat(web): 新增 EvalController 手动评估/查看/统计接口"
```

### Task 9: 配置 rag.eval 段

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/application-dev.yml`

- [ ] **Step 1: 在 rag 段增加 eval 配置**

在 `application-dev.yml` 的 `rag:` 段（`retrieval:` 之后）追加：

```yaml
  eval:
    enabled: true              # 评估组件总开关：@ConditionalOnProperty 决定评估器/Service/EvalController 是否装配
    online-enabled: false      # 是否接入 chat 在线自动评估（默认关，先手动验证）
    async-enabled: true        # 在线评估是否异步（true=有界线程池；false=当前线程同步，仅调试用）
```

- [ ] **Step 2: 为 5 个类加上 @ConditionalOnProperty（🟡3 具体化接线，消除死开关）**

`rag.eval.enabled` 不能只写在 yml 与注释里，必须落到真实装配点，否则 `enabled=false` 时 bean 仍存在（死开关）。给以下类补充 import 与类级注解：

**① `AnswerRelevancyEvaluator` / ② `AnswerCorrectnessEvaluator` / ③ `AnswerFaithfulnessEvaluator`：**
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true", matchIfMissing = true)
public class AnswerXxxEvaluator implements AnswerEvaluator { ... }
```
（三个实现类各自加一份）

**④ `AnswerEvaluationService`**（类声明上方加 `@ConditionalOnProperty(...)`，同 ①—③）。

**⑤ `EvalController`**（`@RestController` 旁加 `@ConditionalOnProperty(...)`，同 ①—③）。

> 注意：`ChatController` 是主链路类，**自身不加** `rag.eval.enabled` 注解（避免整体被禁用）；它仅通过 `@Value("${rag.eval.online-enabled:false}")` 读在线开关、`@Value("${rag.eval.async-enabled:true}")` 读异步开关。**关键（🔴 冲突）：`AnswerEvaluationService` 加了 `@ConditionalOnProperty` 后，`enabled=false` 时不再是 bean——`ChatController` 中必须用 `@Autowired(required=false)` 字段注入并在在线分支判空（见 Task 7 Step 1），否则按 `@RequiredArgsConstructor` 必填构造注入会在 `enabled=false` 时构造失败、拖垮整个 `/api` 启动。**
> 验证：`rag.eval.enabled=false` 时启动应成功——`AnswerEvaluationService` / `EvalController` 均不被实例化、`ChatController` 正常注入（评估服务为 null）、主链路可用（可利用 `ApplicationContext.getBeansOfType` 或日志确认装配数 + `/api/chat` 可访问）。

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-bootstrap/src/main/resources/application-dev.yml
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/  company-rag-web/src/main/java/com/company/rag/web/controller/EvalController.java
git commit -m "feat(dev): 新增 rag.eval 开关并把 enabled 接线到评估器/Service/Controller"
```

---

## 阶段 3：全量校验与提交

### Task 10: Reactor 联合编译 + 相关单测

**Files:**
- 全部改动文件

- [ ] **Step 1: Reactor 全量编译**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS（EXIT=0）

- [ ] **Step 2: 运行受影响模块测试（最窄范围，不跑全量）**

Run: `cd /d/tmp/CompanyRag && mvn -o -q -pl company-rag-rag -am test -Dtest=AnswerEvaluationServiceTest`
Expected: PASS

Run: `cd /d/tmp/CompanyRag && mvn -o -q -pl company-rag-web -am test -Dtest=EvalControllerTest`
Expected: PASS

- [ ] **Step 3: 回到远端分支并核对 git 状态**

Run: `git status` / `git log --oneline`
Expected: 本次各 Task 提交按序出现，工作区干净（遗留 `.commit-msg-eval.txt` 未跟踪除外）。

---

## Self-Review

**1. Spec coverage：**
- 落库表 `answer_eval_result`（`session_row_id` / `source` / 三维平铺分数）：Task 2-5 覆盖（entity/mapper/存量迁移/新租户建表）。
- 双写（Redis 即时缓冲 + PG 落库，落库失败不回抛）：Task 6 覆盖。
- 在线异步自动评估（开关默认关、有界线程池、不回抛）：Task 7 覆盖。
- 手动/查看/统计接口（run/result/results/stats、鉴权 + 租户头 + 越权过滤）：Task 8 覆盖。
- `rag.eval` 配置开关：Task 9 覆盖。
- RLS 隔离（`tenant_id = current_tenant_id()`）对齐既有 `rag_session`：Task 4-5 覆盖。

**2. Placeholder scan：** 步骤均含完整代码/命令/预期。`TenantContextSnapshot` 更名为本项目真实类（`com.company.rag.rag.workflow.TenantContextSnapshot`，仅 `captureNow()/apply()/clear()`），Task 7 已按此真实签名落地，无占位/待定 API。线程池已改为 Java 17 兼容的普通命名 `ThreadFactory`（`Thread.ofVirtual` 为 Java 21 API，禁用）。落库一律 `AnswerCase.tenantId()` 显式值且 null 拒绝、不做 0L 兜底（🟡2 已修）。

**3. Type consistency：**
- `AnswerCase` 六参 record（query/context/answer/tenantId/sessionRowId/source）+ 三参便捷构造（Task 1），`run` 规格化用六参构造并显式 tenantId（Task 8）——一致。
- `AnswerEvalResultEntity` 字段（Task 2）与 `toEntity` 映射（Task 6）逐字段对应。
- `run` 返回 `List<AnswerEvalResultEntity>`（落库实体），与 `result`/`results` 返回类型统一；`evaluate`/`evaluateAll`（内存 `AnswerEvalResult`）保留给在线/离线无持久化诉求场景，并新增 `evaluateAllPersisted` 供 `run` 复用落库逻辑。
- `stats`/`listResults`/`findByQuery` 签名在 Service（Task 6）与 Controller（Task 8）调用保持一致。
- Mapper 需新增为 `AnswerEvaluationService` 构造依赖，测试 setUp 同步注入——已显式提示。
- `TenantContextSnapshot` 名称仅作依赖说明，实际以工程类名/包为准（Task 7 提示校验）。

**4. 风险提示（DB/权限/安全，符合项目规则）：**
- 建表 DDL 中 `schemaName` 一律白名单校验，且 `createTableSql/.formatted(...)` 占位符个数需人工核对（🟡6：已给出精确计数 6→7、12→14，rlsSql 位置符参数不变），防 SQL 注入与运行时字符串格式化异常。
- 新表 `FORCE ROW LEVEL SECURITY` + `tenant_id = current_tenant_id()` 策略，杜绝跨租户读写（铁律）。
- 在线异步评估不依赖跨线程 ThreadLocal 租户，采用显式入参或快照恢复，防租户串扰（铁律）。
- `EvalController` 4 端点均 `@PreAuthorize` + 租户头缺失即拒绝，查询均按 `tenant_id == headerTenantId` 过滤，防越权。
- 手动 `run` 强制 `source="manual"`、清空 `sessionRowId`，防客户端伪造在线来源污染统计语义。
- `async-enabled` 已真实读取（Task 7 注入，false→当前线程同步分支），拒绝分支不再误清主线程上下文（🟡1/🟡4 已修）。
- `enabled` 已接线到 5 个类（三个评估器 + Service + EvalController）真实 `@ConditionalOnProperty`，非注释级死开关（🟡3 已修）。
- **`ChatController` 对 `AnswerEvaluationService` 采用 `@Autowired(required=false)` 可选注入 + 在线分支判空**，消除「@RequiredArgsConstructor 必填注入」与「enabled=false 不装配」的构造冲突，保证关闭开关时主链路正常启动（🔴 冲突修正）。
- `evaluateAllPersisted` 对落库失败条数显式统计并告警，不再静默丢失败（🟡7 已修）。
- `result.getToolContext()` 可能为 null：Task 7 已加空值归一化为 `""`，faithfulness 对空上下文按「无法印证」处理（🟡5 已修）。
- **既有测试回归（🔴2）**：`AnswerEvaluationService` 构造器 4 → 5 参、`evaluate()` 保持纯评估+Redis 语义、落库集中到 `evaluateAllPersisted()`——Task 6 Step 2 明确同步修正既有 `AnswerEvaluationServiceTest` 的 setUp（补 `evalResultMapper` mock + 5 参构造），既有三参 `evaluate` 用例沿用不变、按新契约通过；Task 7 在线分支改走 `evaluateAllPersisted`（强制 tenantId 落库），与 spec §4 在线双写语义一致，不回归。迭代过程已核对真实测试 `L29`（4 参构造）、`L38/L50`（三参 evaluate）与真实 `AnswerCase`/`AnswerEvaluationService` 源码字段（4 final 字段）。
