package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

class AnswerEvaluationServiceTest {

    private AnswerRelevancyEvaluator relevancy;
    private AnswerCorrectnessEvaluator correctness;
    private AnswerFaithfulnessEvaluator faithfulness;
    private AnswerEvaluationService service;

    @BeforeEach
    void setUp() {
        relevancy = mock(AnswerRelevancyEvaluator.class);
        correctness = mock(AnswerCorrectnessEvaluator.class);
        faithfulness = mock(AnswerFaithfulnessEvaluator.class);
        RedissonClient redisson = mock(RedissonClient.class);
        service = new AnswerEvaluationService(redisson, relevancy, correctness, faithfulness);
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
}