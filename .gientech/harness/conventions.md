# 编码规范 — CompanyRag

## Java 编码规范

### 语言级别
- Java 17，使用 var 仅限于局部变量且类型一目了然时
- 使用 Records 简化数据传输对象

### 代码风格
- 缩进：4 空格
- 大括号：K&R 风格（不换行）
- 行宽：不超过 120 字符
- 文件末尾：保留一个空行
- 导入顺序：Java 标准库 → 第三方库 → 项目内部包，组间空行分隔

### 命名规范
- 类名：PascalCase，名词
- 接口实现类：`Impl` 后缀
- 方法名：camelCase，动词或动词短语
- 布尔方法：`is`/`has`/`can` 前缀
- 常量：UPPER_SNAKE_CASE
- 变量：camelCase，具有描述性，避免单字母

### 类结构顺序
1. 常量
2. 静态变量
3. 实例变量
4. 构造函数
5. 静态方法
6. 实例方法
7. 私有方法

### 方法规范
- 单方法不超过 50 行
- 单方法参数不超过 5 个
- 每个方法只做一件事（单一职责）

## 异常处理规范

### 异常分类
- **业务异常**：`BizException`（继承 RuntimeException），包含 code + message
- **系统异常**：`RuntimeException`，由 `GlobalExceptionHandler` 统一兜底
- **参数校验**：`MethodArgumentNotValidException` 统一处理

### 日志级别
| 级别 | 场景 | 示例 |
|------|------|------|
| ERROR | 系统异常，需立即处理 | 数据库连接失败、运行时异常 |
| WARN | 业务异常或警告 | 参数校验失败、BizException |
| INFO | 重要流程信息 | 启动完成、Schema 初始化 |
| DEBUG | 调试细节 | 方法调用入参/出参 |

### 日志格式
- 使用 SLF4J + `@Slf4j` 注解
- 使用占位符 `{}`，禁止字符串拼接
- 包含业务上下文信息（如租户 ID、请求路径）
- 禁止记录密码、API Key 等敏感信息

## 安全规范

### 敏感信息
- API Key、数据库密码等通过环境变量注入
- 配置文件中的敏感信息提交前脱敏

### 输入校验
- Controller 层使用 Bean Validation 注解（`@NotBlank`、`@Email` 等）
- 文件上传限制：单文件 50MB，总请求 100MB

### 多租户安全
- 所有数据访问必须经过 `tenant_id` 过滤
- 租户 ID 通过请求头 `X-Tenant-Id` 传递
- Schema 隔离由 MyBatis-Plus 拦截器自动处理

## 测试规范
- 单元测试框架：JUnit 5 + Mockito
- 核心业务逻辑覆盖率目标 ≥ 80%
- 测试类命名：`{被测类}Test`
- 测试方法命名：`{methodName}_{scenario}_{expectedResult}`