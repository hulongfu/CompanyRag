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
                // sys_tenant / sys_user / sys_user_tenant_rel 表忽略租户隔离（全局/登录需跨租户查询）
                // audit_log 平台级审计表忽略租户隔离：存放所有租户记录，admin 需跨租户可见
                // 匹配点：MP 的 ignoreTable 收到的是 jsqlparser Table.getName()（纯表名，不含 schema）。
                // 仍追加 endsWith(".audit_log") 以防御未来传入带 schema 前缀的表名。
                return "sys_tenant".equalsIgnoreCase(tableName)
                    || "sys_user".equalsIgnoreCase(tableName)
                    || "sys_user_tenant_rel".equalsIgnoreCase(tableName)
                    || "audit_log".equalsIgnoreCase(tableName)
                    || (tableName != null && tableName.toLowerCase().endsWith(".audit_log"));
            }
        }));

        return interceptor;
    }
}