package com.tcm.ragassistant.repository;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.CorpusId;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {

    List<KnowledgeChunk> findAll(CorpusId corpus);

    Optional<KnowledgeChunk> findById(String id, CorpusId corpus);

    List<KnowledgeChunk> findByDisease(String disease, CorpusId corpus);

    List<KnowledgeChunk> search(String keyword, String disease, String sourceType, String evidenceLevel, CorpusId corpus);

    List<KnowledgeDocument> findDocuments(CorpusId corpus);

    Optional<KnowledgeDocument> findDocumentById(String id, CorpusId corpus);

    default List<KnowledgeDocument> findImportedDocuments() { return List.of(); }

    String knowledgeRevision(CorpusId corpus);

    default String importRevision(CorpusId corpus) {
        return knowledgeRevision(corpus);
    }

    default long importRevisionNumber(CorpusId corpus) {
        return Long.parseUnsignedLong(importRevision(corpus).substring(0, 16), 16);
    }

    default void onChange(java.util.function.Consumer<CorpusId> listener) { }

    void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> chunks);
}
