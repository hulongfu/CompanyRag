package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 回归报告快照 Mapper。
 *
 * <p>分页读侧不依赖 MyBatis-Plus 分页插件（{@code TenantMyBatisPlusConfig} 未注册
 * {@code PaginationInnerInterceptor}），改为手写 LIMIT/OFFSET + 独立 count 两条语句。
 * {@code ${schema}} 只能存经 Service 白名单校验的 {@code TenantContext.getSchema()}，
 * 其余参数一律 {@code #{}} 绑定；两条语句都必须声明 {@code @Param("schema")}，
 * 否则 MyBatis 运行期报 {@code Parameter 'schema' not found}。
 */
@Mapper
public interface EvalRegressionReportMapper extends BaseMapper<EvalRegressionReportEntity> {

    /** 分页查回归报告历史（仅按租户过滤，无时间条件；run_time DESC + id DESC 二级排序保证稳定分页） */
    @Select("""
        SELECT * FROM ${schema}.eval_regression_report
        WHERE tenant_id = #{tenantId}
        ORDER BY run_time DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<EvalRegressionReportEntity> selectHistoryPage(@Param("schema") String schema,
                                                        @Param("tenantId") Long tenantId,
                                                        @Param("limit") int limit,
                                                        @Param("offset") long offset);

    /** 统计该租户回归报告总数（与查记录同一 WHERE 基，不含 LIMIT/OFFSET） */
    @Select("""
        SELECT COUNT(*) FROM ${schema}.eval_regression_report
        WHERE tenant_id = #{tenantId}
        """)
    long countHistory(@Param("schema") String schema, @Param("tenantId") Long tenantId);
}
