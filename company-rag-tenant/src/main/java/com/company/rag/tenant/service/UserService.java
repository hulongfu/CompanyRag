package com.company.rag.tenant.service;

import com.company.rag.common.model.UserDTO;
import java.util.List;

/**
 * 用户服务接口
 */
public interface UserService {
    
    /**
     * 创建用户
     * @param request 创建用户请求
     * @return 创建的用户信息
     */
    UserDTO.UserResponse createUser(UserDTO.CreateRequest request);
    
    /**
     * 查询用户列表 (支持筛选)
     * @param role 角色筛选 (可选)
     * @param tenantId 租户 ID 筛选 (可选)
     * @param status 状态筛选 (可选)
     * @param username 用户名模糊搜索 (可选)
     * @return 用户列表
     */
    List<UserDTO.UserResponse> queryUserList(String role, Long tenantId, Integer status, String username);
    
    /**
     * 根据 ID 查询用户详情
     * @param id 用户 ID
     * @return 用户详情
     */
    UserDTO.UserDetailResponse getUserById(Long id);
    
    /**
     * 更新用户信息
     * @param id 用户 ID
     * @param request 更新用户请求
     * @return 更新后的用户信息
     */
    UserDTO.UserResponse updateUser(Long id, UserDTO.UpdateRequest request);
    
    /**
     * 删除用户
     * @param id 用户 ID
     * @return 是否删除成功
     */
    boolean deleteUser(Long id);
}
