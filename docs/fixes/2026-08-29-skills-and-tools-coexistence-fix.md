# Skills 与 Tools 同时启用问题修复报告

## 问题描述

**时间**: 2026-08-29  
**问题**: 用户反馈 Agent 无法同时支持 Skills 和 Tools，认为 `SkillsAgentHook` 会覆盖 `ToolCallbackProvider` 提供的工具定义。

**用户期望的优先级**：
1. 用户问题 → 先匹配 Skills（如果匹配）→ 执行 Skill（Skill 内部可调用 Tools）
2. 用户问题 → 无匹配 Skill → 直接调用 Tools（如 searchKnowledgeBase）
3. 涉及知识查询相关的，优先尝试调用知识库查询工具

## Phase 1: 根因调查

### 初始错误判断

我最初在没有完整调查的情况下，错误地认为：
- `SkillsAgentHook` 会覆盖 `ToolCallbackProvider` 的工具定义
- 解决方案是移除 `SkillsAgentHook`

**这是完全错误的判断！** 违背了 `systematic-debugging-preset` 的核心原则：**NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST**

### 正确的调查方法

用户添加了自定义 System Prompt 后，我通过完整日志分析发现：

#### 1. System Prompt 成功注入（日志行 980）
```
你是一个智能助手，可调用各种 skill 与 tool 完成任务。
接收到用户请求后，先列出自己所有的 skill 与 tool，
然后根据请求的匹配度选择合适的 skill 或 tool 调用来完成任务，
也可同时调用多个 skill 与 tool 来配合完成任务。
注意：涉及知识查询相关的，请优先尝试调用知识库查询工具
```

#### 2. Tools 工作正常 ✅
- **行 1050**: `toolCalls=[ToolCall[name=searchKnowledgeBase, ...]]`
- **行 1200**: 再次调用 `searchKnowledgeBase`（不同关键词）
- **行 1352**: 第三次调用 `searchKnowledgeBase`

#### 3. Skills 也工作正常 ✅
- **行 1635**: 另一个会话（计算问题）
- **行 1705**: `toolCalls=[ToolCall[name=read_skill, arguments={"skill_name": "calculator"}]]`
- **行 1783**: `toolCalls=[ToolCall[name=custom_execute_command, ...]]`

#### 4. 优先级符合预期 ✅
- **知识查询问题**（行 1050）：直接调用 `searchKnowledgeBase` 工具
- **计算问题**（行 1705）：先调用 `read_skill` 读取技能说明，再调用 `custom_execute_command` 执行

## 核心结论

`SkillsAgentHook` **并不会覆盖** `ToolCallbackProvider` 的工具定义。实际上：

| 组件 | 作用 | 状态 |
|------|------|------|
| `SkillsAgentHook` | 注入 System Prompt，使 LLM 知道 Skills | ✅ 正常工作 |
| `ToolCallbackProvider` | 注册 Tools 定义 | ✅ 正常工作 |
| System Prompt | 指导 LLM 优先调用策略 | ✅ 成功注入 |
| LLM | 根据请求类型自主选择 | ✅ 正确决策 |

### 关键发现

**之前的 System Prompt 为空**（显示为 `.`），导致 LLM 没有收到调用指导。  
**添加自定义 System Prompt 后**，LLM 能够正确理解：
1. 可以同时调用 Skills 和 Tools
2. 根据请求类型自主选择
3. 涉及知识查询时优先调用知识库工具

## 修复方案

### 修改文件
`company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java`

### 关键代码（行 87-96）
```java
ReactAgent agent = ReactAgent.builder()
        .name("rag-agent")
        .systemPrompt("你是一个智能助手，可调用各种 skill 与 tool 完成任务。" +
                "接收到用户请求后，先列出自己所有的 skill 与 tool，然后根据请求的匹配度选择合适的 skill 或 tool 调用来完成任务，" +
                "也可同时调用多个 skill 与 tool 来配合完成任务。" +
                "注意：涉及知识查询相关的，请优先尝试调用知识库查询工具")
        .model(chatModel)
        .toolCallbackProviders(toolCallbackProvider)
        .hooks(List.of(skillsHook))  // 添加 SkillsAgentHook，使 Agent 能调用技能
        .enableLogging(true)
        .build();
```

### 修复要点
1. ✅ 保留 `SkillsAgentHook`（启用 Skills 功能）
2. ✅ 保留 `ToolCallbackProvider`（启用 Tools 功能）
3. ✅ 添加 `.systemPrompt()` 指导 LLM 调用策略
4. ✅ 明确说明知识查询优先调用 `searchKnowledgeBase`

## 验证结果

### 测试场景 1: 知识查询问题
**用户输入**: "我的阿里云 API KEY 是多少？本地知识库有记录吗？"

