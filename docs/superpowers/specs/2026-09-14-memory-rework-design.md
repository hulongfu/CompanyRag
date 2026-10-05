# 会话记忆规范化（RagChatMemory 只读helper + Controller 层统一落库）设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅（已根据 2026-09-14-design-review 及二次评审修订）
> 前置决策：**memory-A**——把 `ChatController` 手动拼接会话历史读取逻辑，收敛到只读的 `RagChatMemory` 组件；落库责任统一收口到 Controller 层，用服务端可信身份落库。
> 修订说明（二次修订）：
> - **废弃 Advisor/CONVERSATION_ID 包装**：主路径既定手动注入，`MessageChatMemoryAdvisor` 与 `CONVERSATION_ID` 编解码无实际承载，按 YAGNI 移除。`RagChatMemory.get(sessionId)` 仅作只读历史 helper（不写库），`add` 空操作与 `ChatMemoryRepository`/`buildConversationId`/`extractSessionId` 一并取消。
> - **落库收敛为 Controller 层自行落库（移除 persist 开关风声）**：不再把 `saveConversation` 下钻到 `search`，也**不用显式 persist 开关**（其若落在 `RagQuery` body 字段可被客户端 `setPersist(false)` 规避、且落库仍用 body userId）。改为 `ChatController.chat` 与 `ChatController.ragSearch` 各自用**服务端可信身份**（headerTenantId + SecurityContext.userId）在 Controller 层自行 `saveConversation`；`search` 内的条件性 `saveConversation`（L112-122）**整体移除**。
> - **🔴2 重述为"防御性 + `/api/rag/search` 现状 userId 缺口"**：主链路 `/api/chat` 身份来源已可信（header+SecurityContext），「从可伪造 ID 解析身份」是防御性约束；真正的现状越权缺口在 `/api/rag/search`——`ragSearch` 只覆盖 `tenantId`（L255）不覆盖 `userId`，而 `search` L114 用 body 可控的 `query.getUserId()` 落库，须一并修复（用 SecurityContext.userId）。
> - **全量历史改为有上限（新增可配置上限与兜底）**：默认不再无界；提供 `window-size` 显式配置（默认如 50 轮，单位=轮/2 行），超长会话按「最近 N 轮」截断并降级提示，避免 O(n) 读全表 + 拼入 prompt 溢出 LLM 上下文。
> - **三次修订（实现级缺口收敛）**：
>   - **有界截断升级为 DB 层 `LIMIT` 强制契约**：新增 `getRecentSessionDetail(tenantId, userId, sessionId, limit)`，SQL 用 `ORDER BY createTime/id DESC LIMIT n`；**禁止** `getSessionDetail` 全量后内存 `subList`（否则 O(n) 规避失效）。§3.2/§3.5 由"若实现为分页/limit"升级为强制。
>   - **`ragSearch` 落库 context 显式来源**：`RagResult` 无 `context` 字段，`ragSearch` 必须用公共方法 `RagResultContextBuilder.build(result)` 从 `result.getChunks()` 重建（复用 `search` L79-84 逻辑），否则 `rag_session.context` 静默为空破坏 faithfulness 评估。
>   - **`RagChatMemory.get` 返回 `List<Message>`**：内部完成 `RagSession → Message` 转换（接管 `ChatController` L137-138 职责），抽象不旁落。
>   - **window-size 单位钉死为"最大轮数（turn）"**：一轮=2 行，`limit = window-size × 2`。
>   - **去掉 `rag.memory.enabled` 开关**（YAGNI）：`RagChatMemory` 是薄安全包装，去掉开关避免「新组件 + 旧手拼」两份加载逻辑长期共存；`enabled=false` 的"回退为手拼"不再成立。

## 1. 目标

以更标准的方式承载「会话历史」：用**只读的 `RagChatMemory`** 统一承载对话历史的读取，替代 `ChatController` 中手写的 `getSessionDetail` → `UserMessage/AssistantMessage` 拼接；落库责任统一收口到 Controller 层并用服务端可信身份执行。**不引入 `MessageChatMemoryAdvisor`**（主路径手动注入，Advisor 无实际承载，YAGNI）。

