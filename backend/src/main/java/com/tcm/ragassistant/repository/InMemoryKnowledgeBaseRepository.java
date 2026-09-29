package com.tcm.ragassistant.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.exception.BusinessException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeChunk> chunkById = new LinkedHashMap<>();
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeDocument> documentById = new LinkedHashMap<>();

    public InMemoryKnowledgeBaseRepository(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader
    ) {
        try {
            List<KnowledgeDocument> loadedDocuments = readJson(
                    objectMapper,
                    resourceLoader,
                    "classpath:data/knowledge-documents.json",
                    KnowledgeDocument.class
            );
            List<KnowledgeChunk> loadedChunks = readJson(
                    objectMapper,
                    resourceLoader,
                    "classpath:data/knowledge-base.json",
                    KnowledgeChunk.class
            );
            loadedDocuments.forEach(document -> {
                documents.add(document);
                documentById.put(document.id(), document);
            });
            loadedChunks.forEach(chunk -> {
                chunks.add(chunk);
                chunkById.put(chunk.id(), chunk);
            });
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("KNOWLEDGE_BASE_LOAD_ERROR", "知识库初始化失败", exception);
        }
    }

    @Override
    public List<KnowledgeChunk> findAll() {
        return List.copyOf(chunks);
    }

    @Override
    public Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(chunkById.get(id));
    }

    @Override
    public List<KnowledgeChunk> findByDisease(String disease) {
        if (disease == null || disease.isBlank()) {
            return findAll();
        }
        String normalized = disease.toLowerCase(Locale.ROOT);
        return chunks.stream()
                .filter(chunk -> normalized.contains(chunk.disease().toLowerCase(Locale.ROOT))
                        || chunk.disease().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    @Override
    public List<KnowledgeChunk> search(
            String keyword,
            String disease,
            String sourceType,
            String evidenceLevel
    ) {
        String normalizedKeyword = normalize(keyword);
        return chunks.stream()
                .filter(chunk -> matches(chunk.disease(), disease))
                .filter(chunk -> matches(chunk.sourceType(), sourceType))
                .filter(chunk -> matches(chunk.evidenceLevel(), evidenceLevel))
                .filter(chunk -> normalizedKeyword.isBlank()
                        || searchableText(chunk).contains(normalizedKeyword))
                .toList();
    }

    @Override
    public List<KnowledgeDocument> findDocuments() {
        return List.copyOf(documents);
    }

    @Override
    public Optional<KnowledgeDocument> findDocumentById(String id) {
        return Optional.ofNullable(documentById.get(id));
    }

    @Override
    public synchronized void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        if (documentById.containsKey(document.id())) {
            throw new BusinessException("DOCUMENT_ALREADY_EXISTS", "文档编号已存在：" + document.id());
        }
        documents.add(document);
        documentById.put(document.id(), document);
        for (KnowledgeChunk chunk : newChunks) {
            if (chunkById.containsKey(chunk.id())) {
                throw new BusinessException("CHUNK_ALREADY_EXISTS", "知识片段编号已存在：" + chunk.id());
            }
            chunks.add(chunk);
            chunkById.put(chunk.id(), chunk);
        }
    }

    private <T> List<T> readJson(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            String location,
            Class<T> elementType
    ) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)
            );
        }
    }

    private boolean matches(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return actual != null && normalize(actual).contains(normalize(expected));
    }

    private String searchableText(KnowledgeChunk chunk) {
        return normalize(String.join(" ",
                chunk.id(),
                chunk.documentId(),
                chunk.title(),
                chunk.sourceType(),
                chunk.publisher(),
                chunk.disease(),
                chunk.section(),
                String.join(" ", chunk.syndromes()),
                String.join(" ", chunk.formulas()),
                String.join(" ", chunk.herbs()),
                String.join(" ", chunk.keywords()),
                chunk.content()
        ));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}

