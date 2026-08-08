## Git Push

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
