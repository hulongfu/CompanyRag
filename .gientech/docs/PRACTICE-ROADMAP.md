# 实践路线图 — CompanyRag L1 Harness

## L0 基线（已完成）
- [x] 项目初始化：Spring Boot 3.4.4 + Maven 多模块
- [x] 基础设施：PostgreSQL 16 + PGVector + Redis + Docker Compose
- [x] 核心链路：文档解析 → 切分 → 向量化 → 混合检索 → Rerank → 流式回答
- [x] 多租户：Schema 隔离 + 行级安全 + 三种角色
- [x] Agent：数据库查询 / 代码检索 / API 文档 MCP 工具
- [x] 可观测性：Prometheus + Grafana + Actuator
- [x] 工程保障：Resilience4j 熔断限流 + 两级缓存

## L1 当前阶段（本次完成）
- [ ] 设计知识库文档化（DESIGN-PHILOSOPHY / PACKAGE-DESIGN / PRACTICE-ROADMAP / VERIFICATION-PROTOCOL）
- [ ] Harness 产出物：boundaries / conventions / api-contracts / iron-rules
- [ ] AGENTS.md 项目智能体指令
- [ ] SOP 目录与模板

## L2 规划（后续）
- [ ] 单元测试覆盖核心业务逻辑（≥80%）
- [ ] 集成测试覆盖 RAG 全链路
- [ ] Checkstyle / PMD / JaCoCo 代码质量工具集成
- [ ] CI/CD 管道搭建
- [ ] API 文档自动生成（SpringDoc OpenAPI）

## L3 规划（远期）
- [ ] 性能压测与调优
- [ ] 安全审计与渗透测试
- [ ] 生产环境高可用部署
- [ ] 多区域部署与容灾