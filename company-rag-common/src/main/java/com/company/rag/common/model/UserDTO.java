package com.company.rag.common.model;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 用户管理 DTO
 */
@Data
public class UserDTO {
    
    /**
     * 创建用户请求
     */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度必须在 3-50 个字符之间")
        private String username;
        
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 100, message = "密码长度必须在 6-100 个字符之间")
        private String password;
        
        @NotBlank(message = "显示名不能为空")
        @Size(max = 100, message = "显示名长度不能超过 100 个字符")
        private String displayName;
        
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
        private String email;
        
        @NotBlank(message = "角色不能为空")
        private String role;  // admin, user, viewer
        
        @NotEmpty(message = "至少选择一个租户")
        private List<Long> tenantIds;
    }
    
    /**
     * 更新用户请求
     */
    @Data
    public static class UpdateRequest {
        @Size(max = 50, message = "用户名长度不能超过 50 个字符")
        private String username;
        
        @Size(max = 100, message = "密码长度不能超过 100 个字符")
        private String password;  // 留空表示不修改
        
        @Size(max = 100, message = "显示名长度不能超过 100 个字符")
        private String displayName;
        
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
        private String email;
        
        @NotBlank(message = "角色不能为空")
        private String role;  // admin, user, viewer
        
        @NotEmpty(message = "至少选择一个租户")
        private List<Long> tenantIds;
    }
    
    /**
     * 用户响应
     */
    @Data
    public static class UserResponse {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private String role;
        private Integer status;
        private List<Long> tenantIds;
        private List<String> tenantNames;
        private String createTime;
    }
    
    /**
     * 用户详情响应
     */
    @Data
    public static class UserDetailResponse {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private String role;
        private Integer status;
        private List<Long> tenantIds;
        private String createTime;
        private String updateTime;
    }
}
