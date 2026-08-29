## Git Push

### 2026-08-29: Agent 技能路径优化与代码清理

- commit_type:            Fix
- task_id:                0000
- task_name:              Agent 技能路径优化与代码清理
- commit_hash:            a16145c88425e5ce1d8fc4512b454f837b014c86
- short_hash:             a16145c
- branch:                 main
- remote:                 gitee (GitHub 网络失败)
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java
  - company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java
  - agent_skills/file-manager/SKILL.md
  - agent_skills/web-search/SKILL.md
  - agent_skills/read-document/SKILL.md
  - company-rag-bootstrap/src/main/resources/application-dev.yml
  - company-rag-bootstrap/src/main/resources/application.yml
- commit_message:         Fix:0000_Agent 技能路径优化与代码清理：优化技能脚本相对路径支持 + 清理冗余代码
- commit_command:         git commit -m "Fix:0000_Agent 技能路径优化与代码清理：优化技能脚本相对路径支持 + 清理冗余代码"
- commit_exit_code:       0
- push_command:           git push gitee main (成功) && git push origin main (失败：Connection was reset)
- push_exit_code:         0 (gitee) / 128 (github)
- remote_head_check_command: git ls-remote gitee main
- remote_head:            a16145c88425e5ce1d8fc4512b454f837b014c86
- result:                 Gitee 推送成功，GitHub 因网络原因失败
- notes:                  核心优化：1) ExecuteTool 新增 detectSkillWorkingDirectory() 方法，自动检测技能目录并设置工作目录 2) 所有技能 SKILL.md 改用相对路径 scripts/xxx.py 3) CodeSearchTool 修复 Files.lines() UTF-8 编码问题 4) ExecuteTool 支持安全系统命令 (mkdir/copy/move 等)。编译验证通过（mvn clean compile）

---


### 2026-08-27: MCP 安全加固与技能加载修复

- commit_type:            BugFix
- task_id:                MCP安全技能加载修复
- task_name:              MCP安全加固与技能加载修复
- commit_hash:            d7642dbecdc8e94130be01bf60b9792720e7926f
- short_hash:             d7642db
- branch:                 feature/openclaw-skill-engine
- remote:                 gitee & github
- staged_files:
  - Dockerfile
  - company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java
  - company-rag-mcp/src/main/java/com/company/rag/mcp/filter/McpSecurityFilter.java
  - agent_skills/
  - docs/fixes/2026-08-26-mcp-architecture-optimization.md
  - docs/fixes/2026-08-26-mcp-security-and-skill-loading-fix.md
- commit_message:         BugFix:MCP安全技能加载修复_MCP安全加固与技能加载修复：forbid MCP anonymous access, self-set tenant schema, and support SKILLS_PATH env for skill loading
- commit_command:         git commit -m "BugFix:MCP安全技能加载修复_MCP安全加固与技能加载修复：forbid MCP anonymous access, self-set tenant schema, and support SKILLS_PATH env for skill loading"
- commit_exit_code:       0
- push_command:           git push gitee feature/openclaw-skill-engine && git push origin feature/openclaw-skill-engine
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee/origin feature/openclaw-skill-engine
- remote_head:            d7642dbecdc8e94130be01bf60b9792720e7926f
- result:                 gitee 推送成功且远端 HEAD 与本地一致；github push 命令成功(exit 0) 但后续 ls-remote 验证因网络连接丢失失败，无法独立确认 github 远端 HEAD
- notes:                  MCP 安全加固：1) 禁止匿名访问，强制 JWT 认证 2) 在 McpSecurityFilter 内自足设置租户 Schema（消除对 JwtAuthenticationFilter 的隐式耦合）3) finally 清理 TenantContext。技能加载修复：AgentConfig 支持 SKILLS_PATH 环境变量 + 加载失败降级处理；Dockerfile 复制 agent_skills 目录并设置环境变量。编译验证通过（mvn compile）

---

### 2026-08-26: 多租户 RLS 跨租户泄漏隐患分析评估（架构澄清）

- commit_type:            Task
- task_id:                0000
- task_name:              多租户 RLS 跨租户泄漏隐患分析评估
- commit_hash:            5b27e77cbb42e08c31f7052d82bbe39e3843339c
- short_hash:             5b27e77
- branch:                 feature/openclaw-skill-engine
- remote:                 gitee & github
- staged_files:
  - company-rag-tenant/src/main/java/com/company/rag/tenant/context/TenantContextHelper.java
  - company-rag-tenant/src/main/java/com/company/rag/tenant/interceptor/TenantSchemaInterceptor.java
  - docs/architecture/multi-tenant-isolation-architecture.md
