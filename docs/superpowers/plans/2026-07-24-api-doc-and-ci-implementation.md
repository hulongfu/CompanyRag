# API 文档自动生成 + Gitee Go CI 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 CompanyRag 项目集成 SpringDoc OpenAPI 文档和 Gitee Go 自动化 CI 流水线。

**架构：**
- API 文档：在 `company-rag-web` 模块添加 SpringDoc 依赖 + 配置类，零侵入式生成 OpenAPI 文档
- CI/CD：在仓库根目录创建 `.gitee/workflows/ci.yml`，定义 Maven 构建+测试流水线

**Tech Stack:** SpringDoc OpenAPI 2.8.6, Gitee Go, Maven, Docker

---

### Task 1: 添加 SpringDoc Maven 依赖

**Files:**
- Modify: `company-rag-web/pom.xml`

- [ ] **Step 1: 在 company-rag-web/pom.xml 添加 SpringDoc 依赖**

在 `<dependencies>` 末尾（lombok 依赖之后）添加：

```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.8.6</version>
        </dependency>
```

- [ ] **Step 2: 验证 Maven 依赖解析**

```bash
cd D:/tmp/CompanyRag
mvn dependency:resolve -pl company-rag-web -am
```

Expected: BUILD SUCCESS，依赖解析成功。

- [ ] **Step 3: Commit**

```bash
git add company-rag-web/pom.xml
git commit -m "feat: add springdoc-openapi dependency for API docs"
```

---

### Task 2: 添加 SpringDoc 配置

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 中添加 SpringDoc 配置**

找到 `spring:` 配置块，在 `servlet:` 配置之后添加：

```yaml
  springdoc:
    api-docs:
      path: /api-docs
    swagger-ui:
      path: /swagger-ui.html
      operations-sorter: method
    show-actuator: false
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-bootstrap/src/main/resources/application.yml
git commit -m "feat: configure springdoc OpenAPI and Swagger UI paths"
```

---

### Task 3: 创建 OpenAPI 全局配置类

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/config/OpenApiConfig.java`

- [ ] **Step 1: 创建 OpenApiConfig.java**

```java
package com.company.rag.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.Parameters;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 全局配置
 * 配置 API 文档标题、版本、全局请求头等信息
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI companyRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CompanyRag API")
                        .description("企业知识库 RAG 系统 REST API")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("CompanyRag Team")
                                .url("https://gitee.com/LongHuDaoChang/CompanyRag")));
    }

    /**
     * 全局请求头参数：X-Tenant-Id（租户 ID）
     */
    @Bean
    public OpenApiCustomizer globalHeaderCustomizer() {
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    // 添加 X-Tenant-Id 请求头参数
                    operation.addParametersItem(new Parameter()
                            .in("header")
                            .name("X-Tenant-Id")
                            .description("租户 ID")
                            .required(false)
                            .schema(new StringSchema()));
                    // 添加 X-User-Id 请求头参数
                    operation.addParametersItem(new Parameter()
                            .in("header")
                            .name("X-User-Id")
                            .description("用户 ID")
                            .required(false)
                            .schema(new StringSchema()));
                })
        );
    }
}
```

- [ ] **Step 2: 验证编译通过**

```bash
cd D:/tmp/CompanyRag
mvn compile -pl company-rag-web -am
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 验证 Swagger UI 可访问（需启动应用）**

```bash
cd D:/tmp/CompanyRag
mvn spring-boot:run -pl company-rag-bootstrap -am -Dspring-boot.run.profiles=dev
```

在浏览器访问 `http://localhost:8080/swagger-ui.html`，应显示所有 API 端点列表。

- [ ] **Step 4: Commit**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/config/OpenApiConfig.java
git commit -m "feat: add OpenAPI global config with tenant/user header params"
```

---

### Task 4: 创建 Gitee Go CI 流水线

**Files:**
- Create: `.gitee/workflows/ci.yml`

- [ ] **Step 1: 创建 .gitee/workflows/ci.yml**

```yaml
pipeline:
  name: CompanyRag CI
  trigger:
    push:
      - main
    tag:
      - v*
  stages:
    - stage: Build and Test
      jobs:
        - job: Maven Build
          steps:
            - run: mvn clean test -B
            - run: mvn package -DskipTests -B
            - run: echo "✅ 构建完成，JAR 包已生成"
    - stage: Docker Build
      jobs:
        - job: Docker Build
          steps:
            - run: |
                if [ -n "${GITEE_TAG}" ]; then
                  docker build -t company-rag:${GITEE_TAG} .
                  echo "✅ Docker 镜像构建完成: company-rag:${GITEE_TAG}"
                else
                  echo "⏭️ 非标签触发，跳过 Docker 构建"
                fi
```

- [ ] **Step 2: Commit**

```bash
git add .gitee/workflows/ci.yml
git commit -m "feat: add Gitee Go CI workflow for build and test"
```

---

### Task 5: 更新 README 添加构建徽章（可选）

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 在 README.md 中添加 Gitee Go CI 状态徽章**

在 README 标题下方添加（需替换为实际仓库地址）：

```markdown
[![Gitee Go CI](https://gitee.com/LongHuDaoChang/CompanyRag/badge/ci.svg)](https://gitee.com/LongHuDaoChang/CompanyRag/ci)
```

> 注意：徽章 URL 需在 Gitee Go 首次运行后确认实际地址。

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add Gitee Go CI badge to README"
```

---

### Task 6: 最终验证与推送

- [ ] **Step 1: 确保所有文件已提交**

```bash
cd D:/tmp/CompanyRag
git status
```

Expected: working tree clean

- [ ] **Step 2: 推送到 Gitee**

```bash
git push gitee main
```

- [ ] **Step 3: 在 Gitee 仓库页面验证 CI 运行**

打开 `https://gitee.com/LongHuDaoChang/CompanyRag`，查看 CI 标签页，确认流水线已触发并运行成功。