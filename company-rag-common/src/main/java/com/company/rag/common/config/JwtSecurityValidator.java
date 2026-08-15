package com.company.rag.common.config;

import com.company.rag.common.security.JwtProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * JWT 配置验证器
 * 在应用启动时验证 JWT 密钥强度，确保生产环境安全
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtSecurityValidator {

    private final JwtProperties jwtProperties;

    /**
     * 最小密钥长度（字节）- Base64 解码后
     */
    private static final int MIN_KEY_LENGTH_BYTES = 32;

    @PostConstruct
    public void validate() {
        String secret = jwtProperties.getSecret();

        // 检查密钥是否为空
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                "JWT_SECRET 未配置！生产环境必须设置强随机密钥。\n" +
                "生成方法：openssl rand -base64 32\n" +
                "请在环境变量中设置 JWT_SECRET"
            );
        }

        // 检查是否是示例密钥（弱密钥）
        if (isWeakOrExampleKey(secret)) {
            throw new IllegalStateException(
                "JWT_SECRET 使用了弱密钥或示例密钥！生产环境必须使用强随机密钥。\n" +
                "当前密钥：" + maskKey(secret) + "\n" +
                "生成方法：openssl rand -base64 32\n" +
                "请在环境变量中设置 JWT_SECRET"
            );
        }

        // 验证密钥长度（Base64 解码后至少 32 字节）
        try {
            byte[] decodedKey = Base64.getDecoder().decode(secret.trim());
            if (decodedKey.length < MIN_KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                    "JWT_SECRET 长度不足！Base64 解码后至少需要 " + MIN_KEY_LENGTH_BYTES + " 字节。\n" +
                    "当前长度：" + decodedKey.length + " 字节\n" +
                    "生成方法：openssl rand -base64 32 | tr -d '\\n'\n" +
                    "请在环境变量中设置 JWT_SECRET"
                );
            }
            log.info("JWT 密钥验证通过：长度={} 字节，符合要求（≥{} 字节）", decodedKey.length, MIN_KEY_LENGTH_BYTES);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "JWT_SECRET 不是有效的 Base64 编码！\n" +
                "生成方法：openssl rand -base64 32 | tr -d '\\n'\n" +
                "请在环境变量中设置 JWT_SECRET", e
            );
        }

        log.info("JWT 安全配置验证通过");
    }

    /**
     * 检查是否是弱密钥或示例密钥
     */
    private boolean isWeakOrExampleKey(String secret) {
        // 常见弱密钥模式
        String[] weakPatterns = {
            "your_jwt_secret",
            "your_secret",
            "secret_key",
            "jwt_secret",
            "this-is-a-secret",
            "example",
            "test",
            "demo",
            "default"
        };

        String lowerSecret = secret.toLowerCase();
        for (String pattern : weakPatterns) {
            if (lowerSecret.contains(pattern.toLowerCase())) {
                return true;
            }
        }

        // 检查是否是已知的示例密钥
        String[] knownExampleKeys = {
            "dGhpcy1pcy1hLXNlY3JldC1rZXktZm9yLWp3dC10b2tlbi1nZW5lcmF0aW9uLTEyMzQ1Njc4OTA=", // this-is-a-secret-key-for-jwt-token-generation-1234567890
            "c2VjcmV0LWtleS1mb3Itand0LXRva2VuLWdlbmVyYXRpb24=", // secret-key-for-jwt-token-generation
            "bXktc3VwZXItc2VjcmV0LWtleS0xMjM0NTY3ODkw" // my-super-secret-key-1234567890
        };

        for (String exampleKey : knownExampleKeys) {
            if (secret.trim().equals(exampleKey)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 脱敏显示密钥（仅显示前后各 4 个字符）
     */
    private String maskKey(String secret) {
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }
}
