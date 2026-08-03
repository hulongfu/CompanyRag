package com.company.rag.web.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String refreshToken;
    private long expireIn;
    private Long userId;
    private List<Long> tenantIds;     // 可访问的租户列表
    private Long currentTenantId;     // 当前租户 ID（默认选第一个）
    private String role;
    private String displayName;
}
