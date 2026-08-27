# 安全清理报告：Git 历史中的密钥泄露

**报告日期：** 2026-08-27  
**严重级别：** 🔴 严重（多重安全漏洞）  
**清理状态：** ⚠️ 部分完成（当前文件已清理，历史清理待执行）

---

## 问题概述

虽然 `.env` 文件已从 Git 历史中删除（`git log --all --oneline -- .env` 无输出），但**真实密钥值仍然嵌入在多个文件和历史提交中**。

---

## 已发现的密钥泄露

### 1. JWT_SECRET 泄露

**密钥值：** `HpuBmoiAE+WW0YyIayX8shBc72a1ZuDYrXITXHpyghU=`

**泄露位置：**
| 文件 | 状态 | 说明 |
|------|------|------|
| `.env.example` | ✅ 已清理 | 已替换为占位符 `<your-base64-encoded-jwt-secret-here>` |
| `docs/security-fixes/2026-08-27-jwt-secret-leak-fix.md` | ✅ 已脱敏 | 已替换为 `<REDACTED>` 标记 |
| **Git 历史提交** | ❌ **待清理** | 仍存在于多个历史提交中 |

**影响：** 攻击者可使用此密钥伪造任意租户的 JWT Token，完全绕过认证系统。

---

### 2. Redis 密码泄露

**密码值：** `[REDACTED-REDIS-PASSWORD]`

**泄露位置：**
| 文件 | 状态 | 说明 |
|------|------|------|
| `application-dev.yml` | ⚠️ 默认值 | 作为环境变量默认值存在 |
| `application-test.yml` | ⚠️ 默认值 | 作为环境变量默认值存在 |
| `.gientech/docs/superpowers/plans/2026-07-26-rerank-performance-optimization.md` | ❌ 未清理 | 文档中的示例配置 |
| 中文文件名文档 | ❌ 未清理 | 部署文档中的示例命令 |
| **Git 历史提交** | ❌ **待清理** | 存在于多个历史提交中 |

**影响：** 如果 Redis 暴露在公网，攻击者可直接访问缓存数据。

---

### 3. API Key 泄露

**密钥值：** `[REDACTED-API-KEY]`

**泄露位置：**
| 文件 | 状态 | 说明 |
|------|------|------|
| `.gientech/wiki/基础设置与中间件/环境变量配置.md` | ❌ 未清理 | Wiki 文档中的示例 |
| **Git 历史提交** | ❌ **待清理** | 存在于多个历史提交中 |

**影响：** 可能导致 API 配额被盗用或产生费用。

---

## 已完成的清理工作

### Phase 1: 当前文件清理 ✅

1. **`.env.example`** - 已将 JWT_SECRET 替换为占位符
2. **`docs/security-fixes/2026-08-27-jwt-secret-leak-fix.md`** - 已脱敏所有真实密钥

### Phase 2: Git 历史清理 ⏳ 待执行

需要使用 `git filter-repo` 从所有历史提交中删除以下密钥：

1. `HpuBmoiAE+WW0YyIayX8shBc72a1ZuDYrXITXHpyghU=` (JWT_SECRET)
2. `[REDACTED-REDIS-PASSWORD]` (Redis 密码)
3. `[REDACTED-API-KEY]` (API Key)

---

## 建议的清理步骤

### 立即执行（高优先级）

1. **轮换所有已泄露的密钥** 🔴
   ```bash
   # 1. 生成新的 JWT_SECRET
   python -c "import base64; import secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
   
   # 2. 更新 .env 文件
   # 3. 更新 DashScope API Key
   # 4. 更新 Redis 密码
   # 5. 重新部署所有环境
   ```

2. **清理 Git 历史**
   ```bash
   # 创建备份
   git branch backup-before-secret-cleanup
   
   # 使用 git filter-repo 替换密钥
   git filter-repo --force \
     --replace-text <(echo "[REDACTED-JWT-SECRET]<REDACTED-JWT-SECRET>")
   
   # 或使用表达式文件
   git filter-repo --force --replace-text expressions.txt
   
   # 清理 reflog 和垃圾回收
   git reflog expire --expire=now --all
   git gc --prune=now --aggressive
   
   # 强制推送到远程
   git push --force origin main feature/*
   git push --force gitee main feature/*
   ```

3. **通知所有协作者**
   ```bash
   # 协作者需要重新 clone 或执行
   git fetch --all
   git reset --hard origin/main
   ```

### 中期改进（中优先级）

1. **更新配置文件默认值**
   - `application-dev.yml`: 移除 Redis 密码默认值或改为 `<your-redis-password>`
   - `application-test.yml`: 同上

2. **添加密钥强度校验**
   - 启动时检查 JWT_SECRET 是否从环境变量注入
   - 检查密钥长度和熵

3. **更新文档**
   - 所有示例配置使用占位符
   - 添加安全警告

### 长期改进（低优先级）

1. **集成密钥管理系统**
   - HashiCorp Vault
   - AWS Secrets Manager
   - Azure Key Vault

2. **实施密钥轮换机制**
   - 每 90 天自动轮换 JWT_SECRET
   - 密钥版本化管理

3. **添加安全扫描**
   - Git 预提交钩子检查密钥
   - CI/CD 集成密钥扫描（如 GitLeaks、TruffleHog）

---

## 验证清单

- [ ] 所有真实密钥已从当前文件删除
- [ ] Git 历史中不再包含真实密钥
- [ ] 所有环境已更新为新密钥
- [ ] 远程仓库已强制推送
- [ ] 所有协作者已重新同步
- [ ] 文档已更新为占位符
- [ ] 添加了密钥扫描机制

---

## 参考资源

- [Git 历史中删除敏感信息](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
- [git-filter-repo 文档](https://htmlpreview.github.io/?https://github.com/newren/git-filter-repo/blob/docs/html/git-filter-repo.html)
- [OWASP JWT 安全最佳实践](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)

---

**报告生成时间：** 2026-08-27  
**下次审查日期：** 2026-09-03（7 天后）
