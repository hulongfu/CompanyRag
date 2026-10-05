# 会话记忆规范化（RagChatMemory 只读 helper + Controller 层统一落库）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用只读的 `RagChatMemory` 统一承载会话历史读取（有界、DB LIMIT、内部转换），并把落库责任统一收口到 Controller 层（服务端可信身份）。同时修复 `/api/rag/search` 的 userId 越权缺口，并为 `ragSearch` 在 Controller 层补齐落库（含 context 重建）。

**Architecture:**
- 新增只读组件 `RagChatMemory`（在 `company-rag-rag` 模块）：`get(sessionId)` 返回 `List<Message>`。
  - 恒从 `TenantContext` 取可信身份（tenantId/userId），空值/上下文缺失返回空列表（不 NPE）。
  - 内部调 **DB 层 LIMIT** 的 `getRecentSessionDetail(tenantId, userId, sessionId, limit)`（新增 Service 方法），`limit = window-size * 2`（一轮=2 行）。
  - 内部完成 `RagSession → Message` 转换（Q→UserMessage，A→AssistantMessage，升序）。**只读不写库**。
- `RagSearchServiceImpl.search` **移除条件性 `saveConversation`**（零副作用），并把 L79-84 的 context 拼接逻辑**抽出为公共方法** `RagResultContextBuilder.build(RagResult)`。
- `ChatController.chat`：去手拼，改经 `ragChatMemory.get(sessionId)` 获取有界历史；`saveConversation` 保留（唯一 Owner）。
- `ChatController.ragSearch`：**净新增 SecurityContext 身份提取**（仿 `chat` L107-112），不再信任 `query.getUserId()`；`search` 返回后带 `sessionId` 时用可信身份自行 `saveConversation`，context 用 `RagResultContextBuilder.build` 重建；补齐 `TenantContext` 填充。
- 新增配置 `rag.memory.window-size`（默认 50 轮；`-1`=全量退化）。**不引入 `rag.memory.enabled` 开关**（YAGNI）。

**Tech Stack:** Java 17 + Spring Boot 3.4.4 + MyBatis-Plus 3.5.9 + Spring AI 1.0.4

---

## 文件结构

### 新增文件

**后端组件** (`company-rag-rag` 模块):
```
company-rag-rag/src/main/java/com/company/rag/rag/memory/
└── RagChatMemory.java              # 只读历史 helper（返回 List<Message>，内部 DB LIMIT + 转换）

company-rag-rag/src/main/java/com/company/rag/rag/service/support/
└── RagResultContextBuilder.java    # 从 RagResult.getChunks() 重建 context（search/ragSearch 共用）
```

**测试类** (`company-rag-rag` 模块):
```
company-rag-rag/src/test/java/com/company/rag/rag/memory/
└── RagChatMemoryTest.java          # 身份来源/空值/有界/转换/隔离

company-rag-rag/src/test/java/com/company/rag/rag/service/support/
└── RagResultContextBuilderTest.java
```

**web 测试** (`company-rag-web` 模块):
```
company-rag-web/src/test/java/com/company/rag/web/controller/ChatControllerIntegrationTest.java  # 现有，补充用例（见 Task 6）
```

### 修改文件

```
company-rag-rag/src/main/java/com/company/rag/rag/service/RagSessionService.java   # + getRecentSessionDetail 接口声明
company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSessionServiceImpl.java  # + 实现
company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java    # 移除落库 + 抽 context
company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java   # chat 去手拼 / ragSearch 修身份
company-rag-bootstrap/src/main/resources/application.yml                            # + rag.memory.window-size
```

---

## 实施任务

### Task 1: 新增 `RagSessionService.getRecentSessionDetail`（DB 层 LIMIT 有界）

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/RagSessionService.java`
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSessionServiceImpl.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/service/RagSessionServiceImplTest.java`（如存在则补充，否则跳过带 DBMock）

