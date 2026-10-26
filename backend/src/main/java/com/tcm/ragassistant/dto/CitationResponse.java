package com.tcm.ragassistant.dto;

import java.util.List;

public record CitationResponse(
        String chunkId,
        String documentId,
        String documentTitle,
        String sourceType,
        String publisher,
        Integer publicationYear,
        String disease,
        List<String> syndromes,
        List<String> formulas,
        List<String> herbs,
        String section,
        String page,
        String evidenceLevel,
        String quote,
        double relevanceScore,
        String metadataSource
) {
    public CitationResponse(String chunkId, String documentId, String documentTitle, String sourceType,
                            String publisher, Integer publicationYear, String disease, String section, String page,
                            String evidenceLevel, String quote, double relevanceScore) {
        this(chunkId, documentId, documentTitle, sourceType, publisher, publicationYear, disease,
                List.of(), List.of(), List.of(), section, page, evidenceLevel, quote, relevanceScore, "");
    }
}

