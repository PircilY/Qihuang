package com.tcm.ragassistant.domain;

import java.time.Instant;
import java.util.List;

public record KnowledgeChunk(
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
        int pageStart,
        int pageEnd,
        String section,
        String evidenceLevel,
        List<String> keywords,
        String content,
        String checksum,
        String status,
        String version,
        Instant ingestedAt,
        String metadataSource,
        java.util.Map<String, java.util.List<String>> metadataConfidence
) {
    public KnowledgeChunk(String id, String documentId, String title, String sourceType, String publisher,
                           Integer publicationYear, String disease, List<String> syndromes, List<String> formulas,
                           List<String> herbs, int pageStart, int pageEnd, String section, String evidenceLevel,
                           List<String> keywords, String content, String checksum, String status, String version,
                           Instant ingestedAt) {
        this(id, documentId, title, sourceType, publisher, publicationYear, disease, syndromes, formulas, herbs,
                pageStart, pageEnd, section, evidenceLevel, keywords, content, checksum, status, version,
                ingestedAt, "", java.util.Map.of());
    }
    public KnowledgeChunk(String id, String documentId, String title, String sourceType, String publisher,
                           Integer publicationYear, String disease, List<String> syndromes, List<String> formulas,
                           List<String> herbs, int pageStart, int pageEnd, String section, String evidenceLevel,
                           List<String> keywords, String content, String checksum, String status, String version,
                           Instant ingestedAt, String metadataSource) {
        this(id, documentId, title, sourceType, publisher, publicationYear, disease, syndromes, formulas, herbs,
                pageStart, pageEnd, section, evidenceLevel, keywords, content, checksum, status, version,
                ingestedAt, metadataSource, java.util.Map.of());
    }
    public KnowledgeChunk {
        title = title == null ? "" : title;
        sourceType = sourceType == null ? "" : sourceType;
        checksum = checksum == null ? "" : checksum;
        disease = disease == null ? "" : disease;
        evidenceLevel = evidenceLevel == null || evidenceLevel.isBlank() ? "未评级" : evidenceLevel;
        content = content == null ? "" : content;
        syndromes = syndromes == null ? List.of() : List.copyOf(syndromes);
        formulas = formulas == null ? List.of() : List.copyOf(formulas);
        herbs = herbs == null ? List.of() : List.copyOf(herbs);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        publisher = publisher == null ? "" : publisher;
        publicationYear = publicationYear == null ? 0 : publicationYear;
        section = section == null ? "" : section;
        status = status == null ? "ACTIVE" : status;
        version = version == null ? "v1" : version;
        ingestedAt = ingestedAt == null ? Instant.now() : ingestedAt;
        metadataSource = metadataSource == null ? "" : metadataSource;
        metadataConfidence = metadataConfidence == null ? java.util.Map.of() : java.util.Map.copyOf(metadataConfidence);
    }

    public String pageLabel() {
        if (pageStart <= 0) {
            return "页码未标注";
        }
        return pageStart == pageEnd ? "第 " + pageStart + " 页" : "第 " + pageStart + "-" + pageEnd + " 页";
    }

    public String sourceLabel() {
        if (publicationYear != null && publicationYear > 0) {
            return publisher + " · " + publicationYear;
        }
        return publisher;
    }
}

