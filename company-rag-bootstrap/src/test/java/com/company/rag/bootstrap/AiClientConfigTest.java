package com.company.rag.bootstrap;

import com.company.rag.rag.config.AiClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AI 客户端配置能否正确加载
 * 测试使用统一的 spring.ai.openai 配置支持不同厂商的兼容模型
 */
@SpringBootTest
@ActiveProfiles("dev")
public class AiClientConfigTest {

    @Autowired
    private AiClientConfig.OpenAiConfigProperties openAiConfigProperties;

    /**
     * 测试 OpenAI 兼容配置能否正确加载
     */
    @Test
    public void testOpenAiConfigPropertiesShouldNotBeNull() {
        assertNotNull(openAiConfigProperties, "OpenAiConfigProperties 应该被创建");
        assertNotNull(openAiConfigProperties.getApiKey(), 
            "API key 不能为 null - 需要配置 spring.ai.openai.api-key");
    }

    /**
     * 测试 Embedding 配置正确加载
     */
    @Test
    public void testEmbeddingConfigShouldBeCorrect() {
        assertNotNull(openAiConfigProperties.getEmbedding(), "Embedding 配置应该存在");
        assertNotNull(openAiConfigProperties.getEmbedding().getApiKey(), "Embedding API Key 配置应该存在");
    }

    /**
     * 测试 Base URL 配置正确
     */
    @Test
    public void testEmbeddingBaseUrlShouldBeSiliconFlow() {
        String baseUrl = openAiConfigProperties.getEmbedding().getBaseUrl();
        assertNotNull(baseUrl, "Embedding Base URL 应该存在");
        assertTrue(baseUrl.contains("siliconflow"), 
            "Embedding Base URL 应该指向硅基流动：" + baseUrl);
    }
}
