package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;

class AnswerEvaluationServiceTest {

    private AnswerRelevancyEvaluator relevancy;
    private AnswerCorrectnessEvaluator correctness;
    private AnswerFaithfulnessEvaluator faithfulness;
    private AnswerEvalResultMapper evalResultMapper;
    private EvalRegressionReportMapper regressionReportMapper;
    private AnswerEvaluationService service;
    private RedissonClient redisson;
    private com.company.rag.rag.eval.config.EvalProperties evalProperties;

    @BeforeEach
    void setUp() {
        relevancy = mock(AnswerRelevancyEvaluator.class);
        correctness = mock(AnswerCorrectnessEvaluator.class);
        faithfulness = mock(AnswerFaithfulnessEvaluator.class);
        redisson = mock(RedissonClient.class);
        evalResultMapper = mock(AnswerEvalResultMapper.class);
        regressionReportMapper = mock(EvalRegressionReportMapper.class);
        evalProperties = new com.company.rag.rag.eval.config.EvalProperties();
        // 使用默认值（datasetLimitDefault=50 / datasetLimitMax=200），测试内再按需覆盖
        service = new AnswerEvaluationService(redisson, relevancy, correctness, faithfulness, evalResultMapper,
                evalProperties, regressionReportMapper);
    }

    @AfterEach
    void tearDown() {
        com.company.rag.tenant.context.TenantContext.clear();
    }

