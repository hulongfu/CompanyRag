# OpenClaw Skill Engine 实施计划

**日期**: 2026-08-22  
**分支**: feature/openclaw-skill-engine  
**状态**: 待执行  
**关联设计文档**: [2026-08-21-openclaw-skill-engine-design.md](../specs/2026-08-21-openclaw-skill-engine-design.md)

---

## 📋 任务清单

### 阶段 1：核心框架（预计 3-5 天）

#### 1.1 创建模块结构
- [ ] 创建 `company-rag-skill` Maven 模块
- [ ] 配置 `pom.xml`（依赖：company-rag-agent, company-rag-common, Spring Boot）
- [ ] 创建包结构：
  - `com.company.rag.skill.engine` - Skill 引擎核心
  - `com.company.rag.skill.registry` - Skill 注册中心
  - `com.company.rag.skill.executor` - Skill 执行器
  - `com.company.rag.skill.model` - 数据模型
  - `com.company.rag.skill.exception` - 异常定义
  - `com.company.rag.skill.config` - 配置类

#### 1.2 实现数据模型
- [ ] `SkillDefinition` - Skill 元数据定义
  - [ ] 字段：name, displayName, description, type, tags, version, author, metadata
  - [ ] 类型枚举：`SkillType` (MCP_HOSTED, AGENT_NATIVE)
  - [ ] MCP 特有字段：mcpServerId, endpoint
  - [ ] Native 特有字段：skillMdPath, scriptsPath, referencesPath, assetsPath
- [ ] `SkillContext` - Skill 执行上下文
  - [ ] 字段：conversationId, userId, tenantId, userMessage, history, toolCallRecords, variables
- [ ] `SkillResult` - Skill 执行结果
  - [ ] 字段：success, skillName, data, message, citations, log, duration, timestamp
- [ ] `ExecutionLog` - 执行日志
  - [ ] 字段：steps, errors, metadata
- [ ] `StepLog` - 步骤日志
  - [ ] 字段：stepName, status, message, duration, output

#### 1.3 实现异常体系
- [ ] `SkillNotFoundException` - Skill 未找到异常
- [ ] `SkillExecutionException` - Skill 执行失败异常
- [ ] `InvalidSkillDefinitionException` - Skill 定义无效异常
- [ ] `McpSkillCallException` - MCP 调用失败异常

#### 1.4 实现 SkillRegistry（注册中心）
- [ ] `SkillRegistry` 接口定义
  - [ ] `registerSkill(SkillDefinition definition)`
  - [ ] `unregisterSkill(String skillName)`
  - [ ] `getSkill(String skillName)`
  - [ ] `listSkills()`
  - [ ] `listSkillsByTag(String tag)`
  - [ ] `listSkillsByType(SkillType type)`
- [ ] `SkillRegistryImpl` 实现类
  - [ ] 使用 `ConcurrentHashMap` 存储 Skill 定义
  - [ ] 支持线程安全的注册和注销
  - [ ] 实现按标签、类型查询

#### 1.5 实现 SkillEngine（引擎核心）
- [ ] `SkillEngine` 接口定义
  - [ ] `executeSkill(String skillName, SkillContext context)`
  - [ ] `registerSkill(SkillDefinition definition)`
  - [ ] `listSkills()`
- [ ] `SkillEngineImpl` 实现类
  - [ ] 注入 `SkillRegistry` 和 `SkillExecutor`
  - [ ] 实现执行逻辑：查找 Skill → 路由到对应执行器 → 返回结果
  - [ ] 添加指标埋点（调用次数、成功率、耗时）
  - [ ] 添加日志记录

#### 1.6 实现 SkillExecutor（Native 执行器）
- [ ] `SkillExecutor` 接口定义
  - [ ] `execute(SkillDefinition definition, SkillContext context)`
- [ ] `NativeSkillExecutor` 实现类
  - [ ] 解析 `SKILL.md` 文件（Front Matter 元数据）
  - [ ] 解析执行步骤（steps）
  - [ ] 执行 Python 脚本（使用 `ProcessBuilder`）
  - [ ] 调用工具（通过 `AgentToolRegistry`）
  - [ ] 渲染模板（assets/ 目录）
  - [ ] 组合结果并返回

