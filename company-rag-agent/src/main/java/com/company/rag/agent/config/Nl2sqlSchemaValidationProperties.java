package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * nl2sql 表/列名自校验配置。
 * <p>开启后，DatabaseQueryTool 会在执行前校验 LLM 生成的 SQL 所引用的表/列
 * 是否存在于当前租户 schema，缺失时返回带候选的错误文本，帮助 ReAct 自愈。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.nl2sql.schema-validation")
public class Nl2sqlSchemaValidationProperties {

    /** 是否启用表/列名自校验，默认 true；需热关断时改环境变量即可 */
    private boolean enabled = true;
}