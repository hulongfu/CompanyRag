package com.company.rag.web.config;

import com.company.rag.tenant.context.TenantContextHelper;
import com.company.rag.tenant.interceptor.TenantInterceptor;
import com.company.rag.tenant.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 注册租户拦截器，实现请求级别的租户上下文注入和Schema切换
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantContextHelper tenantContextHelper;
    private final TenantMapper tenantMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantInterceptor(tenantContextHelper, tenantMapper))
                .addPathPatterns("/**")
                .excludePathPatterns("/login", "/css/**", "/js/**", "/favicon.ico", "/static/**", "/assets/**", "/webjars/**");
    }
}