# 缓存失效机制完善设计

## 背景

`RagCacheManager` 已定义 `invalidateByDocument`、`invalidateByTenant`、`clearAll` 三个缓存失效方法，但项目中没有任何地方调用它们。文档上传、删除、更新等操作后，缓存中仍保留旧检索结果，导致用户查询可能返回过期数据。

## 问题清单

| 问题 | 严重度 | 说明 |
|------|--------|------|
| 失效方法未被调用 | 严重 | `invalidateByDocument`/`invalidateByTenant`/`clearAll` 定义后从未被任何代码调用 |
| 缓存 Key 与文档无关 | 严重 | Key 格式为 `{tenantId}:{queryHash}`，不含 documentId，`invalidateByDocument` 的 `*doc_{documentId}*` 匹配永远为空 |
| 文档操作不触发失效 | 严重 | `DocumentService` 的 `uploadAndParse`、`deleteDocument` 等方法无任何缓存失效逻辑 |
| 无管理接口 | 警告 | 缺少手动触发缓存失效的管理入口 |

## 设计目标

1. 文档变更（上传/删除/更新）后，相关租户的缓存自动失效
2. 提供手动缓存管理 API
3. 保持现有 TTL 策略和热点判定逻辑不变
4. 跨模块解耦，document 模块不依赖 rag 模块

## 方案概述

采用 **Spring ApplicationEvent + 租户级全量失效** 方案：

- 文档模块在文档变更后发布 `DocumentEvent`
- RAG 模块监听事件，调用 `invalidateByTenant(tenantId)` 清空该租户全部缓存
- 提供管理 API 支持手动触发

选择租户级全量失效而非文档级精粒度失效的原因：
- 缓存 Key 不含 documentId，精粒度失效需要重构 Key 结构
- 检索结果通常跨多个文档，文档级失效的准确率有限
- 租户级失效实现简单，维护成本低

## 架构

```
company-rag-document                    company-rag-rag
┌─────────────────────┐                 ┌──────────────────────────┐
│ DocumentService     │                 │ CacheInvalidationListener│
│                     │  DocumentEvent  │                          │
│ uploadAndParse() ──┼─────────────────►│ onApplicationEvent()    │
│ deleteDocument() ──┼─────────────────►│  → invalidateByTenant() │
│ updateDocument() ──┼─────────────────┘                         │
└─────────────────────┘                 │ RagCacheManager          │
                                        │  - invalidateByTenant()  │
                                        │  - clearAll()            │
                                        └──────────────────────────┘

company-rag-web
┌──────────────────────────┐
│ CacheManageController    │
│  POST /api/cache/clear   │ 清空指定租户缓存
│  POST /api/cache/clearAll│ 清空全部缓存
└──────────────────────────┘
```

## 详细设计

### 1. DocumentEvent（company-rag-common）

定义在 common 模块，避免 document 模块依赖 rag 模块。

```java
// DocumentEventType.java
public enum DocumentEventType {
    ADDED,      // 文档上传/解析完成
    DELETED,    // 文档删除
    UPDATED     // 文档内容更新
}

// DocumentEvent.java (extends ApplicationEvent)
public class DocumentEvent extends ApplicationEvent {
    private final Long tenantId;
    private final Long documentId;
    private final DocumentEventType eventType;
}
```

### 2. CacheInvalidationListener（company-rag-rag）

```java
@Component
public class CacheInvalidationListener {
    private final RagCacheManager cacheManager;

    @EventListener
    public void onDocumentEvent(DocumentEvent event) {
        log.info("收到文档事件 | tenantId={} | documentId={} | type={}",
            event.getTenantId(), event.getDocumentId(), event.getEventType());
        cacheManager.invalidateByTenant(event.getTenantId());
    }
}
```

### 3. DocumentService 改造（company-rag-document）

在以下方法末尾发布 DocumentEvent：

| 方法 | 事件类型 | 位置 |
|------|----------|------|
| `uploadAndParse()` | `ADDED` | 文档解析并入库后 |
| `deleteDocument()` | `DELETED` | 文档删除前 |

### 4. RagCacheManager 优化（company-rag-rag）

当前 `invalidateByTenant` 遍历 `keySet()` 再匹配 prefix，效率低。优化为：

- 使用独立的 `RMapCache` 实例（按租户隔离），或直接 clear 整个缓存（租户数据量不大时）
- 保留现有实现逻辑，仅修正 key 匹配逻辑确保正确性

### 5. CacheManageController（company-rag-web）

```java
@RestController
@RequestMapping("/api/cache")
public class CacheManageController {

    @PostMapping("/clear")
    public R<Void> clearByTenant(@RequestParam Long tenantId) {
        cacheManager.invalidateByTenant(tenantId);
        return R.ok();
    }

    @PostMapping("/clearAll")
    public R<Void> clearAll() {
        cacheManager.clearAll();
        return R.ok();
    }
}
```

## 不做的

- 不改缓存 Key 结构，保持 `{tenantId}:{queryHash}`
- 不改 TTL 策略（默认 5 分钟，热点 30 分钟，阈值 >3 次）
- 不做文档级精粒度失效
- 不引入 Redis Pub/Sub 或消息队列

## 测试计划

| 场景 | 预期 |
|------|------|
| 上传文档后查询同一问题 | 第二次查询应命中新缓存而非旧缓存 |
| 删除文档后查询引用该文档的问题 | 缓存已失效，返回不包含已删除文档的结果 |
| 手动调用 clear 接口 | 指定租户缓存被清空 |
| 手动调用 clearAll 接口 | 全部缓存被清空 |
| 缓存失效异常不影响主流程 | 即使 Redis 异常，文档操作不应失败 |
