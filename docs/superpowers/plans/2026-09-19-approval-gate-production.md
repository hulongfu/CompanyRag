# 审批门（Approval Gate）强制阻塞审批 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在同步 chat 主链路不重建构的前提下，为工具执行路径插入强制人工审批门（方案 A 同步等待）：命中审批判定的工具调用在 `AggregatedToolCallbackProvider.call()` 内 `agentTool.execute()` 之前挂起落库 `PENDING` → 同步轮询 DB 等待人工 approve/deny → approve 执行并把结果作为本次 `call()` 返回值回填黑盒 `ReactAgent`、deny 返回拒绝文案、超时自动转 DENIED。判定 = `AgentTool.requiresApproval()` 自声明 + 高危工具兜底集（execute 强制）。

**Architecture:**
1. **判定层**：`AgentTool` 接口新增默认方法 `default boolean requiresApproval(){return false;}`；`ExecuteTool` 重写返回 `true`。公布 `AgentTool.requiresApproval()` 与"高危兜底集（execute）"。
2. **审批服务（agent 模块）**：`ToolApprovalService` + `ToolApprovalRequest` 实体 + Mapper（新增 agency 的 mapper 包，因 agent 不在现有 MapperScan 范围）+ `ApprovalProperties`（enabled/timeout-seconds）。含状态机 PENDING→EXECUTED/DENIED + 幂等。
3. **拦截回填（rag 模块）**：`AggregatedToolCallbackProvider` 新增构造注入 `ToolApprovalService`，在 `call()` 内 `execute()` 前插入 `gate(...)` + `await(...)`；approve 后执行 `execute()` 并把结果作为返回值、deny/超时返回文案。
4. **超时收敛（agent 模块）**：仿 `AuditLogAsyncWriter` 用 `ScheduledExecutorService` 后台把超时 PENDING 自动转 DENIED。
5. **接口（web 模块）**：`ToolApprovalController` 3 端点（pending/approve/deny）复用 Spring Security + `X-Tenant-Id` 头 + `R<T>`；简版 HTML 审批面板静态页。
6. **建表（tenant/bootstrap）**：`tool_approval_request` 仿 `answer_eval_result`——新租户 `TenantServiceImpl.buildCreateTableSql/buildCreateIndexSql` + 存量 `SchemaMigrationConfig`，含 RLS + grant sequence。

**Tech Stack:** Java 17 / Spring Boot 3.4 / MyBatis-Plus 3.5.9 / SQL (PG) / JUnit 5 + Mockito

**关联 spec:** `docs/superpowers/specs/2026-09-19-approval-gate-design.md`。前置：`docs/superpowers/specs/2026-09-14-human-in-the-loop-design.md`（本 spec 是其方案①的落地）、`docs/features/2026-09-19-hermes-integration-evaluation.md`。

**安全注意事项（铁律）：**
- 审批门是**叠加**的"人肉确认业务恰当性"层，**不削弱/替代** `SqlSecurityValidator`（仅 SELECT）与 `ExecuteTool` 命令白名单硬校验。`approve` 后仍走 `execute()` 内原有全部校验。
- 新表启用与 `answer_eval_result` 一致的 RLS：`tenant_id = current_tenant_id()` + `FORCE ROW LEVEL SECURITY`（防跨租户，铁律）。schema 名一律白名单 `^[a-zA-Z_][a-zA-Z0-9_]*$`。
- 审批同步等待在 **agent 子线程**内：承载线程依赖入线程手动 `TenantContext.setSchema(...)`（方案 A 的落库/轮询/approve 后 execute 都走该线程；`AggregatedToolCallbackProvider.call()` 由 ReactAgent 在 supplyAsync 子线程内同步触发，schema 已由 `callAgentWithTimeout` 手动 set，正确命中）。**不要在审批服务内自己 set/clear 租户上下文**，沿用调用线程的。
- `args_json` 含命令原文（供审批人复核），属功能需要；不新增脱敏（白名单边界下可接受）。记审计。
- approve/deny 用状态机幂等（仅 PENDING 可决策），防重复决策/二次执行。超时对**会话外残留 PENDING** 由收敛器兜底转 DENIED。
- 所有审批 API `@PreAuthorize`，租户 ID 仅取 `X-Tenant-Id` 头，忽略请求体。

---

## 文件结构

**判定层（agent）：**
- Modify `company-rag-agent/.../tool/AgentTool.java`（加默认 `requiresApproval()`）
- Modify `company-rag-agent/.../tool/ExecuteTool.java`（重写返回 `true`）

**审批服务（agent，新增）：**
- Create `company-rag-agent/.../approve/ToolApprovalStatus.java`（状态机常量）
- Create `company-rag-agent/.../approve/ToolApprovalRequest.java`（实体）
- Create `company-rag-agent/.../approve/ToolApprovalRequestMapper.java`（Mapper）
- Create `company-rag-agent/.../approve/ToolApprovalService.java`（gate/await/approve/deny + 高危兜底集）
- Create `company-rag-agent/.../approve/ToolApprovalConverger.java`（超时自动 DENIED，ScheduledExecutorService）
- Create `company-rag-agent/.../config/ApprovalProperties.java`（`agent.approval.*`）

