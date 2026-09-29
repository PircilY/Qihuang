package com.tcm.ragassistant.dto;

import java.time.Instant;
import java.util.List;

public record KnowledgeOverviewResponse(
        int documentCount,
        int chunkCount,
        int diseaseCount,
        int sourceTypeCount,
        int highEvidenceChunkCount,
        int pendingChunkCount,
        double averageChunkLength,
        Instant lastUpdatedAt,
        List<DistributionItem> diseaseDistribution,
        List<DistributionItem> sourceDistribution,
        List<DistributionItem> evidenceDistribution,
        List<DistributionItem> statusDistribution
) {
    public KnowledgeOverviewResponse {
        diseaseDistribution = diseaseDistribution == null ? List.of() : List.copyOf(diseaseDistribution);
        sourceDistribution = sourceDistribution == null ? List.of() : List.copyOf(sourceDistribution);
        evidenceDistribution = evidenceDistribution == null ? List.of() : List.copyOf(evidenceDistribution);
        statusDistribution = statusDistribution == null ? List.of() : List.copyOf(statusDistribution);
    }
}
