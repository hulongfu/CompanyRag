package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MCP 工具 - 执行受约束的系统命令
 *
 * 安全模型（取代旧的"substring 黑名单 + 前缀白名单"）：
 * 1. 命令白名单：只允许 python 与少量只读诊断命令（ls/dir/pwd/type/cat/echo），白名单外一律拒绝。
 * 2. 无 Shell：所有执行基于 ProcessBuilder 直 exec / Java 进程内处理，永不经过 cmd /c、/bin/sh -c，
 *    因此 &&、;、|、$()、反引号、通配符展开等 shell 语义天然不被解释。
 * 3. python 特判：只执行"技能 scripts 目录"下的预审批脚本（真实存在、canonical 解析后仍落于 scripts 内），
 *    绝不执行 cwd/默认工作目录/下载目录下由生成或上传产生的脚本，杜绝"生成代码后执行"。
 * 4. 只读诊断命令：不做任何写操作，可访问路径被限制在受信任根内（cwd、各技能 scripts、登记的信任目录）。
 *
 * 防御思路：既然威胁模型为"LLM/用户相对可信 + 仅内部 RAG Agent 编排"，且只运行可信预审批脚本，
 * 无需 OS 级沙箱即可闭环——信任锚是技能 scripts 目录内容，故该目录必须只读、版本受控。
 */
@Slf4j
@Component
public class ExecuteTool implements AgentTool {

    // 命令超时时间（秒）；web-search 等网络请求需要更长时间，设置为 60 秒
    private static final int COMMAND_TIMEOUT_SECONDS = 60;

    // 进程内只读查看文本文件的最大字节数，防止一次性读取超大文件
    private static final long MAX_DIAG_READ_BYTES = 64 * 1024;

    @Value("${agent.python-exec-path:D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe}")
    private String pythonExecPath;

    // 技能根目录。由 app.skill-base 配置（yml 默认 ${AGENT_SKILL_BASE:./agent_skills}），
    // 命令中的技能前缀即取自该配置目录名，避免在代码里硬编码目录名。
    @Value("${app.skill-base:#{null}}")
    private String skillBase;

    // 未匹配任何技能时的默认工作目录（未配置或不存时回退 user.dir）
    @Value("${app.default-work-dir:#{null}}")
    private String defaultWorkDir;

    // 登记的受信任目录白名单（逗号分隔）；仅放行只读诊断访问，不放行 python 脚本执行
    @Value("${app.trusted-dirs:}")
    private String trustedDirs;

    @Override
    public String getName() {
        return "execute";
    }

