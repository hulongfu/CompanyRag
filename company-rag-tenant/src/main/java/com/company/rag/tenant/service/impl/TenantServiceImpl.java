package com.company.rag.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.common.exception.BizException;
import com.company.rag.tenant.mapper.TenantMapper;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;
import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Tenant getByCode(String tenantCode) {
        return tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenantCode));
    }

    @Override
    public Tenant getById(Long id) {
        return tenantMapper.selectById(id);
    }

    @Override
    @Transactional
    public void createTenantSchema(Tenant tenant) {
        String schemaName = "tenant_" + tenant.getTenantCode();
        // 校验schema名称合法性，防止SQL注入
        if (!schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BizException("非法Schema名称: " + schemaName);
        }

        // 1. 创建独立Schema
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);

        // 2. 在Schema中创建业务表
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS %s.rag_document (
                id BIGSERIAL PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                file_name VARCHAR(256) NOT NULL,
                file_type VARCHAR(32),
                file_size BIGINT,
                file_path VARCHAR(512),
                title VARCHAR(256),
                chunk_count INTEGER DEFAULT 0,
                status INTEGER DEFAULT 0,
                error_msg TEXT,
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS %s.doc_chunk (
                id BIGSERIAL PRIMARY KEY,
                document_id BIGINT NOT NULL REFERENCES %s.rag_document(id) ON DELETE CASCADE,
                tenant_id BIGINT NOT NULL,
                chunk_index INTEGER NOT NULL,
                content TEXT NOT NULL,
                token_count INTEGER DEFAULT 0,
                split_strategy VARCHAR(32),
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS %s.vector_store (
                id UUID PRIMARY KEY,
                content TEXT,
                metadata JSONB,
                embedding vector(1024)
            );
            CREATE TABLE IF NOT EXISTS %s.rag_session (
                id BIGSERIAL PRIMARY KEY,
                session_id VARCHAR(128) NOT NULL,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                query TEXT NOT NULL,
                answer TEXT,
                context TEXT,
                tokens_input INTEGER DEFAULT 0,
                tokens_output INTEGER DEFAULT 0,
                latency_ms INTEGER DEFAULT 0,
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """.formatted(schemaName, schemaName, schemaName, schemaName, schemaName);
        jdbcTemplate.execute(createTableSql);

        // 3. 创建索引
        String createIndexSql = """
            CREATE INDEX IF NOT EXISTS idx_%s_doc_tenant ON %s.rag_document(tenant_id);
            CREATE INDEX IF NOT EXISTS idx_%s_chunk_document ON %s.doc_chunk(document_id);
            CREATE INDEX IF NOT EXISTS idx_%s_chunk_content_trgm ON %s.doc_chunk USING gin (content gin_trgm_ops);
            CREATE INDEX IF NOT EXISTS idx_%s_document_title_trgm ON %s.rag_document USING gin (title gin_trgm_ops);
            CREATE INDEX IF NOT EXISTS idx_%s_session_tenant ON %s.rag_session(tenant_id, session_id);
            CREATE INDEX IF NOT EXISTS idx_%s_vector_store_embedding ON %s.vector_store
                USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
            """.formatted(
                schemaName, schemaName, schemaName, schemaName,
                schemaName, schemaName, schemaName, schemaName,
                schemaName, schemaName, schemaName, schemaName
            );
        jdbcTemplate.execute(createIndexSql);

        // 4. 启用RLS并创建策略
        String rlsSql = """
            ALTER TABLE %s.rag_document ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %s.doc_chunk ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %s.rag_session ENABLE ROW LEVEL SECURITY;
            DROP POLICY IF EXISTS tenant_isolation_document ON %s.rag_document;
            CREATE POLICY tenant_isolation_document ON %s.rag_document
                USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
            DROP POLICY IF EXISTS tenant_isolation_chunk ON %s.doc_chunk;
            CREATE POLICY tenant_isolation_chunk ON %s.doc_chunk
                USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
            DROP POLICY IF EXISTS tenant_isolation_session ON %s.rag_session;
            CREATE POLICY tenant_isolation_session ON %s.rag_session
                USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
            """.formatted(schemaName, schemaName, schemaName,
                schemaName, schemaName, schemaName, schemaName, schemaName, schemaName);
        jdbcTemplate.execute(rlsSql);

        // 5. 更新租户记录
        tenant.setSchemaName(schemaName);
        tenantMapper.updateById(tenant);

        log.info("为租户[{}]创建独立Schema完成: {} | 已创建业务表和RLS策略", tenant.getTenantCode(), schemaName);
    }

    @Override
    public List<User> getUsersByTenant(Long tenantId) {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>().eq(User::getTenantId, tenantId));
    }

    @Override
    @Transactional
    public Tenant createTenantWithSchema(Tenant tenant) {
        // 1. 检查 tenantCode 是否已存在
        Tenant existingTenant = tenantMapper.selectOne(
            new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenant.getTenantCode()));
        if (existingTenant != null) {
            throw new BizException("租户编码已存在：" + tenant.getTenantCode());
        }

        // 2. 设置默认值
        if (tenant.getStatus() == null) {
            tenant.setStatus(1);
        }

        // 3. 保存租户记录
        tenantMapper.insert(tenant);

        try {
            // 4. 创建 Schema 和业务表
            createTenantSchema(tenant);

            // 5. 创建默认管理员用户
            createDefaultAdminUser(tenant);

            log.info("租户 [{}] 创建成功，Schema: {}, 默认管理员用户已创建", 
                tenant.getTenantCode(), tenant.getSchemaName());
        } catch (Exception e) {
            log.error("租户 [{}] 创建失败，将回滚事务", tenant.getTenantCode(), e);
            throw new BizException("创建租户失败：" + e.getMessage());
        }

        return tenant;
    }

    @Override
    public List<Tenant> getAllTenants() {
        return tenantMapper.selectList(new LambdaQueryWrapper<Tenant>().orderByDesc(Tenant::getCreateTime));
    }

    /**
     * 创建默认管理员用户
     * 默认密码：admin123
     */
    private void createDefaultAdminUser(Tenant tenant) {
        User adminUser = new User();
        adminUser.setTenantId(tenant.getId());
        adminUser.setUsername("admin");
        // BCrypt 加密默认密码 admin123
        // 使用静态 BCrypt 密码：$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6
        adminUser.setPassword("$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6");
        adminUser.setDisplayName("管理员");
        adminUser.setRole("admin");
        adminUser.setStatus(1);

        userMapper.insert(adminUser);
        log.info("为租户 [{}] 创建默认管理员用户：admin", tenant.getTenantCode());
    }
}
