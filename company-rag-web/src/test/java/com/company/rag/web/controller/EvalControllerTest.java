package com.company.rag.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.rag.common.model.R;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvalResultEntity;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvalControllerTest {

    @Mock
    AnswerEvaluationService service;

    @InjectMocks
    EvalController controller;

    @Test
    void run_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.run(List.of(new AnswerCase("q", "c", "a")), null));
    }

    @Test
    void run_forcesManualSourceAndTenant() {
        when(service.evaluateAllPersisted(anyList())).thenReturn(List.of());
        R<List<AnswerEvalResultEntity>> r = controller.run(List.of(new AnswerCase("q", "c", "a")), 7L);

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
    void run_rejects_whenCasesNull_withTenant() {
        // cases 为 null 也可接受（化为空列表），不抛异常；此处验证 null 头仍拒绝优先
        assertThrows(IllegalArgumentException.class, () -> controller.run(null, null));
    }

    @Test
    void result_filtersByTenantHeader() {
        AnswerEvalResultEntity entity = new AnswerEvalResultEntity();
        entity.setTenantId(7L);
        when(service.findByQuery(anyLong(), anyString())).thenReturn(entity);
        R<AnswerEvalResultEntity> rr = controller.result("q", 7L);
        verify(service).findByQuery(7L, "q");
        assertNotNull(rr);
        assertNotNull(rr.getData());
        assertEquals(Long.valueOf(7L), rr.getData().getTenantId());
    }

    @Test
    void result_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.result("q", null));
    }

    @Test
    void results_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.results(List.of(1L), null, null, 50, null));
    }

    @Test
    void stats_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.stats(null, null, null));
    }
}
