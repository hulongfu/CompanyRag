# CompanyRag 生产环境部署检查清单

**文档版本：** 1.0  
**更新日期：** 2026-08-14  
**适用场景：** 从开发环境部署到生产服务器

---

## 📋 部署前准备（Pre-Deployment）

### 1. 服务器准备

#### 1.1 服务器要求
- [ ] **操作系统：** Linux（Ubuntu 20.04+ / CentOS 7+）
- [ ] **CPU：** 4 核以上
- [ ] **内存：** 8GB 以上（建议 16GB）
- [ ] **磁盘：** 50GB 以上 SSD
- [ ] **网络：** 可访问外网（LLM API、Maven 仓库）

#### 1.2 软件要求
- [ ] **Docker：** 20.10+
- [ ] **Docker Compose：** 2.0+
- [ ] **JDK：** 17+（如需本地编译）
- [ ] **Maven：** 3.6+（如需本地编译）

#### 1.3 检查命令
```bash
# 检查 Docker
docker --version
docker compose version

# 检查 JDK
java -version

# 检查 Maven
mvn --version

# 检查磁盘空间
df -h

# 检查内存
free -h
```

---

### 2. 密码与密钥生成

#### 2.1 生成强随机密码

**在目标生产服务器上执行：**

```bash
#!/bin/bash
# 创建密码生成脚本
cat > /tmp/generate-passwords.sh << 'SCRIPT'
#!/bin/bash

echo "=========================================="
echo "CompanyRag 生产环境密码生成"
echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="
echo ""

# 生成 JWT_SECRET（32 字节 Base64）
JWT_SECRET=$(openssl rand -base64 32)
echo "JWT_SECRET=$JWT_SECRET"

# 生成 PostgreSQL 密码（24 字节 Base64，约 32 字符）
POSTGRES_PASSWORD=$(openssl rand -base64 24)
echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD"

# 生成 Redis 密码（24 字节 Base64）
REDIS_PASSWORD=$(openssl rand -base64 24)
echo "REDIS_PASSWORD=$REDIS_PASSWORD"

# 生成 Grafana 管理员密码（24 字节 Base64）
GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)
echo "GRAFANA_ADMIN_PASSWORD=$GRAFANA_ADMIN_PASSWORD"

echo ""
echo "=========================================="
echo "⚠️ 重要提示："
echo "1. 立即复制以上密码到密码管理器"
echo "2. 不要将密码截图发送到聊天工具"
echo "3. 建议至少 2 人备份密码"
echo "4. 生产环境密码不应与开发环境相同"
echo "=========================================="
SCRIPT

# 赋予执行权限
chmod +x /tmp/generate-passwords.sh

# 执行生成
/tmp/generate-passwords.sh
```

#### 2.2 密码备份（重要！）

**方式 1：保存到加密文件**
```bash
# 安装 GPG（如未安装）
# Ubuntu/Debian
sudo apt-get install gnupg

# CentOS/RHEL
sudo yum install gnupg2

# 生成 GPG 密钥（首次使用）
gpg --gen-key

# 加密密码文件
gpg -c /tmp/passwords.txt
# 输入加密密码（用于解密）
# 生成 passwords.txt.gpg 加密文件

# 删除明文
shred -u /tmp/passwords.txt

# 保存加密文件到安全位置
cp /tmp/passwords.txt.gpg ~/backup/company-rag-passwords.gpg
```

**方式 2：保存到密码管理器**
```bash
# 1Password / Bitwarden / KeePass 中创建以下条目：

条目名称：CompanyRag-Production-Database
字段：
  - 主机：production-server-ip
  - 端口：5433
  - 数据库：company_rag
  - 用户名：company_rag_app
  - 密码：[复制 POSTGRES_PASSWORD]
  - 备注：部署日期 2026-XX-XX

条目名称：CompanyRag-Production-Redis
字段：
  - 主机：production-server-ip
  - 端口：6379
  - 密码：[复制 REDIS_PASSWORD]

条目名称：CompanyRag-Production-JWT
字段：
  - 密钥：[复制 JWT_SECRET]
  - 备注：Base64 编码，32 字节

条目名称：CompanyRag-Production-Grafana
字段：
  - 用户名：admin
  - 密码：[复制 GRAFANA_ADMIN_PASSWORD]
  - URL: http://production-server-ip:3000
```

