package com.company.rag.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.rag.tenant.context.TenantAwareJdbcTemplate;

/**
 * Spring AI 客户端配置
 * <p>
 * 使用统一的 spring.ai.openai 配置，支持不同厂商的 OpenAI 兼容模型：
 * - Chat 模型：使用 spring.ai.openai.chat 配置
 * - Embedding 模型：使用 spring.ai.openai.embedding 配置
 * </p>
 */
@Configuration
public class AiClientConfig {

    /**
     * OpenAI 兼容配置属性（从 spring.ai.openai 读取）
     */
    @Configuration
    @ConfigurationProperties(prefix = "spring.ai.openai")
    public static class OpenAiConfigProperties {
        private String apiKey;
        private String baseUrl;
        private ChatConfig chat = new ChatConfig();
        private EmbeddingConfig embedding = new EmbeddingConfig();

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public ChatConfig getChat() {
            return chat;
        }

        public void setChat(ChatConfig chat) {
            this.chat = chat;
        }

        public EmbeddingConfig getEmbedding() {
            return embedding;
        }

        public void setEmbedding(EmbeddingConfig embedding) {
            this.embedding = embedding;
        }

        /**
         * Chat 模型配置
         */
        public static class ChatConfig {
            private Options options = new Options();

            public Options getOptions() {
                return options;
            }

            public void setOptions(Options options) {
                this.options = options;
            }

            public static class Options {
                private String model;
                private Double temperature;

                public String getModel() {
                    return model;
                }

                public void setModel(String model) {
                    this.model = model;
                }

                public Double getTemperature() {
                    return temperature;
                }

                public void setTemperature(Double temperature) {
                    this.temperature = temperature;
                }
            }
        }

        /**
         * Embedding 模型配置
         */
        public static class EmbeddingConfig {
            private String apiKey;
            private String baseUrl;
            private Options options = new Options();

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public Options getOptions() {
                return options;
            }

            public void setOptions(Options options) {
                this.options = options;
            }

            public static class Options {
                private String model;

                public String getModel() {
                    return model;
                }

                public void setModel(String model) {
                    this.model = model;
                }
            }
        }
    }

    /**
     * 创建 Embedding 模型（使用 spring.ai.openai.embedding 配置）
     * 优先使用 embedding 专用配置，如果不存在则使用顶层配置
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OpenAiConfigProperties properties) {
        // 优先使用 embedding 专用的 apiKey 和 baseUrl，如果不存在则使用顶层配置
        String apiKey = properties.getEmbedding().getApiKey() != null 
                ? properties.getEmbedding().getApiKey() 
                : properties.getApiKey();
        
        String baseUrl = properties.getEmbedding().getBaseUrl() != null 
                ? properties.getEmbedding().getBaseUrl() 
                : properties.getBaseUrl();
        
        String model = properties.getEmbedding().getOptions().getModel() != null 
                ? properties.getEmbedding().getOptions().getModel() 
                : "text-embedding-ada-002";

        // 创建 OpenAiApi 客户端
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        
        // 创建 EmbeddingOptions，指定模型名称
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        
        // 创建 EmbeddingModel
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    /**
     * 创建 VectorStore Bean
     * <p>
     * 使用 TenantAwareJdbcTemplate 包装 JdbcTemplate，使 PgVectorStore 的 SQL
     * 中硬编码的 public schema 前缀能动态替换为当前租户 schema。
     */
    @Bean
    @Primary
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, 
                                     PgVectorStoreProperties properties) {
        TenantAwareJdbcTemplate tenantAwareJdbcTemplate = new TenantAwareJdbcTemplate(jdbcTemplate);
        return PgVectorStore.builder(tenantAwareJdbcTemplate, embeddingModel)
                .indexType(PgVectorStore.PgIndexType.valueOf(properties.getIndexType()))
                .distanceType(PgVectorStore.PgDistanceType.valueOf(properties.getDistanceType()))
                .dimensions(properties.getDimension())
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .build();
    }

    /**
     * PGVector Store 配置属性
     */
    @Configuration
    @ConfigurationProperties(prefix = "spring.vectorstore.pgvector")
    public static class PgVectorStoreProperties {
        private String indexType = "HNSW";
        private String distanceType = "COSINE_DISTANCE";
        private int dimension = 1024;
        private boolean removeExistingVectorStoreTable = false;

        public String getIndexType() {
            return indexType;
        }

        public void setIndexType(String indexType) {
            this.indexType = indexType;
        }

        public String getDistanceType() {
            return distanceType;
        }

        public void setDistanceType(String distanceType) {
            this.distanceType = distanceType;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public boolean isRemoveExistingVectorStoreTable() {
            return removeExistingVectorStoreTable;
        }

        public void setRemoveExistingVectorStoreTable(boolean removeExistingVectorStoreTable) {
            this.removeExistingVectorStoreTable = removeExistingVectorStoreTable;
        }
    }
}
