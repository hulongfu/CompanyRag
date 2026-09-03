package com.company.rag.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.common.model.UserDTO;
import com.company.rag.common.security.PasswordGenerator;
import com.company.rag.common.security.UserContext;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;
import com.company.rag.tenant.service.TenantService;
import com.company.rag.tenant.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    
    private final UserMapper userMapper;
    private final UserTenantRelMapper userTenantRelMapper;
    private final TenantService tenantService;
    
    private static final DateTimeFormatter DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserDTO.UserResponse createUser(UserDTO.CreateRequest request) {
        log.info("创建用户：username={}, role={}, tenantIds={}", 
                request.getUsername(), request.getRole(), request.getTenantIds());
        
        // 1. 验证用户名唯一性
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            throw new IllegalArgumentException("用户名已存在：" + request.getUsername());
        }
        
        // 1.1 平台管理员唯一性校验：不允许存在多个 admin 账号
        if ("admin".equals(request.getRole()) && userMapper.countByRole("admin") > 0) {
            throw new IllegalArgumentException("系统已存在管理员账号，不允许创建多个管理员");
        }
        
        // 2. 验证角色合法性
        if (!isValidRole(request.getRole())) {
            throw new IllegalArgumentException("无效的角色：" + request.getRole());
        }
        
        // 3. 验证租户存在性
        for (Long tenantId : request.getTenantIds()) {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant == null) {
                throw new IllegalArgumentException("租户不存在：" + tenantId);
            }
        }
        
        // 4. 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(PasswordGenerator.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setStatus(1); // 默认启用
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        
        userMapper.insert(user);
        log.info("用户创建成功：id={}", user.getId());
        
        // 5. 创建用户 - 租户关联
        userTenantRelMapper.batchInsert(user.getId(), request.getTenantIds());
        log.info("用户 - 租户关联创建成功：userId={}, tenantIds={}", 
                user.getId(), request.getTenantIds());
        
        // 6. 构建响应
        return buildUserResponse(user);
    }
    
    @Override
    public List<UserDTO.UserResponse> queryUserList(String role, Long tenantId, 
                                                     Integer status, String username) {
        log.info("查询用户列表：role={}, tenantId={}, status={}, username={}", 
                role, tenantId, status, username);
        
        // 获取当前登录用户信息
        Long currentUserId = TenantContext.getUserId();
        Long currentTenantId = TenantContext.getTenantId();
        // 判断当前用户是否为平台管理员（admin 可查看/管理所有用户）
        boolean isAdmin = isCurrentUserAdmin();
        
        // 租户隔离校验：确保非管理员用户只能查看自己所在租户的用户
        // 平台管理员（admin）不受租户限制
        if (!isAdmin && tenantId != null && currentTenantId != null) {
            // 验证传入的 tenantId 是否与当前用户的租户一致
            if (!tenantId.equals(currentTenantId)) {
                // 检查当前用户是否属于指定租户（通过用户 - 租户关联表）
                List<Long> userTenantIds = userTenantRelMapper.findTenantIdsByUserId(currentUserId);
                if (userTenantIds == null || !userTenantIds.contains(tenantId)) {
                    log.warn("越权访问尝试：userId={}, currentTenantId={}, requestedTenantId={}", 
                            currentUserId, currentTenantId, tenantId);
                    throw new SecurityException("无权查看该租户的用户信息");
                }
            }
        }
        
        List<User> users;
        
        // 平台管理员查看全部用户（不受租户限制，保留角色/状态/用户名筛选）
        if (isAdmin) {
            LambdaQueryWrapper<User> adminWrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(role)) {
                adminWrapper.eq(User::getRole, role);
            }
            if (status != null) {
                adminWrapper.eq(User::getStatus, status);
            }
            if (StringUtils.hasText(username)) {
                adminWrapper.like(User::getUsername, username);
            }
            users = userMapper.selectList(adminWrapper);
        } else if (tenantId != null) {
            // 如果指定了租户 ID，按租户查询
            users = userMapper.findByTenantId(tenantId, status);
        } else {
            // 否则查询所有用户（但受租户上下文限制）
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(role)) {
                wrapper.eq(User::getRole, role);
            }
            if (status != null) {
                wrapper.eq(User::getStatus, status);
            }
            if (StringUtils.hasText(username)) {
                wrapper.like(User::getUsername, username);
            }
            // 如果没有指定租户 ID，使用当前用户的租户 ID 作为过滤条件
            if (currentTenantId != null) {
                users = userMapper.findByTenantId(currentTenantId, status);
            } else {
                users = userMapper.selectList(wrapper);
            }
        }
        
        // 构建响应
        return users.stream()
                .map(this::buildUserResponse)
                .collect(Collectors.toList());
    }
    
    @Override
    public UserDTO.UserDetailResponse getUserById(Long id) {
        log.info("查询用户详情：id={}", id);
        
        User user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        
        List<Long> tenantIds = userTenantRelMapper.findTenantIdsByUserId(id);
        
        UserDTO.UserDetailResponse response = new UserDTO.UserDetailResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setTenantIds(tenantIds);
        response.setCreateTime(user.getCreateTime() != null ? 
                user.getCreateTime().format(DATE_FORMATTER) : null);
        response.setUpdateTime(user.getUpdateTime() != null ? 
                user.getUpdateTime().format(DATE_FORMATTER) : null);
        
        return response;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserDTO.UserResponse updateUser(Long id, UserDTO.UpdateRequest request) {
        log.info("更新用户：id={}, username={}, role={}", id, request.getUsername(), request.getRole());
        
        // 1. 查询用户
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在：" + id);
        }
        
        // 2. 验证角色合法性
        if (!isValidRole(request.getRole())) {
            throw new IllegalArgumentException("无效的角色：" + request.getRole());
        }
        
        // 2.1 平台管理员数量保护：不允许制造多个 admin，或把唯一的 admin 降级
        if ("admin".equals(request.getRole()) && !"admin".equals(user.getRole())) {
            // 把非 admin 用户改为 admin：若已存在 admin 则拒绝
            if (userMapper.countByRole("admin") > 0) {
                throw new IllegalArgumentException("系统已存在管理员账号，不允许创建多个管理员");
            }
        } else if ("admin".equals(user.getRole()) && !"admin".equals(request.getRole())) {
            // 把 admin 降级为非 admin：若这是唯一 admin 则拒绝
            if (userMapper.countByRole("admin") <= 1) {
                throw new IllegalArgumentException("不允许降级唯一的管理员账号");
            }
        }
        
        // 3. 如果修改了用户名，验证唯一性
        if (StringUtils.hasText(request.getUsername()) && 
            !request.getUsername().equals(user.getUsername())) {
            if (userMapper.countByUsername(request.getUsername()) > 0) {
                throw new IllegalArgumentException("用户名已存在：" + request.getUsername());
            }
            user.setUsername(request.getUsername());
        }
        
        // 4. 更新密码 (如果提供)
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(PasswordGenerator.encode(request.getPassword()));
        }
        
        // 5. 更新其他字段
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setUpdateTime(LocalDateTime.now());
        
        userMapper.updateById(user);
        log.info("用户信息更新成功：id={}", id);
        
        // 6. 更新用户 - 租户关联
        userTenantRelMapper.deleteByUserId(id);
        userTenantRelMapper.batchInsert(id, request.getTenantIds());
        log.info("用户 - 租户关联更新成功：userId={}, tenantIds={}", id, request.getTenantIds());
        
        // 7. 构建响应
        return buildUserResponse(user);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUser(Long id) {
        log.info("删除用户：id={}", id);
        
        // 1. 查询用户
        User user = userMapper.selectById(id);
        if (user == null) {
            return false;
        }
        
        // 1.1 不允许删除当前登录的账号（防自删）
        if (id.equals(UserContext.getCurrentUserId())) {
            throw new IllegalArgumentException("不能删除当前登录的账号");
        }
        // 1.2 不允许删除管理员账号（平台管理员唯一性保护）
        if ("admin".equals(user.getRole())) {
            throw new IllegalArgumentException("不允许删除管理员账号");
        }
        
        // 2. 删除用户 - 租户关联
        userTenantRelMapper.deleteByUserId(id);
        log.info("用户 - 租户关联删除成功：userId={}", id);
        
        // 3. 删除用户
        userMapper.deleteById(id);
        log.info("用户删除成功：id={}", id);
        
        return true;
    }
    
    /**
     * 判断当前登录用户是否为平台管理员
     */
    private boolean isCurrentUserAdmin() {
        return "admin".equals(UserContext.getCurrentUserRole());
    }
    
    /**
     * 验证角色是否合法
     */
    private boolean isValidRole(String role) {
        return "admin".equals(role) || "user".equals(role) || "viewer".equals(role);
    }
    
    /**
     * 构建用户响应对象
     */
    private UserDTO.UserResponse buildUserResponse(User user) {
        UserDTO.UserResponse response = new UserDTO.UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setCreateTime(user.getCreateTime() != null ? 
                user.getCreateTime().format(DATE_FORMATTER) : null);
        
        // 查询租户信息
        List<Long> tenantIds = userTenantRelMapper.findTenantIdsByUserId(user.getId());
        List<String> tenantNames = userTenantRelMapper.findTenantNamesByUserId(user.getId());
        
        response.setTenantIds(tenantIds);
        response.setTenantNames(tenantNames);
        
        return response;
    }
}
