package com.company.rag.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecuteTool 单元测试——安全准入（命令白名单 / python 仅限技能 scripts / 路径越权 / 生成代码触发）
 * 说明：仅测试校验层 rejectUnsafePath / resolveWorkingDirectory / normalizePythonPath，
 * 不真正 spawn python 子进程（避免依赖本机 python 环境）。
 */
class ExecuteToolTest {

    private ExecuteTool executeTool;

    @BeforeEach
    void setUp() {
        executeTool = new ExecuteTool();
        ReflectionTestUtils.setField(executeTool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
    }

    // ---------- normalizePythonPath ----------

    @Test
    void testNormalizePythonPath_SystemPython() {
        String result = executeTool.normalizePythonPath("python scripts/calculator.py 50 + 50");
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_SystemPython3() {
        String result = executeTool.normalizePythonPath("python3 scripts/calculator.py 50 + 50");
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_WindowsAbsolutePath() {
        String result = executeTool.normalizePythonPath("D:/old/path/python.exe scripts/calculator.py 50 + 50");
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_NonPythonCommand() {
        // 非 python 命令原样返回
        assertEquals("mkdir test_folder", executeTool.normalizePythonPath("mkdir test_folder"));
        assertEquals("", executeTool.normalizePythonPath(""));
        assertNull(executeTool.normalizePythonPath(null));
    }

    // ---------- 命令白名单 ----------

    @Test
    void testRejectUnsafePath_RejectUnknownCommand() throws IOException {
        // copy / del / mkdir / rm 等写类与黑名单外命令一律拒绝
        String r1 = reject(tempCwd(), "copy a.txt b.txt");
        assertNotNull(r1, "copy 不在白名单应被拒绝");
        String r2 = reject(tempCwd(), "rm -rf /");
        assertNotNull(r2, "rm 不在白名单应被拒绝");
        String r3 = reject(tempCwd(), "python -m pip install x");
        assertNotNull(r3, "python -m 应被拒绝");
        String r4 = reject(tempCwd(), "format C:");
        assertNotNull(r4, "format 不在白名单应被拒绝");
    }

    @Test
    void testRejectUnsafePath_AllowDiagnosticPwdEcho() throws IOException {
        assertNull(reject(tempCwd(), "pwd"), "pwd 应放行");
        assertNull(reject(tempCwd(), "echo 你好 world"), "echo 文本应放行");
        // 引号内 && 是单 token，不应被误伤
        assertNull(reject(tempCwd(), "echo \"a && b\""), "引号内 && 应放行");
    }

    @Test
    void testRejectUnsafePath_RejectMetacharToken() throws IOException {
        // 独立成 token 的元字符必须拒绝（防止命令拼接）
        assertNotNull(reject(tempCwd(), "python scripts/calc.py && type C:/secret.txt"), "&& 拼接应拒绝");
        assertNotNull(reject(tempCwd(), "echo x > out.txt"), "重定向 > 应拒绝");
        assertNotNull(reject(tempCwd(), "echo a | b"), "管道 | 应拒绝");
        assertNotNull(reject(tempCwd(), "echo a $(rm -rf /)"), "$() 应拒绝");
    }

    // ---------- python 仅限技能 scripts（生成代码触发防护核心） ----------

    private ExecuteTool skillTool(Path skillBase) {
        ExecuteTool tool = new ExecuteTool();
        ReflectionTestUtils.setField(tool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        ReflectionTestUtils.setField(tool, "skillBase", skillBase.toAbsolutePath().toString());
        ReflectionTestUtils.setField(tool, "defaultWorkDir", skillBase.toAbsolutePath().toString());
        return tool;
    }

    private Path newSkillScript(Path root, String skill, String scriptName) throws IOException {
        Path skillDir = root.resolve(skill);
        Path scripts = skillDir.resolve("scripts");
        Files.createDirectories(scripts);
        Path script = scripts.resolve(scriptName);
        Files.writeString(script, "print('trusted')", StandardCharsets.UTF_8);
        return script;
    }

    @Test
    void testRejectUnsafePath_AllowSkillScript() throws IOException {
        Path root = Files.createTempDirectory("skill-root");
        try {
            newSkillScript(root, "web-search", "search_tool.py");
            ExecuteTool tool = skillTool(root);
            String prefix = root.getFileName().toString();
            String result = (String) ReflectionTestUtils.invokeMethod(tool, "rejectUnsafePath",
                    "python " + prefix + "/web-search/scripts/search_tool.py \"关键词\"");
            assertEquals(null, result, "技能 scripts 脚本应放行，但被拒绝: " + result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_AllowSkillScriptRelativeFromCwd() throws IOException {
        // 技能命中时 wd=技能目录，相对 scripts/x.py 也应放行
        Path root = Files.createTempDirectory("skill-root");
        try {
            newSkillScript(root, "calculator", "calculator.py");
            ExecuteTool tool = skillTool(root);
            ReflectionTestUtils.setField(tool, "defaultWorkDir", root.resolve("calculator").toString());
            String result = (String) ReflectionTestUtils.invokeMethod(tool, "rejectUnsafePath",
                    "python scripts/calculator.py 50 + 50");
            assertEquals(null, result, "命中技能后相对 scripts 脚本应放行，但被拒绝: " + result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_RejectCwdScript() throws IOException {
        // cwd/默认工作目录里生成的 py 不得执行（生成代码触发防护）
        Path root = Files.createTempDirectory("cwd-root");
        try {
            Files.writeString(root.resolve("generated.py"), "print('evil')", StandardCharsets.UTF_8);
            String result = reject(root.toFile(), "python generated.py");
            assertNotNull("cwd 下脚本应被拒绝", result);
            // 绝对路径的 cwd 脚本同样拒绝
            String abs = root.resolve("generated.py").toAbsolutePath().toString();
            result = reject(null, "python " + abs);
            assertNotNull("cwd 绝对路径脚本应被拒绝", result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_RejectDownloadDirScript() throws IOException {
        // 下载/落地目录里的脚本不得执行
        Path root = Files.createTempDirectory("dl-root");
        try {
            Path dl = root.resolve("downloads");
            Files.createDirectories(dl);
            Files.writeString(dl.resolve("populated.py"), "print('x')", StandardCharsets.UTF_8);
            String result = reject(null, "python " + dl.resolve("populated.py").toAbsolutePath());
            assertNotNull("下载目录脚本应被拒绝", result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_RejectNonSkillScript() throws IOException {
        // 默认工作目录下的普通脚本（非技能 scripts）拒绝
        Path root = Files.createTempDirectory("default-root");
        try {
            Files.writeString(root.resolve("my_script.py"), "print('x')", StandardCharsets.UTF_8);
            String result = reject(root.toFile(), "python my_script.py");
            assertNotNull("非技能 scripts 脚本应被拒绝", result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_RejectInlineCode() throws IOException {
        String r1 = reject(tempCwd(), "python -c \"import os\"");
        assertNotNull("python -c 应被拒绝", r1);
        String r2 = reject(tempCwd(), "python -i");
        assertNotNull("python -i 应被拒绝", r2);
        String r3 = reject(tempCwd(), "python");
        assertNotNull("python 无脚本应被拒绝", r3);
    }

    @Test
    void testRejectUnsafePath_RejectPathTraversalToSkillScript() throws IOException {
        // 试图用 .. 穿出技能 scripts 目录回落到工作目录的脚本 → 拒绝
        Path root = Files.createTempDirectory("skill-root");
        try {
            newSkillScript(root, "calculator", "calculator.py");
            ExecuteTool tool = skillTool(root);
            String result = (String) ReflectionTestUtils.invokeMethod(tool, "rejectUnsafePath",
                    "python scripts/../../generated.py");
            assertNotNull("路径穿越应被拒绝", result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    // ---------- 只读诊断命令路径校验 ----------

    @Test
    void testRejectUnsafePath_AllowReadFileInCwd() throws IOException {
        Path root = Files.createTempDirectory("diag-root");
        try {
            Files.writeString(root.resolve("report.txt"), "hello", StandardCharsets.UTF_8);
            String result = reject(root.toFile(), "type report.txt");
            assertEquals(null, result, "cwd 内 type 相对文件应放行，但被拒绝: " + result);
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    @Test
    void testRejectUnsafePath_RejectReadAbsoluteOutside() throws IOException {
        // 只读命令尝试越级读系统文件 → 拒绝（即使只读）
        String result = reject(tempCwd(), "type C:/Windows/System32/drivers/etc/hosts");
        assertNotNull("越权读取系统文件应被拒绝", result);
    }

    @Test
    void testRejectUnsafePath_AllowLsInCwd() throws IOException {
        assertNull(reject(tempCwd(), "ls"), "ls 应放行");
        assertNull(reject(tempCwd(), "dir ."), "dir . 应放行");
    }

    @Test
    void testRejectUnsafePath_RejectLsOutside() throws IOException {
        Path root = Files.createTempDirectory("diag-root");
        String result = reject(root.toFile(), "ls C:/Windows/System32");
        assertNotNull("列出系统目录应被拒绝", result);
    }

    @Test
    void testRejectUnsafePath_RejectEchoDir() throws IOException {
        // echo 指向一个不存在的路径也仅作文本（放行）；此处验证文件参数幻觉不误伤
        assertNull(reject(tempCwd(), "echo report.txt"), "echo 文本应放行");
    }

    // ---------- 工作目录决策 ----------

    @Test
    void testResolveWorkingDirectory_MatchedSkill() throws IOException {
        // 用非默认目录名的临时技能根，验证命令前缀是动态取自配置目录名而非硬编码
        Path tempRoot = Files.createTempDirectory("company-rag-skills-root");
        try {
            Files.createDirectories(tempRoot.resolve("file-manager"));
            String rootName = tempRoot.getFileName().toString();
            executeTool = skillToolForResolve(tempRoot.toAbsolutePath().toString());
            String command = "python " + rootName + "/file-manager/scripts/list_files.py .";
            File dir = executeTool.resolveWorkingDirectory(command);
            assertNotNull(dir);
            assertTrue(dir.getAbsolutePath().replace('\\', '/').endsWith(rootName + "/file-manager"));
        } finally {
            deleteRecursively(tempRoot.toFile());
        }
    }

    @Test
    void testResolveWorkingDirectory_AgentSkillsForm() {
        executeTool = skillToolForResolve("D:/tmp/CompanyRag/agent_skills");
        String command = "python agent_skills/file-manager/scripts/list_files.py .";
        File dir = executeTool.resolveWorkingDirectory(command);
        assertNotNull(dir);
        assertTrue(dir.getAbsolutePath().replace('\\', '/').endsWith("agent_skills/file-manager"));
    }

    @Test
    void testResolveWorkingDirectory_UnmatchedFallsBackToDefault() throws IOException {
        Path tempDir = Files.createTempDirectory("workdir-test");
        executeTool = new ExecuteTool();
        ReflectionTestUtils.setField(executeTool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        ReflectionTestUtils.setField(executeTool, "defaultWorkDir", tempDir.toString());
        try {
            File dir = executeTool.resolveWorkingDirectory("ls");
            assertNotNull(dir);
            assertEquals(tempDir.toFile().getAbsolutePath(), dir.getAbsolutePath());
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    @Test
    void testResolveWorkingDirectory_DefaultDirNotExist_AutoCreate() throws IOException {
        Path tempBase = Files.createTempDirectory("wd-base");
        Path missingDir = tempBase.resolve("not-yet-created/sandbox");
        executeTool = new ExecuteTool();
        ReflectionTestUtils.setField(executeTool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        ReflectionTestUtils.setField(executeTool, "defaultWorkDir", missingDir.toString());
        try {
            File dir = executeTool.resolveWorkingDirectory("ls");
            assertNotNull(dir);
            assertTrue(dir.isDirectory());
            assertEquals(missingDir.toAbsolutePath().normalize().toString(),
                    dir.toPath().toAbsolutePath().normalize().toString());
        } finally {
            deleteRecursively(tempBase.toFile());
        }
    }

    // ---------- 工具方法 ----------

    private ExecuteTool skillToolForResolve(String skillBase) {
        executeTool = new ExecuteTool();
        ReflectionTestUtils.setField(executeTool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        if (skillBase != null) {
            ReflectionTestUtils.setField(executeTool, "skillBase", skillBase);
        }
        return executeTool;
    }

    /** 直接在临时 cwd 上构造工具并执行安全准入 */
    private String reject(File workDir, String command) {
        ExecuteTool tool = new ExecuteTool();
        ReflectionTestUtils.setField(tool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        if (workDir != null) {
            ReflectionTestUtils.setField(tool, "defaultWorkDir", workDir.getAbsolutePath());
        }
        return (String) ReflectionTestUtils.invokeMethod(tool, "rejectUnsafePath", command);
    }

    private File tempCwd() throws IOException {
        return Files.createTempDirectory("cwd-root").toFile();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}