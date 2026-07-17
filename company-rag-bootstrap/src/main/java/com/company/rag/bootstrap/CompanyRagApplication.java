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
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
})
@ComponentScan(basePackages = "com.company.rag")
@MapperScan({"com.company.rag.tenant.mapper", "com.company.rag.document.mapper", "com.company.rag.rag.mapper"})
public class CompanyRagApplication {

    public static void main(String[] args) {
        // 加载 .env 文件到系统环境变量，使 Spring Boot 可以通过 ${VAR} 语法读取
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
        log.info(".env 文件加载完成，共加载 {} 个环境变量", dotenv.entries().size());
        
        SpringApplication.run(CompanyRagApplication.class, args);
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