**拦截回填（rag）：**
- Modify `company-rag-rag/.../config/AggregatedToolCallbackProvider.java`（构造注入 + call() 前插 gate/await）

**接口（web）：**
- Create `company-rag-web/.../controller/ToolApprovalController.java`
- Create `company-rag-web/src/main/resources/static/tool-approval/index.html`（简版审批面板）

**建表（tenant / bootstrap）：**
- Modify `company-rag-tenant/.../tenant/service/impl/TenantServiceImpl.java`
- Modify `company-rag-bootstrap/.../bootstrap/SchemaMigrationConfig.java`
- Modify `company-rag-bootstrap/.../bootstrap/CompanyRagApplication.java`（MapperScan 加 agent mapper 包）

**配置：**
- Modify `company-rag-bootstrap/src/main/resources/application.yml` + `application-dev.yml`（`agent.approval` 段）

**测试：**
- Create `ToolApprovalServiceTest.java`
- Create `ToolApprovalControllerTest.java`
- Modify `ExecuteToolTest.java`（若在测试断言 requiresApproval）——或新增 `AgentToolApprovalTest.java`

---

## 阶段 1：判定层 + 审批服务核心

### Task 1: `AgentTool` 增加 `requiresApproval()` 默认方法

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentTool.java`

- [ ] **Step 1: 新增默认方法**

```java
public interface AgentTool {
    // 既有 getName/getDescription/getParameterSchema/execute(Map) 保持不变

    /**
     * 该工具调用是否需要经过人类审批门。
     * 默认 false；有外部副作用 / 动作类 / 高风险的工具重写返回 true。
     * 判定 = 本方法 OR 高危工具兜底集（ToolApprovalService 维护）。
     */
    default boolean requiresApproval() { return false; }
}
```

- [ ] **Step 2: 编译验证** `cd /d/tmp/CompanyRag && mvn -o -q -DskipTests compile` → BUILD SUCCESS

- [ ] **Step 3: Commit** `feat(agent): AgentTool 新增 requiresApproval() 默认自声明方法`

### Task 2: `ExecuteTool` 声明需审批

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ExecuteTool.java`

- [ ] **Step 1: 重写 `requiresApproval()`**

在 `getParameterSchema()` 之后新增：
```java
@Override
public boolean requiresApproval() {
    // 动作类工具：执行受约束命令，需人工确认业务恰当性（叠加在命令白名单硬校验之上）
    return true;
}
```

- [ ] **Step 2: 编译验证**（同 Task 1 命令）

- [ ] **Step 3: Commit** `feat(agent): ExecuteTool 声明 requiresApproval=true（动作类需审批）`

### Task 3: 审批状态 + 实体 + Mapper（agent 模块新增 mapper 包）

**Files:**
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/approve/ToolApprovalStatus.java`
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/approve/ToolApprovalRequest.java`
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/approve/ToolApprovalRequestMapper.java`

- [ ] **Step 1: 状态机常量类**

```java
package com.company.rag.agent.approve;

/** 审批单状态机：PENDING → EXECUTED / DENIED。仅 PENDING 可被决策（幂等），超时自动转 DENIED。 */
public final class ToolApprovalStatus {
    private ToolApprovalStatus() {}
    public static final String PENDING = "PENDING";
    public static final String EXECUTED = "EXECUTED";
    public static final String DENIED = "DENIED";
}
```

- [ ] **Step 2: 实体**

```java
package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 工具审批单（每租户 schema 一张，RLS 按 tenant_id 隔离）。 */
@Data
@TableName("tool_approval_request")
public class ToolApprovalRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;            // RLS 依据，取自调用线程 TenantContext
    private String toolName;          // 工具名，如 execute
    private String argsJson;          // 参数快照（审批人复核用）
    private String sessionId;         // 关联会话
    private Long requesterUserId;     // 发起调用用户（调用线程 TenantContext）
    private String status;            // PENDING/EXECUTED/DENIED
    private String result;            // approve 后执行结果回填
    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;
}
```

- [ ] **Step 3: Mapper**

```java
package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 工具审批单 Mapper（按租户 schema 隔离，RLS 兜底）。 */
@Mapper
public interface ToolApprovalRequestMapper extends BaseMapper<ToolApprovalRequest> {
}
```

- [ ] **Step 4: 扩 MapperScan（关键——agent mapper 不在现有扫描范围）**

现有 `CompanyRagApplication` L32-33：
```java
@MapperScan({"com.company.rag.tenant.mapper", "com.company.rag.document.mapper",
             "com.company.rag.rag.mapper", "com.company.rag.rag.eval.answer"})
