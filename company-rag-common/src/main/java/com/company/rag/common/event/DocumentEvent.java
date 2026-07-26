package com.company.rag.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DocumentEvent extends ApplicationEvent {
    private final Long tenantId;
    private final Long documentId;
    private final DocumentEventType eventType;

    public DocumentEvent(Object source, Long tenantId, Long documentId, DocumentEventType eventType) {
        super(source);
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.eventType = eventType;
    }
}
