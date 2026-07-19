# Agent 模块

## 概述

Agent 模块基于 Spring AI 实现智能工具调用编排，支持 LLM 自动识别用户意图并选择合适工具执行。

## 工作流程

```
用户提问 → LLM 意图分析 → 是否需要工具？
  ├─ 不需要 → LLM 直接回答
  └─ 需要 → 选择工具 → 执行工具 → 结果反馈 LLM → 生成最终回答
```

## 工具注册中心 (AgentToolRegistry)

自动发现并注册所有 `AgentTool` 实现：

- `register(tool)` — 注册工具
- `getTool(name)` — 按名称获取工具
- `listTools()` — 列出所有工具（名称、描述、参数 schema）
- `executeTool(name, params)` — 执行指定工具

## 可用工具

| 工具 | 名称 | 说明 |
|------|------|------|
| DatabaseQueryTool | database_query | 自然语言查询业务数据 |
| CodeSearchTool | code_search | 在项目源码中搜索代码片段 |
| ApiDocTool | api_doc | 动态扫描 Spring 端点生成 API 文档 |

## Agent 编排服务 (RagAgentService)

`process(userMessage, toolContext)` 方法：

1. 获取工具列表，构建 System Prompt
2. 第一步：LLM 分析是否需要工具
   - 若回答包含 `[USE_TOOL:工具名]` 标记，则进入工具执行流程
   - 否则直接返回 LLM 回答
3. 执行工具：`toolRegistry.executeTool(toolName, params)`
4. 第二步：LLM 基于工具结果生成最终回答

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/agent/chat | Agent 智能对话 |
| POST | /api/agent/query-db | 直接查询数据库 |
| POST | /api/agent/search-code | 直接搜索代码 |
| GET | /api/agent/api-doc | 获取 API 文档 |
