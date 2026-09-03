package com.company.rag.common.security;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 用户上下文工具类
 * 用于从 SecurityContext 中获取当前登录用户信息
 */
public class UserContext {

    /**
     * 获取当前登录用户 ID
     * 
     * @return 当前用户 ID，如果未认证则返回 null
     */
    public static Long getCurrentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUser) {
            return ((SecurityUser) principal).getUserId();
        }
        return null;
    }

    /**
     * 获取当前租户 ID
     * 
     * @return 当前租户 ID，如果未认证则返回 null
     */
    public static Long getCurrentTenantId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof SecurityUser) {
            return ((SecurityUser) principal).getTenantId();
        }
        return null;
    }

    /**
     * 获取当前用户名
     * 
     * @return 当前用户名，如果未认证则返回 null
     */
    public static String getCurrentUsername() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof SecurityUser) {
            return ((SecurityUser) principal).getUsername();
        }
        return null;
    }

    /**
     * 获取当前用户角色
     * 
     * @return 当前用户角色，如果未认证则返回 null
     */
    public static String getCurrentUserRole() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUser) {
            return ((SecurityUser) principal).getRole();
        }
        return null;
    }
}
