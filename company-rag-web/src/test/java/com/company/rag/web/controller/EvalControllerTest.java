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
import com.company.rag.rag.eval.answer.LabelledEvalSample;
import java.time.LocalDateTime;
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

    // ============ P1 /api/eval/dataset（spec §3.2.2，走 TenantContext，不读 X-Tenant-Id 头） ============

    @Test
    void dataset_defaultsLimit50_andForwardWhenContextSet() {
        setTenantContext();
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();
        when(service.dataset(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to), org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(new LabelledEvalSample("q", "ctx", "ans", 7L, true, 0.9, (short) 1, 1L, 1L, to)));

        R<List<LabelledEvalSample>> r = controller.dataset(from, to, 50);
        // 缺省 limit 由 @RequestParam defaultValue=50 赋值
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
    }

    @Test
    void dataset_forwardsRawLimitAndTenantFromContext() {
        setTenantContext();
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now();
        // 控制器只透传原始 limit，上/下限收敛由 Service.clampLimit 负责（已在 Service 单测覆盖）
        when(service.dataset(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to), org.mockito.ArgumentMatchers.eq(999)))
                .thenReturn(List.of());

        R<List<LabelledEvalSample>> r = controller.dataset(from, to, 999);
        assertNotNull(r);
        verify(service).dataset(null, from, to, 999);
    }

    private static void setTenantContext() {
        setTenantContext("tenant_abc");
    }

    private static void setTenantContext(String schema) {
        com.company.rag.tenant.context.TenantContext.setTenantId(7L);
        com.company.rag.tenant.context.TenantContext.setSchema(schema);
    }
}
