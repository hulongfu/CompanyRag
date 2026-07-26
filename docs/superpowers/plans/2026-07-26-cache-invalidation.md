# 缓存失效机制实施计划

> **For agentic workers:** REQUIRED SUB-Skill: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善 RAG 缓存失效机制，确保文档变更后缓存自动失效，并提供手动管理接口。

**Architecture:** 文档模块通过 Spring ApplicationEvent 发布 DocumentEvent，RAG 模块监听事件并调用 RagCacheManager.invalidateByTenant() 清空租户缓存。Web 模块提供管理 API。

**Tech Stack:** Spring ApplicationEvent, Redisson RMapCache, Spring Boot REST Controller

---

### Task 1: 创建 DocumentEventType 枚举

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/event/DocumentEventType.java`

- [ ] **Step 1: 创建 DocumentEventType 枚举**

```java
package com.company.rag.common.event;

public enum DocumentEventType {
    ADDED,
    DELETED,
    UPDATED
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/event/DocumentEventType.java
git commit -m "feat: 创建 DocumentEventType 枚举"
```

---

### Task 2: 创建 DocumentEvent 事件类

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/event/DocumentEvent.java`

- [ ] **Step 1: 创建 DocumentEvent 类**

```java
package com.company.rag.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DocumentEvent extends ApplicationEvent {
    private final Long tenantId;
    private final Long documentId;
    private final DocumentEventType eventType;

    public DocumentEvent(Object source, Long tenantId, Long documentId, DocumentEventType eventType) {
        super(source);
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.eventType = eventType;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/event/DocumentEvent.java
git commit -m "feat: 创建 DocumentEvent 事件类"
```

---

### Task 3: 创建 CacheInvalidationListener

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/cache/CacheInvalidationListener.java`

- [ ] **Step 1: 创建 CacheInvalidationListener**

```java
package com.company.rag.rag.cache;

import com.company.rag.common.event.DocumentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private final RagCacheManager cacheManager;