    @Test
    void evaluate_allPass_whenAllDimensionsPass() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        AnswerEvalResult result = service.evaluate(new AnswerCase("q", "ctx", "an answer here that is long"));
        assertNotNull(result);
        assertTrue(result.pass());
        assertTrue(result.score() > 0);
    }

    @Test
    void evaluate_fails_whenAnyDimensionFails() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(false);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        AnswerEvalResult result = service.evaluate(new AnswerCase("q", "ctx", "an answer"));
        assertNotNull(result);
        assertFalse(result.pass());
    }

    @Test
    void evaluate_returnsNull_forNullCase() {
        assertNull(service.evaluate(null));
        assertTrue(service.evaluateAll(null).isEmpty());
        assertTrue(service.evaluateAll(List.of()).isEmpty());
    }

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
    void evaluateAllPersisted_countsFailedWhenTenantIdNull() {        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        // tenantId=null（三参构造）经 evaluateAllPersisted 强制落库被拒并剔除，而非抛给调用方
        AnswerCase offline = new AnswerCase("q", "ctx", "a sufficiently long answer");
        List<AnswerEvalResultEntity> entities = service.evaluateAllPersisted(List.of(offline));
        assertTrue(entities.isEmpty());
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

    // ============ P1 数据集抽取（spec §3.2.2 dataset） ============

    /** 便捷：模拟已鉴权请求的用户租户上下文 */
    private static void setTenantContext(Long tenantId, String schema) {
        com.company.rag.tenant.context.TenantContext.setTenantId(tenantId);
        com.company.rag.tenant.context.TenantContext.setSchema(schema);
    }

    @Test
    void dataset_resolvesTenantFromContext_andPassesThrough() {
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();

        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 10))
                .thenReturn(List.of(new LabelledEvalSample("q", "ctx", "ans", 42L, true, 0.8,
                        (short) 1, 1001L, 1L, to)));

        List<LabelledEvalSample> samples = service.dataset(null, from, to, 10);
        assertEquals(1, samples.size());
        assertEquals(42L, samples.get(0).tenantId());
        assertEquals((short) 1, samples.get(0).humanLabel());
        // limit=10 属 0<10<=200，透传（不回落、不收敛）
        verify(evalResultMapper).selectDataset("tenant_abc", 42L, from, to, 10);
    }

    @Test
    void dataset_fallsBackAndConvergesLimit() {
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();

        // limit<=0 → 回落 datasetLimitDefault(50)
        service.dataset(null, from, to, 0);
        verify(evalResultMapper).selectDataset("tenant_abc", 42L, from, to, 50);
        // limit>200 → 收敛 datasetLimitMax(200)
        service.dataset(null, from, to, 500);
        verify(evalResultMapper).selectDataset("tenant_abc", 42L, from, to, 200);
    }

    @Test
    void dataset_rejects_missingFromOrTo() {
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dataset(null, null, now, 50));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dataset(null, now, null, 50));
    }

    @Test
    void dataset_rejects_missingTenantContext() {
        // 未验证用户上下文（tenantId=null）→ 400
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dataset(null, now.minusDays(1), now, 50));
    }

    @Test
    void dataset_rejects_illegalSchema() {
        setTenantContext(42L, "tenant_abc; DROP TABLE x;");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dataset(null, now.minusDays(1), now, 50));
    }

    @Test
    void dataset_rejects_blankSchema() {
        setTenantContext(42L, "   ");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dataset(null, now.minusDays(1), now, 50));
    }

    // ============ P2 doEvaluate 唯一入口 + evaluateNoCache（spec §3.3） ============

    /** evaluateNoCache 不得触碰 Redis（不污染在线缓存），也不落库 */
    @Test
    void evaluateNoCache_doesNotWriteRedis_norPersist() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        EvalDecision decision = service.evaluateNoCache("q", "ctx", "a sufficiently long answer");
        assertTrue(decision.pass());
        assertEquals(1.0, decision.score(), 1e-9);

        verify(redisson, org.mockito.Mockito.never()).getMapCache(anyString());
        verify(evalResultMapper, org.mockito.Mockito.never()).insert(any(AnswerEvalResultEntity.class));
    }

    /** 落库维度分必须由 doEvaluate 三维布尔派生，与判定严格一致（不另存独立维度分） */
    @Test
    void evaluateAllPersisted_dimensionScoresDerivedFromBooleans() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(false);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        service.evaluateAllPersisted(List.of(
                new AnswerCase("q", "ctx", "a sufficiently long answer", 42L, 1001L, "manual")));

        ArgumentCaptor<AnswerEvalResultEntity> captor = ArgumentCaptor.forClass(AnswerEvalResultEntity.class);
        verify(evalResultMapper).insert(captor.capture());
        AnswerEvalResultEntity entity = captor.getValue();
        assertEquals(1.0, entity.getRelevancyScore(), 1e-9);
        assertEquals(0.0, entity.getCorrectnessScore(), 1e-9);
        assertEquals(1.0, entity.getFaithfulnessScore(), 1e-9);
        // 两真一假 → 综合分 2/3（整数除法会塌成 0.0/1.0，此处守护 double 口径）
        assertEquals(2.0 / 3.0, entity.getScore(), 1e-9);
        assertFalse(entity.getPass());
    }

    /** doEvaluate 的 pass 必须与 AnswerEvalResult.allPass(三维) 等价（测试断言，不各自推导） */
    @Test
    void evaluateNoCache_passEqualsAllPassOfThreeDimensions() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(false);

        EvalDecision decision = service.evaluateNoCache("q", "ctx", "ans");
        java.util.Map<String, Boolean> passes = new java.util.LinkedHashMap<>();
        passes.put("relevancy", decision.relevancy());
        passes.put("correctness", decision.correctness());
        passes.put("faithfulness", decision.faithfulness());
        assertEquals(AnswerEvalResult.allPass(passes), decision.pass());
        assertFalse(decision.pass());
    }

    /** 维度调用顺序钉死 relevancy → correctness → faithfulness */
    @Test
    void evaluateNoCache_invokesDimensionsInOrder() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        var inOrder = org.mockito.Mockito.inOrder(relevancy, correctness, faithfulness);
        service.evaluateNoCache("q", "ctx", "ans");
        inOrder.verify(relevancy).evaluate("q", "ctx", "ans");
        inOrder.verify(correctness).evaluate("q", "ctx", "ans");
        inOrder.verify(faithfulness).evaluate("q", "ctx", "ans");
        inOrder.verifyNoMoreInteractions();
    }

    // ============ P3 回归重跑（spec §3.3/§3.4） ============

    private static LabelledEvalSample sample(String query, Long rowId, boolean persistedPass, short humanLabel) {
        return new LabelledEvalSample(query, "ctx", "ans", 42L, persistedPass, 0.5,
                humanLabel, rowId, rowId, java.time.LocalDateTime.now());
    }

    /** 反射取 Service 内部租户锁表（仅测试用，钉死锁键来源与 0 样本未建锁） */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantLock>
            locksOf(AnswerEvaluationService svc) throws Exception {
        var f = AnswerEvaluationService.class.getDeclaredField("regressionLocks");
        f.setAccessible(true);
        return (java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantLock>) f.get(svc);
    }

    /** 指纹 = md5(排序后 sessionRowId 逗号拼接)，与返回集一致 */
    @Test
    void regression_fingerprintIsMd5OfSortedRowIds() {
        evalProperties.setRuleVersion("v1.0");
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();
        // 故意乱序传入，指纹必须按排序后的 "1,2" 计算
        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 50))
                .thenReturn(List.of(sample("q2", 2L, true, (short) 1), sample("q1", 1L, true, (short) 1)));
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        EvalRegressionReportEntity report = service.regression(null, from, to, 50);

        String expectedFp = org.springframework.util.DigestUtils.md5DigestAsHex(
                "1,2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expectedFp, report.getDatasetFingerprint());
        assertEquals("v1.0", report.getRuleVersion());
        assertEquals(42L, report.getTenantId());
        assertEquals(2, report.getSampleCount());
        verify(regressionReportMapper).insert(report);
        // 回归重跑不得写 Redis 缓存（spec R3）
        verify(redisson, org.mockito.Mockito.never()).getMapCache(anyString());
    }

    /**
     * 四格 + 全指标公式（含 (double) cast、F1、三维一致率、persisted_pass_agree）。
     * 样本设计（正类 = humanLabel=1，即 q1/q4）：
     *   q1 r=T c=T f=T → 重跑 pass=T, human=+1 → TP
     *   q2 r=T c=T f=T → 重跑 pass=T, human=-1 → FP
     *   q3 r=T c=T f=F → 重跑 pass=F, human=-1 → TN
     *   q4 r=T c=F f=F → 重跑 pass=F, human=+1 → FN
     */
    @Test
    void regression_metrics_fourCellsAndFormulas() {
        evalProperties.setRuleVersion("v1.0");
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();
        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 50)).thenReturn(List.of(
                sample("q1", 1L, true, (short) 1),
                sample("q2", 2L, true, (short) -1),
                sample("q3", 3L, true, (short) -1),
                sample("q4", 4L, false, (short) 1)));
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(org.mockito.ArgumentMatchers.eq("q4"), anyString(), anyString())).thenReturn(false);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(org.mockito.ArgumentMatchers.eq("q3"), anyString(), anyString())).thenReturn(false);
        when(faithfulness.evaluate(org.mockito.ArgumentMatchers.eq("q4"), anyString(), anyString())).thenReturn(false);

        EvalRegressionReportEntity r = service.regression(null, from, to, 50);

        assertEquals(1, r.getTp());
        assertEquals(1, r.getFp());
        assertEquals(1, r.getTn());
        assertEquals(1, r.getFn());
        assertEquals(0.5, r.getAccuracy(), 1e-9);        // (tp+tn)/n = 2/4
        assertEquals(0.5, r.getPassRate(), 1e-9);        // 重跑 pass 2/4
        assertEquals(0.5, r.getPrecision(), 1e-9);       // tp/(tp+fp) = 1/2
        assertEquals(0.5, r.getRecall(), 1e-9);          // tp/(tp+fn) = 1/2
        assertEquals(0.5, r.getF1(), 1e-9);
        assertEquals(0.5, r.getNegativeRecall(), 1e-9);  // tn/(tn+fp) = 1/2
        assertEquals(0.75, r.getPersistedPassAgree(), 1e-9); // 仅 q3（落库 true vs 重跑 false）不一致
        // 三维一致率（布尔 vs human 正负同向）：relevancy 2/4、correctness 1/4、faithfulness 2/4
        assertEquals(0.5, r.getRelevancyAgree(), 1e-9);
        assertEquals(0.25, r.getCorrectnessAgree(), 1e-9);
        assertEquals(0.5, r.getFaithfulnessAgree(), 1e-9);
        // 均分：q1=1, q2=1, q3=2/3, q4=1/3 → sum=3 → 0.75
        assertEquals(0.75, r.getAvgScore(), 1e-9);
        assertEquals(1.0, r.getAvgRelevancy(), 1e-9);
        assertEquals(0.75, r.getAvgCorrectness(), 1e-9);
        assertEquals(0.5, r.getAvgFaithfulness(), 1e-9);
    }

    /** F1 守护：tp=0 且 p=r=0 → 0.0（灾难场景不显示满分） */
    @Test
    void regression_f1Guard_whenNoTruePositive() {
        evalProperties.setRuleVersion("v1.0");
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();
        // q1 重跑 pass 但人工差评 → FP；q2 重跑 fail 但人工好评 → FN ⇒ tp=0 且两分母均 >0
        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 50)).thenReturn(List.of(
                sample("q1", 1L, true, (short) -1), sample("q2", 2L, false, (short) 1)));
        when(relevancy.evaluate(org.mockito.ArgumentMatchers.eq("q1"), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(org.mockito.ArgumentMatchers.eq("q1"), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(org.mockito.ArgumentMatchers.eq("q1"), anyString(), anyString())).thenReturn(true);
        when(relevancy.evaluate(org.mockito.ArgumentMatchers.eq("q2"), anyString(), anyString())).thenReturn(false);
        when(correctness.evaluate(org.mockito.ArgumentMatchers.eq("q2"), anyString(), anyString())).thenReturn(false);
        when(faithfulness.evaluate(org.mockito.ArgumentMatchers.eq("q2"), anyString(), anyString())).thenReturn(false);

        EvalRegressionReportEntity r = service.regression(null, from, to, 50);
        assertEquals(0.0, r.getPrecision(), 1e-9);   // 0/(0+1)
        assertEquals(0.0, r.getRecall(), 1e-9);      // 0/(0+1)
        assertEquals(0.0, r.getF1(), 1e-9);          // p=r=0 → 0.0（非 2*0*0/0 = NaN）
        assertEquals(0, r.getTp());
        assertEquals(1, r.getFp());
        assertEquals(1, r.getFn());
    }

    /** 0 样本：不落快照、且锁根本未被创建（钉死「先判空、后抢锁」顺序） */
    @Test
    void regression_zeroSamples_noSnapshot_noLockCreated() throws Exception {
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();
        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 50)).thenReturn(List.of());

        assertNull(service.regression(null, from, to, 50));
        verify(regressionReportMapper, org.mockito.Mockito.never()).insert(any(EvalRegressionReportEntity.class));
        // 锁 map 为空 → tryLock 从未被申请（0 样本不白占租户锁）
        assertTrue(locksOf(service).isEmpty());
    }

    /** 同租户锁被占用 → tryLock 超时抛 BizException(409)，不重跑不落库 */
    @Test
    void regression_lockBusy_throws409() throws Exception {
        evalProperties.setRegressionLockTimeoutMs(50);
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime to = java.time.LocalDateTime.now();
        when(evalResultMapper.selectDataset("tenant_abc", 42L, from, to, 50))
                .thenReturn(List.of(sample("q1", 1L, true, (short) 1)));

        // ReentrantLock 可重入：同线程持有会直接 tryLock 成功，故必须用独立线程持锁模拟并发第二请求
        java.util.concurrent.locks.ReentrantLock held = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                held.unlock();
            }
        });
        locksOf(service).put(42L, held);
        holder.start();
        assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS), "持锁线程未能及时获锁");
        try {
            com.company.rag.common.exception.BizException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            com.company.rag.common.exception.BizException.class,
                            () -> service.regression(null, from, to, 50));
            assertEquals(409, ex.getCode());
            // 抢不到锁：不重跑、不落库
            verify(regressionReportMapper, org.mockito.Mockito.never()).insert(any(EvalRegressionReportEntity.class));
            verify(relevancy, org.mockito.Mockito.never()).evaluate(anyString(), anyString(), anyString());
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    // ============ P4 回归报告历史分页（spec §3.4） ============

    /** page=3/pageSize=10 → offset=(3-1)*10=20，响应为 IPage 约定四字段 */
    @Test
    void history_computesOffsetAndReturnsIPageShape() {
        setTenantContext(42L, "tenant_abc");
        java.time.LocalDateTime t = java.time.LocalDateTime.now();
        when(regressionReportMapper.selectHistoryPage("tenant_abc", 42L, 10, 20L))
                .thenReturn(List.of(reportOf(1L, t)));
        when(regressionReportMapper.countHistory("tenant_abc", 42L)).thenReturn(7L);

        java.util.Map<String, Object> r = service.history(null, 3, 10);

        assertEquals(7L, r.get("total"));
        assertEquals(10, r.get("size"));
        assertEquals(3, r.get("current"));
        assertEquals(1, ((List<?>) r.get("records")).size());
        verify(regressionReportMapper).selectHistoryPage("tenant_abc", 42L, 10, 20L);
    }

    /** 页码收敛（plan 任务 4.2）：page<=0→1；pageSize<=0→50；>historyPageMax(200)→200 */
    @Test
    void history_clampPageAndPageSize() {
        assertEquals(1, service.clampPage(0));
        assertEquals(1, service.clampPage(-3));
        assertEquals(7, service.clampPage(7));

        assertEquals(50, service.clampPageSize(0));
        assertEquals(50, service.clampPageSize(-5));
        assertEquals(200, service.clampPageSize(999));   // 收敛 historyPageMax，不借 datasetLimitMax
        assertEquals(30, service.clampPageSize(30));
    }

    /** 大页码 offset 用 long 计算，防 int 溢出成负 offset（PG 负 OFFSET 直接报错） */
    @Test
    void history_largePageOffsetDoesNotOverflow() {
        setTenantContext(42L, "tenant_abc");
        when(regressionReportMapper.selectHistoryPage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        when(regressionReportMapper.countHistory("tenant_abc", 42L)).thenReturn(0L);

        service.history(null, Integer.MAX_VALUE, 50);

        long expectedOffset = (long) (Integer.MAX_VALUE - 1) * 50L;
        assertTrue(expectedOffset > 0);
        verify(regressionReportMapper).selectHistoryPage("tenant_abc", 42L, 50, expectedOffset);
    }

    /** schema 非法时拒绝，不允许裸传到 ${schema} */
    @Test
    void history_rejectsIllegalSchema() {
        setTenantContext(42L, "bad-schema");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.history(null, 1, 50));
        verify(regressionReportMapper, org.mockito.Mockito.never()).selectHistoryPage(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static EvalRegressionReportEntity reportOf(Long id, java.time.LocalDateTime runTime) {
        EvalRegressionReportEntity e = new EvalRegressionReportEntity();
        e.setId(id);
        e.setTenantId(42L);
        e.setRunTime(runTime);
        return e;
    }
}