**方式 3：打印纸质备份（高安全场景）**
```bash
# 打印到终端，手动抄写
cat /tmp/passwords.txt

# 或使用 qrencode 生成二维码
sudo apt-get install qrencode
qrencode -t ANSIUTF8 < /tmp/passwords.txt
```

---

### 3. 项目代码部署

#### 3.1 方式 A：Git 克隆（推荐）
```bash
# 创建应用目录
sudo mkdir -p /opt/company-rag
sudo chown $USER:$USER /opt/company-rag
cd /opt/company-rag

# 克隆代码（私有仓库需配置 SSH Key）
git clone https://github.com/hulongfu/CompanyRag.git .

# 或 SSH 方式（推荐）
# git clone git@github.com:hulongfu/CompanyRag.git .

# 切换到最新稳定版本
git checkout main
git pull origin main
```

#### 3.2 方式 B：上传编译包
```bash
# 本地编译
cd D:/tmp/CompanyRag
mvn clean package -DskipTests

# 上传 JAR 包到服务器
scp company-rag-bootstrap/target/company-rag-bootstrap-1.0.0-SNAPSHOT.jar user@production-server:/opt/company-rag/

# 上传 docker-compose.yml
scp docker-compose.yml user@production-server:/opt/company-rag/
```

---

### 4. 配置文件准备

#### 4.1 创建生产环境 .env 文件

```bash
cd /opt/company-rag

# 创建 .env 文件
cat > .env << 'EOF'
# ===========================================
# CompanyRag 生产环境配置
# 生成日期：2026-XX-XX
# ===========================================

# ---------- LLM API Key（从官方控制台获取）----------
DASHSCOPE_API_KEY=sk-your-dashscope-key-here
SILICONFLOW_API_KEY=sk-your-siliconflow-key-here

# ---------- 数据库配置 ----------
POSTGRES_PASSWORD=<从步骤 2 复制 POSTGRES_PASSWORD>
POSTGRES_PORT=5433
POSTGRES_DB=company_rag
POSTGRES_USER=company_rag_app

# ---------- Redis 配置 ----------
REDIS_PASSWORD=<从步骤 2 复制 REDIS_PASSWORD>
REDIS_PORT=6379

# ---------- JWT 配置 ----------
JWT_SECRET=<从步骤 2 复制 JWT_SECRET>
JWT_EXPIRATION=86400000

# ---------- Grafana 配置 ----------
GRAFANA_ADMIN_PASSWORD=<从步骤 2 复制 GRAFANA_ADMIN_PASSWORD>

# ---------- 应用配置 ----------
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
LOG_LEVEL=INFO

# ===========================================
# 安全提示：
# 1. 本文件已加入 .gitignore，不应提交到 Git
# 2. 文件权限应设置为 600
# 3. 定期更换密码（建议 90 天）
# ===========================================
EOF

# 设置文件权限（仅 owner 可读写）
chmod 600 .env

# 验证权限
ls -la .env
# 应显示：-rw------- 1 user user .env
```

#### 4.2 验证配置文件

```bash
# 检查 .env 文件是否存在且权限正确
test -f .env && echo "✅ .env 文件存在" || echo "❌ .env 文件不存在"
stat -c "%a" .env | grep -q "600" && echo "✅ 权限正确 (600)" || echo "❌ 权限错误"

# 检查必需环境变量
for var in DASHSCOPE_API_KEY SILICONFLOW_API_KEY POSTGRES_PASSWORD REDIS_PASSWORD JWT_SECRET GRAFANA_ADMIN_PASSWORD; do
  grep -q "^$var=" .env && echo "✅ $var 已配置" || echo "❌ $var 未配置"
done
```

---

### 5. 网络与安全配置

#### 5.1 防火墙配置

