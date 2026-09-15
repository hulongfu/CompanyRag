# 七份设计方案 · 最终复核报告（2026-09-14 批次）

> 评审对象：`D:/tmp/CompanyRag/docs/superpowers/specs/` 下 7 份 `2026-09-14-*` 方案 + orchestration 总览
> 评审方法：全部结论基于**实读 CompanyRag 源码**（行号证据），非记忆推测
> 本轮为闭环复核：用户已确认删除 `design-review.md`（🔴1 撤诉），并已自修 `memory-rework` 的双写误诊断（🔴3 撤诉）
> 修订闭环：§3 所列 8 处 🟡 一致性/措辞/预算项，**已全部按建议落实**（见 §6 修订落地核对），文档已自洽

---

## 0. 前置闭环确认

| 项 | 状态 |
|---|---|
| 🔴1 缺 `design-review.md` 源文件 | **撤诉**：用户已主动删除，不再作为问题（仅 orchestration 仍残留 2 处引用，见 §3-①，属文档卫生） |
| 🔴3 主链路双写 | **撤诉**：实读核实 `RagSearchServiceImpl.search:112` 受 `sessionId!=null` 守卫，`KnowledgeBaseTool:80-83` 不设 sessionId → 主链路单写。用户已自修 `memory-rework` §3.3（重写为"落库责任收敛+消除隐式落库"），§7 L133 决定移除 L112-122 内嵌 `saveConversation` |
| 🟡5 ChatRouter 越权 | **降级闭环**：`memory-rework` §5 L114 已确认 `ChatRouter` `@Deprecated`、不在 `/api/chat` 主链路，仅测试/向后兼容 |

**结论：7 份方案当前无 🔴 阻塞项。** 剩余均为 🟡 一致性/措辞/设计增强，可边实施边修。

---

## 1. 总评

- **整体质量高**：问题定位准（租户隔离、异步隔离、工具内无 LLM、捕获/降级折中、FaithfulnessChecker 共享、线程池隔离均判断正确）。
- **编排合理**：阶段 0（捕获/降级前置）→ 阶段 1（离线+ETL 并行）→ 阶段 2（同文件合并）→ 阶段 3（主链路收口串行）的依赖与冲突矩阵清晰，避免了"各自声称封闭却同改一文件"的典型坑。
- **实读验证通过的关键事实**：`StreamingAgentExecutor:51` 硬编码 null、`AgentResult` 仅 answer/toolContext、`DatabaseQueryTool` 无 ChatModel、`TenantContextHelper` 全废弃、`TenantSchemaInterceptor` 每条 SQL 前自动 SET search_path、`TenantContextSnapshot` 仅 `captureNow/apply/clear`、`DocumentParseServiceImpl` 单 `@Transactional` 同步流水线——均与 spec 描述一致。

---

## 2. 逐方案评估

### 2.1 orchestration（编排总览）
- **结论**：✅ 指挥文档职责到位，冲突矩阵与排序正确。
- 残留 🟡：见 §3-①（dangling 引用 design-review.md）、§3-②（L62「防双写」措辞未随 memory 重写同步）。

### 2.2 answer-evaluator（回答质量评估独立化）
- **结论**：✅ 纯离线、不碰主链路，风险最低，可最先落地。
- 🟡①：§8 L94-95「faithfulness 依赖与生成时一致的 `toolContext`」——若阶段 0 走 B（不捕获），离线评估仍可用，但**需保证评估入参的 context 在生成时已被持久化/可取得**，否则 faithfulness 维度无据可依。建议在 plan 明确 context 供给方式（落库或调用时传入）。
- 🟡②：§2 L23「仓库当前未落地 Evaluator SPI 实例」——实施前确认 Spring AI 版本确实提供 `Evaluator` SPI（避免版本不符返工）。属 plan 阶段核对项。

### 2.3 rag-etl-hardening（ETL 健壮性）
- **结论**：✅ 🔴5（异步租户隔离）已正确修复，伪代码用 `apply()/clear()` 与真实 API 一致。
- 🟡①（设计增强，非阻塞）：拦截器对「未设 TenantContext」fallback 到 `public`（spec 称「fail-open 于应用视角」）。对**漏恢复**的 worker，这会静默把数据写到 public（空 schema）而非报错，**可能掩盖异步隔离回归**。建议：worker 落库前增加显式 fail-closed 断言（TenantContext 未恢复则抛错），不依赖 public 兜底。
- 🟡②：§3.4 L76 `task.getTenantSnapshot()` 需在提交线程（请求线程）真实捕获五字段，§7 L131 已承诺「上传时捕获随任务传递」——实施时确认 `DocumentParseServiceImpl` 上传入口确实在 `TenantContext` 已注入后捕获，否则 worker `apply()` 恢复为空。属实现核对项。

