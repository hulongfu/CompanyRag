# MCP 安全修复和技能加载修复 - 修改总结

## 修改日期
2026-08-26

## 问题背景

### 问题 1：MCP Server 匿名访问和租户上下文清理问题

**根因分析：**
1. `McpSecurityFilter` 允许匿名访问（无 Token 时仍放行请求）
2. `finally` 块未清理 `TenantContext`，依赖 `TenantInterceptor` 清理
3. 匿名访问时租户上下文未设置，可能导致数据越权访问或 Schema 错误

**安全风险：**
- 未认证用户可以调用 MCP 工具
- 租户隔离失效，可能访问其他租户数据
- 线程池复用可能导致上下文污染

### 问题 2：技能在生产镜像加载失败问题

**根因分析：**
1. Dockerfile 只打包 `app.jar`，未复制 `agent_skills` 目录
2. `AgentConfig.java` 使用硬编码的相对路径 `./agent_skills`
3. Docker 容器运行目录为 `/app`，技能目录不存在

**影响：**
- Agent 无法加载任何技能
- 技能调用功能完全失效

---

## 修改内容

### 修改 1：McpSecurityFilter.java - 禁止匿名访问并强制租户上下文

**文件路径：** `company-rag-mcp/src/main/java/com/company/rag/mcp/filter/McpSecurityFilter.java`

**关键修改：**

1. **禁止匿名访问**（第 59-65 行）：
```java
// 2. Token 不存在时，拒绝访问（禁止匿名访问）
if (token == null || token.isEmpty()) {
    log.warn("MCP 拒绝匿名访问：URI={}", path);
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, 
            "MCP 访问需要 JWT 认证，请提供 Authorization 请求头");
    return;
}
```

2. **强制租户上下文验证**（第 112-118 行）：
```java
// 用户没有任何租户，拒绝访问
log.warn("MCP 用户无租户：userId={}", userId);
response.sendError(HttpServletResponse.SC_FORBIDDEN, 
        "用户未关联任何租户");
return;
```

3. **防御性检查租户上下文**（第 120-126 行）：
```java
// 8. 验证租户上下文是否已设置（防御性检查）
if (TenantContext.getTenantId() == null) {
    log.error("MCP 租户上下文未设置，拒绝访问：userId={}", userId);
    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
            "租户上下文未设置");
    return;
}
```

4. **finally 块清理上下文**（第 135-139 行）：
```java
} finally {
    // 清理上下文（在请求完成后）
    TenantContext.clear();
    log.debug("MCP 请求完成，已清理租户上下文：URI={}", path);
}
```

5. **更新类注释**（第 19-33 行）：
- 明确说明禁止匿名访问
- 说明强制租户隔离策略
- 说明防御性检查机制

---

### 修改 2：AgentConfig.java - 使用环境变量配置技能路径

**文件路径：** `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java`

**关键修改：**

1. **支持环境变量配置**（第 45-51 行）：
```java
// 配置 Skills 注册中心，支持通过环境变量配置技能路径
// 默认值：./agent_skills（本地开发）
// Docker 环境：通过 SKILLS_PATH 环境变量配置，如 /app/agent_skills
String skillsPath = System.getenv("SKILLS_PATH");
if (skillsPath == null || skillsPath.isEmpty()) {
    skillsPath = "./agent_skills";
}
```

2. **增强日志记录**（第 54-67 行）：
- 使用 `log.error` 记录技能目录不存在
- 提供明确的错误提示和解决建议

3. **降级处理**（第 70-82 行）：
```java
// 创建 FileSystemSkillRegistry，扫描外部文件系统中的技能
FileSystemSkillRegistry skillRegistry;
try {
    skillRegistry = FileSystemSkillRegistry.builder()
            .userSkillsDirectory(finalSkillsPath)  // 使用 String 参数
            .build();
    log.info("Skills 注册中心初始化成功：{}", finalSkillsPath);
} catch (Exception e) {
    log.error("Skills 注册中心初始化失败：{}", finalSkillsPath, e);
    // 降级处理：创建空的注册中心，避免应用启动失败
    skillRegistry = FileSystemSkillRegistry.builder()
            .userSkillsDirectory("./agent_skills_empty_fallback")
            .build();
}
```

