package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * MCP 工具定义（tools/list 返回格式）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
    
    /**
     * 输出参数 Schema（JSON Schema 格式，可选）
     */
    @JsonProperty("outputSchema")
    private Object outputSchema;
}