**Agent 行为**:
1. Round 0: 调用 `searchKnowledgeBase`（关键词："阿里云 API KEY"）
2. Round 1: 调用 `searchKnowledgeBase`（关键词："阿里云 API 密钥 key 配置"）
3. Round 2: 调用 `searchKnowledgeBase`（关键词："API KEY 密钥 环境变量 配置"）
4. Round 3: 基于工具返回生成最终答案

**结果**: ✅ 符合预期，优先调用知识库查询工具

### 测试场景 2: 计算问题
**用户输入**: "计算 9999 * 777.2 / 76.2"

**Agent 行为**:
1. Round 0: 调用 `read_skill` 读取 calculator 技能说明
2. Round 1: 调用 `custom_execute_command` 执行 Python 脚本计算

**结果**: ✅ 符合预期，使用 Skill 完成任务

## 教训与反思

### 我犯的错误
1. **没有完整调查就下结论**：违背了 systematic-debugging-preset 的核心原则
2. **错误假设**：认为 `SkillsAgentHook` 会覆盖 Tools，但日志证明完全兼容
3. **快速修复**：直接移除 `SkillsAgentHook`，而不是深入调查真正的问题

### 正确的做法
1. ✅ **收集完整证据**：通过日志分析 System Prompt、toolCalls、实际调用行为
2. ✅ **对比分析**：对比修复前后的日志差异
3. ✅ **验证假设**：用实际测试数据验证每个假设
4. ✅ **遵循流程**：严格按照 systematic-debugging-preset 的四个阶段执行

## 相关文件

- `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java` - Agent 配置
- `D:\MyTemp\tmpLog.txt` - 测试日志（4044 行）
- `docs/fixes/2026-08-25-skill-fix-verification.md` - 之前 Skills 功能修复记录

## 总结

**问题根源**：不是 `SkillsAgentHook` 和 `ToolCallbackProvider` 冲突，而是缺少自定义 System Prompt 指导 LLM。

**解决方案**：添加 `.systemPrompt()` 方法，明确告诉 LLM 如何同时使用 Skills 和 Tools。

**验证结果**：Skills 和 Tools 同时工作正常，优先级符合预期。

**关键教训**：永远不要在没有完整调查之前就下结论并尝试修复。

## 附录：相关错误修复

### 错误 1: No ToolCallback found for tool name: custom_execute_command

**现象**：
```
java.lang.IllegalStateException: No ToolCallback found for tool name: custom_execute_command
```

**根因**：calculator 技能的 SKILL.md 中定义使用 `execute` 工具，但项目中没有注册该工具。

**解决方案**：创建 `ExecuteTool.java` 实现 `AgentTool` 接口，提供安全的命令执行功能。

**安全限制**：
- 只允许执行 `python` 和 `python3` 命令
- 禁止危险命令（rm、del、format 等）
- 命令超时限制（30 秒）
- 禁止 shell 注入（bash -c、sh -c）

**文件**：`company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`

---

### 错误 2: ExecuteTool 安全策略过严导致 web-search 技能失败

**现象**（2026-08-29 重启后测试）：
```
不允许的命令前缀，只允许执行 python/python3 命令：
D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe skills/web-search/scripts/search_tool.py ...
检测到危险命令，拒绝执行
```

**根因**：
- web-search 技能使用虚拟环境的 Python 解释器：`D:/uv_project/mcp-server-docker/.venv/Scripts/python.exe`
- ExecuteTool 的安全检查只允许 `python ` 或 `python3 ` 开头的命令
- 虚拟环境的 Python 完整路径被误判为危险命令

**修复方案**：
1. **支持多种 Python 命令格式**：
   - `python ` / `python3 ` - 系统 PATH 中的 Python
   - `*.exe` - Windows 上的 Python 可执行文件（如虚拟环境）
   - `/path/to/python` - Unix 上的 Python 可执行文件路径

2. **增加超时时间**：从 30 秒增加到 60 秒（web-search 等网络请求需要更长时间）

3. **改进安全检测**：
   - 仍禁止危险命令（rm、del、format 等）
   - 允许 Python 相关命令的多种格式
   - 禁止 shell 注入（bash -c、sh -c）

**修改文件**：
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`
  - 新增 `isPythonCommand()` 方法，支持多种 Python 命令格式
  - 修改 `isCommandSafe()` 方法，调用新的判断逻辑
  - 超时时间从 30 秒增加到 60 秒

**验证**：
- ✅ 编译成功
- ✅ web-search 技能现在可以使用虚拟环境 Python
- ✅ calculator 技能继续正常工作
- ✅ 安全限制仍然有效（危险命令被禁止）
