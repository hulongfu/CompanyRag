# 用户管理功能设计文档

**日期:** 2026-08-04  
**作者:** CompanyRag Team  
**状态:** 待实现

---

## 1. 概述

### 1.1 背景
当前系统已有租户管理功能，但缺少用户管理界面。管理员需要能够管理用户账户，包括创建、编辑、删除用户，以及为用户分配租户和角色。

### 1.2 目标
- 添加用户管理界面，仅管理员可见
- 支持用户的增删改查操作
- 支持用户与多租户关联
- 支持角色分配 (admin/user/viewer)
- 删除用户时级联删除用户 - 租户关联数据

### 1.3 范围
**包含:**
- 用户管理前端界面 (index.html 新增标签页)
- 用户管理后端 API (Controller + Service + Mapper)
- 权限控制 (仅管理员访问)
- 审计日志记录

**不包含:**
- 用户自助修改密码功能
- 用户注册功能
- 角色权限细粒度控制

---

## 2. 架构设计

### 2.1 技术栈
- **前端:** Vue 3 + Element Plus (与现有租户管理一致)
- **后端:** Spring Boot 3.4 + Spring Security
- **数据库:** PostgreSQL 16 + PGVector
- **ORM:** MyBatis-Plus 3.5.9

### 2.2 模块结构
```
company-rag-web/
└── controller/
    └── UserController.java (新增)

company-rag-tenant/
├── model/
│   ├── User.java (已有)
│   └── UserTenantRel.java (已有)
├── mapper/
│   ├── UserMapper.java (已有)
│   └── UserTenantRelMapper.java (已有)
├── service/
│   └── UserService.java (新增接口)
└── service/impl/
    └── UserServiceImpl.java (新增实现)

company-rag-web/
└── model/
    └── UserDTO.java (新增 DTO)
```

### 2.3 数据流
```
前端 (Vue) → UserController → UserService → UserMapper/UserTenantRelMapper → Database
                ↓
          权限校验 (@PreAuthorize("hasRole('ADMIN')"))
```

---

## 3. 功能设计

### 3.1 用户管理界面

#### 3.1.1 导航栏
在 `index.html` 顶部导航栏添加"👤 用户"按钮，与"🏢 租户"并列：
```html
<button class="hdr-btn" 
        v-if="role === 'admin'"
        :class="{ active: currentTab === 'user' }" 
        @click="currentTab = 'user'">
    👤 用户
</button>
```