- commit_message:         Task:0000_多租户 RLS 跨租户泄漏隐患分析评估：clarify architecture and remove misleading comments
- commit_command:         git commit -m "Task:0000_多租户 RLS 跨租户泄漏隐患分析评估：clarify architecture and remove misleading comments"
- commit_exit_code:       0
- push_command:           git push gitee feature/openclaw-skill-engine && git push origin feature/openclaw-skill-engine
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee/origin feature/openclaw-skill-engine
- remote_head:            5b27e77cbb42e08c31f7052d82bbe39e3843339c
- result:                 推送成功，Gitee 和 GitHub 远端 HEAD 与本地提交哈希一致
- notes:                  方案 C 实施：1) 清理 TenantSchemaInterceptor 和 TenantContextHelper 中的误导性注释 2) 添加清晰的架构说明：Schema 隔离为主（100% 可靠），RLS 为辅（深度防御）3) 新增完整架构文档 multi-tenant-isolation-architecture.md 防止后续误解

---

### 2026-08-25: MCP 工具无法调用修复（第六次修复）

- commit_type:            BugFix
- task_id:                0000
- task_name:              MCP 工具无法调用修复
- commit_hash:            d4aded88848afe22ae433fa6c8d795e4528dcc07
- short_hash:             d4aded8
- branch:                 feature/openclaw-skill-engine
- remote:                 gitee & github
- staged_files:
  - docs/fixes/2026-08-25-mcp-tool-not-called-fix.md
  - company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java
  - company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientAutoConfig.java
  - company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientRegistry.java
  - company-rag-rag/src/main/java/com/company/rag/rag/config/AgentToolConfig.java
- commit_message:         BugFix:0000_MCP 工具无法调用修复：use @DependsOn to fix Bean initialization order
- commit_command:         git commit -m "BugFix:0000_MCP 工具无法调用修复：use @DependsOn to fix Bean initialization order"
- commit_exit_code:       0
- push_command:           git push gitee feature/openclaw-skill-engine && git push origin feature/openclaw-skill-engine
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee/origin feature/openclaw-skill-engine
- remote_head:            d4aded88848afe22ae433fa6c8d795e4528dcc07
- result:                 推送成功，Gitee 和 GitHub 远端 HEAD 与本地提交哈希一致
- notes:                  修复 Bean 初始化顺序问题：1) 发现@AutoConfigureAfter 因缺少@AutoConfiguration 注解而失效 2) 改用@DependsOn("mcpClientAutoConfig")强制 Bean 创建顺序 3) 确保 MCP 工具在 ReactAgent 构建前完成注册

---



### 2026-08-25: Spring AI Alibaba 1.1.2 API 兼容性修复

- commit_type:            BugFix
- task_id:                0000
- task_name:              spring-ai-alibaba-api-fix
- commit_hash:            61f5aaf5387846452883c1051271474fef23f5d1
- short_hash:             61f5aaf
- branch:                 feature/openclaw-skill-engine
- remote:                 gitee
- staged_files:
  - company-rag-agent/pom.xml
  - company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java
  - company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
  - company-rag-bootstrap/pom.xml
  - company-rag-bootstrap/src/main/resources/application-dev.yml
  - company-rag-bootstrap/src/main/resources/application-prod.yml
  - company-rag-bootstrap/src/main/resources/application-test.yml
  - company-rag-mcp/src/test/java/com/company/rag/mcp/handler/JsonRpcHandlerTest.java
  - company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolEndToEndTest.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java
  - pom.xml
  - docs/fixes/2026-08-24-spring-ai-alibaba-1.1.2-api-fix.md
