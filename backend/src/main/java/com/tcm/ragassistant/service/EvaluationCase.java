package com.tcm.ragassistant.service;

import java.util.List;

public record EvaluationCase(
        String id,
        String category,
        String question,
        List<String> expectedChunkIds,
        boolean shouldRefuse
) {
    public EvaluationCase {
        category = category == null ? "未分类" : category;
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
    }
}

