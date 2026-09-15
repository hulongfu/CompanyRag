# 设计方案评审与修订建议（补充：双写误诊断撤诉 + 内嵌副作用隐患）

> 评审对象：`D:/tmp/CompanyRag/docs/superpowers/specs/` 下 7 份 2026-09-14-* 方案
> 评审方法：全部结论基于**实读 CompanyRag 源码**（行号证据），非记忆推测
> 本文件为对 2026-09-14 首轮评审的补充修订，重点修正 🔴3 双写误诊断

---

## 0. 关键变更摘要

- **🔴3（"修复真实双写"）正式撤诉**：经用户质证 + 实读源码复核，`/api/chat` 主链路**不存在双写**。
- 原"唯一落库 Owner"决策本身**保留**，但性质从"修复现有故障"更正为"**固化单写、防止回归的防御性约束**"。
- 真正的设计隐患被重新定位到 `RagSearchServiceImpl.search` L115 的**内嵌 `saveConversation` 副作用**（随调用方是否传 `sessionId` 隐式触发），属**潜在双写耦合**，非现状故障。

---

## 1. 用户质证证据链（已逐条实读核实 ✅）

| # | 声明 | 实读证据 | 结果 |
|---|---|---|---|
| 1 | `RagSearchServiceImpl.search` 落库是**有条件**的 | `RagSearchServiceImpl.java:112` `if (query.getSessionId() != null)` 才进 L115 `saveConversation` | ✅ |
| 2 | `KnowledgeBaseTool` 构造的 `RagQuery` **从不设 `sessionId`** | `KnowledgeBaseTool.java:80-83` 只 `setTenantId/setQuery/setTopK` | ✅ |
| 3 | 主链路 agent 调 `searchKnowledgeBase` → `search` → `query.getSessionId()` 为 null → L115 不落库 | 由 1+2 直接推出 | ✅ |
| 4 | 唯一设 `ragQuery.setSessionId` 处是 `ChatRouter.buildRagQuery:308`，属废弃链路 | 全仓搜 `setSessionId`：`ragQuery.setSessionId` 仅 `ChatRouter.java:308`；其余均为 `TenantContext`/测试 | ✅ |
| 5 | `RagAgentService:207` 只把 `sessionId` 写 `TenantContext`，工具从 `TenantContext` 读 `tenantId`，凭据不回流 `RagQuery` | `RagAgentService.java:198-207`；`KnowledgeBaseTool.java:81` 读 `TenantContext.getTenantId()` | ✅ |

**结论**：`ChatController.chat`（L121）每轮落库 1 次 → 单写。误诊断撤回。

---

## 2. 重新定位的真实隐患（替代原 🔴3）

> 严重度：🟡（潜在双写隐患，**非现状故障**）

### 2.1 隐患描述
`RagSearchServiceImpl.search` 在其方法体内**内嵌了一个隐性的 `saveConversation` 副作用**（L115），且该副作用的触发**完全取决于调用方是否传入 `sessionId`**（L112 守卫）。

- 现状主链路：`KnowledgeBaseTool` 不设 `sessionId` → 不触发 → 无双写。
- 当前**唯一**会触发该内嵌落库的调用方：已废弃的 `ChatRouter.buildRagQuery:308`（DOCUMENT 意图经 `processDocument → search` 且带 `sessionId`）。该链路仅用于测试/向后兼容，不在 `/api/chat` 主路径。
- **回归风险**：若未来任何主链路调用方在 `search` 的 `RagQuery` 上补传 `sessionId`，即将与 `ChatController.chat`（L121）**真实双写**。

### 2.2 推荐修订方向（任选其一，建议写入 spec §8）
1. **抽离副作用**：把 `saveConversation` 从 `search` 内移出，改由显式调用方负责落库；`search` 回归纯检索职责。
2. **显式契约 + 废弃标记**：保留现状但给 `search` 的落库分支加 `@Deprecated` 注释与文档契约，明确"`search` 不负责落库，落库由 `ChatController` 唯一负责"，并断言"`sessionId != null` 分支仅服务已废弃 ChatRouter"。
3. **加防护断言（临时）**：在 `search` 落库分支加 `log.warn`/断言，一旦主链路调用方误传 `sessionId` 立即报警，防止静默双写。