- commit_message:         BugFix:0000_spring-ai-alibaba-api-fix:fix DashScope auto-configuration conflicts and upgrade Spring AI to 1.1.3
- commit_command:         git commit -m "BugFix:0000_spring-ai-alibaba-api-fix:fix DashScope auto-configuration conflicts and upgrade Spring AI to 1.1.3"
- commit_exit_code:       0
- push_command:           git push gitee feature/openclaw-skill-engine
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee feature/openclaw-skill-engine
- remote_head:            61f5aaf5387846452883c1051271474fef23f5d1
- result:                 推送成功，Gitee 远端 HEAD 与本地提交哈希一致；GitHub 因网络问题推送失败（预期情况）
- notes:                  修复 Spring AI Alibaba 1.1.2 API 兼容性问题：1) 升级 Spring AI BOM 从 1.0.4 到 1.1.3 2) 排除所有 DashScope 原生自动配置（Audio/Chat/Agent/Embedding/Image/Rerank/Video）3) 添加 ReactAgent name 配置 4) 修复 AgentController 编译错误 5) 删除重复的 toolCallbackProvider Bean。实现使用 OpenAI 兼容接口（通义千问 Chat + 硅基流动 Embedding/Rerank）

---


### 2026-08-24: MCP 工具动态版本修复

- commit_type:            BugFix
- task_id:                mcp-tool-fix
- task_name:              MCP 工具动态注册问题修复
- commit_hash:            83564e48deeb4dbfdc3ca8ddba9784d18d55a192
- short_hash:             83564e4
- branch:                 feature/openclaw-skill-engine
- remote:                 gitee
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java
  - company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientAutoConfig.java
  - company-rag-rag/src/main/java/com/company/rag/rag/config/AggregatedToolCallbackProvider.java
  - company-rag-rag/src/main/java/com/company/rag/rag/config/AgentToolConfig.java
  - company-rag-rag/pom.xml
  - docs/fixes/2026-08-24-mcp-tool-dynamic-version-fix.md
  - docs/fixes/2026-08-24-mcp-tool-integration-fix.md
- commit_message:         修复 MCP 工具动态注册问题：实现版本号机制，ChatClient 自动重建
- commit_command:         git commit -m "修复 MCP 工具动态注册问题：实现版本号机制，ChatClient 自动重建"
- commit_exit_code:       0
- push_command:           git push gitee feature/openclaw-skill-engine
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee feature/openclaw-skill-engine
- remote_head:            07d1ae7
- result:                 推送成功，Gitee 远端 HEAD 与本地提交哈希一致；GitHub 因网络问题推送失败（预期情况）
- notes:                  修复 MCP 工具注册后无法被 ChatClient 调用的问题：1) AgentToolRegistry 增加版本号管理，每次注册工具时递增 2) RagAgentService 改为缓存模式，getChatClient() 检查版本号变化 3) 工具列表变化时自动重建 ChatClient 4) 移除 McpClientAutoConfig 多余的@EnableConfigurationProperties。实现工具动态注册生效，支持 MCP 工具和本地工具混合使用。


### 2026-08-20: 会话记忆功能实现

- commit_type:            Feat
- task_id:                20260820
- task_name:              会话记忆功能实现
- commit_hash:            a2e50296a02f4762e8261d185bc9eb646d454c3f
- short_hash:             a2e5029
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
- commit_message:         Feat:20260820_会话记忆功能实现：实现会话记忆功能，支持三级窗口控制策略
- commit_command:         git commit -m "Feat:20260820_会话记忆功能实现：实现会话记忆功能，支持三级窗口控制策略"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            a2e50296a02f4762e8261d185bc9eb646d454c3f
- result:                 推送成功，Gitee 远端 HEAD 与本地提交哈希一致
- notes:                  实现会话记忆功能：1) RagAgentService 新增 processWithHistory 方法 2) ChatController 读取历史会话并传给 Agent 3) 三级窗口控制策略（直接注入→LLM 压缩→硬窗口截断）4) 修复 toString() 导致的噪声问题，改用 getText() 提取纯文本内容

### 2026-08-15: 生产安全 bug 修复

- commit_type:            BugFix
- task_id:                0000
- task_name:              生产安全 bug 修复
- commit_hash:            350c84dceb58eab413a47b39f9d806cc2121f388
- short_hash:             350c84d
- branch:                 main
- remote:                 github + gitee
- staged_files:
  - Dockerfile
  - README.md
  - .dockerignore
  - company-rag-common/src/main/java/com/company/rag/common/config/JwtSecurityValidator.java
  - company-rag-common/src/main/java/com/company/rag/common/security/UserContext.java
  - company-rag-bootstrap/src/main/resources/application-prod.yml
  - company-rag-bootstrap/src/main/resources/application.yml
  - company-rag-rag/src/main/java/com/company/rag/rag/service/RagCircuitBreakerConfig.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/CacheManageController.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/SessionController.java
  - deploy-log.md
