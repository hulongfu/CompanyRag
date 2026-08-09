package com.company.rag.document.service;

import com.company.rag.common.event.DocumentEvent;
import com.company.rag.document.mapper.DocumentChunkMapper;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.document.service.impl.DocumentParseServiceImpl;
import com.company.rag.document.splitter.DocumentSplitter;
import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentParseServiceImplEventTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private List<DocumentSplitter> splitters;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentParseServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DocumentParseServiceImpl(vectorStore, splitters, documentMapper, chunkMapper, jdbcTemplate, eventPublisher);
        // 设置租户上下文，因为 deleteDocument 需要
        TenantContext.setSchema("test_tenant");
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        // 清理租户上下文，避免污染其他测试
        TenantContext.clear();
    }

    @Test
    void deleteDocument_shouldPublishDeletedEvent() {
        // Given
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(0);
        when(chunkMapper.delete(any())).thenReturn(0);
        when(documentMapper.deleteById(anyLong())).thenReturn(0);

        // When
        service.deleteDocument(100L, 1L);

        // Then
        ArgumentCaptor<DocumentEvent> captor = ArgumentCaptor.forClass(DocumentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DocumentEvent event = captor.getValue();
        assert event.getTenantId().equals(1L) : "tenantId 不匹配";
        assert event.getDocumentId().equals(100L) : "documentId 不匹配";
    }
}