**约束：**
- **保隔离（验收铁律）**：多租户 + 用户 + 会话三级隔离必须**由受信任来源提供租户/用户身份**——读取历史用 `TenantContext`（或等价可信上下文），落库用 `headerTenantId` + `SecurityContext.userId`，**绝不从可伪造的外部入参（body 的 `tenantId`/`userId`/`sessionId` 中的身份维度）解析鉴权**。
- **保语义（有界）**：历史注入默认不再无界。提供 `window-size` 显式配置（默认：最近 `N` 轮，单位=轮/2 行，如 50 轮），超长会话按「最近 N 轮」截断并降级提示，避免 O(n) 读全表与 prompt 溢出 LLM 上下文。**不得默默改变现状语义——默认值即现状行为的上限化封装**，且改动前后多轮行为保持一致（见 §3.5 取舍）。
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

- `ChatController.chat` 手动拼 history（ChatController.java:131-145）：
  ```
  List<RagSession> historySessions = getSessionDetail(tenantId, userId, sessionId); // 全量升序
  for (session : historySessions) {
      historyMessages.add(new UserMessage(session.getQuery()));
      historyMessages.add(new AssistantMessage(session.getAnswer()));
  }
  ```
- `ChatController.chat` 在回答后调用 `saveConversation(...)` 落库（ChatController.java:156-164，含回填自增 id + 异步元数据批量更新）。
- `processWithHistory(history, userMsg)` 把 history 原样传给 `callAgentWithTimeout`。
- `RagSearchServiceImpl.search` L111-122：`if (query.getSessionId() != null) { saveConversation(...) }`——**条件性副作用**（客户端 body 带 `sessionId` 时触发落库，且落库 userId 取自 body，见 §2.1）。

## 3. 架构设计

### 3.1 核心思路

用**只读的 `RagChatMemory`** 承载历史读取（内部恒取可信身份），主路径由 `ChatController` 显式调用并注入。**不引入 `MessageChatMemoryAdvisor`/`ChatMemoryRepository`/`CONVERSATION_ID`**：主路径本是手动注入，Advisor 及 conversationId 编解码无实际承载，按 YAGNI 移除，避免"给 `getSessionDetail` 改名却未兑现规范收益"。

### 3.2 隔离模型（可信身份来源；🔴2 重述后）

```
RagChatMemory.get(sessionId): List<Message>          // 返回类型 = Message 列表（内部完成转换，见下）
  // 关键：不从 sessionId 解析 tenantId/userId！
  // 鉴权身份只取受信任上下文：
  Long tenantId = TenantContext.getTenantId();   // 已由请求链注入，不可客户端伪造
  Long userId   = TenantContext.getUserId();
  if (tenantId == null || userId == null || sessionId == null) return List.of();  // 空值保护（§8 #8）
  // 【强制契约】有界须由 DB 层 LIMIT 完成，禁止 fetch 全表后再在内存截断（否则 §3.5 的 O(n) 规避失效）
  int limit = (windowSize > 0) ? windowSize * 2 : DEFAULT_WINDOW_SIZE * 2;   // 一轮=2 行（Q+A），见 §3.5
  List<RagSession> recent = ragSessionService.getRecentSessionDetail(
          tenantId, userId, sessionId, limit);   // 新增专用方法：DB 层 ORDER BY createTime/id DESC LIMIT n 取最近 n 条再升序
  return toMessages(recent);   // 内部完成 RagSession → Message 转换（Q→UserMessage，A→AssistantMessage，保持升序）
```

