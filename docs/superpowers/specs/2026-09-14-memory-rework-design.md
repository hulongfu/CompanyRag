# 会话记忆规范化（RagChatMemory 只读helper + Controller 层统一落库）设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅（已根据 2026-09-14-design-review 及二次评审修订）
> 前置决策：**memory-A**——把 `ChatController` 手动拼接会话历史读取逻辑，收敛到只读的 `RagChatMemory` 组件；落库责任统一收口到 Controller 层，用服务端可信身份落库。
> 修订说明（二次修订）：
> - **废弃 Advisor/CONVERSATION_ID 包装**：主路径既定手动注入，`MessageChatMemoryAdvisor` 与 `CONVERSATION_ID` 编解码无实际承载，按 YAGNI 移除。`RagChatMemory.get(sessionId)` 仅作只读历史 helper（不写库），`add` 空操作与 `ChatMemoryRepository`/`buildConversationId`/`extractSessionId` 一并取消。
> - **落库收敛为 Controller 层自行落库（移除 persist 开关风声）**：不再把 `saveConversation` 下钻到 `search`，也**不用显式 persist 开关**（其若落在 `RagQuery` body 字段可被客户端 `setPersist(false)` 规避、且落库仍用 body userId）。改为 `ChatController.chat` 与 `ChatController.ragSearch` 各自用**服务端可信身份**（headerTenantId + SecurityContext.userId）在 Controller 层自行 `saveConversation`；`search` 内的条件性 `saveConversation`（L112-122）**整体移除**。
> - **🔴2 重述为"防御性 + `/api/rag/search` 现状 userId 缺口"**：主链路 `/api/chat` 身份来源已可信（header+SecurityContext），「从可伪造 ID 解析身份」是防御性约束；真正的现状越权缺口在 `/api/rag/search`——`ragSearch` 只覆盖 `tenantId`（L255）不覆盖 `userId`，而 `search` L114 用 body 可控的 `query.getUserId()` 落库，须一并修复（用 SecurityContext.userId）。
> - **全量历史改为有上限（新增可配置上限与兜底）**：默认不再无界；提供 `window-size` 显式配置（默认如 50 轮），超长会话按「最近 N 轮」截断并降级提示，避免 O(n) 读全表 + 拼入 prompt 溢出 LLM 上下文。

## 1. 目标

以更标准的方式承载「会话历史」：用**只读的 `RagChatMemory`** 统一承载对话历史的读取，替代 `ChatController` 中手写的 `getSessionDetail` → `UserMessage/AssistantMessage` 拼接；落库责任统一收口到 Controller 层并用服务端可信身份执行。**不引入 `MessageChatMemoryAdvisor`**（主路径手动注入，Advisor 无实际承载，YAGNI）。

**约束：**
- **保隔离（验收铁律）**：多租户 + 用户 + 会话三级隔离必须**由受信任来源提供租户/用户身份**——读取历史用 `TenantContext`（或等价可信上下文），落库用 `headerTenantId` + `SecurityContext.userId`，**绝不从可伪造的外部入参（body 的 `tenantId`/`userId`/`sessionId` 中的身份维度）解析鉴权**。
- **保语义（有界）**：历史注入默认不再无界。提供 `window-size` 显式配置（默认：最近 `N` 轮/N 条），超长会话按「最近 N 轮」截断并降级提示，避免 O(n) 读全表与 prompt 溢出 LLM 上下文。**不得默默改变现状语义——默认值即现状行为的上限化封装**，且改动前后多轮行为保持一致（见 §3.5 取舍）。
- **唯一落库 Owner（Controller 层）**：`RagChatMemory` 只读不写；`ChatController.chat` 与 `ChatController.ragSearch` 各自用服务端可信身份 `saveConversation`；**移除 `RagSearchServiceImpl.search` 内的条件性副作用**（见 §3.3）。
- 不引入 mem0；落库仍走既有 `rag_session` 表与 `RagSessionService`。

## 2. 现状回顾

### 2.1 隔离现状核对（🔴2：防御性 + `/api/rag/search` 现状缺口）

