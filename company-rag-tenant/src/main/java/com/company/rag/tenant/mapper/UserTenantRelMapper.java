package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.UserTenantRel;
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
}
