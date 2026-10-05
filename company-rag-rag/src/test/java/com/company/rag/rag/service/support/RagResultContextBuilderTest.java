package com.company.rag.rag.service.support;

import com.company.rag.rag.model.RagResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagResultContextBuilder} 单元测试 —— 覆盖正常 / 边界 / 异常场景。
 *
 * <p>验证从 chunks 重建 context 的规则：单块含来源与内容、多块以 "\n\n" 分隔、
 * null/空 chunks 返回空串、documentName 为 null 时回落 "未知"。</p>
 */
class RagResultContextBuilderTest {

    private RagResult.ChunkResult chunk(String name, String content) {
        RagResult.ChunkResult c = new RagResult.ChunkResult();
        c.setDocumentName(name);
        c.setContent(content);
        return c;
    }

    @Test
    void 单块_含来源与内容() {
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk("文档A", "这是内容A")));

        assertEquals("[来源:文档A] 这是内容A", RagResultContextBuilder.build(result));
    }

    @Test
    void 多块_以双换行分隔() {
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk("文档A", "内容A"), chunk("文档B", "内容B")));

        assertEquals("[来源:文档A] 内容A\n\n[来源:文档B] 内容B",
                RagResultContextBuilder.build(result));
    }

    @Test
    void null结果_返回空串() {
        assertEquals("", RagResultContextBuilder.build(null));
    }

    @Test
    void 空chunks_返回空串() {
        assertEquals("", RagResultContextBuilder.build(new RagResult()));
    }

    @Test
    void chunks为null_返回空串() {
        RagResult result = new RagResult();
        result.setChunks(null);
        assertEquals("", RagResultContextBuilder.build(result));
    }

    @Test
    void 空集合chunks_返回空串() {
        RagResult result = new RagResult();
        result.setChunks(new ArrayList<>());
        assertEquals("", RagResultContextBuilder.build(result));
    }

    @Test
    void 文档名为null_回落未知() {
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk(null, "内容")));

        assertEquals("[来源:未知] 内容", RagResultContextBuilder.build(result));
    }

    @Test
    void 内容为null_输出null占位() {
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk("文档A", null)));

        assertTrue(RagResultContextBuilder.build(result).startsWith("[来源:文档A] "));
    }
}