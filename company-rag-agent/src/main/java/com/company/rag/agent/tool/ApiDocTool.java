package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP工具 - API文档生成
 * 动态扫描Spring MVC端点并生成API文档
 */
@Slf4j
@Component
public class ApiDocTool implements AgentTool {

    private final RequestMappingHandlerMapping handlerMapping;

    public ApiDocTool(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public String getName() {
        return "api_doc";
    }

    @Override
    public String getDescription() {
        return "扫描Spring MVC端点生成API文档，获取当前系统的所有REST接口信息。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "filter", Map.of(
                        "type", "string",
                        "description", "端点名称过滤关键字（可选）"
                )
        ));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String filter = params != null ? (String) params.get("filter") : null;
        return generateApiDoc(filter);
    }

    /**
     * 生成API文档
     */
    public String generateApiDoc(String filter) {
        var endpoints = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> filter == null || entry.getKey().toString().contains(filter))
                .map(entry -> {
                    var mapping = entry.getKey();
                    var method = entry.getValue();
                    return String.format("  %s %s -> %s.%s()",
                            mapping.getMethodsCondition().getMethods(),
                            mapping.getPathPatternsCondition(),
                            method.getBeanType().getSimpleName(),
                            method.getMethod().getName());
                })
                .collect(Collectors.joining("\n"));

        return "## API文档\n" + (endpoints.isEmpty() ? "无匹配端点" : endpoints);
    }
}