# AI 工作流技术选型备忘

> 日期：2026-09-13
> 类型：决策备忘（非规格文档）
> 结论：**继续使用 Spring AI Alibaba Graph，不引入 LangGraph4j。**

## 背景

企业知识库 RAG 系统计划扩展「AI 工作流（Graph 编排）」能力。技术候选：Spring AI Alibaba Graph vs LangGraph4j。

评估约束：
- 尚未确定具体业务场景（评估阶段）
- 不要求持久化 / 断点恢复（单次请求内运行完即可）
- 倾向：1) 与现有技术栈一致；3) 性能与轻量优先

> 评估范围说明：本次仅对约束中的第 1、3 项进行评估（按当时澄清要求聚焦这两项），
> 其余候选维度未纳入本次决策，不构成遗漏。

## 现状核实

- 项目已引入 Spring AI 1.1.3 + Spring AI Alibaba 1.1.2.0。
- 当前 Agent 已在用 `com.alibaba.cloud.ai.graph` 包下的 `ReactAgent`，
  其来源于 `spring-ai-alibaba-graph-core` 1.1.2.0。
- Graph Core 是基于 `spring-ai-rag` / `spring-ai-client-chat` 自研的有状态多 Agent 图编排
  （不依赖外部 LangGraph4j 工件；redisson / mongodb 等为 optional 依赖）。
- 已有自封装层：`RagAgentService` + `StreamingAgentExecutor`，集成了 Skills + Tools + MCP 工具
  （数据库查询 / 代码检索 / API 文档）。

## 决策理由

### 第 1 项「与现有技术栈一致」
- 已在用 Alibaba Graph，扩展是顺延现有架构，非引入新框架重写。
- 已具备封装层，未来扩展 `StateGraph` / `StatefulAgent` 成本低。
- 与整条 RAG 链路同源（Spring AI 生态），租户隔离 / 熔断等既有组件无需适配。

### 第 3 项「性能与轻量优先」
- 已确认不需要持久化 / 断点恢复，LangGraph4j 差异化能力（Checkpointer、断点续跑、人工介入）不适用。
- 依赖面可控：已核实 `spring-ai-alibaba-graph-core` 1.1.2.0 的 pom 中，
  redisson / mongodb / postgresql / mysql / ojdbc 均为 **optional** 依赖，且**不引入任何外部 LangGraph4j 工件**
  ——它是基于 Spring AI 生态自研的图编排，比再叠一套框架更轻。
- 单次请求内运行，现有引擎已能覆盖多步任务 / 分支条件 / 多 Agent 协作三类场景。

## 观察项（未来重估触发条件）

若业务演进到以下需求，再重新评估 LangGraph4j（或 Python LangGraph）：
- 长任务中断后从检查点恢复
- 人工在流程中间介入审核
- 跨请求保持工作流状态

现阶段不为此类非存在场景提前引入依赖。

## 已知局限（选型代价，如实记录）

- 当前 `ReactAgent` 尚未启用**流式响应**能力（本项目 `StreamingAgentExecutor` 的类注释已标注此局限）。
  若未来对图节点的 AI 产出有流式输出诉求，需先评估 Alibaba Graph
  对节点级流式能力的支持，再决定是否引入替代方案。

## 后续动作

确定具体业务场景后，再做完整方案设计并规划实现。
