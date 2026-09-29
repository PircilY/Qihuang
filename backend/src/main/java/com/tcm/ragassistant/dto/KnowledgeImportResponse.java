package com.tcm.ragassistant.dto;

import java.time.Instant;

public record KnowledgeImportResponse(
        KnowledgeDocumentResponse document,
        int chunkCount,
        String status,
        Instant processedAt
) {
}
