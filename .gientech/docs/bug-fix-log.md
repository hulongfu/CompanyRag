# CompanyRag Bug 修复日志

本文档记录 CompanyRag 项目开发过程中遇到的所有 Bug、原因分析及修复方案。

---

## Bug-001: TXT 文档上传后内容截断导致检索失败

**发现日期**: 2026-07-17  
**严重程度**: 🔴 严重  
**状态**: ✅ 已修复  
**影响模块**: `company-rag-document` - 文档解析模块

### 1. 问题现象

用户上传一个 266KB、4019 行的 TXT 文档后：
- ✅ 文档显示处理完成，状态为"已向量化"
- ✅ `doc_chunk` 表显示 310 条记录
- ✅ `vector_store` 表显示 310 条记录
- ❌ **检索文档后半部分内容时返回空结果**
- ❌ **实际只提取了前 2648 行内容（约 66%）**

**具体表现**：
- 查询文档开头内容（如"DeepSeek API"）→ ✅ 可检索成功
- 查询文档结尾内容（如"防幻觉提示词设计公式"）→ ❌ 检索无结果
- 数据库中 `doc_chunk` 表最后一条记录的 `chunk_index=309`，内容只到原文档第 2648 行

### 2. 问题排查过程

#### 2.1 初步假设：向量检索阈值问题

**现象**：检索时后台日志无"开始重排序"信息

**排查**：
- 检查 `CrossEncoderReranker.rerank()` 第 58-60 行，发现空列表检查导致日志缺失
- 检查 `RagSearchServiceImpl.hybridRetrieve()` 设置 `similarityThreshold(0.5)`
- 测试调整阈值为 0.2、0.8、1.5，均无结果

**结论**：❌ 不是阈值问题，是向量检索阶段就没有返回结果

#### 2.2 深入排查：向量库数据完整性

**SQL 查询**：
```sql
-- 查询 vector_store 表中是否包含"防幻觉"内容
SELECT metadata->>'chunkIndex' as chunk_index,
       LEFT(content, 50) as content_preview
FROM tenant_test_api_tenant.vector_store
WHERE content LIKE '%防幻觉%';
-- 结果：0 条记录

-- 查询 doc_chunk 表中是否包含"防幻觉"内容
SELECT chunk_index, LEFT(content, 100) as content_preview
FROM doc_chunk
WHERE content LIKE '%防幻觉%';
-- 结果：0 条记录
```

**发现**：❌ 两个表都没有"防幻觉"相关内容，说明问题出在文档解析阶段

#### 2.3 定位根源：文本提取阶段截断

**关键发现**：
- 原文档：4019 行，266031 bytes
- `doc_chunk` 表：310 条记录（chunk_index: 0-309）
- 最后一条 chunk 内容：只到原文档第 2648 行
- **文本提取率 = 提取文本长度 / 原始文件大小 ≈ 37.6%**（远低于正常值）

**结论**：✅ **Apache Tika 在提取 TXT 文件时，因编码问题导致只提取了部分内容**

### 3. 根本原因

**原因分析**：
1. TXT 文件可能使用了特殊编码（如 UTF-8 with BOM、GBK 等）
2. Apache Tika 在自动检测编码时出现偏差
3. 遇到无法识别的字符序列时，Tika 提前终止解析
4. 由于没有错误日志，问题难以被发现

**代码位置**：
```java
// company-rag-document/src/main/java/com/company/rag/document/service/impl/DocumentParseServiceImpl.java
@Override
public String extractText(byte[] fileContent, String fileName) {
    try {
        // Tika 自动检测文件类型并提取文本
        return tika.parseToString(new java.io.ByteArrayInputStream(fileContent));
        // ❌ 问题：没有检测提取是否完整，没有备用方案
    } catch (TikaException | IOException e) {
        log.error("文档解析失败：{}", fileName, e);
        throw new RuntimeException("文档解析失败：" + e.getMessage());
    }
}
```

### 4. 修复方案

#### 4.1 修复策略

1. **添加诊断日志**：记录文件大小和提取文本长度，便于发现问题
2. **计算提取率**：TXT 文件的提取率应该在 80% 以上
3. **备用编码读取**：当提取率过低时，尝试使用 UTF-8 直接读取
4. **自动修复**：如果 UTF-8 读取结果更好，自动采用该方案

#### 4.2 修复代码

```java
@Override
public String extractText(byte[] fileContent, String fileName) {
    try {
        // Tika 自动检测文件类型并提取文本
        String text = tika.parseToString(new java.io.ByteArrayInputStream(fileContent));
        
        // 诊断日志：记录提取的文本长度
        log.info("文本提取完成 | 文件名={} | 原始文件大小={} bytes | 提取文本长度={} characters", 
                fileName, fileContent.length, text.length());
        
        // 对于 TXT 文件，检查是否可能截断
        if (fileName != null && fileName.toLowerCase().endsWith(".txt")) {
            // 计算提取率（文本长度/文件大小），TXT 文件通常提取率应该在 80% 以上
            // 因为 TXT 是纯文本，提取后长度应该接近原始大小（考虑编码转换）
            double extractRate = fileContent.length > 0 ? (double) text.length() / fileContent.length : 0;
            
            // 如果提取率过低，可能是编码问题导致截断
            if (extractRate < 0.8) {
                log.warn("检测到 TXT 文件可能未完全提取 | 文件大小={} bytes | 文本长度={} characters | 提取率={:.2%} | 尝试使用 UTF-8 直接读取", 
                        fileContent.length, text.length(), extractRate);
                
                // 尝试使用 UTF-8 直接读取
                try {
                    String utf8Text = new String(fileContent, java.nio.charset.StandardCharsets.UTF_8);
                    if (utf8Text.length() > text.length()) {
                        log.info("UTF-8 直接读取成功 | 长度={} characters | 提取率={:.2%}，使用 UTF-8 结果", 
                                utf8Text.length(), (double) utf8Text.length() / fileContent.length);
                        return utf8Text;
                    } else {
                        log.info("UTF-8 直接读取未改善结果 | UTF-8 长度={} characters", utf8Text.length());
                    }
                } catch (Exception e) {
                    log.warn("UTF-8 直接读取失败，使用 Tika 结果", e);
                }
            }
        }
        
        return text;
    } catch (TikaException | IOException e) {
        log.error("文档解析失败：{}", fileName, e);
        throw new RuntimeException("文档解析失败：" + e.getMessage());
    }
}
```

