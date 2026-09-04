package com.company.rag.rag.fusion;

import com.company.rag.rag.model.FusedResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFilterTest {

    private final ResultFilter filter = new ResultFilter();

    /**
     * 回归用户症状：答案只在全文路(权重0.2)命中且排名第3，
     * 旧逻辑 finalScore=0.25*0.2=0.05 < 0.3 会被丢弃。
     * 修复后，默认(无硬阈值)应保留该 chunk。
     */
    @Test
    void filter_default_noHardThreshold_keepsSingleLowWeightRouteHit() {
        FusedResult relevant = new FusedResult();
        relevant.setChunkId("correct-chunk");
        relevant.setFinalScore(0.05);

        FusedResult noisy = new FusedResult();
        noisy.setChunkId("multi-route-hit");
        noisy.setFinalScore(0.85);

        List<FusedResult> results = List.of(noisy, relevant);

        List<FusedResult> out = filter.filter(results, 10, null);

        assertEquals(2, out.size(), "默认不启用硬阈值，两条都保留");
        assertTrue(out.stream().anyMatch(r -> r.getChunkId().equals("correct-chunk")),
                "低权重单路命中的正确 chunk 不能被丢弃");
    }

    /**
     * 显式传入 >0 阈值时，仍应执行硬阈值过滤（保留原有能力）。
     */
    @Test
    void filter_withExplicitThreshold_appliesHardFilter() {
        FusedResult low = new FusedResult();
        low.setChunkId("low");
        low.setFinalScore(0.05);

        FusedResult high = new FusedResult();
        high.setChunkId("high");
        high.setFinalScore(0.5);

        List<FusedResult> out = filter.filter(List.of(low, high), 10, 0.3);

        assertEquals(1, out.size());
        assertEquals("high", out.get(0).getChunkId());
    }

    /**
     * topK 限制仍旧生效。
     */
    @Test
    void filter_respectsTopK() {
        FusedResult a = new FusedResult();
        a.setChunkId("a");
        a.setFinalScore(0.9);
        FusedResult b = new FusedResult();
        b.setChunkId("b");
        b.setFinalScore(0.8);

        List<FusedResult> out = filter.filter(List.of(a, b), 1, null);

        assertEquals(1, out.size());
        assertEquals("a", out.get(0).getChunkId());
    }

    @Test
    void filter_emptyList_returnsEmpty() {
        assertTrue(filter.filter(List.of(), 10, null).isEmpty());
    }
}