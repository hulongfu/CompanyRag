# CompanyRag 当前已知待完善项盘点清单

> 说明：本文档为审计/盘点产物，作为后续优化工作的依据，不代表已批准实施。
> 盘点日期：2026-09-09。基于当前 main（30857e5）代码、文档与 git 状态逐项核实。

---

## 一、总体结论

项目已具备高完成度工程水准：多模块架构清晰、核心 RAG 链路（解析→切分→向量化→混合检索→Rerank→缓存→流式→审计→监控→追踪）完整、交付纪律严格（deploy-log 每提交留证）。**主代码（company-rag-*/src/main）已无遗留 TODO/FIXME 空实现**——历史上标注为"TODO 空实现"的审计落库（`TenantServiceImpl.recordAuditLog`）当前已委托 `AuditLogServiceImpl` 真实落库，已闭环。

但"无 TODO"不等于"无待完善"。以下按四类列出剩余空间。

---

## 二、待完善项明细

### A. 测试盲区（最需优先级）

| # | 项 | 现状证据 | 风险 |
|---|----|---------|------|
| A1 | PG 真库集成测试被跳过 | `MultiRetrieveIntegrationTest`、`AuditLogTenantIsolationIT`、`RlsIsolationTest` 均带 `@EnabledIfSystemProperty(it.pg=true)` 守卫；无 PG 的 CI 中整类 Skipped | RAG 向量检索、租户 RLS、审计隔离这些**核心正确性**在常规 CI 无覆盖 |
| A2 | 无测试覆盖率度量 | 路线图 L2 项"单元测试覆盖 ≥80%""JaCoCo"均未勾选；当前 64 测试但无覆盖率门禁 | 回归风险不可量化 |
| A3 | 无 RAG 全链路集成测试 | 路线图 L2"集成测试覆盖 RAG 全链路"未勾选 | 端到端回归无自动保障 |

### B. 已声明的规划/路线图未落地

| # | 项 | 现状证据 | 来源 |
|---|----|---------|------|
| B1 | L1 阶段多项"本次完成"仍为未勾选态 | L1 下 4 项（设计知识库文档化 / Harness 产出物 / AGENTS.md / SOP）虽已实际产出，但勾选框仍为 `[ ]` | `PRACTICE-ROADMAP.md` L12-16 |
| B2 | 代码质量工具未集成 | L2"Checkstyle / PMD / JaCoCo"未勾选，pom 未见相关插件配置 | `PRACTICE-ROADMAP.md` L21 |
| B3 | CI/CD 管道未搭建 | L2"CI/CD 管道搭建"未勾选 | `PRACTICE-ROADMAP.md` L22 |
| B4 | API 文档自动生成待落地 | L2"SpringDoc OpenAPI 自动生成"未勾选（注意：生产侧已反向下调 `permit-swagger`，需权衡开放策略） | `PRACTICE-ROADMAP.md` L23 |
| B5 | 远期项未排期 | L3 性能压测、安全渗透、高可用、多区域容灾均未勾选 | `PRACTICE-ROADMAP.md` L25-29 |

### C. 仓库卫生与安全残留

| # | 项 | 现状证据 | 建议 |
|---|----|---------|------|
| C1 | 未提交改动未归位 | `git status` 显示 ` M deploy-log.md`（已改未提交） | 及时提交或明确丢弃 |
| C2 | 未跟踪产物未忽略 | `?? agent_skills/file-manager/scripts/__pycache__/` 未被 `.gitignore` 覆盖 | `.gitignore` 补充 Python `__pycache__/`、`*.pyc` |
| C3 | 根目录一次性脚本/报告混乱 | `cleanup-env-*.sh`、`secret-replacements.txt`、`修复完成报告.md`、`最终修复说明.md`、`SESSION_HISTORY_FIX.md`、`GenPass.class`/`GenPass.java` 散落根目录 | 历史敏感信息清理遗留物，宜归档到独立 `docs/_archive/` 或 `tools/` 并规整 |
| C4 | `.gitignore` 覆盖不全面 | 已覆盖 `.env*`、`k8s/secret.generated.yaml`，但缺 Python 产物、部分临时报告后缀 | 补充 `*.pyc`/`__pycache__/` 及临时文件规则 |
| C5 | 敏感信息历史记录风险持续 | 根目录多个 `*-replacements.txt`、`cleanup-env` 脚本，说明发生过 API Key 误提交后清理事件 | 建议用 `git filter-repo` 彻底清除历史敏感提交，而不只依赖后续 ignore（需用户批准后执行） |

### D. 其他观察（非阻塞）

| # | 观察 | 说明 |
|---|------|------|
| D1 | 模块可观测性文档与实现存在序号漂移 | deploy-log 中提交序与文件简述偶有"待推送/已推送"重复记录，易造成追溯歧义 |
| D2 | `sql/init.sql` 与运行时建表逻辑需保持同步 | 历史上出现过"部署漂移"（Flyway 被 exclude），虽已用 `SchemaMigrationConfig` 兜底，后续新增列仍需三处同步（init.sql / TenantServiceImpl / 存档 V4 SQL） |

---

## 三、建议优先级排序（供后续优化排期）

1. **P0（正确性/安全）**：补齐 A1 真库测试的 CI 触发方案（如 CI Job 起 PG 容器并传 `-Dit.pg=true`）；评估 C5 历史敏感信息彻底清除。
2. **P1（工程质量）**：落地 B2 代码质量工具 + JaCoCo（可顺带满足 A2 覆盖率）；补齐 B1 路线图勾选态使其反映真实进度。
3. **P2（仓库卫生）**：处理 C1、C2、C3、C4 的归档与 `.gitignore` 规整。
4. **P3（后续规划）**：B3 CI/CD、B4 文档生成、B5 远期项按业务需要排期。

---

## 四、附：本盘点涉及的证据位置

- 审计落库已闭环：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java:409-411`
- PG 跳过的测试：`company-rag-rag/src/test/java/.../MultiRetrieveIntegrationTest.java`、`company-rag-tenant/src/test/java/.../AuditLogTenantIsolationIT.java`、`RlsIsolationTest.java`
- 路线图未落地项：`.gientech/docs/PRACTICE-ROADMAP.md`
- 历史审计 TODO 记录：`docs/superpowers/specs/2026-09-05-audit-log-persistence-design.md`
- 仓库卫生：`git status` / `.gitignore`