#### 3.1.2 用户管理区域布局
```
┌─────────────────────────────────────────────────────┐
│  👤 用户管理                                         │
├─────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────┐  │
│  │ 筛选条件                                       │  │
│  │ [角色下拉] [租户下拉] [状态下拉] [搜索框] [查询]│  │
│  └───────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────┐  │
│  │ 创建用户                                       │  │
│  │ 用户名：[_____] 密码：[_____]                  │  │
│  │ 显示名：[_____] 邮箱：[_____]                  │  │
│  │ 角色：[下拉] 关联租户：[多选下拉]               │  │
│  │ [创建用户] [重置]                              │  │
│  └───────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────┐  │
│  │ 用户列表                     [🔄 刷新]         │  │
│  ├───────────────────────────────────────────────┤  │
│  │ ID | 用户名 | 显示名 | 邮箱 | 角色 | 租户 |...│  │
│  │    | 操作：[编辑] [删除]                       │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 3.2 用户列表功能

#### 3.2.1 筛选条件
- **角色筛选:** admin / user / viewer
- **租户筛选:** 显示该租户下的所有用户
- **状态筛选:** 启用 / 禁用
- **用户名搜索:** 模糊匹配

#### 3.2.2 表格列定义
| 列名 | 字段 | 宽度 | 说明 |
|------|------|------|------|
| ID | id | 80px | 用户 ID |
| 用户名 | username | 150px | 登录用户名 |
| 显示名 | displayName | 150px | 用户昵称 |
| 邮箱 | email | 200px | 邮箱地址 |
| 角色 | role | 100px | admin/user/viewer |
| 关联租户 | tenantNames | 250px | 租户名称列表，逗号分隔 |
| 状态 | status | 100px | 启用/禁用标签 |
| 操作 | - | 200px | 编辑、删除按钮 |

### 3.3 创建用户功能

#### 3.3.1 表单字段
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| 用户名 | text | ✅ | 登录用户名，需唯一 |
| 密码 | password | ✅ | 初始密码 |
| 显示名 | text | ✅ | 用户昵称 |
| 邮箱 | email | ❌ | 可选 |
| 角色 | select | ✅ | admin/user/viewer |
| 关联租户 | multi-select | ✅ | 至少选择一个租户 |

#### 3.3.2 业务逻辑
1. 验证用户名唯一性
2. 密码加密 (BCrypt)
3. 插入 `sys_user` 表
4. 插入 `sys_user_tenant_rel` 表 (多条记录)
5. 记录审计日志

### 3.4 编辑用户功能

#### 3.4.1 可编辑字段
- 显示名、邮箱、角色、关联租户：**可修改**
- 用户名：**可修改** (根据需求确认)
- 密码：**留空表示不修改**，否则修改

#### 3.4.2 业务逻辑
1. 查询用户是否存在
2. 如果密码不为空，重新加密
3. 更新 `sys_user` 表
4. 删除旧的 `sys_user_tenant_rel` 记录
5. 插入新的 `sys_user_tenant_rel` 记录
6. 记录审计日志

### 3.5 删除用户功能

#### 3.5.1 确认提示
简单确认："确定要删除用户 XXX 吗？"

#### 3.5.2 业务逻辑
1. 查询用户是否存在
2. 删除 `sys_user_tenant_rel` 记录 (级联)
3. 删除 `sys_user` 记录
4. 记录审计日志

---

## 4. API 设计

### 4.1 REST API 列表

| 方法 | 路径 | 描述 | 权限 | 审计日志 |
|------|------|------|------|----------|
| POST | /api/user | 创建用户 | ADMIN | ✅ CREATE_USER |
| GET | /api/user/list | 查询用户列表 (支持筛选) | ADMIN | ❌ |
| GET | /api/user/{id} | 查询用户详情 | ADMIN | ❌ |
| PUT | /api/user/{id} | 更新用户信息 | ADMIN | ✅ UPDATE_USER |
| DELETE | /api/user/{id} | 删除用户 | ADMIN | ✅ DELETE_USER |

### 4.2 请求/响应格式

#### 4.2.1 创建用户请求
```http
POST /api/user
Content-Type: application/json

{
  "username": "zhangsan",
  "password": "123456",
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "role": "user",
  "tenantIds": [1, 2]
}
```

#### 4.2.2 更新用户请求
```http
PUT /api/user/{id}
Content-Type: application/json

{
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "role": "admin",
  "tenantIds": [1, 3],
  "password": ""  // 留空表示不修改密码
}
```

#### 4.2.3 用户列表响应
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "username": "zhangsan",
      "displayName": "张三",
      "email": "zhangsan@example.com",
      "role": "user",
      "status": 1,
      "tenantIds": [1, 2],
      "tenantNames": ["默认租户", "公司 A"],
      "createTime": "2026-08-02 10:00:00"
    }
  ]
}
```

#### 4.2.4 用户详情响应
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "username": "zhangsan",
    "displayName": "张三",
    "email": "zhangsan@example.com",
    "role": "user",
    "status": 1,
    "tenantIds": [1, 2],
    "createTime": "2026-08-02 10:00:00"
  }
}
```

---

## 5. 数据库设计

### 5.1 现有表结构

#### sys_user (用户表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| username | VARCHAR | 用户名 (唯一) |
| password | VARCHAR | 加密密码 |
| displayName | VARCHAR | 显示名 |
| email | VARCHAR | 邮箱 |
| role | VARCHAR | 角色 (admin/user/viewer) |
| status | INTEGER | 状态 (1=启用，0=禁用) |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |

#### sys_user_tenant_rel (用户 - 租户关联表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| tenant_id | BIGINT | 租户 ID |

### 5.2 索引设计
现有索引已足够：
- `sys_user.username` - 唯一性验证
- `sys_user_tenant_rel.user_id` - 关联查询
- `sys_user_tenant_rel.tenant_id` - 按租户筛选

---

## 6. 权限与安全设计

### 6.1 后端权限控制

使用 Spring Security + `@PreAuthorize` 注解：

```java
@RestController
@RequestMapping("/api/user")
@PreAuthorize("hasRole('ADMIN')")  // 类级别限制
public class UserController {
    
