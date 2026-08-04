package com.company.rag.tenant.service;

import com.company.rag.common.model.UserDTO;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;
import com.company.rag.tenant.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 用户服务单元测试
 */
class UserServiceTest {
    
    private UserMapper userMapper;
    private UserTenantRelMapper userTenantRelMapper;
    private TenantService tenantService;
    private UserServiceImpl userService;
    
    private UserDTO.CreateRequest createRequest;
    private UserDTO.UpdateRequest updateRequest;
    
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        userTenantRelMapper = mock(UserTenantRelMapper.class);
        tenantService = mock(TenantService.class);
        userService = new UserServiceImpl(userMapper, userTenantRelMapper, tenantService);
        
        createRequest = new UserDTO.CreateRequest();
        createRequest.setUsername("testuser");
        createRequest.setPassword("password123");
        createRequest.setDisplayName("测试用户");
        createRequest.setEmail("test@example.com");
        createRequest.setRole("user");
        createRequest.setTenantIds(Arrays.asList(1L, 2L));
        
        updateRequest = new UserDTO.UpdateRequest();
        updateRequest.setDisplayName("更新后的用户");
        updateRequest.setEmail("updated@example.com");
        updateRequest.setRole("admin");
        updateRequest.setTenantIds(Arrays.asList(1L));
    }
    
    @Test
    void testCreateUser_Success() {
        when(userMapper.countByUsername("testuser")).thenReturn(0);
        
        Tenant tenant1 = new Tenant();
        tenant1.setId(1L);
        Tenant tenant2 = new Tenant();
        tenant2.setId(2L);
        when(tenantService.getById(1L)).thenReturn(tenant1);
        when(tenantService.getById(2L)).thenReturn(tenant2);
        
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        });
        
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L, 2L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1", "租户 2"));
        
        UserDTO.UserResponse response = userService.createUser(createRequest);
        
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("testuser", response.getUsername());
        assertEquals("user", response.getRole());
        
        verify(userMapper).insert(any(User.class));
        verify(userTenantRelMapper).batchInsert(eq(1L), eq(Arrays.asList(1L, 2L)));
    }
    
    @Test
    void testCreateUser_UsernameExists() {
        when(userMapper.countByUsername("testuser")).thenReturn(1);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("用户名已存在"));
    }
    
    @Test
    void testCreateUser_InvalidRole() {
        createRequest.setRole("invalid_role");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("无效的角色"));
    }
    
    @Test
    void testCreateUser_TenantNotFound() {
        when(userMapper.countByUsername("testuser")).thenReturn(0);
        when(tenantService.getById(999L)).thenReturn(null);
        createRequest.setTenantIds(Arrays.asList(999L));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("租户不存在"));
    }
    
    @Test
    void testQueryUserList_WithFilters() {
        User user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setRole("user");
        user1.setStatus(1);
        
        User user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setRole("admin");
        user2.setStatus(1);
        
        when(userMapper.findByTenantId(1L, 1)).thenReturn(Arrays.asList(user1, user2));
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1"));
        when(userTenantRelMapper.findTenantIdsByUserId(2L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(2L)).thenReturn(Arrays.asList("租户 1"));
        
        List<UserDTO.UserResponse> users = userService.queryUserList("user", 1L, 1, null);
        
        assertEquals(2, users.size());
        verify(userMapper).findByTenantId(1L, 1);
    }
    
    @Test
    void testGetUserById_Success() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("user");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.of(2026, 8, 4, 10, 0, 0));
        user.setUpdateTime(LocalDateTime.of(2026, 8, 4, 10, 0, 0));
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        
        UserDTO.UserDetailResponse response = userService.getUserById(1L);
        
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("testuser", response.getUsername());
    }
    
    @Test
    void testGetUserById_NotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);
        
        UserDTO.UserDetailResponse response = userService.getUserById(999L);
        
        assertNull(response);
    }
    
    @Test
    void testUpdateUser_Success() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("user");
        user.setStatus(1);
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1"));
        
        UserDTO.UserResponse response = userService.updateUser(1L, updateRequest);
        
        assertNotNull(response);
        verify(userMapper).updateById(any(User.class));
        verify(userTenantRelMapper).deleteByUserId(1L);
        verify(userTenantRelMapper).batchInsert(eq(1L), eq(Arrays.asList(1L)));
    }
    
    @Test
    void testUpdateUser_NotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateUser(999L, updateRequest)
        );
        assertTrue(exception.getMessage().contains("用户不存在"));
    }
    
    @Test
    void testDeleteUser_Success() {
        User user = new User();
        user.setId(1L);
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.deleteById(1L)).thenReturn(1);
        
        boolean result = userService.deleteUser(1L);
        
        assertTrue(result);
        verify(userTenantRelMapper).deleteByUserId(1L);
        verify(userMapper).deleteById(1L);
    }
    
    @Test
    void testDeleteUser_NotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);
        
        boolean result = userService.deleteUser(999L);
        
        assertFalse(result);
    }
}