- **主链路 `/api/chat` 身份来源已可信**：`ChatController.chat`（ChatController.java:84-124）取 `headerTenantId`（X-Tenant-Id，已由鉴权链验证，L95/L105）与 `SecurityContext`（SecurityUser.userId，L108-124），**从不从可伪造的 ID 解析身份**。故主链路并无可被直接利用的「从 CONVERSATION_ID 解析身份」越权。
- `RagSessionServiceImpl.getSessionDetail`（RagSessionServiceImpl.java:162-169）虽直接用传入 `tenantId/userId` 等值过滤，但其调用方若传入**可信身份**则安全；该写法本身不是漏洞来源，而是**调用契约要求身份可信**——这是 🔴2 的**防御性约束**（防止将来有人把身份装进可伪造入参、或新 `RagChatMemory.get` 误解析 ID）。
- **真正的现状越权缺口在 `/api/rag/search`**（ChatController.java:240-260）：
  - `ragSearch` 只用请求头覆盖 `tenantId`（L255 `query.setTenantId(headerTenantId)`），**不覆盖 userId**；
  - `RagSearchServiceImpl.search` L114 落库时 `Long userId = query.getUserId() != null ? query.getUserId() : 1L;` ——取的是**请求体可控的 `query.getUserId()`**；
  - 客户端可对 `/api/rag/search` 伪造 `userId` → 会话被归属到他人名下（跨用户数据污染/越权归属）。
  - **本 spec 在 §3.3 一并修复此缺口**：所有落库改用 `SecurityContext.userId`（服务端可信），并在 `/api/rag/search` 补齐身份处理。

### 2.2 当前手动拼接 + 落库

- `ChatController.chat` 手动拼 history（ChatController.java:29-45）：
  ```
  List<RagSession> historySessions = getSessionDetail(tenantId, userId, sessionId); // 全量升序
  for (session : historySessions) {
      historyMessages.add(new UserMessage(session.getQuery()));
      historyMessages.add(new AssistantMessage(session.getAnswer()));
  }
  ```
- `ChatController.chat` 在回答后调用 `saveConversation(...)` 落库（L52-67，含回填自增 id + 异步元数据批量更新）。
- `processWithHistory(history, userMsg)` 把 history 原样传给 `callAgentWithTimeout`。
- `RagSearchServiceImpl.search` L111-122：`if (query.getSessionId() != null) { saveConversation(...) }`——**条件性副作用**（客户端 body 带 `sessionId` 时触发落库，且落库 userId 取自 body，见 §2.1）。

## 3. 架构设计

### 3.1 核心思路

用**只读的 `RagChatMemory`** 承载历史读取（内部恒取可信身份），主路径由 `ChatController` 显式调用并注入。**不引入 `MessageChatMemoryAdvisor`/`ChatMemoryRepository`/`CONVERSATION_ID`**：主路径本是手动注入，Advisor 及 conversationId 编解码无实际承载，按 YAGNI 移除，避免"给 `getSessionDetail` 改名却未兑现规范收益"。

### 3.2 隔离模型（可信身份来源；🔴2 重述后）

```
RagChatMemory.get(sessionId):
  // 关键：不从 sessionId 解析 tenantId/userId！
  // 鉴权身份只取受信任上下文：
  Long tenantId = TenantContext.getTenantId();   // 已由请求链注入，不可客户端伪造
  Long userId   = TenantContext.getUserId();
  if (tenantId == null || userId == null || sessionId == null) return List.of();  // 空值保护（§8 #8）
  return ragSessionService.getSessionDetail(tenantId, userId, sessionId);  // 按受信任身份过滤 + 有界截断（§3.5）
  // 若实现为分页/limit，则按 window-size 取「最近 N 轮」（orderBy createTime/id desc 取最近 N 条再 asc）
```

- **`sessionId` 只作为会话定位符，绝不作为鉴权输入**。
- 即使传入他人 `sessionId`，`getSessionDetail` 仍按 `TenantContext` 的 tenantId/userId 过滤 → 不会命中他人数据。
- **前置条件**：`TenantContext` 必须已由请求链填充（`ChatController.chat` L90/105/124）。`/api/rag/search` 路径当前不填充 `TenantContext`（见 §2.1），不得在未补齐上下文前调用 `get`；本 spec 中该路径用 header+SecurityContext 直接落库，不依赖 `get`（见 §3.3）。
- 与既有 RLS / 目录隔离一致：身份来自可信上下文，而非可伪造的外部入参。

### 3.3 落库责任收敛（Controller 层统一落库；同步修复 `/api/rag/search` userId 缺口）