---

## 3. 对 `2026-09-14-memory-rework-design.md` 的精确修订文本

> 以下为可直接替换的逐节文本。未列出的章节（§2.1、§3.1、§3.2、§3.4、§3.5、§4、§7 主体）保持不变。

### 3.1 顶部「修订说明」（L7）
**原文：**
```
> 修订说明：修复 🔴2（隔离身份必须取自 `TenantContext` 而非解析可伪造 ID）、🔴3（明确历史落库唯一 Owner 防双写），并采用手动注入主路径、界定 window-size 语义。
```
**替换为：**
```
> 修订说明：明确 🔴2（隔离身份必须取自 `TenantContext` 而非解析可伪造 ID）；将"唯一落库 Owner"定为**防御性约束**（现状主链路单写、无双写，非修复故障）；并采用手动注入主路径、界定 window-size 语义。
```

### 3.2 §1 目标（L16）
**原文：**
```
- **唯一落库 Owner**：历史落库在 ChatController 与 Advisor 间**二选一**，防双写。
```
**替换为：**
```
- **唯一落库 Owner（防御性约束）**：落库唯一方固定为 `ChatController`；`RagChatMemory` 只读不写。现状主链路（`/api/chat`）已是单写，本约束用于固化现状、防止未来回归（见 §8）。
```

### 3.3 §3.3 唯一落库 Owner（L62-71，整节替换）
**原文：**
```
### 3.3 唯一落库 Owner（🔴3 已修复）

**决定：`ChatController` 继续负责历史落库；`RagChatMemory` 只负责读，不写。**

- 现状 `ChatController.chat`（L121 已有 `saveConversation`）保留为唯一落库方。
- `RagChatMemory` 只实现"读历史注入"（`get`），**不实现 `add` 的落库**（或 `add` 为空操作/不调用 `saveConversation`）。
- 明确写入改动清单："`RagChatMemory` 不调用 `saveConversation`，落库仍由 `ChatController` 负责"，避免 Advisor 触发双写。

> 备选（不采用）：若未来改由 Advisor 写、ChatController 删保存，需同步删掉 L121 的 `saveConversation`，并验证回填 id / 异步元数据更新不受影响。本 spec 默认选"ChatController 落库"，改动更小、风险更低。
```
**替换为：**
```
### 3.3 唯一落库 Owner（防御性约束，现状无双写）

**决定：`ChatController` 继续负责历史落库；`RagChatMemory` 只负责读，不写。**

- 现状 `ChatController.chat`（L121 已有 `saveConversation`）保留为唯一落库方。
- `RagChatMemory` 只实现"读历史注入"（`get`），**不实现 `add` 的落库**（或 `add` 为空操作/不调用 `saveConversation`）。
- 明确写入改动清单："`RagChatMemory` 不调用 `saveConversation`，落库仍由 `ChatController` 负责"。

> 评审修订说明：此前将"唯一 Owner"标注为"修复真实双写 🔴3"是**方向性误诊断**。实读核实 `/api/chat` 主链路**无双写**——`RagSearchServiceImpl.search` 的 `saveConversation` 受 `query.getSessionId() != null` 守卫（L112），而主链路 `KnowledgeBaseTool` 构造的 `RagQuery` 只设 `tenantId/query/topK`、从不设 `sessionId`（`KnowledgeBaseTool.java:80-83`），故工具执行期不落库；`ChatController.chat` 每轮落库 1 次，为单写。本约束价值在于**固化单写、防未来回归**，而非修复现有故障。

> 备选（不采用）：若未来改由 Advisor 写、ChatController 删保存，需同步删掉 L121 的 `saveConversation`，并验证回填 id / 异步元数据更新不受影响。本 spec 默认选"ChatController 落库"，改动更小、风险更低。
```

