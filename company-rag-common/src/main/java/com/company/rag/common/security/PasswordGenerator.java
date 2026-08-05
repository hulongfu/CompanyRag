package com.company.rag.common.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码生成工具（仅用于初始化数据库）
 */
public class PasswordGenerator {
    
    private static final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    
    /**
     * 编码密码
     * @param rawPassword 原始密码
     * @return 编码后的密码
     */
    public static String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
    
    public static void main(String[] args) {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        
        // 生成 admin123 的 BCrypt 密码
        String rawPassword = "admin123";
        String encodedPassword = passwordEncoder.encode(rawPassword);
        
        System.out.println("原始密码：" + rawPassword);
        System.out.println("BCrypt 密码：" + encodedPassword);
        System.out.println();
        System.out.println("SQL 更新语句：");
        System.out.println("UPDATE sys_user SET password = '" + encodedPassword + "' WHERE username = 'admin';");
    }
}

