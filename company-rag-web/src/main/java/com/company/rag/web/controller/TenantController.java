package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.dto.TenantDTO;
import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户管理接口
 */
@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    /**
     * 创建租户（自动初始化 Schema 和默认管理员用户）
     */
    @PostMapping
    public R<TenantDTO.TenantResponse> create(@RequestBody @Validated TenantDTO.CreateRequest request) {
        try {
            Tenant tenant = new Tenant();
            tenant.setTenantCode(request.getTenantCode());
            tenant.setTenantName(request.getTenantName());
            tenant.setContactName(request.getContactName());
            tenant.setContactPhone(request.getContactPhone());
            
            Tenant createdTenant = tenantService.createTenantWithSchema(tenant);
            
            TenantDTO.TenantResponse response = new TenantDTO.TenantResponse();
            response.setId(createdTenant.getId());
            response.setTenantCode(createdTenant.getTenantCode());
            response.setTenantName(createdTenant.getTenantName());
            response.setSchemaName(createdTenant.getSchemaName());
            response.setStatus(createdTenant.getStatus());
            response.setContactName(createdTenant.getContactName());
            response.setContactPhone(createdTenant.getContactPhone());
            response.setCreateTime(createdTenant.getCreateTime() != null ? 
                createdTenant.getCreateTime().toString() : null);
            
            return R.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return R.fail(500, "创建租户失败：" + e.getMessage());
        }
    }

    /**
     * 查询租户列表
     */
    @GetMapping("/list")
    public R<List<TenantDTO.TenantResponse>> list() {
        List<Tenant> tenants = tenantService.getAllTenants();
        List<TenantDTO.TenantResponse> responses = tenants.stream().map(t -> {
            TenantDTO.TenantResponse response = new TenantDTO.TenantResponse();
            response.setId(t.getId());
            response.setTenantCode(t.getTenantCode());
            response.setTenantName(t.getTenantName());
            response.setSchemaName(t.getSchemaName());
            response.setStatus(t.getStatus());
            response.setContactName(t.getContactName());
            response.setContactPhone(t.getContactPhone());
            response.setCreateTime(t.getCreateTime() != null ? 
                t.getCreateTime().toString() : null);
            return response;
        }).toList();
        
        return R.ok(responses);
    }

    /**
     * 查询租户详情
     */
    @GetMapping("/{id}")
    public R<TenantDTO.TenantResponse> getById(@PathVariable Long id) {
        Tenant tenant = tenantService.getById(id);
        if (tenant == null) {
            return R.fail(404, "租户不存在");
        }
        
        TenantDTO.TenantResponse response = new TenantDTO.TenantResponse();
        response.setId(tenant.getId());
        response.setTenantCode(tenant.getTenantCode());
        response.setTenantName(tenant.getTenantName());
        response.setSchemaName(tenant.getSchemaName());
        response.setStatus(tenant.getStatus());
        response.setContactName(tenant.getContactName());
        response.setContactPhone(tenant.getContactPhone());
        response.setCreateTime(tenant.getCreateTime() != null ? 
            tenant.getCreateTime().toString() : null);
        
        return R.ok(response);
    }
}
