package com.company.rag.tenant.service;

import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import com.company.rag.tenant.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户详情服务实现
 * 
 * 用于 Spring Security 认证时加载用户信息
 * 登录后查询用户关联的租户列表，支持多租户切换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final UserTenantRelMapper userTenantRelMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("加载用户：{}", username);
        
        User user = userMapper.findByUsername(username);
        if (user == null) {
            log.warn("用户不存在：{}", username);
            throw new UsernameNotFoundException("用户不存在：" + username);
        }
        
        // 检查用户状态（1=启用，0=禁用）
        if (user.getStatus() != null && user.getStatus() != 1) {
            log.warn("用户已禁用：{}", username);
            throw new UsernameNotFoundException("用户已禁用：" + username);
        }
        
        // 查询用户关联的租户列表
        List<Long> tenantIds = userTenantRelMapper.findTenantIdsByUserId(user.getId());
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.warn("用户没有关联任何租户：{}", username);
            throw new UsernameNotFoundException("用户没有关联任何租户：" + username);
        }
        
        // 默认租户取第一个
        Long defaultTenantId = tenantIds.get(0);
        
        log.info("用户加载成功：{}, tenantIds={}, defaultTenantId={}, role={}", 
                username, tenantIds, defaultTenantId, user.getRole());
        
        return new SecurityUser(
                user.getId(),
                defaultTenantId,
                tenantIds,
                user.getUsername(),
                user.getPassword(),
                user.getRole() != null ? user.getRole() : "user",
                user.getStatus() == null || user.getStatus() == 1
        );
    }
}
