# 会话历史功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现完整的会话历史管理功能，支持多轮对话会话管理、会话列表查询、会话详情查看、会话创建/删除/更新。

**Architecture:** 采用父子结构，`rag_session_meta` 存储会话元信息，`rag_session` 存储对话明细。后端控制会话 ID 生成，混合保存策略（首次实时，后续异步更新）。

**Tech Stack:** Spring Boot 3.4 + MyBatis-Plus 3.5.9 + PostgreSQL 16 + PGVector + Vue 3 + Element Plus

---

## 文件结构

### 新增文件

**后端：**
- `company-rag-rag/src/main/java/com/company/rag/rag/entity/RagSession.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/entity/RagSessionMeta.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/mapper/RagSessionMapper.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/mapper/RagSessionMetaMapper.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/service/RagSessionService.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSessionServiceImpl.java`
- `company-rag-web/src/main/java/com/company/rag/web/controller/SessionController.java`
- `company-rag-web/src/main/java/com/company/rag/web/model/SessionCreateRequest.java`
- `company-rag-web/src/main/java/com/company/rag/web/model/SessionUpdateRequest.java`

**测试：**
- `company-rag-rag/src/test/java/com/company/rag/rag/service/RagSessionServiceTest.java`
- `company-rag-web/src/test/java/com/company/rag/web/controller/SessionControllerTest.java`

**数据库：**
- `sql/session-history-tables.sql`

**前端：**
- 修改 `company-rag-web/src/main/resources/templates/index.html`

### 修改文件

- `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java` - 集成会话保存逻辑
- `company-rag-rag/src/main/java/com/company/rag/rag/model/RagQuery.java` - 添加 sessionId、userId 字段

---

## Task 1: 数据库表结构

**Files:**
- Create: `sql/session-history-tables.sql`

- [ ] **Step 1: 创建数据库迁移脚本**

```sql
-- ================================================
-- 会话历史功能数据库迁移脚本
-- ================================================

-- rag_session_meta 表（会话元信息）
CREATE TABLE IF NOT EXISTS rag_session_meta (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(256),
    last_query TEXT,
    message_count INTEGER DEFAULT 0,
    is_deleted BOOLEAN DEFAULT FALSE,
    tags JSONB DEFAULT '[]'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_session_meta_tenant_user ON rag_session_meta(tenant_id, user_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_session_meta_deleted ON rag_session_meta(is_deleted) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_session_meta_tags ON rag_session_meta USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_session_meta_title_trgm ON rag_session_meta USING GIN(title gin_trgm_ops);

-- 行级安全策略
ALTER TABLE rag_session_meta ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session_meta ON rag_session_meta;
CREATE POLICY tenant_isolation_session_meta ON rag_session_meta
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

-- rag_session 表（如果不存在则创建）
CREATE TABLE IF NOT EXISTS rag_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    query TEXT NOT NULL,
    answer TEXT,
    context TEXT,
    tokens_input INTEGER DEFAULT 0,
    tokens_output INTEGER DEFAULT 0,
    latency_ms INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_session_session_id ON rag_session(session_id);
CREATE INDEX IF NOT EXISTS idx_session_tenant_create ON rag_session(tenant_id, create_time DESC);

-- 行级安全策略（如果不存在）
ALTER TABLE rag_session ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session ON rag_session;
CREATE POLICY tenant_isolation_session ON rag_session
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
```

- [ ] **Step 2: 提交数据库脚本**

```bash
git add sql/session-history-tables.sql
git commit -m "feat: add session history database migration script"
```

---

## Task 2: 实体类

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/entity/RagSession.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/entity/RagSessionMeta.java`

- [ ] **Step 1: 创建 RagSession 实体类**

```java
package com.company.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * RAG 会话明细实体
 */
@Data
@TableName("rag_session")
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

- [ ] **Step 2: 创建 RagSessionMeta 实体类**

```java
package com.company.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * RAG 会话元信息实体
 */
@Data
@TableName("rag_session_meta")
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

    private String tags;

    private String metadata;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: 提交实体类**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/entity/*.java
git commit -m "feat: add RagSession and RagSessionMeta entities"
```

---

## Task 3: Mapper 接口

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/mapper/RagSessionMapper.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/mapper/RagSessionMetaMapper.java`

- [ ] **Step 1: 创建 RagSessionMapper**

```java
package com.company.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.rag.entity.RagSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 会话 Mapper 接口
 */
@Mapper
public interface RagSessionMapper extends BaseMapper<RagSession> {
}
```

- [ ] **Step 2: 创建 RagSessionMetaMapper**

```java
package com.company.rag.rag.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.rag.entity.RagSessionMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * RAG 会话元信息 Mapper 接口
 */
