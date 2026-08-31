# 下载工具设计文档

**创建时间**: 2026-08-31  
**优先级**: P0  
**状态**: 设计中

---

## 📋 需求背景

### 问题现状

根据 2026-08-31 的日志分析：
- **45 行 API 文档** → Agent 执行 **15 轮 LLM 调用** → 耗时 **4.7 分钟**
- **80% 时间浪费** 在解决 file-manager 技能的命令行参数传递问题
- **用户体验极差**：等待时间长、错误频发

### 核心需求

1. **简化文件下载流程**：Agent 只需传递内容和文件名
2. **封装复杂性**：内部处理编码、目录创建、特殊字符等
3. **提供下载链接**：用户可点击链接直接下载文件
4. **支持多场景**：文档、报告、代码、数据导出等

---

## 🎯 设计目标

| 目标 | 当前（file-manager） | 目标（download 工具） | 改进 |
|------|---------------------|----------------------|------|
| **LLM 轮次** | 15 轮 | 2-3 轮 | 减少 80%+ |
| **总耗时** | 4.7 分钟 | 20-30 秒 | 减少 90%+ |
| **Token 消耗** | 30,000+ | 3,000-4,000 | 减少 90%+ |
| **用户等待** | 无法接受 | 可接受 | ✅ |
| **错误率** | 高（命令行截断） | 低（内部封装） | ✅ |

---

## 🏗️ 架构设计

### 模块划分

```
company-rag-agent/
├── tool/
│   └── DownloadTool.java          # Agent 工具封装
├── service/
│   └── DownloadService.java       # 业务逻辑（文件写入 + 清理）
└── config/
    └── DownloadConfig.java        # 配置类（临时目录、过期时间等）

company-rag-web/
└── controller/
    └── DownloadController.java    # REST API（生成 + 下载）

company-rag-common/
└── response/
    └── DownloadResponse.java      # 统一响应对象
```

---

## 📐 API 设计

### 1. 生成下载链接（Agent 调用）

**接口**: `POST /api/tool/download`

**请求**:
```json
{
  "content": "# API 文档\n\n...",
  "filename": "api-doc.md",
  "contentType": "text/markdown"
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "downloadUrl": "/api/download/a3f8c2d9-1e5b-47f0-a6c8-d2e4b7f90123",
    "fileId": "a3f8c2d9-1e5b-47f0-a6c8-d2e4b7f90123",
    "filename": "api-doc.md",
    "contentType": "text/markdown",
    "size": 2048,
    "expiresAt": "2026-09-01T14:00:00Z",
    "createdAt": "2026-08-31T14:00:00Z"
  }
}
```

**Agent 工具方法签名**:
```java
@Tool(name = "download_file", description = "将内容写入文件并生成下载链接")
public String downloadFile(String content, String filename, String contentType);
```

---

### 2. 下载文件（用户点击）

**接口**: `GET /api/download/{fileId}`

**响应**:
- **Content-Type**: 根据文件类型自动设置
- **Content-Disposition**: `attachment; filename="api-doc.md"`
- **Body**: 文件二进制内容

**示例**:
```bash
GET /api/download/a3f8c2d9-1e5b-47f0-a6c8-d2e4b7f90123

Response Headers:
  Content-Type: text/markdown
  Content-Disposition: attachment; filename="api-doc.md"
  Content-Length: 2048

Response Body:
  # API 文档
  ...
```

---

### 3. 预览文件（可选，Markdown 格式）

**接口**: `GET /api/preview/{fileId}`

**响应**:
- **Content-Type**: `text/html`
- **Body**: 渲染后的 HTML（使用 marked.js 或类似库）

**说明**: P2 功能，初期可先实现基础下载，预览后续添加。

---

## 🗂️ 数据模型

