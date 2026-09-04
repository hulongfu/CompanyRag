package com.company.rag.rag.eval;

import com.company.rag.rag.model.RagResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RetrievalEvalRunnerTest {

    private final RetrievalEvalRunner runner = new RetrievalEvalRunner();

    @Test
    void evaluate_withOldThreshold_showsDrop_butWithNullKeepsReference() {
        // reference chunk 只在全文路命中(第3个位置)
        EvalCase evalCase = new EvalCase("c1", "某配置项含义",
                List.of("correct-chunk"));

        List<RagResult.ChunkResult> vector = List.of();
        List<RagResult.ChunkResult> fulltext = List.of(
                chunk("noise-1"), chunk("noise-2"), chunk("correct-chunk"));
        List<RagResult.ChunkResult> fuzzy = List.of();

        // 旧默认(显式 0.3)：correct-chunk 融合分 = 0.25*0.2 = 0.05 < 0.3，被丢弃
        RetrievalEvalResult withOld = runner.evaluate(
                evalCase, vector, fulltext, fuzzy, 10, 0.3);
        assertEquals(1.0, withOld.recallBefore(), 0.001, "融合前应召回");
        assertEquals(0.0, withOld.recallAfter(), 0.001, "被 0.3 阈值丢弃");

        // B0 新默认(不启用硬阈值 null)：correct-chunk 被保留
        RetrievalEvalResult withNew = runner.evaluate(
                evalCase, vector, fulltext, fuzzy, 10, null);
        assertEquals(1.0, withNew.recallAfter(), 0.001, "默认不再丢弃低权重单路命中");
        assertFalse(withNew.droppedChunkIds().contains("correct-chunk"));
    }

    private RagResult.ChunkResult chunk(String id) {
        RagResult.ChunkResult c = new RagResult.ChunkResult();
        c.setChunkId(id);
        c.setContent("content of " + id);
        return c;
    }
}