- **返回类型为 `List<Message>`**（非 `List<RagSession>`）：`get` 内部承担 `RagSession → Message` 转换（即接管 `ChatController` 当前 L137-138 的职责），`ChatController` 拿到即可直接传给 `processWithHistory(List<Message>, String)`，抽象不旁落。
- **`sessionId` 只作为会话定位符，绝不作为鉴权输入**。
- 即使传入他人 `sessionId`，`getRecentSessionDetail` 仍按 `TenantContext` 的 tenantId/userId 过滤 → 不会命中他人数据。
- **强制 DB 层 `LIMIT`**：新增 `RagSessionServiceImpl.getRecentSessionDetail(tenantId, userId, sessionId, int limit)`，SQL 为 `ORDER BY createTime/id DESC LIMIT n`（取最近 n 条后反转回升序）。**禁止**调无 `LIMIT` 的 `getSessionDetail` 再 `subList` 截断——那会让 DB 仍读全表，`window-size` 的性能承诺不兑现（长会话依旧 O(n)）。原 `getSessionDetail` 保留（供非有界场景，如「回放整个会话」类功能）。
- **前置条件**：`TenantContext` 必须已由请求链填充（`ChatController.chat` L90/105/124）。`/api/rag/search` 路径当前不填充 `TenantContext`（见 §2.1），不得在未补齐上下文前调用 `get`；本 spec 中该路径用 header+SecurityContext 直接落库，不依赖 `get`（见 §3.3）。
- 与既有 RLS / 目录隔离一致：身份来自可信上下文，而非可伪造的外部入参。

### 3.3 落库责任收敛（Controller 层统一落库；同步修复 `/api/rag/search` userId 缺口）

**根因核实结论**：
- `/api/chat` 主链路本无双写。`ChatController.chat`（ChatController.java:156-164）用可信身份落库；`KnowledgeBaseTool.searchKnowledgeBase`（KnowledgeBaseTool.java:80-84）构造的 `RagQuery` 只设 `tenantId/query/topK`、**从不设 sessionId** → `search` 内条件性落库（L112 `if (query.getSessionId() != null)`）在主链路不触发。
- 会设置 `sessionId` 的是 `ChatRouter.buildRagQuery`（L308，测试/向后兼容，非主路径）与 `ChatController.ragSearch`（`/api/rag/search`，废弃但存活）。
- **真正的隐患是落库副作用与信任缺口耦合在 `search` 内**：`RagSearchServiceImpl.search` L111-122 的 `saveConversation` 是**条件性副作用**（代码可见，非"隐性"），其**落库 userId 取自 body 可控的 `query.getUserId()`**（L114），配合 `/api/rag/search` 只用 header 覆盖 tenantId、**不覆盖 userId**，构成**跨用户数据污染/越权归属**（§2.1）。

**决定（Controller 层统一落库，服务端可信身份）**：
- **移除 `RagSearchServiceImpl.search` 内的条件性 `saveConversation`（L111-122）**——不再以此为落库点，也不引入任何 `persist` 开关（若下钻成 `RagQuery` body 字段会被客户端 `setPersist(false)` 规避，且落库仍用 body userId）。
- **`RagChatMemory` 只读不写**：仅实现"读历史注入"（`get`，有界），**不调用 `saveConversation`**，不做 write-back。
- **`ChatController.chat`（主链路）**：保留其 `saveConversation`（ChatController.java:156-164），身份用 `headerTenantId` + `SecurityContext.userId`（现状可信，不改）。
- **`ChatController.ragSearch`（`/api/rag/search`）**：补齐落库责任与身份信任——
  - **【净新增】`SecurityContext` 身份提取是必须新写的代码块，非微调/挪库**：当前 `ragSearch`（ChatController.java:243-260）**完全没有** `SecurityUser` 提取（仅 `chat` 在 L108-124 有）。实现时须仿照 `chat` L108-124 新增：

    > Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    > if (principal instanceof SecurityUser) { verifiedUserId = ((SecurityUser) principal).getUserId(); }
    > if (verifiedUserId == null) { throw new IllegalStateException("用户 ID 不能为空"); }

    `search` 返回后**一律用该 `verifiedUserId` 落库，不再信任 body 的 `query.getUserId()`**——否则只移了落库位置、未加身份提取，🔴 userId 越权洞等于没修。
  - 在 `search` 返回后，若客户端带 `sessionId`，由 Controller 用 `headerTenantId` + `verifiedUserId` 自行 `saveConversation(query, answer, context)`，保住该存活端点带 `sessionId` 的历史持久化语义；
  - **context 来源（须显式规定）**：`RagResult` 无 `context` 字段（RagResult.java:11-16 仅有 answer/chunks/sessions/sessionId/metrics）。Controller 拿不到 context 时，**必须按 `search` 现状相同规则从 `result.getChunks()` 重建**（即 `"[来源:" + documentName + "] " + content` 逐块拼接，换行 `\n\n`）。建议把 `search` 内 L79-84 的拼接逻辑**抽成公共方法**（如 `RagResultContextBuilder.build(RagResult)`），`search` 与 `ragSearch` 共用，避免两处漂移、杜绝 `rag_session.context` 静默为空而破坏后续 faithfulness 评估（见 §7）。
  - 需要时补 `TenantContext` 填充（见 §3.2 前置条件），但落库身份以显式参数传递，不依赖其回填。
  - `search` 本身只返回检索结果，**落库副作用完全移出**。
