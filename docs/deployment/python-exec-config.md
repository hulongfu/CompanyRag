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
