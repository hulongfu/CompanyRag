# 回答质量评估（AnswerEvaluator）独立化设计

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案A**——新增独立 `AnswerEvaluator`，封装 Spring AI `Evaluator` SPI，返回 `AnswerEvalResult(query, context, answer, pass, score)`，覆盖 answer-relevancy / correctness / faithfulness 三个维度。不与既有检索端评估耦合。

## 1. 目标

为 Agent 回答引入**可复用的回答质量评估能力**，作为生产链路之外的评估工具与反馈信号来源，同时不改变 `chat` 主链路行为。

**约束：**
- 不动 `ChatController` 主链路（评估是独立能力，默认不进在线回复路径）。
- 复用 Spring AI 官方 `Evaluator` SPI（继承而非自造），保证与官方生态一致。
- 返回统一结构 `AnswerEvalResult`，与既有 `R<T>` 体系统一对外。
- 第一版内置 answer-relevancy / correctness / faithfulness 三项，作为独立可调用的评估服务 + 可选离线评估脚本。
- 不引入额外存储；评估结果选择落库（评估表）或纯返回由 plan 阶段定。

## 2. 现状回顾

- 项目现有 `company-rag-rag/.../eval/`：`EvalCase` / `RetrievalEvalResult` / `RetrievalEvalRunner`，是**检索端**基于规则的召回质量评估（doc 是否被正确命中）。
- 缺**回答端**评估：对 `RagAgentService` 产出的自然语言回答，判断其与问题/上下文的契合度（相关性、正确性、忠实度/幻觉），目前无任何机制。
- 采用 Spring AI 官方 `Evaluator` SPI 自行实现（本项目仓库当前并未落地该 SPI 的实例，`answer-evaluator` 即首次引入其回答端实现）。

## 3. 架构设计

### 3.1 核心组件

| 组件 | 职责 | 依赖 |
|---|---|---|
| `AnswerEvaluator`（接口） | 评估一次回答，返回 `AnswerEvalResult` | Spring AI `Evaluator` |
| `AnswerEvalResult` | 数据结构：query / context / answer / pass / score / 维度明细 | —（纯数据） |
| `AnswerRelevancyEvaluator` | 回答相对问题的相关性评分 | Spring AI `RelevancyEvaluator` 风格 |
| `AnswerCorrectnessEvaluator` | 回答相对参考答案的正确性评分 | Spring AI `CorrectnessEvaluator` 风格 |
| `AnswerFaithfulnessEvaluator` | 回答相对上下文是否忠实（防幻觉） | Spring AI `FaithfulnessEvaluator` 风格 |
| `AnswerEvaluationService` | 聚合调用多维度，产出综合 `AnswerEvalResult`；可批量评估 | 各 Evaluator |

### 3.2 与既有评估的关系

```
检索端（既有）                   回答端（本 spec）
RetrievalEvalRunner      ──►     AnswerEvaluator
  规则/命中率                       LLM 判别
  召回质量                          回答质量（相关/正确/忠实）
```

两者**正交互补**：检索端保证"召回对了"，回答端保证"答对了"。不合并、不互相依赖。

### 3.3 与 reflection 的 faithfulness 分工（评审 🟡）

reflection（在线）与 answer-evaluator（离线）的 faithfulness 维度**功能重叠**，须明确分工并**复用同一份判定实现**，不各写一套：

| 维度 | reflection（在线轻量自校） | answer-evaluator（本 spec：离线金标准） |
|---|---|---|
| 时机 | 在线每次回答后 | 离线/批量 |
| 成本 | 低、短超时 + 熔断、失败回退 | 较高、可重可报告 |
| 用途 | 即时修正被返回的回答 | 质检报告、反馈信号 |
| faithfulness 判定 | 只要"有无明显幻觉"的轻量/二元分支 | 可重粒度评分 |

> **共享实现**：两侧抽取共用 `FaithfulnessChecker`（faithfulness 判定 prompt/工具）。reflection 调其轻量分支；本 spec 的 `AnswerFaithfulnessEvaluator` 复用同一份判定逻辑做可重评分。避免两份 faithfulness 各写一套。

## 4. 数据流

1. 提供 `AnswerEvaluationService.evaluate(query, context, answer)` 入口（可批量接受 `List<AnswerCase>`）。
2. 服务按维度依次调用各 `Evaluator`，各自返回 `pass/score`。
3. 汇总为 `AnswerEvalResult`：综合 pass（各维度按规则合成）与 score（加权或均值）。
4. 返回给调用方；可选将结果写入评估表（供离线质检与反馈分析），是否落库留待 plan 确认。

## 5. 使用场景（不改变主链路）

- **离线质检脚本**：对一批历史 Q/A 批量评估，输出质量报告（对应 Spring AI `Evaluator` SPI 的标准用法）。
- **反馈信号源**：`chat/feedback` 的 👍/👎 之外，增加自动评估维度（可选，后续迭代）。
- **接入点预留**：设计为独立 Bean，后续若需在线触发可在 `RagAgentService` 或 Controller 装配，但**默认不接入在线路径**。

## 6. 测试策略

- **单测**：mock 各 `Evaluator`，验证 `AnswerEvalResult` 聚合逻辑（pass 合成、score 加权、空输入）。
- **服务测试**：`AnswerEvaluationService` 批量评估流程。
- **接入验证**：离线脚本对样例 Q/A 输出可读报告；`chat` 主链路回归不受影响（未接入）。
- 验证命令采用最窄范围：`company-rag-rag` 模块相关测试类。

## 7. 改动清单

- **新增**：`company-rag-rag/.../eval/answer/` 下 `AnswerEvaluator`(接口) / `AnswerEvalResult` / `AnswerRelevancyEvaluator` / `AnswerCorrectnessEvaluator` / `AnswerFaithfulnessEvaluator` / `AnswerEvaluationService`。
- **新增（共享）**：`FaithfulnessChecker`（faithfulness 判定实现，reflection 与本 spec 复用）。
- **可选**：评估结果表 `answer_eval_result`（列：tenant_id / session_row_id / query / context / answer / pass / score / 维度明细 / create_time）。
- **不动**：`rag/eval` 既有检索端评估、`ChatController` 主链路、数据库既有表。

## 8. 风险与观察项

- **LLM 评估成本**：每维度一次 LLM 调用，批量评估耗时/成本需控制；建议评估并发池与生产隔离，在线默认关闭。
- **评分尺度一致性**：LLM 判别式评分的绝对值稳定性需抽样校准；先做可观测，不立即接入自动反馈决策。
- **上下文敏感**：faithfulness 依赖检索 `context`，需保证评估时传入的 context 与回答生成时的检索上下文一致，否则失真。
- **不接主链路是本 spec 既定边界**：避免评估相关二次 LLM 调用拉高在线延迟与成本。在线路径的轻量自校由 reflection spec 承担，且与本 spec **共享 `FaithfulnessChecker`**，不各自重复实现。
- **context 一致性**：faithfulness 依赖检索 `context`，评估须用与回答生成一致的 `toolContext`（依赖阶段 0 的 `AgentResult.toolContext` 真实透传，见 reflection spec）。