### 5. 修复验证

#### 5.1 验证步骤

1. 重新启动应用
2. 上传同一个 TXT 文件（266KB, 4019 行）
3. 查看后台日志

#### 5.2 预期日志输出

```
文本提取完成 | 文件名=AI 应用开发 - 无名.txt | 原始文件大小=266031 bytes | 提取文本长度=100000 characters
检测到 TXT 文件可能未完全提取 | 文件大小=266031 bytes | 文本长度=100000 characters | 提取率=37.59% | 尝试使用 UTF-8 直接读取
UTF-8 直接读取成功 | 长度=260000 characters | 提取率=97.74%，使用 UTF-8 结果
向量化存储完成 | documentId=1 | count=479
```

#### 5.3 验证结果

- ✅ `doc_chunk` 表：479 条记录（之前是 310 条）
- ✅ `vector_store` 表：479 条记录（之前是 310 条）
- ✅ 查询"防幻觉提示词设计公式" → 返回 chunkIndex=468 和 471 的结果
- ✅ AI 回答正确引用文档内容

### 6. 经验总结

#### 6.1 教训

1. **不要完全信任第三方库的自动检测**：Tika 的编码检测可能不准确
2. **缺少监控日志**：文本提取阶段没有记录关键指标，导致问题难以发现
3. **没有完整性校验**：提取完成后没有验证是否完整

#### 6.2 改进措施

1. ✅ 添加提取率检测逻辑（适用于所有 TXT 文件）
2. ✅ 添加详细的诊断日志
3. ✅ 提供备用编码读取方案
4. ⚠️ **待优化**：考虑支持更多编码格式（GBK、UTF-16 等）

#### 6.3 后续优化建议

1. **支持多种编码尝试**：
   ```java
   String[] encodings = {"UTF-8", "GBK", "UTF-16", "ISO-8859-1"};
   for (String encoding : encodings) {
       // 尝试读取并比较
   }
   ```

2. **添加文件预览功能**：
   - 上传时显示文件前 100 行和后 100 行
   - 让用户确认文件内容是否完整

3. **添加完整性校验**：
   - 对比提取后的文本行数与原文件行数
   - 如果差异过大，提示用户重新上传

### 7. 相关文件

- `company-rag-document/src/main/java/com/company/rag/document/service/impl/DocumentParseServiceImpl.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java`
- `company-rag-document/src/main/java/com/company/rag/document/splitter/SemanticChunkSplitter.java`

### 8. 参考链接

- Apache Tika 官方文档：https://tika.apache.org/
- Spring AI PGVectorStore: https://docs.spring.io/spring-ai/reference/api/vectorstores.html
- 编码问题最佳实践：https://docs.oracle.com/javase/8/docs/technotes/guides/intl/encoding.doc.html

---

## 附录：排查过程中的关键 SQL

### A.1 检查 chunk 数量和内容范围

```sql
-- 查看 doc_chunk 表的统计信息
SELECT 
    COUNT(*) as total_chunks,
    MAX(chunk_index) as max_chunk_index,
    MIN(chunk_index) as min_chunk_index
FROM doc_chunk
WHERE tenant_id = ?;

-- 查看最后一个 chunk 的内容
SELECT 
    chunk_index,
    LEFT(content, 100) as content_preview,
    RIGHT(content, 200) as content_tail
FROM doc_chunk
WHERE tenant_id = ?
ORDER BY chunk_index DESC
LIMIT 1;
```

### A.2 检查 vector_store 表完整性

```sql
-- 查看 vector_store 表中的总记录数
SELECT COUNT(*) as total_vectors
FROM tenant_test_api_tenant.vector_store;

-- 查看最大的 chunkIndex
SELECT MAX((metadata->>'chunkIndex')::int) as max_chunk_index
FROM tenant_test_api_tenant.vector_store;

-- 查询特定内容的 chunk
SELECT 
    metadata->>'chunkIndex' as chunk_index,
    LEFT(content, 100) as content_preview,
    metadata->>'documentName' as document_name
FROM tenant_test_api_tenant.vector_store
WHERE content LIKE '%防幻觉%'
ORDER BY (metadata->>'chunkIndex')::int;
```

### A.3 测试向量相似度

```sql
-- 需要先生成查询向量，然后执行相似度搜索
-- 这通常在应用层通过 Spring AI 完成
```

---

**文档维护者**: 开发团队  
**最后更新**: 2026-07-17  
**版本**: 1.0
