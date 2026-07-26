package com.company.rag.rag.fusion;

import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.model.NormalizedResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RankNormalizerTest {
    
    private final RankNormalizer normalizer = new RankNormalizer();
    
    @Test
    void normalize_shouldApplyReciprocalRankFormula() {
        // Given
        List<RagResult.ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId("chunk_" + i);
            cr.setContent("content " + i);
            results.add(cr);
        }
        
        // When
        List<NormalizedResult> normalized = normalizer.normalize(results);
        
        // Then
        assertEquals(5, normalized.size());
        assertEquals(1.0, normalized.get(0).getNormalizedScore(), 0.001);  // 1/(0+1)
        assertEquals(0.5, normalized.get(1).getNormalizedScore(), 0.001);  // 1/(1+1)
        assertEquals(0.333, normalized.get(2).getNormalizedScore(), 0.001); // 1/(2+1)
        assertEquals(0.25, normalized.get(3).getNormalizedScore(), 0.001); // 1/(3+1)
        assertEquals(0.2, normalized.get(4).getNormalizedScore(), 0.001);  // 1/(4+1)
    }
    
    @Test
    void normalize_emptyList_shouldReturnEmptyList() {
        // When
        List<NormalizedResult> normalized = normalizer.normalize(new ArrayList<>());
        
        // Then
        assertTrue(normalized.isEmpty());
    }
}
