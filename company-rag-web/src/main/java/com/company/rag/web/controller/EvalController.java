package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvalResultEntity;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
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
}
