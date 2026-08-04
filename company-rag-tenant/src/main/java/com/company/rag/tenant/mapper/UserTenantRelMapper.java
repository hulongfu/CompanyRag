package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.UserTenantRel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface UserTenantRelMapper extends BaseMapper<UserTenantRel> {
    
    /**
     * 根据用户 ID 查询关联的租户 ID 列表
     */
    @Select("SELECT tenant_id FROM sys_user_tenant_rel WHERE user_id = #{userId}")
    List<Long> findTenantIdsByUserId(Long userId);
    
    /**
     * 根据租户 ID 查询关联的用户 ID 列表
     */
    @Select("SELECT user_id FROM sys_user_tenant_rel WHERE tenant_id = #{tenantId}")
    List<Long> findUserIdsByTenantId(Long tenantId);

    /**
     * 根据用户 ID 删除关联记录
     */
    @Delete("DELETE FROM sys_user_tenant_rel WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);

    /**
     * 批量插入用户 - 租户关联
     */
    @Insert("<script>" +
            "INSERT INTO sys_user_tenant_rel (user_id, tenant_id) VALUES " +
            "<foreach collection='tenantIds' item='tenantId' separator=','>" +
            "(#{userId}, #{tenantId})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("userId") Long userId, @Param("tenantIds") List<Long> tenantIds);

    /**
     * 根据用户 ID 查询租户名称列表
     */
    @Select("<script>" +
            "SELECT t.tenant_name FROM sys_user_tenant_rel rel " +
            "INNER JOIN sys_tenant t ON rel.tenant_id = t.id " +
            "WHERE rel.user_id = #{userId}" +
            "</script>")
    List<String> findTenantNamesByUserId(Long userId);
}
