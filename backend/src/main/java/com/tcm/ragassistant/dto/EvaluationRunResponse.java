package com.tcm.ragassistant.dto;

import java.time.Instant;
import java.util.List;

public record EvaluationRunResponse(
        String datasetName,
        int totalCases,
        List<EvaluationSummary> summaries,
        List<EvaluationCaseResult> caseResults,
        Instant generatedAt
) {
    public EvaluationRunResponse {
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
    }
}

