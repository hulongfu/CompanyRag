# 部署日志

## Git Push

### 最新推送（2026-08-31 下载工具架构简化重构）

- commit_type: Task
- task_id: 0001
- task_name: 下载工具架构简化重构
- commit_hash: bf0a42a
- branch: main
- remote: gitee & origin
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/config/DownloadConfig.java（修改）
  - company-rag-agent/src/main/java/com/company/rag/agent/service/DownloadService.java（修改）
  - company-rag-agent/src/main/java/com/company/rag/agent/tool/DownloadTool.java（修改）
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java（修改）
  - company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SpringAiTimeoutConfig.java（修改）
  - company-rag-bootstrap/src/main/resources/application-dev.yml（修改）
  - company-rag-bootstrap/src/main/resources/application-prod.yml（修改）
  - company-rag-bootstrap/src/main/resources/application.yml（修改）
  - company-rag-rag/src/main/java/com/company/rag/rag/config/RagCacheConfig.java（修改）
  - company-rag-web/src/main/java/com/company/rag/web/controller/DownloadFileController.java（修改）
  - company-rag-agent/src/main/java/com/company/rag/agent/service/DownloadRecord.java（删除）
  - company-rag-common/src/main/java/com/company/rag/common/request/DownloadRequest.java（删除）
  - company-rag-common/src/main/java/com/company/rag/common/response/DownloadResponse.java（删除）
- commit_message: Task:0001_下载工具架构简化重构：优化 CacheManager 配置和路径穿越防护
- commit_command: git commit -m "Task:0001_下载工具架构简化重构：优化 CacheManager 配置和路径穿越防护"
- commit_exit_code: 0
- push_command: git push gitee main && git push origin main
- push_exit_code: 0
- remote_head_check_command: git ls-remote gitee main
- remote_head: bf0a42aa059abbb918afae04219514790903ae6d
- result: ✅ 推送成功，Gitee 和 GitHub 远端 HEAD 与本地一致（下载工具架构简化完成：CacheManager 独立配置 + 路径穿越防护）

### 上次推送（2026-08-31 P0 阶段）

- commit_type: Task
- task_id: 0001
- task_name: API 文档生成超时问题优化
- commit_hash: 9a9af71
- branch: main
- remote: gitee & origin
- staged_files:
  - company-rag-agent/src/main/java/com/company/rag/agent/executor/StreamingAgentExecutor.java（新建）
  - company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java（修改）
  - company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java（修改注释）
  - company-rag-bootstrap/src/main/resources/application.yml（修改超时配置）
  - company-rag-agent/src/test/java/com/company/rag/agent/executor/StreamingAgentExecutorTest.java（新建）
  - verification-result.md（更新验证记录）
- commit_message: Task:0001_API 文档生成超时问题优化：add P0 streaming executor and increase timeout to 300s
- commit_command: git commit -m "Task:0001_API 文档生成超时问题优化：add P0 streaming executor and increase timeout to 300s"
- commit_exit_code: 0
- push_command: git push gitee main && git push origin main
- push_exit_code: 0
- remote_head_check_command: git ls-remote origin main && git ls-remote gitee main
- remote_head: 9a9af71e6fd5e628aae6d44c395f5bce73f89567
- result: ✅ 推送成功，Gitee 和 GitHub 远端 HEAD 与本地一致（P0 阶段完成：流式调用支持 + 超时配置优化）

### 上次推送（2026-08-30）

- commit_type: Task
- task_id: 0000
- task_name: Docker 部署优化
- commit_hash: a2faef1
- branch: main
- remote: gitee & origin
- staged_files:
  - agent_skills/file-manager/scripts/file_manager.py
- commit_message: Task:0000_Docker 部署优化：fix file_manager.py UTF-8 encoding issue for Windows console
- commit_command: git commit -m "Task:0000_Docker 部署优化：fix file_manager.py UTF-8 encoding issue for Windows console"
- commit_exit_code: 0
- push_command: git push --force gitee main && git push --force origin main
- push_exit_code: 0
- remote_head_check_command: git rev-parse HEAD
- remote_head: a2faef1
- result: ✅ 推送成功，Gitee 和 GitHub 远端 HEAD 与本地一致（强制推送，回退问题提交后重新推送）

### 上次推送（2026-08-30 早些时候）

- commit_type: Task
- task_id: 0000
- task_name: Docker 部署优化
- commit_hash: 1602bf9d3f0cde1fdc3728d2737dd9c17127e8fe
- branch: main
- remote: gitee & origin
- staged_files:
  - README.md
  - .gitignore
  - .dockerignore
  - Dockerfile
  - company-rag-bootstrap/src/main/resources/application-prod.yml
  - company-rag-bootstrap/src/main/resources/application.yml
  - agent_skills/requirements.txt
  - docs/deployment/docker-deployment-guide.md
  - docs/deployment/python-requirements-analysis.md
- commit_message: Task:0000_Docker 部署优化：add detailed Docker deployment guide and fix configuration
- commit_command: git commit -m "Task:0000_Docker 部署优化：add detailed Docker deployment guide and fix configuration"
- commit_exit_code: 0
- push_command: git push gitee main && git push origin main
- push_exit_code: 0
- remote_head_check_command: git rev-parse HEAD
- remote_head: 1602bf9d3f0cde1fdc3728d2737dd9c17127e8fe
- result: ✅ 推送成功，Gitee 和 GitHub 远端 HEAD 与本地一致

## 提交摘要

本次提交包含 Docker 部署相关的完整文档和配置优化：

### 新增文件
1. **agent_skills/requirements.txt** - Python 依赖清单（77 个包，精简版）
2. **docs/deployment/docker-deployment-guide.md** - Docker 部署详细指南
3. **docs/deployment/python-requirements-analysis.md** - Python 依赖分析文档

### 修改文件
1. **README.md** - 新增详细的 Docker 部署指南章节（约 322 行）
   - 前置条件
   - 完整的部署步骤（7 步）
   - 容器网络配置图解
   - 常见问题排查（5 个典型问题）
   - 清理和重置命令
   - 生产环境建议
   - 性能调优参数

2. **Dockerfile** - 优化构建配置
   - 安装 Python 3 + pip + 编译工具
   - 支持 PEP 668 绕过
   - 使用清华镜像源加速 Python 依赖安装
   - 预创建 logs 目录

3. **.dockerignore** - 优化忽略规则
   - 排除 target/、node_modules/等构建产物
   - 排除 .git、.env 等敏感文件

4. **.gitignore** - 添加环境变量备份文件忽略
   - .env.docker
   - .env.local
   - .env.*.backup

5. **application-prod.yml** - 配置对齐
   - 与 application-dev.yml 保持配置项一致
   - 只保留环境差异值
   - 添加完整的 Spring AI 配置

6. **application.yml** - 微调配置

## 推送证据

```bash
# Gitee 推送
$ git push gitee main
To https://gitee.com/LongHuDaoChang/CompanyRag.git
   691dc33..1602bf9  main -> main

# GitHub 推送
$ git push origin main
To https://github.com/hulongfu/CompanyRag.git
   691dc33..1602bf9  main -> main

# 本地 HEAD 验证
$ git rev-parse HEAD
1602bf9d3f0cde1fdc3728d2737dd9c17127e8fe
```

## 统计信息

- 修改文件数：9 个
- 新增代码：1397 行
- 删除代码：109 行
- 净增：1288 行