**Ubuntu (UFW):**
```bash
# 启用防火墙
sudo ufw enable

# 允许 SSH
sudo ufw allow 22/tcp

# 允许应用端口
sudo ufw allow 8080/tcp

# 允许 Grafana（可选，建议通过 Nginx 反向代理）
sudo ufw allow 3000/tcp

# 允许 Prometheus（可选，建议内网访问）
# sudo ufw allow 9090/tcp

# 查看状态
sudo ufw status verbose
```

**CentOS (Firewalld):**
```bash
# 启用防火墙
sudo systemctl enable firewalld
sudo systemctl start firewalld

# 开放端口
sudo firewall-cmd --permanent --add-service=ssh
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --permanent --add-port=3000/tcp

# 重载配置
sudo firewall-cmd --reload

# 查看状态
sudo firewall-cmd --list-all
```

**云服务商安全组（阿里云/腾讯云/AWS）：**
- [ ] 添加入站规则：8080（应用）
- [ ] 添加入站规则：3000（Grafana，可选）
- [ ] 添加入站规则：22（SSH，建议限制 IP）
- [ ] **禁止**公开访问 5433（PostgreSQL）
- [ ] **禁止**公开访问 6379（Redis）

---

#### 5.2 SSH 安全加固（可选但建议）

```bash
# 备份 SSH 配置
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak

# 编辑 SSH 配置
sudo vim /etc/ssh/sshd_config

# 修改以下配置：
# Port 2222                    # 修改默认端口（可选）
# PermitRootLogin no           # 禁止 root 登录
# PasswordAuthentication no    # 禁止密码登录（使用 SSH Key）
# PubkeyAuthentication yes     # 启用公钥认证
# MaxAuthTries 3               # 最大尝试次数

# 重启 SSH 服务
sudo systemctl restart sshd

# ⚠️ 注意：修改 SSH 配置前确保已配置 SSH Key，否则可能无法登录！
```

---

## 🚀 部署执行（Deployment）

### 6. 启动基础设施

```bash
cd /opt/company-rag

# 启动 PostgreSQL 和 Redis
docker compose up -d postgres redis

# 查看容器状态
docker compose ps

# 等待数据库就绪（约 10-30 秒）
sleep 15

# 验证 PostgreSQL
docker compose exec -T postgres pg_isready -U company_rag_app -d company_rag

# 验证 Redis
docker compose exec -T redis redis-cli -a "$REDIS_PASSWORD" ping
# 应返回：PONG
```

---

### 7. 编译与启动应用

#### 7.1 方式 A：服务器本地编译（推荐）

```bash
cd /opt/company-rag

# 编译项目（跳过测试）
mvn clean package -DskipTests

# 查看编译结果
ls -lh company-rag-bootstrap/target/*.jar

# 启动应用（后台运行）
nohup java -jar company-rag-bootstrap/target/company-rag-bootstrap-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  > logs/app.log 2>&1 &

# 记录进程 ID
echo $! > logs/app.pid

# 查看启动日志
tail -f logs/app.log
```

#### 7.2 方式 B：使用 Docker 运行应用

```bash
# 在 docker-compose.yml 中添加 app 服务
cat >> docker-compose.yml << 'EOF'

  app:
    image: openjdk:17-slim
    container_name: company-rag-app
    working_dir: /app
    volumes:
      - ./company-rag-bootstrap/target:/app
      - ./logs:/app/logs
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    depends_on:
      - postgres
      - redis
    restart: unless-stopped
    command: >
      java -jar company-rag-bootstrap-1.0.0-SNAPSHOT.jar
EOF

# 启动所有服务
docker compose up -d

# 查看日志
docker compose logs -f app
```

---

### 8. 验证部署

#### 8.1 健康检查

```bash
# 等待应用启动（约 30-60 秒）
sleep 30

# 检查应用健康状态
curl -s http://localhost:8080/actuator/health | jq .

# 预期输出：
# {
#   "status": "UP",
#   "components": { ... }
# }

# 检查 Prometheus 指标
curl -s http://localhost:8080/actuator/prometheus | head -20

# 检查应用首页
curl -s http://localhost:8080/ | head -10
```

#### 8.2 功能验证