### 3.4 §5 安全表（L107 行）
**原文：**
```
| 唯一落库 Owner（🔴3） | `RagChatMemory` 只读不写；落库仍由 `ChatController` 负责，防双写 |
```
**替换为：**
```
| 唯一落库 Owner（防御性约束） | `RagChatMemory` 只读不写；落库唯一方 = `ChatController`。现状主链路单写、无双写；本约束固化现状、防回归 |
```

### 3.5 §6 测试策略（L117）
**原文：**
```
- **双写回归**：同 session 一轮对话后，`rag_session` 表只新增 1 行（非 2 行），验证唯一 Owner。
```
**替换为：**
```
- **单写回归（防回归，非修复）**：同 session 一轮对话后，`rag_session` 表只新增 1 行（非 2 行）。注意：当前主链路本就单写，本测试用于锁定现状、防止 `RagSearchServiceImpl.search` 内嵌 `saveConversation`（L115）在未来被误触发导致双写（见 §8）。
```

### 3.6 §8 风险与观察项（L131 整条替换）
**原文：**
```
- **双写（评审 🔴3）**：唯一 Owner = ChatController；`RagChatMemory` 不落库。改动清单明确"谁删保存/谁不写"。
```
**替换为：**
```
- **内嵌落库副作用（潜在双写隐患，🟡 需收敛）**：`RagSearchServiceImpl.search` 在 L115 **内嵌隐性 `saveConversation` 副作用**，且仅由调用方是否传入 `sessionId` 控制（L112 `query.getSessionId() != null`）。现状主链路（`KnowledgeBaseTool` 不设 `sessionId`）不触发，故无双写；当前唯一会触发该内嵌落库的调用方是已废弃的 `ChatRouter.buildRagQuery`（L308，DOCUMENT 意图经 `processDocument→search` 带 `sessionId`）。**回归风险**：若未来任何主链路调用方在 `search` 上补传 `sessionId`，将与 `ChatController.chat`（L121）真实双写。建议：将 `saveConversation` 从 `search` 抽离到显式调用方，或加 `@Deprecated`+文档契约明确"`search` 不负责落库"，消除"副作用随传参隐式触发"的耦合。
```

---

## 4. 首轮其他结论的当前状态（未经本次质证推翻）

| 编号 | 结论 | 状态 |
|---|---|---|
| 🔴1 | 评审源 `2026-09-14-design-review.md` 全仓不存在，所有 🔴/🟡 修订标记无法溯源 | **维持** |
| 🟡1 | memory 🔴2"可伪造 `CONVERSATION_ID` 解析越权"在当前代码不存在（tenantId 来自 `X-Tenant-Id` 头 + `SecurityContext`，请求体被忽略）；应改写为对新 `RagChatMemory` 的纵深防御 | **维持** |
| 🟡2 | human `warning` 与 reflection 检索上下文争用同一 `toolContext` 单 String 字段，需独立载体 | **维持** |
| 🟡3 | nl2sql 返回 `String` vs `ToolResult` 与"对外签名不变"自相矛盾 | **维持** |
| 🟡4 | ETL 伪代码 `snapshot.restore()`/`TenantContextScope` 不存在，真实 API 为 `apply()`/`clear()` | **维持** |
| 🟡5 | ChatRouter 用 `request.getTenantId()` + userId 缺省 `1L` 落库 | **降级**：用户确认 `ChatRouter` 已废弃、仅测试/向后兼容，不在主路径；维持"若仍在线须统一走 `X-Tenant-Id`"的备注即可 |

---

## 5. 待确认事项

1. 是否将上述 §3 修订文本**直接写入** `2026-09-14-memory-rework-design.md`？（当前仅提供文本，未改原文）
2. `2026-09-14-design-review.md` 是否在你本地其他路径？若补上，🔴1 即可闭环。
3. 内嵌副作用（§2）是否纳入本次迭代，还是另开 spec 收敛？
