package com.company.rag.tenant.context;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.PrintWriter;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantAwareJdbcTemplate 的 resolveSchema 逻辑单元测试
 */
class TenantAwareJdbcTemplateTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testResolveSchema_ReplacesPublicSchemaWhenTenantSet() {
        TenantContext.setSchema("tenant_001");
        TenantAwareJdbcTemplate template = createTemplate();
        String sql = "SELECT * FROM public.vector_store";
        String resolved = invokeResolveSchema(template, sql);
        assertEquals("SELECT * FROM tenant_001.vector_store", resolved);
    }

    @Test
    void testResolveSchema_NoReplacementWhenNoTenant() {
        TenantContext.clear();
        TenantAwareJdbcTemplate template = createTemplate();
        String sql = "SELECT * FROM public.vector_store";
        String resolved = invokeResolveSchema(template, sql);
        assertEquals("SELECT * FROM public.vector_store", resolved);
    }

    @Test
    void testResolveSchema_EmptySchemaNoReplacement() {
        TenantContext.setSchema("");
        TenantAwareJdbcTemplate template = createTemplate();
        String sql = "SELECT * FROM public.vector_store";
        String resolved = invokeResolveSchema(template, sql);
        assertEquals("SELECT * FROM public.vector_store", resolved);
    }

    @Test
    void testResolveSchema_MultiplePublicReplacements() {
        TenantContext.setSchema("tenant_default");
        TenantAwareJdbcTemplate template = createTemplate();
        String sql = "SELECT * FROM public.a JOIN public.b ON public.a.id = public.b.id";
        String resolved = invokeResolveSchema(template, sql);
        assertEquals("SELECT * FROM tenant_default.a JOIN tenant_default.b ON tenant_default.a.id = tenant_default.b.id", resolved);
    }

    @Test
    void testResolveSchema_PgVectorSimilaritySearchSql() {
        TenantContext.setSchema("tenant_default");
        TenantAwareJdbcTemplate template = createTemplate();
        String sql = "SELECT *, embedding <=> ? AS distance FROM public.vector_store WHERE embedding <=> ? < ?  ORDER BY distance LIMIT ? ";
        String resolved = invokeResolveSchema(template, sql);
        assertEquals("SELECT *, embedding <=> ? AS distance FROM tenant_default.vector_store WHERE embedding <=> ? < ?  ORDER BY distance LIMIT ? ", resolved);
    }

    private TenantAwareJdbcTemplate createTemplate() {
        DataSource dataSource = new DataSource() {
            @Override public Connection getConnection() { return null; }
            @Override public Connection getConnection(String u, String p) { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public void setLoginTimeout(int s) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public void setLogWriter(PrintWriter pw) {}
            @Override public PrintWriter getLogWriter() { return null; }
        };
        JdbcTemplate delegate = new JdbcTemplate(dataSource);
        return new TenantAwareJdbcTemplate(delegate);
    }

    private String invokeResolveSchema(TenantAwareJdbcTemplate template, String sql) {
        try {
            java.lang.reflect.Method method = TenantAwareJdbcTemplate.class.getDeclaredMethod("resolveSchema", String.class);
            method.setAccessible(true);
            return (String) method.invoke(template, sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}