### DownloadRecord（下载记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| tenant_id | Long | 租户 ID（多租户隔离） |
| user_id | Long | 用户 ID（可选，用于审计） |
| filename | String | 原始文件名 |
| file_path | String | 服务器存储路径 |
| content_type | String | MIME 类型 |
| size | Long | 文件大小（字节） |
| expires_at | LocalDateTime | 过期时间 |
| created_at | LocalDateTime | 创建时间 |
| downloaded_count | Integer | 下载次数 |

**说明**:
- 初期可使用**内存存储**（ConcurrentHashMap）+ 定时清理
- 后期可持久化到数据库（如果需要审计/统计）

---

## 🔧 核心功能实现

### 1. DownloadService

```java
@Service
public class DownloadService {
    
    /**
     * 生成下载文件
     * @param tenantId 租户 ID
     * @param content 文件内容
     * @param filename 文件名
     * @param contentType MIME 类型（可选）
     * @return 下载记录
     */
    public DownloadRecord createDownloadFile(
        Long tenantId, 
        String content, 
        String filename,
        String contentType
    );
    
    /**
     * 获取下载记录
     * @param fileId 文件 ID
     * @return 下载记录（不存在返回 null）
     */
    public DownloadRecord getDownloadFile(String fileId);
    
    /**
     * 删除下载文件（物理删除 + 记录删除）
     * @param fileId 文件 ID
     */
    public void deleteDownloadFile(String fileId);
    
    /**
     * 清理过期文件（定时任务调用）
     * @return 清理的文件数量
     */
    public int cleanupExpiredFiles();
}
```

---

### 2. DownloadTool（Agent 工具）

```java
@Tool
public class DownloadTool {
    
    @ToolMethod(name = "download_file", description = "将内容写入文件并生成下载链接")
    public String downloadFile(
        @ToolParam(description = "文件内容") String content,
        @ToolParam(description = "文件名，如 report.md") String filename,
        @ToolParam(description = "文件类型（可选）", required = false) String contentType
    ) {
        // 1. 获取当前租户 ID（从 ThreadLocal 或请求上下文）
        Long tenantId = TenantContext.getCurrentTenantId();
        
        // 2. 调用 Service 生成文件
        DownloadRecord record = downloadService.createDownloadFile(
            tenantId, content, filename, contentType
        );
        
        // 3. 返回下载链接（JSON 格式，方便 Agent 解析）
        return String.format("""
            文件已生成，下载信息：
            - 文件名：%s
            - 大小：%d 字节
            - 下载链接：%s
            - 过期时间：%s
            
            用户可点击下载链接获取文件。
            """,
            record.getFilename(),
            record.getSize(),
            record.getDownloadUrl(),
            record.getExpiresAt()
        );
    }
}
```

---

### 3. DownloadController

```java
@RestController
@RequestMapping("/api/tool")
public class DownloadController {
    
    @PostMapping("/download")
    public R<DownloadResponse> createDownload(
        @RequestBody DownloadRequest request,
        HttpServletRequest httpRequest
    ) {
        // 1. 从请求中获取租户 ID
        Long tenantId = TenantContext.getCurrentTenantId();
        
        // 2. 生成文件
        DownloadRecord record = downloadService.createDownloadFile(
            tenantId,
            request.getContent(),
            request.getFilename(),
            request.getContentType()
        );
        
        // 3. 返回响应
        return R.success(DownloadResponse.from(record));
    }
}

@RestController
@RequestMapping("/api/download")
public class DownloadFileController {
    
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> downloadFile(
        @PathVariable String fileId,
        HttpServletRequest httpRequest
    ) {
        // 1. 获取下载记录
        DownloadRecord record = downloadService.getDownloadFile(fileId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        
        // 2. 读取文件内容
        File file = new File(record.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        // 3. 增加下载次数
        downloadService.incrementDownloadCount(fileId);
        
        // 4. 返回文件
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(record.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename=\"" + record.getFilename() + "\"")
            .body(new FileSystemResource(file));
    }
}
```

---

## 🧹 清理策略

### 定时任务

