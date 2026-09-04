package com.company.rag.bootstrap.config;

import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.mcp.filter.McpSecurityFilter;
import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security 配置类
 * 
 * 主要功能：
 * - 禁用 CSRF（无状态 API）
 * - 配置无状态会话（STATELESS）
 * - 放行公开接口（认证、静态资源、Swagger、健康检查）
 * - 其他请求需要 JWT 认证
 * - 配置 401/403 异常处理
 * - 集成 JwtAuthenticationFilter
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final McpSecurityFilter mcpSecurityFilter;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantService tenantService;

    /**
     * 是否放行 Swagger/OpenAPI 文档。
     * 默认 true（开发环境），生产环境通过配置设为 false 避免 API 全景暴露。
     */
    @Value("${rag.security.permit-swagger:true}")
    private boolean permitSwagger;

    /**
     * 配置安全过滤链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（无状态 API 不需要）
            .csrf(AbstractHttpConfigurer::disable)
            
            // 配置会话管理为无状态
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // 配置请求授权规则
            .authorizeHttpRequests(auth -> {
                // 放行认证相关接口
                auth.requestMatchers(new AntPathRequestMatcher("/api/auth/**", HttpMethod.POST.name())).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/api/auth/**", HttpMethod.GET.name())).permitAll();

                // 放行登录页面和首页
                auth.requestMatchers(new AntPathRequestMatcher("/")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/login")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/index")).permitAll();

                // 放行静态资源
                auth.requestMatchers(new AntPathRequestMatcher("/static/**")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/public/**")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/assets/**")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/*.js")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/*.css")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/*.html")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/*.ico")).permitAll();

                // Swagger UI 和 API 文档：仅开发环境放行（rag.security.permit-swagger=true）。
                // 生产环境（false）不注册放行规则，请求会落入 anyRequest().authenticated() 需认证，避免 API 全景暴露。
                if (permitSwagger) {
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll();
                }

                // 放行测试端点
                auth.requestMatchers("/test/**").permitAll();

                // 放行错误页面（Swagger 内部资源 404 时会转发到 /error）
                auth.requestMatchers("/error").permitAll();

                // 放行健康检查端点及其探针子路径（/actuator/health、/liveness、/readiness），供 K8s/Docker 健康检查
                auth.requestMatchers(new AntPathRequestMatcher("/actuator/health/**")).permitAll();
                auth.requestMatchers(new AntPathRequestMatcher("/health")).permitAll();

                // 信息端点和 Prometheus 指标需要认证（避免敏感信息泄露）
                auth.requestMatchers(new AntPathRequestMatcher("/actuator/info")).authenticated();
                auth.requestMatchers(new AntPathRequestMatcher("/actuator/prometheus")).authenticated();
                auth.requestMatchers(new AntPathRequestMatcher("/metrics")).authenticated();

                // 放行 MCP 端点（MCP Server 需要被外部 AI 应用/框架调用）
                auth.requestMatchers(new AntPathRequestMatcher("/mcp/**")).permitAll();

                // 放行下载接口（下载链接是临时生成的，包含租户信息，且有安全检查）
                auth.requestMatchers(new AntPathRequestMatcher("/api/download/**")).permitAll();

                // 其他所有请求需要认证
                auth.anyRequest().authenticated();
            })
            
            // 配置异常处理
            .exceptionHandling(exception -> exception
                // 401 未认证
                .authenticationEntryPoint((request, response, authException) -> {
                    log.warn("认证失败：URI={}, Method={}, Message={}", request.getRequestURI(), request.getMethod(), authException.getMessage());
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"message\":\"未认证或 Token 无效\"}");
                })
                // 403 未授权
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    log.warn("访问被拒绝：{}", accessDeniedException.getMessage());
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"message\":\"权限不足\"}");
                })
            )
            
            // 添加 JWT 认证过滤器（在用户名密码认证过滤器之前执行）
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            
            // 添加 MCP 安全过滤器（在 JWT 认证过滤器之前执行，用于 MCP 端点的特殊处理）
            .addFilterBefore(mcpSecurityFilter, JwtAuthenticationFilter.class);
        
        return http.build();
    }

    /**
     * 配置认证提供者
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    /**
     * 配置认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
