package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户（跨租户，供登录认证使用）
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    User findByUsername(String username);

    /**
     * 根据用户名查询用户 (用于验证唯一性)
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
    int countByUsername(String username);

    /**
     * 根据租户 ID 查询用户列表 (关联租户表)
     */
    @Select("<script>" +
            "SELECT u.* FROM sys_user u " +
            "INNER JOIN sys_user_tenant_rel rel ON u.id = rel.user_id " +
            "WHERE rel.tenant_id = #{tenantId}" +
            "<if test='status != null'> AND u.status = #{status}</if>" +
            "</script>")
    List<User> findByTenantId(@Param("tenantId") Long tenantId, @Param("status") Integer status);
}
