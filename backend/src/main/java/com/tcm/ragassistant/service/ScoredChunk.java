package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeChunk;

public record ScoredChunk(
        KnowledgeChunk chunk,
        double score,
        double lexicalScore,
        double vectorScore,
        String matchedBy
) {
    public ScoredChunk withScore(double newScore, String newMatchedBy) {
        return new ScoredChunk(chunk, newScore, lexicalScore, vectorScore, newMatchedBy);
    }
}

