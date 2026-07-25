# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 配置阿里云 Maven 镜像加速
COPY <<EOF /usr/share/maven/ref/settings-docker.xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-maven</id>
      <mirrorOf>central</mirrorOf>
      <name>阿里云公共仓库</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

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
RUN mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/company-rag-bootstrap/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