- [ ] **Step 1: 在接口 `RagSessionService` 新增方法声明**

  在 `getSessionDetail` 之后新增：
  ```java
  /**
   * 获取最近会话详情（DB 层 LIMIT 有界截断）。
   * @param limit 最大返回行数（= window-size × 2，一轮=2 行）；limit <= 0 时等价于全量（供 window-size=-1 退化）
   * @return 按时间升序（createTime/id）返回最近 limit 条；超长只取最近 limit 条再反转回升序
   */
  List<RagSession> getRecentSessionDetail(Long tenantId, Long userId, String sessionId, int limit);
  ```

- [ ] **Step 2: 在 `RagSessionServiceImpl` 实现该方法（强制 DB 层 LIMIT，禁止内存切片）**

  ```java
  @Override
  public List<RagSession> getRecentSessionDetail(Long tenantId, Long userId, String sessionId, int limit) {
      if (tenantId == null || userId == null || sessionId == null) {
          return List.of();
      }
      // 【强制契约】有界须由 DB 层 LIMIT 完成：ORDER BY createTime/id DESC LIMIT n 取最近 n 条，再反转回升序。
      // 禁止 getSessionDetail 全量查询后内存 subList —— 那会让 DB 仍 O(n) 读全表，window-size 的性能承诺不兑现。
      if (limit <= 0) {
          // -1 退化：等价全量升序（运维显式打开，知晓 O(n)+token 风险）
          return sessionMapper.selectList(new LambdaQueryWrapper<RagSession>()
                  .eq(RagSession::getTenantId, tenantId)
                  .eq(RagSession::getUserId, userId)
                  .eq(RagSession::getSessionId, sessionId)
                  .orderByAsc(RagSession::getCreateTime));
      }
      List<RagSession> recentDesc = sessionMapper.selectList(new LambdaQueryWrapper<RagSession>()
              .eq(RagSession::getTenantId, tenantId)
              .eq(RagSession::getUserId, userId)
              .eq(RagSession::getSessionId, sessionId)
              .orderByDesc(RagSession::getCreateTime)
              .last("LIMIT " + limit));
      // 反转回升序，保持原顺序语义
      java.util.Collections.reverse(recentDesc);
      return recentDesc;
  }
  ```

  > 注意：`createTime` 可能同刻，排序以 `orderByDesc(createTime)` 为主；若需绝对稳定可用 `orderByDesc(createTime).orderByDesc(RagSession::getId)`（id 为自增，见 RagSession.java:16-17）。实现时两条排序统一用同一个字段口径，避免 off-by-N。

- [ ] **Step 3: 运行测试验证（最窄范围）**

  ```bash
  cd company-rag-rag
  mvn test -Dtest=RagSessionServiceImplTest -q
  ```
  若无该类或无法单测（需 DB），则跳过并以后续集成测（Task 6）为准；执行/跳过分明记录。

- [ ] **Step 4: Commit**

  ```bash
  git add company-rag-rag/src/main/java/com/company/rag/rag/service/RagSessionService.java
  git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSessionServiceImpl.java
  git commit -m "feat(memory): 新增 RagSessionService.getRecentSessionDetail（DB 层 LIMIT 有界截断，-1 退化全量）"
  ```

---