#### 1.7 配置文件
- [ ] `application-skill.yml` - Skill 配置
  ```yaml
  skill:
    enabled: true
    base-path: ./agent_skills  # Skill 目录
    timeout: 30s             # 默认超时
    retry:
      max-attempts: 3
      initial-interval: 1s
      multiplier: 2.0
    python:
      interpreter: python3   # Python 解释器路径
      sandbox-enabled: true  # 启用沙箱
  ```

#### 1.8 单元测试
- [ ] `SkillRegistryTest` - 注册中心测试
  - [ ] 测试注册、查询、注销
  - [ ] 测试按标签、类型过滤
- [ ] `SkillEngineTest` - 引擎核心测试
  - [ ] 测试执行流程
  - [ ] 测试异常处理
- [ ] `NativeSkillExecutorTest` - Native 执行器测试
  - [ ] 测试 SKILL.md 解析
  - [ ] 测试 Python 脚本执行
  - [ ] 测试工具调用组合

**阶段 1 交付物**:
- ✅ `company-rag-skill` 模块可编译
- ✅ `SkillEngine` 可运行（支持 AGENT_NATIVE 类型）
- ✅ 单元测试覆盖率 > 80%

---

### 阶段 2：MCP 集成（预计 2-3 天）

#### 2.1 扩展 company-rag-mcp-client
- [ ] 添加 Skill 调用方法
  - [ ] `callSkill(String serverId, String skillName, Map<String, Object> params)`
  - [ ] 构建 JSON-RPC 2.0 请求（方法名：`skill/execute`）
  - [ ] 序列化 `SkillContext` 为 JSON
  - [ ] 解析响应为 `SkillResult`

#### 2.2 实现 McpSkillClient
- [ ] `McpSkillClient` 类
  - [ ] 注入 `HttpMcpClient`（复用现有 MCP 客户端）
  - [ ] 实现 `execute(SkillDefinition definition, SkillContext context)`
  - [ ] 处理超时（默认 30 秒）
  - [ ] 处理错误（重试、降级）

#### 2.3 创建示例 MCP Skill Server
- [ ] 创建 `company-rag-mcp-skill-code-review` 目录
- [ ] 编写 `SKILL.md` 文件
  - [ ] 定义元数据（name, description, tags, type=MCP_HOSTED）
  - [ ] 定义 MCP endpoint
  - [ ] 定义输入输出格式
- [ ] 实现 MCP Server（FastMCP 框架）
  - [ ] 注册 `skill/execute` 方法
  - [ ] 实现代码审查逻辑
  - [ ] 返回结构化结果

#### 2.4 集成测试
- [ ] `McpSkillClientTest` - MCP 客户端测试
  - [ ] 测试连接到 MCP Skill Server
  - [ ] 测试调用 Skill
  - [ ] 测试错误处理
- [ ] `SkillEngineMcpIntegrationTest` - 引擎集成测试
  - [ ] 测试 MCP_HOSTED Skill 执行
  - [ ] 测试降级策略

**阶段 2 交付物**:
- ✅ MCP Hosted Skill 可调用
- ✅ 示例 Skill：`code-review` 可运行
- ✅ 集成测试通过

---

### 阶段 3：完善功能（预计 3-5 天）

#### 3.1 错误处理和重试机制
- [ ] 实现 `SkillFallbackHandler`
  - [ ] 记录失败日志
  - [ ] MCP 失败时尝试切换到 Native 模式
  - [ ] 返回友好错误消息
- [ ] 配置 Resilience4j 重试策略
  - [ ] 最大重试次数：3
  - [ ] 指数退避：1s, 2s, 4s
  - [ ] 仅重试 `SkillExecutionException`

#### 3.2 可观测性
- [ ] `SkillMetrics` 指标类
  - [ ] Counter: `skill.calls.total` - 调用总次数
  - [ ] Gauge: `skill.success.rate` - 成功率
  - [ ] Timer: `skill.execution.duration` - 执行耗时
  - [ ] DistributionSummary: `skill.type.distribution` - 类型分布
