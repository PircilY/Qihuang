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
        if (!Double.isFinite(lexicalWeight) || lexicalWeight < 0.0) {
            lexicalWeight = 1.0;
        }
        if (!Double.isFinite(vectorWeight) || vectorWeight < 0.0) {
            vectorWeight = 0.85;
        }
        if (lexicalWeight == 0.0 && vectorWeight == 0.0) {
            lexicalWeight = 1.0;
        }
        if (!Double.isFinite(minimumEvidenceScore)
                || minimumEvidenceScore < 0.0
                || minimumEvidenceScore > 1.0) {
            minimumEvidenceScore = 0.016;
        }
    }
}