**根因核实结论**：
- `/api/chat` 主链路本无双写。`ChatController.chat`（L52-67）用可信身份落库；`KnowledgeBaseTool.searchKnowledgeBase`（KnowledgeBaseTool.java:80-84）构造的 `RagQuery` 只设 `tenantId/query/topK`、**从不设 sessionId** → `search` 内条件性落库（L112 `if (query.getSessionId() != null)`）在主链路不触发。
- 会设置 `sessionId` 的是 `ChatRouter.buildRagQuery`（L308，测试/向后兼容，非主路径）与 `ChatController.ragSearch`（`/api/rag/search`，废弃但存活）。
- **真正的隐患是落库副作用与信任缺口耦合在 `search` 内**：`RagSearchServiceImpl.search` L111-122 的 `saveConversation` 是**条件性副作用**（代码可见，非"隐性"），其**落库 userId 取自 body 可控的 `query.getUserId()`**（L114），配合 `/api/rag/search` 只用 header 覆盖 tenantId、**不覆盖 userId**，构成**跨用户数据污染/越权归属**（§2.1）。

**决定（Controller 层统一落库，服务端可信身份）**：
- **移除 `RagSearchServiceImpl.search` 内的条件性 `saveConversation`（L111-122）**——不再以此为落库点，也不引入任何 `persist` 开关（若下钻成 `RagQuery` body 字段会被客户端 `setPersist(false)` 规避，且落库仍用 body userId）。
- **`RagChatMemory` 只读不写**：仅实现"读历史注入"（`get`，有界），**不调用 `saveConversation`**，不做 write-back。
- **`ChatController.chat`（主链路）**：保留其 `saveConversation`（L52-67），身份用 `headerTenantId` + `SecurityContext.userId`（现状可信，不改）。
- **`ChatController.ragSearch`（`/api/rag/search`）**：补齐落库责任与身份信任——
  - 从 `SecurityContext` 取得 `verifiedUserId`，**不再信任 body 的 `query.getUserId()`**；
  - 在 `search` 返回后，若客户端带 `sessionId`，由 Controller 用 `headerTenantId` + `verifiedUserId` 自行 `saveConversation(query, answer, context)`，保住该存活端点带 `sessionId` 的历史持久化语义；
  - 需要时补 `TenantContext` 填充（见 §3.2 前置条件），但落库身份以显式参数传递，不依赖其回填。
  - `search` 本身只返回检索结果，**落库副作用完全移出**。
- 收敛效果：`/api/chat` 与 `/api/rag/search` 各自在 Controller 层、用服务端可信身份落库；`search` 变为纯检索，杜绝隐藏双写与 body 身份污染。

> 备选（不采用）：若未来接回写路径，需统一由单一写入口承担 `saveConversation` 并验证回填 id / 异步元数据更新不受影响。本 spec 默认「Controller 层落库 + `search` 零副作用」，改动更小、风险更低。

### 3.4 调用链（主路径手动注入；签名对外不变）

```
现状：ChatController 手拼 history（getSessionDetail） → processWithHistory(history, query) → 回答后 saveConversation 落库
方案A（主路径）：
  ChatController 调用唯一读取入口得到有界历史：
      List<Message> history = ragChatMemory.get(request.getSessionId());   // 只读、有界、恒取 TenantContext 身份
  → processWithHistory(history, query)        // 签名 (List<Message>, String) 对外保留不变
  → 回答后仍由 ChatController 调 saveConversation 落库（唯一 Owner，可信身份，不改）
```

- **签名兼容**：`processWithHistory(List<Message>, String)` **签名对外保持不变**。历史加载责任从「ChatController 手拼 SQL 结果」前移到「通过 `RagChatMemory.get` 获取」，但**仍在 Controller 内显式获取并注入**，`RagAgentService` 内部逻辑不动。
- **手动注入优先**：历史由 Controller 经 `ragChatMemory.get(...)`（内部恒取 `TenantContext` 身份）显式传入，与现有手动拼历史逻辑保持一致，不引入 Advisor 自动钩子。
- **不引入 `MessageChatMemoryAdvisor`/`ChatMemoryRepository`/`CONVERSATION_ID`/`buildConversationId`/`extractSessionId`**（YAGNI，见 §3.1）。

### 3.5 有界历史语义与 window-size（🟡 → 生产兜底）

- `RagChatMemory.get` **默认有界返回**：按 `window-size`（`rag.memory.window-size`，**默认如 50 轮/100 条**，可配）取**最近 N 轮**（`orderBy createTime/id desc` 取最近 N 条再按升序，保持原顺序语义）。
- **超长会话兜底**：超过 `window-size` 时，仅注入最近 N 轮，并在健康/响应语义上不因截断而报错；如需要可加日志或轻量提示「历史已截断」。
- **不引入 `MessageWindowChatMemory`**（避免再绑回 Advisor）；窗口由 `RagChatMemory` 查询时按 `limit` 截断实现。
- **`window-size` 语义**：显式配置项（默认有界值），取值 `-1` 表示退化为现状全量（需运维显式打开并知晓 O(n)+token 风险）；**绝不在默认态默默无界增长**。
- **取舍**：有界化相对现状"全量"是**安全上限化封装**，多轮短会话行为与现状一致；仅长会话在接近上限时开始截断，属显式声明的生产保护，不构成未声明漂移。

