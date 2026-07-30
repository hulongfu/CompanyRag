# Chat Router 使用指南

## 概述

Chat Router 提供了统一的智能聊天入口 `/api/chat`，能够自动识别用户意图并路由到合适的处理器。采用混合策略意图识别（规则匹配 + LLM 降级），支持三级降级保障企业级可用性。

## API 接口

### POST /api/chat

**请求示例**：
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "query": "公司有多少员工？",
    "sessionId": "session_123",
    "topK": 10,
    "enableRerank": true,
    "includeDebug": false
  }'
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "answer": "公司目前有 500 名员工，分布在 5 个部门...",
    "sources": ["员工手册.pdf", "组织架构.docx"],
    "metrics": {
      "totalMs": 1200,
      "intent": "DATABASE",
      "routePath": "intent:database"
    }
  }
}
```

### 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| query | String | 是 | - | 用户问题 |
| sessionId | String | 否 | null | 会话 ID |
| tenantId | Integer | 否 | null | 租户 ID（从 Header 获取） |
| topK | Integer | 否 | 10 | RAG 检索条数 |
| enableRerank | Boolean | 否 | true | 是否启用 Rerank |
| includeDebug | Boolean | 否 | false | 是否包含调试信息 |
| mode | String | 否 | "auto" | 手动指定模式：auto/rag/agent |

## 意图类型

| 意图 | 说明 | 路由目标 | 降级策略 |
|------|------|----------|----------|
| DOCUMENT | 文档查询 | RagSearchService | LLM 直接回答 |
| DATABASE | 数据库查询 | RagAgentService | RAG → LLM |
| CODE | 代码查询 | RagAgentService | RAG → LLM |
| CHAT | 闲聊 | 直接 LLM | 兜底回答 |

## 降级策略

### P0 级（核心功能）
- **目标**：用户必须得到回答
- **措施**：LLM 不可用 → 兜底提示；超时 → 超时提示；未知错误 → 兜底回答

### P1 级（重要功能）
- **目标**：尽量准确路由
- **措施**：意图识别失败 → 默认 DOCUMENT；RAG 无结果 → 纯 LLM；Agent 失败 → RAG → LLM

### P2 级（辅助功能）
- **目标**：可牺牲
- **措施**：指标收集失败 → 默认值；会话保存失败 → 记录日志；调试信息失败 → 忽略

## 配置项

```yaml
# application.yml
rag:
  router:
    intent:
      rule-threshold: 0.8      # 规则匹配置信度阈值
      llm-timeout: 5000        # LLM 识别超时 (ms)
      default-intent: DOCUMENT # 默认意图
```

## 前端集成

### 第一阶段（当前）
- 统一调用 `/api/chat`
- 高级模式切换（默认隐藏，可通过"设置"按钮显示）
- 支持 auto/rag/agent 三种模式

### 第二阶段（未来）
- 移除模式切换 UI
- 完全智能化

## 监控指标

| 指标 | 类型 | 说明 |
|------|------|------|
| chat.requests.total | counter | 总请求数 |
| chat.requests.by_intent | counter | 按意图分类 |
| chat.latency.ms | histogram | 响应延迟 |
| chat.router.fallback.count | counter | 降级次数 |

## 测试

```bash
# 运行单元测试
cd company-rag-rag && mvn test -Dtest=PatternRuleTest,IntentRecognizerTest,ChatRouterTest -q

# 运行 Controller 测试
cd company-rag-web && mvn test -Dtest=ChatControllerTest -q

# 运行集成测试
cd company-rag-rag && mvn test -Dtest=ChatRouterIntegrationTest -q
```

## 常见问题

### Q: 如何调试路由问题？
A: 设置 `includeDebug: true` 获取详细的路由信息

### Q: 如何强制使用 RAG 模式？
A: 设置 `mode: "rag"` 参数

### Q: 意图识别不准确怎么办？
A: 检查日志中的意图识别结果，优化 PatternRule 规则