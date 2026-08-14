## Git Push

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
