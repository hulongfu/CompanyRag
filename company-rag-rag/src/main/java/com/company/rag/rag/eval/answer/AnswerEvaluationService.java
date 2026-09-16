package com.company.rag.rag.eval.answer;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.tenant.context.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 回答评估聚合服务：按维度依次评估，合成综合 pass/score，并写入 Redis 临时缓冲层
 * （Redisson RMapCache，租户键前缀 + TTL），阶段 3 feedback 信号源再读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {

    private final RedissonClient redissonClient;
    private final AnswerRelevancyEvaluator relevancyEvaluator;
    private final AnswerCorrectnessEvaluator correctnessEvaluator;
    private final AnswerFaithfulnessEvaluator faithfulnessEvaluator;

    private static final String EVAL_PREFIX = RagConstant.CACHE_NAMESPACE + "eval:";
    private static final long EVAL_TTL_SECONDS = 60 * 60 * 24; // 24h

    /**
     * 评估单条回答，并写入 Redis 缓冲层。
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

    /** 批量评估入口 */
    public List<AnswerEvalResult> evaluateAll(List<AnswerCase> cases) {
        if (cases == null) return List.of();
        return cases.stream().map(this::evaluate).filter(Objects::nonNull).toList();
    }

    private void writeToRedis(AnswerCase answerCase, AnswerEvalResult result) {
        try {
            Long tenantId = TenantContext.getTenantId();
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