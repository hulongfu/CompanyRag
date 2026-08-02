package com.company.rag.tenant.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.interceptor.TenantSchemaInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 多租户插件配置
 * 自动为所有查询追加 tenant_id = ? 条件，并在执行前设置正确的 search_path
 */
@Configuration
public class TenantMyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 注册 TenantSchemaInterceptor：在每次 MyBatis 查询/更新前，在 MyBatis 当前连接上设置 search_path
        //    解决时序竞争问题：拦截器通过 JdbcTemplate 设置 search_path 后，MyBatis 可能拿到另一个连接
        interceptor.addInnerInterceptor(new TenantSchemaInterceptor());

        // 2. 注册 TenantLineInnerInterceptor：自动为所有查询追加 tenant_id = ? 条件
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.getTenantId();
                return tenantId != null ? new LongValue(tenantId) : null;
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // sys_tenant 表忽略租户隔离
                // sys_user 表忽略租户隔离（用户登录时需要跨租户查询）
                return "sys_tenant".equalsIgnoreCase(tableName) 
                    || "sys_user".equalsIgnoreCase(tableName);
            }
        }));

        return interceptor;
    }
}