```bash
# 1. 登录验证（使用默认账号）
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 预期返回 JWT token
# {"code":200,"data":{"token":"eyJhbGc..."},"message":"success"}

# 2. 检查租户列表（需要登录后）
# 使用返回的 token 访问受保护接口

# 3. 检查数据库连接
docker compose exec -T postgres psql -U company_rag_app -d company_rag \
  -c "SELECT COUNT(*) FROM tenant_tenant;"
```

#### 8.3 监控验证

```bash
# 访问 Grafana
echo "Grafana URL: http://$(hostname -I | awk '{print $1}'):3000"
echo "用户名：admin"
echo "密码：$GRAFANA_ADMIN_PASSWORD"

# 访问 Prometheus
echo "Prometheus URL: http://$(hostname -I | awk '{print $1}'):9090"

# 检查 Docker 容器状态
docker compose ps

# 查看资源使用
docker stats --no-stream
```

---

## ✅ 部署后检查（Post-Deployment）

### 9. 安全加固

#### 9.1 修改默认密码

```bash
# ⚠️ 重要：首次登录后立即修改 admin 密码！

# 方式 1：通过 Web 界面修改
# 1. 访问 http://server-ip:8080
# 2. 使用 admin/admin123 登录
# 3. 进入个人中心修改密码

# 方式 2：通过 API 修改
curl -X PUT http://localhost:8080/api/admin/password \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"admin123","newPassword":"YourNewStrongPassword123!"}'
```

#### 9.2 配置 HTTPS（建议）

```bash
# 使用 Nginx 反向代理 + Let's Encrypt 免费证书

# 安装 Nginx
sudo apt-get install nginx  # Ubuntu
sudo yum install nginx      # CentOS

# 配置 Nginx
sudo vim /etc/nginx/sites-available/company-rag

server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# 安装 Certbot
sudo apt-get install certbot python3-certbot-nginx

# 获取证书
sudo certbot --nginx -d your-domain.com

# 自动续期证书
sudo crontab -e
# 添加：0 3 1 * * certbot renew --quiet
```

---

#### 9.3 日志轮转配置

```bash
# 创建 logrotate 配置
sudo vim /etc/logrotate.d/company-rag

/opt/company-rag/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0640 $USER $USER
    postrotate
        # 如需重新加载日志，可在此添加命令
    endscript
}

# 测试配置
sudo logrotate -d /etc/logrotate.d/company-rag
```

---

### 10. 备份策略

#### 10.1 数据库备份脚本

```bash
# 创建备份脚本
cat > /opt/company-rag/scripts/backup-db.sh << 'SCRIPT'
#!/bin/bash

# 配置
BACKUP_DIR="/opt/backups/company-rag/db"
DATE=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=30

# 创建备份目录
mkdir -p $BACKUP_DIR

# 导出数据库
docker compose exec -T postgres pg_dump -U company_rag_app company_rag \
  | gzip > $BACKUP_DIR/company_rag_$DATE.sql.gz

# 删除旧备份
find $BACKUP_DIR -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete

# 记录日志
echo "[$(date)] 数据库备份完成：$BACKUP_DIR/company_rag_$DATE.sql.gz" \
  >> /var/log/company-rag-backup.log
SCRIPT

# 赋予执行权限
chmod +x /opt/company-rag/scripts/backup-db.sh

# 测试备份
/opt/company-rag/scripts/backup-db.sh

# 配置定时任务（每天凌晨 2 点）
crontab -e
# 添加：0 2 * * * /opt/company-rag/scripts/backup-db.sh
```

#### 10.2 配置文件备份

```bash
# 备份 .env 文件
cp /opt/company-rag/.env /opt/backups/company-rag/config/.env.$(date +%Y%m%d)

# 备份到远程服务器（可选）
scp /opt/company-rag/.env backup-server:/backups/company-rag/
```

---

### 11. 监控告警配置

#### 11.1 Grafana 仪表盘导入

```bash
# 访问 Grafana
# http://server-ip:3000

# 登录：admin / <GRAFANA_ADMIN_PASSWORD>

# 导入仪表盘：
# 1. 点击 Dashboards → Import
# 2. 输入 ID：10280（Spring Boot 监控）
# 3. 选择 Prometheus 数据源
# 4. 点击 Import
```

