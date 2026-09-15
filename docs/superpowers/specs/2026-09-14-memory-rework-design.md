# 会话记忆规范化（MessageChatMemoryAdvisor）设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅（已根据 2026-09-14-design-review 修订）
> 前置决策：**memory-A**——把 `ChatController` 手动拼接会话历史的逻辑，规范化为 Spring AI `MessageChatMemoryAdvisor` + `ChatMemoryRepository`；收益中等、风险可控。
> 修订说明：修复 🔴2（隔离身份必须取自 `TenantContext` 而非解析可伪造 ID）；🔴3 经根因核实为误诊断（主链路本无双写），已重写为「落库责任收敛 + 消除 `RagSearchServiceImpl.search` 隐式落库副作用」；澄清 🟡5 越权边界——废弃 `ChatRouter`（`buildRagQuery`/`processAgent` 用不可信 `request.getTenantId()` 落库）**不在 `/api/chat` 主链路**，仅测试与向后兼容引用，若未来重新启用须统一改走 `X-Tenant-Id`；采用手动注入主路径、界定 window-size 语义。

## 1. 目标

以更标准的方式承载「会话历史」：用 Spring AI `MessageChatMemoryAdvisor` 统一管理对话记忆的读取，替代 `ChatController` 中手写的 `getSessionDetail` → `UserMessage/AssistantMessage` 拼接。

**约束：**
- **保隔离（验收铁律）**：多租户 + 用户 + 会话三级隔离必须**由受信任的 `TenantContext` 提供租户/用户身份**，绝不从可伪造的 `CONVERSATION_ID` 解析鉴权身份。
- **保语义**：全量历史注入，不得因窗口截断丢历史（行为不得漂移）。
- **唯一落库 Owner**：历史落库在 ChatController 与 Advisor 间**二选一**，防双写。
- 不引入 mem0；落库仍走既有 `rag_session` 表与 `RagSessionService`。

## 2. 现状回顾

### 2.1 当前隔离写法（🔴 需修正）

- `RagSessionServiceImpl.getSessionDetail(L162)`：`selectList(... eq(RagSession::getTenantId, tenantId).eq(RagSession::getUserId, userId).eq(getSessionId, sessionId))`——**直接用传入的 `tenantId/userId` 作等值过滤**。
- 若 `tenantId/userId` 由可伪造的 `CONVERSATION_ID` 解析产生，则 `tenantB|userB|sessionX` 可直接读 tenantB 数据，**跨租户越权**。这正是评审指出的隔离漏洞。

### 2.2 当前手动拼接 + 落库

- `ChatController.chat`（L96-110）手动拼 history：
  ```
  List<RagSession> historySessions = getSessionDetail(tenantId, userId, sessionId); // 全量升序
  for (session : historySessions) {
      historyMessages.add(new UserMessage(session.getQuery()));
      historyMessages.add(new AssistantMessage(session.getAnswer()));
  }
  ```
- `ChatController.chat`（L121）在回答后调用 `saveConversation(...)` 落库（含回填自增 id + 异步元数据批量更新）。
- `processWithHistory(history, userMsg)` 把 history 原样传给 `callAgentWithTimeout`。

## 3. 架构设计

### 3.1 核心思路

引入 `MessageChatMemoryAdvisor` 承载历史读取，但其 `ChatMemoryRepository` 实现映射到既有 `rag_session` 表，且**鉴权身份恒定取自 `TenantContext`**。主路径采用**手动注入**（降低对框架钩子依赖），Advisor 仅作可选增强。

### 3.2 修正后的隔离模型（🔴2 已修复）

```
RagChatMemory.get(CONVERSATION_ID):
  // 关键：不从 CONVERSATION_ID 解析 tenantId/userId！
  // 鉴权身份只取受信任上下文：
  Long tenantId = TenantContext.getTenantId();   // 已由请求链注入，不可客户端伪造
  Long userId   = TenantContext.getUserId();
  String sessionId = extractSessionId(CONVERSATION_ID);   // ID 仅承载 sessionId
  return ragSessionService.getSessionDetail(tenantId, userId, sessionId);  // 按受信任身份过滤
```

