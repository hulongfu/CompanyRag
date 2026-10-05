package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvalResultEntity;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import com.company.rag.rag.eval.answer.EvalRegressionReportEntity;
import com.company.rag.rag.eval.answer.LabelledEvalSample;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答评估 Controller：手动评估 + 查看 + 统计。
 * 全部按鉴权用户 + X-Tenant-Id 头隔离租户，防止越权跨租户读写。
 */
@Slf4j
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true")
public class EvalController {

    private final AnswerEvaluationService answerEvaluationService;

    /** 手动批量评估（source=manual，返回落库后的持久化实体，与查看接口类型一致）。
     *  仅 admin/user 可触发，viewer 只读不可评 */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public R<List<AnswerEvalResultEntity>> run(@RequestBody List<AnswerCase> cases,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        // 【安全关键】租户 ID 必须从请求头获取（已过 JwtAuthenticationFilter 校验）
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
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        return R.ok(answerEvaluationService.findByQuery(headerTenantId, query));
    }

    /** 按时间范围查评估列表 */
    @GetMapping("/results")
    @PreAuthorize("isAuthenticated()")
    public R<List<AnswerEvalResultEntity>> results(
            @RequestParam(required = false) List<Long> sessionRowIds,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
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
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        return R.ok(answerEvaluationService.stats(headerTenantId, from, to));
    }

    /**
     * 抽取带人工标签的评估样本集（spec §3.2.2 dataset）。
     * 【租户单源】走 TenantContext（由 JWT 过滤器双写），不读 X-Tenant-Id 头：
     * 防调用方传入 schema 与 tenant 指向不一致；tenantId 参数由 Service 以 context 解析。
     * 不加锁（锁仅在回归 Service 内）；limit>200 收敛、<=0 回落默认 50。
     */
    @PostMapping("/dataset")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public R<List<LabelledEvalSample>> dataset(@RequestParam(required = false) LocalDateTime from,
                                               @RequestParam(required = false) LocalDateTime to,
                                               @RequestParam(defaultValue = "50") int limit) {
        // tenantId 形参传 null：dead param，仅占位对齐签名，真实值由 Service 以 TenantContext 解析
        return R.ok(answerEvaluationService.dataset(null, from, to, limit));
    }

    /**
     * 回归重跑并落快照（spec §3.3）。走 TenantContext（不读 X-Tenant-Id 头），limit 透传给 dataset。
     * 0 样本 → HTTP 200 + data=null + msg="无匹配样本，未落快照"（前端以 data==null && msg 判定未落快照）；
     * 锁仅在 Service 内（tryLock 失败抛 409 BizException，本层只转发）。
     */
    @PostMapping("/regression")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public R<EvalRegressionReportEntity> regression(@RequestParam(required = false) LocalDateTime from,
                                                    @RequestParam(required = false) LocalDateTime to,
                                                    @RequestParam(defaultValue = "50") int limit) {
        EvalRegressionReportEntity report = answerEvaluationService.regression(null, from, to, limit);
        if (report == null) {
            // code=200 是「空结果/未落快照」的显式约定：客户端据 data==null 区分有数据与未落快照
            return R.fail(200, "无匹配样本，未落快照");
        }
        return R.ok(report);
    }

    /**
     * 回归报告历史分页（spec §3.4）。走 TenantContext（不读 X-Tenant-Id 头）。
     * page<=0 回落 1；pageSize<=0 回落 50、超 historyPageMax 由 Service 收敛；本层只透传原始值。
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public R<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int pageSize) {
        return R.ok(answerEvaluationService.history(null, page, pageSize));
    }
}