- [ ] 添加 Micrometer 埋点到 `SkillEngineImpl`
- [ ] 创建 Grafana 面板
  - [ ] Skill 调用趋势（按类型、按名称）
  - [ ] Skill 执行成功率
  - [ ] Skill 执行耗时分布（P50, P90, P99）
  - [ ] Skill 错误类型分布

#### 3.3 编写文档
- [ ] `SKILL.md 编写指南.md`
  - [ ] 文件格式说明（Front Matter, 提示词，步骤）
  - [ ] 示例：code-review, data-report, article-creator
  - [ ] 最佳实践
- [ ] `Skill 开发文档.md`
  - [ ] 如何创建新 Skill
  - [ ] 如何调试 Skill
  - [ ] 如何测试 Skill
- [ ] `Skill API 参考.md`
  - [ ] 接口定义
  - [ ] 数据模型
  - [ ] 异常类型

#### 3.4 创建 Skill 模板
- [ ] `agent_skills/templates/code-review/`
  - [ ] `SKILL.md` - 代码审查 Skill 定义
  - [ ] `scripts/parse_code.py` - 代码解析脚本
  - [ ] `scripts/analyze_quality.py` - 质量分析脚本
  - [ ] `assets/report-template.md` - 报告模板
- [ ] `agent_skills/templates/data-report/`
  - [ ] `SKILL.md` - 数据报表 Skill 定义
  - [ ] `scripts/query_data.py` - 数据查询脚本
  - [ ] `scripts/generate_chart.py` - 图表生成脚本
  - [ ] `assets/dashboard-template.md` - 仪表板模板
- [ ] `agent_skills/templates/article-creator/`
  - [ ] `SKILL.md` - 文章创作 Skill 定义
  - [ ] `scripts/research_topic.py` - 主题研究脚本
  - [ ] `scripts/draft_outline.py` - 大纲生成脚本
  - [ ] `assets/article-template.md` - 文章模板

**阶段 3 交付物**:
- ✅ 完整的错误处理和重试机制
- ✅ Grafana 可观测性面板
- ✅ Skill 开发文档和模板

---

### 阶段 4：试点应用（预计 2-3 天）

#### 4.1 创建实际 Skill
- [ ] **代码审查 Skill** (`code-review`)
  - [ ] 功能：对提交的代码进行自动化审查
  - [ ] 输入：代码内容、编程语言
  - [ ] 输出：问题列表、改进建议、质量评分
  - [ ] 类型：MCP_HOSTED
- [ ] **数据报表 Skill** (`data-report`)
  - [ ] 功能：生成数据分析和可视化报表
  - [ ] 输入：数据源、分析维度、时间范围
  - [ ] 输出：报表数据、图表、分析结论
  - [ ] 类型：AGENT_NATIVE
- [ ] **文章创作 Skill** (`article-creator`)
  - [ ] 功能：基于主题创作文章
  - [ ] 输入：主题、字数要求、风格
  - [ ] 输出：文章草稿、引用来源
  - [ ] 类型：AGENT_NATIVE

#### 4.2 集成到 RagAgentService
- [ ] 修改 `RagAgentService.processWithHistory()`
  - [ ] 注入 `SkillEngine`
  - [ ] LLM 判断是否需要调用 Skill
  - [ ] 调用 `SkillEngine.executeSkill()`
  - [ ] 将结果整合到回复中
- [ ] 更新 Prompt
  - [ ] 添加 Skill 调用说明
  - [ ] 添加 Skill 列表和描述

#### 4.3 用户测试
- [ ] 邀请 2-3 名用户试用
- [ ] 收集反馈：
  - [ ] Skill 调用是否流畅
  - [ ] 执行结果是否满意
  - [ ] 性能是否可接受
- [ ] 记录问题并优化

#### 4.4 性能优化
- [ ] 分析性能瓶颈（使用 Micrometer 指标）
- [ ] 优化 Python 脚本执行（缓存、并行）
- [ ] 优化 MCP 调用（连接池、异步）
- [ ] 添加缓存机制（相同输入返回缓存结果）

**阶段 4 交付物**:
- ✅ 3 个生产级 Skill
- ✅ 集成到 Agent 服务
- ✅ 用户测试反馈报告
- ✅ 性能优化报告

