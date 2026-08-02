package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具 - API 文档生成
 * 动态扫描 Spring MVC 端点并生成 API 文档
 */
@Slf4j
@Component
public class ApiDocTool implements AgentTool {

    private final RequestMappingHandlerMapping handlerMapping;

    @Autowired
    public ApiDocTool(RequestMappingHandlerMapping requestMappingHandlerMapping) {
        // 明确使用 requestMappingHandlerMapping，避免与 controllerEndpointHandlerMapping 混淆
        this.handlerMapping = requestMappingHandlerMapping;
    }

    @Override
    public String getName() {
        return "api_doc";
    }

    @Override
    public String getDescription() {
        return "扫描 Spring MVC 端点生成 API 文档，获取当前系统的 REST 接口信息。";
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
     * 生成 API 文档（@Tool 注解版本，供 Spring AI 自动调用）
     * @param filter 过滤关键字（可选）
     * @return API 文档 Markdown 字符串
     */
    @Tool(
        name = "api_doc",
        description = """
            扫描 Spring MVC 端点生成 API 文档，返回当前系统的 REST 接口信息。
            支持按关键字过滤端点。
            
            适用场景：
            - 查看系统有哪些 REST 接口、接口的请求方法和路径
            - 例如："生成 API 文档"、"查看用户相关的接口"、"有哪些 GET 接口？"
            
            不适用场景：
            - 接口的详细业务逻辑 -> 使用 code_search 搜索对应 Controller
            """
    )
    public String generateApiDoc(
            @ToolParam(description = "端点名称过滤关键字（可选）", required = false) String filter) {
        log.info("生成 API 文档，filter={}", filter);
        
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

        String result = "## API 文档\n" + (endpoints.isEmpty() ? "无匹配端点" : endpoints);
        log.info("API 文档生成完成，找到{}个端点", endpoints.isEmpty() ? 0 : endpoints.split("\n").length);
        return result;
    }
}
