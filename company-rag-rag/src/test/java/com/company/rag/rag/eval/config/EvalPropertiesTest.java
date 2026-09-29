package com.company.rag.rag.eval.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link EvalProperties} 单元测试（spec §5 配置绑定 + 十二轮 enabled 门控断言）。
 *
 * <ul>
 *   <li>字段初始值缺省兜底（禁用 @DefaultValue，八轮）；</li>
 *   <li>relaxed binding 断言：regressionLockTimeoutMs / historyPageMax 与键对齐（九/十二轮）；</li>
 *   <li>@PostConstruct：enabled=true 缺 rule-version 抛；enabled=false 缺 rule-version 不抛（十二轮）。</li>
 * </ul>
 */
class EvalPropertiesTest {

    @Test
    void fieldInitializersProvideDefaults() {
        EvalProperties p = new EvalProperties();
        assertFalse(p.isEnabled());
        assertFalse(p.isOnlineEnabled());
        assertTrue(p.isAsyncEnabled());
        // 关键缺省：回归并发锁等待、数据集上限、历史每页上限（须与键 relaxed 对齐）
        assertEquals(30000L, p.getRegressionLockTimeoutMs());
        assertEquals(50, p.getDatasetLimitDefault());
        assertEquals(200, p.getDatasetLimitMax());
        assertEquals(200, p.getHistoryPageMax());
        assertFalse(p.isRegressionGateEnabled());
        // ruleVersion 保持 null，由 @PostConstruct 在 enabled=true 时强校验
        assertNull(p.getRuleVersion());
    }

    @Test
    void settersBindExplicitConfigValues() {
        EvalProperties p = new EvalProperties();
        p.setEnabled(true);
        p.setRuleVersion("v1.0");
        p.setRegressionLockTimeoutMs(5000);
        p.setHistoryPageMax(100);
        p.setDatasetLimitDefault(10);
        p.setDatasetLimitMax(150);
        assertEquals("v1.0", p.getRuleVersion());
        assertEquals(5000, p.getRegressionLockTimeoutMs());
        assertEquals(100, p.getHistoryPageMax());
        assertEquals(10, p.getDatasetLimitDefault());
        assertEquals(150, p.getDatasetLimitMax());
    }

    @Test
    void postConstruct_throws_whenEnabledAndRuleVersionBlank() {
        EvalProperties p = new EvalProperties();
        p.setEnabled(true);
        p.setRuleVersion(null);
        assertThrows(IllegalStateException.class, p::validate, "enabled=true 缺 rule-version 必须装配期抛错");
    }

    @Test
    void postConstruct_noThrow_whenDisabledAndRuleVersionMissing() {
        // enabled=false 停用评估：即使缺 rule-version 也不抛（防停用评估时启动失败，十二轮）
        EvalProperties p = new EvalProperties();
        p.setEnabled(false);
        p.setRuleVersion(null);
        p.validate();
    }

    @Test
    void postConstruct_noThrow_whenEnabledAndRuleVersionPresent() {
        EvalProperties p = new EvalProperties();
        p.setEnabled(true);
        p.setRuleVersion("v1.0");
        p.validate();
    }
}
