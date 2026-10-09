package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

import java.util.List;

public record EvaluationSummary(
        IterationVersion version,
        int caseCount,
        int retrievalCaseCount,
        double hitRateAt1,
        double hitRateAt3,
        double hitRateAtK,
        double recallAtK,
        double meanReciprocalRank,
        double topKJaccard,
        double expectedClaimCoverage,
        double citationCoverage,
        double citationValidityRate,
        double citationSupportRate,
        int trueReject,
        int falseReject,
        int falseAccept,
        int trueAccept,
        double rejectPrecision,
        double rejectRecall,
        double refusalAccuracy,
        double averageConfidence,
        double averageLatencyMs,
        int failureCount,
        List<EvaluationCategorySummary> byCategory
) {
    public EvaluationSummary {
        byCategory = byCategory == null ? List.of() : List.copyOf(byCategory);
    }
}

