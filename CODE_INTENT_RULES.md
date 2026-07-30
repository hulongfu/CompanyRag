# CODE 意图识别规则说明

## 工具分类

CODE 意图会触发 Agent 调用，系统目前有两个代码相关工具：

### 1. ApiDocTool（API 文档生成工具）
**功能**：动态扫描 Spring MVC 端点，生成当前系统的 API 接口文档

**触发场景**：
- "生成 API 文档"
- "扫描所有接口"
- "获取当前的 REST API 列表"
- "API 文档自动生成"

**规则模式**：
- `.*生成.*API.*`
- `.*API 文档.*`
- `.*扫描.*端点.*`
- `.*获取.*接口.*`

### 2. CodeSearchTool（代码检索工具）
**功能**：在源码目录中搜索包含特定关键词的代码片段

**触发场景**：
- "在哪里调用了 UserService？"
- "搜索包含@Transactional 的代码"
- "查找所有使用 Redis 的地方"
- "有没有处理 JWT 的代码示例"
- "帮我找一下数据库连接的代码"
- "搜索用户登录相关的代码"

**规则模式**：
- `.*搜索.*代码.*`
- `.*查找.*代码.*`
- `.*哪里.*调用.*`
- `.*包含.*的代码.*`
- `.*使用.*的地方.*`
- `.*代码示例.*`
- `.*代码片段.*`

### 3. 通用代码查询
**场景**：询问代码实现方式、示例代码等

**触发场景**：
- "这个功能怎么实现"
- "java 示例代码"
- "python 脚本怎么写"
- "这个函数怎么写"

**规则模式**：
- `.*怎么实现.*`
- `.*java.*`
- `.*python.*`
- `.*函数.*怎么写.*`

## 意图识别流程

```
用户查询
  ↓
规则匹配（PatternRule）
  ↓
匹配成功 → 返回对应意图（CODE/DATABASE/CHAT）
  ↓
匹配失败 → LLM 识别（带详细 prompt）
  ↓
LLM 失败 → 默认 DOCUMENT 意图
```

## CODE 意图处理流程

```
CODE 意图
  ↓
ChatRouter.processAgent()
  ↓
RagAgentService.process()
  ↓
LLM 分析是否需要工具 → 选择工具（ApiDocTool / CodeSearchTool）
  ↓
执行工具 → 获取结果
  ↓
LLM 基于工具结果生成回答
  ↓
返回给用户
```

## 对比：容易混淆的场景

| 用户查询 | 意图 | 说明 |
|---------|------|------|
| "生成 API 文档" | CODE | 调用 ApiDocTool 扫描实际接口 |
| "API 文档相关的任务有哪些" | DOCUMENT | RAG 检索需求文档 |
| "在哪里调用了 UserService" | CODE | 调用 CodeSearchTool 搜索源码 |
| "UserService 的功能描述" | DOCUMENT | RAG 检索设计文档 |
| "查询用户表结构" | DATABASE | 调用 DatabaseQueryTool |
| "用户表有哪些字段" | DOCUMENT | RAG 检索数据库设计文档 |

## 测试用例

### 应该触发 CodeSearchTool
- ✅ "搜索包含@Transactional 的代码"
- ✅ "在哪里调用了 UserService"
- ✅ "查找所有使用 Redis 的地方"
- ✅ "有没有处理 JWT 的代码示例"

### 应该触发 ApiDocTool
- ✅ "生成 API 文档"
- ✅ "扫描所有接口"
- ✅ "获取当前的 REST API 列表"

### 应该走 RAG 检索
- ✅ "API 文档相关的任务有哪些"
- ✅ "需求文档中关于代码规范的描述"
- ✅ "这个功能的任务状态是什么"