## 4. 数据流

1. `ChatController.chat` 传入 `sessionId`；`tenantId/userId` 已由请求鉴权链注入 `TenantContext`（L90/105/124）。
2. `ChatController` 经唯一读取入口 `ragChatMemory.get(sessionId)` 获取**有界**历史（内部恒取 `TenantContext` 身份，按 `window-size` 截断为最近 N 轮）。
3. `ChatController` 将历史作为 `List<Message>` 传给 `processWithHistory(history, userMessage)`（签名对外不变）。
4. ReAct 在含历史上下文下生成回答。
5. `ChatController.chat` 用可信身份（`headerTenantId` + `SecurityContext.userId`）调 `saveConversation` 落库（回填 id、异步元数据更新不变，**唯一 Owner**）。
6. `ChatController.ragSearch`（`/api/rag/search`）`search` 返回后，用可信身份（`headerTenantId` + `SecurityContext.userId`）自行 `saveConversation`（客户端带 `sessionId` 时），保住持久化语义；`search` 本身零副作用。

## 5. 安全与兼容性

| 关注点 | 策略 |
|---|---|
| 多租户隔离（🔴2） | 读历史恒取 `TenantContext.getTenantId()/getUserId()`；落库恒用 `headerTenantId` + `SecurityContext.userId`；**绝不从可伪造 body（`tenantId`/`userId`/`sessionId` 中的身份维度）解析鉴权**；伪造 ID/跨 session 不越权 |
| `/api/rag/search` userId 缺口（🔴 新增） | `ragSearch` 不再信任 `query.getUserId()`；改为从 `SecurityContext` 取 `verifiedUserId` 落库，杜绝 body 伪造归属 |
| 唯一落库 Owner（🔴3） | `RagChatMemory` 只读不写；`search` 内 L111-122 条件性 `saveConversation` **整体移除**（零副作用，不用 persist 开关）；`/api/chat` 与 `/api/rag/search` 各自在 Controller 层用可信身份落库 |
| 废弃 `ChatRouter` 越权边界（🟡5） | `ChatRouter`（`buildRagQuery` L308 用不可信 `request.getTenantId()` + `processAgent` 落库）**已 `@Deprecated`、不在 `/api/chat` 主链路**，仅测试与向后兼容引用。若未来重新启用，落库身份必须与 `ChatController` 同源改走可信 `X-Tenant-Id` + `SecurityContext`，spec 明确此边界 |
| 有界历史语义 | `get` 默认按 `window-size` 截断为最近 N 轮；`-1` 退化为全量（需运维显式打开）；`get` 空值/上下文缺失时返回空列表 |
| 落库兼容 | 不动 `saveConversation` 逻辑与元数据批量更新；`search` 移除落库不影响主链路（本就不落库） |
| 契约不变 | `processWithHistory` 签名对外保留；`AgentResult`/`ChatResponse` 其结构仅做**可控扩展**（如可选 `warnings` 字段，不破坏既有 `answer`/`toolContext`） |
| 事务边界（已知取舍） | `saveConversation` 无 `@Transactional`（RagSessionServiceImpl.java:51 已注释），与 `updateFeedback` 的 `@Transactional` 混用——保留现状，仅在文档记录此取舍（见 §8） |
| mem0 边界 | 不引入 mem0 |

## 6. 测试策略