### 2.4 nl2sql-tool-hardening（NL2SQL 小升级）
- **结论**：✅ 🔴4（无 ChatModel→ReAct 重试）、🟡3（ToolResult 内部载体、String 签名不变）均已正确修复。
- 🟡①：§3.1 L39 新增 `SqlSchemaValidator` 与既有 `SqlSecurityValidator` 命名易混，建议在其上补一行注释明确「新=表/列存在性校验，既有=语法+白名单」，降低实现误用。
- 🟡②：自纠完全依赖 ReAct 读取错误文本重试（§6 L85），需在 plan 确认 Agent 最大迭代上限足够让「缺失清单→二次生成」收敛，否则可能反复失败。

### 2.5 human-in-the-loop（提示式人工介入）
- **结论**：✅ 🟡2（warning 独立载体、不复用 toolContext）已正确修复，与 nl2sql 阶段 2 合并清晰。
- 🟡①（措辞矛盾）：§1 L15「不改变 `AgentResult` 结构」与 §7 L76「`AgentResult` 仅在需要时新增可选 `warnings` 字段」张力——新增字段**就是**结构扩展。建议 §1 改为「结构**可控扩展**（仅新增可选 `warnings`，不破坏既有 answer/toolContext 字段）」，与 orchestration L7 一致。
- 🟡②：方案②安全强度有限（仅提示不拦截），spec 已诚实标注，留待方案①异步审批模型后续演进——合理，无阻塞。

### 2.6 memory-rework（会话记忆规范化）— 已由用户自修
- **结论**：✅ 双写误诊断已重写为正确的「落库责任收敛」，并进一步决定**移除 `RagSearchServiceImpl.search` 隐式 `saveConversation`**（§7 L133），方向优于单纯解耦。
- 🟡①（自身措辞不一致）：§1 L16 仍写「唯一落库 Owner：历史落库在 ChatController 与 Advisor 间**二选一，防双写**」，与已重写的 §3.3「主链路本无双写」矛盾。建议 §1 改为「唯一落库 Owner（防御性约束）：`RagChatMemory` 只读不写，落库收敛到 `ChatController`，并消除 `search` 隐式落库」。
- 🟡②（架构措辞矛盾）：标题/§3.1 称「MessageChatMemoryAdvisor 设计」，但 §3.4 主路径用**手动注入**、`Advisor` 仅可选增强。若实际机制是 `ChatMemoryRepository`+手动 `get`，建议标题改为「ChatMemoryRepository + 手动注入」或明确「Advisor 仅作为 ChatClient 可选装配、不参与主路径」，避免实施时歧义。
- 🟡③：§5 L117「`AgentResult`/`ChatResponse` 结构不动」与 human 新增 `warnings` 字段冲突——同上，改为「仅新增可选字段」。

### 2.7 reflection（答后自省）
- **结论**：✅ 🔴1（toolContext 前提）已正确识别并给出阶段 0「捕获(A)/降级(B)」二选一，与 orchestration 一致。
- 🟡①（可行性预算）：方案 A 依赖 ReactAgent 观测/recorder 能产出 payload，但实读确认当前 `ToolCallRecorder` 仅记 name/duration/status/error、**无 payload**。方案 A 需先扩展 `ToolCallRecorder`。建议在 plan 显式把「扩展 ToolCallRecorder 捕获 payload」列为阶段 0 前置任务并预算；若不可行直接走 B。
- 🟡②：§5 与 answer-evaluator 共用 `FaithfulnessChecker` 的落点已明确，无重复实现风险——✅。

---

## 3. 跨方案一致性问题汇总（🟡，建议边实施边修）