- commit_message:         BugFix:0000_生产安全 bug 修复：fix security vulnerabilities and production readiness issues
- commit_command:         git commit -m "BugFix:0000_生产安全 bug 修复：fix security vulnerabilities and production readiness issues"
- commit_exit_code:       0
- push_command:           git push origin main && git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote origin main && git ls-remote gitee main
- remote_head:            350c84dceb58eab413a47b39f9d806cc2121f388
- result:                 推送成功，GitHub 和 Gitee 远端 HEAD 与本地提交哈希一致
- notes:                  修复 8 个生产安全和稳定性问题：1) SessionController userId 硬编码 2) JWT 弱密钥无 fail-fast 3) 控制器缺少@PreAuthorize 4) Dockerfile root 运行 5) Dockerfile 无.dockerignore 6) -DskipTests 7) 无优雅停机 8) LLM 超时配置错误 9) application-prod.yml currentSchema=public 租户隔离问题

### 2026-08-15: 修复 WHERE 子句中子查询的跨租户访问漏洞

- commit_type:            BugFix
- task_id:                0003
- task_name:              WHERE 子句子查询跨租户访问漏洞修复
- commit_hash:            ef03186
- short_hash:             ef03186
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
  - company-rag-agent/src/test/java/com/company/rag/agent/tool/WhereSubqueryTest.java
- commit_message:         BugFix:0003_修复 WHERE 子句中子查询的跨租户访问漏洞
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            ef03186
- result:                 推送成功，远端 HEAD 与本地提交哈希一致
- notes:                  修复 hasExplicitSchemaInSelectObject 方法未检查 WHERE/SELECT/HAVING/GROUP BY/ORDER BY 子句中的子查询问题。增强 hasExplicitSchemaInExpression 方法递归检查 InExpression、ExistsExpression、ComparisonOperator、AndExpression、OrExpression 等所有表达式类型。新增 4 个测试用例全部通过。

### 2026-08-14: 租户安全漏洞综合修复

- commit_type:            BugFix
- task_id:                0001
- task_name:              租户安全漏洞修复
- commit_hash:            0f9c377ecd74c8a97b6a48f94141ab435c3c9754
- short_hash:             0f9c377
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java
  - company-rag-tenant/src/main/java/com/company/rag/tenant/interceptor/TenantInterceptor.java
  - company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/UserServiceImpl.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/TenantController.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/UserController.java
- commit_message:         BugFix:0001_租户安全漏洞修复：fix 5 tenant isolation vulnerabilities
- commit_command:         git commit -m "BugFix:0001_租户安全漏洞修复：fix 5 tenant isolation vulnerabilities"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            0f9c377ecd74c8a97b6a48f94141ab435c3c9754
- result:                 推送成功，远端 HEAD 与本地提交哈希一致
- notes:                  修复 5 个安全问题：1) ?tenantId= URL 参数绕过 2) SQL 子查询跨租户绕过 3) Controller 层权限缺失 4) TenantContext 清理不完整 5) UserService 租户校验缺失

### 2026-08-14: 修复 containsExplicitSchema 漏检带引号标识符

- commit_type:            BugFix
- task_id:                0002
- task_name:              引号标识符跨租户访问漏洞修复
- commit_hash:            4287e71
- short_hash:             4287e71
- branch:                 main
- remote:                 (待推送)
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
  - company-rag-agent/src/test/java/com/company/rag/agent/security/QuotedIdentifierTest.java
  - company-rag-agent/src/test/java/com/company/rag/agent/tool/DatabaseQueryToolTest.java
- commit_message:         BugFix:0002_修复 containsExplicitSchema 漏检带引号标识符的跨租户访问
- commit_exit_code:       0
- result:                 提交成功，待推送
- notes:                  修复 containsExplicitSchema 方法对带引号标识符（如"tenant_other"."secret"）的漏检问题，增加 hasExplicitSchemaInSelect() 方法直接检查 table.getSchemaName() != null。同时修复 addSchemaPrefix 使用 cleanSql 确保注释被移除。

---

### 2026-08-14: JSqlParser SQL 注入防护

- commit_type:            Feat
- task_id:                20260814
- task_name:              企业级生产部署评估 - SQL 注入防护
- commit_hash:            50d8e7d
- short_hash:             50d8e7d
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/pom.xml
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
  - company-rag-agent/src/main/java/com/company/rag/agent/security/SqlSecurityValidator.java (新建)
  - company-rag-agent/src/test/java/com/company/rag/agent/tool/DatabaseQueryToolTest.java
  - docs/superpowers/specs/2026-08-14-production-readiness-assessment.md
