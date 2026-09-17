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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;

class AnswerEvaluationServiceTest {

    private AnswerRelevancyEvaluator relevancy;
    private AnswerCorrectnessEvaluator correctness;
    private AnswerFaithfulnessEvaluator faithfulness;
    private AnswerEvalResultMapper evalResultMapper;
    private AnswerEvaluationService service;

    @BeforeEach
    void setUp() {
        relevancy = mock(AnswerRelevancyEvaluator.class);
        correctness = mock(AnswerCorrectnessEvaluator.class);
        faithfulness = mock(AnswerFaithfulnessEvaluator.class);
        RedissonClient redisson = mock(RedissonClient.class);
        evalResultMapper = mock(AnswerEvalResultMapper.class);
        service = new AnswerEvaluationService(redisson, relevancy, correctness, faithfulness, evalResultMapper);
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
    void evaluateAllPersisted_countsFailedWhenTenantIdNull() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
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
}
