# Python 可执行文件路径配置化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Python 可执行文件路径配置化，支持多环境部署，同时保持向后兼容性。

**Architecture:** 在 `application.yml` 中添加 `agent.python-exec-path` 配置项，`ExecuteTool` 通过 `@Value` 注入配置路径，并在执行命令前调用 `normalizePythonPath()` 方法将所有 Python 命令统一替换为配置路径。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring AI 1.0, Maven

---

## Task 1: 添加配置项到 application.yml

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 中添加 agent 配置块**

在 `application.yml` 文件的第 178 行（logging 配置之前）添加：

```yaml
# Agent 配置
agent:
  # Python 可执行文件路径（支持多环境配置）
  # 开发环境：D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe
  # 生产环境：/usr/bin/python3 或 /opt/venv/bin/python
  python-exec-path: ${PYTHON_EXEC_PATH:D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe}
```

- [ ] **Step 2: 验证 YAML 格式**

运行：检查 YAML 缩进是否正确（2 空格）

Expected: 无语法错误

- [ ] **Step 3: 提交**

```bash
git add company-rag-bootstrap/src/main/resources/application.yml
git commit -m "feat: 添加 Python 可执行文件路径配置项"
```

---

## Task 2: 在 ExecuteTool 中注入配置路径

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`

- [ ] **Step 1: 添加 @Value 注解导入**

在文件开头添加导入（如果不存在）：

```java
import org.springframework.beans.factory.annotation.Value;
```

- [ ] **Step 2: 添加 pythonExecPath 字段**

在 `ExecuteTool` 类的第 28 行（`COMMAND_TIMEOUT_SECONDS` 常量之后）添加：

```java
    // 命令超时时间（秒）
    // web-search 等网络请求需要更长时间，设置为 60 秒
    private static final int COMMAND_TIMEOUT_SECONDS = 60;

    // Python 可执行文件路径（从配置文件注入）
    @Value("${agent.python-exec-path:D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe}")
    private String pythonExecPath;
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java
git commit -m "feat: 注入 Python 可执行文件路径配置"
```

---

## Task 3: 实现 normalizePythonPath 方法

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`

- [ ] **Step 1: 添加 normalizePythonPath 方法**

在 `isPythonCommand()` 方法之后（第 374 行之后）添加：

```java
    /**
     * 替换命令中的 Python 路径为配置路径
     * 统一将所有 Python 命令替换为配置的 python-exec-path
     * 
     * 检测模式：
     * 1. python 开头（系统 PATH 中的 Python）
     * 2. python3 开头（系统 PATH 中的 Python3）
     * 3. Windows 绝对路径：D:/**/**/python.exe 或 D:/**/**/pythonw.exe
     * 4. Unix 绝对路径：/usr/**/python 或 /**/venv/bin/python
     * 
     * @param command 原始命令
     * @return 替换后的命令
     */
    private String normalizePythonPath(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        
        String normalized = command;
        
        // 模式 1: python 或 python3 开头（后面跟空格）
        if (normalized.startsWith("python ") || normalized.startsWith("python3 ")) {
            // 移除 python/python3 及其后的空格，保留脚本路径和参数
            String scriptAndArgs = normalized.substring(normalized.indexOf(' ') + 1);
            normalized = pythonExecPath + " " + scriptAndArgs;
            log.debug("替换 Python 命令：{} → {}", command, normalized);
            return normalized;
        }
        
        // 模式 2: Windows Python 可执行文件路径（包含 python.exe 或 pythonw.exe）
        // 匹配 D:/path/to/python.exe 或 D:\path\to\python.exe
        if (normalized.matches("^[A-Za-z]:.*python[w]?\\.exe\\s+.*")) {
            // 提取脚本路径和参数
            int firstSpace = normalized.indexOf(' ');
            if (firstSpace > 0) {
                String scriptAndArgs = normalized.substring(firstSpace + 1);
                normalized = pythonExecPath + " " + scriptAndArgs;
                log.debug("替换 Windows Python 路径：{} → {}", command, normalized);
                return normalized;
            }
        }
        
        // 模式 3: Unix Python 路径（以 /python 或 /python3 结尾的路径）
        // 例如：/usr/bin/python3, /home/user/.venv/bin/python
        if (normalized.matches("^[/\\\\].*python3?[w]?\\s+.*")) {
            // 提取脚本路径和参数
            int firstSpace = normalized.indexOf(' ');
            if (firstSpace > 0) {
                String scriptAndArgs = normalized.substring(firstSpace + 1);
                normalized = pythonExecPath + " " + scriptAndArgs;
                log.debug("替换 Unix Python 路径：{} → {}", command, normalized);
                return normalized;
            }
        }
        
        // 不匹配任何模式，保持原样
        return command;
    }
```

