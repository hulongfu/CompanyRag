package com.company.rag.rag.eval.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 回答评估配置（spec §3.2.2、§3.4）。
 *
 * <p>与 {@code ApprovalProperties} 同范式：@Data + @Component + @ConfigurationProperties，
 * 字段名与 application.yml 的 {@code rag.eval.*} 中划线键 relaxed 对齐。
 * 数值/布尔用【字段初始值】兜底缺省（禁用 @DefaultValue，其 @Target 不含 FIELD）。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "rag.eval")
public class EvalProperties {

    /** 评估关键能力总开关（装配门控仍在 Controller/Service 的 @ConditionalOnProperty；本字段仅供 @PostConstruct 决定是否校验规则版本）。 */
    private boolean enabled = false;

    /** 是否接入 chat 在线自动评估（company-rag-web ChatController，只管在线抽样，与端点装配无关）。 */
    private boolean onlineEnabled = false;

    /** 在线评估是否异步（true=有界线程池；false=当前线程同步，仅调试用）。 */
    private boolean asyncEnabled = true;

    /** 规则版本号（快照 rule_version 落库列），保持 null，由 @PostConstruct 在 enabled=true 时强制断言非空。 */
    private String ruleVersion;

    /** 回归评估并发锁单租户最长等待（毫秒），超时返回 409。 */
    private long regressionLockTimeoutMs = 30000;

    /** 回归样本集默认条数（不传 limit 时的回落值）。 */
    private int datasetLimitDefault = 50;

    /** 回归样本集单次最大条数上限（防冻结/防越权放大）。 */
    private int datasetLimitMax = 200;

    /** 回归门控总开关（占位，默认关，先手动验证报表正确性）。 */
    private boolean regressionGateEnabled = false;

    /** 回归历史报表分页单页最大条数（防冻结；独立于 datasetLimitMax）。 */
    private int historyPageMax = 200;

    @PostConstruct
    public void validate() {
        // 规则版本是快照必需列：enabled=true 时缺席会在落库时空指针 → 装配期即显式暴露
        // （enabled=false 停用评估放行，不因缺 rule-version 导致应用启动失败）
        if (enabled && !StringUtils.hasText(ruleVersion)) {
            log.error("[EVAL] rag.eval.rule-version 缺失且 rag.eval.enabled=true：规则版本为快照必需列，请显式配置（如 rule-version: v1.0）");
            throw new IllegalStateException("[EVAL] rag.eval.rule-version 缺失：enabled=true 时 rule_version 为 NOT NULL 列，必须显式配置规则版本号");
        }
    }
}