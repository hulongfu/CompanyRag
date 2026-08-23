# Spring AI Alibaba Skill Engine 实施计划

**日期**: 2026-08-23  
**关联设计文档**: `docs/superpowers/specs/2026-08-23-spring-ai-alibaba-skill-engine-design.md`  
**预计工期**: 3 天  

---

## 阶段 1：环境准备（0.5 天）

### 任务 1.1：添加 Spring AI Alibaba 依赖

**文件**: `pom.xml`, `company-rag-bootstrap/pom.xml`

**改动**:
1. 在父 `pom.xml` 中添加 Spring AI Alibaba BOM
2. 在 `company-rag-bootstrap/pom.xml` 中添加 `spring-ai-alibaba-agent-framework` 依赖

**验收**:
- [ ] `mvn dependency:tree` 无版本冲突
- [ ] 项目能正常编译

### 任务 1.2：创建 Skill 目录结构

**操作**:
1. 在项目根目录创建 `./agent_skills` 目录
2. 创建示例 Skill：`calculator`
3. 编写 `SKILL.md` 和 `scripts/calculator.py`

**验收**:
- [ ] `./agent_skills/calculator/SKILL.md` 存在
- [ ] `./agent_skills/calculator/scripts/calculator.py` 存在

---

## 阶段 2：配置 ReactAgent（0.5 天）

### 任务 2.1：创建 AgentConfig 配置类

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java`

**内容**:
- 配置 `ReactAgent` Bean
- 配置 `FileSystemSkillRegistry` 扫描路径
- 注入 `ToolCallbackProvider`

**验收**:
- [ ] `ReactAgent` Bean 可注入
- [ ] 日志显示 Skill 扫描成功

### 任务 2.2：配置 application.yml

**文件**: `company-rag-bootstrap/src/main/resources/application-dev.yml`

**内容**:
```yaml
spring:
  ai:
    alibaba:
      agent:
        enabled: true
        skill:
          registry:
            file-system:
              enabled: true
              paths:
                - ./agent_skills
```

**验收**:
- [ ] 配置文件语法正确
- [ ] 应用启动无配置错误

---

## 阶段 3：改造 RagAgentService（1 天）

### 任务 3.1：修改 RagAgentService 使用 ReactAgent

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**改动**:
1. 将 `ChatClient` 改为 `ReactAgent`
2. 修改 `processWithHistory` 方法调用 `reactAgent.processWithHistory()`
3. 保留现有日志和追踪逻辑

**验收**:
- [ ] 单元测试通过
- [ ] Agent 能正常处理用户请求

### 任务 3.2：适配 AgentResult 返回格式

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/service/AgentResult.java`

**改动**:
- 确保 `AgentResult` 包含 `toolCalls` 字段
- 确保 `ReactAgent` 返回结果能正确转换

**验收**:
- [ ] 返回格式符合设计文档
- [ ] 工具调用记录完整

---

## 阶段 4：创建示例 Skill（0.5 天）

### 任务 4.1：编写 Calculator Skill

**文件**: `./agent_skills/calculator/SKILL.md`

**内容**:
- YAML Front Matter（name, description, read_when）
- 执行说明（如何使用 Python 脚本）
- 示例交互

**文件**: `./agent_skills/calculator/scripts/calculator.py`

**内容**:
- Python 脚本，支持四则运算
- 命令行参数解析
- 错误处理

**验收**:
- [ ] Agent 能发现 calculator Skill
- [ ] Agent 能调用 calculator Skill 执行计算

### 任务 4.2：测试 Skill 调用

**测试**:
- 手动测试：通过 API 发送请求 "计算 100 * 25"
- 验证：返回结果包含 "2500"

**验收**:
- [ ] 手动测试通过
- [ ] 日志显示 Skill 调用链路

---

## 阶段 5：集成测试（0.5 天）

### 任务 5.1：编写集成测试

**文件**: `company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceIntegrationTest.java`

**测试用例**:
1. `testProcessWithSkill()`: 测试 Skill 调用
2. `testProcessWithTool()`: 测试 Tool 调用
3. `testSkillAndToolCollaboration()`: 测试 Skill 和 Tool 协同

**验收**:
- [ ] 所有测试用例通过
- [ ] 测试覆盖率 > 80%

### 任务 5.2：性能测试和优化

**测试**:
- 压测：100 并发请求
- 监控：P90 耗时 < 3 秒

**验收**:
- [ ] 性能指标达标
- [ ] 无内存泄漏

---

## 风险与缓解

### 风险 1：依赖冲突

**风险**: Spring AI Alibaba 与现有 Spring AI 1.0.4 冲突

**缓解**:
- 先运行 `mvn dependency:tree` 分析
- 选择兼容版本（1.0.0.4+）

### 风险 2：Skill 执行安全

**风险**: Python 脚本执行存在安全风险

**缓解**:
- 限制脚本访问权限
- 禁止访问文件系统、网络
- 代码审查

### 风险 3：Agent 决策准确性

**风险**: Agent 错误选择 Skill 或 Tool

**缓解**:
- 优化 SKILL.md 的 description 和 read_when
- 监控和日志分析

---

## 交付物清单

1. ✅ `pom.xml` 添加 Spring AI Alibaba 依赖
2. ✅ `AgentConfig.java` 配置类
3. ✅ `application.yml` Skill Registry 配置
4. ✅ `RagAgentService.java` 改造完成
5. ✅ `./agent_skills/calculator/` 示例 Skill
6. ✅ 集成测试用例
7. ✅ 性能测试报告

---

**下一步**: 等待用户确认后开始实施