```
改为追加 `"com.company.rag.agent.approve"`.
> 原因：`com.company.rag.agent` 不在原扫描范围，不加会「Bean 注入失败：Mapper 未注册」。

- [ ] **Step 5: 编译验证** → BUILD SUCCESS

- [ ] **Step 6: Commit** `feat(agent): 新增 tool_approval_request 实体/Mapper/状态机并扩 MapperScan`

### Task 4: `ApprovalProperties` 配置属性

**Files:**
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/config/ApprovalProperties.java`

- [ ] **Step 1: 新增 `@ConfigurationProperties("agent.approval")`**

```java
package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 审批门配置（全局开关，非 per-tool）。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.approval")
public class ApprovalProperties {
    /** 总开关：默认关闭。关闭时全部工具直接执行、不走审批门。 */
    private boolean enabled = false;
    /** 同步等待人工审批的上限（秒）。默认 300，与 Agent 整体 5 分钟超时对齐；超时自动转 DENIED。 */
    private long timeoutSeconds = 300;
    /** 轮询 DB 状态间隔（毫秒），用于等待期间循环查询，避免忙等。 */
    private long pollIntervalMs = 500;
    /** 高危工具兜底集（逗号分隔工具名）：即使 requiresApproval=false 也强制审批。 */
    private String highRiskTools = "execute";
}
```

- [ ] **Step 2: 编译验证** → BUILD SUCCESS

- [ ] **Step 3: Commit** `feat(agent): ApprovalProperties 审批门配置（enabled/timeout/高危兜底集）`

### Task 5: `ToolApprovalService`（gate / await / approve / deny / pending + 高危兜底）

**Files:**
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/approve/ToolApprovalService.java`
- Test: `company-rag-agent/src/test/java/com/company/rag/agent/approve/ToolApprovalServiceTest.java`

- [ ] **Step 1: 实现核心服务**

```java
package com.company.rag.agent.approve;

