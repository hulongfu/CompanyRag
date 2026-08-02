package com.company.rag.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi0xMjM0NTY=");
        properties.setAccessTokenExpiration(3600000L);
        properties.setRefreshTokenExpiration(86400000L);
        tokenProvider = new JwtTokenProvider(properties);
    }

    @Test
    void generateAccessToken_shouldReturnValidToken() {
        String token = tokenProvider.generateAccessToken(1L, 1L, "admin");
        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(1L, tokenProvider.getUserIdFromToken(token));
        assertEquals(1L, tokenProvider.getTenantIdFromToken(token));
        assertEquals("admin", tokenProvider.getRoleFromToken(token));
    }

    @Test
    void generateRefreshToken_shouldReturnValidToken() {
        String token = tokenProvider.generateRefreshToken(1L);
        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(1L, tokenProvider.getUserIdFromToken(token));
    }

    @Test
    void validateToken_withInvalidToken_shouldReturnFalse() {
        assertFalse(tokenProvider.validateToken("invalid-token"));
    }

    @Test
    void validateToken_withExpiredToken_shouldReturnFalse() throws Exception {
        JwtProperties shortExp = new JwtProperties();
        shortExp.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi0xMjM0NTY=");
        shortExp.setAccessTokenExpiration(1L);
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortExp);

        String token = shortProvider.generateAccessToken(1L, 1L, "admin");
        Thread.sleep(10);
        assertFalse(shortProvider.validateToken(token));
    }
}