    @EventListener
    public void onDocumentEvent(DocumentEvent event) {
        log.info("收到文档事件 | tenantId={} | documentId={} | type={}",
                event.getTenantId(), event.getDocumentId(), event.getEventType());
        try {
            cacheManager.invalidateByTenant(event.getTenantId());
        } catch (Exception e) {
            // 缓存失效异常不应影响文档操作
            log.error("缓存失效失败 | tenantId={} | error={}", event.getTenantId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/cache/CacheInvalidationListener.java
git commit -m "feat: 创建 CacheInvalidationListener 监听文档事件并失效缓存"
```

---

### Task 4: 修复 RagCacheManager.invalidateByTenant 的 prefix 匹配 bug

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/cache/RagCacheManager.java:94-111`

- [ ] **Step 1: 修复 invalidateByTenant 方法**

当前 `prefix = tenantId + ":"`（如 `"1:"`），但实际 cache key 格式为 `company:rag:vector:{tenantId}:{query}`。需引入 `RagConstant.CACHE_DOC_VECTOR` 修正 prefix。

将第 94-111 行替换为：

```java
    public void invalidateByTenant(Long tenantId) {
        RMapCache<String, RagResult> cache = getCache();
        // Cache key 格式: company:rag:vector:{tenantId}:{query}
        String prefix = RagConstant.CACHE_DOC_VECTOR + tenantId + ":";

        try {
            int deletedCount = 0;
            for (String key : cache.keySet()) {
                if (key.startsWith(prefix)) {
                    cache.remove(key);
                    deletedCount++;
                }
            }

            log.info("失效租户缓存成功 | tenantId={} | deletedCount={}", tenantId, deletedCount);
        } catch (Exception e) {
            log.error("失效租户缓存失败 | tenantId={} | error={}", tenantId, e.getMessage());
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/cache/RagCacheManager.java
git commit -m "fix: 修正 invalidateByTenant 的 prefix 匹配逻辑"
```

---

### Task 5: 改造 DocumentParseServiceImpl 发布 DocumentEvent

**Files:**
- Modify: `company-rag-document/src/main/java/com/company/rag/document/service/impl/DocumentParseServiceImpl.java`

- [ ] **Step 1: 注入 ApplicationEventPublisher**

在类中添加字段（与现有字段并列）：

```java
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
```

构造器已使用 `@RequiredArgsConstructor`，会自动注入。

- [ ] **Step 2: 添加 import**

在文件头部添加：

```java
import com.company.rag.common.event.DocumentEvent;
import com.company.rag.common.event.DocumentEventType;
```

- [ ] **Step 3: uploadAndParse 方法末尾发布事件**

在 `uploadAndParse` 方法的 `return doc;` 之前（第 99 行之前）添加：

```java
        // 发布文档事件触发缓存失效
        eventPublisher.publishEvent(new DocumentEvent(this, tenantId, doc.getId(), DocumentEventType.ADDED));

        return doc;
```

- [ ] **Step 4: deleteDocument 方法末尾发布事件**

在 `deleteDocument` 方法最后一行 `log.info("删除文档记录...")` 之后（第 172 行之后、方法结束之前）添加：

```java
        // 发布文档事件触发缓存失效
        eventPublisher.publishEvent(new DocumentEvent(this, tenantId, id, DocumentEventType.DELETED));
    }
```

- [ ] **Step 5: Commit**

```bash
git add company-rag-document/src/main/java/com/company/rag/document/service/impl/DocumentParseServiceImpl.java
git commit -m "feat: 文档上传/删除后发布 DocumentEvent 触发缓存失效"
```

---

### Task 6: 创建 CacheManageController

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/CacheManageController.java`

- [ ] **Step 1: 创建 CacheManageController**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.cache.RagCacheManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheManageController {

    private final RagCacheManager cacheManager;

    /**
     * 清空指定租户的缓存
     */
    @PostMapping("/clear")
    public R<Void> clearByTenant(@RequestParam Long tenantId) {
        cacheManager.invalidateByTenant(tenantId);
        return R.ok();
    }

    /**
     * 清空所有 RAG 缓存
     */
    @PostMapping("/clearAll")
    public R<Void> clearAll() {
        cacheManager.clearAll();
        return R.ok();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/CacheManageController.java
git commit -m "feat: 创建 CacheManageController 提供缓存管理 API"
```

---

### Task 7: 单元测试 — CacheInvalidationListener

**Files:**
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/cache/CacheInvalidationListenerTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.company.rag.rag.cache;

import com.company.rag.common.event.DocumentEvent;
import com.company.rag.common.event.DocumentEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationListenerTest {

    @Mock
    private RagCacheManager cacheManager;

    @InjectMocks
    private CacheInvalidationListener listener;

    @Test
    void onDocumentEvent_shouldInvalidateTenantCache() {
        // Given
        DocumentEvent event = new DocumentEvent(this, 1L, 100L, DocumentEventType.ADDED);

        // When
        listener.onDocumentEvent(event);

        // Then
        verify(cacheManager).invalidateByTenant(eq(1L));
    }

    @Test
    void onDocumentEvent_shouldNotThrowWhenCacheManagerFails() {
        // Given
        DocumentEvent event = new DocumentEvent(this, 1L, 100L, DocumentEventType.DELETED);
        doThrow(new RuntimeException("Redis 连接失败")).when(cacheManager).invalidateByTenant(eq(1L));

        // When & Then — 不应抛出异常
        try {
            listener.onDocumentEvent(event);
        } catch (Exception e) {
            throw new AssertionError("缓存失效异常不应向上抛出", e);
        }
        verify(cacheManager).invalidateByTenant(eq(1L));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd company-rag-rag && mvn test -Dtest=CacheInvalidationListenerTest -q
```

Expected: PASS (2 tests)

- [ ] **Step 3: Commit**

```bash
git add company-rag-rag/src/test/java/com/company/rag/rag/cache/CacheInvalidationListenerTest.java
git commit -m "test: 添加 CacheInvalidationListener 单元测试"
```

---

### Task 8: 单元测试 — RagCacheManager.invalidateByTenant 修复验证

**Files:**
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/cache/RagCacheManagerTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.company.rag.rag.cache;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.rag.model.RagResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagCacheManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RMapCache<String, RagResult> cache;

    private RagCacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new RagCacheManager(redissonClient);
    }

    @Test
    void invalidateByTenant_shouldRemoveMatchingKeys() {
        // Given — 租户 1 和租户 2 的缓存 key
        String prefix = RagConstant.CACHE_DOC_VECTOR;
        String key1 = prefix + "1:test query";
        String key2 = prefix + "1:another query";
        String key3 = prefix + "2:other tenant query";
        when(redissonClient.getMapCache(eq(prefix + "search"))).thenReturn(cache);
        when(cache.keySet()).thenReturn(Set.of(key1, key2, key3));

        // When
        manager.invalidateByTenant(1L);

        // Then — 只删除租户 1 的 key
        verify(cache).remove(eq(key1));
        verify(cache).remove(eq(key2));
        verify(cache, never()).remove(eq(key3));
    }

    @Test
    void clearAll_shouldClearEntireCache() {
        // Given
        String prefix = RagConstant.CACHE_DOC_VECTOR;
        when(redissonClient.getMapCache(eq(prefix + "search"))).thenReturn(cache);

        // When
        manager.clearAll();

        // Then
        verify(cache).clear();
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd company-rag-rag && mvn test -Dtest=RagCacheManagerTest -q
```

Expected: PASS (2 tests)

- [ ] **Step 3: Commit**

```bash
git add company-rag-rag/src/test/java/com/company/rag/rag/cache/RagCacheManagerTest.java
git commit -m "test: 添加 RagCacheManager 单元测试验证 prefix 修复"
```

---

### Task 9: 单元测试 — DocumentParseServiceImpl 事件发布

**Files:**
- Create: `company-rag-document/src/test/java/com/company/rag/document/service/DocumentParseServiceImplEventTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.company.rag.document.service;

import com.company.rag.common.event.DocumentEvent;
import com.company.rag.document.mapper.DocumentChunkMapper;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.document.service.impl.DocumentParseServiceImpl;
import com.company.rag.document.splitter.DocumentSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentParseServiceImplEventTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private List<DocumentSplitter> splitters;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentParseServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DocumentParseServiceImpl(vectorStore, splitters, documentMapper, chunkMapper, jdbcTemplate, eventPublisher);
    }

    @Test
    void deleteDocument_shouldPublishDeletedEvent() {
        // Given
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(0);
        when(chunkMapper.delete(any())).thenReturn(0);
        when(documentMapper.deleteById(any())).thenReturn(0);

        // When
        service.deleteDocument(100L, 1L);

        // Then
        ArgumentCaptor<DocumentEvent> captor = ArgumentCaptor.forClass(DocumentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DocumentEvent event = captor.getValue();
        assert event.getTenantId().equals(1L) : "tenantId 不匹配";
        assert event.getDocumentId().equals(100L) : "documentId 不匹配";
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd company-rag-document && mvn test -Dtest=DocumentParseServiceImplEventTest -q
```

Expected: PASS (1 test)

- [ ] **Step 3: Commit**

```bash
git add company-rag-document/src/test/java/com/company/rag/document/service/DocumentParseServiceImplEventTest.java
git commit -m "test: 验证 DocumentParseServiceImpl 事件发布逻辑"
```

---

### Task 10: 推送到远程

- [ ] **Step 1: 推送代码**

```bash
git push origin main
```
