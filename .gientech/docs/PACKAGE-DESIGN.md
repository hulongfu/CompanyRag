# 包结构设计 — CompanyRag

## 多模块结构

```
company-rag-common        # 公共模块：常量、异常、工具类、统一响应
company-rag-tenant        # 多租户：上下文、Schema 拦截器、权限控制
company-rag-document      # 文档解析：Tika 集成、三种切分策略
company-rag-rag           # RAG 核心：混合检索、Rerank、缓存、Prompt、指标
company-rag-agent         # Agent：MCP 工具编排、数据库查询/代码检索/API 文档
company-rag-web           # Web 层：REST Controller、前端页面路由
company-rag-bootstrap     # 启动模块：全局配置、入口类
```

## 各模块包结构

### company-rag-common
```
com.company.rag.common
├── constant/       # 常量类（RagConstant 等）
├── exception/      # 异常定义（BizException、GlobalExceptionHandler）
├── model/          # 统一响应模型（R<T>）
└── config/         # 公共配置
```

### company-rag-tenant
```
com.company.rag.tenant
├── mapper/         # 数据访问层
├── model/          # 数据模型
└── service/        # 服务层
```

### company-rag-document
```
com.company.rag.document
├── mapper/         # 数据访问层
└── splitter/       # 切分策略（语义/滑动窗口/固定大小）
```

### company-rag-rag
```
com.company.rag.rag
├── mapper/         # 数据访问层
├── service/        # 检索服务（RagSearchService）
├── rerank/         # 重排序（Cross-Encoder）
├── cache/          # 两级缓存管理
├── prompt/         # Prompt 模板管理
└── observability/  # 可观测性指标
```

### company-rag-agent
```
com.company.rag.agent
├── tool/           # MCP 工具（DatabaseQueryTool、CodeSearchTool、ApiDocTool）
└── service/        # 编排服务
```

### company-rag-web
```
com.company.rag.web
└── controller/     # REST API 控制器
```

## 分层依赖规则

1. `common` 无项目内部依赖
2. `tenant` 依赖 `common`
3. `document` 依赖 `common`
4. `rag` 依赖 `document` + `tenant` + `common`
5. `agent` 依赖 `rag` + `common`
6. `web` 依赖 `rag` + `agent` + `document` + `common`
7. `bootstrap` 依赖所有模块