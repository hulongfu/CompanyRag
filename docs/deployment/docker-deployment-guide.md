# CompanyRag Docker 部署指南

**最后更新**: 2026-08-30  
**版本**: v1.0

---

## 📋 目录

1. [快速开始](#快速开始)
2. [构建和运行](#构建和运行)
3. [配置说明](#配置说明)
4. [Python 环境](#python-环境)
5. [故障排查](#故障排查)

---

## 🚀 快速开始

### 前置要求

- Docker 20.10+
- Docker Compose 2.0+（可选）
- 至少 4GB 可用内存
- 至少 10GB 可用磁盘空间

### 一键构建和运行

```bash
# 1. 构建镜像
docker build -t company-rag:latest .

# 2. 运行容器
docker run -d \
  --name company-rag \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DASHSCOPE_API_KEY=your_api_key \
  -e PGVECTOR_HOST=your_db_host \
  -e PGVECTOR_PORT=5432 \
  -e PGVECTOR_DATABASE=company_rag \
  -e PGVECTOR_USER=postgres \
  -e PGVECTOR_PASSWORD=your_password \
  company-rag:latest
```

---

## 🛠️ 构建和运行

### 方式一：直接构建

```bash
# 构建镜像
docker build -t company-rag:latest .

# 查看镜像
docker images company-rag

# 运行容器
docker run -d \
  --name company-rag \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  company-rag:latest
```

### 方式二：使用 Docker Compose

创建 `docker-compose.yml`:

```yaml
version: '3.8'

services:
  company-rag:
    build:
      context: .
      dockerfile: Dockerfile
    image: company-rag:latest
    container_name: company-rag
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}
      - PGVECTOR_HOST=${PGVECTOR_HOST}
      - PGVECTOR_PORT=5432
      - PGVECTOR_DATABASE=${PGVECTOR_DATABASE}
      - PGVECTOR_USER=${PGVECTOR_USER}
      - PGVECTOR_PASSWORD=${PGVECTOR_PASSWORD}
    volumes:
      - ./logs:/app/logs
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
```

然后执行：

```bash
# 构建并启动
docker-compose up -d --build

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

---

## ⚙️ 配置说明

### 环境变量

#### 必需的环境变量

| 变量名 | 说明 | 示例 |
|--------|------|------|
| `DASHSCOPE_API_KEY` | 通义千问 API 密钥 | `sk-xxxxxxxx` |
| `PGVECTOR_HOST` | PostgreSQL 数据库地址 | `192.168.1.100` |
| `PGVECTOR_PORT` | PostgreSQL 端口 | `5432` |
| `PGVECTOR_DATABASE` | 数据库名称 | `company_rag` |
| `PGVECTOR_USER` | 数据库用户名 | `postgres` |
| `PGVECTOR_PASSWORD` | 数据库密码 | `your_password` |

#### 可选的环境变量

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Spring 激活的配置文件 |
| `PYTHON_EXEC_PATH` | `/usr/bin/python` | Python 解释器路径 |
| `SKILLS_PATH` | `/app/agent_skills` | 技能定义路径 |
| `SERVER_PORT` | `8080` | 服务端口 |
| `LOG_LEVEL` | `INFO` | 日志级别 |

### 使用 .env 文件

创建 `.env` 文件：

```bash
# .env
DASHSCOPE_API_KEY=sk-xxxxxxxx
PGVECTOR_HOST=192.168.1.100
PGVECTOR_PORT=5432
PGVECTOR_DATABASE=company_rag
PGVECTOR_USER=postgres
PGVECTOR_PASSWORD=your_password
```

然后运行：

```bash
docker run -d \
  --name company-rag \
  -p 8080:8080 \
  --env-file .env \
  company-rag:latest
```

---

## 🐍 Python 环境

### 已安装的 Python 版本

```bash
# 进入容器查看 Python 版本
docker exec -it company-rag python3 --version
# 输出：Python 3.x.x

# 查看 pip 版本
docker exec -it company-rag pip3 --version
```

### 已安装的 Python 依赖

```bash
# 查看已安装的包
docker exec -it company-rag pip3 list

# 查看已安装的包数量
docker exec -it company-rag pip3 list | wc -l
# 应该显示约 77 个包
```

### Python 依赖管理

依赖文件位置：`/app/agent_skills/requirements.txt`

**依赖分类**（共 77 个包）：

1. **HTTP 和网络库**（12 个）：aiohttp, httpx, requests 等
2. **MCP 相关**（7 个）：mcp, fastmcp, dashscope 等
3. **数据验证和配置**（7 个）：pydantic, python-dotenv 等
4. **JSON 和数据格式**（6 个）：orjson, pyyaml, jsonschema 等
5. **文档解析和处理**（13 个）：beautifulsoup4, pypdf, python-docx 等
6. **数据处理和分析**（5 个）：pandas, numpy 等
7. **日志和工具**（8 个）：loguru, rich, tenacity 等
8. **文本处理**（6 个）：emoji, tiktoken, rapidfuzz 等
9. **加密和安全**（3 个）：cryptography, cffi 等
10. **异步和并发**（3 个）：anyio, sniffio 等
11. **其他实用工具**（8 个）：click, psutil, pillow 等

### 添加新的 Python 依赖

1. 编辑 `agent_skills/requirements.txt`，添加新依赖：

```text
# 现有依赖...

# 新增依赖
new-package==1.0.0
```

2. 重新构建镜像：

```bash
docker build -t company-rag:latest .
```

3. 重启容器：

```bash
docker restart company-rag
```

### 技能执行测试

```bash
# 进入容器
docker exec -it company-rag bash

# 测试 Python 脚本
python /app/agent_skills/calculator/scripts/calculator.py "50 + 50"
# 输出：100

# 测试 HTTP 请求
python -c "import requests; print(requests.get('https://httpbin.org/get').status_code)"
# 输出：200
```

---

## 🔍 故障排查

### 容器启动失败

```bash
# 查看容器日志
docker logs company-rag

# 查看容器状态
docker inspect company-rag

# 进入容器调试
docker exec -it company-rag bash
```

### Python 依赖安装失败

```bash
# 查看构建日志
docker build -t company-rag:latest . 2>&1 | tee build.log

# 检查 requirements.txt 格式
docker run --rm -it company-rag:latest cat /app/agent_skills/requirements.txt

# 手动测试 pip 安装
docker run --rm -it company-rag:latest bash
pip3 install -r /app/agent_skills/requirements.txt
```

### 技能执行失败

```bash
# 检查技能文件是否存在
docker exec -it company-rag ls -la /app/agent_skills/

# 检查 Python 路径配置
docker exec -it company-rag echo $PYTHON_EXEC_PATH

# 检查技能权限
docker exec -it company-rag ls -la /app/agent_skills/calculator/scripts/
```

### 内存不足

```bash
# 查看容器资源使用
docker stats company-rag

# 限制容器内存
docker run -d \
  --name company-rag \
  -p 8080:8080 \
  -m 2g \
  --memory-swap 4g \
  company-rag:latest
```

### 网络问题

```bash
# 测试容器内网络
docker exec -it company-rag curl -I https://pypi.tuna.tsinghua.edu.cn

# 测试数据库连接
docker exec -it company-rag bash
nc -zv $PGVECTOR_HOST $PGVECTOR_PORT
```

### 常见错误及解决方案

#### 错误 1: `pip3: command not found`

**原因**: Python 未正确安装  
**解决**: 检查 Dockerfile 中的 apt-get 安装步骤

#### 错误 2: `lxml 编译失败`

**原因**: 缺少 libxml2-dev 或 libxslt1-dev  
**解决**: 确保 Dockerfile 中安装了系统依赖

#### 错误 3: `cryptography 导入失败`

**原因**: 缺少 libssl-dev  
**解决**: 确保 Dockerfile 中安装了 libssl-dev

#### 错误 4: `PIL 无法导入`

**原因**: 缺少 libjpeg-dev 或 zlib1g-dev  
**解决**: 确保 Dockerfile 中安装了图像库依赖

---

## 📊 镜像信息

### 镜像大小

```bash
# 查看镜像大小
docker images company-rag

# 预期大小：约 600MB - 800MB
# - 基础镜像 (eclipse-temurin:17-jre): ~200MB
# - Python 和系统依赖：~150MB
# - Python 包（77 个）：~200-400MB
# - 应用 JAR: ~50-100MB
```

### 镜像层分析

```bash
# 安装 docker-slim 分析镜像
docker-slim build company-rag:latest --dockerfile Dockerfile

# 查看镜像历史
docker history company-rag:latest
```

---

## 🔒 安全建议

### 1. 使用非 root 用户

Dockerfile 已配置 `appuser` 用户运行应用，不要修改为 root 用户。

### 2. 限制容器权限

```bash
docker run -d \
  --name company-rag \
  --read-only \
  --tmpfs /tmp \
  --cap-drop=ALL \
  --cap-add=NET_BIND_SERVICE \
  company-rag:latest
```

### 3. 使用 Docker Secret 管理敏感信息

```bash
# 创建 secret
echo "your_password" | docker secret create pg_password -

# 在 docker-compose.yml 中使用
services:
  company-rag:
    secrets:
      - pg_password
```

### 4. 定期更新镜像

```bash
# 重新构建最新镜像
docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jre
docker build -t company-rag:latest .

# 删除旧镜像
docker rmi company-rag:old
```

---

## 📈 性能优化

### 1. 使用多阶段构建（已实现）

Dockerfile 使用多阶段构建，减少最终镜像大小。

### 2. 使用构建缓存

```bash
# 利用 Docker 层缓存
docker build -t company-rag:latest .

# 使用构建缓存加速
DOCKER_BUILDKIT=1 docker build -t company-rag:latest .
```

### 3. 减少镜像层数

Dockerfile 已优化为最少的 RUN 指令，减少镜像层数。

### 4. 使用 .dockerignore

创建 `.dockerignore` 文件：

```text
# Git
.git
.gitignore

# IDE
.idea
.vscode
*.iml

# Maven
target/
*.log

# Node
node_modules/

# Python
__pycache__/
*.pyc
.pytest_cache/

# 文档
docs/
*.md

# 其他
Dockerfile
docker-compose.yml
```

---

## 📝 更新日志

### v1.0 (2026-08-30)

- ✅ 添加 Python 3 解释器支持
- ✅ 安装 77 个精简的 Python 依赖包
- ✅ 配置系统依赖支持 lxml, cryptography, pillow 等
- ✅ 使用清华 PyPI 镜像加速下载
- ✅ 配置 PYTHON_EXEC_PATH 环境变量
- ✅ 优化镜像体积（从 314 个包减少到 77 个）
- ✅ 添加完整的部署文档

---

## 📞 技术支持

如有问题，请查看：

1. [Python 依赖精简报告](./python-requirements-analysis.md)
2. [项目 README](../README.md)
3. [Spring Boot 配置](../company-rag-bootstrap/src/main/resources/application.yml)