| # | 位置 | 问题 | 建议 | 状态 |
|---|---|---|---|---|
| ① | orchestration L6 / L80 | 仍引用已删 `design-review.md`（「采用评审 design-review.md 作为修订依据源」） | 改为「各 spec 内联修订记录」或删除该句 | ✅ 已修复（改为"内联修订记录 + 不再引用"） |
| ② | orchestration L62 | 「memory 防双写」措辞未随 memory §3.3 重写同步 | 改为「memory 收敛落库边界（消除 search 隐式落库）」 | ✅ 已修复（追加 fail-closed 断言与 recorder 预算） |
| ③ | memory §1 L16 | 「防双写」与自身 §3.3「主链路本无双写」矛盾 | 改为防御性约束措辞（见 §2.6-🟡①） | ✅ 已修复（改为「唯一落库 Owner（防御性约束）」） |
| ④ | human §1 L15 vs §7 L76 | 「结构不动」vs「新增可选 warnings」张力 | §1 改为「可控扩展（仅新增可选字段）」 | ✅ 已修复（§1 L15 改为「结构可控扩展」） |
| ⑤ | memory §5 L117 vs human | 「AgentResult 结构不动」与 human 新增 warnings 冲突 | 同上，统一为「仅新增可选字段」 | ✅ 已修复（§5 L117 改为「仅做可控扩展」） |
| ⑥ | 阶段 0（reflection/orchestration P0） | A/B 是硬前置且 gate 三个下游 spec；A 依赖 ToolCallRecorder payload 能力（当前不具备） | plan 阶段显式决策 A/B，若 A 则预算 ToolCallRecorder 扩展 | ✅ 已修复（orchestration §2/§4/§5 + reflection §8 均补 recorder 扩展预算与回退 B） |
| ⑦ | rag-etl §3.4 | 拦截器 fallback 到 public 可能掩盖 worker 漏恢复 | worker 落库前加 fail-closed 断言 | ✅ 已修复（伪代码含 `assertTenantContextRestored`，说明补充"不依赖 public 兜底"） |
| ⑧ | memory §3.4 | Advisor 标题 vs 手动注入主路径措辞矛盾 | 统一架构表述，避免实施歧义 | ✅ 已修复（标题改为「ChatMemoryRepository + 手动注入；MessageChatMemoryAdvisor 可选」） |

---

## 4. 实施排序确认（与 orchestration 一致，无冲突）

```
阶段 0：捕获(A)/降级(B) 二选一（gate reflection/answer-eval 在线 faithfulness/human 透传）
阶段 1：answer-evaluator（离线，最先） + rag-etl（异步隔离，并行）
阶段 2：DatabaseQueryTool 合并（nl2sql + human，同文件同 ToolResult）
阶段 3：memory-rework（主链路收口） + reflection（末尾委派自省，串行）
```
- 阶段 3 memory 移除 `search` 隐式落库（§7 L133）是**消除隐藏双写路径**的关键动作，建议在 memory 实施时一并完成，不要留到反思阶段。

---

## 5. 最终判定

- **可进入 plan/实施**：7 份方案方向上均无硬伤，阶段划分与冲突处理成熟。
- **实施前需拍板**：阶段 0 的 A/B 选择（决定 ToolCallRecorder 是否要扩 payload）。
- **实施中顺手修**：§3 列出的 8 处 🟡 一致性/措辞项（均为文档自洽，不影响代码正确性）。
- **设计增强（可选）**：rag-etl worker 侧 fail-closed 断言（§3-⑦）。

---

## 6. 修订落地核对（本轮闭环）

| # | 文件 → 位置 | 落地内容 | 状态 |
|---|---|---|---|
| ① | `orchestration.md` L84 | references 改为「各 spec 内联修订记录」，声明 `design-review.md` 已删除不再引用 | ✅ |
| ② | `orchestration.md` §2 阶段 0、§4 P0、§5 铁律 | 「memory 防双写」措辞对齐 §3.3；并补 `ToolCallRecorder` 扩展预算项 | ✅ |
| ③ | `memory-rework-design.md` §1 L16 | 改为「唯一落库 Owner（防御性约束）」并引用 §3.3 根因 | ✅ |
| ④ | `human-in-the-loop-design.md` §1 L15 | 改为「结构**可控扩展**（仅新增可选 `warnings`）」 | ✅ |
| ⑤ | `memory-rework-design.md` §5 L117 | `AgentResult`/`ChatResponse` 改为「结构仅做可控扩展」 | ✅ |
| ⑥ | `orchestration.md` + `reflection-design.md` §8 | 显式把「扩展 ToolCallRecorder 捕获 payload」列为阶段 0-A 前置任务并预算；不可行回退 B | ✅ |
| ⑦ | `rag-etl-hardening-design.md` §3.4 | worker 落库前加 `assertTenantContextRestored` fail-closed 断言，不依赖 public 兜底 | ✅ |
| ⑧ | `memory-rework-design.md` 标题 + §3.1/§3.4 | 架构表述统一为「ChatMemoryRepository + 手动注入（Advisor 可选）」 | ✅ |

**结论：§3 全部 8 处 🟡 已按建议落实到对应 spec/编排文档，文档已自洽，可进入 plan/实施。**