- 收敛效果：`/api/chat` 与 `/api/rag/search` 各自在 Controller 层、用服务端可信身份落库；`search` 变为纯检索，杜绝隐藏双写与 body 身份污染。

> 备选（不采用）：若未来接回写路径，需统一由单一写入口承担 `saveConversation` 并验证回填 id / 异步元数据更新不受影响。本 spec 默认「Controller 层落库 + `search` 零副作用」，改动更小、风险更低。

### 3.4 调用链（主路径手动注入；签名对外不变）

```
现状：ChatController 手拼 history（getSessionDetail） → processWithHistory(history, query) → 回答后 saveConversation 落库
方案A（主路径）：
  ChatController 调用唯一读取入口得到有界历史（返回即 List<Message>，内部已完成转换）：
      List<Message> history = ragChatMemory.get(request.getSessionId());   // 只读、有界、恒取 TenantContext 身份、已转换
  → processWithHistory(history, query)        // 签名 (List<Message>, String) 对外保留不变
  → 回答后仍由 ChatController 调 saveConversation 落库（唯一 Owner，可信身份，不改）
```

- **签名兼容**：`processWithHistory(List<Message>, String)` **签名对外保持不变**。历史加载责任从「ChatController 手拼 SQL 结果」前移到「通过 `RagChatMemory.get` 获取」，但**仍在 Controller 内显式获取并注入**，`RagAgentService` 内部逻辑不动。
- **手动注入优先**：历史由 Controller 经 `ragChatMemory.get(...)`（内部恒取 `TenantContext` 身份）显式传入，与现有手动拼历史逻辑保持一致，不引入 Advisor 自动钩子。
- **不引入 `MessageChatMemoryAdvisor`/`ChatMemoryRepository`/`CONVERSATION_ID`/`buildConversationId`/`extractSessionId`**（YAGNI，见 §3.1）。

### 3.5 有界历史语义与 window-size（强制 DB LIMIT；单位钉死为"最大轮数"）

- `RagChatMemory.get` **默认有界返回**：按 `window-size`（`rag.memory.window-size`，默认如 50 轮，可配）取**最近 N 轮**。
- **单位钉死为「最大轮数（turn）」**：一轮 = 一行用户消息 + 一行助手回答（`rag_session` 中 Q/A 各占一行）。实现层换算口径统一为 **limit = window-size × 2（按行数）**，配置注释写清「单位=轮，一轮=2 行；DB LIMIT 取 window-size×2 行」。
- **【强制契约】有界必须是 DB 层 `LIMIT`，禁止全量 fetch 后内存切片**：`RagChatMemory.get` 必须经 `getRecentSessionDetail(tenantId, userId, sessionId, limit)` 执行 `ORDER BY createTime/id DESC LIMIT n`，DB 只得最近 n 条再反转升序。**不得**先 `getSessionDetail` 全量查询再 `subList`——否则 DB 仍 O(n) 读全表，§3.5「避免 O(n) 读全表」即假声明，长会话性能收益落空。
- **超长会话兜底**：超过 `window-size` 时，仅注入最近 N 轮，并在健康/响应语义上不因截断而报错；如需要可加日志或轻量提示「历史已截断」。
- **不引入 `MessageWindowChatMemory`**（避免再绑回 Advisor）；窗口由 `RagChatMemory` 查询时通过 DB `LIMIT` 截断实现。
- **`window-size` 语义**：显式配置项（默认有界值，单位=轮/2 行），取值 `-1` 表示退化为**现状全量**（此时 `getRecentSessionDetail` 退化为等价的 `getSessionDetail` 全量读，需运维显式打开并知晓 O(n)+token 风险）；**绝不在默认态默默无界增长**。
- **取舍**：有界化相对现状"全量"是**安全上限化封装**，多轮短会话行为与现状一致；仅长会话在接近上限时开始截断，属显式声明的生产保护，不构成未声明漂移。