- [ ] **Step 2: 提交**

```bash
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java
git commit -m "feat: 实现 Python 路径归一化方法"
```

---

## Task 4: 在 executeCommand 中调用 normalizePythonPath

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`

- [ ] **Step 1: 修改 executeCommand 方法**

在 `executeCommand()` 方法的第 98-100 行，修改为：

```java
        try {
            // 路径归一化：将所有 Python 命令替换为配置路径
            String normalizedCommand = normalizePythonPath(command);
            log.debug("归一化后的命令：{}", normalizedCommand);
            
            // 命令预处理：将 Windows cmd 语法转换为 Unix Shell 语法
            String processedCommand = preprocessCommand(normalizedCommand);
            log.debug("预处理后的命令：{}", processedCommand);
```

注意：将原来的 `preprocessCommand(command)` 改为 `preprocessCommand(normalizedCommand)`

- [ ] **Step 2: 提交**

```bash
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java
git commit -m "feat: 在命令执行前调用路径归一化"
```

---

## Task 5: 编写单元测试

**Files:**
- Create: `company-rag-agent/src/test/java/com/company/rag/agent/tool/ExecuteToolTest.java`

- [ ] **Step 1: 创建测试类**

创建文件 `company-rag-agent/src/test/java/com/company/rag/agent/tool/ExecuteToolTest.java`：

```java
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
        // 测试系统 PATH 中的 python
        String command = "python scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_SystemPython3() {
        // 测试系统 PATH 中的 python3
        String command = "python3 scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_WindowsAbsolutePath() {
        // 测试 Windows 绝对路径
        String command = "D:/old/path/python.exe scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_WindowsPythonw() {
        // 测试 pythonw.exe
        String command = "D:/old/path/pythonw.exe scripts/script.py";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/script.py", result);
    }

    @Test
    void testNormalizePythonPath_UnixAbsolutePath() {
        // 测试 Unix 绝对路径
        String command = "/usr/bin/python3 scripts/calculator.py 50 + 50";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/calculator.py 50 + 50", result);
    }

    @Test
    void testNormalizePythonPath_VenvPath() {
        // 测试虚拟环境路径
        String command = "/home/user/.venv/bin/python scripts/script.py";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("D:/test/venv/Scripts/python.exe scripts/script.py", result);
    }

    @Test
    void testNormalizePythonPath_NonPythonCommand() {
        // 测试非 Python 命令（保持不变）
        String command = "mkdir test_folder";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("mkdir test_folder", result);
    }

    @Test
    void testNormalizePythonPath_EmptyCommand() {
        // 测试空命令
        String command = "";
        String result = executeTool.normalizePythonPath(command);
        assertEquals("", result);
    }

    @Test
    void testNormalizePythonPath_NullCommand() {
        // 测试 null 命令
        String result = executeTool.normalizePythonPath(null);
        assertNull(result);
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd company-rag-agent
mvn test -Dtest=ExecuteToolTest
```

Expected: 所有测试通过

- [ ] **Step 3: 提交**

```bash
git add company-rag-agent/src/test/java/com/company/rag/agent/tool/ExecuteToolTest.java
git commit -m "test: 添加 ExecuteTool 路径替换单元测试"
```

---

## Task 6: 编写集成测试

**Files:**
- Modify: `company-rag-agent/src/test/java/com/company/rag/agent/tool/ExecuteToolTest.java`

- [ ] **Step 1: 添加集成测试方法**

在测试类中添加：

```java
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
```

- [ ] **Step 2: 运行测试**

```bash
cd company-rag-agent
mvn test -Dtest=ExecuteToolTest#testExecuteCommand_CalculatorSkill
```

Expected: 测试通过

- [ ] **Step 3: 提交**

```bash
git add company-rag-agent/src/test/java/com/company/rag/agent/tool/ExecuteToolTest.java
git commit -m "test: 添加集成测试用例"
```

---

## Task 7: 验证编译和运行

**Files:**
- 无

- [ ] **Step 1: 编译项目**

```bash
mvn clean compile -pl company-rag-agent
```

Expected: 编译成功，无错误

- [ ] **Step 2: 运行所有测试**

```bash
mvn test -pl company-rag-agent
```

Expected: 所有测试通过

- [ ] **Step 3: 提交**

```bash
git commit -am "chore: 验证编译和测试通过"
```

---

## Task 8: 更新应用文档

**Files:**
- Create: `docs/deployment/python-exec-config.md`

- [ ] **Step 1: 创建部署文档**

创建文件 `docs/deployment/python-exec-config.md`：

```markdown
# Python 可执行文件路径配置

## 概述

Python 可执行文件路径通过配置文件和环境变量进行管理，支持多环境部署。

## 配置方式

### 方式 1：配置文件（推荐）

在 `application.yml` 中配置：

```yaml
agent:
  python-exec-path: D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe
```

### 方式 2：环境变量

```bash
export PYTHON_EXEC_PATH=/opt/venv/bin/python
```

环境变量优先级高于配置文件。

## 环境示例

### 开发环境（Windows）

```yaml
# application-dev.yml
agent:
  python-exec-path: D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe
```

### 生产环境（Linux）

```yaml
# application-prod.yml
agent:
  python-exec-path: /opt/venv/bin/python
```

### Docker 部署

```dockerfile
ENV PYTHON_EXEC_PATH=/usr/local/bin/python
```

## 验证配置

启动应用后，检查日志输出：

```
DEBUG ExecuteTool - 归一化后的命令：D:/test/venv/Scripts/python.exe scripts/calculator.py
```

## 故障排查

### 问题：技能执行失败，提示找不到 Python

**原因：** 配置的 Python 路径不存在

**解决：**
1. 检查 Python 路径是否正确
2. 确认 Python 可执行文件存在
3. 检查文件权限

### 问题：配置未生效

**原因：** 环境变量覆盖了配置文件

**解决：**
1. 检查环境变量 `PYTHON_EXEC_PATH`
2. 使用 `printenv PYTHON_EXEC_PATH` 查看值
3. 清除环境变量或修改配置文件
```

- [ ] **Step 2: 提交**

```bash
git add docs/deployment/python-exec-config.md
git commit -m "docs: 添加 Python 路径配置部署文档"
```

---

## 自审清单

### 1. Spec 覆盖检查

- ✅ 配置项添加到 `application.yml`
- ✅ `ExecuteTool` 注入配置路径
- ✅ 实现 `normalizePythonPath()` 方法
- ✅ 在 `executeCommand()` 中调用
- ✅ 单元测试覆盖所有场景
- ✅ 集成测试验证功能
- ✅ 部署文档

### 2. Placeholder 检查

- ✅ 无 "TBD"、"TODO"
- ✅ 所有步骤都有具体代码
- ✅ 所有命令都有预期输出

### 3. 类型一致性检查

- ✅ 方法名：`normalizePythonPath()`
- ✅ 字段名：`pythonExecPath`
- ✅ 配置项：`agent.python-exec-path`

---

**计划完成！**

两个执行选项：

1. **Subagent-Driven（推荐）** - 每个任务分发一个子代理，任务间审查，快速迭代
2. **Inline Execution** - 使用 executing-plans 在此会话中执行任务，批量执行带检查点

选择哪种方式？
