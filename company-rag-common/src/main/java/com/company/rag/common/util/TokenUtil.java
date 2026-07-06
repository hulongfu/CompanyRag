package com.company.rag.common.util;

import java.util.UUID;

/**
 * Token工具 - 简单实现，生产环境替换为JWT
 */
public class TokenUtil {

    public static String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateSessionId() {
        return "session_" + UUID.randomUUID().toString().replace("-", "");
    }
}