#### 11.2 配置告警规则

**在 Grafana 中配置：**

1. **LLM 调用失败率告警**
   - 指标：`rate(llm_calls_failed_total[5m]) / rate(llm_calls_total[5m])`
   - 阈值：> 0.5
   - 持续时间：5 分钟
   - 级别：Critical

2. **应用内存告警**
   - 指标：`jvm_memory_used_bytes / jvm_memory_max_bytes`
   - 阈值：> 0.9
   - 持续时间：10 分钟
   - 级别：Warning

3. **数据库连接池告警**
   - 指标：`hikaricp_active_connections / hikaricp_max_connections`
   - 阈值：> 0.8
   - 持续时间：5 分钟
   - 级别：Warning

---

## 📊 部署验收清单

### 12. 最终验证

- [ ] **应用访问：** http://server-ip:8080 正常打开
- [ ] **健康检查：** `/actuator/health` 返回 UP
- [ ] **登录功能：** admin 账号可登录
- [ ] **租户管理：** 可创建/查看租户
- [ ] **文档上传：** 可上传文档并解析
- [ ] **RAG 检索：** 可执行知识库问答
- [ ] **监控面板：** Grafana 仪表盘正常显示
- [ ] **日志记录：** 日志文件正常滚动
- [ ] **数据库备份：** 备份脚本执行成功
- [ ] **密码修改：** admin 默认密码已修改
- [ ] **权限检查：** .env 文件权限为 600
- [ ] **防火墙配置：** 仅开放必要端口
- [ ] **密码备份：** 密码已保存到密码管理器

---

## 🆘 故障排查

### 常见问题

#### 1. 应用启动失败

```bash
# 查看日志
tail -100 /opt/company-rag/logs/app.log

# 检查端口占用
netstat -tlnp | grep 8080

# 检查 Java 版本
java -version

# 检查内存
free -h
```

#### 2. 数据库连接失败

```bash
# 检查 PostgreSQL 容器
docker compose ps postgres

# 查看 PostgreSQL 日志
docker compose logs postgres

# 测试连接
docker compose exec postgres psql -U company_rag_app -d company_rag -c "SELECT 1;"
```

#### 3. Redis 连接失败

```bash
# 检查 Redis 容器
docker compose ps redis

# 查看 Redis 日志
docker compose logs redis

# 测试连接
docker compose exec redis redis-cli -a "$REDIS_PASSWORD" ping
```

#### 4. LLM API 调用失败

```bash
# 检查 API Key 配置
grep DASHSCOPE_API_KEY /opt/company-rag/.env

# 测试 API 连通性
curl -I https://dashscope.aliyuncs.com

# 检查余额/配额
# 访问 https://dashscope.console.aliyun.com/
```

---

## 📞 联系与支持

**项目仓库：**
- GitHub: https://github.com/hulongfu/CompanyRag.git

**文档：**
- 架构文档：`README.md`
- 快速开始：`docs/QUICKSTART.md`
- 安全修复：`docs/security-fixes/`
- 评估报告：`docs/superpowers/specs/2026-08-14-production-readiness-assessment.md`

**部署日志：**
- 应用日志：`/opt/company-rag/logs/app.log`
- Docker 日志：`docker compose logs`
- 备份日志：`/var/log/company-rag-backup.log`

---

## 📝 部署记录模板

```markdown
## 部署记录

**部署日期：** 2026-XX-XX
**部署人员：** XXX
**服务器 IP：** X.X.X.X
**部署版本：** 1.0.0-SNAPSHOT

### 配置信息
- [ ] .env 文件已生成并设置权限 600
- [ ] 密码已备份到密码管理器
- [ ] 防火墙规则已配置
- [ ] SSH 加固已完成

### 验证结果
- [ ] 应用启动成功
- [ ] 健康检查通过
- [ ] 功能测试通过
- [ ] 监控配置完成

### 问题记录
（部署过程中遇到的问题及解决方案）

### 备注
（其他需要记录的信息）
```

---

**文档版本：** 1.0  
**最后更新：** 2026-08-14  
**下次审查：** 部署后 3 个月或重大版本更新
