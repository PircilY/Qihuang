package com.tcm.ragassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
        int candidateLimit,
        int finalLimit,
        int rrfRankConstant,
        double lexicalWeight,
        double vectorWeight,
        double minimumEvidenceScore,
        boolean normalizedFusion,
        double metadataWeight
) {
    public RetrievalProperties(int candidateLimit, int finalLimit, int rrfRankConstant,
                               double lexicalWeight, double vectorWeight, double minimumEvidenceScore) {
        this(candidateLimit, finalLimit, rrfRankConstant, lexicalWeight, vectorWeight, minimumEvidenceScore, false, 0.25);
    }
    public double maximumFusionScore() { return (lexicalWeight + vectorWeight) / (rrfRankConstant + 1.0); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RetrievalProperties {
        if (!Double.isFinite(metadataWeight) || metadataWeight < 0 || metadataWeight > 1) metadataWeight = 0.25;
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