- commit_message:         feat: 添加 JSqlParser SQL 注入防护
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- result:                 推送成功
- notes:                  引入 JSqlParser 4.6 进行 SQL 语法级验证，添加 29 个单元测试，所有测试通过

---

### 2026-08-14: 企业级生产部署评估

- commit_type:            Feat
- task_id:                20260814
- task_name:              企业级生产部署评估
- commit_hash:            2779d3206a39ce2bc2699a7ed1bd7adc1c6a140a
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-bootstrap/src/main/resources/application-dev.yml
  - company-rag-bootstrap/src/main/resources/application-test.yml
  - docs/architecture/tenant-isolation-verification.md
  - docs/superpowers/specs/2026-08-14-production-readiness-assessment.md
- commit_message:         Feat:20260814_企业级生产部署评估：完善部署评估报告和租户隔离验证文档
- commit_command:         git commit -m "Feat:20260814_企业级生产部署评估：完善部署评估报告和租户隔离验证文档"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            2779d3206a39ce2bc2699a7ed1bd7adc1c6a140a
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---


### 2026-08-11: 租户 admin 账号硬编码密码漏洞修复及默认租户创建实施

- commit_type:            BugFix
- task_id:                security-fix
- task_name:              租户 admin 账号硬编码密码漏洞修复及默认租户创建实施
- commit_hash:            b94f27fbafc34ea83806150780645a86e1dca017
- branch:                 main
- remote:                 gitee
- staged_files:
  - README.md
  - company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
  - company-rag-web/src/main/resources/templates/index.html
  - docs/security-fixes/2026-08-11-remove-per-tenant-admin-fix.md
  - docs/security-fixes/2026-08-11-tenant-auto-associate-and-default-tenant.md
  - sql/migrations/V3__init_platform_admin.sql
- commit_message:         BugFix:security-fix_租户 admin 账号硬编码密码漏洞修复及默认租户创建实施：修复 createTenantWithSchema 移除硬编码 admin 创建，实现租户自动关联，添加 V3 初始化脚本和默认租户，同步更新 README 文档
- commit_command:         git commit -m "BugFix:security-fix_租户 admin 账号硬编码密码漏洞修复及默认租户创建实施：修复 createTenantWithSchema 移除硬编码 admin 创建，实现租户自动关联，添加 V3 初始化脚本和默认租户，同步更新 README 文档"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            b94f27fbafc34ea83806150780645a86e1dca017
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---


- commit_type:            BugFix
- task_id:                0000
- task_name:              修复 DatabaseQueryTool 跨租户访问漏洞
- commit_hash:            d8abdfe3b8c3d75a17775e5a3ad77bef5f8910a1
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/pom.xml
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
  - company-rag-agent/src/test/java/com/company/rag/agent/tool/DatabaseQueryToolTest.java
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java
  - docs/security-fixes/2026-08-11-database-query-tool-cross-tenant-fix.md
  - sql/migrations/V1__fix_tenant_isolation_security.sql
  - sql/migrations/V2__fix_database_query_tool_cross_tenant_access.sql
  - verification-result.md
- commit_message:         BugFix:0000_修复 DatabaseQueryTool 跨租户访问漏洞：fix cross-tenant data access vulnerability
- commit_command:         git commit -m "BugFix:0000_修复 DatabaseQueryTool 跨租户访问漏洞：fix cross-tenant data access vulnerability"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            d8abdfe3b8c3d75a17775e5a3ad77bef5f8910a1
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---


### 2026-08-10: README 数据库初始化文档更新

- commit_type:            Task
- task_id:                0000
- task_name:              untitled
- commit_hash:            6326be8e244aeeb11c5ec092b042f32074361d71
- branch:                 main
- remote:                 gitee
- staged_files:
  - README.md
  - sql/migrations/README.md
- commit_message:         Task:0000_untitled：update README.md with database initialization guide, add sql/migrations/README.md
- commit_command:         git commit -m "Task:0000_untitled：update README.md with database initialization guide, add sql/migrations/README.md"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            6326be8e244aeeb11c5ec092b042f32074361d71
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---


### 2026-08-10: database_query 安全修复与 Flyway 禁用

