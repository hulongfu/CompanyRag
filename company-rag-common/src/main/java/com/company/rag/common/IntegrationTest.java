package com.company.rag.common;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试注解
 * 标记需要完整 Spring 上下文和外部依赖（Redis、PostgreSQL 等）的测试
 * 
 * 使用方式：
 * 1. 本地运行：mvn test -Pintegration-test
 * 2. CI 跳过：mvn clean package -B -DexcludedGroups=integration-test
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("dev")
@Tag("integration-test")
public @interface IntegrationTest {
}
