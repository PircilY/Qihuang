package com.tcm.ragassistant.service;

import java.util.List;

public record EvaluationCase(
        String id,
        String category,
        String question,
        List<String> expectedDocumentIds,
        List<String> expectedChunkIds,
        List<String> expectedClaims,
        boolean shouldRefuse
) {
    public EvaluationCase {
        category = category == null ? "uncategorized" : category;
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        expectedClaims = expectedClaims == null ? List.of() : List.copyOf(expectedClaims);
    }
}

