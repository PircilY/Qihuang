package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

import java.time.Instant;
import java.util.List;

public record ChatResponse(
        String requestId,
        String question,
        IterationVersion version,
        String answer,
        boolean refused,
        String refusalReason,
        double confidence,
        long latencyMs,
        List<CitationResponse> citations,
        RetrievalTrace retrieval,
        Instant generatedAt
) {
    public ChatResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}

