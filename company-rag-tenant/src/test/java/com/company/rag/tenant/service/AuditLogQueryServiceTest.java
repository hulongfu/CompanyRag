package com.company.rag.tenant.service;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogQueryService 单测：分页过滤条件拼装、null 忽略、@TableName("public.audit_log") 生效
 *
 * 经 mock 的 selectPage 捕获 wrapper，用 getSqlSegment()（列条件，值以 ? 占位）
 * 与 getParamNameValuePairs()（绑定的具体值）双维度断言。
 */
class AuditLogQueryServiceTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final AuditLogQueryService auditLogQueryService = new AuditLogQueryService(auditLogMapper);

    /**
     * 纯单测无 Spring 上下文，需手动初始化实体 TableInfo，
     * 否则 LambdaQueryWrapper 按字段解析列名时会报 "can not find lambda cache"。
     */
    @BeforeEach
    void initEntityTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLog.class);
    }

    /** 捕获传给 selectPage 的 wrapper（mock 保留传入对象引用） */
    private LambdaQueryWrapper<AuditLog> captureWrapper() {
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>(1, 20));
        ArgumentCaptor<Wrapper<AuditLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(auditLogMapper).selectPage(any(Page.class), captor.capture());
        return (LambdaQueryWrapper<AuditLog>) captor.getValue();
    }

    @Test
    void queryWithTenantIdFilter() {
        auditLogQueryService.query("42", null, null, null, null, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        assertThat(wrapper.getSqlSegment()).contains("tenant_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("42");
    }

    @Test
    void queryWithUserIdFilter() {
        auditLogQueryService.query(null, 9L, null, null, null, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        assertThat(wrapper.getSqlSegment()).contains("user_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(9L);
    }

    @Test
    void queryWithActionTypeFilter() {
        auditLogQueryService.query(null, null, "LOGIN", null, null, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        assertThat(wrapper.getSqlSegment()).contains("action_type");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("LOGIN");
    }

    @Test
    void queryWithTimeRange() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 12, 31, 23, 59);
        auditLogQueryService.query(null, null, null, start, end, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        assertThat(wrapper.getSqlSegment()).contains("created_at");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(start, end);
    }

    @Test
    void queryWithCombinedFilters() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        auditLogQueryService.query("42", 9L, "LOGIN", start, null, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("tenant_id").contains("user_id").contains("action_type").contains("created_at");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("42", 9L, "LOGIN", start);
    }

    @Test
    void queryNullFilterIgnored() {
        auditLogQueryService.query(null, null, "", null, null, 1, 20);

        LambdaQueryWrapper<AuditLog> wrapper = captureWrapper();
        // 空 actionType / null 条件都不应进入 SQL where 段
        String sql = wrapper.getSqlSegment();
        assertThat(sql)
                .doesNotContain("action_type")
                .doesNotContain("tenant_id")
                .doesNotContain("user_id");
    }

    @Test
    void queryReturnsPageInstance() {
        when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<>(1, 20));
        Page<AuditLog> result = auditLogQueryService.query(null, null, null, null, null, 2, 50);
        assertThat(result).isNotNull();
        assertThat(result.getCurrent()).isEqualTo(1);
    }

    @Test
    void tableNameIsPublicAuditLog() {
        // 标准写法：value=audit_log + schema=public，生成的 SQL 仍以 public.audit_log 落库，
        // 防止回退裸表名被租户行级插件污染
        TableName tableName = AuditLog.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("audit_log");
        assertThat(tableName.schema()).isEqualTo("public");
    }
}