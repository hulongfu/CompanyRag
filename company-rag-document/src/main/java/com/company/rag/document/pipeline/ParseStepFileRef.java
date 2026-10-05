package com.company.rag.document.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析步骤（不可重试）：
 * 校验已落盘的临时文件引用存在且可读。若文件缺失（如被清理）则直接失败，
 * 不再重试——文件已不在，重跑无意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseStepFileRef implements DocumentPipelineStep {

    @Override
    public String name() {
        return "PARSE";
    }

    @Override
    public void execute(PipelineTask task) {
        String fileRef = task.getFileRef();
        if (fileRef == null || fileRef.isBlank()) {
            throw new IllegalArgumentException("任务缺少临时文件引用（fileRef）");
        }
        if (!Files.isRegularFile(Path.of(fileRef))) {
            // 解析步骤不重试：文件已不存在，重跑也无法恢复
            throw new IllegalStateException("临时文件不存在或不可读：" + fileRef);
        }
        log.debug("解析步骤通过文件引用校验 | taskId={} | file={}", task.getTaskId(), fileRef);
    }
}