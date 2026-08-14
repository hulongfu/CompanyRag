package com.company.rag.agent.security;

import com.company.rag.common.exception.BizException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.Table;

import java.util.HashSet;
import java.util.Set;

/**
 * SQL 安全验证器（使用 JSqlParser）
 * 
 * 提供完整的 SQL 语法分析，防止 SQL 注入攻击
 * 
 * @author CompanyRag
 * @since 2026-08-14
 */
public class SqlSecurityValidator {
    
    /**
     * 验证 SQL 是否安全（仅允许 SELECT 查询）
     * 
     * @param sql SQL 查询语句
     * @throws BizException 如果 SQL 不安全或语法错误
     */
    public static void validateSelectSql(String sql) {
        try {
            // 1. 解析 SQL
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            // 2. 验证必须是 SELECT
            if (!(statement instanceof Select)) {
                throw new BizException("仅支持 SELECT 查询");
            }
            
            Select selectStatement = (Select) statement;
            
            // 3. 验证查询体 (JSqlParser 5.0 中 Select 本身就是查询体)
            validateSelect(selectStatement);
            
        } catch (JSQLParserException e) {
            throw new BizException("SQL 语法错误：" + e.getMessage());
        }
    }
    
    /**
     * 验证 Select（JSqlParser 5.0 中 Select 本身就是查询体）
     */
    private static void validateSelect(Select select) {
        // JSqlParser 5.0: getPlainSelect() 对 SetOperationList 会抛出 ClassCastException
        // 需要先检查类型
        if (select instanceof SetOperationList) {
            // UNION/INTERSECT/EXCEPT 操作 - 禁止
            throw new BizException("禁止使用 UNION/INTERSECT/EXCEPT 操作符");
        }

        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect != null) {
            // 验证 FROM 子句
            validateFromItem(plainSelect.getFromItem());

            // 验证 JOIN
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    validateFromItem(join.getRightItem());
                }
            }

            // 验证 WHERE 子句（防止危险函数）
            if (plainSelect.getWhere() != null) {
                validateExpression(plainSelect.getWhere());
            }

            // 验证 HAVING 子句
            if (plainSelect.getHaving() != null) {
                validateExpression(plainSelect.getHaving());
            }
        }
    }
    
    /**
     * 验证 FromItem（表或子查询）
     */
    private static void validateFromItem(FromItem fromItem) {
        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            
            // 检查是否有 schema 前缀（将在 DatabaseQueryTool 中统一添加）
            if (table.getSchemaName() != null) {
                throw new BizException("禁止显式指定 schema");
            }
            
        } else if (fromItem instanceof ParenthesedSelect) {
            // 递归验证子查询 (JSqlParser 5.0: ParenthesedSelect 有 getSelect() 方法)
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            validateSelect(parenthesedSelect.getSelect());
            
        } else if (fromItem instanceof ParenthesedFromItem) {
            // 括号包裹的项
            ParenthesedFromItem parenthesis = (ParenthesedFromItem) fromItem;
            validateFromItem(parenthesis.getFromItem());
        }
    }
    
    /**
     * 验证表达式（防止危险函数）
     */
    private static void validateExpression(net.sf.jsqlparser.expression.Expression expression) {
        if (expression == null) {
            return;
        }
        
        // 检查函数调用
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.ExpressionList) {
            net.sf.jsqlparser.expression.operators.relational.ExpressionList list = 
                (net.sf.jsqlparser.expression.operators.relational.ExpressionList) expression;
            for (Object expr : list.getExpressions()) {
                if (expr instanceof net.sf.jsqlparser.expression.Expression) {
                    validateExpression((net.sf.jsqlparser.expression.Expression) expr);
                }
            }
        }
        
        // 检查是否是函数
        if (expression instanceof net.sf.jsqlparser.expression.Function) {
            net.sf.jsqlparser.expression.Function function = 
                (net.sf.jsqlparser.expression.Function) expression;
            String functionName = function.getName();
            
            // 禁止危险函数
            if (isDangerousFunction(functionName)) {
                throw new BizException("禁止使用危险函数：" + functionName);
            }
        }
    }
    
    /**
     * 检查是否是危险函数
     */
    private static boolean isDangerousFunction(String functionName) {
        if (functionName == null) {
            return false;
        }
        
        String upperName = functionName.toUpperCase();
        
        // PostgreSQL 危险函数列表
        String[] dangerousFunctions = {
            "COPY",           // 文件导出
            "LO_IMPORT",      // 大对象导入
            "LO_EXPORT",      // 大对象导出
            "PG_READ_FILE",   // 读取文件
            "PG_WRITE_FILE",  // 写入文件
            "PG_LS_DIR",      // 列出目录
            "PG_READ_BINARY_FILE",
            "PG_READ_FILE_EXTENDED",
            "EXEC",           // 执行命令
            "EXECUTE",
            "XP_CMDDASH",     // SQL Server 命令执行
            "SP_EXECUTESQL",
            "INFORMATION_SCHEMA", // 信息模式（可能被用于信息收集）
        };
        
        for (String dangerous : dangerousFunctions) {
            if (upperName.contains(dangerous)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 提取 SQL 中的所有表名
     */
    public static Set<String> extractTableNames(String sql) throws JSQLParserException {
        Set<String> tableNames = new HashSet<>();
        Statement statement = CCJSqlParserUtil.parse(sql);
        
        if (statement instanceof Select) {
            Select select = (Select) statement;
            extractTableNamesFromSelect(select, tableNames);
        }
        
        return tableNames;
    }
    
    /**
     * 从 Select 中提取表名（JSqlParser 5.0）
     */
    private static void extractTableNamesFromSelect(Select select, Set<String> tableNames) {
        if (select instanceof SetOperationList) {
            return;
        }

        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect != null) {
            extractTableNamesFromFromItem(plainSelect.getFromItem(), tableNames);

            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    extractTableNamesFromFromItem(join.getRightItem(), tableNames);
                }
            }
        }
    }
    
    /**
     * 从 FromItem 中提取表名
     */
    private static void extractTableNamesFromFromItem(FromItem fromItem, Set<String> tableNames) {
        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            tableNames.add(table.getName());
        } else if (fromItem instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            extractTableNamesFromSelect(parenthesedSelect.getSelect(), tableNames);
        }
    }
}