- **隔离测试（关键，🔴2 回归）**：固定 `TenantContext`=tenantA，调用 `ragChatMemory.get(sessionBelongsToTenantB)`，断言**不返回 tenantB 数据**（`getSessionDetail` 按 trusted 身份过滤）。不再伪造 `CONVERSATION_ID`（已废除）。
- **`/api/rag/search` userId 信任（🔴 回归）**：携带 body `userId=他人`，断言最终落库归属 = `SecurityContext.userId`，非 body 值。
- **单测（`RagChatMemory`）**：`get` 恒用上下文身份；空 `TenantContext`/null `sessionId` 返回空列表（不 NPE）；有界截断正确（按 `window-size` 取最近 N 轮）。
- **双写回归**：同 session 一轮对话后，`rag_session` 表只新增 1 行（非 2 行），验证唯一 Owner。
- **工具检索不落库（🔴3 回归）**：`KnowledgeBaseTool`（不设 sessionId）触发 `ragSearchService.search` 后，`saveConversation` 不被调用 / `rag_session` 不新增——证明 `search` 已无副作用残留。
- **`/api/rag/search` 持久化保持（S1 回归）**：`ChatController.ragSearch` 带 `sessionId` 调用 `search` 后，`rag_session` 表新增 1 行且 userId=SecurityContext 用户——证明 Controller 层兜底落库不丢历史且归属可信。
- **有界兜底（#2 回归）**：构造超 `window-size` 的长会话，断言注入为最近 N 轮。
- **集成**：同 session 多轮能正确携带历史；rowId 回填正常。
- 验证命令采用最窄范围：`company-rag-agent` + `company-rag-rag` 相关测试类。

## 7. 改动清单

- **新增**：`RagChatMemory`（普通只读组件，非 `ChatMemoryRepository`；`get(sessionId)` 恒从 `TenantContext` 取身份 + 有界截断；**不写库**）。
- **修改**：`ChatController`——去手拼，改经 `ragChatMemory.get(request.getSessionId())` 获取历史再注入；`chat` 的 `saveConversation` 保留（可信身份，唯一 Owner）。
- **修改**：`RagSearchServiceImpl`——**移除 L111-122 条件性 `saveConversation`**（search 零副作用；不用 persist 开关）。
- **修改**：`ChatController.ragSearch`（`/api/rag/search`，废弃但存活）——**从 `SecurityContext` 取 `verifiedUserId`（不再信任 `query.getUserId()`）**；`search` 返回后带 `sessionId` 时用可信身份自行 `saveConversation`；必要时补齐 `TenantContext` 填充（#1/#3/#8/#14）。
- **修改**：`RagAgentService`——`processWithHistory` 兼容保留，内部逻辑不动。
- **新增配置**：`rag.memory.enabled`（false 时 `ChatController` 回退为现有手拼 `getSessionDetail` 逻辑，不引入新组件）、`rag.memory.window-size`（默认有界值，如 50 轮/100 条；`-1`=全量）。
- **不动**：`rag_session` 表、`RagSessionService` 落库逻辑、既有会话接口、mem0。

## 8. 风险与观察项

- **隔离被绕过（最高优先级，🔴2）**：读与落库身份必须恒取自可信来源（`TenantContext`/`headerTenantId`+`SecurityContext`），`sessionId` 仅定位。**验收铁律 + 隔离测试双重约束**。
- **`/api/rag/search` userId 越权（🔴 新增）**：修复前客户端可伪造 `query.getUserId()` 污染他人归属；修复后落库 userId 一律取 `SecurityContext`。**回归测试兜底**。
- **双写隐患（🔴3）**：`search` 移除条件性落库后零副作用；唯一写入口为两个 Controller。`RagChatMemory` 不落库。
- **`/api/rag/search` 回归（S1）**：该废弃端点存活且依赖 `search` 原条件性落库；移除后由 `ChatController.ragSearch` 在 Controller 层用可信身份兜底，**否则带 `sessionId` 的旧客户端静默丢历史**。
- **有界历史（🔴 生产兜底）**：默认不无界，按 `window-size` 截断为最近 N 轮，避免 O(n) 读全表与 prompt 溢出 LLM 上下文；超长会话仅截断不报错（可加「历史已截断」提示）。`-1` 为全量回退需运维显式决定。
- **Advisor 取舍（YAGNI）**：不引入 `MessageChatMemoryAdvisor`/`CONVERSATION_ID`，规避了与 ReAct 接入对齐的前置工作，也避免了"给 `getSessionDetail` 改名"的无收益抽象。
- **事务边界（已知取舍）**：`saveConversation` 无 `@Transactional`（RagSessionServiceImpl.java:51 已注释）+ `L111-122` 原 `try/catch` 仅 `log.warn` 吞异常——前者保留现状、在 §5/§7 注明；后者随 `search` 移除落库而一并退出作用域。
- **`rag.memory.enabled=false`**：回退为现有手拼逻辑，行为与现状完全一致（见 §7）。
- **跨方案依赖**：本 spec 与 reflection 同改 `RagAgentService`（reflection 在末尾委派自省），实施排序在阶段 3 收口，避免同一方法多处并行修改冲突（见编排总览）。