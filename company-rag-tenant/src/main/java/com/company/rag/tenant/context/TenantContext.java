package com.company.rag.tenant.context;

/**
 * 租户上下文 - 使用 ThreadLocal 存储当前请求的租户信息和会话信息
 */
public class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT_CODE = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SCHEMA = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) { CURRENT_TENANT_ID.set(tenantId); }
    public static Long getTenantId() { return CURRENT_TENANT_ID.get(); }
    public static void setTenantCode(String code) { CURRENT_TENANT_CODE.set(code); }
    public static String getTenantCode() { return CURRENT_TENANT_CODE.get(); }
    public static void setUserId(Long userId) { CURRENT_USER_ID.set(userId); }
    public static Long getUserId() { return CURRENT_USER_ID.get(); }
    public static void setSchema(String schema) { CURRENT_SCHEMA.set(schema); }
    public static String getSchema() { return CURRENT_SCHEMA.get(); }
    
    /**
     * 设置当前会话 ID
     * @param sessionId 会话 ID
     */
    public static void setSessionId(String sessionId) { CURRENT_SESSION_ID.set(sessionId); }
    
    /**
     * 获取当前会话 ID
     * @return 会话 ID，如果未设置返回 null
     */
    public static String getSessionId() { return CURRENT_SESSION_ID.get(); }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
        CURRENT_TENANT_CODE.remove();
        CURRENT_USER_ID.remove();
        CURRENT_SCHEMA.remove();
        CURRENT_SESSION_ID.remove();
    }
}
