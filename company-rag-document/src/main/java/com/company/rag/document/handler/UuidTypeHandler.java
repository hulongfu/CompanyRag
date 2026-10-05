package com.company.rag.document.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * java.util.UUID 类型处理器。
 *
 * <p>让 MyBatis-Plus 的 BaseMapper insert/select 能正确处理 UUID 主键
 * （否则报 "Type handler was null ... javaType (java.util.UUID) : jdbcType (null)"）。
 * 通过 application.yml 的 mybatis-plus.type-handlers-package 自动注册。</p>
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        // PG 驱动会将 java.util.UUID 直接映射到 pg uuid 类型，无需显式 jdbcType
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getString(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getString(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getString(columnIndex));
    }

    private UUID toUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return UUID.fromString(value);
    }
}
