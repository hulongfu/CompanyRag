# 会话历史功能设计文档

**创建时间**: 2026-01-18  
**版本**: 1.0  
**状态**: 待实现  

---

## 1. 概述

### 1.1 背景
当前项目的 `rag_session` 表一直是空的，原因是代码中没有实现会话持久化功能。虽然数据库初始化脚本中预留了表结构，但 RAG 搜索流程中没有保存会话数据的逻辑，前端对话数据仅保存在内存中，刷新页面后丢失。

### 1.2 目标
实现完整的会话历史管理功能，支持：
- 多轮对话会话（Session）管理
- 会话列表查询（分页 + 搜索）
- 会话详情查看
- 会话创建、删除、更新
- 多租户数据隔离

### 1.3 设计原则
- **后端控制**：会话 ID 由后端统一生成和管理
- **父子结构**：元数据与对话明细分离存储
- **混合保存**：首次实时保存，后续异步更新
- **租户隔离**：所有数据按租户物理隔离

---

## 2. 架构设计

### 2.1 数据模型

```
┌─────────────────────────┐       ┌─────────────────────────┐
│   rag_session_meta      │       │     rag_session         │
│   (会话元信息表)         │       │     (对话明细表)         │
├─────────────────────────┤       ├─────────────────────────┤
│ id (PK)                 │       │ id (PK)                 │
│ session_id (UNIQUE)     │◄──────│ session_id (FK)         │
│ tenant_id               │ 1    N│ tenant_id               │
│ user_id                 │       │ user_id                 │
│ title                   │       │ query                   │
│ last_query              │       │ answer                  │
│ message_count           │       │ context                 │
│ is_deleted              │       │ tokens_input            │
│ tags (JSONB)            │       │ tokens_output           │
│ metadata (JSONB)        │       │ latency_ms              │
│ create_time             │       │ create_time             │
│ update_time             │       └─────────────────────────┘
└─────────────────────────┘
```

### 2.2 核心流程

**会话创建流程：**
```
用户发起首次对话
    ↓
后端生成 session_id (UUID)
    ↓
创建 rag_session_meta 记录
    ↓
保存对话到 rag_session
    ↓
返回 session_id 给前端
```

**后续对话流程：**
```
用户继续提问（携带 session_id）
    ↓
保存对话到 rag_session
    ↓
异步更新 rag_session_meta
    (last_query, message_count, update_time)
```

---

## 3. 数据库设计

### 3.1 rag_session_meta 表（新增）

```sql
CREATE TABLE rag_session_meta (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(256),                    -- 会话标题（首轮问题自动生成）
    last_query TEXT,                       -- 最后一次提问内容（用于列表预览）
    message_count INTEGER DEFAULT 0,       -- 会话消息数
    is_deleted BOOLEAN DEFAULT FALSE,      -- 软删除标记
    tags JSONB DEFAULT '[]'::jsonb,        -- 标签数组
    metadata JSONB DEFAULT '{}'::jsonb,    -- 扩展元数据
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX idx_session_meta_tenant_user ON rag_session_meta(tenant_id, user_id, create_time DESC);
CREATE INDEX idx_session_meta_deleted ON rag_session_meta(is_deleted) WHERE is_deleted = FALSE;
CREATE INDEX idx_session_meta_tags ON rag_session_meta USING GIN(tags);
CREATE INDEX idx_session_meta_title_trgm ON rag_session_meta USING GIN(title gin_trgm_ops);

-- 行级安全策略
ALTER TABLE rag_session_meta ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_session_meta ON rag_session_meta
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
```

### 3.2 rag_session 表（已有，确认字段）

```sql
CREATE TABLE rag_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    query TEXT NOT NULL,                   -- 用户问题
    answer TEXT,                           -- AI 回答
    context TEXT,                          -- 检索上下文
    tokens_input INTEGER DEFAULT 0,        -- 输入 token 数
    tokens_output INTEGER DEFAULT 0,       -- 输出 token 数
    latency_ms INTEGER DEFAULT 0,          -- 延迟（毫秒）
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX idx_session_session_id ON rag_session(session_id);
CREATE INDEX idx_session_tenant_create ON rag_session(tenant_id, create_time DESC);

-- 行级安全策略（已在 init.sql 中定义）
ALTER TABLE rag_session ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_session ON rag_session
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
```

---

## 4. 后端设计

### 4.1 实体类