### Task 2: 新增上下文重建公共方法 `RagResultContextBuilder`

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/service/support/RagResultContextBuilder.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/service/support/RagResultContextBuilderTest.java`

- [ ] **Step 1: 创建 `RagResultContextBuilder`（抽自 search L79-84 拼接逻辑）**

  ```java
  package com.company.rag.rag.service.support;

  import com.company.rag.rag.model.RagResult;
  import java.util.stream.Collectors;

  /** 从 RAG 检索结果重建 prompt/落库使用的 context（search 与 ragSearch 共用，避免两处漂移）。 */
  public final class RagResultContextBuilder {

      private RagResultContextBuilder() {}

      /**
       * 按现状规则重建 context：逐块 "[来源:{documentName}] {content}"，块间 "\n\n"。
       * RagResult 无 context 字段（RagResult.java:11-16），故须从 chunks 重建；避免 rag_session.context 静默为空。
       */
      public static String build(RagResult result) {
          if (result == null || result.getChunks() == null || result.getChunks().isEmpty()) {
              return "";
          }
          return result.getChunks().stream()
                  .map(c -> {
                      String name = c.getDocumentName() != null ? c.getDocumentName() : "未知";
                      return "[来源:" + name + "] " + c.getContent();
                  })
                  .collect(Collectors.joining("\n\n"));
      }
  }
  ```

- [ ] **Step 2: 创建 `RagResultContextBuilderTest`（正常/边界/异常）**

  ```java
  package com.company.rag.rag.service.support;

  import com.company.rag.rag.model.RagResult;
  import com.company.rag.rag.model.RagResult.ChunkResult;
  import org.junit.jupiter.api.Test;
  import java.util.List;
  import static org.junit.jupiter.api.Assertions.*;

  class RagResultContextBuilderTest {
      @Test
      void build_单块_含来源与内容() { /* 断言 "[来源:文档A] 文本" */ }
      @Test
      void build_多块_以双换行分隔() { /* 断言块间 "\n\n" */ }
      @Test
      void build_null结果_返回空串() { assertEquals("", RagResultContextBuilder.build(null)); }
      @Test
      void build_空chunks_返回空串() { assertEquals("", RagResultContextBuilder.build(new RagResult())); }
      @Test
      void build_文档名为null_回落未知() { /* 断言 "[来源:未知] ..." */ }
  }
  ```

- [ ] **Step 3: 运行测试验证（最窄范围）**

  ```bash
  cd company-rag-rag
  mvn test -Dtest=RagResultContextBuilderTest -q
  ```
  Expected: PASS

- [ ] **Step 4: Commit**

  ```bash
  git add company-rag-rag/src/main/java/com/company/rag/rag/service/support/RagResultContextBuilder.java
  git add company-rag-rag/src/test/java/com/company/rag/rag/service/support/RagResultContextBuilderTest.java
  git commit -m "feat(memory): 新增 RagResultContextBuilder 上下文重建公共方法（search/ragSearch 共用）"
  ```

---

### Task 3: 修改 `RagSearchServiceImpl`——移除落库副作用 + 复用 context 重建

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java`

- [ ] **Step 1: 移除 `search` 内条件性 `saveConversation`（L111-122）**

  删除当前 Step 6「保存对话记录」整段：
  ```java
  // 6. 保存对话记录（如果有 sessionId）
  if (query.getSessionId() != null) {
      try {
          Long userId = query.getUserId() != null ? query.getUserId() : 1L;
          ragSessionService.saveConversation(...);
      } catch (Exception e) {
          log.warn("保存对话记录失败", e);
      }
  }
  ```
  使 `search` 变为纯检索（零副作用），杜绝隐藏双写与 body userId 污染。

- [ ] **Step 2: 复用 `RagResultContextBuilder.build` 重建 context（替换 L79-84 内联拼接）**

  将 `search` 内 `String context = chunks.stream()...` 替换为：
  ```java
  // 4. 构建 Prompt 并调用 LLM（context 由公共方法重建）
  String context = RagResultContextBuilder.build(result);  // 注意：此时 result 尚未组装，需先建 result 并 setChunks
  ```
  > 实现注意：现有 L79-84 在 step4、组装 result 在 step5。重构后需保证 `result.setChunks(chunks)` 在 build 之前调用（调整顺序），或 `build` 直接接收 `chunks`/临时 RagResult。选取最小改动：先 `result.setChunks(chunks)` 再 `String context = RagResultContextBuilder.build(result)`，然后删掉原内联 stream。

- [ ] **Step 3: 移除 `search` 中对 `ragSessionService` 的落库依赖（若不再使用则删注入字段）**

  检查 `RagSearchServiceImpl` 是否还有其他 `ragSessionService` 使用点；若 `search` 移除后无其他使用，删除对应 `@Autowired`/`final` 字段，避免无用依赖。若删除后出现编译错误（其它方法在用），保留字段。

- [ ] **Step 4: 编译验证（最窄范围，仅编译模块）**

  ```bash
  cd company-rag-rag
  mvn -q compile
  ```
  Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

  ```bash
  git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java
  git commit -m "refactor(memory): RagSearchServiceImpl.search 移除条件性落库，context 改用公共方法重建"
  ```

