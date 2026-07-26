package com.company.rag.rag.fusion;

import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.FusedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultFuserTest {
    
    private ResultFuser fuser;
    
    @BeforeEach
    void setUp() {
        fuser = new ResultFuser();
    }
    
    @Test
    void fuse_shortQuery_shouldUseShortQueryWeights() {
        // Given
        String query = "micro service";  // 2 terms < 5
        List<NormalizedResult> vector = createMockResults("v1", "v2");
        List<NormalizedResult> fulltext = createMockResults("f1", "f2");
        List<NormalizedResult> fuzzy = createMockResults("z1", "z2");
        
        // When
        List<FusedResult> fused = fuser.fuse(vector, fulltext, fuzzy, query);
        
        // Then
        assertFalse(fused.isEmpty());
        // 验证权重：vector 0.7, fulltext 0.2, fuzzy 0.1
    }
    
    @Test
    void fuse_longQuery_shouldUseLongQueryWeights() {
        // Given
        String query = "micro service architecture design pattern best practice";  // 6 terms >= 5
        List<NormalizedResult> vector = createMockResults("v1", "v2");
        List<NormalizedResult> fulltext = createMockResults("f1", "f2");
        List<NormalizedResult> fuzzy = createMockResults("z1", "z2");
        
        // When
        List<FusedResult> fused = fuser.fuse(vector, fulltext, fuzzy, query);
        
        // Then
        assertFalse(fused.isEmpty());
        // 验证权重：vector 0.4, fulltext 0.4, fuzzy 0.2
    }
    
    @Test
    void fuse_properNounQuery_shouldUseProperNounWeights() {
        // Given
        String query = "REST-API-v2";  // contains "-"
        List<NormalizedResult> vector = createMockResults("v1", "v2");
        List<NormalizedResult> fulltext = createMockResults("f1", "f2");
        List<NormalizedResult> fuzzy = createMockResults("z1", "z2");
        
        // When
        List<FusedResult> fused = fuser.fuse(vector, fulltext, fuzzy, query);
        
        // Then
        assertFalse(fused.isEmpty());
        // 验证权重：vector 0.5, fulltext 0.4, fuzzy 0.1
    }
    
    private List<NormalizedResult> createMockResults(String... chunkIds) {
        List<NormalizedResult> results = new ArrayList<>();
        for (String id : chunkIds) {
            NormalizedResult r = new NormalizedResult();
            r.setChunkId(id);
            r.setNormalizedScore(0.5);
            results.add(r);
        }
        return results;
    }
}