import com.company.rag.agent.config.ApprovalProperties;
import com.company.rag.agent.tool.AgentTool;
import com.company.rag.tenant.context.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人类审批门服务（方案 A 同步等待）。
 * 设计：审批门是叠加的"人肉确认业务恰当性"层，不削弱/替代各工具既有硬校验。
 * 判定 = 工具 requiresApproval() OR 高危兜底集（防工具漏声明导致默认放行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalService {

    private final ToolApprovalRequestMapper mapper;
    private final ApprovalProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 返回按当前租户/线程上下文生成的一个审批单（PENDING 落库）。 */
    public ToolApprovalRequest createRequest(String toolName, Map<String,Object> params) {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setTenantId(TenantContext.getTenantId());   // 铁律：取自调用线程，不新起上下文
        req.setToolName(toolName);
        req.setArgsJson(toJson(params));
        req.setSessionId(TenantContext.getSessionId());
        req.setRequesterUserId(TenantContext.getUserId());
        req.setStatus(ToolApprovalStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        mapper.insert(req);
        log.info("[APPROVAL] 待审批单创建 id={}, tool={}, tenantId={}", req.getId(), toolName, TenantContext.getTenantId());
        return req;
    }

    /** 是否命中审批：未开启总开关直接 false；否则 高危兜底集 或 tool.requiresApproval()。 */
    public boolean needsApproval(String toolName, AgentTool tool) {
        if (tool == null) return false;
        if (!props.isEnabled()) return false;
        return highRiskTools().contains(toolName) || tool.requiresApproval();
    }

    /** 同步等待人工结果（在调用线程内轮询 DB）。返回是否放行执行。 */
    public ApprovalVerdict await(Long requestId, String toolName) {
        long deadline = System.currentTimeMillis() + props.getTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ToolApprovalRequest row = mapper.selectById(requestId);
            if (row == null) {
                log.warn("[APPROVAL] 审批单不存在 id={}", requestId);
                return ApprovalVerdict.deny("审批单不存在", false);
            }
            if (ToolApprovalStatus.EXECUTED.equals(row.getStatus())) {
                return ApprovalVerdict.approve();      // 放行执行 execute()
            }
            if (ToolApprovalStatus.DENIED.equals(row.getStatus())) {
                return ApprovalVerdict.deny(row.getResult(), true);
            }
            try { Thread.sleep(props.getPollIntervalMs()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return ApprovalVerdict.deny("等待被中断", false); }
        }
        // 超时：会话外兜底（这里同步线程也可直接转 DENIED 收敛）
        markDenied(requestId, "审批超时(" + props.getTimeoutSeconds() + "s)，已自动拒绝");
        return ApprovalVerdict.deny("审批超时，已自动拒绝", false);
    }

    /** approve：幂等，仅 PENDING 可决策。 */
    public boolean approve(Long id) {
        ToolApprovalRequest row = mapper.selectById(id);
        if (row == null) return false;
        if (!ToolApprovalStatus.PENDING.equals(row.getStatus())) {
            log.warn("[APPROVAL] 已决策，忽略重复 approve id={}, status={}", id, row.getStatus());
            return false;
        }
        row.setStatus(ToolApprovalStatus.EXECUTED);
        row.setDecidedAt(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[APPROVAL] 已批准 id={}, tool={}", id, row.getToolName());
        return true;
    }

    /** deny：置 DENIED，可选原因。idempotent。 */
    public boolean deny(Long id, String reason) {
        ToolApprovalRequest row = mapper.selectById(id);
        if (row == null) return false;
        if (!ToolApprovalStatus.PENDING.equals(row.getStatus())) return false;
        row.setStatus(ToolApprovalStatus.DENIED);
        row.setResult(reason);
        row.setDecidedAt(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[APPROVAL] 已拒绝 id={}, tool={}, reason={}", id, row.getToolName(), reason);
        return true;
    }

    private void markDenied(Long id, String reason) { deny(id, reason); }

    public long getTimeoutSeconds() { return props.getTimeoutSeconds(); }

    private Set<String> highRiskTools() {
        if (props.getHighRiskTools() == null || props.getHighRiskTools().isBlank()) return Set.of();
        return Arrays.stream(props.getHighRiskTools().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private String toJson(Map<String,Object> params) {
        try { return objectMapper.writeValueAsString(params); }
        catch (JsonProcessingException e) { log.warn("参数序列化失败，存原始 toString", e); return String.valueOf(params); }
    }

    /** 审批等待裁决。 */
    public static final class ApprovalVerdict {
        private final boolean proceed;
        private final boolean deniedByUser;
        private final String denyMessage;
        private ApprovalVerdict(boolean proceed, boolean deniedByUser, String denyMessage) {
            this.proceed = proceed; this.deniedByUser = deniedByUser; this.denyMessage = denyMessage;
        }
        public static ApprovalVerdict approve() { return new ApprovalVerdict(true, false, null); }
        public static ApprovalVerdict deny(String msg, boolean byUser) { return new ApprovalVerdict(false, byUser, msg); }
        public boolean shouldProceed() { return proceed; }
        public String getDenyMessage() { return denyMessage; }
        // deniedByUser 供需要区分文案时使用
    }
}
```

- [ ] **Step 2: 单元测试（正常/边界/异常）**

`ToolApprovalServiceTest`（mock `ToolApprovalRequestMapper` + `ApprovalProperties`）覆盖：
- `needsApproval`：不开启返回 false；开启时命中高危兜底集（execute）返回 true；命中 `tool.requiresApproval()`（mock AgentTool 返回 true）返回 true；默认 false 返回 false。
- `approve`/`deny` 幂等：非 PENDING 二次决策返回 false。
- `await`（用较短的 timeoutSeconds 注入 + 预置 EXECUTED 行）：返回 approve；预置 DENIED 返回 deny 且带 reason；超时（timeout=0）返回 deny。

- [ ] **Step 3: 运行测试** `cd /d/tmp/CompanyRag && mvn -o -q -pl company-rag-agent -am test -Dtest=ToolApprovalServiceTest` → PASS

- [ ] **Step 4: Commit** `feat(agent): ToolApprovalService 审批门核心（gate/await/approve/deny+高危兜底）`

### Task 6: 审批超时收敛器（`ToolApprovalConverger`）

**Files:**
- Create: `company-rag-agent/src/main/java/com/company/rag/agent/approve/ToolApprovalConverger.java`

- [ ] **Step 1: ScheduledExecutorService 后台把超时 PENDING 转 DENIED**

仿 `AuditLogAsyncWriter`（项目无 @EnableScheduling，自建 `ScheduledExecutorService`）：

```java
package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.agent.config.ApprovalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审批超时收敛器：后台周期扫描超时的 PENDING 审批单，自动转 DENIED。
 * 项目未开启 @EnableScheduling，故仿 AuditLogAsyncWriter 自建单线程调度器。
 * 补充兜底：即使同步等待线程也转了 DENIED，这里保证「会话外残留 PENDING」不成为孤儿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolApprovalConverger {

    private final ToolApprovalRequestMapper mapper;
    private final ApprovalProperties props;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) return;   // 总开关关闭不启动
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tool-approval-converger");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::converge, 30, 30, TimeUnit.SECONDS);
        log.info("[APPROVAL] 超时收敛器启动：超时阈值 {}s", props.getTimeoutSeconds());
    }

    /** 扫描所有 PENDING 且 requested_at 早于 (now - timeoutSeconds) 的单，置 DENIED。 */
    void converge() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getTimeoutSeconds());
            List<ToolApprovalRequest> expired = mapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalRequest>()
                            .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING)
                            .lt(ToolApprovalRequest::getRequestedAt, threshold));
            for (ToolApprovalRequest row : expired) {
                row.setStatus(ToolApprovalStatus.DENIED);
                row.setResult("审批超时(" + props.getTimeoutSeconds() + "s)，已自动拒绝");
                row.setDecidedAt(LocalDateTime.now());
                mapper.updateById(row);
                log.info("[APPROVAL] 超时转 DENIED id={}, tool={}", row.getId(), row.getToolName());
            }
        } catch (Exception e) {
            log.warn("[APPROVAL] 超时收敛扫描异常（不影响下次）：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
```

> 注：本器以全部租户的 `tool_approval_request` 为扫描对象（MapperScan + schema 拦截器决定其在连接上跑哪个 schema）。若需逐租户，需按 schema 遍历——但本项目 Mapper 依赖当前 schema（search_path），Converger 线程无租户上下文（schema=public）。**风险提示（见 Self-Review）：Converger 扫描范围需确认 schema 命中。** 若 public 下无表会导致查询异常（已 try/catch 兜底）。可选项：为提升可靠性，可在 `Converger` 启动时遍历 `information_schema` 的 `tenant_%` schemas，逐个 `TenantContext.setSchema` + 执行收敛。**本计划采用「遍历 tenant_% schema + 手动 setSchema」的稳妥版**（见下 Step 2 修正）。

- [ ] **Step 2: 修正为逐租户 schema 遍历（可靠收敛）——必须同时 setSchema + setTenantId**

**为何必须两项都设置（已核实拦截器源码）：**
- `TenantSchemaInterceptor.applyTenantContext`（`tenant/.../TenantSchemaInterceptor.java` L90）：**只有 `schema != null && !schema.isBlank() && tenantId != null` 才进入租户分支**（`SET search_path TO {schema}, public`）。
- `TenantMyBatisPlusConfig` 的 `TenantLineHandler.getTenantId()`（L33）取 `TenantContext.getTenantId()`，为 null 时 `tenant_id=?` 参数为空 → 查不到行。

所以**只 setSchema 不 setTenantId 会落到 else 分支走 public、且 tenant_id=null**，收敛失效。遍历时需从 `public.sys_tenant` 反查 schemaName→tenantId（`TenantInterceptor` 已用 `tenant.getSchemaName()` 反查同模式）。

`converge()` 实现（注入 `TenantMapper`，用 `LambdaQueryWrapper` 反查，贴合项目既有模式、无裸 SQL）：
```java
private final TenantMapper tenantMapper;   // public.sys_tenant，TenantLine ignoreTable 豁免

void converge() {
    // 1. 枚举所有租户 schema（从 information_schema 取，白名单校验）
    List<String> schemaNames = jdbcTemplate.query(
        "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'tenant_%'",
        (rs, i) -> rs.getString(1));
    for (String schema : schemaNames) {
        try {
            // 2. 用 public.sys_tenant（ignoreTable 豁免，反查可靠不依赖硬拼规则）
            //    TenantInterceptor 同款反查；Java 侧先 setSchema 使本连接进入租户分支，查询 sys_tenant
            Long tenantId = null;
            // sys_tenant 在 public：确保当前连接 search_path=public 下查（用 TenantMapper，ignoreTable 不加 tenant 条件）
            Tenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getSchemaName, schema));
            tenantId = tenant != null ? tenant.getId() : null;
            if (tenantId == null) { log.warn("[APPROVAL] schema {} 无对应租户，跳过", schema); continue; }
            // 3. 设置双上下文（拦截器要求 schema+tenantId 双非空才走租户分支），try-finally clear
            TenantContext.setSchema(schema);
            TenantContext.setTenantId(tenantId);
            try {
                LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getTimeoutSeconds());
                List<ToolApprovalRequest> expired = mapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalRequest>()
                        .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING)
                        .lt(ToolApprovalRequest::getRequestedAt, threshold));
                for (ToolApprovalRequest row : expired) {
                    row.setStatus(ToolApprovalStatus.DENIED);
                    row.setResult("审批超时(" + props.getTimeoutSeconds() + "s)，已自动拒绝");
                    row.setDecidedAt(LocalDateTime.now());
                    mapper.updateById(row);
                    log.info("[APPROVAL] 超时转 DENIED id={}, tool={}", row.getId(), row.getToolName());
                }
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            log.warn("[APPROVAL] schema {} 收敛异常（跳过，不影响下次）：{}", schema, e.getMessage());
        }
    }
}
```

> **依赖与边界**：`ToolApprovalConverger` 注入 `TenantMapper`（agent 模块经 tenant 传递依赖可注入）+ `JdbcTemplate`（仅枚举 schema 用）。`sys_tenant`、`sys_user` 等表被 `TenantLineInnerInterceptor` ignoreTable 豁免（TenantMyBatisPlusConfig L43-51），反查不会追加 `tenant_id=?` 条件，可在任意上下文下查到 —— 故**反查应在未 set 租户上下文（public）时执行**，代码顺序即反查在前、set 双上下文在后（见上）。
> **schema=tenant_+code 的校验**：`tenant_%` LIKE 已足够；真正白名单在 `TenantSchemaInterceptor` 的 `^[a-zA-Z_][a-zA-Z0-9_]*$`（schemas 来自 DB 不受用户输入直接控制，安全）。

- [ ] **Step 3: 编译验证** → BUILD SUCCESS

- [ ] **Step 4: Commit** `feat(agent): 审批超时收敛器把残留 PENDING 自动转 DENIED`

---

## 阶段 2：拦截回填 + 接口 + 建表

### Task 7: `AggregatedToolCallbackProvider` 插入审批门

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/config/AggregatedToolCallbackProvider.java`

