package com.company.rag.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
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
                            .schema(new io.swagger.v3.oas.models.media.StringSchema()));
                    // 添加 X-User-Id 请求头参数
                    operation.addParametersItem(new Parameter()
                            .in("header")
                            .name("X-User-Id")
                            .description("用户 ID")
                            .required(false)
                            .schema(new io.swagger.v3.oas.models.media.StringSchema()));
                })
        );
    }
}