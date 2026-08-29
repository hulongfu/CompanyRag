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
            
            注意：在 Git Bash 环境中，自动将 Windows cmd 命令转换为 Bash 语法
            （例如：set VAR=value → export VAR=value）
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
            // 命令预处理：将 Windows cmd 语法转换为 Unix Shell 语法
            String processedCommand = preprocessCommand(command);
            log.debug("预处理后的命令：{}", processedCommand);
            
            // 检测是否需要通过 Shell 执行（包含 &&、||、export 等 Shell 特性）
            boolean useShell = requiresShellExecution(processedCommand);
            
            ProcessBuilder processBuilder;
            if (useShell) {
                // 根据操作系统选择正确的 Shell
                String osName = System.getProperty("os.name").toLowerCase();
                boolean isWindows = osName.contains("win");
                
                if (isWindows) {
                    // Windows: 使用 cmd.exe /c 执行
                    log.info("检测到 Windows 环境，使用 cmd.exe /c 执行");
                    processBuilder = new ProcessBuilder("cmd.exe", "/c", processedCommand);
                } else {
                    // Linux/macOS: 使用 /bin/sh -c 执行
                    log.info("检测到 Unix 环境，使用 /bin/sh -c 执行");
                    processBuilder = new ProcessBuilder("/bin/sh", "-c", processedCommand);
                }
            } else {
                // 直接执行可执行文件（简单命令）
                String[] commandParts = parseCommand(processedCommand);
                log.info("直接执行命令：{}", String.join(" ", commandParts));
                processBuilder = new ProcessBuilder(commandParts);
            }
            
            processBuilder.redirectErrorStream(true); // 合并标准输出和错误输出
            
            // 设置工作目录：如果命令包含技能路径，自动切换到技能目录
            String workingDir = detectSkillWorkingDirectory(command);
            if (workingDir != null) {
                processBuilder.directory(new java.io.File(workingDir));
                log.debug("设置工作目录：{}", workingDir);
            }
            
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
     * 预处理命令：根据操作系统转换命令语法
     * 
     * Windows (cmd.exe) 转换规则：
     * 1. set VAR=value → set VAR=value（保持不变）
     * 2. export VAR=value → set VAR=value
     * 3. chcp 65001 && → （移除，不需要）
     * 
     * Unix (bash/sh) 转换规则：
     * 1. set VAR=value → export VAR=value
     * 2. chcp 65001 && → （移除，默认 UTF-8）
     * 
     * @param command 原始命令
     * @return 转换后的命令
     */
    private String preprocessCommand(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        
        String processed = command;
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isWindows = osName.contains("win");
        
        if (isWindows) {
            // Windows 环境：转换为 cmd.exe 语法
            
            // 1. 移除 chcp 65001 &&
            processed = processed.replaceAll("chcp\\s+65001\\s*&&\\s*", "");
            
            // 2. export VAR=value → set VAR=value
            processed = processed.replaceAll("\\bexport\\s+([a-zA-Z_][a-zA-Z0-9_]*)=", "set $1=");
            
            log.debug("Windows 命令预处理：{} → {}", command, processed);
        } else {
            // Unix 环境：转换为 bash/sh 语法
            
            // 1. 移除 chcp 65001 &&
            processed = processed.replaceAll("chcp\\s+65001\\s*&&\\s*", "");
            
            // 2. set VAR=value → export VAR=value
            processed = processed.replaceAll("\\bset\\s+([a-zA-Z_][a-zA-Z0-9_]*)=", "export $1=");
            
            log.debug("Unix 命令预处理：{} → {}", command, processed);
        }
        
        return processed;
    }

    /**
     * 判断命令是否需要通过 Shell 执行
     * 
     * ProcessBuilder 直接执行可执行文件，不支持：
     * - Shell 内建命令（export, cd, source, alias 等）
     * - 管道（|）
     * - 重定向（>, <, >>）
     * - 逻辑运算符（&&, ||）
     * - 通配符展开（*, ?）
     * 
     * @param command 命令
     * @return true 如果需要 Shell 执行
     */
    private boolean requiresShellExecution(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        
        // 检查是否包含 Shell 特性
        String[] shellFeatures = {
            "&&", "||", "|",  // 逻辑运算符和管道
            ">", "<", ">>", "2>", "&>",  // 重定向
            ";",  // 命令分隔符
            "$(", "`",  // 命令替换
            "${",  // 变量展开
            "*", "?", "[",  // 通配符（简单检查）
            "export ", "cd ", "source ", "alias ",  // Shell 内建命令
            "echo ", "printf "  // 可能需要 Shell 展开
        };
        
        for (String feature : shellFeatures) {
            if (command.contains(feature)) {
                return true;
            }
        }
        
        return false;
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
     * 支持简单的空格分隔，后续可以改进为支持引号等复杂情况
     */
    private String[] parseCommand(String command) {
        // 简单的空格分隔，后续可以改进为支持引号等复杂情况
        return command.split("\\s+");
    }

    /**
     * 检测技能工作目录
     * 根据命令中的路径自动推断技能目录，设置工作目录以支持相对路径
     * 
     * @param command 完整命令
     * @return 工作目录路径，如果无法检测则返回 null
     */
    private String detectSkillWorkingDirectory(String command) {
        // 匹配模式：skills/{skill-name}/scripts/
        // 例如：skills/file-manager/scripts/file_manager.py
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("skills/([^/]+)/scripts/");
        java.util.regex.Matcher matcher = pattern.matcher(command);
        
        if (matcher.find()) {
            String skillName = matcher.group(1);
            // 构建技能目录路径（相对于项目根目录）
            String userDir = System.getProperty("user.dir");
            String skillDir = userDir + "/agent_skills/" + skillName;
            
            // 验证目录是否存在
            java.io.File dir = new java.io.File(skillDir);
            if (dir.exists() && dir.isDirectory()) {
                return skillDir;
            }
        }
        
        return null;
    }
}
