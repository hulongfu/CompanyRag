package com.company.rag.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密码验证测试
 */
public class PasswordVerificationTest {

    @Test
    public void testAdminPassword() {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        
        // 数据库中存储的密码
        String storedPassword = "$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6";
        
        // 前端输入的密码
        String inputPassword = "admin123";
        
        // 验证密码是否匹配
        boolean matches = passwordEncoder.matches(inputPassword, storedPassword);
        
        System.out.println("Stored password: " + storedPassword);
        System.out.println("Input password: " + inputPassword);
        System.out.println("Password matches: " + matches);
        
        assertTrue(matches, "密码应该匹配 admin123");
    }
}
