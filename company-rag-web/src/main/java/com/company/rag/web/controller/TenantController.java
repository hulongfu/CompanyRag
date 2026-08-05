package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.R;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.dto.TenantDTO;
import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(actionType = "CREATE_TENANT", targetType = "tenant", detail = "'创建租户：' + #request.tenantCode")
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
     * - 普通用户只能看到其关联的租户
     * - admin 用户可以看到所有租户
     */
    @GetMapping("/list")
    public R<List<TenantDTO.TenantResponse>> list() {
        // 根据当前登录用户获取租户列表（自动区分 admin 和普通用户）
        List<Tenant> tenants = tenantService.getTenantsByCurrentUser();
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

    /**
     * 删除租户（级联删除 Schema 和所有数据）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(actionType = "DELETE_TENANT", targetType = "tenant", targetId = "#id", detail = "'删除租户：ID=' + #id")
    public R<Boolean> delete(@PathVariable Long id) {
        try {
            boolean success = tenantService.deleteTenantWithSchema(id);
            if (!success) {
                return R.fail(404, "租户不存在");
            }
            return R.ok(true);
        } catch (Exception e) {
            return R.fail(500, "删除租户失败：" + e.getMessage());
        }
    }
}
