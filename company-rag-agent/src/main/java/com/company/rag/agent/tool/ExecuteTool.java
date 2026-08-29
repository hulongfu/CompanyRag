package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MCP 工具 - 执行系统命令
 * 用于执行安全的系统命令，如 Python 脚本、Shell 脚本等
 * 
 * 实现 AgentTool 接口以被 AgentToolRegistry 自动注册
 */
@Slf4j
@Component
public class ExecuteTool implements AgentTool {

    // 命令超时时间（秒）
    // web-search 等网络请求需要更长时间，设置为 60 秒
    private static final int COMMAND_TIMEOUT_SECONDS = 60;

    @Override
    public String getName() {
        return "execute";
    }

    @Override
    public String getDescription() {
        return "执行系统命令，如 Python 脚本、Shell 脚本等。适用于执行 calculator、web-search 等技能定义的命令（超时时间 60 秒）。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "command", Map.of(
                        "type", "string",
                        "description", "要执行的系统命令，例如：python scripts/calculator.py 50 + 50"
                )
        ));
        schema.put("required", new String[]{"command"});
        return schema;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String command = params != null ? (String) params.get("command") : null;
        if (command == null || command.trim().isEmpty()) {
            return "错误：命令不能为空";
        }
        return executeCommand(command);
    }

    /**
     * 执行系统命令（@Tool 注解版本，供 Spring AI 自动调用）
     * @param command 要执行的系统命令
     * @return 命令执行结果
     */
    @Tool(
        name = "execute",
        description = """
            执行系统命令，如 Python 脚本、Shell 脚本等。
            
            适用场景：
            - 执行 calculator 技能：python scripts/calculator.py 50 + 50
            - 执行 web-search 技能：python skills/web-search/scripts/search_tool.py "关键词"
            - 执行其他预定义的脚本文件
            
            不适用场景：
            - 危险命令（rm -rf、del、format 等）被禁止
            - 交互式命令（需要用户输入的）不支持
            - 超时限制：60 秒（网络请求可能需要更长时间）
            """
    )
    public String executeCommand(
            @ToolParam(description = "要执行的系统命令", required = true) String command) {
        
        log.info("执行命令：{}", command);
        
        // 安全检查：禁止危险命令
        if (!isCommandSafe(command)) {
            log.warn("检测到危险命令，拒绝执行：{}", command);
            return "错误：禁止执行危险命令（如删除、格式化等破坏性操作）";
        }
        
        try {
            // 解析命令
            String[] commandParts = parseCommand(command);
            
            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.redirectErrorStream(true); // 合并标准输出和错误输出
            
            Process process = processBuilder.start();
            
            // 等待命令执行完成（带超时）
            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("命令执行超时（{}秒）：{}", COMMAND_TIMEOUT_SECONDS, command);
                return "错误：命令执行超时（超过 " + COMMAND_TIMEOUT_SECONDS + " 秒）";
            }
            
            // 读取输出
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            
            int exitCode = process.exitValue();
            log.info("命令执行完成，退出码={}, 输出长度={}", exitCode, output.length());
            
            if (exitCode == 0) {
                return output.isEmpty() ? "命令执行成功，无输出" : output;
            } else {
                return "命令执行失败（退出码=" + exitCode + "）:\n" + output;
            }
            
        } catch (IOException e) {
            log.error("命令执行失败：{}", command, e);
            return "命令执行失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("命令执行被中断：{}", command, e);
            return "命令执行被中断";
        }
    }

    /**
     * 检查命令是否安全
     * 禁止执行危险命令
     */
    private boolean isCommandSafe(String command) {
        String lowerCommand = command.toLowerCase();
        
        // 禁止的危险命令列表
        String[] dangerousCommands = {
            "rm -rf", "rm -r", "rm ",  // 允许 rm 但禁止递归删除
            "del ", "deltree ",
            "format ", "mkfs",
            "dd ",
            "chmod 777", "chmod -R 777",
            "chown -R",
            "sudo rm", "sudo del",
            "> /dev/", ">> /dev/",
            "curl ", "wget ",  // 禁止下载执行
            "bash -c", "sh -c"  // 禁止 shell 注入
        };
        
        for (String dangerous : dangerousCommands) {
            if (lowerCommand.contains(dangerous)) {
                return false;
            }
        }
        
        // 允许执行 Python 命令的多种格式：
        // 1. python / python3 - 系统 PATH 中的 Python
        // 2. *.exe - Windows 上的 Python 可执行文件（如虚拟环境）
        // 3. /path/to/python - Unix 上的 Python 可执行文件路径
        if (isPythonCommand(lowerCommand)) {
            return true;
        }
        
        // 允许 file-manager 技能需要的安全系统命令
        if (isSafeSystemCommand(lowerCommand)) {
            return true;
        }
        
        log.warn("不允许的命令前缀，只允许执行 python/python3 命令、Python 可执行文件路径或安全的系统命令：{}", command);
        return false;
    }

    /**
     * 判断是否为安全的系统命令
     * 支持 file-manager 技能需要的命令
     */
    private boolean isSafeSystemCommand(String command) {
        // 允许 mkdir 命令（创建文件夹）
        if (command.startsWith("mkdir ")) {
            // 但要禁止危险参数
            if (command.contains("sudo") || command.contains("rm -rf") || command.contains("del ")) {
                return false;
            }
            return true;
        }
        
        // 允许 copy 命令（复制文件）
        if (command.startsWith("copy ") || command.startsWith("xcopy ")) {
            return true;
        }
        
        // 允许 move 命令（移动/重命名文件）
        if (command.startsWith("move ")) {
            return true;
        }
        
        // 允许 dir 命令（列出目录）
        if (command.startsWith("dir ")) {
            return true;
        }
        
        // 允许 cd 命令（切换目录）
        if (command.startsWith("cd ")) {
            return true;
        }
        
        // 允许 echo 命令（输出文本）
        if (command.startsWith("echo ")) {
            return true;
        }
        
        // 允许 type 命令（查看文件内容，Windows）
        if (command.startsWith("type ")) {
            return true;
        }
        
        return false;
    }

    /**
     * 判断是否为 Python 命令
     * 支持多种格式：python, python3, *.exe, /path/to/python
     */
    private boolean isPythonCommand(String command) {
        // 格式 1: python 或 python3 开头
        if (command.startsWith("python ") || command.startsWith("python3 ")) {
            return true;
        }
        
        // 格式 2: Windows Python 可执行文件路径（包含 python.exe 或 pythonw.exe）
        if (command.contains("python.exe ") || command.contains("pythonw.exe ")) {
            return true;
        }
        
        // 格式 3: Unix Python 路径（以 /python 或 /python3 结尾的路径）
        // 例如：/usr/bin/python3, /home/user/.venv/bin/python
        if (command.matches(".*[/\\\\]python3?[w]?\\..*") || 
            command.matches(".*[/\\\\]python3?[w]?\\s+.*")) {
            return true;
        }
        
        return false;
    }

    /**
     * 解析命令为数组
     * 支持简单的空格分隔
     */
    private String[] parseCommand(String command) {
        // 简单的空格分隔，后续可以改进为支持引号等复杂情况
        return command.split("\\s+");
    }
}
