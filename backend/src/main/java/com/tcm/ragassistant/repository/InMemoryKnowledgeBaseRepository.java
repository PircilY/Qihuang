package com.tcm.ragassistant.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.service.KnowledgeRevision;
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
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeChunk> chunkById = new LinkedHashMap<>();
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeDocument> documentById = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final Set<String> seedDocumentIds = new HashSet<>();
    private final List<KnowledgeChunk> expandedChunks = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeChunk> expandedChunkById = new LinkedHashMap<>();
    private final List<KnowledgeDocument> expandedDocuments = new CopyOnWriteArrayList<>();
    private final Map<String, KnowledgeDocument> expandedDocumentById = new LinkedHashMap<>();
    private volatile String baselineRevision;
    private final AtomicLong baselineImportRevision = new AtomicLong();
    private int maxImportDocuments = 1000;
    private long maxImportCharacters = 10_000_000;
    private int journalEntries;
    private final List<java.util.function.Consumer<CorpusId>> listeners = new ArrayList<>();

    @Override
    public synchronized void onChange(java.util.function.Consumer<CorpusId> listener) { listeners.add(listener); }

    @org.springframework.beans.factory.annotation.Autowired
    void configureLimits(@Value("${app.knowledge.max-import-documents:1000}") int documents,
                         @Value("${app.knowledge.max-import-characters:10000000}") long characters) {
        if (documents < 1 || characters < 1) throw new IllegalArgumentException("Import limits must be positive");
        maxImportDocuments = documents;
        maxImportCharacters = characters;
    }

    private Path journalPath() { return storePath.resolveSibling(storePath.getFileName() + ".jsonl"); }

    @Override
    public synchronized List<KnowledgeDocument> findImportedDocuments() {
        return documents.stream().filter(document -> !seedDocumentIds.contains(document.id())).toList();
    }

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
            loadExpanded(resourceLoader);
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
            if (this.storePath != null && Files.exists(journalPath())) {
                byte[] bytes = Files.readAllBytes(journalPath());
                int complete = bytes.length;
                while (complete > 0 && bytes[complete - 1] != '\n') complete--;
                String journal = new String(bytes, 0, complete, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : journal.split("\n")) {
                    if (line.isBlank()) continue;
                    Snapshot entry = objectMapper.readValue(line, Snapshot.class);
                    for (KnowledgeDocument document : entry.documents()) {
                        if (documentById.containsKey(document.id())) continue; // checkpoint replay
                        List<KnowledgeChunk> imported = entry.chunks().stream()
                                .filter(chunk -> document.id().equals(chunk.documentId())).toList();
                        validateBatch(document, imported);
                        addToMemory(document, imported);
                    }
                    journalEntries++;
                }
                if (complete != bytes.length) {
                    try (FileChannel channel = FileChannel.open(journalPath(), StandardOpenOption.WRITE)) {
                        channel.truncate(complete);
                        channel.force(true);
                    }
                }
            }
            baselineRevision = KnowledgeRevision.calculate(findImportedDocuments());
            baselineImportRevision.set(Long.parseUnsignedLong(baselineRevision.substring(0, 16), 16));
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("KNOWLEDGE_BASE_LOAD_ERROR", "知识库初始化失败", exception);
        }
    }

    private void loadExpanded(ResourceLoader resourceLoader) throws IOException {
        Resource documentsResource = resourceLoader.getResource("classpath:data/expanded/knowledge-documents.json");
        Resource chunksResource = resourceLoader.getResource("classpath:data/expanded/knowledge-base.json");
        if (!documentsResource.exists() || !chunksResource.exists()) {
            return;
        }
        readJson(objectMapper, resourceLoader, "classpath:data/expanded/knowledge-documents.json", KnowledgeDocument.class)
                .forEach(document -> {
                    expandedDocuments.add(document);
                    expandedDocumentById.put(document.id(), document);
                });
        readJson(objectMapper, resourceLoader, "classpath:data/expanded/knowledge-base.json", KnowledgeChunk.class)
                .forEach(chunk -> {
                    expandedChunks.add(chunk);
                    expandedChunkById.put(chunk.id(), chunk);
                });
    }

    private List<KnowledgeChunk> chunks(CorpusId corpus) {
        return corpus == CorpusId.EXPANDED ? expandedChunks : chunks;
    }

    private Map<String, KnowledgeChunk> chunkById(CorpusId corpus) {
        return corpus == CorpusId.EXPANDED ? expandedChunkById : chunkById;
    }

    private List<KnowledgeDocument> documents(CorpusId corpus) {
        return corpus == CorpusId.EXPANDED ? expandedDocuments : documents;
    }

    private Map<String, KnowledgeDocument> documentById(CorpusId corpus) {
        return corpus == CorpusId.EXPANDED ? expandedDocumentById : documentById;
    }

    @Override
    public synchronized List<KnowledgeChunk> findAll(CorpusId corpus) {
        return List.copyOf(chunks(corpus));
    }

    @Override
    public synchronized Optional<KnowledgeChunk> findById(String id, CorpusId corpus) {
        return Optional.ofNullable(chunkById(corpus).get(id));
    }

    @Override
    public synchronized List<KnowledgeChunk> findByDisease(String disease, CorpusId corpus) {
        if (disease == null || disease.isBlank()) return List.of();
        if (disease == null || disease.isBlank()) {
            return findAll(corpus);
        }
        String normalized = disease.toLowerCase(Locale.ROOT);
        return chunks(corpus).stream()
                .filter(chunk -> normalized.contains(chunk.disease().toLowerCase(Locale.ROOT))
                        || chunk.disease().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeChunk> search(
            String keyword,
            String disease,
            String sourceType,
            String evidenceLevel,
            CorpusId corpus
    ) {
        String normalizedKeyword = normalize(keyword);
        return chunks(corpus).stream()
                .filter(chunk -> matches(chunk.disease(), disease))
                .filter(chunk -> matches(chunk.sourceType(), sourceType))
                .filter(chunk -> matches(chunk.evidenceLevel(), evidenceLevel))
                .filter(chunk -> normalizedKeyword.isBlank()
                        || searchableText(chunk).contains(normalizedKeyword))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeDocument> findDocuments(CorpusId corpus) {
        return List.copyOf(documents(corpus));
    }

    @Override
    public synchronized Optional<KnowledgeDocument> findDocumentById(String id, CorpusId corpus) {
        return Optional.ofNullable(documentById(corpus).get(id));
    }

    @Override
    public synchronized String knowledgeRevision(CorpusId corpus) {
        if (corpus == CorpusId.BASELINE && baselineRevision != null) {
            return baselineRevision;
        }
        return ""; // EXPANDED is a read-only build artifact, outside runtime revision protocol
    }

    @Override
    public synchronized long importRevisionNumber(CorpusId corpus) {
        return corpus == CorpusId.BASELINE
                ? baselineImportRevision.get()
                : 0L;
    }

    @Override
    public synchronized void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        validateBatch(document, newChunks);
        long characters = chunks.stream().filter(chunk -> !seedDocumentIds.contains(chunk.documentId()))
                .mapToLong(chunk -> chunk.content().length()).sum()
                + newChunks.stream().mapToLong(chunk -> chunk.content().length()).sum();
        if (findImportedDocuments().size() >= maxImportDocuments || characters > maxImportCharacters) {
            throw new BusinessException("KNOWLEDGE_CAPACITY_EXCEEDED", "运行时导入文档数或总字符数超过配置上限");
        }
        persist(document, newChunks);
        addToMemory(document, newChunks);
        baselineRevision = KnowledgeRevision.calculate(findImportedDocuments());
        baselineImportRevision.incrementAndGet();
        listeners.forEach(listener -> listener.accept(CorpusId.BASELINE));
    }

    public synchronized void registerDocument(KnowledgeDocument document, List<KnowledgeChunk> newChunks,
                                              CorpusId corpus) {
        if (corpus == CorpusId.EXPANDED) {
            throw new BusinessException("EXPANDED_CORPUS_READ_ONLY", "扩充语料为构建产物，不支持运行时覆盖");
        }
        registerDocument(document, newChunks);
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
        long originalLength = 0;
        try {
            Files.createDirectories(storePath.getParent());
            byte[] entry = (objectMapper.writeValueAsString(new Snapshot(List.of(document), newChunks)) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(journalPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                originalLength = channel.size();
                channel.position(originalLength);
                try {
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(entry);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                } catch (IOException failure) {
                    channel.truncate(originalLength);
                    channel.force(true);
                    throw failure;
                }
            }
            journalEntries++;
        } catch (IOException exception) {
            throw new BusinessException("KNOWLEDGE_STORE_ERROR", "知识库保存失败，文档未登记", exception);
        }
        // Geometrically spaced checkpoints keep total rewritten bytes linear in import volume.
        int count = findImportedDocuments().size() + 1;
        if ((count & (count - 1)) == 0) checkpoint(document, newChunks);
    }

    private void checkpoint(KnowledgeDocument document, List<KnowledgeChunk> newChunks) {
        Path temporary = null;
        try {
            List<KnowledgeDocument> saved = new ArrayList<>(findImportedDocuments());
            saved.add(document);
            Set<String> ids = saved.stream().map(KnowledgeDocument::id).collect(java.util.stream.Collectors.toSet());
            List<KnowledgeChunk> evidence = new ArrayList<>(chunks.stream()
                    .filter(chunk -> ids.contains(chunk.documentId())).toList());
            evidence.addAll(newChunks);
            temporary = Files.createTempFile(storePath.getParent(), "knowledge-", ".tmp");
            objectMapper.writeValue(temporary.toFile(), new Snapshot(saved, evidence));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(journalPath(), StandardOpenOption.WRITE)) {
                channel.truncate(0);
                channel.force(true);
            }
            journalEntries = 0;
        } catch (IOException exception) {
            // The journal is already durable; checkpoint failure must not turn a committed import into a failure.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Knowledge checkpoint postponed", exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
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