    @PostMapping
    @AuditLog(actionType = "CREATE_USER", targetType = "user", 
              detail = "'创建用户：' + #request.username")
    public R<UserDTO.UserResponse> create(...) { }
    
    @DeleteMapping("/{id}")
    @AuditLog(actionType = "DELETE_USER", targetType = "user", 
              targetId = "#id", detail = "'删除用户：ID=' + #id")
    public R<Boolean> delete(@PathVariable Long id) { }
}
```

### 6.2 前端权限控制

#### 6.2.1 UI 级别隐藏
```html
<button class="hdr-btn" 
        v-if="role === 'admin'"
        :class="{ active: currentTab === 'user' }" 
        @click="currentTab = 'user'">
    👤 用户
</button>
```

#### 6.2.2 路由守卫
```javascript
function switchToUserTab() {
    if (role.value !== 'admin') {
        ElementPlus.ElMessage.error('只有管理员才能访问用户管理');
        return;
    }
    currentTab.value = 'user';
}
```

### 6.3 安全注意事项

#### 6.3.1 密码安全
- 密码必须使用 BCrypt 加密存储
- 使用项目现有的 `PasswordGenerator` 工具类
- 禁止明文传输或存储密码

#### 6.3.2 审计日志
- 创建/更新/删除用户必须记录审计日志
- 记录操作人、操作类型、目标用户、时间戳

#### 6.3.3 数据隔离
- 管理员可以看到所有租户的用户 (合理的管理需求)
- 查询操作仍需遵守租户隔离规则

---

## 7. 事务设计

### 7.1 事务边界

**创建用户:**
```java
@Transactional
public UserResponse createUser(CreateRequest request) {
    // 1. 验证用户名唯一性
    // 2. 密码加密
    // 3. 插入 sys_user
    // 4. 插入 sys_user_tenant_rel
    // 5. 记录审计日志
}
```

**更新用户:**
```java
@Transactional
public UserResponse updateUser(Long id, UpdateRequest request) {
    // 1. 查询用户
    // 2. 更新密码 (如有)
    // 3. 更新 sys_user
    // 4. 删除旧的关联记录
    // 5. 插入新的关联记录
    // 6. 记录审计日志
}
```

**删除用户:**
```java
@Transactional
public boolean deleteUser(Long id) {
    // 1. 查询用户
    // 2. 删除 sys_user_tenant_rel
    // 3. 删除 sys_user
    // 4. 记录审计日志
}
```

---

## 8. 前端交互设计

### 8.1 创建用户流程
```
1. 填写表单
2. 点击"创建用户"按钮
3. 前端验证 (必填字段、租户至少选择一个)
4. 调用 POST /api/user
5. 成功后：提示成功 + 刷新列表 + 重置表单
6. 失败后：提示错误信息
```

### 8.2 编辑用户流程
```
1. 点击"编辑"按钮
2. 弹出对话框，回显用户信息
3. 修改字段 (密码留空表示不修改)
4. 点击"保存"按钮
5. 调用 PUT /api/user/{id}
6. 成功后：提示成功 + 刷新列表 + 关闭对话框
```

### 8.3 删除用户流程
```
1. 点击"删除"按钮
2. 弹出确认框："确定要删除用户 XXX 吗？"
3. 点击"确定删除"
4. 调用 DELETE /api/user/{id}
5. 成功后：提示成功 + 刷新列表
```

---

## 9. 错误处理

### 9.1 常见错误码

| 错误码 | 说明 | 处理 |
|--------|------|------|
| 400 | 请求参数错误 | 提示具体字段错误 |
| 401 | 未认证 | 跳转登录页 |
| 403 | 无权限 | 提示"只有管理员才能操作" |
| 404 | 用户不存在 | 提示"用户不存在" |
| 409 | 用户名已存在 | 提示"用户名已被使用" |
| 500 | 服务器错误 | 提示"系统错误，请稍后重试" |

### 9.2 前端错误处理
```javascript
try {
    const json = await apiRequest('/api/user', {
        method: 'POST',
        body: JSON.stringify(formData)
    });
    if (json && json.code === 200) {
        ElementPlus.ElMessage.success('创建成功');
    } else {
        ElementPlus.ElMessage.error('创建失败：' + (json.msg || ''));
    }
} catch(e) {
    ElementPlus.ElMessage.error('操作失败：' + e.message);
}
```

---

## 10. 测试计划

### 10.1 单元测试
- UserService 测试：创建、更新、删除、查询
- 边界条件：用户名重复、密码为空、租户为空

### 10.2 集成测试
- API 接口测试：所有 REST 端点
- 权限测试：非管理员访问应返回 403
- 事务测试：删除用户时关联数据是否级联删除

### 10.3 前端测试
- UI 渲染测试：用户列表、表单
- 交互测试：创建、编辑、删除流程
- 权限测试：非管理员不应看到用户管理入口

---

## 11. 验收标准

### 11.1 功能验收
- [ ] 管理员能看到用户管理标签页
- [ ] 非管理员看不到用户管理标签页
- [ ] 能创建用户并关联租户
- [ ] 能编辑用户信息 (包括修改密码)
- [ ] 能删除用户 (级联删除关联数据)
- [ ] 能按角色/租户/状态/用户名筛选用户
- [ ] 用户列表显示正确的租户名称

### 11.2 安全验收
- [ ] 非管理员调用 API 返回 403
- [ ] 密码加密存储
- [ ] 审计日志正确记录

### 11.3 性能验收
- [ ] 用户列表加载时间 < 1 秒 (1000 条数据内)
- [ ] 创建/更新/删除操作 < 500ms

---

## 12. 待办事项

### 12.1 后端开发
- [ ] 创建 UserDTO (CreateRequest, UpdateRequest, UserResponse, UserDetailResponse)
- [ ] 创建 UserService 接口
- [ ] 创建 UserServiceImpl 实现
- [ ] 创建 UserController
- [ ] 编写单元测试

### 12.2 前端开发
- [ ] 在 index.html 添加用户管理标签页
- [ ] 实现用户列表展示
- [ ] 实现创建用户表单
- [ ] 实现编辑用户对话框
- [ ] 实现删除确认功能
- [ ] 实现筛选功能

### 12.3 测试与部署
- [ ] 编写集成测试
- [ ] 手动测试所有功能
- [ ] 部署到测试环境
- [ ] 验收测试

---

## 13. 风险与注意事项

### 13.1 技术风险
- **用户名修改:** 如果允许修改用户名，需要确认是否影响其他模块 (如审计日志、会话管理等)
- **租户关联:** 删除用户时需要确保级联删除关联数据，避免脏数据

### 13.2 业务风险
- **管理员删除自己:** 需要防止管理员删除自己的账户
- **最后一个管理员:** 需要确保系统中至少有一个管理员用户

### 13.3 安全措施
- 建议添加"禁止删除当前登录用户"的检查
- 建议添加"至少保留一个管理员"的检查

---

## 14. 附录

### 14.1 相关文件
- `company-rag-tenant/src/main/java/com/company/rag/tenant/model/User.java`
- `company-rag-tenant/src/main/java/com/company/rag/tenant/model/UserTenantRel.java`
- `company-rag-web/src/main/resources/templates/index.html`

### 14.2 参考资料
- Spring Security 文档
- Element Plus 文档
- 项目现有租户管理实现