- **`CONVERSATION_ID` 只作为 sessionId 的载体，绝不作为鉴权输入**。
- 即使传入伪造 `tenantB|userB|sessionX`，`extractSessionId` 取 sessionX，但 `get/sessionDetail` 仍按 `TenantContext.getTenantId()`（如 tenantA）过滤 → 不会命中 tenantB 数据。
- 与既有 RLS / 目录隔离一致：身份来自上下文，而非可伪造的外部入参。
- 每次处理仍有一条 `RagSessionService` 方法，接受 `sessionId`（可选新增），租户/用户恒从上下文取。

### 3.3 落库责任收敛（🔴3 已重写：原为误诊断）

**根因核实结论**：`/api/chat` 主链路本不存在双写。
- `RagSearchServiceImpl.search` 的落库是有条件的（`RagSearchServiceImpl.java:112`），仅当 `query.getSessionId() != null` 才触发 L115 `saveConversation`。
- `KnowledgeBaseTool.searchKnowledgeBase`（`KnowledgeBaseTool.java:80-83`）构造的 `RagQuery` 只设置 `tenantId/query/topK`，**从不设置 sessionId** → `/api/chat` 主链路 agent 调用检索工具时 `getSessionId()` 为 null，**L115 不落库**。
- 唯一设置 `ragQuery.setSessionId` 的是已废弃 `ChatRouter.buildRagQuery`（L308，仅测试与向后兼容），非主路径。

**因此真正的隐患是设计耦合而非现状故障**：`RagSearchServiceImpl.search` 内嵌了隐性的 `saveConversation` 副作用，依赖调用方是否传 sessionId。若未来调用方在 `search` 上补传 sessionId，将真实双写。

**决定（落库责任收敛）**：
- **唯一落库 Owner = `ChatController`**：保留 `ChatController.chat` L121 `saveConversation`。
- **`RagChatMemory` 只读不写**：仅实现"读历史注入"（`get`），`add` 为空操作，不调用 `saveConversation`。
- **消除 `RagSearchServiceImpl.search` 的隐式落库**：移除 L112-122 的 `saveConversation` 内嵌逻辑（或改为显式 `persist` 开关并由调用方显式触发），使所有落库事件收敛到唯一 Owner，杜绝隐藏双写路径。

> 备选（不采用）：若未来改由 Advisor 写、ChatController 删保存，需同步删掉 L121 `saveConversation`，并验证回填 id / 异步元数据更新不受影响。本 spec 默认选"ChatController 落库"，改动更小、风险更低。

### 3.4 调用链（主路径手动注入）

```
现状：ChatController 手拼 history → processWithHistory(history, query) → saveConversation 落库
方案A（主路径）：
  ChatController 不手拼，改为传 sessionId（来自请求）
  RagAgentService.processWithHistory 内部手动加载：
      List<Message> history = ragChatMemory.get(buildConversationId(sessionId));  // 手动注入，不依赖 Advisor 自动钩子
      messages.addAll(history);
  → callAgentWithTimeout(messages) → 回答
  → 仍由 ChatController 调 saveConversation 落库（唯一 Owner，不改）
```

- **手动注入优先**：在 `RagAgentService` 构建 messages 时显式 `addAll(ragChatMemory.get(...))`，与现有 L131-134 手动拼历史逻辑保持一致，**不强依赖 Advisor 自动注入钩子**。
- `MessageChatMemoryAdvisor` 仅作可选增强（若 ReAct 支持），不作为主路径依赖；即使接管，其 `ChatMemoryRepository` 仍是不写库的 `RagChatMemory`。

### 3.5 全量语义与 window-size（🟡 界定）

- `RagChatMemory.get` **永远全量返回**（升序，不截断）。
- 不引入 `MessageWindowChatMemory` 的截断语义。
- 文档中的 `window-size` 若将来要开放，必须是**显式配置项**（默认 -1=全量）且带取舍说明，绝不默默改变现状语义。

## 4. 数据流

1. `ChatController.chat` 传入 `sessionId`；`tenantId/userId` 已由请求鉴权链注入 `TenantContext`。
2. `RagAgentService.processWithHistory` 手动调 `ragChatMemory.get(buildConversationId(sessionId))`：
   - 内部恒取 `TenantContext` 的 tenantId/userId + 仅从 ID 提取 sessionId → 全量历史注入。
3. ReAct 在含历史上下文下生成回答。
4. `ChatController.chat` 调 `saveConversation` 落库（**唯一 Owner**，回填 id、异步元数据更新不变）。

## 5. 安全与兼容性

