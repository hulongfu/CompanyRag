package com.company.rag.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性
 * 从环境变量或配置文件中读取
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** JWT 签名密钥（通过环境变量 JWT_SECRET 注入） */
    private String secret;

    /** Access Token 有效期（毫秒），默认 2 小时 */
    private long accessTokenExpiration = 7200000L;

    /** Refresh Token 有效期（毫秒），默认 7 天 */
    private long refreshTokenExpiration = 604800000L;
}
