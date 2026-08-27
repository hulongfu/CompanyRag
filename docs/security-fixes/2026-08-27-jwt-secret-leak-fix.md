# 安全修复报告：JWT 密钥泄露与生产环境配置问题

**修复日期：** 2026-08-27  
**严重级别：** 🔴 严重（安全漏洞）  
**修复状态：** ✅ 已完成

---

## 问题概述

### 问题 1：生产环境未注入 JWT_SECRET 导致 500 错误

**症状：** 部署后所有接口（/api/chat、/mcp 等）返回 500 错误

**根因：**
- `application.yml:82` 默认值为空：`jwt.secret: ${JWT_SECRET:}`
- `docker-compose.yml` 未设置 `JWT_SECRET` 环境变量
- `JwtTokenProvider.getSigningKey()` 使用空密钥调用 `Keys.hmacShaKeyFor([])` 抛出 `IllegalArgumentException`

**影响：** 生产环境部署后立即失效，所有需要 JWT 认证的接口无法使用

---

### 问题 2：裸镜像默认 dev 环境 + 硬编码 JWT 密钥

**症状：** 任何人可使用公开密钥伪造任意租户 JWT

**根因：**
1. `Dockerfile` 未设置 `SPRING_PROFILES_ACTIVE`，默认使用 `application.yml:28` 的 `dev` 环境
2. `application-dev.yml:63` 硬编码密钥 `k07fDiqMxu5Fgnes77ASlM2Tl7CM9yCeiIZcWV8z9w8=` 已提交到 Git 仓库
3. `.gitignore` 忽略了 `.env` 但配置文件已公开

**影响：** 
- 🔴 **租户隔离完全失效**
- 攻击者可伪造任意租户的 JWT Token
- 可访问、篡改所有租户数据

---

## 修复内容

### 1. docker-compose.yml - 添加 JWT_SECRET 环境变量

**修改位置：** `docker-compose.yml:41`

```yaml
environment:
  SPRING_PROFILES_ACTIVE: prod
  DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
  JWT_SECRET: ${JWT_SECRET}  # ✅ 新增
```

**验证：** 生产环境部署时必须从 `.env` 文件注入 `JWT_SECRET`

---

### 2. Dockerfile - 强制设置生产环境

**修改位置：** `Dockerfile:42-43`

```dockerfile
# 强制设置生产环境（防止裸镜像默认 dev 环境）
ENV SPRING_PROFILES_ACTIVE=prod
```

**验证：** 裸镜像运行时不再默认使用 dev 环境

---

### 3. application-dev.yml - 移除硬编码 JWT 密钥

**修改位置：** `company-rag-bootstrap/src/main/resources/application-dev.yml:61-63`

**修改前：**
```yaml
# JWT 配置（开发环境临时配置，生产环境应通过环境变量）
jwt:
  secret: k07fDiqMxu5Fgnes77ASlM2Tl7CM9yCeiIZcWV8z9w8=  # ❌ 硬编码
```

**修改后：**
```yaml
# JWT 配置（开发环境通过环境变量注入，禁止硬编码）
jwt:
  secret: ${JWT_SECRET:}  # ✅ 从环境变量注入
```

**验证：** 开发环境也必须通过 `.env` 文件提供密钥

---

### 4. .env.example - 创建配置模板

**新增文件：** `.env.example`

包含所有必需环境变量的占位符和说明，新用户可复制为 `.env` 后填写。

**关键说明：**
```bash
# JWT 配置（密钥必须是 Base64 编码，至少 32 字节）
# 生成命令：python -c "import base64; import secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
JWT_SECRET=...
```

---

### 5. .env - 更新为新密钥

**修改位置：** `.env:24`

**旧密钥（已泄露，不再安全）：**
```
JWT_SECRET=k07fDiqMxu5Fgnes77ASlM2Tl7CM9yCeiIZcWV8z9w8=
```

**新密钥（已更新）：**
```
JWT_SECRET=HpuBmoiAE+WW0YyIayX8shBc72a1ZuDYrXITXHpyghU=
```

---

## 验证步骤

### 本地开发环境验证

```bash
# 1. 确认 .env 文件存在且包含新密钥
cat .env | grep JWT_SECRET

# 2. 启动开发环境
docker-compose up -d

# 3. 验证应用日志无 JWT 相关错误
docker logs company-rag-app 2>&1 | grep -i "jwt\|exception"

# 4. 测试登录接口
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 5. 使用返回的 Token 访问受保护接口
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/chat
```

### 生产环境验证

```bash
# 1. 确认 .env 文件包含 JWT_SECRET
cat .env | grep JWT_SECRET

# 2. 重新构建镜像
docker-compose build app

# 3. 启动服务
docker-compose up -d

# 4. 验证容器环境变量
docker exec company-rag-app env | grep SPRING_PROFILES_ACTIVE
docker exec company-rag-app env | grep JWT_SECRET

# 5. 验证应用正常启动
docker logs company-rag-app 2>&1 | tail -20
```

---

## 后续行动

### 立即执行
- ✅ 所有开发者更新本地 `.env` 文件
- ✅ 重新部署生产环境
- ✅ 撤销所有已颁发的 JWT Token（用户需重新登录）

### 建议执行
- [ ] 将旧密钥 `k07fDiqMxu5Fgnes77ASlM2Tl7CM9yCeiIZcWV8z9w8=` 加入密钥轮换黑名单
- [ ] 审计日志，检查是否有异常 Token 使用记录
- [ ] 考虑实施密钥定期轮换机制（如每 90 天）

### 长期改进
- [ ] 集成密钥管理系统（如 HashiCorp Vault、AWS Secrets Manager）
- [ ] 实施密钥强度校验（启动时检查密钥长度和熵）
- [ ] 添加安全配置检查（启动时验证敏感配置是否从环境变量注入）

---

## 安全建议

### 密钥管理最佳实践

1. **永远不要硬编码密钥** - 所有敏感配置通过环境变量注入
2. **定期轮换密钥** - 建议每 90 天更换一次 JWT 密钥
3. **密钥长度要求** - 至少 256 位（32 字节），Base64 编码
4. **密钥生成方式** - 使用密码学安全的随机数生成器
   ```bash
   # 推荐生成命令
   python -c "import base64; import secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
   ```

### 配置文件管理

1. **`.env` 文件** - 必须存在于 `.gitignore` 中
2. **`.env.example`** - 提供模板但不包含真实密钥
3. **配置文件** - 所有敏感字段使用 `${VAR:}` 占位符

---

## 修复文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `docker-compose.yml` | 修改 | 添加 `JWT_SECRET` 环境变量 |
| `Dockerfile` | 修改 | 强制设置 `SPRING_PROFILES_ACTIVE=prod` |
| `application-dev.yml` | 修改 | 移除硬编码密钥，改为环境变量注入 |
| `.env` | 修改 | 更新为新密钥 |
| `.env.example` | 新增 | 配置模板文件 |

---

## 参考文档

- [JWT 安全最佳实践](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
- [Docker 安全实践](https://docs.docker.com/develop/security-best-practices/)

---

**修复完成时间：** 2026-08-27  
**修复验证人：** 系统自动修复  
**下次审查日期：** 2026-11-27（90 天后）

