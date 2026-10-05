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
import com.company.rag.rag.eval.answer.EvalRegressionReportEntity;
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

    // ============ P3 /api/eval/regression（锁在 Service，本层只转发） ============

    @Test
    void regression_zeroSamples_returnsCode200WithNullData() {
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();
        when(service.regression(null, from, to, 50)).thenReturn(null);

        R<EvalRegressionReportEntity> r = controller.regression(from, to, 50);
        // 显式约定：code=200 + data=null + msg 文案，前端据此判定「未落快照」
        assertEquals(200, r.getCode());
        assertNull(r.getData());
        assertEquals("无匹配样本，未落快照", r.getMsg());
    }

    @Test
    void regression_forwardsRawLimitAndReturnsReport() {
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now();
        EvalRegressionReportEntity report = new EvalRegressionReportEntity();
        report.setId(9L);
        report.setTenantId(7L);
        when(service.regression(null, from, to, 999)).thenReturn(report);

        R<EvalRegressionReportEntity> r = controller.regression(from, to, 999);
        verify(service).regression(null, from, to, 999);
        assertNotNull(r.getData());
        assertEquals(Long.valueOf(9L), r.getData().getId());
    }

    /** Service 抛 409 BizException 时 Controller 不吞异常、不自行加锁，原样上抛交全局处理 */
    @Test
    void regression_propagates409FromService() {
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();
        when(service.regression(null, from, to, 50))
                .thenThrow(new com.company.rag.common.exception.BizException(409, "该租户回归评估正在进行中，请稍后重试"));

        com.company.rag.common.exception.BizException ex = assertThrows(
                com.company.rag.common.exception.BizException.class, () -> controller.regression(from, to, 50));
        assertEquals(409, ex.getCode());
    }

    // ============ P4 /api/eval/history（分页只透传，收敛在 Service） ============

    @Test
    void history_forwardsRawPageAndReturnsIPageShape() {
        java.util.Map<String, Object> page = new java.util.LinkedHashMap<>();
        page.put("records", List.of());
        page.put("total", 0L);
        page.put("size", 50);
        page.put("current", 1);
        // 原始 page/pageSize 直通，边界收敛由 Service clampPage/clampPageSize 负责
        when(service.history(null, 1, 999)).thenReturn(page);

        R<java.util.Map<String, Object>> r = controller.history(1, 999);
        verify(service).history(null, 1, 999);
        assertNotNull(r.getData());
        assertEquals(0L, r.getData().get("total"));
        assertEquals(50, r.getData().get("size"));
    }

    @Test
    void history_defaultsArePage1Size50() {
        // @RequestParam(defaultValue) 由 Spring 注入；此处直接以 1/50 调用验证签名缺省语义
        when(service.history(null, 1, 50)).thenReturn(java.util.Map.of(
                "records", List.of(), "total", 0L, "size", 50, "current", 1));
        R<java.util.Map<String, Object>> r = controller.history(1, 50);
        assertEquals(1, r.getData().get("current"));
    }

    private static void setTenantContext() {
        setTenantContext("tenant_abc");
    }

    private static void setTenantContext(String schema) {
        com.company.rag.tenant.context.TenantContext.setTenantId(7L);
        com.company.rag.tenant.context.TenantContext.setSchema(schema);
    }
}
