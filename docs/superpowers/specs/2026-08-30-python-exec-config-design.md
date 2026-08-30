# Python 可执行文件路径配置化设计

## 1. 概述

**问题：** 当前 `ExecuteTool.java` 中硬编码了 Python 可执行文件路径（`python.exe`、`pythonw.exe`），技能文档中也硬编码了具体路径 `D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe`。部署到其他环境时，Python 路径可能不同，导致技能无法执行。

**目标：** 将 Python 可执行文件路径配置化，支持多环境部署，同时保持向后兼容性。

## 2. 架构设计

### 2.1 配置层
在 `application.yml` 中添加 `agent.python-exec-path` 配置项，支持环境变量覆盖。

### 2.2 执行层
`ExecuteTool` 注入配置路径，执行命令时进行路径替换：
- 检测命令中的 Python 解释器（`python`、`python3`、具体路径）
- 统一替换为配置的 `python-exec-path`
- 保持脚本路径和参数不变

### 2.3 兼容性
- 向后兼容：现有技能文档无需修改
- 默认值：配置缺省时使用当前硬编码路径
- 环境变量：支持通过 `PYTHON_EXEC_PATH` 环境变量覆盖

## 3. 组件设计

### 3.1 配置项
```yaml
# application.yml
agent:
  # Python 可执行文件路径（支持多环境配置）
  # 开发环境：D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe
  # 生产环境：/usr/bin/python3 或 /opt/venv/bin/python
  python-exec-path: ${PYTHON_EXEC_PATH:D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe}
```

### 3.2 ExecuteTool 修改
**新增字段：**
```java
@Value("${agent.python-exec-path:D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe}")
private String pythonExecPath;
```

**新增方法：**
```java
/**
 * 替换命令中的 Python 路径为配置路径
 * 支持检测常见的硬编码路径模式
 */
private String normalizePythonPath(String command)
```

**修改方法：**
- `executeCommand()`: 在执行前调用 `normalizePythonPath()` 处理命令

### 3.3 路径替换逻辑
**检测模式：**
1. `python ` 开头（系统 PATH 中的 Python）
2. `python3 ` 开头（系统 PATH 中的 Python3）
3. Windows 绝对路径：`D:/**/**/python.exe` 或 `D:/**/**/pythonw.exe`
4. Unix 绝对路径：`/usr/**/python` 或 `/**/venv/bin/python`

**替换策略：**
- 统一替换：所有 Python 命令开头都替换为配置的 `python-exec-path`
- 保持参数：只替换 Python 解释器路径，脚本路径和参数保持不变
- 示例：
  - `python scripts/calc.py 1 + 2` → `{config.path} scripts/calc.py 1 + 2`
  - `python3 /app/script.py` → `{config.path} /app/script.py`
  - `D:/old/path/python.exe script.py` → `{config.path} script.py`

## 4. 数据流

```
用户请求 → Agent → ExecuteTool.executeCommand()
                              ↓
                    normalizePythonPath(command)
                              ↓
                    检测并替换 Python 路径
                              ↓
                    isCommandSafe(processedCommand)
                              ↓
                    ProcessBuilder.start()
```

## 5. 错误处理

### 5.1 配置缺失
- 使用 `@Value` 的默认值机制
- 默认值为当前开发环境路径

### 5.2 路径无效
- 执行时由 `ProcessBuilder` 抛出 `IOException`
- 现有错误处理逻辑已覆盖

### 5.3 跨平台兼容性
- Windows：支持 `.exe` 和 `.bat` 扩展名
- Linux/macOS：支持无扩展名和 shebang 脚本

## 6. 测试策略

### 6.1 单元测试
- `ExecuteTool.normalizePythonPath()` 路径替换逻辑
- 测试 Windows 路径、Unix 路径、系统 PATH 三种场景

### 6.2 集成测试
- 执行 calculator 技能（使用配置路径）
- 执行 file-manager 技能（使用系统命令）

### 6.3 环境测试
- 开发环境：Windows + 虚拟环境
- 生产环境：Linux + 系统 Python（模拟）

## 7. 部署说明

### 7.1 开发环境
无需修改，使用默认配置。

### 7.2 生产环境
**方式 1：环境变量**
```bash
export PYTHON_EXEC_PATH=/opt/venv/bin/python
```

**方式 2：配置文件**
```yaml
# application-prod.yml
agent:
  python-exec-path: /opt/venv/bin/python
```

### 7.3 Docker 部署
```dockerfile
ENV PYTHON_EXEC_PATH=/usr/local/bin/python
```

## 8. 影响范围

### 8.1 修改文件
- `company-rag-bootstrap/src/main/resources/application.yml` - 新增配置项
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java` - 实现路径替换

### 8.2 不变文件
- 所有技能文档（`agent_skills/**/*.md`）- 向后兼容
- 其他工具类 - 无影响

## 9. 成功标准

1. ✅ 配置项可在 `application.yml` 中定义
2. ✅ 环境变量可覆盖配置
3. ✅ 执行技能时自动使用配置路径
4. ✅ 现有技能文档无需修改
5. ✅ 跨平台部署正常工作

## 10. 后续优化（可选）

- 支持多 Python 路径配置（不同技能使用不同解释器）
- 支持 Python 版本检测
- 支持虚拟环境自动激活

---

**版本：** 1.0  
**日期：** 2026-08-30  
**作者：** Agent  
**状态：** 已完成