- commit_type:            Task
- task_id:                0000
- task_name:              untitled
- commit_hash:            4fb55794378053501cdd0bca56e0095417a91499
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-agent/src/test/java/com/company/rag/agent/tool/DatabaseQueryToolTest.java
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java
  - company-rag-bootstrap/src/main/resources/application-dev.yml
  - company-rag-bootstrap/src/main/resources/application.yml
  - company-rag-bootstrap/src/main/resources/db/migration/README.md
  - company-rag-bootstrap/src/main/resources/db/migration/V0__baseline.sql
  - company-rag-bootstrap/src/main/resources/db/migration/V1__fix_tenant_isolation_security.sql
  - sql/migrations/001-fix-tenant-isolation-security.sql
- commit_message:         Task:0000_untitled：add DatabaseQueryTool test,disable Flyway auto-config,update migration docs
- commit_command:         git commit -m "Task:0000_untitled：add DatabaseQueryTool test,disable Flyway auto-config,update migration docs"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            4fb55794378053501cdd0bca56e0095417a91499
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---


### 2026-08-10: 生产环境加固修复

- commit_type:            Task
- task_id:                0000
- task_name:              untitled
- commit_hash:            3affd08d8d1f6d716a0c350b073f89c0a8fe2447
- branch:                 main
- remote:                 gitee
- staged_files:
  - .env.example
  - README.md
  - company-rag-bootstrap/pom.xml
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java
  - company-rag-bootstrap/src/main/resources/application.yml
  - company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
  - docker-compose.yml
  - company-rag-bootstrap/src/main/resources/logback-spring.xml
  - company-rag-bootstrap/src/main/resources/db/migration/README.md
- commit_message:         Task:0000_untitled：fix SQL comment syntax and add production hardening
- commit_command:         git commit -m "Task:0000_untitled：fix SQL comment syntax and add production hardening"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            3affd08d8d1f6d716a0c350b073f89c0a8fe2447
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-10: RAG 检索优化修复

- commit_type:            BugFix
- task_id:                0000
- task_name:              RAG 检索优化修复
- commit_hash:            636deb7a0ca8c76927b23281c83b7d574fa78d95
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-rag/src/main/java/com/company/rag/rag/fusion/ResultFilter.java
  - company-rag-rag/src/main/java/com/company/rag/rag/fusion/ResultFuser.java
  - company-rag-rag/src/main/java/com/company/rag/rag/model/RagQuery.java
  - company-rag-rag/src/main/java/com/company/rag/rag/rerank/SiliconFlowRerankClient.java
  - company-rag-rag/src/main/java/com/company/rag/rag/service/RagCircuitBreakerConfig.java
  - company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java
  - company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java
  - company-rag-rag/src/test/java/com/company/rag/rag/rerank/SiliconFlowRerankClientTest.java
- commit_message:         BugFix:0000_RAG 检索优化修复：优化 ResultFilter 去重逻辑和 Rerank 超时/重试配置
- commit_command:         git commit -m "BugFix:0000_RAG 检索优化修复：优化 ResultFilter 去重逻辑和 Rerank 超时/重试配置"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            636deb7a0ca8c76927b23281c83b7d574fa78d95
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-09: 修复来源文档显示 bug

- commit_type:            BugFix
- task_id:                0000
- task_name:              修复来源文档显示 bug
- commit_hash:            f58df226131c48ceeab855674903b3fe1f7d7351
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-rag/src/main/java/com/company/rag/rag/retriever/impl/FullTextRetriever.java
  - company-rag-rag/src/main/java/com/company/rag/rag/retriever/impl/FuzzyRetriever.java
  - company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java
  - company-rag-bootstrap/src/main/resources/application.yml
  - company-rag-rag/src/main/java/com/company/rag/rag/router/ChatRouter.java
- commit_message:         BugFix:0000_修复来源文档显示 bug：修复 metadata 丢失和来源文档显示格式问题
- commit_command:         git commit -m "BugFix:0000_修复来源文档显示 bug：修复 metadata 丢失和来源文档显示格式问题"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            f58df226131c48ceeab855674903b3fe1f7d7351
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-09: SQL 语法错误修复

