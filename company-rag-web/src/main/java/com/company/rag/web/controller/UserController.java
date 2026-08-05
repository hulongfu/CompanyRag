package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.R;
import com.company.rag.common.model.UserDTO;
import com.company.rag.tenant.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    
    private final UserService userService;
    
    /**
     * 1. 创建用户
     */
    @PostMapping
    @AuditLog(actionType = "CREATE_USER", targetType = "user", 
              detail = "'创建用户：' + #request.username")
    public R<UserDTO.UserResponse> create(@RequestBody @Validated UserDTO.CreateRequest request) {
        try {
            log.info("接收到创建用户请求：username={}", request.getUsername());
            UserDTO.UserResponse response = userService.createUser(request);
            return R.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("创建用户失败：{}", e.getMessage());
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("创建用户异常", e);
            return R.fail(500, "创建用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 2. 查询用户列表 (支持筛选)
     */
    @GetMapping("/list")
    public R<List<UserDTO.UserResponse>> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String username) {
        try {
            log.debug("查询用户列表：role={}, tenantId={}, status={}, username={}", 
                    role, tenantId, status, username);
            List<UserDTO.UserResponse> users = userService.queryUserList(role, tenantId, status, username);
            return R.ok(users);
        } catch (Exception e) {
            log.error("查询用户列表异常", e);
            return R.fail(500, "查询用户列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 3. 查询用户详情
     */
    @GetMapping("/{id}")
    public R<UserDTO.UserDetailResponse> getById(@PathVariable Long id) {
        try {
            log.debug("查询用户详情：id={}", id);
            UserDTO.UserDetailResponse user = userService.getUserById(id);
            if (user == null) {
                return R.fail(404, "用户不存在");
            }
            return R.ok(user);
        } catch (Exception e) {
            log.error("查询用户详情异常", e);
            return R.fail(500, "查询用户详情失败：" + e.getMessage());
        }
    }
    
    /**
     * 4. 更新用户
     */
    @PutMapping("/{id}")
    @AuditLog(actionType = "UPDATE_USER", targetType = "user", 
              targetId = "#id", detail = "'更新用户：ID=' + #id")
    public R<UserDTO.UserResponse> update(@PathVariable Long id, 
                                          @RequestBody @Validated UserDTO.UpdateRequest request) {
        try {
            log.info("接收到更新用户请求：id={}, username={}", id, request.getUsername());
            UserDTO.UserResponse response = userService.updateUser(id, request);
            return R.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("更新用户失败：{}", e.getMessage());
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("更新用户异常", e);
            return R.fail(500, "更新用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 5. 删除用户
     */
    @DeleteMapping("/{id}")
    @AuditLog(actionType = "DELETE_USER", targetType = "user", 
              targetId = "#id", detail = "'删除用户：ID=' + #id")
    public R<Boolean> delete(@PathVariable Long id) {
        try {
            log.info("接收到删除用户请求：id={}", id);
            boolean success = userService.deleteUser(id);
            if (!success) {
                return R.fail(404, "用户不存在");
            }
            return R.ok(true);
        } catch (Exception e) {
            log.error("删除用户异常", e);
            return R.fail(500, "删除用户失败：" + e.getMessage());
        }
    }
}
