package com.tcm.ragassistant.domain;

import java.time.Instant;
import java.util.List;

public record KnowledgeDocument(
        String id,
        String title,
        String sourceType,
        String publisher,
        Integer publicationYear,
        String authority,
        String evidenceLevel,
        List<String> diseaseScope,
        List<String> herbs,
        List<String> formulas,
        List<String> syndromes,
        String fileName,
        String checksum,
        String version,
        String status,
        Instant registeredAt,
        String licenseNote,
        String description,
        String sourceUrl,
        Instant accessedAt,
        String metadataSource
) {
    public KnowledgeDocument(String id, String title, String sourceType, String publisher, Integer publicationYear,
                             String authority, String evidenceLevel, List<String> diseaseScope, String fileName,
                             String checksum, String version, String status, Instant registeredAt, String licenseNote,
                             String description, String sourceUrl, Instant accessedAt) {
        this(id, title, sourceType, publisher, publicationYear, authority, evidenceLevel, diseaseScope,
                List.of(), List.of(), List.of(), fileName,
                checksum, version, status, registeredAt, licenseNote, description, sourceUrl, accessedAt, "");
    }
    public KnowledgeDocument(String id, String title, String sourceType, String publisher, Integer publicationYear,
                             String authority, String evidenceLevel, List<String> diseaseScope, String fileName,
                             String checksum, String version, String status, Instant registeredAt, String licenseNote,
                             String description, String sourceUrl, Instant accessedAt, String metadataSource) {
        this(id, title, sourceType, publisher, publicationYear, authority, evidenceLevel, diseaseScope,
                List.of(), List.of(), List.of(), fileName, checksum, version, status, registeredAt, licenseNote,
                description, sourceUrl, accessedAt, metadataSource);
    }
    public KnowledgeDocument {
        title = title == null ? "" : title;
        sourceType = sourceType == null ? "" : sourceType;
        evidenceLevel = evidenceLevel == null || evidenceLevel.isBlank() ? "未评级" : evidenceLevel;
        diseaseScope = diseaseScope == null ? List.of() : List.copyOf(diseaseScope);
        herbs = herbs == null ? List.of() : List.copyOf(herbs);
        formulas = formulas == null ? List.of() : List.copyOf(formulas);
        syndromes = syndromes == null ? List.of() : List.copyOf(syndromes);
        publisher = publisher == null ? "" : publisher;
        publicationYear = publicationYear == null ? 0 : publicationYear;
        authority = authority == null ? "" : authority;
        fileName = fileName == null ? "" : fileName;
        checksum = checksum == null ? "" : checksum;
        version = version == null ? "v1" : version;
        status = status == null ? "ACTIVE" : status;
        registeredAt = registeredAt == null ? Instant.now() : registeredAt;
        licenseNote = licenseNote == null ? "" : licenseNote;
        description = description == null ? "" : description;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        metadataSource = metadataSource == null ? "" : metadataSource;
    }
}