---

### Task 4: 新增只读组件 `RagChatMemory`

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/memory/RagChatMemory.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/memory/RagChatMemoryTest.java`

- [ ] **Step 1: 创建 `RagChatMemory`（注入 `RagSessionService` + window-size 配置）**

  ```java
  package com.company.rag.rag.memory;

  import com.company.rag.rag.entity.RagSession;
  import com.company.rag.rag.service.RagSessionService;
  import com.company.rag.tenant.context.TenantContext;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.ai.chat.messages.AssistantMessage;
  import org.springframework.ai.chat.messages.Message;
  import org.springframework.ai.chat.messages.UserMessage;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.stereotype.Component;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * 只读会话历史 helper。
   * 职责：按 TenantContext 可信身份读取有界历史并转换为 Message 列表。只读不写库。
   */
  @Slf4j
  @Component
  @RequiredArgsConstructor
  public class RagChatMemory {

      private final RagSessionService ragSessionService;

      @Value("${rag.memory.window-size:50}")
      private int windowSize;

      /** 默认窗口（轮），与 yml 默认一致。 */
      static final int DEFAULT_WINDOW_SIZE = 50;

      /**
       * 获取有界历史消息（内部完成 RagSession→Message 转换，保持升序）。
       * 身份恒取 TenantContext（不可客户端伪造）；空值/上下文缺失返回空列表。
       */
      public List<Message> get(String sessionId) {
          Long tenantId = TenantContext.getTenantId();
          Long userId = TenantContext.getUserId();
          if (tenantId == null || userId == null || sessionId == null) {
              log.debug("会话历史读取条件不足，返回空列表：sessionId={}", sessionId);
              return List.of();
          }
          // 一轮=2 行（Q+A）；windowSize<=0 视为 -1 退化全量（由 service 层处理 limit<=0）
          int limit = (windowSize > 0) ? windowSize * 2 : 0;
          List<RagSession> sessions =
                  ragSessionService.getRecentSessionDetail(tenantId, userId, sessionId, limit);
          return toMessages(sessions);
      }

      /** RagSession → Message：Q→UserMessage，A→AssistantMessage，保持升序。 */
      private List<Message> toMessages(List<RagSession> sessions) {
          if (sessions == null || sessions.isEmpty()) {
              return List.of();
          }
          List<Message> messages = new ArrayList<>(sessions.size() * 2);
          for (RagSession s : sessions) {
              messages.add(new UserMessage(s.getQuery()));
              messages.add(new AssistantMessage(s.getAnswer()));
          }
          return messages;
      }
  }
  ```

- [ ] **Step 2: 创建 `RagChatMemoryTest`（正常/边界/异常/隔离）**

  覆盖（Mock `RagSessionService`，用子类化或反射注入 windowSize，或加包级 setter）：
  - `get` 恒用 `TenantContext` 身份：构造 `TenantContext.setTenantId/setUserId/setSessionId` 后调用，`verify(ragSessionService).getRecentSessionDetail(tenantId, userId, sessionId, 100)`（window 50 → limit 100）。
  - **隔离测试（🔴2 回归）**：`TenantContext`=tenantA，service mock 返回一组 RagSession（模拟 tenantB 不应命中——因 service 侧已按 trusted 过滤，此处验证 `get` 把 tenantId/userId 正确传入，不传 sessionId 里伪造的身份）。
  - 空值保护：`TenantContext` 未设置 / `sessionId=null` → 返回空列表，service 不被调用（不 NPE）。
  - 有界换算：window=50 → limit=100；window=-1（或 <=0）→ limit=0（透传给 service 退化全量）。
  - 转换正确：mock 返回 2 个 RagSession（各含 query/answer），断言返回 `List<Message>` 顺序为 [UserMessage(q1), AssistantMessage(a1), UserMessage(q2), AssistantMessage(a2)]。
  - 保持升序：service 返回已反转升序的数据，`get` 不再二次反转。

- [ ] **Step 3: 运行测试验证（最窄范围）**

  ```bash
  cd company-rag-rag
  mvn test -Dtest=RagChatMemoryTest -q
  ```
  Expected: PASS

- [ ] **Step 4: Commit**

  ```bash
  git add company-rag-rag/src/main/java/com/company/rag/rag/memory/RagChatMemory.java
  git add company-rag-rag/src/test/java/com/company/rag/rag/memory/RagChatMemoryTest.java
  git commit -m "feat(memory): 新增只读组件 RagChatMemory（有界历史 + TenantContext 身份 + Message 转换）"
  ```

---

### Task 5: 修改 `ChatController.chat`——去手拼、改经 `RagChatMemory`

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`

