package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.common.constant.RagConstant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 回答评估聚合服务：按维度依次评估，合成综合 pass/score。
 * 双写策略：Redis 即时缓冲层（Redisson RMapCache，租户键前缀 + TTL）
 *           + PG 持久化落库（每租户 schema 的 answer_eval_result 表）。
 * 落库一律取 AnswerCase.tenantId() 显式值且 null 拒绝，不依赖评估线程 ThreadLocal
 * （主线程 finally 已 clear，异步线程恒为 null，会落 tenant_id=0 永久不可见）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AnswerEvaluationService {

    private final RedissonClient redissonClient;
    private final AnswerRelevancyEvaluator relevancyEvaluator;
    private final AnswerCorrectnessEvaluator correctnessEvaluator;
    private final AnswerFaithfulnessEvaluator faithfulnessEvaluator;
    private final AnswerEvalResultMapper evalResultMapper;

    private static final String EVAL_PREFIX = RagConstant.CACHE_NAMESPACE + "eval:";
    private static final long EVAL_TTL_SECONDS = 60 * 60 * 24; // 24h

    /**
     * 评估单条回答，并写入 Redis 缓冲层（不落库、不抛异常）。
     * 纯离线/测试样本（tenantId==null，三参构造）与在线提数都可用。
     * 需要落库的调用方走 evaluateAndPersist / evaluateAllPersisted（强制校验 tenantId）。
     */
    public AnswerEvalResult evaluate(AnswerCase answerCase) {
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

    /** 便捷：兼容既有 evaluateAll（逐个评估，返回内存结果列表） */
    public List<AnswerEvalResult> evaluateAll(List<AnswerCase> cases) {
        if (cases == null) return List.of();
        return cases.stream().map(this::evaluate).filter(Objects::nonNull).toList();
    }

    /**
     * 批量评估并返回落库后的持久化实体（手动 run 用，与查看接口返回类型一致）。
     * 单条落库失败返回 null 并被剔除，此处对失败条数显式统计并告警，
     * 避免「返回条数少于请求却无任何信号」的静默丢失败。
     */
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
        // 落库并保留实体（供手动 run 返回持久化结果）
        AnswerEvalResultEntity entity = toEntity(answerCase, result);
        try {
            evalResultMapper.insert(entity);
            log.info("[EVAL] 已落库评估结果 id={}, tenantId={}, source={}",
                    entity.getId(), entity.getTenantId(), entity.getSource());
            return entity;
        } catch (Exception e) {
            // 落库失败不回抛，但返回 null 让调用方感知未持久化
            log.warn("[EVAL] 落库失败：{}", e.getMessage());
            return null;
        }
    }

    private AnswerEvalResultEntity toEntity(AnswerCase answerCase, AnswerEvalResult result) {
        // 【铁律】跨线程落库：tenantId 必须为显式值，不依赖评估线程 ThreadLocal
        // （恒为 null，会落 tenant_id=0）。null 直接拒绝，不做 0L 兜底——
        // 静默兜底会掩盖「租户丢失」错误并写入永远不可见的数据。
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

    /** 按时间范围查列表（分页取 limit 条，限 1~200） */
    public List<AnswerEvalResultEntity> listResults(Long tenantId, List<Long> sessionRowIds,
                                                    LocalDateTime from, LocalDateTime to, int limit) {
        LambdaQueryWrapper<AnswerEvalResultEntity> wrapper = new LambdaQueryWrapper<AnswerEvalResultEntity>()
                .eq(AnswerEvalResultEntity::getTenantId, tenantId);
        if (sessionRowIds != null && !sessionRowIds.isEmpty()) {
            wrapper.in(AnswerEvalResultEntity::getSessionRowId, sessionRowIds);
        }
        if (from != null) wrapper.ge(AnswerEvalResultEntity::getCreateTime, from);
        if (to != null) wrapper.le(AnswerEvalResultEntity::getCreateTime, to);
        int pageSize = (limit <= 0 || limit > 200) ? 50 : limit;
        return evalResultMapper.selectList(
                wrapper.orderByDesc(AnswerEvalResultEntity::getCreateTime).last("LIMIT " + pageSize));
    }

    /** 统计：总数 / pass 数（综合 pass 率）/ 平均分 / 三维均分（近似，取最近 200 条） */
    public Map<String, Object> stats(Long tenantId, LocalDateTime from, LocalDateTime to) {
        List<AnswerEvalResultEntity> rows = listResults(tenantId, null, from, to, 200);
        Map<String, Object> stats = new LinkedHashMap<>();
        long passCount = rows.stream().filter(e -> Boolean.TRUE.equals(e.getPass())).count();
        stats.put("total", rows.size());
        stats.put("passCount", passCount);
        stats.put("passRate", rows.isEmpty() ? 0.0 : passCount * 1.0 / rows.size());
        stats.put("avgScore", rows.isEmpty() ? 0.0
                : rows.stream().mapToDouble(AnswerEvalResultEntity::getScore).average().orElse(0.0));
        stats.put("avgRelevancyScore", rows.isEmpty() ? 0.0
                : rows.stream().mapToDouble(AnswerEvalResultEntity::getRelevancyScore).average().orElse(0.0));
        stats.put("avgCorrectnessScore", rows.isEmpty() ? 0.0
                : rows.stream().mapToDouble(AnswerEvalResultEntity::getCorrectnessScore).average().orElse(0.0));
        stats.put("avgFaithfulnessScore", rows.isEmpty() ? 0.0
                : rows.stream().mapToDouble(AnswerEvalResultEntity::getFaithfulnessScore).average().orElse(0.0));
        return stats;
    }

    private void writeToRedis(AnswerCase answerCase, AnswerEvalResult result) {
        try {
            Long tenantId = answerCase.tenantId();
            String queryText = answerCase.query() == null ? "" : answerCase.query();
            String hash = DigestUtils.md5DigestAsHex(queryText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String key = EVAL_PREFIX + (tenantId != null ? tenantId : "0") + ":" + hash;
            redissonClient.<Object, Object>getMapCache("answer-eval")
                    .put(key, result, EVAL_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("[EVAL] 已写入评估结果 key={}, pass={}, score={}", key, result.pass(), result.score());
        } catch (Exception e) {
            // 评估写入失败不影响评估结果本身，仅记录
            log.warn("[EVAL] 写入 Redis 失败：{}", e.getMessage());
        }
    }
}
