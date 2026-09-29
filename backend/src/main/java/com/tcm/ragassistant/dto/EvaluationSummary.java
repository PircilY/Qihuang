package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

public record EvaluationSummary(
        IterationVersion version,
        double hitRateAt1,
        double hitRateAt3,
        double meanReciprocalRank,
        double citationCoverage,
        double refusalAccuracy,
        double averageConfidence,
        double averageLatencyMs,
        int failureCount
) {
}