- [ ] **Step 1: 注入 `RagChatMemory`**

  在依赖字段区（L49-51 附近）新增：
  ```java
  private final RagChatMemory ragChatMemory;
  ```
  > `@RequiredArgsConstructor` 会自动注入；需 ensure `ragChatMemory` 为 `final` 字段。

- [ ] **Step 2: 替换手拼历史（L131-139 → 单行 `ragChatMemory.get`）**

  将当前：
  ```java
  if (request.getSessionId() != null && request.getTenantId() != null) {
      // 读取历史会话（按时间升序）
      List<RagSession> historySessions = ragSessionService.getSessionDetail(
              request.getTenantId(), verifiedUserId, request.getSessionId());
      // 转换为 Message 列表
      List<Message> historyMessages = new ArrayList<>();
      for (RagSession session : historySessions) {
          historyMessages.add(new UserMessage(session.getQuery()));
          historyMessages.add(new AssistantMessage(session.getAnswer()));
      }
      log.debug(...);
      result = ragAgentService.processWithHistory(historyMessages, request.getQuery());
  } else { ... }
  ```
  替换为：
  ```java
  if (request.getSessionId() != null && request.getTenantId() != null) {
      // 唯一读取入口：经 RagChatMemory 获取有界历史（内部取 TenantContext 身份 + 转换为 Message）
      List<Message> historyMessages = ragChatMemory.get(request.getSessionId());
      log.debug("加载会话历史：sessionId={}, historySize={}",
              request.getSessionId(), historyMessages.size() / 2);
      result = ragAgentService.processWithHistory(historyMessages, request.getQuery());
  } else { ... }
  ```
  > 注意：`get` 内部已恒取 `TenantContext` 身份，而 `chat` 已在 L90/105/124 填充 `TenantContext`（含 L124 `TenantContext.setUserId(verifiedUserId)`），故可信身份来源成立。
  > 若 `RagChatMemory` 在 `company-rag-rag` 模块而 `ChatController` 在 `company-rag-web` 模块，需确认 `company-rag-web` 依赖 `company-rag-rag`（既有 `RagSearchService` 已跨模块使用，依赖成立）。
  > 移除 `historyMessages` 相关无用 import（如 `ArrayList`、`RagSession`、`UserMessage`/`AssistantMessage`），避免残留未用 import（Java 仅告警不影响编译，但保持整洁）。

- [ ] **Step 3: 移除无用的 `RagSession` import（若 chat 不再使用）**

  检查 `ChatController` 中其他使用 `RagSession` 的地方；`updateFeedback` 用的是 `RagSessionMeta`/直接 service 调用。若 `RagSession` 不再被引用则删除其 import。

- [ ] **Step 4: 编译验证（最窄范围，web 模块编译）**

  ```bash
  cd company-rag-web
  mvn -q compile
  ```
  Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

  ```bash
  git add company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
  git commit -m "refactor(memory): ChatController.chat 去手拼历史，改经 RagChatMemory.get 有界读取"
  ```

---

