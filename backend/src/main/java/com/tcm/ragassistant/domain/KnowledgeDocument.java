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
        String fileName,
        String checksum,
        String version,
        String status,
        Instant registeredAt,
        String licenseNote,
        String description,
        String sourceUrl,
        Instant accessedAt
) {
    public KnowledgeDocument {
        diseaseScope = diseaseScope == null ? List.of() : List.copyOf(diseaseScope);
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
    }
}