@Mapper
public interface RagSessionMetaMapper extends BaseMapper<RagSessionMeta> {

    /**
     * 查询会话列表（分页）
     */
    List<RagSessionMeta> selectSessionList(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("keyword") String keyword,
        @Param("tags") List<String> tags,
        @Param("offset") Integer offset,
        @Param("limit") Integer limit
    );

    /**
     * 统计会话数量
     */
    Long countSessionList(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("keyword") String keyword,
        @Param("tags") List<String> tags
    );
}
```

- [ ] **Step 3: 创建 MyBatis XML 映射文件**

创建 `company-rag-rag/src/main/resources/mapper/RagSessionMetaMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.company.rag.rag.mapper.RagSessionMetaMapper">

    <resultMap id="SessionMetaResultMap" type="com.company.rag.rag.entity.RagSessionMeta">
        <id column="id" property="id"/>
        <result column="session_id" property="sessionId"/>
        <result column="tenant_id" property="tenantId"/>
        <result column="user_id" property="userId"/>
        <result column="title" property="title"/>
        <result column="last_query" property="lastQuery"/>
        <result column="message_count" property="messageCount"/>
        <result column="is_deleted" property="isDeleted"/>
        <result column="tags" property="tags" typeHandler="org.apache.ibatis.type.JdbcType.OTHER"/>
        <result column="metadata" property="metadata" typeHandler="org.apache.ibatis.type.JdbcType.OTHER"/>
        <result column="create_time" property="createTime"/>
        <result column="update_time" property="updateTime"/>
    </resultMap>

    <select id="selectSessionList" resultMap="SessionMetaResultMap">
        SELECT * FROM rag_session_meta
        WHERE tenant_id = #{tenantId}
        AND user_id = #{userId}
        AND is_deleted = FALSE
        <if test="keyword != null and keyword != ''">
            AND (title LIKE '%' || #{keyword} || '%' OR last_query LIKE '%' || #{keyword} || '%')
        </if>
        <if test="tags != null and tags.size() > 0">
            AND tags ?| #{tags}
        </if>
        ORDER BY create_time DESC
        LIMIT #{limit} OFFSET #{offset}
    </select>

    <select id="countSessionList" resultType="java.lang.Long">
        SELECT COUNT(*) FROM rag_session_meta
        WHERE tenant_id = #{tenantId}
        AND user_id = #{userId}
        AND is_deleted = FALSE
        <if test="keyword != null and keyword != ''">
            AND (title LIKE '%' || #{keyword} || '%' OR last_query LIKE '%' || #{keyword} || '%')
        </if>
        <if test="tags != null and tags.size() > 0">
            AND tags ?| #{tags}
        </if>
    </select>

</mapper>
```

- [ ] **Step 4: 提交 Mapper**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/mapper/*.java
git add company-rag-rag/src/main/resources/mapper/RagSessionMetaMapper.xml
git commit -m "feat: add RagSessionMapper and RagSessionMetaMapper with XML"
```

---

## Task 4: Service 层

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/service/RagSessionService.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSessionServiceImpl.java`

- [ ] **Step 1: 创建 RagSessionService 接口**

```java
package com.company.rag.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import java.util.List;

/**
 * RAG 会话服务接口
 */
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

    /**
     * 更新会话元数据（异步批量更新）
     */
    void updateSessionMeta(String sessionId, String lastQuery, int messageCount);
}
```

- [ ] **Step 2: 创建 RagSessionServiceImpl 实现类**

```java
package com.company.rag.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.mapper.RagSessionMapper;
import com.company.rag.rag.mapper.RagSessionMetaMapper;
import com.company.rag.rag.service.RagSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSessionServiceImpl implements RagSessionService {

    private final RagSessionMapper sessionMapper;
    private final RagSessionMetaMapper sessionMetaMapper;

    // 内存中的会话计数器（用于批量更新）
    private final Map<String, SessionCounter> sessionCounters = new ConcurrentHashMap<>();

    private static class SessionCounter {
        int count = 0;
        String lastQuery;
        LocalDateTime lastUpdateTime = LocalDateTime.now();

        synchronized void increment(String query) {
            count++;
            lastQuery = query;
            lastUpdateTime = LocalDateTime.now();
        }
    }

    @Override
    @Transactional
    public RagSessionMeta createSession(Long tenantId, Long userId, String title) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        RagSessionMeta meta = new RagSessionMeta();
        meta.setSessionId(sessionId);
        meta.setTenantId(tenantId);
        meta.setUserId(userId);
        meta.setTitle(title);
        meta.setMessageCount(0);
        meta.setIsDeleted(false);
        meta.setTags("[]");
        meta.setMetadata("{}");

        sessionMetaMapper.insert(meta);
        log.info("创建会话成功 | tenantId={} userId={} sessionId={} title={}",
                tenantId, userId, sessionId, title);

        return meta;
    }

    @Override
    public void saveConversation(Long tenantId, String sessionId, Long userId,
                                String query, String answer, String context,
                                Integer tokensInput, Integer tokensOutput, Integer latencyMs) {
        // 检查会话是否存在，不存在则创建
        RagSessionMeta meta = sessionMetaMapper.selectOne(
            new LambdaQueryWrapper<RagSessionMeta>()
                .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta == null) {
            log.warn("会话不存在，自动创建 | sessionId={}", sessionId);
            createSession(tenantId, userId, query);
        }

        // 保存对话记录
        RagSession session = new RagSession();
        session.setSessionId(sessionId);
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setQuery(query);
        session.setAnswer(answer);
        session.setContext(context);
        session.setTokensInput(tokensInput != null ? tokensInput : 0);
        session.setTokensOutput(tokensOutput != null ? tokensOutput : 0);
        session.setLatencyMs(latencyMs != null ? latencyMs : 0);

        sessionMapper.insert(session);

        // 异步更新会话元数据
        updateSessionMetaAsync(sessionId, query);
    }

    /**
     * 异步更新会话元数据（批量）
     */
    private void updateSessionMetaAsync(String sessionId, String lastQuery) {
        SessionCounter counter = sessionCounters.computeIfAbsent(sessionId, k -> new SessionCounter());
        counter.increment(lastQuery);

        // 每 5 次对话或超过 5 分钟更新一次数据库
        if (counter.count >= 5 || 
            java.time.Duration.between(counter.lastUpdateTime, LocalDateTime.now()).toMinutes() >= 5) {
            updateSessionMeta(sessionId, counter.lastQuery, counter.count);
            sessionCounters.remove(sessionId);
        }
    }

    @Override
    @Transactional
    public void updateSessionMeta(String sessionId, String lastQuery, int messageCount) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
            new LambdaQueryWrapper<RagSessionMeta>()
                .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            meta.setLastQuery(lastQuery);
            meta.setMessageCount(meta.getMessageCount() + messageCount);
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.debug("更新会话元数据 | sessionId={} messageCount={}", sessionId, meta.getMessageCount());
        }
    }

    @Override
    public Page<RagSessionMeta> getSessionList(Long tenantId, Long userId,
                                               String keyword, List<String> tags,
                                               int page, int size) {
        int offset = (page - 1) * size;
        List<RagSessionMeta> records = sessionMetaMapper.selectSessionList(
            tenantId, userId, keyword, tags, offset, size);
        Long total = sessionMetaMapper.countSessionList(tenantId, userId, keyword, tags);

        Page<RagSessionMeta> result = new Page<>(page, size, total);
        result.setRecords(records);
        return result;
    }

    @Override
    public List<RagSession> getSessionDetail(Long tenantId, String sessionId) {
        return sessionMapper.selectList(
            new LambdaQueryWrapper<RagSession>()
                .eq(RagSession::getTenantId, tenantId)
                .eq(RagSession::getSessionId, sessionId)
                .orderByAsc(RagSession::getCreateTime)
        );
    }

    @Override
    @Transactional
    public void deleteSession(Long tenantId, String sessionId) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
            new LambdaQueryWrapper<RagSessionMeta>()
                .eq(RagSessionMeta::getTenantId, tenantId)
                .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            meta.setIsDeleted(true);
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.info("软删除会话 | tenantId={} sessionId={}", tenantId, sessionId);
        }
    }

    @Override
    @Transactional
    public void updateSession(Long tenantId, String sessionId, String title, List<String> tags) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
            new LambdaQueryWrapper<RagSessionMeta>()
                .eq(RagSessionMeta::getTenantId, tenantId)
                .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            if (title != null) {
                meta.setTitle(title);
            }
            if (tags != null) {
                meta.setTags(tags.toString());
            }
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.info("更新会话信息 | tenantId={} sessionId={}", tenantId, sessionId);
        }
    }
}
```

- [ ] **Step 3: 提交 Service 层**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/service/*.java
git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/*.java
git commit -m "feat: implement RagSessionService with batch update support"
```