4. **启动告警**（第 103-104 行）：
```java
// 技能加载成功告警
log.info("ReactAgent 初始化完成，技能功能已启用");
```

---

### 修改 3：Dockerfile - 复制 skills 目录并设置环境变量

**文件路径：** `Dockerfile`

**关键修改：**

1. **复制 agent_skills 目录**（第 32-33 行）：
```dockerfile
# 复制 agent_skills 目录（技能定义）
COPY --from=build /build/agent_skills /app/agent_skills
```

2. **设置文件权限**（第 36-37 行）：
```dockerfile
# 更改文件所有者为 appuser
RUN chown appuser:appgroup app.jar
RUN chown -R appuser:appgroup /app/agent_skills
```

3. **设置环境变量**（第 39-40 行）：
```dockerfile
# 设置环境变量：技能路径
ENV SKILLS_PATH=/app/agent_skills
```

---

## 验证结果

### 编译验证
```bash
mvn clean compile -pl company-rag-mcp,company-rag-agent -am
```
✅ 编译成功，无错误

---

## 安全增强总结

### MCP 安全策略
| 项目 | 修改前 | 修改后 |
|------|--------|--------|
| 匿名访问 | 允许 | **禁止** |
| Token 验证 | 可选 | **强制** |
| 租户上下文 | 可能为空 | **强制设置** |
| 上下文清理 | 依赖拦截器 | **Filter 自动清理** |
| 防御性检查 | 无 | **调用前验证** |

### 技能加载增强
| 项目 | 修改前 | 修改后 |
|------|--------|--------|
| 路径配置 | 硬编码 `./agent_skills` | **环境变量 `SKILLS_PATH`** |
| Docker 支持 | ❌ 未复制 skills 目录 | ✅ **完整复制并设置权限** |
| 错误处理 | `log.warn` | **`log.error` + 降级处理** |
| 可观测性 | 基础日志 | **详细日志 + 告警** |

---

## 部署说明

### 本地开发
无需修改，默认使用 `./agent_skills` 目录

### Docker 部署
1. 构建镜像：
```bash
docker build -t company-rag:latest .
```

2. 运行容器（默认环境变量已设置）：
```bash
docker run -p 8080:8080 company-rag:latest
```

3. 自定义技能路径（可选）：
```bash
docker run -p 8080:8080 \
  -e SKILLS_PATH=/custom/skills \
  -v /host/skills:/custom/skills \
  company-rag:latest
```

### MCP 客户端调用
必须提供 JWT Token：
```http
Authorization: Bearer <your-jwt-token>
X-Tenant-Id: <tenant-id>
```

---

## 影响范围

### 影响的模块
- `company-rag-mcp` - MCP 安全过滤器
- `company-rag-agent` - Agent 技能加载配置
- `Dockerfile` - 生产镜像构建

### 向后兼容性
- **破坏性变更**：MCP 不再支持匿名访问
- **迁移指南**：所有 MCP 客户端必须提供 JWT Token

### 测试建议
1. 测试 MCP 匿名访问被拒绝
2. 测试有效 Token 可以正常访问
3. 测试租户隔离有效性
4. 测试 Docker 镜像中技能加载
5. 测试技能加载失败时的降级处理

---

## 参考文档
- [MCP 安全过滤器设计](../docs/superpowers/specs/2026-08-16-mcp-status-assessment.md)
- [技能引擎设计](../docs/superpowers/specs/2026-08-23-spring-ai-alibaba-skill-engine-design.md)
- [多租户隔离架构](../docs/architecture/multi-tenant-isolation-architecture.md)
