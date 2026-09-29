package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

public record EvaluationCaseResult(
        String caseId,
        String category,
        String question,
        IterationVersion version,
        boolean expectedRefusal,
        boolean retrievalHit,
        int hitRank,
        int citationCount,
        boolean citationPresent,
        boolean refusalCorrect,
        double confidence,
        long latencyMs
) {
}