---

## Task 5: Controller 层

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/SessionController.java`
- Create: `company-rag-web/src/main/java/com/company/rag/web/model/SessionCreateRequest.java`
- Create: `company-rag-web/src/main/java/com/company/rag/web/model/SessionUpdateRequest.java`

- [ ] **Step 1: 创建请求模型**

```java
// SessionCreateRequest.java
package com.company.rag.web.model;

import lombok.Data;

@Data
public class SessionCreateRequest {
    private String title;
}
```

```java
// SessionUpdateRequest.java
package com.company.rag.web.model;

import lombok.Data;
import java.util.List;

@Data
public class SessionUpdateRequest {
    private String title;
    private List<String> tags;
}
```

- [ ] **Step 2: 创建 SessionController**

```java
package com.company.rag.web.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.common.model.R;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.web.model.SessionCreateRequest;
import com.company.rag.web.model.SessionUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 */
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final RagSessionService sessionService;

    /**
     * 创建新会话
     */
    @PostMapping
    public R<RagSessionMeta> createSession(@RequestBody SessionCreateRequest request,
                                           @RequestHeader("X-Tenant-Id") Long tenantId) {
        // TODO: 从认证信息获取 userId，暂时使用默认值
        Long userId = 1L;
        String title = request.getTitle() != null ? request.getTitle() : "新会话";
        RagSessionMeta meta = sessionService.createSession(tenantId, userId, title);
        return R.ok(meta);
    }

    /**
     * 获取会话列表（分页 + 搜索）
     */
    @GetMapping("/list")
    public R<Page<RagSessionMeta>> getSessionList(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = 1L;
        Page<RagSessionMeta> result = sessionService.getSessionList(tenantId, userId, keyword, tags, page, size);
        return R.ok(result);
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/{sessionId}")
    public R<List<RagSession>> getSessionDetail(@PathVariable String sessionId,
                                                 @RequestHeader("X-Tenant-Id") Long tenantId) {
        List<RagSession> sessions = sessionService.getSessionDetail(tenantId, sessionId);
        return R.ok(sessions);
    }

    /**
     * 软删除会话
     */
    @DeleteMapping("/{sessionId}")
    public R<Void> deleteSession(@PathVariable String sessionId,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        sessionService.deleteSession(tenantId, sessionId);
        return R.ok();
    }

    /**
     * 更新会话信息
     */
    @PutMapping("/{sessionId}")
    public R<Void> updateSession(@PathVariable String sessionId,
                                  @RequestBody SessionUpdateRequest request,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        sessionService.updateSession(tenantId, sessionId, request.getTitle(), request.getTags());
        return R.ok();
    }
}
```

- [ ] **Step 3: 提交 Controller**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/SessionController.java
git add company-rag-web/src/main/java/com/company/rag/web/model/*.java
git commit -m "feat: add SessionController with CRUD APIs"
```

