package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.document.entity.Document;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档管道服务实现。
 *
 * <p>上传入口改为：落盘临时文件 → 建文档记录 → 写 PENDING 状态 → 提交异步管道 → 返回 taskId，
 * 不再同步解析，请求线程立即返回。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineServiceImpl implements DocumentPipelineService {

    private final DocumentMapper documentMapper;
    private final DocumentPipelineStateMapper stateMapper;
    private final AsyncDocumentPipelineExecutor executor;

    @Value("${document.pipeline.file-temp-dir:./data/uploads}")
    private String fileTempDir;

    @Override
    public UUID submitUpload(MultipartFile file, Long tenantId) {
        UUID taskId = UUID.randomUUID();

        // 1. 落盘临时文件（失败即抛，由 Controller 全局异常处理转统一错误码）
        Path tempFile;
        try {
            Path dir = Paths.get(fileTempDir);
            Files.createDirectories(dir);
            String suffix = suffixOf(file.getOriginalFilename());
            tempFile = Files.createTempFile(dir, "upl-", suffix);
            file.transferTo(tempFile.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("上传文件落盘失败：" + e.getMessage(), e);
        }

        // 2. 建文档记录（PENDING）
        Document doc = new Document();
        doc.setTenantId(tenantId);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileSize(file.getSize());
        doc.setStatus(0); // 待处理
        doc.setFilePath(tempFile.toAbsolutePath().toString());
        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.contains(".")) {
            doc.setFileType(originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase());
        }
        documentMapper.insert(doc);

        // 3. 写 PENDING 状态
        // step 记为首个待执行步骤名（与 ParseStepFileRef.name() 一致，列 NOT NULL 不可为 null）
        DocumentPipelineState state = new DocumentPipelineState();
        state.setTaskId(taskId);
        state.setDocumentId(doc.getId());
        state.setTenantId(tenantId);
        state.setStep("PARSE");
        state.setStatus(PipelineStatus.PENDING.name());
        state.setRetryCount(0);
        state.setCreateTime(LocalDateTime.now());
        state.setUpdateTime(LocalDateTime.now());
        stateMapper.insert(state);

        // 4. 构造任务并提交（快照捕获请求线程租户上下文）
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();
        String schema = snapshot.getSchema() != null ? snapshot.getSchema() : TenantContext.getSchema();
        PipelineTask task = PipelineTask.builder()
                .taskId(taskId)
                .documentId(doc.getId())
                .tenantId(tenantId)
                .expectedSchema(schema)
                .fileRef(tempFile.toAbsolutePath().toString())
                .fileName(doc.getFileName())
                .tenantSnapshot(snapshot)
                .build();
        executor.submit(task);

        log.info("文档管道任务已提交 | taskId={} | documentId={} | schema={}", taskId, doc.getId(), schema);
        return taskId;
    }

    @Override
    public PipelineStatusVO getStatus(UUID taskId, Long tenantId) {
        DocumentPipelineState state = stateMapper.selectOne(
                new LambdaQueryWrapper<DocumentPipelineState>()
                        .eq(DocumentPipelineState::getTaskId, taskId)
                        .eq(DocumentPipelineState::getTenantId, tenantId));
        if (state == null) {
            return null; // 非本租户任务或不存在：不泄露他租户信息
        }
        PipelineStatusVO vo = new PipelineStatusVO();
        vo.setStatus(state.getStatus());
        vo.setStep(state.getStep());
        vo.setErrorStep(state.getErrorStep());
        vo.setErrorMsg(state.getErrorMsg());
        vo.setRetryCount(state.getRetryCount());
        return vo;
    }

    @Override
    public void retryStep(UUID taskId, Long tenantId) {
        DocumentPipelineState state = stateMapper.selectOne(
                new LambdaQueryWrapper<DocumentPipelineState>()
                        .eq(DocumentPipelineState::getTaskId, taskId)
                        .eq(DocumentPipelineState::getTenantId, tenantId));
        if (state == null) {
            throw new IllegalStateException("任务不存在或不属于当前租户");
        }
        if (!PipelineStatus.FAILED.name().equals(state.getStatus())) {
            throw new IllegalStateException("仅 FAILED 状态可补跑，当前状态=" + state.getStatus());
        }

        // 重建任务：fileRef 取文档落盘路径，快照在请求线程捕获
        Document doc = documentMapper.selectById(state.getDocumentId());
        String fileRef = doc != null ? doc.getFilePath() : null;
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();
        String schema = snapshot.getSchema() != null ? snapshot.getSchema() : TenantContext.getSchema();

        // 更新为 PENDING 交由模板重放
        state.setStatus(PipelineStatus.PENDING.name());
        state.setErrorStep(null);
        state.setErrorMsg(null);
        state.setUpdateTime(LocalDateTime.now());
        stateMapper.updateById(state);

        PipelineTask task = PipelineTask.builder()
                .taskId(taskId)
                .documentId(state.getDocumentId())
                .tenantId(tenantId)
                .expectedSchema(schema)
                .fileRef(fileRef)
                .fileName(null)
                .tenantSnapshot(snapshot)
                .build();
        executor.submit(task);
        log.info("文档管道任务补跑已提交 | taskId={} | documentId={}", taskId, state.getDocumentId());
    }

    private String suffixOf(String fileName) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                return fileName.substring(dot); // 含点，如 ".txt"
            }
        }
        return "";
    }
}