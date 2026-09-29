package com.tcm.ragassistant.dto;

public record CitationResponse(
        String chunkId,
        String documentId,
        String documentTitle,
        String sourceType,
        String publisher,
        Integer publicationYear,
        String disease,
        String section,
        String page,
        String evidenceLevel,
        String quote,
        double relevanceScore
) {
}