- commit_type:            BugFix
- task_id:                0000
- task_name:              SQL 语法错误修复
- commit_hash:            7b164b2908f1384c8330b7ff5dc6166bcd629c31
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-rag/src/main/java/com/company/rag/rag/retriever/impl/FullTextRetriever.java
  - company-rag-rag/src/main/java/com/company/rag/rag/retriever/impl/FuzzyRetriever.java
  - company-rag-tenant/src/main/java/com/company/rag/tenant/context/TenantSqlHelper.java
  - company-rag-tenant/src/test/java/com/company/rag/tenant/context/TenantSqlHelperTest.java
  - company-rag-document/pom.xml
  - company-rag-document/src/main/java/com/company/rag/document/service/impl/DocumentParseServiceImpl.java
  - company-rag-document/src/test/java/com/company/rag/document/service/DocumentParseServiceImplEventTest.java
- commit_message:         BugFix:0000_SQL 语法错误修复：修复 FullTextRetriever/FuzzyRetriever 的 SQL 拼接问题
- commit_command:         git commit -m "BugFix:0000_SQL 语法错误修复：修复 FullTextRetriever/FuzzyRetriever 的 SQL 拼接问题"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            7b164b2908f1384c8330b7ff5dc6166bcd629c31
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-08: 修复重排序重复调用

- commit_type:            BugFix
- task_id:                0001
- task_name:              修复重排序重复调用
- commit_hash:            c4d95e78b217130d43427221027c0ceb40035951
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java
- commit_message:         BugFix:0001_修复重排序重复调用：remove duplicate rerank call in MultiRetrieveService
- commit_command:         git commit -m "BugFix:0001_修复重排序重复调用：remove duplicate rerank call in MultiRetrieveService"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            c4d95e78b217130d43427221027c0ceb40035951
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-08: 修复缓存 Key 缺少参数维度

- commit_type:            BugFix
- task_id:                0001
- task_name:              修复缓存 Key 缺少参数维度
- commit_hash:            f34d40b55b18f263eb679cbba4d47d4fc4c7ada7
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-rag/src/main/java/com/company/rag/rag/cache/RagCacheManager.java
  - company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java
  - company-rag-rag/src/test/java/com/company/rag/rag/cache/RagCacheManagerTest.java
- commit_message:         BugFix:0001_修复缓存 Key 缺少参数维度：add topK/strategy/rerank to cache key and remove unused invalidateByDocument
- commit_command:         git commit -m "BugFix:0001_修复缓存 Key 缺少参数维度：add topK/strategy/rerank to cache key and remove unused invalidateByDocument"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            f34d40b55b18f263eb679cbba4d47d4fc4c7ada7
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 2026-08-08: Swagger UI 路径匹配修复

- commit_type:            BugFix
- task_id:                swagger-ui-fix
- task_name:              swagger-ui 路径匹配修复
- commit_hash:            ae27b2d3b346ee6fd6b7571580e2332e86f95f3d
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java
  - company-rag-bootstrap/src/main/resources/application.yml
  - company-rag-web/src/main/java/com/company/rag/web/config/WebMvcConfig.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/SpaFallbackController.java
  - company-rag-web/pom.xml
  - deploy-log.md
- commit_message:         BugFix:swagger-ui 路径匹配修复：fix SpaFallbackController regex pattern to exclude static resources
- commit_command:         git commit -m "BugFix:swagger-ui 路径匹配修复：fix SpaFallbackController regex pattern to exclude static resources"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            ae27b2d3b346ee6fd6b7571580e2332e86f95f3d
- result:                 推送成功，远端 HEAD 与本地提交哈希一致

---

### 之前的提交

- commit_type:            BugFix
- task_id:                0000
- task_name:              untitled
- commit_hash:            402cfc93ccf7bbd85fac2b51d2bcfad53fc3b6af
- branch:                 main
- remote:                 gitee
- staged_files:
  - company-rag-tenant/src/main/java/com/company/rag/tenant/service/TenantService.java
  - company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
  - company-rag-web/src/main/java/com/company/rag/web/controller/TenantController.java
- commit_message:         BugFix:0000_untitled：修复普通用户登录后查看所有租户问题，改为根据角色过滤租户列表
- commit_command:         git commit -m "BugFix:0000_untitled：修复普通用户登录后查看所有租户问题，改为根据角色过滤租户列表"
- commit_exit_code:       0
- push_command:           git push gitee main
- push_exit_code:         0
- remote_head_check_command: git ls-remote gitee main
- remote_head:            402cfc93ccf7bbd85fac2b51d2bcfad53fc3b6af
- result:                 推送成功，远端 HEAD 与本地提交哈希一致
