package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 工具 - 代码检索
 * 在项目源码目录中搜索代码片段
 */
@Slf4j
@Component
public class CodeSearchTool implements AgentTool {

    /** 源码根目录，可通过配置覆盖（Docker 容器中可能不同） */
    private final String srcBase;

    public CodeSearchTool(@Value("${app.code-search.src-base:./src}") String srcBase) {
        this.srcBase = srcBase;
    }

    @Override
    public String getName() {
        return "code_search";
    }

    @Override
    public String getDescription() {
        return "在项目源码目录中搜索代码片段，支持按关键词和文件类型过滤。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "keyword", Map.of(
                        "type", "string",
                        "description", "搜索关键词"
                ),
                "ext", Map.of(
                        "type", "string",
                        "description", "文件扩展名过滤（如 .java），可选"
                )
        ));
        schema.put("required", List.of("keyword"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String keyword = (String) params.get("keyword");
        String ext = (String) params.get("ext");
        return searchCode(keyword, ext);
    }

    /**
     * 在源码中搜索关键词（@Tool 注解版本，供 Spring AI 自动调用）
     * @param keyword 搜索关键词
     * @param fileExtension 文件扩展名过滤（如 .java），可选
     * @return 搜索结果
     */
    @Tool(name = "code_search", description = "在项目源码目录中搜索代码片段，支持按关键词和文件类型过滤。")
    public String searchCode(
            @ToolParam(description = "搜索关键词", required = true) String keyword,
            @ToolParam(description = "文件扩展名过滤（如 .java），可选", required = false) String fileExtension) {
        
        if (keyword == null || keyword.isBlank()) {
            return "错误：搜索关键词不能为空";
        }
        
        log.info("代码搜索：keyword={}, ext={}", keyword, fileExtension);
        
        StringBuilder result = new StringBuilder();
        try (Stream<java.nio.file.Path> paths = Files.walk(Paths.get(srcBase))) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> fileExtension == null || p.toString().endsWith(fileExtension))
                    .forEach(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            lines.filter(line -> line.toLowerCase().contains(keyword.toLowerCase()))
                                    .findFirst()
                                    .ifPresent(line -> result.append(p).append(": ").append(line.trim()).append("\n"));
                        } catch (IOException e) {
                            // skip
                        }
                    });
        } catch (IOException e) {
            return "代码搜索失败：" + e.getMessage();
        }
        
        String finalResult = result.length() > 0 ? result.toString() : "未找到匹配的代码";
        log.info("代码搜索完成，找到{}个匹配", result.length() > 0 ? result.toString().split("\n").length : 0);
        return finalResult;
    }
}
