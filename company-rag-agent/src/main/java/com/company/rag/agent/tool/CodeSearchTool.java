package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
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
 * MCP工具 - 代码检索
 * 在项目源码目录中搜索代码片段
 */
@Slf4j
@Component
public class CodeSearchTool implements AgentTool {

    /** 源码根目录，可通过配置覆盖（Docker容器中可能不同） */
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
        if (keyword == null || keyword.isBlank()) {
            return "错误：搜索关键词不能为空";
        }
        return searchCode(keyword, ext);
    }

    /**
     * 在源码中搜索关键词
     */
    public String searchCode(String keyword, String fileExtension) {
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
            return "代码搜索失败: " + e.getMessage();
        }
        return result.length() > 0 ? result.toString() : "未找到匹配的代码";
    }
}