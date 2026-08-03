package com.company.rag.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 安全用户实体，实现 UserDetails
 * 携带 userId、tenantId（当前租户）、tenantIds（可访问的租户列表）、role 供 JWT 和权限校验使用
 */
@Getter
public class SecurityUser implements UserDetails {

    private final Long userId;
    private final Long tenantId;      // 当前租户 ID
    private final List<Long> tenantIds; // 可访问的租户列表
    private final String username;
    private final String password;
    private final String role;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;

    public SecurityUser(Long userId, Long tenantId, List<Long> tenantIds,
                        String username, String password,
                        String role, boolean enabled) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.tenantIds = tenantIds != null ? tenantIds : Collections.emptyList();
        this.username = username;
        this.password = password;
        this.role = role;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        this.enabled = enabled;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
