package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.KnowledgeDocument;

import java.time.Instant;
import java.util.List;

public record KnowledgeDocumentResponse(
        String id,
        String title,
        String sourceType,
        String publisher,
        Integer publicationYear,
        String authority,
        String evidenceLevel,
        List<String> diseaseScope,
        String fileName,
        String checksum,
        String version,
        String status,
        Instant registeredAt,
        String licenseNote,
        String description,
        int chunkCount,
        String sourceUrl,
        Instant accessedAt
) {
    public static KnowledgeDocumentResponse from(KnowledgeDocument document, int chunkCount) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.title(),
                document.sourceType(),
                document.publisher(),
                document.publicationYear(),
                document.authority(),
                document.evidenceLevel(),
                document.diseaseScope(),
                document.fileName(),
                document.checksum(),
                document.version(),
                document.status(),
                document.registeredAt(),
                document.licenseNote(),
                document.description(),
                chunkCount,
                document.sourceUrl(),
                document.accessedAt()
        );
    }
}
