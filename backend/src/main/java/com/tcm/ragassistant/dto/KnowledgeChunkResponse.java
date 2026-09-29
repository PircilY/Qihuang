package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.KnowledgeChunk;

import java.time.Instant;
import java.util.List;

public record KnowledgeChunkResponse(
        String id,
        String documentId,
        String title,
        String sourceType,
        String publisher,
        Integer publicationYear,
        String disease,
        List<String> syndromes,
        List<String> formulas,
        List<String> herbs,
        String page,
        String section,
        String evidenceLevel,
        List<String> keywords,
        String content,
        String checksum,
        String status,
        String version,
        Instant ingestedAt,
        int contentLength
) {
    public static KnowledgeChunkResponse from(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                chunk.id(),
                chunk.documentId(),
                chunk.title(),
                chunk.sourceType(),
                chunk.publisher(),
                chunk.publicationYear(),
                chunk.disease(),
                chunk.syndromes(),
                chunk.formulas(),
                chunk.herbs(),
                chunk.pageLabel(),
                chunk.section(),
                chunk.evidenceLevel(),
                chunk.keywords(),
                chunk.content(),
                chunk.checksum(),
                chunk.status(),
                chunk.version(),
                chunk.ingestedAt(),
                chunk.content().length()
        );
    }
}