### Task 6: 修改 `ChatController.ragSearch`——净新增身份提取 + Controller 层落库 + context 重建

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/model/RagResult.java`（如需在 build 前 setChunks 的中间态，见 Task 3；此 Task 无需改）

- [ ] **Step 1: 净新增 `SecurityContext` 身份提取（仿 `chat` L107-112）**

  在 `ragSearch` 方法内、`query.setTenantId(headerTenantId)`（L255）之后新增：
  ```java
  // 【安全关键】用户 ID 必须从已认证的安全上下文中获取，不能信任请求体 query.getUserId()
  Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  Long verifiedUserId = null;
  if (principal instanceof SecurityUser) {
      verifiedUserId = ((SecurityUser) principal).getUserId();
  }
  if (verifiedUserId == null) {
      log.error("用户 ID 缺失，拒绝服务：query={}", query.getQuery());
      throw new IllegalStateException("用户 ID 不能为空，请确认用户已正确登录");
  }
  ```
  这是**净新增代码块**（当前 `ragSearch` 完全没有 SecurityUser 提取），切勿只挪落库位置而漏加——否则 🔴 userId 越权洞未修。

- [ ] **Step 2: 结果组装后由 Controller 用可信身份落库 + context 重建**

  将返回前的 `RagResult result = ragSearchService.search(query); return R.ok(result);` 改为：
  ```java
  RagResult result = ragSearchService.search(query);

  // 【落库 Owner = Controller 层】带 sessionId 时用服务端可信身份自行保存（context 从 chunks 重建）
  if (query.getSessionId() != null) {
      // 如 /api/rag/search 需要 TenantContext（后续功能可能依赖），在此补齐会话/用户上下文
      TenantContext.setSessionId(query.getSessionId());
      // 在已有 headerTenantId 基础上补齐用户/会话上下文（幂等）
      // TenantContext.setTenantId(headerTenantId); TenantContext.setUserId(verifiedUserId);
      String context = RagResultContextBuilder.build(result);
      ragSessionService.saveConversation(
              headerTenantId, query.getSessionId(), verifiedUserId,
              query.getQuery(), result.getAnswer(), context,
              null, null, null);
  }

  return R.ok(result);
  ```
  > **context 来源须显式**：`RagResult` 无 context 字段，若不重建，`rag_session.context` 会静默为空，破坏后续 faithfulness 评估。此处用 `RagResultContextBuilder.build(result)` 从 `result.getChunks()` 重建（与 `search` 舍弃的逻辑等价，现为公共方法）。

- [ ] **Step 3: 补充 import**

  在 `ChatController` 顶部 `import com.company.rag.rag.service.support.RagResultContextBuilder;`（若 Task 3 已跨模块暴露）。确认 `SecurityContextHolder`、`SecurityUser` 已 import（chat 已在用）。

- [ ] **Step 4: 编译 + 运行相关测试验证**

  ```bash
  cd company-rag-web
  mvn -q compile
  mvn test -Dtest=ChatControllerIntegrationTest -q
  ```
  补充/更新用例：
  - **userId 信任（🔴 回归）**：携带 body `userId=他人` 调 `/api/rag/search`，断言 `rag_session` 落库 userId=SecurityContext 用户（非 body 值）。
  - **context 重建（🔴 回归）**：断言 `/api/rag/search` 落库后 `rag_session.context` 非空，且 = `[来源:{docName}] {content}` 拼接。
  - **持久化保持（S1 回归）**：带 `sessionId` 调用后 `rag_session` 新增 1 行。
  - **双写回归**：同 session 一轮后仅 1 行。
  若 `ChatControllerIntegrationTest` 需完整上下文而无法便捷跑通，则改用 `MockMvc` 级/`RagSearchService` mock 的单测并记录跳过理由。

- [ ] **Step 5: Commit**

  ```bash
  git add company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
  git add company-rag-web/src/test/java/com/company/rag/web/controller/ChatControllerIntegrationTest.java
  git commit -m "fix(memory): ragSearch 净新增 SecurityContext 身份提取 + Controller 层落库与 context 重建（修 userId 越权）"
  ```

---

### Task 7: 新增配置 `rag.memory.window-size`

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 新增配置项（单位钉死=轮，一轮=2 行）**

  在 `rag:` 块（L121）下新增：
  ```yaml
  rag:
    agent:
      executor:
        ...
    # 会话历史读取窗口
    # 单位 = 轮（turn）：一轮 = 一行用户消息 + 一行助手回答（rag_session 中 Q/A 各占一行）
    # 实现层 DB LIMIT 取 window-size × 2 行；设置为 -1 表示退化为全量读取（运维显式打开，知晓 O(n)+token 风险）
    memory:
      window-size: 50
  ```

- [ ] **Step 2: 确认默认值与 `RagChatMemory` 的 `@Value("${rag.memory.window-size:50}")` 一致（50）**

- [ ] **Step 3: 提交**

  ```bash
  git add company-rag-bootstrap/src/main/resources/application.yml
  git commit -m "config(memory): 新增 rag.memory.window-size（默认 50 轮，-1 全量退化）"
  ```

---

### Task 8: 全链路自检与收尾

**Files:** 已改动的全部文件

- [ ] **Step 1: 核对设计文档 §7 改动清单逐项落地**

  - `RagChatMemory.get` 返回 `List<Message>`、恒取 TenantContext 身份、调 `getRecentSessionDetail` DB LIMIT、内部转换、不写库 —— ✓（Task 4）
  - `RagSessionService.getRecentSessionDetail` DB LIMIT + `-1` 退化 —— ✓（Task 1）
  - `ChatController.chat` 去手拼改 `ragChatMemory.get`，`saveConversation` 保留 —— ✓（Task 5）
  - `RagSearchServiceImpl.search` 移除条件性落库；L79-84 拼 → `RagResultContextBuilder.build` —— ✓（Task 2/3）
  - `ragSearch` 净新增 SecurityContext 提取 + Controller 层落库 + context 重建 + TenantContext 补齐 —— ✓（Task 6）
  - 配置 `rag.memory.window-size`（无 enabled 开关）—— ✓（Task 7）

- [ ] **Step 2: 交叉检查 `TenantContext` 填充**

  确认 `chat` 已填（L90 sessionId / L105 tenantId / L124 userId）；`ragSearch` 中「需要时补 TenantContext」为可选项（默认不依赖 `get`），不影响主逻辑。

- [ ] **Step 3: 最窄范围回归**

  ```bash
  cd company-rag-rag && mvn -q test -Dtest=RagResultContextBuilderTest,RagChatMemoryTest,RagSessionServiceImplTest
  cd ../company-rag-web && mvn -q test -Dtest=ChatControllerIntegrationTest
  ```
  记录各命令 exit code 与通过情况。若个别逻辑因缺 DB/上下文无法跑，明确注明跳过，不虚报。

- [ ] **Step 4: Commit 收尾**（若有未提交清理项）
  ```bash
  git status
  git commit -am "chore(memory): 收尾自检"   # 仅在存在待提交改动时执行
  ```

---

## 关键风险与注意点

- **DB `LIMIT` 强制（🔴）**：`getRecentSessionDetail` 必须用 `LIMIT` 或等价的 DB 层 limit，绝不能用 `getSessionDetail` 全量后内存 `subList`——否则长会话依旧 O(n)，window-size 性能承诺是假声明。
- **`ragSearch` 身份提取是净新增（🔴）**：`SecurityContext`/`SecurityUser` 提取必须新写，只挪落库位置等于没修 userId 越权洞。
- **context 重建（🔴）**：`RagResult` 无 context 字段，`ragSearch` 落库必须 `RagResultContextBuilder.build(result)` 从 `chunks` 重建，否则 `rag_session.context` 静默为空、评估退化。
- **一轮=2 行（off-by-2）**：`window-size` 单位=轮，DB LIMIT 取 `window-size×2` 行；配置注释已写明。
- **跨模块依赖**：`ChatController`（web）→ `RagChatMemory`/`RagResultContextBuilder`（rag）依赖已成立（既有 `RagSearchService` 同模式）。
- **`RagSession`/`ArrayList`/`UserMessage`/`AssistantMessage` 未用 import**：重构后清理，避免告警堆积。
- **事务边界（已知取舍）**：`saveConversation` 无 `@Transactional`（沿用现状），`updateFeedback` 的 `@Transactional` 不受影响。

> 验证范围遵循系统策略：仅编译/测试当前改动相关的最窄模块（`company-rag-rag`、`company-rag-web` 相关测试类）；不运行全仓/多模块全量测试或全 CI。如需完整回归，请显式提出后再执行。