- [ ] **Step 1: 构造注入 ToolApprovalService，call() 内 execute() 前插入 gate/await**

```java
// 新增 import
import com.company.rag.agent.approve.ToolApprovalService;

// 构造：新增参数
private final ToolApprovalService toolApprovalService;
public AggregatedToolCallbackProvider(AgentToolRegistry agentToolRegistry,
                                      McpClientRegistry mcpClientRegistry,
                                      ToolApprovalService toolApprovalService) {
    this.agentToolRegistry = agentToolRegistry;
    this.mcpClientRegistry = mcpClientRegistry;
    this.toolApprovalService = toolApprovalService;
    this.objectMapper = new ObjectMapper();
}
```

`call(String input)` 内，在 `Map<String,Object> params` 解析之后、`String result = agentTool.execute(params)` 之前插入：

```java
// 审批门：命中则同步等待人工审批（方案 A），approve 后执行并回填、deny/超时返回拒绝文案
if (toolApprovalService.needsApproval(name, agentTool)) {
    var req = toolApprovalService.createRequest(name, params);
    var verdict = toolApprovalService.await(req.getId(), name);
    if (!verdict.shouldProceed()) {
        log.info("[APPROVAL] 工具 {} 调用被审批拒绝：{}", name, verdict.getDenyMessage());
        return "工具调用被审批拒绝：" + (verdict.getDenyMessage() != null ? verdict.getDenyMessage() : "");
    }
    log.info("[APPROVAL] 工具 {} 已获人工批准，执行", name);
}
// 原有 agentTool.execute(params) ...
```

