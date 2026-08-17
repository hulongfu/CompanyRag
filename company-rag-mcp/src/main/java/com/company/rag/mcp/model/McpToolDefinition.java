package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.AllArgsConstructor;

/**
 * MCP 工具定义（tools/list 返回格式）
 */
@Data
@AllArgsConstructor
public class McpToolDefinition {
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具描述
     */
    private String description;
    
    /**
     * 输入参数 Schema（JSON Schema 格式）
     */
    @JsonProperty("inputSchema")
    private Object inputSchema;
}
