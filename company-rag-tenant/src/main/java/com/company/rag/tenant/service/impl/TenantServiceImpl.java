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
        // 创建独立Schema
        String schemaName = "tenant_" + tenant.getTenantCode();
        // 校验schema名称合法性，防止SQL注入
        if (!schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BizException("非法Schema名称: " + schemaName);
        }
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        tenant.setSchemaName(schemaName);
        tenantMapper.updateById(tenant);
        log.info("为租户[{}]创建独立Schema: {}", tenant.getTenantCode(), schemaName);
    }

    @Override
    public List<User> getUsersByTenant(Long tenantId) {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>().eq(User::getTenantId, tenantId));
    }
}