    @Override
    public String getDescription() {
        return "执行受约束的命令：Python 技能脚本，或只读诊断命令（ls/dir/pwd/type/cat/echo）。仅允许技能 scripts 目录下的预审批 Python 脚本；诊断命令只读。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new java.util.HashMap<>();
        schema.put("type", "object");
        String skillPrefix = skillDirName();
        String example;
        if (!skillPrefix.isEmpty()) {
            example = "要执行的命令，例如：python " + skillPrefix
                    + "/calculator/scripts/calculator.py 50 + 50，或 ls，或 type config.yaml";
        } else {
            example = "要执行的命令，例如：python <技能根目录>/calculator/scripts/calculator.py 50 + 50，"
                    + "或 ls，或 type config.yaml";
        }
        schema.put("properties", Map.of(
                "command", Map.of(
                        "type", "string",
                        "description", example
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
     * 执行受约束命令（@Tool 注解版本，供 Spring AI 自动调用）
     *
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    @Tool(
        name = "execute",
        description = """
            执行受约束的命令。
            
            允许的命令（命令白名单）：
            - Python 技能脚本：python <技能根目录名>/{技能名}/scripts/xxx.py [参数]    仅限预审批技能脚本，禁止 -c/-m/-i 内联代码
            - 只读诊断命令：ls / dir [路径]、pwd、type / cat <文件>、echo <文本>
            
            安全约束：
            - 只允许上述白名单命令；系统写类命令（copy/move/del/mkdir/rm 等）一律拒绝，文件操作请用 file-manager
            - 不使用 shell（无管道/重定向/命令组合/通配符展开/环境变量展开）
            - Python 脚本只能来自技能 scripts 目录，且必须真实存在；cwd、默认工作目录与下载目录的脚本一律拒绝
            - 危险/写类命令被禁止
            - 交互式命令（需要用户输入的）不支持
            - 只读诊断命令可访问的路径被限制在受信任根内（cwd、各技能 scripts、登记的信任目录）
            - 超时限制：60 秒
            """
    )
    public String executeCommand(
            @ToolParam(description = "要执行的命令", required = true) String command) {
        log.info("执行命令：{}", command);

        // 命令白名单 + 元字符 + 路径校验（null 表示通过）
        String rejectedReason = rejectUnsafePath(command);
        if (rejectedReason != null) {
            log.warn("命令被拒绝：{}，原因：{}", command, rejectedReason);
            return "错误：" + rejectedReason;
        }

        // 路径归一化：将所有 python 命令替换为配置的 python 路径
        String normalizedCommand = normalizePythonPath(command);
        String[] tokens = parseCommand(normalizedCommand);
        CmdKind kind = classify(tokens[0]);

        switch (kind) {
            case PYTHON:
                return runPython(command, tokens);
            case LIST:
            case READ:
                return runDiagnostic(command, kind, tokens);
            case PWD:
                return runPwd(command);
            case ECHO:
                return runEcho(tokens);
            default:
                return "错误：命令不在白名单";
        }
    }

    /**
     * 命令分类 RM
     */
    private enum CmdKind { PYTHON, LIST, READ, PWD, ECHO, UNKNOWN }

    /**
     * 根据命令第一个 token 判断命令类别
     */
    private CmdKind classify(String firstToken) {
        if (isPythonExecutable(firstToken)) {
            return CmdKind.PYTHON;
        }
        String lower = firstToken.toLowerCase();
        switch (lower) {
            case "ls":
            case "dir":
                return CmdKind.LIST;
            case "type":
            case "cat":
                return CmdKind.READ;
            case "pwd":
                return CmdKind.PWD;
            case "echo":
                return CmdKind.ECHO;
            default:
                return CmdKind.UNKNOWN;
        }
    }

    /**
     * 命令级安全准入：命令白名单 + token 级元字符拒绝 + 按命令分发路径校验。
     * 这是替换旧 isCommandSafe / rejectUnsafePath / containsShellMetachar 的统一入口。
     *
     * @param command 原始命令
     * @return 拒绝原因；null 表示安全放行
     */
    String rejectUnsafePath(String command) {
        if (command == null || command.isBlank()) {
            return "命令为空";
        }
        String[] tokens = parseCommand(command);
        if (tokens.length == 0) {
            return "命令为空";
        }
        CmdKind kind = classify(tokens[0]);
        if (kind == CmdKind.UNKNOWN) {
            return "命令不在白名单，仅允许 python 及只读诊断命令（ls/dir/pwd/type/cat/echo）";
        }

        // token 级元字符拒绝（独立 token 形式的 shell 复合/重定向符，杜绝命令拼接）
        String metaReason = rejectMetacharTokens(tokens);
        if (metaReason != null) {
            return metaReason;
        }

        switch (kind) {
            case PYTHON:
                if (tokens.length < 2) {
                    return "python 命令缺少脚本路径";
                }
                return rejectPythonScript(tokens[1]);
            case LIST:
                return tokens.length > 1
                        ? rejectDiagnosticPath(tokens[1], command, /*mustBeDir*/ true)
                        : null;
            case READ:
                if (tokens.length < 2) {
                    return "读文件命令缺少文件路径";
                }
                return rejectDiagnosticPath(tokens[1], command, false);
            default:
                // pwd / echo 无路径操作
                return null;
        }
    }

    /**
     * 拒绝任何独立成 token 的 shell 复合/重定向元字符。
     * 基于 token 而非 substring：引号内的 "a && b" 是单个 token，不会被误伤。
     */
    private String rejectMetacharTokens(String[] tokens) {
        for (String token : tokens) {
            if (token.startsWith("$(") || token.equals("`")
                    || token.equals(";") || token.equals("&&") || token.equals("||")
                    || token.equals("|") || token.equals(">") || token.equals(">>")
                    || token.equals("<") || token.startsWith("${")) {
                return "命令不允许包含 shell 复合/重定向元字符：" + token
                        + "（不使用 shell，仅允许单条进程内/直 exec 命令）";
            }
        }
        return null;
    }

    /**
     * python 特判：脚本 token 必须真实存在并 canonical 解析后仍落于某技能 scripts 目录内，
     * 且不得使用解释器内联选项（-c/-m/-i）。cwd / 默认工作目录 / 下载目录的脚本一律拒绝。
     */
    private String rejectPythonScript(String scriptToken) {
        String clean = unquote(scriptToken);
        if (clean.isEmpty()) {
            return "python 脚本路径为空";
        }
        // 拒绝解释器内联选项：必须执行脚本文件
        if (clean.startsWith("-")) {
            return "python 命令不允许使用解释器选项执行内联代码（如 -c/-m/-i），必须执行脚本文件";
        }

        // 允许的 python 根 = 所有技能 scripts 目录（canonical）
        List<Path> allowedScriptRoots = skillScriptRoots();
        if (allowedScriptRoots.isEmpty()) {
            return "未配置任何技能脚本目录，无法执行 python 脚本";
        }

        // 脚本候选：绝对路径直接取；相对路径按技能根与工作目录分别解析
        // （此点已由上方 allowedScriptRoots 非空校验保证 skillBase 已配置）
        java.io.File wd = resolveWorkingDirectory(scriptToken);
        java.io.File skillBaseFile = new java.io.File(skillBaseDir());
        List<java.io.File> candidates = new ArrayList<>();
        java.io.File rawFile = new java.io.File(clean);
        if (rawFile.isAbsolute()) {
            candidates.add(rawFile);
        } else {
            // 命令形如 {配置技能根目录名}/{name}/scripts/x.py，
            // 需剥掉配置目录名前缀再定位到技能目录；无此前缀则原样保留。
            String stripped = clean;
            String prefix = skillDirName();
            if (!prefix.isEmpty() && stripped.startsWith(prefix + "/")) {
                stripped = stripped.substring(prefix.length() + 1);
            }
            candidates.add(new java.io.File(skillBaseFile, stripped));
            candidates.add(new java.io.File(skillBaseFile, clean));
            if (wd != null) {
                candidates.add(new java.io.File(wd, clean));
            }
        }

        for (java.io.File candidate : candidates) {
            if (!candidate.exists()) {
                continue;
            }
            Path real;
            try {
                real = candidate.toPath().toRealPath();
            } catch (IOException e) {
                continue;
            }
            if (isWithinAny(real, allowedScriptRoots)) {
                return null;
            }
        }
        return "python 脚本必须位于某技能 scripts 目录且真实存在（cwd/默认工作目录/下载目录的脚本一律拒绝）：" + clean;
    }

    /**
     * 只读诊断命令路径校验：目标路径 canonical 后必须落在受信任根内。
     *
     * @param shellCmd 用于判定默认工作目录
     */
    private String rejectDiagnosticPath(String pathToken, String shellCmd, boolean mustBeDir) {
        String clean = unquote(pathToken);
        if (clean.isEmpty()) {
            return "路径为空";
        }
        java.io.File wd = resolveWorkingDirectory(shellCmd);
        if (wd == null) {
            wd = new java.io.File(System.getProperty("user.dir"));
        }
        Path resolved = resolveWithinRoots(clean, wd);
        if (resolved == null) {
            return clean + " 不在受信任的只读路径范围内（仅 cwd、各技能 scripts、登记的信任目录），拒绝访问";
        }
        if (mustBeDir && !Files.isDirectory(resolved)) {
            return clean + " 不是目录";
        }
        if (!mustBeDir && !Files.isRegularFile(resolved)) {
            return clean + " 不是常规文件";
        }
        return null;
    }

    /**
     * 执行 python 脚本（进程外直 exec，无 shell）。
     */
    private String runPython(String command, String[] tokens) {
        // tokens[0] 已被 normalize 为 python 解释器路径，直接作为 argv
        String[] commandParts = tokens;
        log.debug("归一化后命令：{}", String.join(" ", commandParts));

        java.io.File workingDir = resolveWorkingDirectory(command);
        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.redirectErrorStream(true);

        // 白名单重建子进程环境：默认 ProcessBuilder.environment() 会把父进程全部环境变量（含
        // JWT_SECRET / DASHSCOPE_API_KEY 等密钥）同样拷贝给子进程。系统只运行可信预审批技能脚本，
        // 但为避免密钥随脚本扩散，这里清空后仅保留脚本运行必需的最小变量集，再注入技能根目录。
        // 保留项取舍：PATH/SystemRoot/SystemDrive 为解释器与 Windows 运行必需；TEMP/TMP/LANG/
        // PYTHONIOENCODING/USERPROFILE/HOME 为 Python 标准库与编码解析所用，缺失会导致乱码或功能异常。
        java.util.Map<String, String> env = processBuilder.environment();
        env.clear();
        copyEnv(env, "PATH");
        copyEnv(env, "SystemRoot");
        copyEnv(env, "SystemDrive");
        copyEnv(env, "TEMP");
        copyEnv(env, "TMP");
        copyEnv(env, "LANG");
        copyEnv(env, "PYTHONIOENCODING");
        copyEnv(env, "USERPROFILE");
        copyEnv(env, "HOME");
        // 向技能脚本（含 file-manager）注入技能根目录，使其能动态识别 scripts 信任锚做只读守卫，避免脚本内硬编码路径
        String skillBasePath = skillBaseDir();
        if (skillBasePath != null) {
            env.put("AGENT_SKILL_BASE", skillBasePath);
        }
        if (workingDir != null) {
            processBuilder.directory(workingDir);
            log.debug("设置工作目录：{}", workingDir.getAbsolutePath());
        } else {
            log.debug("未设置工作目录，沿用父进程工作目录");
        }
        return runWithTimeout(processBuilder, String.join(" ", commandParts));
    }

    /**
     * 仅当源环境存在时，将其值放入子进程环境白名单（复制该键），用于最小化子进程暴露的敏感环境变量。
     */
    private void copyEnv(java.util.Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null) {
            env.put(key, value);
        }
    }

    /**
     * 只读诊断命令的进程内实现（不产生子进程，天然无 shell 语义、无写操作）。
     */
    private String runDiagnostic(String command, CmdKind kind, String[] tokens) {
        java.io.File wd = resolveWorkingDirectory(command);
        if (wd == null) {
            wd = new java.io.File(System.getProperty("user.dir"));
        }
        String target = tokens.length > 1 ? unquote(tokens[1]) : ".";
        Path resolved = resolveWithinRoots(target, wd);
        if (resolved == null) {
            return "错误：路径不在受信任的只读范围内：" + target;
        }

        if (kind == CmdKind.LIST) {
            if (!Files.isDirectory(resolved)) {
                return "错误：不是目录：" + target;
            }
            try (java.util.stream.Stream<Path> stream = Files.list(resolved)) {
                List<String> names = stream.map(p -> p.getFileName().toString()).sorted()
                        .collect(Collectors.toList());
                return "目录条目（" + names.size() + "）：" + String.join(", ", names);
            } catch (IOException e) {
                log.error("列出目录失败：{}", resolved, e);
                return "错误：列出目录失败：" + e.getMessage();
            }
        }

        // READ（type/cat）：只读查看文本文件
        if (!Files.isRegularFile(resolved)) {
            return "错误：不是常规文件：" + target;
        }
        try {
            long size = Files.size(resolved);
            if (size > MAX_DIAG_READ_BYTES) {
                return "错误：文件过大（" + size + " 字节），仅允许查看 " + MAX_DIAG_READ_BYTES + " 字节以内";
            }
            return new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取文件失败：{}", resolved, e);
            return "错误：读取文件失败：" + e.getMessage();
        }
    }

    private String runPwd(String command) {
        java.io.File wd = resolveWorkingDirectory(command);
        return "当前工作目录：" + (wd != null ? wd.getAbsolutePath() : System.getProperty("user.dir"));
    }

    private String runEcho(String[] tokens) {
        if (tokens.length < 2) {
            return "echo 无内容";
        }
        return String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
    }

    /**
     * 统一执行 ProcessBuilder 并读取输出，带超时。
     */
    private String runWithTimeout(ProcessBuilder processBuilder, String displayCommand) {
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("命令执行超时（{}秒）：{}", COMMAND_TIMEOUT_SECONDS, displayCommand);
                return "错误：命令执行超时（超过 " + COMMAND_TIMEOUT_SECONDS + " 秒）";
            }
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.exitValue();
            log.info("命令执行完成，退出码={}, 输出长度={}", exitCode, output.length());
            if (exitCode == 0) {
                return output.isEmpty() ? "命令执行成功，无输出" : output;
            }
            return "命令执行失败（退出码=" + exitCode + "）:\n" + output;
        } catch (IOException e) {
            log.error("命令执行失败：{}", displayCommand, e);
            return "命令执行失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("命令执行被中断：{}", displayCommand, e);
            return "命令执行被中断";
        }
    }

    /**
     * 所有技能 scripts 目录的 canonical 路径集合（已存在的才纳入）。
     */
    private List<Path> skillScriptRoots() {
        List<Path> roots = new ArrayList<>();
        String basePath = skillBaseDir();
        if (basePath == null) {
            return roots;
        }
        java.io.File baseDir = new java.io.File(basePath);
        java.io.File[] skillDirs = baseDir.listFiles();
        if (skillDirs == null) {
            return roots;
        }
        for (java.io.File skillDir : skillDirs) {
            if (!skillDir.isDirectory()) {
                continue;
            }
            java.io.File scriptsDir = new java.io.File(skillDir, "scripts");
            if (!scriptsDir.isDirectory()) {
                continue;
            }
            try {
                roots.add(scriptsDir.toPath().toRealPath());
            } catch (IOException e) {
                log.warn("技能脚本目录无法解析 canonical 路径，跳过：{}", scriptsDir.getAbsolutePath());
            }
        }
        return roots;
    }

    /**
     * 将路径 token（相对或绝对）解析到受信任根内；命中则返回 canonical 路径，否则返回 null。
     * 相对路径以工作目录为基准解析，与进程实际 cwd 保持一致。
     */
    private Path resolveWithinRoots(String clean, java.io.File wd) {
        java.io.File raw = new java.io.File(clean);
        java.io.File abs = raw.isAbsolute() ? raw : new java.io.File(wd, clean);
        if (!abs.exists()) {
            return null;
        }
        Path real;
        try {
            real = abs.toPath().toRealPath();
        } catch (IOException e) {
            return null;
        }
        List<Path> roots = diagnosticRoots(wd);
        if (isWithinAny(real, roots)) {
            return real;
        }
        return null;
    }

    /**
     * 只读诊断命令允许的根：cwd + 各技能 scripts + 登记的信任目录。
     */
    private List<Path> diagnosticRoots(java.io.File wd) {
        List<Path> roots = new ArrayList<>();
        try {
            roots.add(wd.toPath().toRealPath());
        } catch (IOException e) {
            roots.add(wd.toPath().toAbsolutePath().normalize());
        }
        roots.addAll(skillScriptRoots());
        if (trustedDirs != null && !trustedDirs.isBlank()) {
            for (String dir : trustedDirs.split(",")) {
                String trimmed = dir.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                java.io.File tf = new java.io.File(trimmed);
                if (!tf.exists()) {
                    continue;
                }
                try {
                    roots.add(tf.toPath().toRealPath());
                } catch (IOException e) {
                    // 跳过无法解析的信任目录
                }
            }
        }
        return roots;
    }

    private boolean isWithinAny(Path real, List<Path> roots) {
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private String unquote(String token) {
        if (token == null) {
            return "";
        }
        String clean = token;
        if (clean.length() >= 2) {
            char first = clean.charAt(0);
            char last = clean.charAt(clean.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                clean = clean.substring(1, clean.length() - 1);
            }
        }
        return clean;
    }

    /**
     * 判断是否为 python 解释器 token（python/python3/*.exe/绝对路径结尾 python）。
     */
    private boolean isPythonExecutable(String token) {
        if (token == null) {
            return false;
        }
        String lower = token.toLowerCase();
        if (lower.equals("python") || lower.equals("python3")) {
            return true;
        }
        if (lower.contains("python.exe") || lower.contains("pythonw.exe")) {
            return true;
        }
        if (lower.matches(".*[/\\\\]python3?[w]?$")) {
            return true;
        }
        return lower.equals(pythonExecPath.toLowerCase());
    }

    /**
     * 替换命令中的 Python 路径为配置的 python-exec-path。
     * 支持 python / python3 开头、Windows 绝对路径（python.exe/pythonw.exe）、Unix 路径。
     * 非 python 命令原样返回。
     */
    String normalizePythonPath(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        String normalized = command;
        if (normalized.startsWith("python ") || normalized.startsWith("python3 ")) {
            String scriptAndArgs = normalized.substring(normalized.indexOf(' ') + 1);
            normalized = pythonExecPath + " " + scriptAndArgs;
            log.debug("替换 Python 命令：{} → {}", command, normalized);
            return normalized;
        }
        if (normalized.matches("^[A-Za-z]:.*python[w]?\\.exe\\s+.*")) {
            int firstSpace = normalized.indexOf(' ');
            if (firstSpace > 0) {
                normalized = pythonExecPath + " " + normalized.substring(firstSpace + 1);
                log.debug("替换 Windows Python 路径：{} → {}", command, normalized);
                return normalized;
            }
        }
        if (normalized.matches("^[/\\\\].*python3?[w]?\\s+.*")) {
            int firstSpace = normalized.indexOf(' ');
            if (firstSpace > 0) {
                normalized = pythonExecPath + " " + normalized.substring(firstSpace + 1);
                log.debug("替换 Unix Python 路径：{} → {}", command, normalized);
                return normalized;
            }
        }
        return command;
    }

    /**
     * 将命令行解析为参数数组（引号感知）。与旧版一致，处理引号、转义与空白切分，
     * 使 `100 * 25` 中的 `*` 原样作为参数传递，绝不被当作元字符/通配符。
     */
    private String[] parseCommand(String command) {
        if (command == null || command.isBlank()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        boolean hasToken = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                    quoteChar = 0;
                } else if (c == '\\' && i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true;
                    quoteChar = c;
                    hasToken = true;
                } else if (c == '\\' && i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                    hasToken = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }
        if (hasToken || inQuote) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * 解析命令的工作目录，采用三段式回退：
     * 1. 命令匹配到技能 → 技能目录
     * 2. 未匹配技能 → 配置的默认工作目录（app.default-work-dir，自动创建）
     * 3. 默认目录不可用 → null
     */
    java.io.File resolveWorkingDirectory(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String skillDir = detectSkillWorkingDirectory(command);
        if (skillDir != null) {
            return new java.io.File(skillDir);
        }
        if (defaultWorkDir != null && !defaultWorkDir.isBlank()) {
            java.io.File defaultDir = new java.io.File(defaultWorkDir);
            if (!defaultDir.exists()) {
                try {
                    java.nio.file.Files.createDirectories(defaultDir.toPath());
                    log.info("默认工作目录不存在，已自动创建：{}", defaultDir.getAbsolutePath());
                } catch (IOException e) {
                    log.error("创建默认工作目录失败：{}", defaultDir.getAbsolutePath(), e);
                }
            }
            if (defaultDir.isDirectory()) {
                return defaultDir;
            }
            log.warn("默认工作目录不可用，回退到父进程工作目录：{}", defaultWorkDir);
        }
        return null;
    }

    /**
     * 从命令文本中检测命中的技能目录（{配置技能根目录名}/{name}/ 写法）。
     */
    private String detectSkillWorkingDirectory(String command) {
        String prefix = skillDirName();
        if (prefix.isEmpty()) {
            return null;
        }
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(prefix) + "/([^/]+)/");
        java.util.regex.Matcher matcher = pattern.matcher(command);
        if (matcher.find()) {
            String skillName = matcher.group(1);
            java.io.File skillDir = new java.io.File(skillBaseDir(), skillName);
            if (skillDir.isDirectory()) {
                return skillDir.getAbsolutePath();
            }
            log.warn("技能目录不存在，忽略技能匹配：{}", skillDir.getAbsolutePath());
        }
        return null;
    }

    /** 技能根目录的绝对路径；未配置 app.skill-base 时返回 null（表示无技能，python 一律拒绝）。 */
    private String skillBaseDir() {
        if (skillBase == null || skillBase.isBlank()) {
            return null;
        }
        return new java.io.File(skillBase).getAbsolutePath();
    }

    /** 技能根目录的目录名，作为命令中技能前缀；未配置时返回空串。 */
    private String skillDirName() {
        String base = skillBaseDir();
        if (base == null) {
            return "";
        }
        return new java.io.File(base).getName();
    }
}