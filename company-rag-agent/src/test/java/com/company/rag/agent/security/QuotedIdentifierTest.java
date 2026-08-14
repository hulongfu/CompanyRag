package com.company.rag.agent.security;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试带引号标识符的跨租户访问漏洞
 * 
 * 验证问题：SELECT * FROM "tenant_other"."secret" 是否能绕过检查
 */
public class QuotedIdentifierTest {
    
    @Test
    public void testQuotedSchemaBypass() throws JSQLParserException {
        // 攻击者使用双引号包裹 schema.table 绕过检查
        String sql = "SELECT * FROM \"tenant_other\".\"secret\"";
        
        Statement statement = CCJSqlParserUtil.parse(sql);
        Select select = (Select) statement;
        
        Table table = (Table) select.getPlainSelect().getFromItem();
        
        System.out.println("=== JSqlParser 解析结果 ===");
        System.out.println("Table getName(): " + table.getName());
        System.out.println("Table getSchemaName(): " + table.getSchemaName());
        System.out.println("Table getFullyQualifiedName(): " + table.getFullyQualifiedName());
        
        // 当前漏洞：getName() 可能返回带引号的完整名称
        // getSchemaName() 可能为 null，导致 validateFromItem 检查通过
    }
    
    @Test
    public void testExtractTableNamesWithQuotedIdentifiers() throws JSQLParserException {
        String sql = "SELECT * FROM \"tenant_other\".\"secret\"";
        
        Set<String> tableNames = SqlSecurityValidator.extractTableNames(sql);
        
        System.out.println("\n=== 提取的表名 ===");
        System.out.println("Extracted table names: " + tableNames);
        
        // 验证：表名是否包含点号
        for (String tableName : tableNames) {
            System.out.println("  - " + tableName + " contains '.': " + tableName.contains("."));
        }
    }
    
    @Test
    public void testNormalSchemaAccess() throws JSQLParserException {
        // 正常的 schema.table 写法（不带引号）
        String sql = "SELECT * FROM tenant_other.secret";
        
        Statement statement = CCJSqlParserUtil.parse(sql);
        Select select = (Select) statement;
        
        Table table = (Table) select.getPlainSelect().getFromItem();
        
        System.out.println("\n=== 正常 schema.table 解析结果 ===");
        System.out.println("Table getName(): " + table.getName());
        System.out.println("Table getSchemaName(): " + table.getSchemaName());
        System.out.println("Table getFullyQualifiedName(): " + table.getFullyQualifiedName());
    }
    
    @Test
    public void testValidateFromItemWithQuotedIdentifier() throws JSQLParserException {
        // 测试 validateFromItem 是否能拦截带引号的跨租户访问
        String sql = "SELECT * FROM \"tenant_other\".\"secret\"";
        
        try {
            SqlSecurityValidator.validateSelectSql(sql);
            fail("应该抛出异常，禁止显式指定 schema");
        } catch (Exception e) {
            System.out.println("\n=== validateFromItem 拦截结果 ===");
            System.out.println("异常消息：" + e.getMessage());
            assertTrue(e.getMessage().contains("schema"), "应该提示禁止显式指定 schema");
        }
    }
}