```java
@Component
public class DownloadCleanupScheduler {
    
    @Autowired
    private DownloadService downloadService;
    
    /**
     * 每小时清理一次过期文件
     */
    @Scheduled(fixedRate = 3600000) // 1 小时
    public void cleanupExpiredFiles() {
        int cleaned = downloadService.cleanupExpiredFiles();
        log.info("清理过期下载文件：{} 个", cleaned);
    }
}
```

### 过期时间配置

```yaml
# application.yml
download:
  # 文件保留时间（小时）
  retention-hours: 24
  # 临时文件存储目录
  temp-dir: ${DOWNLOAD_TEMP_DIR:/tmp/company-rag-downloads}
  # 单个文件最大大小（MB）
  max-file-size: 50
```

---

## 🔒 安全考虑

### 1. 多租户隔离

- 每个租户的文件存储在不同子目录：`/downloads/{tenantId}/{fileId}`
- 下载时验证租户 ID 匹配

### 2. 文件名安全

- 禁止路径遍历（如 `../../../etc/passwd`）
- 只允许安全字符：`[a-zA-Z0-9._-]`
- 自动过滤危险字符

### 3. 文件大小限制

- 单个文件最大 50MB（可配置）
- 超过限制返回错误

### 4. 内容安全

- 不执行文件内容（纯存储）
- 下载时设置正确的 Content-Type

---

## 📊 性能优化

### 1. 大文件处理

- 超过 1MB 使用流式写入（避免内存溢出）
- 下载时使用 `FileInputStream` 流式传输

### 2. 并发控制

- 使用 `ConcurrentHashMap` 存储下载记录
- 文件写入使用同步锁（避免并发冲突）

### 3. 缓存策略

- 下载记录缓存到内存（快速查询）
- 文件内容不缓存（直接读磁盘）

---

## 🧪 测试场景

### 单元测试

1. ✅ 生成小文件（< 1KB）
2. ✅ 生成中等文件（100KB-1MB）
3. ✅ 生成大文件（> 1MB）
4. ✅ 文件名包含特殊字符
5. ✅ 中文文件名
6. ✅ 过期文件清理
7. ✅ 多租户隔离

### 集成测试

1. ✅ Agent 调用 download_file 工具
2. ✅ 用户点击下载链接
3. ✅ 文件内容正确性验证
4. ✅ 并发下载测试

---

## 📅 实施计划

| 阶段 | 任务 | 预计时间 | 状态 |
|------|------|---------|------|
| **Phase 1** | 设计文档 | 30 分钟 | ✅ |
| **Phase 2** | DownloadService 实现 | 2 小时 | ⏳ |
| **Phase 3** | DownloadController 实现 | 1.5 小时 | ⏳ |
| **Phase 4** | DownloadTool 实现 | 1 小时 | ⏳ |
| **Phase 5** | 单元测试 | 1.5 小时 | ⏳ |
| **Phase 6** | 集成测试 | 1 小时 | ⏳ |
| **Phase 7** | 文档更新 | 30 分钟 | ⏳ |
| **总计** | | **8 小时** | |

---

## 🎯 成功标准

1. **功能完整**：
   - ✅ Agent 可调用 download_file 工具
   - ✅ 用户可点击下载链接
   - ✅ 文件内容正确
   - ✅ 自动清理过期文件

2. **性能达标**：
   - ✅ 45 行 API 文档 → 2-3 轮 LLM → 20-30 秒完成
   - ✅ 支持 50MB 以内文件
   - ✅ 并发下载 10 个文件无错误

3. **用户体验**：
   - ✅ 下载链接可点击
   - ✅ 文件名正确
   - ✅ 浏览器可识别文件类型

---

## 🔄 后续优化（P2）

1. **在线预览**：Markdown 格式支持浏览器预览
2. **批量下载**：支持打包多个文件为 ZIP
3. **持久化**：下载记录存入数据库（审计/统计）
4. **分享功能**：生成分享链接（带密码/有效期）

---

**评审通过后开始实施 Phase 2-7**
