package com.tcm.ragassistant.repository;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {

    List<KnowledgeChunk> findAll();

    Optional<KnowledgeChunk> findById(String id);

    List<KnowledgeChunk> findByDisease(String disease);

    List<KnowledgeChunk> search(String keyword, String disease, String sourceType, String evidenceLevel);

    List<KnowledgeDocument> findDocuments();

    Optional<KnowledgeDocument> findDocumentById(String id);

    void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> chunks);
}

