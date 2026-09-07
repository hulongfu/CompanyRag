package com.company.rag.bootstrap;

import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.service.TenantService;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

/**
 * CompanyRag 企业知识库 RAG 系统启动类
 * <p>
 * 排除 ContextFunctionCatalogAutoConfiguration 以解决 Spring Cloud Function
 * 与 Spring Boot 3.4.4 的兼容性问题
 */
@Slf4j
@SpringBootApplication(exclude = {
        ContextFunctionCatalogAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class
})
@ComponentScan(basePackages = "com.company.rag")
@MapperScan({"com.company.rag.tenant.mapper", "com.company.rag.document.mapper", "com.company.rag.rag.mapper"})
@EnableAsync  // 启用异步方法支持（用于下载工具的异步清理）
public class CompanyRagApplication {

    public static void main(String[] args) {
        // 加载 .env 文件到系统环境变量，使 Spring Boot 可以通过 ${VAR} 语法读取
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
        log.info(".env 文件加载完成，共加载 {} 个环境变量", dotenv.entries().size());
        
        // 生产环境安全检查：验证关键环境变量是否已配置
        validateProductionEnvironment();
        
        SpringApplication.run(CompanyRagApplication.class, args);
    }

    /**
     * 生产环境安全检查
     * 验证关键环境变量是否已正确配置
     */
    private static void validateProductionEnvironment() {
        log.info("开始生产环境安全检查...");
        
        // JWT_SECRET 强校验：仅生产 Profile（prod）启用，本地开发/测试跳过以允许快速启动
        String activeProfile = resolveActiveProfile();
        boolean isProd = activeProfile != null && activeProfile.toLowerCase().contains("prod");
        if (isProd) {
            String jwtSecret = System.getProperty("JWT_SECRET");
            if (jwtSecret == null || jwtSecret.trim().isEmpty() ||
                "your_jwt_secret_key_here_must_be_strong_random_string".equals(jwtSecret)) {
                log.error("========================================");
                log.error("【安全警告】JWT_SECRET 未配置或使用默认值！");
                log.error("生产环境必须设置强随机密钥，否则 Token 可被伪造。");
                log.error("生成方法：openssl rand -base64 32");
                log.error("========================================");
                throw new IllegalStateException("JWT_SECRET 未配置或使用默认值，启动终止");
            }
            log.info("✓ JWT_SECRET 已配置");
        } else {
            log.info("非生产 Profile（{}），跳过 JWT_SECRET 强校验", activeProfile);
        }
        
        // 检查数据库密码
        String dbPassword = System.getProperty("POSTGRES_PASSWORD");
        if (dbPassword == null || dbPassword.trim().isEmpty() || 
            "your_strong_database_password_here".equals(dbPassword) ||
            "company_rag_app123456".equals(dbPassword)) {
            log.warn("========================================");
            log.warn("【安全警告】POSTGRES_PASSWORD 未配置或使用默认值！");
            log.warn("生产环境必须设置强密码以保护数据库安全。");
            log.warn("========================================");
            // 注意：这里使用警告而非错误，允许开发环境使用默认值
        } else {
            log.info("✓ POSTGRES_PASSWORD 已配置");
        }
        
        // 检查 API Key
        String dashscopeKey = System.getProperty("DASHSCOPE_API_KEY");
        if (dashscopeKey == null || dashscopeKey.trim().isEmpty() || 
            "your_dashscope_api_key_here".equals(dashscopeKey)) {
            log.warn("========================================");
            log.warn("【配置警告】DASHSCOPE_API_KEY 未配置！");
            log.warn("LLM 功能将无法使用，请配置有效的 API Key。");
            log.warn("========================================");
        } else {
            log.info("✓ DASHSCOPE_API_KEY 已配置");
        }
        
        log.info("生产环境安全检查完成");
    }

    /**
     * 解析当前激活的 Spring Profile
     * <p>
     * validateProductionEnvironment() 在 SpringApplication.run 之前执行，Environment 尚未就绪，
     * 因此需手动读取 spring.profiles.active（JVM 参数优先，回退到 SPRING_PROFILES_ACTIVE 环境变量）。
     * .env 已通过 Dotenv 加载为 System properties，故此处直接用 getProperty 即可覆盖两种来源。
     */
    private static String resolveActiveProfile() {
        // 优先读 JVM 参数 -Dspring.profiles.active；否则回退 .env/环境变量注入的 SPRING_PROFILES_ACTIVE
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null || profile.trim().isEmpty()) {
            profile = System.getProperty("SPRING_PROFILES_ACTIVE");
        }
        return profile;
    }

    /**
     * 启动时自动为未初始化 Schema 的租户创建 Schema
     */
    @Bean
    public ApplicationRunner tenantSchemaInitializer(TenantService tenantService) {
        return args -> {
            log.info("开始检查并初始化租户 Schema...");
            
            try {
                List<Tenant> tenants = tenantService.getAllTenants();
                int initializedCount = 0;
                
                for (Tenant tenant : tenants) {
                    // 如果租户没有 schemaName，说明还未初始化
                    if (tenant.getSchemaName() == null || tenant.getSchemaName().isEmpty()) {
                        log.info("检测到未初始化 Schema 的租户：{} ({}), 开始初始化...", 
                            tenant.getTenantName(), tenant.getTenantCode());
                        
                        try {
                            tenantService.createTenantSchema(tenant);
                            log.info("租户 [{}] Schema 初始化成功", tenant.getTenantCode());
                            initializedCount++;
                        } catch (Exception e) {
                            log.error("租户 [{}] Schema 初始化失败：{}", tenant.getTenantCode(), e.getMessage(), e);
                        }
                    }
                }
                
                if (initializedCount > 0) {
                    log.info("本次启动共初始化 {} 个租户的 Schema", initializedCount);
                } else {
                    log.info("所有租户 Schema 已初始化完成");
                }
                
            } catch (Exception e) {
                log.error("检查租户 Schema 状态时发生错误", e);
            }
            
            log.info("租户 Schema 检查完成");
        };
    }
}
