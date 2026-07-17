# 验证协议 — CompanyRag L1 Harness

## 验证方法

### 静态检查
- 所有 harness 文件存在且格式正确（Markdown）
- 文件路径与命名符合规范
- 引用关系完整（如 AGENTS.md 引用 harness 文件路径正确）

### 内容完整性
- boundaries.md 覆盖所有模块边界
- conventions.md 覆盖编码/日志/异常/安全规范
- api-contracts.md 覆盖所有 REST API
- iron-rules.md 覆盖不可违反的硬性规则

### 一致性检查
- 设计哲学与 conventions 无矛盾
- 包结构设计与实际代码一致
- API 契约与实际 Controller 定义一致

## 验收标准

| 检查项 | 标准 |
|--------|------|
| 设计知识库文档 | 4 份文档完整，内容与项目实际一致 |
| boundaries.md | 覆盖 7 个模块边界 + 外部依赖边界 |
| conventions.md | 覆盖编码/日志/异常/安全/测试 |
| api-contracts.md | 覆盖所有公开 REST API |
| iron-rules.md | ≥5 条不可违反规则 |
| AGENTS.md | 包含项目描述/技术栈/规则/路径引用 |
| sop 目录 | 存在且包含 README.md |