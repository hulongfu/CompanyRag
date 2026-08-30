# 单阶段构建：本地构建 + Docker 打包
# 说明：先在本地执行 mvn clean package -DskipTests，然后 Docker 只负责打包运行
# 企业开发环境推荐方案：利用本地 Maven 缓存，Docker 构建仅需 2-3 分钟

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app

# ============================================
# 安装 Python 和系统依赖
# ============================================
# 安装 Python 解释器、pip 和必要的系统库
# 支持 lxml, python-magic, cryptography, pillow 等需要编译的包
# 添加 gcc 用于编译 numpy 等需要原生扩展的包
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    libxml2-dev \
    libxslt1-dev \
    libmagic1 \
    libssl-dev \
    libjpeg-dev \
    zlib1g-dev \
    gcc \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/python3 /usr/bin/python \
    && python3 --version \
    && pip3 --version

# ============================================
# 创建非 root 用户
# ============================================
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# ============================================
# 复制应用 JAR (本地构建)
# ============================================
COPY company-rag-bootstrap/target/*.jar app.jar

# ============================================
# 复制 agent_skills 目录 (本地)
# ============================================
COPY agent_skills /app/agent_skills

# ============================================
# 复制 .env.docker 到 /app/.env (应用启动时需要)
# ============================================
COPY company-rag-bootstrap/.env.docker /app/.env

# ============================================
# 安装 Python 依赖
# ============================================
# 如果 requirements.txt 存在且非空，则安装依赖
# 使用 --no-cache-dir 减少镜像体积
# 使用 -i 指定 PyPI 镜像源加速下载
# 使用 --break-system-packages 绕过 PEP 668 限制 (Docker 容器中安全)
RUN if [ -s /app/agent_skills/requirements.txt ]; then \
        echo "Installing Python dependencies from requirements.txt..."; \
        pip3 install --no-cache-dir \
            --break-system-packages \
            -i https://pypi.tuna.tsinghua.edu.cn/simple \
            -r /app/agent_skills/requirements.txt; \
    else \
        echo "No requirements.txt found or file is empty, skipping Python dependency installation"; \
    fi

# ============================================
# 创建日志目录并更改文件所有者
# ============================================
RUN mkdir -p /app/logs && \
    chown appuser:appgroup app.jar && \
    chown -R appuser:appgroup /app/agent_skills && \
    chown -R appuser:appgroup /app/logs

# ============================================
# 设置环境变量
# ============================================
# Python 可执行文件路径（支持 ExecuteTool 路径替换）
ENV PYTHON_EXEC_PATH=/usr/bin/python

# 技能路径（Agent 读取技能定义的目录）
ENV SKILLS_PATH=/app/agent_skills

# 激活 Spring 生产环境
ENV SPRING_PROFILES_ACTIVE=prod

# ============================================
# 切换到非 root 用户
# ============================================
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
