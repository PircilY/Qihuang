package com.tcm.ragassistant.dto;

public record EvaluationCategorySummary(
        String category,
        int caseCount,
        int retrievalCaseCount,
        double hitRateAt1,
        double hitRateAt3,
        double hitRateAtK,
        double recallAtK,
        double meanReciprocalRank,
        double topKJaccard,
        double expectedClaimCoverage,
        double citationPresenceRate,
        double citationValidityRate,
        double citationSupportRate,
        int trueReject,
        int falseReject,
        int falseAccept,
        int trueAccept,
        double rejectPrecision,
        double rejectRecall,
        double rejectAccuracy,
        int failureCount
) {
}