**RagSessionMeta.java**
```java
@TableName("rag_session_meta")
@Data
public class RagSessionMeta {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long tenantId;
    private Long userId;
    private String title;
    private String lastQuery;
    private Integer messageCount;
    private Boolean isDeleted;
    private String tags;      // JSON 字符串
    private String metadata;  // JSON 字符串
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

**RagSession.java**
```java
@TableName("rag_session")
@Data
public class RagSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long tenantId;
    private Long userId;
    private String query;
    private String answer;
    private String context;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer latencyMs;
    private LocalDateTime createTime;
}
```

### 4.2 Mapper 接口

```java
@Mapper
public interface RagSessionMapper extends BaseMapper<RagSession> {
}

@Mapper
public interface RagSessionMetaMapper extends BaseMapper<RagSessionMeta> {
}
```

### 4.3 Service 层

**RagSessionService.java**
```java
public interface RagSessionService {
    /**
     * 创建新会话
     */
    RagSessionMeta createSession(Long tenantId, Long userId, String title);
    
    /**
     * 保存对话记录
     */
    void saveConversation(Long tenantId, String sessionId, Long userId, 
                         String query, String answer, String context,
                         Integer tokensInput, Integer tokensOutput, Integer latencyMs);
    
    /**
     * 获取会话列表（分页 + 搜索）
     */
    Page<RagSessionMeta> getSessionList(Long tenantId, Long userId, 
                                        String keyword, List<String> tags,
                                        int page, int size);
    
    /**
     * 获取会话详情
     */
    List<RagSession> getSessionDetail(Long tenantId, String sessionId);
    
    /**
     * 软删除会话
     */
    void deleteSession(Long tenantId, String sessionId);
    
    /**
     * 更新会话信息
     */
    void updateSession(Long tenantId, String sessionId, String title, List<String> tags);
}
```

### 4.4 Controller 层

**SessionController.java**
```java
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {
    
    private final RagSessionService sessionService;
    
    @PostMapping
    public R<RagSessionMeta> createSession(@RequestBody SessionCreateRequest request,
                                           @RequestHeader("X-Tenant-Id") Long tenantId) {
        // TODO: 从认证信息获取 userId
        Long userId = 1L;
        return R.ok(sessionService.createSession(tenantId, userId, request.getTitle()));
    }
    
    @GetMapping("/list")
    public R<Page<RagSessionMeta>> getSessionList(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = 1L;
        return R.ok(sessionService.getSessionList(tenantId, userId, keyword, tags, page, size));
    }
    
    @GetMapping("/{sessionId}")
    public R<List<RagSession>> getSessionDetail(@PathVariable String sessionId,
                                                 @RequestHeader("X-Tenant-Id") Long tenantId) {
        return R.ok(sessionService.getSessionDetail(tenantId, sessionId));
    }
    
    @DeleteMapping("/{sessionId}")
    public R<Void> deleteSession(@PathVariable String sessionId,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        sessionService.deleteSession(tenantId, sessionId);
        return R.ok();
    }
    
    @PutMapping("/{sessionId}")
    public R<Void> updateSession(@PathVariable String sessionId,
                                  @RequestBody SessionUpdateRequest request,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        sessionService.updateSession(tenantId, sessionId, request.getTitle(), request.getTags());
        return R.ok();
    }
}
```

### 4.5 集成到 RAG 搜索流程

修改 `RagSearchServiceImpl.search()` 方法：

```java
@Override
public RagResult search(RagQuery query) {
    long start = System.currentTimeMillis();
    
    // ... 现有检索逻辑 ...
    
    // 新增：保存会话
    if (query.getSessionId() != null) {
        // 异步保存会话
        CompletableFuture.runAsync(() -> {
            sessionService.saveConversation(
                query.getTenantId(),
                query.getSessionId(),
                query.getUserId(),
                query.getQuery(),
                result.getAnswer(),
                context,
                tokensInput,
                tokensOutput,
                llmMs
            );
        });
    } else {
        // 创建新会话
        RagSessionMeta session = sessionService.createSession(
            query.getTenantId(),
            query.getUserId(),
            query.getQuery()  // 用首轮问题作为标题
        );
        query.setSessionId(session.getSessionId());
        
        // 保存首轮对话
        sessionService.saveConversation(...);
    }
    
    // 返回 session_id 给前端
    result.setSessionId(query.getSessionId());
    
    return result;
}
```

---

## 5. 前端设计

### 5.1 UI 布局

```
┌─────────────────────────────────────────────────────────────┐
│  Header: CompanyRag 企业知识库                               │
├─────────────┬───────────────────────────────────────────────┤
│ 会话列表    │  对话区域                                      │
│ ┌─────────┐ │                                               │
│ │🔍 搜索   │ │  用户：你好                                    │
│ ├─────────┤ │  AI:  你好！有什么可以帮你？                    │
│ │💬 会话 1  │ │                                               │
│ │💬 会话 2  │ │                                               │
│ │💬 会话 3  │ │                                               │
│ │💬 会话 4  │ │                                               │
│ └─────────┘ │                                               │
│ [新建会话]  │                                               │
└─────────────┴───────────────────────────────────────────────┘
```

### 5.2 前端功能

**新增功能：**
1. 会话列表侧边栏（可折叠）
2. 会话搜索框（支持标题关键词、标签筛选）
3. 分页加载（每页 20 条）
4. 新建会话按钮
5. 会话项操作（删除、重命名）
6. 点击切换会话

**修改功能：**
1. 对话时携带 `session_id`
2. 接收后端返回的 `session_id`（首次对话时）

### 5.3 API 调用示例

```javascript
// 创建会话
async function createSession() {
    const res = await fetch('/api/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': tenantId },
        body: JSON.stringify({ title: '新会话' })
    });
    const json = await res.json();
    return json.data.sessionId;
}

