package com.tcm.ragassistant.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.exception.BusinessException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeChunk> chunkById = new LinkedHashMap<>();
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeDocument> documentById = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final Set<String> seedDocumentIds = new HashSet<>();

    public InMemoryKnowledgeBaseRepository(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${app.knowledge.store-path:./runtime/knowledge-imports.json}") String storePath
    ) {
        this.objectMapper = objectMapper;
        this.storePath = storePath.isBlank() ? null : Path.of(storePath).toAbsolutePath().normalize();
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
                seedDocumentIds.add(document.id());
                documents.add(document);
                documentById.put(document.id(), document);
            });
            loadedChunks.forEach(chunk -> {
                chunks.add(chunk);
                chunkById.put(chunk.id(), chunk);
            });
            if (this.storePath != null && Files.exists(this.storePath)) {
                Snapshot saved = objectMapper.readValue(this.storePath.toFile(), Snapshot.class);
                for (KnowledgeDocument document : saved.documents()) {
                    List<KnowledgeChunk> documentChunks = saved.chunks().stream()
                            .filter(chunk -> document.id().equals(chunk.documentId())).toList();
                    validateBatch(document, documentChunks);
                    addToMemory(document, documentChunks);
                }
                if (saved.chunks().stream().anyMatch(chunk -> !documentById.containsKey(chunk.documentId()))) {
                    throw new IOException("Orphaned persisted knowledge chunk");
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("KNOWLEDGE_BASE_LOAD_ERROR", "知识库初始化失败", exception);
        }
    }

    @Override
    public synchronized List<KnowledgeChunk> findAll() {
        return List.copyOf(chunks);
    }

    @Override
    public synchronized Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(chunkById.get(id));
    }

    @Override
    public synchronized List<KnowledgeChunk> findByDisease(String disease) {
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
    public synchronized List<KnowledgeChunk> search(
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
    public synchronized List<KnowledgeDocument> findDocuments() {
        return List.copyOf(documents);
    }

    @Override
    public synchronized Optional<KnowledgeDocument> findDocumentById(String id) {
        return Optional.ofNullable(documentById.get(id));
    }

    @Override
    public synchronized void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        validateBatch(document, newChunks);
        persist(document, newChunks);
        addToMemory(document, newChunks);
    }

    private void validateBatch(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        if (document == null || document.id() == null || document.id().isBlank()) {
            throw new BusinessException("INVALID_DOCUMENT", "文档编号不能为空");
        }
        if (newChunks == null || newChunks.isEmpty()) {
            throw new BusinessException("INVALID_CHUNKS", "至少需要登记一个知识片段");
        }
        if (documentById.containsKey(document.id())) {
            throw new BusinessException("DOCUMENT_ALREADY_EXISTS", "文档编号已存在：" + document.id());
        }
        if (!document.checksum().isBlank() && documents.stream()
                .anyMatch(existing -> existing.checksum().equals(document.checksum()))) {
            throw new BusinessException("DOCUMENT_ALREADY_EXISTS", "相同正文已登记，请勿重复导入");
        }

        Set<String> incomingIds = new HashSet<>();
        for (KnowledgeChunk chunk : newChunks) {
            if (chunk == null || chunk.id() == null || chunk.id().isBlank()) {
                throw new BusinessException("INVALID_CHUNK", "知识片段编号不能为空");
            }
            if (!document.id().equals(chunk.documentId())) {
                throw new BusinessException("CHUNK_DOCUMENT_MISMATCH", "知识片段不属于当前文档：" + chunk.id());
            }
            if (!incomingIds.add(chunk.id())) {
                throw new BusinessException("CHUNK_ALREADY_EXISTS", "导入请求中存在重复片段编号：" + chunk.id());
            }
            if (chunkById.containsKey(chunk.id())) {
                throw new BusinessException("CHUNK_ALREADY_EXISTS", "知识片段编号已存在：" + chunk.id());
            }
        }

    }

    private void addToMemory(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        documents.add(document);
        documentById.put(document.id(), document);
        for (KnowledgeChunk chunk : newChunks) {
            chunks.add(chunk);
            chunkById.put(chunk.id(), chunk);
        }
    }

    private void persist(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        if (storePath == null) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(storePath.getParent());
            List<KnowledgeDocument> savedDocuments = new ArrayList<>(documents.stream()
                    .filter(item -> !seedDocumentIds.contains(item.id())).toList());
            savedDocuments.add(document);
            Set<String> ids = savedDocuments.stream().map(KnowledgeDocument::id)
                    .collect(java.util.stream.Collectors.toSet());
            List<KnowledgeChunk> savedChunks = new ArrayList<>(chunks.stream()
                    .filter(item -> ids.contains(item.documentId())).toList());
            savedChunks.addAll(newChunks);
            temporary = Files.createTempFile(storePath.getParent(), "knowledge-", ".tmp");
            objectMapper.writeValue(temporary.toFile(), new Snapshot(savedDocuments, savedChunks));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Publish memory changes only after the complete snapshot is durable.
            Files.move(temporary, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new BusinessException("KNOWLEDGE_STORE_ERROR", "知识库保存失败，文档未登记", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover temporary file is not a committed snapshot.
                }
            }
        }
    }

    public record Snapshot(List<KnowledgeDocument> documents, List<KnowledgeChunk> chunks) {}

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