---

## Task 6: 集成到 RAG 搜索流程

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/model/RagQuery.java`
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/model/RagResult.java`
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java`

- [ ] **Step 1: 修改 RagQuery 添加 sessionId 和 userId 字段**

```java
// 在 RagQuery.java 中添加
private String sessionId;
private Long userId;
```

- [ ] **Step 2: 修改 RagResult 添加 sessionId 字段**

```java
// 在 RagResult.java 中添加
private String sessionId;

// 添加 getter/setter
public String getSessionId() { return sessionId; }
public void setSessionId(String sessionId) { this.sessionId = sessionId; }
```

- [ ] **Step 3: 修改 RagSearchServiceImpl 集成会话保存**

```java
// 在 RagSearchServiceImpl.java 中添加
private final RagSessionService sessionService;

// 在构造函数中注入
public RagSearchServiceImpl(..., RagSessionService sessionService) {
    this.sessionService = sessionService;
    // ... 其他字段
}

// 在 search() 方法中添加会话保存逻辑
@Override
public RagResult search(RagQuery query) {
    long start = System.currentTimeMillis();
    // ... 现有逻辑 ...

    RagResult result = new RagResult();
    result.setAnswer(answer);
    // ... 其他设置 ...

    // 新增：保存会话
    if (query.getSessionId() != null && !query.getSessionId().isEmpty()) {
        // 异步保存会话
        CompletableFuture.runAsync(() -> {
            try {
                sessionService.saveConversation(
                    query.getTenantId(),
                    query.getSessionId(),
                    query.getUserId() != null ? query.getUserId() : 1L,
                    query.getQuery(),
                    answer,
                    context,
                    0,  // tokensInput - 后续从 LLM 响应中获取
                    0,  // tokensOutput
                    llmMs
                );
            } catch (Exception e) {
                log.error("保存会话失败 | sessionId={}", query.getSessionId(), e);
            }
        });
        result.setSessionId(query.getSessionId());
    } else {
        // 创建新会话
        try {
            RagSessionMeta session = sessionService.createSession(
                query.getTenantId(),
                query.getUserId() != null ? query.getUserId() : 1L,
                query.getQuery()
            );
            query.setSessionId(session.getSessionId());

            // 保存首轮对话
            sessionService.saveConversation(
                query.getTenantId(),
                query.getSessionId(),
                query.getUserId() != null ? query.getUserId() : 1L,
                query.getQuery(),
                answer,
                context,
                0, 0, llmMs
            );
            result.setSessionId(query.getSessionId());
        } catch (Exception e) {
            log.error("创建会话失败", e);
        }
    }

    return result;
}
```

- [ ] **Step 4: 提交集成代码**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/model/*.java
git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java
git commit -m "feat: integrate session history into RAG search flow"
```

