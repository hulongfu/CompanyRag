package com.company.rag.tenant.model.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 租户数据传输对象
 */
public class TenantDTO {

    /**
     * 创建租户请求
     */
    @Data
    public static class CreateRequest {
        
        @NotBlank(message = "租户编码不能为空")
        @Size(min = 2, max = 64, message = "租户编码长度必须在 2-64 之间")
        @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]*$", message = "租户编码只能包含字母、数字和下划线，且不能以数字开头")
        private String tenantCode;
        
        @NotBlank(message = "租户名称不能为空")
        @Size(min = 2, max = 128, message = "租户名称长度必须在 2-128 之间")
        private String tenantName;
        
        private String contactName;
        
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String contactPhone;
    }
    
    /**
     * 租户响应
     */
    @Data
    public static class TenantResponse {
        private Long id;
        private String tenantCode;
        private String tenantName;
        private String schemaName;
        private Integer status;
        private String contactName;
        private String contactPhone;
        private String createTime;
    }
}