## 4. 数据流

1. `ChatController.chat` 传入 `sessionId`；`tenantId/userId` 已由请求鉴权链注入 `TenantContext`（L90/105/124）。
2. `ChatController` 经唯一读取入口 `ragChatMemory.get(sessionId)` 获取**有界**历史（内部恒取 `TenantContext` 身份、返回 `List<Message>`、按 `window-size` 由 DB `getRecentSessionDetail` LIMIT 截断为最近 N 轮）。
3. `ChatController` 将历史作为 `List<Message>` 传给 `processWithHistory(history, userMessage)`（签名对外不变）。
4. ReAct 在含历史上下文下生成回答。
5. `ChatController.chat` 用可信身份（`headerTenantId` + `SecurityContext.userId`）调 `saveConversation` 落库（回填 id、异步元数据更新不变，**唯一 Owner**）。
6. `ChatController.ragSearch`（`/api/rag/search`）`search` 返回后，用可信身份（`headerTenantId` + `SecurityContext.userId`）自行 `saveConversation`（客户端带 `sessionId` 时），context 由 `result.getChunks()` **重建**（`RagResultContextBuilder.build`），保住持久化语义；`search` 本身零副作用。

## 5. 安全与兼容性

| 关注点 | 策略 |
|---|---|
| 多租户隔离（🔴2） | 读历史恒取 `TenantContext.getTenantId()/getUserId()`；落库恒用 `headerTenantId` + `SecurityContext.userId`；**绝不从可伪造 body（`tenantId`/`userId`/`sessionId` 中的身份维度）解析鉴权**；伪造 ID/跨 session 不越权 |
| `/api/rag/search` userId 缺口（🔴 新增） | `ragSearch` 不再信任 `query.getUserId()`；改为从 `SecurityContext` 取 `verifiedUserId` 落库，杜绝 body 伪造归属 |
| 唯一落库 Owner（🔴3） | `RagChatMemory` 只读不写；`search` 内 L111-122 条件性 `saveConversation` **整体移除**（零副作用，不用 persist 开关）；`/api/chat` 与 `/api/rag/search` 各自在 Controller 层用可信身份落库 |
| 废弃 `ChatRouter` 越权边界（🟡5） | `ChatRouter`（`buildRagQuery` L308 用不可信 `request.getTenantId()` + `processAgent` 落库）**已 `@Deprecated`、不在 `/api/chat` 主链路**。**生产代码 0 调用方**（仅类自身声明 + `SchemaMigrationConfig` 一条注释伪引用）；测试侧残留真实引用 `ChatRouterIntegrationTest.java`（`@Autowired ChatRouter`）。本轮不动其实现；若未来重新启用，落库身份必须与 `ChatController` 同源改走可信 `X-Tenant-Id` + `SecurityContext`。建议后续评估删除 `ChatRouter` 及其 `ChatRouterIntegrationTest`（疑似死代码，删除前确认无其他依赖） |
| 有界历史语义 | `get` 默认按 `window-size`（单位=轮/2 行）**由 DB `getRecentSessionDetail` LIMIT** 截断为最近 N 轮；`-1` 退化为全量（需运维显式打开）；`get` 空值/上下文缺失时返回空列表 |
| 落库兼容 | 不动 `saveConversation` 逻辑与元数据批量更新；`search` 移除落库不影响主链路（本就不落库） |
| 契约不变 | `processWithHistory` 签名对外保留；`AgentResult`/`ChatResponse` 其结构仅做**可控扩展**（如可选 `warnings` 字段，不破坏既有 `answer`/`toolContext`） |
| 事务边界（已知取舍） | `saveConversation` 无 `@Transactional`（RagSessionServiceImpl.java:51 已注释），与 `updateFeedback` 的 `@Transactional` 混用——保留现状，仅在文档记录此取舍（见 §8） |
| mem0 边界 | 不引入 mem0 |

