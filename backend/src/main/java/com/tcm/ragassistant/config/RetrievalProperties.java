package com.tcm.ragassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
        int candidateLimit,
        int finalLimit,
        int rrfRankConstant,
        double lexicalWeight,
        double vectorWeight,
        double minimumEvidenceScore
) {
    public RetrievalProperties {
        if (candidateLimit <= 0) {
            candidateLimit = 8;
        }
        if (finalLimit <= 0) {
            finalLimit = 4;
        }
        if (rrfRankConstant <= 0) {
            rrfRankConstant = 60;
        }
    }
}

