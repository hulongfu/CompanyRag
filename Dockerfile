# 构建阶段
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 配置阿里云 Maven 镜像加速
COPY maven-settings.xml /usr/share/maven/ref/settings-docker.xml

COPY pom.xml .
COPY company-rag-common/pom.xml company-rag-common/
COPY company-rag-tenant/pom.xml company-rag-tenant/
COPY company-rag-document/pom.xml company-rag-document/
COPY company-rag-rag/pom.xml company-rag-rag/
COPY company-rag-agent/pom.xml company-rag-agent/
COPY company-rag-web/pom.xml company-rag-web/
COPY company-rag-bootstrap/pom.xml company-rag-bootstrap/
RUN mvn dependency:go-offline -B

COPY . .
# 执行测试并打包（生产环境不应跳过测试）
RUN mvn package -B

# 运行阶段
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app

# 创建非 root 用户运行应用（安全最佳实践）
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# 复制应用 JAR
COPY --from=build /build/company-rag-bootstrap/target/*.jar app.jar

# 复制 agent_skills 目录（技能定义）
COPY --from=build /build/agent_skills /app/agent_skills

# 更改文件所有者为 appuser
RUN chown appuser:appgroup app.jar
RUN chown -R appuser:appgroup /app/agent_skills

# 设置环境变量：技能路径
ENV SKILLS_PATH=/app/agent_skills

# 强制设置生产环境（防止裸镜像默认 dev 环境）
ENV SPRING_PROFILES_ACTIVE=prod

# 切换到非 root 用户
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