## 6. 测试策略

- **隔离测试（关键，🔴2 回归）**：固定 `TenantContext`=tenantA，调用 `ragChatMemory.get(sessionBelongsToTenantB)`，断言**不返回 tenantB 数据**（`getSessionDetail` 按 trusted 身份过滤）。不再伪造 `CONVERSATION_ID`（已废除）。
- **`/api/rag/search` userId 信任（🔴 回归）**：携带 body `userId=他人`，断言最终落库归属 = `SecurityContext.userId`，非 body 值。
- **`/api/rag/search` context 重建（🔴 回归）**：断言 `rag_search` 落库后 `rag_session.context` 非空，且内容 = `[来源:{docName}] {content}` 拼接（`RagResultContextBuilder.build` 规则），证明未被静默置空、faithfulness 评估不退化。
- **单测（`RagChatMemory`）**：`get` 恒用上下文身份；空 `TenantContext`/null `sessionId` 返回空列表（不 NPE）；有界截断正确（按 `window-size` 取最近 N 轮）；**返回 `List<Message>` 且 Q→UserMessage、A→AssistantMessage 顺序正确**。
- **DB 层 `LIMIT` 有界（🔴2 回归）**：断言 `getRecentSessionDetail` 生成的 SQL 含 `LIMIT`（而非全量 `selectList`）；构造超 `window-size` 长会话，断言仅注入最近 N 轮且 DB 只返回 limit 行（验证非内存切片）。
- **双写回归**：同 session 一轮对话后，`rag_session` 表只新增 1 行（非 2 行），验证唯一 Owner。
- **工具检索不落库（🔴3 回归）**：`KnowledgeBaseTool`（不设 sessionId）触发 `ragSearchService.search` 后，`saveConversation` 不被调用 / `rag_session` 不新增——证明 `search` 已无副作用残留。
- **`/api/rag/search` 持久化保持（S1 回归）**：`ChatController.ragSearch` 带 `sessionId` 调用 `search` 后，`rag_session` 表新增 1 行且 userId=SecurityContext 用户——证明 Controller 层兜底落库不丢历史且归属可信。
- **有界兜底（#2 回归）**：构造超 `window-size` 的长会话，断言注入为最近 N 轮。
- **集成**：同 session 多轮能正确携带历史；rowId 回填正常。
- 验证命令采用最窄范围：`company-rag-agent` + `company-rag-rag` 相关测试类。

## 7. 改动清单

- **新增**：`RagChatMemory`（普通只读组件，非 `ChatMemoryRepository`；`get(sessionId)` 返回 `List<Message>`，恒从 `TenantContext` 取身份、内部完成 `RagSession → Message` 转换、按 `window-size` 由 DB `getRecentSessionDetail` LIMIT 截断；**不写库**）。
- **新增**：`RagSessionService.getRecentSessionDetail(tenantId, userId, sessionId, int limit)`（DB 层 `ORDER BY createTime/id DESC LIMIT limit`，取最近 limit 条再反转升序；`window-size=-1` 时退化为全量查询）。实现于 `RagSessionServiceImpl`。
- **修改**：`ChatController`——去手拼，改经 `ragChatMemory.get(request.getSessionId())` 获取历史再注入；`chat` 的 `saveConversation` 保留（可信身份，唯一 Owner）。
- **修改**：`RagSearchServiceImpl`——**移除 L111-122 条件性 `saveConversation`**（search 零副作用；不用 persist 开关）；将 L79-84 的 context 拼接逻辑**抽出为公共方法** `RagResultContextBuilder.build`（`search` 与 `ragSearch` 共用）。
- **修改**：`ChatController.ragSearch`（`/api/rag/search`，废弃但存活）——**净新增 `SecurityContext` 身份提取**（当前无 SecurityUser 提取，须仿照 `chat` L108-124 新写：`principal instanceof SecurityUser` 取 `verifiedUserId`，null 抛 `IllegalStateException`），**不再信任 `query.getUserId()`**；`search` 返回后带 `sessionId` 时用可信身份自行 `saveConversation`，**context 用 `RagResultContextBuilder.build(result)` 从 `result.getChunks()` 重建**；必要时补齐 `TenantContext` 填充（#1/#3/#8/#14）。⚠️ 若只移落库位置、漏加身份提取，🔴 userId 越权洞未修。
- **修改**：`RagAgentService`——`processWithHistory` 兼容保留，内部逻辑不动。
- **新增配置**：仅 `rag.memory.window-size`（默认有界值，单位=轮/2 行，如 50 轮；`-1`=全量）。**不新增 `rag.memory.enabled` 开关**——`RagChatMemory` 是薄安全包装、风险极低，去掉开关可彻底避免「新组件 + 旧手拼」两份历史加载逻辑长期共存（YAGNI，见 §5/§8）。
- **不动**：`rag_session` 表、`RagSessionService` 落库逻辑（`saveConversation`）、既有会话接口、mem0。