---

## 📅 时间线

| 阶段 | 预计时间 | 开始日期 | 结束日期 | 状态 |
|------|----------|----------|----------|------|
| 阶段 1：核心框架 | 3-5 天 | 2026-08-22 | 2026-08-26 | 待执行 |
| 阶段 2：MCP 集成 | 2-3 天 | 2026-08-27 | 2026-08-29 | 待执行 |
| 阶段 3：完善功能 | 3-5 天 | 2026-08-30 | 2026-09-03 | 待执行 |
| 阶段 4：试点应用 | 2-3 天 | 2026-09-04 | 2026-09-06 | 待执行 |

**总预计时间**: 10-16 天  
**预计完成日期**: 2026-09-06

---

## 🎯 成功标准

### 功能完整性
- [ ] 支持 MCP_HOSTED 和 AGENT_NATIVE 两种 Skill 类型
- [ ] Skill 可以成功调用并返回结果
- [ ] 错误处理和重试机制正常工作
- [ ] 可观测性面板可用

### 代码质量
- [ ] 单元测试覆盖率 > 80%
- [ ] 集成测试全部通过
- [ ] 代码符合项目规范（参考 `.gientech/harness/conventions.md`）
- [ ] 无严重安全漏洞

### 性能指标
- [ ] 简单 Skill 执行时间 < 5 秒
- [ ] 复杂 Skill 执行时间 < 30 秒
- [ ] MCP 调用成功率 > 95%
- [ ] Skill 调用成功率 > 90%

### 用户体验
- [ ] Skill 调用对 LLM 透明（无感知）
- [ ] 错误消息友好且具体
- [ ] 用户测试满意度 > 80%

---

## ⚠️ 风险与缓解

### 风险 1：Python 脚本执行安全
**影响**: 高  
**可能性**: 中  
**缓解措施**:
- 使用沙箱环境（限制文件系统、网络访问）
- 代码审查和签名机制
- 使用 `subprocess` 隔离执行
- 添加超时保护

### 风险 2：Skill 执行性能
**影响**: 中  
**可能性**: 中  
**缓解措施**:
- 异步执行（SSE 流式返回进度）
- 超时控制（默认 30 秒）
- 缓存机制（相同输入返回缓存结果）
- 性能监控和告警

### 风险 3：MCP 协议兼容性
**影响**: 中  
**可能性**: 低  
**缓解措施**:
- 严格遵循 MCP 协议规范
- 添加协议版本检查
- 提供降级策略（切换到 Native 模式）

### 风险 4：Skill 管理复杂度
**影响**: 低  
**可能性**: 中  
**缓解措施**:
- Skill 分类和标签
- 版本管理
- 依赖管理
- 提供 Skill 模板和示例

---

## 📦 交付物清单

### 代码
- [ ] `company-rag-skill` 模块
- [ ] `company-rag-mcp-skill-code-review` 示例 MCP Skill
- [ ] `agent_skills/templates/` 目录（3 个 Skill 模板）

### 文档
- [ ] `SKILL.md 编写指南.md`
- [ ] `Skill 开发文档.md`
- [ ] `Skill API 参考.md`
- [ ] 用户测试报告
- [ ] 性能优化报告

### 配置
- [ ] `application-skill.yml` 配置文件
- [ ] Grafana 仪表板 JSON 导出
- [ ] Prometheus 告警规则

### 测试
- [ ] 单元测试（覆盖率 > 80%）
- [ ] 集成测试（全部通过）
- [ ] 端到端测试（性能测试）

---

## 🔗 相关链接

- [设计文档](../specs/2026-08-21-openclaw-skill-engine-design.md)
- [Hermes Agent 可行性评估](../specs/2026-08-21-hermes-agent-feasibility-assessment.md)
- [MCP Server 实施计划](./2026-08-16-mcp-server-implementation.md)
- [MCP Client 完成计划](./2026-08-18-mcp-client-completion.md)

---

**文档版本**: 1.0  
**创建日期**: 2026-08-22  
**最后更新**: 2026-08-22  
**状态**: 待执行  
**负责人**: 待分配