---

## Task 7: 单元测试

**Files:**
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/service/RagSessionServiceTest.java`

- [ ] **Step 1: 创建 RagSessionServiceTest**

```java
package com.company.rag.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.mapper.RagSessionMapper;
import com.company.rag.rag.mapper.RagSessionMetaMapper;
import com.company.rag.rag.service.impl.RagSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagSessionService 单元测试
 */
class RagSessionServiceTest {

    @Mock
    private RagSessionMapper sessionMapper;

    @Mock
    private RagSessionMetaMapper sessionMetaMapper;

    private RagSessionService sessionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sessionService = new RagSessionServiceImpl(sessionMapper, sessionMetaMapper);
    }

    @Test
    void testCreateSession() {
        // Given
        Long tenantId = 1L;
        Long userId = 1L;
        String title = "测试会话";

        RagSessionMeta mockMeta = new RagSessionMeta();
        mockMeta.setId(1L);
        mockMeta.setSessionId("test-session-id");
        mockMeta.setTenantId(tenantId);
        mockMeta.setUserId(userId);
        mockMeta.setTitle(title);

        when(sessionMetaMapper.insert(any(RagSessionMeta.class))).thenReturn(1);
        when(sessionMetaMapper.selectOne(any())).thenReturn(mockMeta);

        // When
        RagSessionMeta result = sessionService.createSession(tenantId, userId, title);

        // Then
        assertNotNull(result);
        assertEquals("test-session-id", result.getSessionId());
        assertEquals(title, result.getTitle());
        verify(sessionMetaMapper, times(1)).insert(any(RagSessionMeta.class));
    }

    @Test
    void testSaveConversation() {
        // Given
        Long tenantId = 1L;
        String sessionId = "test-session-id";
        Long userId = 1L;
        String query = "测试问题";
        String answer = "测试回答";
        String context = "测试上下文";

        RagSessionMeta mockMeta = new RagSessionMeta();
        mockMeta.setSessionId(sessionId);

        when(sessionMetaMapper.selectOne(any())).thenReturn(mockMeta);
        when(sessionMapper.insert(any(RagSession.class))).thenReturn(1);

        // When
        sessionService.saveConversation(tenantId, sessionId, userId, query, answer, context, 10, 20, 100);

        // Then
        verify(sessionMapper, times(1)).insert(any(RagSession.class));
    }

    @Test
    void testGetSessionList() {
        // Given
        Long tenantId = 1L;
        Long userId = 1L;
        int page = 1;
        int size = 20;

        RagSessionMeta meta1 = new RagSessionMeta();
        meta1.setTitle("会话 1");
        RagSessionMeta meta2 = new RagSessionMeta();
        meta2.setTitle("会话 2");

        when(sessionMetaMapper.selectSessionList(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(Arrays.asList(meta1, meta2));
        when(sessionMetaMapper.countSessionList(any(), any(), any(), any())).thenReturn(2L);

        // When
        Page<RagSessionMeta> result = sessionService.getSessionList(tenantId, userId, null, null, page, size);

        // Then
        assertEquals(2, result.getRecords().size());
        assertEquals(2L, result.getTotal());
    }

    @Test
    void testGetSessionDetail() {
        // Given
        Long tenantId = 1L;
        String sessionId = "test-session-id";

        RagSession session1 = new RagSession();
        session1.setQuery("问题 1");
        RagSession session2 = new RagSession();
        session2.setQuery("问题 2");

        when(sessionMapper.selectList(any())).thenReturn(Arrays.asList(session1, session2));

        // When
        List<RagSession> result = sessionService.getSessionDetail(tenantId, sessionId);

        // Then
        assertEquals(2, result.size());
    }

    @Test
    void testDeleteSession() {
        // Given
        Long tenantId = 1L;
        String sessionId = "test-session-id";

        RagSessionMeta mockMeta = new RagSessionMeta();
        mockMeta.setSessionId(sessionId);

        when(sessionMetaMapper.selectOne(any())).thenReturn(mockMeta);

        // When
        sessionService.deleteSession(tenantId, sessionId);

        // Then
        verify(sessionMetaMapper, times(1)).updateById(any(RagSessionMeta.class));
        assertTrue(mockMeta.getIsDeleted());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -Dtest=RagSessionServiceTest
```

预期：所有测试通过

- [ ] **Step 3: 提交测试**

```bash
git add company-rag-rag/src/test/java/com/company/rag/rag/service/RagSessionServiceTest.java
git commit -m "test: add unit tests for RagSessionService"
```

---

## Task 8: 前端 UI 实现

**Files:**
- Modify: `company-rag-web/src/main/resources/templates/index.html`

- [ ] **Step 1: 修改 CSS 样式**

在 `<style>` 标签中添加：

```css
.sidebar_sessions { width: 280px; background: white; border-right: 1px solid #e4e7ed; display: flex; flex-direction: column; flex-shrink: 0; }
.sidebar_sessions.hidden { display: none; }
.sessions-header { padding: 16px; border-bottom: 1px solid #e4e7ed; display: flex; justify-content: space-between; align-items: center; }
.sessions-header h3 { font-size: 15px; color: #303133; margin: 0; }
.session-search { padding: 12px 16px; border-bottom: 1px solid #f0f0f0; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.session-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: all 0.2s; margin-bottom: 4px; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #e6f0ff; border-left: 3px solid #1a73e8; }
.session-item .session-title { font-size: 13px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 4px; }
.session-item .session-meta { font-size: 11px; color: #909399; display: flex; justify-content: space-between; }
.session-actions { padding: 12px 16px; border-top: 1px solid #e4e7ed; }
.main-with-sessions { display: flex; }
```

- [ ] **Step 2: 修改 HTML 结构**

在 `<div class="app-main">` 中，在侧边栏之前添加会话列表：

```html
<!-- 会话列表侧边栏 -->
<aside :class="['sidebar_sessions', { hidden: !showSessions }]">
    <div class="sessions-header">
        <h3>💬 会话历史</h3>
        <el-button size="small" circle @click="showSessions = false">✕</el-button>
    </div>
    <div class="session-search">
        <el-input v-model="sessionKeyword" placeholder="搜索会话..." size="small" clearable @input="loadSessionList"></el-input>
    </div>
    <div class="session-list">
        <div v-if="sessionList.length === 0" class="empty-state" style="height:200px;">
            <p style="font-size:13px;">暂无会话</p>
        </div>
        <div v-for="session in sessionList" :key="session.sessionId" 
             :class="['session-item', { active: currentSessionId === session.sessionId }]"
             @click="loadSessionDetail(session.sessionId)">
            <div class="session-title">{{ session.title || '未命名会话' }}</div>
            <div class="session-meta">
                <span>{{ session.messageCount || 0 }} 条消息</span>
                <span>{{ formatSessionTime(session.createTime) }}</span>
            </div>
        </div>
        <div v-if="sessionLoading" style="text-align:center;padding:12px;">
            <el-icon class="is-loading">🔄</el-icon>
        </div>
    </div>
    <div class="session-actions">
        <el-button type="primary" size="small" style="width:100%;" @click="createNewSession">➕ 新建会话</el-button>
    </div>
</aside>
```

- [ ] **Step 3: 修改 Vue 组件数据**

在 `setup()` 函数中添加：

```javascript
const showSessions = ref(false);
const sessionList = ref([]);
const sessionKeyword = ref('');
const currentSessionId = ref(null);
const sessionLoading = ref(false);
const sessionPage = ref(1);
const sessionTotal = ref(0);
```

- [ ] **Step 4: 添加会话管理函数**

```javascript
// 加载会话列表
async function loadSessionList(page = 1) {
    sessionLoading.value = true;
    try {
        const params = new URLSearchParams({
            page: page,
            size: 20,
            keyword: sessionKeyword.value || ''
        });
        const res = await fetch(`/api/session/list?${params}`, {
            headers: { 'X-Tenant-Id': tenantId.value }
        });
        const json = await res.json();
        if (json.code === 200) {
            sessionList.value = json.data.records || [];
            sessionTotal.value = json.data.total || 0;
            sessionPage.value = page;
        }
    } catch(e) {
        console.error('加载会话列表失败', e);
    } finally {
        sessionLoading.value = false;
    }
}

// 创建新会话
async function createNewSession() {
    try {
        const res = await fetch('/api/session', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Tenant-Id': tenantId.value
            },
            body: JSON.stringify({ title: '新会话' })
        });
        const json = await res.json();
        if (json.code === 200) {
            currentSessionId.value = json.data.sessionId;
            showSessions.value = true;
            loadSessionList();
            ElementPlus.ElMessage.success('创建会话成功');
        }
    } catch(e) {
        console.error('创建会话失败', e);
        ElementPlus.ElMessage.error('创建会话失败');
    }
}

// 加载会话详情
async function loadSessionDetail(sessionId) {
    try {
        const res = await fetch(`/api/session/${sessionId}`, {
            headers: { 'X-Tenant-Id': tenantId.value }
        });
        const json = await res.json();
        if (json.code === 200) {
            currentSessionId.value = sessionId;
            // 加载历史消息到聊天区域
            messages.value = json.data.map(s => ({
                role: 'user',
                content: s.query
            })).flatMap((msg, i) => [
                msg,
                { role: 'assistant', content: json.data[i]?.answer || '' }
            ]).filter(m => m.content);
            showSessions.value = false;
        }
    } catch(e) {
        console.error('加载会话详情失败', e);
        ElementPlus.ElMessage.error('加载会话详情失败');
    }
}

// 格式化时间
function formatSessionTime(timeStr) {
    if (!timeStr) return '';
    const date = new Date(timeStr);
    const now = new Date();
    const diff = now - date;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
    return date.toLocaleDateString('zh-CN');
}

// 修改 sendMessage 函数，携带 sessionId
async function sendMessage() {
    const text = userInput.value.trim();
    if (!text || isLoading.value) return;

    messages.value.push({ role: 'user', content: text });
    userInput.value = '';
    isLoading.value = true;
    scrollToBottom();

    try {
        const res = await fetch('/api/rag/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': tenantId.value },
            body: JSON.stringify({
                query: text,
                sessionId: currentSessionId.value,
                tenantId: parseInt(tenantId.value),
                userId: 1,
                topK: 10,
                rerankTopK: 5,
                enableRerank: true
            })
        });
        const json = await res.json();
        if (json.code === 200 && json.data) {
            const data = json.data;
            messages.value.push({
                role: 'assistant',
                content: data.answer || '抱歉，没有找到相关答案。',
                sources: data.sessions || []
            });
            // 如果是首次对话，保存 sessionId
            if (!currentSessionId.value && data.sessionId) {
                currentSessionId.value = data.sessionId;
                loadSessionList(); // 刷新会话列表
            }
            if (data.metrics) {
                metrics.value.totalRequests++;
                metrics.value.avgLatency = data.metrics.totalMs || 0;
            }
        } else {
            messages.value.push({ role: 'assistant', content: '查询失败：' + (json.msg || '未知错误') });
        }
    } catch(e) {
        messages.value.push({ role: 'assistant', content: '网络错误，请检查后端服务是否启动' });
    }
    isLoading.value = false;
    scrollToBottom();
}
```

- [ ] **Step 5: 添加切换会话列表按钮**

在 header 的 `header-actions` 中添加：

```html
<el-button size="small" plain round @click="showSessions = !showSessions" style="color:white;border-color:rgba(255,255,255,0.6);">📋 会话</el-button>
```

- [ ] **Step 6: 在 onMounted 中加载会话列表**

```javascript
onMounted(() => {
    loadDocuments();
    loadTenants();
    loadSessionList();
});
```

- [ ] **Step 7: 更新 return 导出**

```javascript
return {
    // ... 现有导出
    showSessions, sessionList, sessionKeyword, currentSessionId, sessionLoading,
    loadSessionList, createNewSession, loadSessionDetail, formatSessionTime,
    sendMessage
};
```

- [ ] **Step 8: 提交前端代码**

```bash
git add company-rag-web/src/main/resources/templates/index.html
git commit -m "feat: add session history UI with list and search"
```

---

## Task 9: 集成测试

**Files:**
- Create: `company-rag-web/src/test/java/com/company/rag/web/controller/SessionControllerTest.java`

- [ ] **Step 1: 创建 SessionControllerTest**

```java
package com.company.rag.web.controller;

import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.service.RagSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SessionController 集成测试
 */
@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagSessionService sessionService;

    @Autowired
    private ObjectMapper objectMapper;

    private RagSessionMeta testMeta;
    private RagSession testSession;

    @BeforeEach
    void setUp() {
        testMeta = new RagSessionMeta();
        testMeta.setSessionId("test-session-id");
        testMeta.setTitle("测试会话");
        testMeta.setTenantId(1L);
        testMeta.setUserId(1L);
        testMeta.setMessageCount(5);

        testSession = new RagSession();
        testSession.setSessionId("test-session-id");
        testSession.setQuery("测试问题");
        testSession.setAnswer("测试回答");
    }

    @Test
    void testCreateSession() throws Exception {
        when(sessionService.createSession(anyLong(), anyLong(), anyString())).thenReturn(testMeta);

        mockMvc.perform(post("/api/session")
                .header("X-Tenant-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"测试会话\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("test-session-id"));

        verify(sessionService, times(1)).createSession(anyLong(), anyLong(), anyString());
    }

    @Test
    void testGetSessionList() throws Exception {
        List<RagSessionMeta> sessions = Arrays.asList(testMeta);
        when(sessionService.getSessionList(anyLong(), anyLong(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1, sessions));

        mockMvc.perform(get("/api/session/list")
                .header("X-Tenant-Id", "1")
                .param("page", "1")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].sessionId").value("test-session-id"));
    }

    @Test
    void testGetSessionDetail() throws Exception {
        List<RagSession> sessions = Arrays.asList(testSession);
        when(sessionService.getSessionDetail(anyLong(), anyString())).thenReturn(sessions);

        mockMvc.perform(get("/api/session/test-session-id")
                .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].query").value("测试问题"));
    }

    @Test
    void testDeleteSession() throws Exception {
        doNothing().when(sessionService).deleteSession(anyLong(), anyString());

        mockMvc.perform(delete("/api/session/test-session-id")
                .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sessionService, times(1)).deleteSession(anyLong(), anyString());
    }

    @Test
    void testUpdateSession() throws Exception {
        doNothing().when(sessionService).updateSession(anyLong(), anyString(), any(), any());

        mockMvc.perform(put("/api/session/test-session-id")
                .header("X-Tenant-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"更新后的标题\",\"tags\":[\"标签 1\",\"标签 2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sessionService, times(1)).updateSession(anyLong(), anyString(), any(), any());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -Dtest=SessionControllerTest
```

预期：所有测试通过

- [ ] **Step 3: 提交测试**

```bash
git add company-rag-web/src/test/java/com/company/rag/web/controller/SessionControllerTest.java
git commit -m "test: add integration tests for SessionController"
```

---

## Task 10: 最终验证和文档

**Files:**
- Modify: `README.md` (可选)

- [ ] **Step 1: 运行完整测试套件**

```bash
mvn clean test
```

预期：所有测试通过

- [ ] **Step 2: 本地启动验证**

```bash
mvn spring-boot:run
```

访问 http://localhost:8080，测试：
1. 点击"📋 会话"按钮，查看会话列表
2. 点击"➕ 新建会话"，创建新会话
3. 发送消息，验证会话自动保存
4. 切换会话，验证历史消息加载
5. 搜索会话，验证搜索功能
6. 删除会话，验证软删除

- [ ] **Step 3: 更新 README（可选）**

在 README.md 中添加会话历史功能说明：

```markdown
## 功能特性

- ✅ 文档上传与解析
- ✅ 智能切分策略
- ✅ 混合检索 + Rerank
- ✅ 流式回答
- ✅ **会话历史管理** (新增)
  - 多轮对话会话管理
  - 会话列表查询（分页 + 搜索）
  - 会话详情查看
  - 会话创建/删除/更新
```

- [ ] **Step 4: 提交最终代码**

```bash
git add README.md
git commit -m "docs: update README with session history feature"
```

- [ ] **Step 5: 推送到远程仓库**

```bash
git push origin main && git push gitee main
```

---

## 验收标准

- [ ] 数据库表创建成功（`rag_session_meta` 和 `rag_session`）
- [ ] 所有单元测试通过
- [ ] 所有集成测试通过
- [ ] 前端可以正常创建会话
- [ ] 前端可以正常查看会话列表
- [ ] 前端可以正常切换会话
- [ ] 对话自动保存到数据库
- [ ] 会话搜索功能正常
- [ ] 软删除功能正常
- [ ] 多租户数据隔离验证通过
- [ ] 代码已推送到 GitHub 和 Gitee

---

**计划完成！** 接下来可以选择执行方式：

**1. Subagent-Driven（推荐）** - 为每个任务分派独立的 subagent，任务间进行 review，快速迭代

**2. Inline Execution** - 在当前会话中使用 executing-plans 技能批量执行任务

**你选择哪种方式？**