> `agentTool` 在外部 for 循环已由 `agentToolRegistry.getTool(name)` 得到，`createToolCallback` 方法签名已有该参数，可直接 `agentTool.requiresApproval()`。
> 注意：`name` 变量为模式内局部的工具名（并非 `this` 字段），需确认 createToolCallback 里能访问到（当前已是方法参数）。

- [ ] **Step 2: 编译验证** → BUILD SUCCESS

- [ ] **Step 3: Commit** `feat(rag): AggregatedToolCallbackProvider call() 内嵌审批门（同步拦截+回填）`

### Task 8: `ToolApprovalController` + 简版 HTML 审批面板

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/ToolApprovalController.java`
- Create: `company-rag-web/src/main/resources/static/tool-approval/index.html`
- Test: `company-rag-web/src/test/java/com/company/rag/web/controller/ToolApprovalControllerTest.java`

- [ ] **Step 1: Controller（3 端点，仿 EvalController 鉴权/租户头/R）**

```java
package com.company.rag.web.controller;

import com.company.rag.agent.approve.ToolApprovalRequest;
import com.company.rag.agent.approve.ToolApprovalRequestMapper;
import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.common.model.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 工具审批 Controller：pending / approve / deny。仿 EvalController 鉴权 + 租户头隔离。 */
@RestController
@RequestMapping("/api/tool-approval")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalRequestMapper mapper;

    /** 待审批列表（当前租户 PENDING 单，含参数快照供复核） */
    @GetMapping("/pending")
    @PreAuthorize("isAuthenticated()")
    public R<List<ToolApprovalRequest>> pending(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) throw new IllegalArgumentException("租户 ID 不能为空");
        List<ToolApprovalRequest> list = mapper.selectList(new LambdaQueryWrapper<ToolApprovalRequest>()
                .eq(ToolApprovalRequest::getTenantId, headerTenantId)
                .eq(ToolApprovalRequest::getStatus, "PENDING")
                .orderByDesc(ToolApprovalRequest::getRequestedAt));
        return R.ok(list);
    }

    /** 批准（唤醒等待线程执行） */
    @PostMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    public R<Void> approve(@PathVariable Long id,
                           @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) throw new IllegalArgumentException("租户 ID 不能为空");
        boolean ok = toolApprovalService.approve(id);
        return ok ? R.ok() : R.fail("审批失败：单不存在或已被决策");
    }

    /** 拒绝 */
    @PostMapping("/{id}/deny")
    @PreAuthorize("isAuthenticated()")
    public R<Void> deny(@PathVariable Long id,
                        @RequestBody(required = false) String reason,
                        @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) throw new IllegalArgumentException("租户 ID 不能为空");
        boolean ok = toolApprovalService.deny(id, reason == null || reason.isBlank() ? "人工拒绝" : reason);
        return ok ? R.ok() : R.fail("拒绝失败：单不存在或已被决策");
    }
}
```

- [ ] **Step 2: 简版 HTML 审批面板（轮询 pending + 复核 + approve/deny）**

`static/tool-approval/index.html`：读取 `X-Tenant-Id`（内置一个示例或从页面 js 配置），每 5s 轮询 `/api/tool-approval/pending`，展示 toolName + argsJson，提供批准/拒绝按钮（`fetch` POST）。带简单鉴权（走同一 Security 会话 Cookie/Token）。用原生 JS 即可，无需前端框架。

- [ ] **Step 3: 控制器单测**（mock service+mapper）：
  - 未带 `X-Tenant-Id` 时 3 端点均拒绝（IllegalArgumentException）。
  - `pending` 按租户头过滤返回。
  - `approve`/`deny` 透传 service 且返回 ok/fail。

- [ ] **Step 4: 运行测试** `mvn -o -q -pl company-rag-web -am test -Dtest=ToolApprovalControllerTest` → PASS

- [ ] **Step 5: Commit** `feat(web): ToolApprovalController 审批 API + 简版 HTML 审批面板`

### Task 9: 建表（新租户 `TenantServiceImpl` + 存量 `SchemaMigrationConfig`）

**Files:**
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`
- Modify: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/SchemaMigrationConfig.java`

- [ ] **Step 1: 新租户建表 buildCreateTableSql / buildCreateIndexSql 追加**

⚠️ **占位符计数是本任务易错点**（同 answer_eval_result 教训）。当前：
- `buildCreateTableSql`（L160-253）：现 12 个 `%s`、12 个 `formatted` 实参。在 `answer_eval_result` 段之后新增 `tool_approval_request`（6 个新增 `%s`：表名 + ENABLE + FORCE + DROP POLICY ON + CREATE POLICY ON + GRANT sequence）→ **需补 6 个实参，12→18**。
- `buildCreateIndexSql`（L260-277）：现 14 个 `%s`、14 个实参。加 `tool_approval_request` 索引 2 个（`idx_%s_tool_approval_status` on `%s`、`idx_%s_tool_approval_time` on `%s`）→ **补 4 个实参，14→18**。

新增表 DDL（含 RLS + grant，仿 answer_eval_result）：
```sql
CREATE TABLE IF NOT EXISTS %s.tool_approval_request (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    args_json TEXT,
    session_id VARCHAR(128),
    requester_user_id BIGINT,
    status VARCHAR(16) NOT NULL,
    result TEXT,
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP
);
ALTER TABLE %s.tool_approval_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE %s.tool_approval_request FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_tool_approval ON %s.tool_approval_request;
CREATE POLICY tenant_isolation_tool_approval ON %s.tool_approval_request
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
GRANT USAGE, SELECT ON SEQUENCE %s.tool_approval_request_id_seq TO company_rag_app;
```
索引（加进 buildCreateIndexSql）：
```sql
CREATE INDEX IF NOT EXISTS idx_%s_tool_approval_status ON %s.tool_approval_request (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_%s_tool_approval_time  ON %s.tool_approval_request (tenant_id, requested_at DESC);
```

> **核实并更新占位符数后**再做 `.formatted(...)` 实参扩充（12→18；14→18）。务必人工数一遍避免 `MissingFormatArgumentException`（字符串无编译检查）。可对 `buildCreateTableSql(schemaName)` 写纯单测校验 DDL 不含未替换 `%s` 且含 `tool_approval_request`。

- [ ] **Step 2: 存量租户 SchemaMigrationConfig 幂等迁移**

仿 `answer_eval_result` 迁移 `ApplicationRunner` 新增一个 bean `migrateToolApprovalTable`：遍历 `tenant_%` schemas（白名单校验）执行与 Step 1 相同的 DDL（`CREATE TABLE IF NOT EXISTS ...`）+ 索引 + RLS + grant。catch 异常记日志不抛（防启动失败）。

- [ ] **Step 3: 编译验证** → BUILD SUCCESS

- [ ] **Step 4: Commit** `feat(tenant/bootstrap): tool_approval_request 建表（新租户+存量迁移+RLS+索引）`

### Task 10: 配置 application*.yml

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/application.yml`
- Modify: `company-rag-bootstrap/src/main/resources/application-dev.yml`

- [ ] **Step 1: 加 `agent.approval` 段**

```yaml
agent:
  approval:
    enabled: false        # 审批门总开关：默认关闭。开启后 execute 强制审批，其余看工具自声明
    timeout-seconds: 300   # 同步等待上限，超时自动转 DENIED
    poll-interval-ms: 500
    high-risk-tools: execute
```

> 默认 `enabled: false` 保持主链路现有行为零变化（向后兼容）。

- [ ] **Step 2: Commit** `feat(dev): 新增 agent.approval 审批门配置（默认关）`

---

## 阶段 3：全量校验与提交

### Task 11: Reactor 联合编译 + 相关单测 + 核对

- [ ] **Step 1: 联合编译** `mvn -o -q -DskipTests compile` → BUILD SUCCESS
- [ ] **Step 2: 最窄测试**（不跑全量）：
  - `mvn -o -q -pl company-rag-agent -am test -Dtest=ToolApprovalServiceTest`
  - `mvn -o -q -pl company-rag-web -am test -Dtest=ToolApprovalControllerTest`
  - `mvn -o -q -pl company-rag-rag test`（确认 AggregatedToolCallbackProvider 改动不破坏既有 rag 测试）
- [ ] **Step 3: 核对 git**：本次各 Task 提交按序出现，工作区干净。

---

## Self-Review

**1. Spec 覆盖：**
- Tool 自声明 `requiresApproval()` + 高危兜底集 execute：Task 1/2/5 覆盖。
- 拦截点 `AggregatedToolCallbackProvider.call()` 内 execute() 前：Task 7 覆盖（关键，ReactAgent 不经过 executeTool，已确证）。
- 方案 A 同步等待 + approve 执行回填 + deny/超时文案：Task 5/7 覆盖。
- `tool_approval_request` 表 + RLS + grant + 索引：Task 9 覆盖（新租户 + 存量迁移）。
- REST 3 端点 + 鉴权 + 租户头：Task 8 覆盖。
- 超时自动 DENIED 收敛：Task 6 覆盖。
- `agent.approval.*` 配置：Task 4/10 覆盖。

**2. 模块依赖/扫描：**
- agent 模块新增 mapper，必须扩 `CompanyRagApplication` 的 `@MapperScan`（原不含 `com.company.rag.agent`）。Task 3 Step 4 明确。**漏做则 Bean 注入失败。**
- rag 依赖 agent（已 import `agent.tool`/`agent.approve`），`AggregatedToolCallbackProvider` 注入 `ToolApprovalService` 无循环依赖（agent 不依赖 rag 的 config）。✅

**3. 租户/线程安全（关键风险）：**
- **方案 A 等待发生在 agent 异步子线程**：`RagAgentService.callAgentWithTimeout` 已在该 supplyAsync 子线程手动 `TenantContext.setSchema/TenantId/UserId/SessionId`，`AggregatedToolCallbackProvider.call()` 由 ReactAgent 在该线程同步触发，故审批落库/轮询/approve 后 execute 都能命中正确租户 schema。✅ 审批服务不得自建上下文。
- **Converger 无租户上下文**（scheduler 线程 schema=public）：**必须同时 setSchema + setTenantId**——`TenantSchemaInterceptor`（L90）要求 schema 与 tenantId 双非空才走租户分支；`TenantLineHandler`（TenantMyBatisPlusConfig L33）取 tenantId。只 setSchema 会落 public + tenant_id=null 致收敛失效。已按 Task 6 Step 2 遍历 `tenant_%` + 经 `public.sys_tenant` 反查 tenantId 的稳妥版实现。**必须按 Step 2 实现。**
- `TenantLineInnerInterceptor` 自动追加 `tenant_id=?`；`TenantSchemaInterceptor` 设 search_path + app.tenant_id。审批请求表不 ignore，天然隔离。✅

**4. 安全：**
- 审批门叠加不 bypass 硬校验：approve 后仍走 `ExecuteTool.execute()` 的命令白名单 / `DatabaseQueryTool` 的 SqlSecurityValidator。✅
- RLS + `tenant_id=current_tenant_id()` + FORCE RLS：审批人只能看本租户 PENDING。✅
- 状态机幂等：仅 PENDING 可决策，重复 approve/deny 返回 false。✅
- args_json 含命令原文落库：功能需要，无密钥（白名单边界可接受），预留未来脱敏。✅

**5. 占位符/类型一致性：**
- Table DDL 占位符 12→18（table）/14→18（index），需在 Task 9 精确更新 formatted 实参，避免运行时 `MissingFormatArgumentException`（字符串无编译检查）。**已有现成防线**：`TenantServiceImplSchemaTest`（`company-rag-tenant/.../impl/TenantServiceImplSchemaTest.java` L52 注释即「新增表后未同步占位符会抛 MissingFormatArgumentException」）。Task 9 追加增量断言：`buildCreateTableSql(schemaName)` 返回不含裸 `%s`、含 `tool_approval_request` 与 `tenant_isolation_tool_approval`；`buildCreateIndexSql` 同理。已实测当前 12 个（table）/14 个（index）占位符与实参严格对齐。
- 实体字段（tenantId/toolName/argsJson/sessionId/requesterUserId/status/result/requestedAt/decidedAt）与 DDL 列逐一对齐；`await` 裁决类型一致性。
- `ObjectMapper` 实例与既有 provider 的独立，无冲突。

**6. 向后兼容：**
- 默认 `enabled: false`：配置关闭时 `needsApproval` 恒 false，主链路零变化。现有 5 工具仅 ExecuteTool 改返回 true，其余默认 false + 接口加默认方法不破坏签名。
- `AggregatedToolCallbackProvider` 构造新增必填参数——`AgentToolConfig.toolCallbackProvider(...)` 目前只传 `AggregatedToolCallbackProvider aggregatedProvider`（Spring 自动装配），构造由容器实例化 `AggregatedToolCallbackProvider`，加参由容器注入，无需改 config；但需确认无手动 new。✅（L34-39 为容器构造注入，加参安全。）