// 加载会话列表
async function loadSessionList(page = 1, keyword = '') {
    const params = new URLSearchParams({ page, size: 20, keyword });
    const res = await fetch(`/api/session/list?${params}`, {
        headers: { 'X-Tenant-Id': tenantId }
    });
    const json = await res.json();
    return json.data;
}

// 加载会话详情
async function loadSessionDetail(sessionId) {
    const res = await fetch(`/api/session/${sessionId}`, {
        headers: { 'X-Tenant-Id': tenantId }
    });
    const json = await res.json();
    return json.data;
}

// 发送消息（携带 session_id）
async function sendMessage(query, sessionId) {
    const res = await fetch('/api/rag/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': tenantId },
        body: JSON.stringify({ 
            query, 
            sessionId,
            tenantId: parseInt(tenantId)
        })
    });
    const json = await res.json();
    // json.data.sessionId 可能是新创建的会话 ID
    return json.data;
}
```

---

## 6. 安全设计

### 6.1 多租户隔离
- 所有数据库查询必须包含 `tenant_id` 条件
- 使用 `X-Tenant-Id` 请求头传递租户信息
- RLS 行级安全策略防止越权访问

### 6.2 用户权限
- 用户只能查看自己的会话（通过 `user_id` 过滤）
- 管理员可查看租户下所有会话
- 删除会话为软删除，保留审计数据

### 6.3 数据保护
- 敏感信息（如 context 中的检索内容）不脱敏存储
- 后续可支持会话数据导出功能

---

## 7. 性能优化

### 7.1 异步保存
使用 `CompletableFuture` 异步保存会话，不阻塞主流程：

```java
CompletableFuture.runAsync(() -> {
    sessionService.saveConversation(...);
}, threadPoolExecutor);
```

### 7.2 批量更新
对于 `message_count`、`last_query` 的更新，采用批量合并策略：
- 内存中计数，每 5 次对话更新一次数据库
- 页面关闭前强制更新

### 7.3 索引优化
- `rag_session_meta(tenant_id, user_id, create_time DESC)` - 列表查询
- `rag_session(session_id, create_time)` - 详情查询
- GIN 索引支持 JSONB 字段搜索

---

## 8. 测试计划

### 8.1 单元测试
- `RagSessionServiceTest` - 测试会话创建、保存、查询、删除
- `SessionControllerTest` - 测试 API 接口

### 8.2 集成测试
- 创建会话 → 保存对话 → 查询列表 → 查看详情 → 删除会话
- 多租户数据隔离验证

### 8.3 前端测试
- 会话列表加载
- 会话切换
- 搜索功能
- 分页功能

---

## 9. 上线计划

### Phase 1: 基础功能（本次实现）
- [ ] 数据库表创建
- [ ] 实体类 + Mapper
- [ ] Service + Controller
- [ ] 集成到 RAG 搜索
- [ ] 前端会话列表 UI
- [ ] 基础分页查询

### Phase 2: 增强功能（后续迭代）
- [ ] 标签管理
- [ ] 会话搜索（全文检索）
- [ ] 会话导出
- [ ] 无限滚动优化

---

## 10. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 异步保存失败 | 数据丢失 | 添加重试机制，失败日志记录 |
| 会话数据量大 | 查询慢 | 分页限制，索引优化，归档策略 |
| 多租户隔离失效 | 数据泄露 | RLS 策略 + 代码层双重校验 |
| 前端性能问题 | 体验差 | 虚拟滚动，懒加载 |

---

## 11. 验收标准

- [ ] 创建会话成功，返回 session_id
- [ ] 对话自动保存到数据库
- [ ] 会话列表正确显示（分页 + 搜索）
- [ ] 会话详情包含完整对话历史
- [ ] 软删除后可会不再出现在列表中
- [ ] 多租户数据完全隔离
- [ ] 异步保存不影响主流程性能
