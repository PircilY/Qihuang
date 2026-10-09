package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

import java.util.List;

public record EvaluationCaseResult(
        String caseId,
        String category,
        String question,
        IterationVersion version,
        boolean expectedRefusal,
        List<String> expectedDocumentIds,
        List<String> expectedChunkIds,
        List<String> retrievedDocumentIds,
        List<String> retrievedChunkIds,
        boolean retrievalHit,
        int hitRank,
        boolean hitAt1,
        boolean hitAt3,
        boolean hitAtK,
        double recallAtK,
        double reciprocalRank,
        double topKJaccard,
        double expectedClaimCoverage,
        int citationCount,
        boolean citationPresent,
        boolean citationValid,
        boolean citationSupported,
        boolean refusalCorrect,
        double confidence,
        long latencyMs
) {
    public EvaluationCaseResult {
        expectedDocumentIds = copy(expectedDocumentIds);
        expectedChunkIds = copy(expectedChunkIds);
        retrievedDocumentIds = copy(retrievedDocumentIds);
        retrievedChunkIds = copy(retrievedChunkIds);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