## 8. 风险与观察项

- **隔离被绕过（最高优先级，🔴2）**：读与落库身份必须恒取自可信来源（`TenantContext`/`headerTenantId`+`SecurityContext`），`sessionId` 仅定位。**验收铁律 + 隔离测试双重约束**。
- **`/api/rag/search` userId 越权（🔴 新增）**：修复前客户端可伪造 `query.getUserId()` 污染他人归属；修复后落库 userId 一律取 `SecurityContext`。**回归测试兜底**。
- **双写隐患（🔴3）**：`search` 移除条件性落库后零副作用；唯一写入口为两个 Controller。`RagChatMemory` 不落库。
- **`/api/rag/search` 回归（S1）**：该废弃端点存活且依赖 `search` 原条件性落库；移除后由 `ChatController.ragSearch` 在 Controller 层用可信身份兜底，**否则带 `sessionId` 的旧客户端静默丢历史**。
- **有界历史（🔴 生产兜底）**：默认不无界，按 `window-size` **由 DB `getRecentSessionDetail` LIMIT** 截断为最近 N 轮，避免 O(n) 读全表与 prompt 溢出 LLM 上下文；超长会话仅截断不报错（可加「历史已截断」提示）。`-1` 为全量回退需运维显式决定。
- **DB `LIMIT` 契约（🔴 强制）**：有界截断必须在 DB 层完成（`ORDER BY createTime/id DESC LIMIT n`）。**禁止** `getSessionDetail` 全量后 `subList`，否则 DB 仍 O(n)、§3.5 性能承诺不兑现。
- **Advisor 取舍（YAGNI）**：不引入 `MessageChatMemoryAdvisor`/`CONVERSATION_ID`，规避了与 ReAct 接入对齐的前置工作，也避免了"给 `getSessionDetail` 改名"的无收益抽象。
- **事务边界（已知取舍）**：`saveConversation` 无 `@Transactional`（RagSessionServiceImpl.java:51 已注释）+ `L111-122` 原 `try/catch` 仅 `log.warn` 吞异常——前者保留现状、在 §5/§7 注明；后者随 `search` 移除落库而一并退出作用域。
- **`/api/rag/search` context 丢失（🔴 新增）**：`RagResult` 无 context 字段；若 `ragSearch` 不按 `result.getChunks()` 重建 context，`rag_session.context` 会静默为空，破坏后续对该行的 faithfulness 评估（评估依赖 context 印证）。已用 `RagResultContextBuilder.build` 统一重建 + 回归测试兜底。
- **不引入 `rag.memory.enabled` 开关**：`RagChatMemory` 是薄安全包装、风险极低，去掉开关避免「新组件 + 旧手拼」两份历史加载逻辑长期共存（YAGNI）；否则每次改动需双份维护、测试面翻倍。
- **跨方案依赖**：本 spec 与 reflection 同改 `RagAgentService`（reflection 在末尾委派自省），实施排序在阶段 3 收口，避免同一方法多处并行修改冲突（见编排总览）。