package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.common.constant.RagConstant;
import com.company.rag.common.exception.BizException;
import com.company.rag.rag.eval.config.EvalProperties;
import com.company.rag.tenant.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
    private final EvalProperties evalProperties;
    private final EvalRegressionReportMapper regressionReportMapper;

    private static final String EVAL_PREFIX = RagConstant.CACHE_NAMESPACE + "eval:";
    private static final long EVAL_TTL_SECONDS = 60 * 60 * 24; // 24h
    /** 历史分页缺省页大小（plan 任务 4.2 固定 50，与 datasetLimitDefault 分属不同配置语义） */
    private static final int HISTORY_PAGE_SIZE_DEFAULT = 50;

    /**
     * 回归重跑租户键锁（spec §3.4 唯一落点，本期单实例进程内锁；多副本需升级 Redisson RLock）。
     * 锁键与 dataset 查询的 tenant_id 同源（均为 TenantContext.getTenantId() 解析值），
     * 否则锁与数据口径分裂会导致锁失效或锁错租户。
     */
    private final ConcurrentHashMap<Long, ReentrantLock> regressionLocks = new ConcurrentHashMap<>();

    /**
     * 评估单条回答，并写入 Redis 缓冲层（不落库、不抛异常）。
     * 纯离线/测试样本（tenantId==null，三参构造）与在线提数都可用。
     * 需要落库的调用方走 evaluateAndPersist / evaluateAllPersisted（强制校验 tenantId）。
     */
    public AnswerEvalResult evaluate(AnswerCase answerCase) {
        if (answerCase == null) {
            return null;
        }
        EvalDecision decision = doEvaluate(answerCase.query(), answerCase.context(), answerCase.answer());
        AnswerEvalResult result = new AnswerEvalResult(answerCase.query(), answerCase.context(),
                answerCase.answer(), decision.pass(), decision.score(), toDimensionScores(decision));

        writeToRedis(answerCase, result);
        return result;
    }

    /**
     * 唯一评估入口（spec §3.3）：三维依次评估并合成 pass/score。
     * 顺序固定 relevancy → correctness → faithfulness（保持重构前行为，不改乱）。
     */
    private EvalDecision doEvaluate(String query, String context, String answer) {
        boolean relevancy = relevancyEvaluator.evaluate(query, context, answer);
        boolean correctness = correctnessEvaluator.evaluate(query, context, answer);
        boolean faithfulness = faithfulnessEvaluator.evaluate(query, context, answer);

        // 每个三元表达式各自加括号：+ 优先级高于 ?:，缺括号会把 0.0 + boolean 解析成非法操作数
        double score = ((relevancy ? 1.0 : 0.0) + (correctness ? 1.0 : 0.0)
                + (faithfulness ? 1.0 : 0.0)) / 3.0;

        return new EvalDecision(relevancy, correctness, faithfulness,
                relevancy && correctness && faithfulness, score);
    }

    /**
     * 不写 Redis、不落库的评估（仅供回归重跑逐样本使用，spec R3：不污染在线缓存）。
     */
    public EvalDecision evaluateNoCache(String query, String context, String answer) {
        return doEvaluate(query, context, answer);
    }

    /** 三维布尔派生落库维度分：键用全名，值 true→1.0 / false→0.0（不另存独立维度分） */
    private Map<String, Double> toDimensionScores(EvalDecision decision) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("relevancy", decision.relevancy() ? 1.0 : 0.0);
        scores.put("correctness", decision.correctness() ? 1.0 : 0.0);
        scores.put("faithfulness", decision.faithfulness() ? 1.0 : 0.0);
        return scores;
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
        EvalDecision decision = doEvaluate(answerCase.query(), answerCase.context(), answerCase.answer());
        AnswerEvalResult result = new AnswerEvalResult(answerCase.query(), answerCase.context(),
                answerCase.answer(), decision.pass(), decision.score(), toDimensionScores(decision));

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

    /**
     * 抽取带人工标签（feedback≠0）的评估样本集（spec §3.2.2 dataset 口径）。
     *
     * 【租户单源钉死】：方法签名保留 tenantId 形参仅为对齐 spec 签名，真实取值始终以
     * TenantContext 为准（与 schema 同源），调用方传入的 raw tenantId 不直通 Mapper。
     *
     * @param tenantId 死参（以 TenantContext.getTenantId() 为准），调用方须传 context 解析值
     */
    public List<LabelledEvalSample> dataset(Long tenantId, LocalDateTime from, LocalDateTime to, int limit) {
        // 【铁律】校验全部以 TenantContext 为唯一来源，不信任参数       （防 schema 与 tenant 指向不一致）
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to 不能为空，均须提供时间范围");
        }
        String schema = resolveSchema();
        Long resolvedTenantId = resolveTenantId();
        int effectiveLimit = clampLimit(limit);
        return evalResultMapper.selectDataset(schema, resolvedTenantId, from, to, effectiveLimit);
    }

    /**
     * 校验并解析当前租户 schema（取自 TenantContext.getSchema()，白名单防 SQL 注入）。
     */
    String resolveSchema() {        String schema = TenantContext.getSchema();
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("无法解析租户 schema，请确认请求已携带租户上下文");
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("非法 schema 名: " + schema);
        }
        return schema;
    }

    /**
     * 校验并解析当前租户 ID（单源自 TenantContext.getTenantId()，不走请求头）。
     */
    Long resolveTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("无法解析租户 ID，请确认请求已携带租户上下文");
        }
        return tenantId;
    }

    /**
     * 回归报告历史分页（spec §3.4）：查 eval_regression_report（非 answer_eval_result）。
     * 租户/schema 与 dataset 同源；手写 LIMIT/OFFSET + 独立 count（无 MP 分页插件）。
     */
    public Map<String, Object> history(Long tenantId, int page, int pageSize) {
        // 【铁律】schema 必须经白名单后再交给 ${schema} 插值，防越权 schema
        String schema = resolveSchema();
        Long resolvedTenantId = resolveTenantId();
        int current = clampPage(page);
        int size = clampPageSize(pageSize);
        long offset = (long) (current - 1) * size;   // long 防大页码 int 溢出成负 offset
        List<EvalRegressionReportEntity> records =
                regressionReportMapper.selectHistoryPage(schema, resolvedTenantId, size, offset);
        long total = regressionReportMapper.countHistory(schema, resolvedTenantId);

        // 对齐 IPage 约定的响应结构，前端无需适配新字段名
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", size);
        result.put("current", current);
        return result;
    }

    /** page 回落：page<=0 → 1（不设上限，越界页由 SQL 空结果自然表达） */
    int clampPage(int page) {
        return page <= 0 ? 1 : page;
    }

    /** pageSize 收敛：<=0 回落 50；>historyPageMax 收敛 historyPageMax（独立键，不借 datasetLimitMax） */
    int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return HISTORY_PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, evalProperties.getHistoryPageMax());
    }

    /** limit 上限收敛：0<limit<=200 透传；limit<=0 回落 datasetLimitDefault；limit>200 收敛 datasetLimitMax。 */
    int clampLimit(int limit) {
        if (limit <= 0) {
            return evalProperties.getDatasetLimitDefault();
        }
        if (limit > evalProperties.getDatasetLimitMax()) {
            return evalProperties.getDatasetLimitMax();
        }
        return limit;
    }

    /**
     * 回归重跑（spec §3.3）：dataset 取批 → 先判空（0 样本不抢锁不落快照）→
     * tryLock(租户键) → 逐样本 evaluateNoCache 重跑 → 指标报告 → 落快照。
     * 锁唯一落点在此（Controller 仅转发 409）；limit 透传给 dataset 保证同批口径。
     */
    public EvalRegressionReportEntity regression(Long tenantId, LocalDateTime from, LocalDateTime to, int limit) {
        // ① 先判空、后抢锁（spec 十一轮）：0 样本不白占该租户锁最长 30s
        List<LabelledEvalSample> samples = dataset(tenantId, from, to, limit);
        if (samples.isEmpty()) {
            return null;
        }
        // 锁键与 dataset 查询 tenant_id 同源（context 解析值）
        Long lockKey = resolveTenantId();
        ReentrantLock lock = regressionLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(evalProperties.getRegressionLockTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(409, "回归评估被中断，请重试");
            }
            if (!locked) {
                // 抢不到锁：不重跑、不落快照（同租户同批重跑必须串行）
                throw new BizException(409, "该租户回归评估正在进行中，请稍后重试");
            }
            return doRegressionLocked(samples, from, to);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /** 锁内执行：重跑 + 指标 + 落快照（调用方已持租户锁） */
    private EvalRegressionReportEntity doRegressionLocked(List<LabelledEvalSample> samples,
                                                           LocalDateTime from, LocalDateTime to) {
        // 指纹（spec 四轮阻断项3）：Java 侧排序拼接后 md5，与返回集天然一致，免 0 行 string_agg NULL
        String fingerprint = computeFingerprint(samples);

        int n = samples.size();
        int tp = 0, tn = 0, fp = 0, fn = 0;
        int passCount = 0;              // re_run_pass=true 条数
        int relevancyAgree = 0, correctnessAgree = 0, faithfulnessAgree = 0;
        int persistedPassAgree = 0;
        double scoreSum = 0.0, relevancySum = 0.0, correctnessSum = 0.0, faithfulnessSum = 0.0;

        for (LabelledEvalSample s : samples) {
            EvalDecision d = evaluateNoCache(s.query(), s.context(), s.answer());
            boolean humanPositive = s.humanLabel() != null && s.humanLabel() > 0;

            // 四格：re_run_pass vs humanLabel（human=1 为正类；humanLabel∈{1,-1}）
            if (d.pass() && humanPositive) tp++;
            else if (d.pass()) fp++;
            else if (!humanPositive) tn++;
            else fn++;

            if (d.pass()) passCount++;
            if (d.relevancy() == humanPositive) relevancyAgree++;
            if (d.correctness() == humanPositive) correctnessAgree++;
            if (d.faithfulness() == humanPositive) faithfulnessAgree++;
            // persisted_pass_i 直接取 dataset 返回的落库 pass（=e.pass），不按 eval_id 回查
            if (d.pass() == Boolean.TRUE.equals(s.persistedPass())) persistedPassAgree++;

            scoreSum += d.score();
            relevancySum += d.relevancy() ? 1.0 : 0.0;
            correctnessSum += d.correctness() ? 1.0 : 0.0;
            faithfulnessSum += d.faithfulness() ? 1.0 : 0.0;
        }

        // 指标：int/int 一律显式 (double) cast（不 cast 商恒 0）；分母 0 → 1.0（spec 字面）
        double accuracy = (double) (tp + tn) / n;
        double passRate = (double) passCount / n;
        double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        double negativeRecall = (tn + fp) == 0 ? 1.0 : (double) tn / (tn + fp);
        // F1：p=r==0 → 0.0（灾难场景不显示满分）；「仅 TN」退化场景按 spec 字面照算不特判
        double f1 = (precision == 0.0 && recall == 0.0) ? 0.0 : 2 * precision * recall / (precision + recall);

        EvalRegressionReportEntity report = new EvalRegressionReportEntity();
        // 【铁律】落库显式租户 + null 拒绝（回归锁内仍在请求线程，context 可用；防未来挪异步线程静默落 tenant_id=0）
        report.setTenantId(resolveTenantId());
        report.setRuleVersion(evalProperties.getRuleVersion());
        report.setDatasetFingerprint(fingerprint);
        report.setDatasetFrom(from);
        report.setDatasetTo(to);
        report.setSampleCount(n);
        report.setPassRate(passRate);
        report.setAvgScore(scoreSum / n);   // scoreSum 已 double，/n 自动双精度
        report.setAvgRelevancy(relevancySum / n);
        report.setAvgCorrectness(correctnessSum / n);
        report.setAvgFaithfulness(faithfulnessSum / n);
        report.setPersistedPassAgree((double) persistedPassAgree / n);
        report.setTp(tp);
        report.setTn(tn);
        report.setFp(fp);
        report.setFn(fn);
        report.setAccuracy(accuracy);
        report.setPrecision(precision);
        report.setRecall(recall);
        report.setF1(f1);
        report.setNegativeRecall(negativeRecall);
        report.setRelevancyAgree((double) relevancyAgree / n);
        report.setCorrectnessAgree((double) correctnessAgree / n);
        report.setFaithfulnessAgree((double) faithfulnessAgree / n);

        regressionReportMapper.insert(report);
        log.info("[EVAL] 回归快照已落库 reportId={}, tenantId={}, fingerprint={}, n={}",
                report.getId(), report.getTenantId(), fingerprint, n);
        return report;
    }

    /** 数据集指纹：sessionRowId 排序后逗号拼接取 md5（与返回集一致，null 行已由 dataset SQL 过滤） */
    private String computeFingerprint(List<LabelledEvalSample> samples) {
        String joined = samples.stream()
                .map(LabelledEvalSample::sessionRowId)
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return DigestUtils.md5DigestAsHex(joined.getBytes(StandardCharsets.UTF_8));
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
