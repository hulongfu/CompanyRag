package com.company.rag.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecuteTool 单元测试
 */
class ExecuteToolTest {

    private ExecuteTool executeTool;

    @BeforeEach
    void setUp() {
        executeTool = new ExecuteTool();
        // 设置测试用的 Python 路径
        ReflectionTestUtils.setField(executeTool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
    }

    @Test
    void testNormalizePythonPath_SystemPython() {
        String command = "python scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_SystemPython3() {
        String command = "python3 scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_WindowsAbsolutePath() {
        String command = "D:/old/path/python.exe scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_WindowsPythonw() {
        String command = "D:/old/path/pythonw.exe scripts/script.py";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/script.py", result);
    }

    @Test
    void testNormalizePythonPath_UnixAbsolutePath() {
        String command = "/usr/bin/python3 scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_VenvPath() {
        String command = "/home/user/.venv/bin/python scripts/script.py";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/script.py", result);
    }

    @Test
    void testNormalizePythonPath_NonPythonCommand() {
        String command = "mkdir test_folder";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("mkdir test_folder", result);
    }

    @Test
    void testNormalizePythonPath_EmptyCommand() {
        String command = "";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("", result);
    }

    @Test
    void testNormalizePythonPath_NullCommand() {
        String result = executeTool.normalizePythonPath(null);
        assertNull(result);
    }

    @Test
    void testExecuteCommand_CalculatorSkill() {
        // 测试执行 calculator 技能（实际执行需要 Python 环境）
        // 这里只测试命令格式是否正确
        String command = "python scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        
        // 验证命令被正确替换
        assertTrue(result.startsWith("D:/test/venv/Scripts/python.exe"));
        assertTrue(result.contains("scripts/calculator.py"));
        assertTrue(result.contains("50 + 50"));
    }
}
