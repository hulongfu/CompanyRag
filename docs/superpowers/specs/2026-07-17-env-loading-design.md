# .env 文件加载支持设计

## 概述

为 CompanyRag 项目添加 `.env` 文件加载支持，使开发人员可以通过 `.env` 文件管理环境变量配置，避免在 `application.yml` 中硬编码敏感信息。

## 问题现状

1. `.env` 文件已提交到 Git 仓库（包含敏感 API Key）
2. `.gitignore` 中没有忽略 `.env` 文件
3. `.env` 中的变量在项目中不生效（Spring Boot 不会自动加载 .env 文件）

## 解决方案

使用 `dotenv-java` 库在应用启动时加载 `.env` 文件，将环境变量注入到系统属性中，供 Spring Boot 配置使用。

## 架构设计

### 依赖组件

- **dotenv-java 3.0.0**：轻量级 Java 环境变量加载库
- 加载位置：项目根目录 `.env` 文件
- 加载时机：`main` 方法启动，`SpringApplication.run()` 之前

### 数据流

```
应用启动
    ↓
加载 .env 文件
    ↓
注入到 System.setProperty()
    ↓
Spring Boot 读取环境变量
    ↓
application.yml 使用 ${VAR:default} 语法
```

### 环境变量优先级

1. `.env` 文件中的配置（最高优先级）
2. 系统环境变量
3. `application.yml` 中的默认值

## 实现细节

### 1. 添加依赖

在 `company-rag-bootstrap/pom.xml` 中添加：

```xml
<dependency>
    <groupId>io.github.cdimascio</groupId>
    <artifactId>dotenv-java</artifactId>
    <version>3.0.0</version>
</dependency>
```

### 2. 修改启动类

在 `CompanyRagApplication.java` 的 `main` 方法中：

```java
public static void main(String[] args) {
    // 加载 .env 文件到系统环境变量
    io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.load();
    dotenv.entries().forEach(entry -> 
        System.setProperty(entry.getKey(), entry.getValue())
    );
    
    SpringApplication.run(CompanyRagApplication.class, args);
}
```

### 3. 修改 application.yml

将硬编码配置改为环境变量引用：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5433}/${POSTGRES_DB:company_rag}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

server:
  port: ${SERVER_PORT:8080}

spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

### 4. 安全处理

- `.env` 添加到 `.gitignore`
- 创建 `.env.example` 模板（不含真实密钥）
- 从 Git 历史中移除 `.env` 的敏感信息

## 测试策略

1. **单元测试**：验证环境变量加载逻辑
2. **集成测试**：启动应用，验证配置正确读取
3. **边界测试**：
   - `.env` 文件不存在时的降级行为
   - 环境变量缺失时使用默认值

## 安全考虑

- API Key 等敏感信息不再提交到 Git
- 生产环境通过环境变量或配置中心注入
- 开发环境使用 `.env` 本地管理

## 文件清单

- `company-rag-bootstrap/pom.xml` - 添加依赖
- `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/CompanyRagApplication.java` - 加载逻辑
- `company-rag-bootstrap/src/main/resources/application.yml` - 使用环境变量
- `.gitignore` - 忽略 .env 文件
- `.env.example` - 配置模板