| 关注点 | 策略 |
|---|---|
| 多租户隔离（🔴2） | 鉴权身份恒取 `TenantContext.getTenantId()/getUserId()`，**不从可伪造 ID 解析**；伪造 ID 不越权 |
| 唯一落库 Owner（🔴3） | `RagChatMemory` 只读不写；落库仍由 `ChatController` 负责；并移除 `RagSearchServiceImpl.search` 的隐式 `saveConversation`，收敛落库边界，防隐藏双写 |
| 废弃 `ChatRouter` 越权边界（🟡5） | `ChatRouter`（`buildRagQuery` L308 用不可信 `request.getTenantId()` + `processAgent` L191-193 落库）**已 `@Deprecated`、不在 `/api/chat` 主链路**，仅测试与向后兼容引用。若未来重新启用，落库身份必须与 `ChatController` 同源改走可信 `X-Tenant-Id`，spec 明确此边界 |
| 全量历史语义 | `get` 不截断；`window-size` 默认 -1=全量，开放需显式配置 |
| 落库兼容 | 不动 `saveConversation` 逻辑与元数据批量更新 |
| 契约不变 | `processWithHistory` 签名对外保留；`AgentResult`/`ChatResponse` 结构不动 |
| mem0 边界 | 不引入 mem0 |

## 6. 测试策略

- **隔离测试（关键）**：伪造 `CONVERSATION_ID`（`tenantB|userB|...`），验证 `get` 仍按 `TenantContext`（tenantA）过滤，**绝不返回 tenantB 数据**；这是 🔴2 的回归验证。
- **单测（`RagChatMemory`）**：`extractSessionId` 正确；`get` 恒用上下文身份、全量返回不截断；`add`（若保留）不触发 `saveConversation`。
- **双写回归**：同 session 一轮对话后，`rag_session` 表只新增 1 行（非 2 行），验证唯一 Owner。
- **工具检索不落库（🔴3 回归）**：`KnowledgeBaseTool`（不传 sessionId）触发 `ragSearchService.search` 后，`saveConversation` 不被调用 / `rag_session` 不新增——证明工具检索无隐式落库残留。
- **集成**：同 session 多轮能正确携带历史；rowId 回填正常。
- 验证命令采用最窄范围：`company-rag-agent` + `company-rag-rag` 相关测试类。

## 7. 改动清单

- **新增**：`RagChatMemory`（`ChatMemoryRepository` 实现；`get` 恒从 `TenantContext` 取身份 + `extractSessionId`；**不写库**）、`buildConversationId(sessionId)` 辅助。
- **修改**：`RagAgentService`（主路径手动加载历史注入；`processWithHistory` 兼容保留）、`ChatController`（去手拼，传 sessionId；**L121 `saveConversation` 保留为唯一落库 Owner**）。
- **修改**：`RagSearchServiceImpl`（**移除 L112-122 内嵌 `saveConversation` 隐式落库**，或改为显式 `persist` 开关并由唯一 Owner 显式触发，收敛落库边界）。
- **新增配置**：`rag.memory.enabled / window-size(默认-1=全量)`。
- **不动**：`rag_session` 表、`RagSessionService` 落库逻辑、既有会话接口、mem0。

## 8. 风险与观察项

- **隔离被绕过（最高优先级，评审 🔴2）**：必须保证 `tenantId/userId` 恒取自 `TenantContext`，ID 仅承载 sessionId。**验收铁律 + 隔离测试双重约束**。
- **双写隐患（评审 🔴3，已重写）**：主链路本无双写；真正需收敛的是 `RagSearchServiceImpl.search` 的隐式 `saveConversation` 副作用。唯一 Owner = ChatController；`RagChatMemory` 不落库；`search` 移除隐式落库。改动清单明确"谁删保存/谁不写"。
- **Advisor 与 ReAct 兼容（🟡）**：主路径用**手动注入**，不依赖 Advisor 自动钩子；Advisor 仅可选增强，降低框架风险。
- **window-size 语义（🟡）**：全量不截断；开放窗口需显式配置并带取舍说明。
- **成本**：全量历史注入长会话放大 token；如需截断应显式配置，不默默改变现状语义。
- **跨方案依赖**：本 spec 与 reflection 同改 `RagAgentService`（reflection 在末尾委派自省），实施排序在阶段 3 收口，避免同一方法多处并行修改冲突（见编排总览）。