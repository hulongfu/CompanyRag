package com.company.rag.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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
     * LLM 调用超时时间（秒）
     * - 连接超时：建立 TCP 连接的时间
     * - 读取超时：等待 LLM 响应的时间（包括流式响应）
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 120; // 2 分钟，给 LLM 充足的响应时间

    /**
     * 配置 RestClient 的超时（用于非流式响应）
     * Spring AI 会自动使用这个 Bean 来创建 OpenAiApi
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        ClientHttpRequestFactory requestFactory = createRequestFactory(
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS),
            Duration.ofSeconds(READ_TIMEOUT_SECONDS)
        );
        
        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    /**
     * 配置 WebClient 的超时（用于流式响应）
     * Spring AI 会自动使用这个 Bean 来创建 OpenAiApi
     * 
     * 注意：WebClient 的超时由 Spring Boot 的自动配置处理
     * 配置在 application.yml 的 spring.http.client 部分
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        // Spring Boot 的 WebClientAutoConfiguration 会自动应用 spring.http.client 配置
        return WebClient.builder();
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
}
