package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 工具 - 代码检索
 * 在项目源码目录中搜索代码片段
 * <p>
 * 支持 Maven 多模块项目结构，自动搜索各模块的 src/main 和 src/test 目录。
 * 排除 target/ 编译输出目录，避免搜索 class 和打包文件。
 */
@Slf4j
@Component
public class CodeSearchTool implements AgentTool {

    /** 项目根目录，默认使用 user.dir（当前工作目录） */
    private final Path projectRoot;

    public CodeSearchTool(@Value("${app.code-search.src-base:#{null}}") String srcBase) {
        // 如果配置了 srcBase，优先使用；否则使用 user.dir
        if (srcBase != null && !srcBase.isBlank()) {
            this.projectRoot = Paths.get(srcBase).toAbsolutePath().normalize();
        } else {
            this.projectRoot = Paths.get(System.getProperty("user.dir")).normalize();
        }
        log.info("代码搜索根目录：{}", this.projectRoot);
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
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    // 排除 target/ 目录（编译输出）
                    .filter(p -> !p.toString().contains("\\target\\") && !p.toString().contains("/target/"))
                    // 排除 .git 目录
                    .filter(p -> !p.toString().contains("\\.git\\") && !p.toString().contains("/.git/"))
                    // 按文件扩展名过滤
                    .filter(p -> fileExtension == null || p.toString().endsWith(fileExtension))
                    .forEach(p -> {
                        try (Stream<String> lines = Files.lines(p)) {
                            lines.filter(line -> line.toLowerCase().contains(keyword.toLowerCase()))
                                    .findFirst()
                                    .ifPresent(line -> {
                                        // 转相对路径，使结果更简洁
                                        String relativePath = projectRoot.relativize(p).toString();
                                        result.append(relativePath).append(": ").append(line.trim()).append("\n");
                                    });
                        } catch (IOException e) {
                            // skip 不可读文件
                        }
                    });
        } catch (IOException e) {
            log.error("代码搜索失败，目录不可读：{}", projectRoot, e);
            return "代码搜索失败：" + e.getMessage();
        }
        
        String finalResult = result.length() > 0 ? result.toString() : "未找到匹配的代码";
        log.info("代码搜索完成，找到{}个匹配", result.length() > 0 ? result.toString().split("\n").length : 0);
        return finalResult;
    }
}