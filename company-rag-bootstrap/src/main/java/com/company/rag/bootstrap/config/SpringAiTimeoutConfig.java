package com.company.rag.bootstrap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Spring AI 超时配置
 * 
 * Spring AI 1.0.4 同时使用 RestClient 和 WebClient 作为底层 HTTP 客户端：
 * - RestClient：用于非流式响应（chatCompletionEntity）
 * - WebClient：用于流式响应（chatCompletionStream）- Flux 流
 * 
 * 本配置通过自定义 RestClient.Builder 来设置连接超时和读取超时
 * 
 * 注意：
 * - 不能在 application.yml 中配置 spring.ai.openai.chat.options.timeout
 * - 因为 OpenAiChatOptions 类没有 timeout 属性
 * - WebClient 的超时通过 spring.http.client 配置（见 application.yml）
 */
@Configuration
public class SpringAiTimeoutConfig {

    /**
     * LLM 调用超时时间（从配置文件读取）
     * - 连接超时：建立 TCP 连接的时间
     * - 读取超时：等待 LLM 响应的时间（包括流式响应）
     * 
     * 配置项：spring.http.client.connect-timeout / read-timeout
     * 格式：数字 + 单位（如 10s, 300s, 2m）
     */
    @Value("${spring.http.client.connect-timeout:10s}")
    private String connectTimeoutStr;
    
    @Value("${spring.http.client.read-timeout:300s}")
    private String readTimeoutStr;

    /**
     * 配置 RestClient 的超时（用于非流式响应）
     * Spring AI 会自动使用这个 Bean 来创建 OpenAiApi
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        Duration connectTimeout = parseDuration(connectTimeoutStr);
        Duration readTimeout = parseDuration(readTimeoutStr);
        
        ClientHttpRequestFactory requestFactory = createRequestFactory(connectTimeout, readTimeout);
        
        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    /**
     * 创建带超时配置的 RequestFactory
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     * @return 配置好的 RequestFactory
     */
    private ClientHttpRequestFactory createRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return factory;
    }
    
    /**
     * 解析时间字符串为 Duration
     * 支持格式：数字 + 单位（如 "10s", "300s", "2m", "1h"）
     * 
     * @param timeStr 时间字符串
     * @return Duration 对象
     */
    private Duration parseDuration(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return Duration.ofSeconds(10); // 默认 10 秒
        }
        
        timeStr = timeStr.trim().toLowerCase();
        
        try {
            // 支持 Spring Boot 的时间格式：数字 + 单位
            if (timeStr.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(timeStr.substring(0, timeStr.length() - 2)));
            } else if (timeStr.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(timeStr.substring(0, timeStr.length() - 1)));
            } else if (timeStr.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(timeStr.substring(0, timeStr.length() - 1)));
            } else if (timeStr.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(timeStr.substring(0, timeStr.length() - 1)));
            } else {
                // 纯数字，默认按秒处理
                return Duration.ofSeconds(Long.parseLong(timeStr));
            }
        } catch (NumberFormatException e) {
            // 解析失败时返回默认值
            return Duration.ofSeconds(10);
        }
    }
}
