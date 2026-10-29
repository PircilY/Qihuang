package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.dto.DistributionItem;
import com.tcm.ragassistant.dto.KnowledgeChunkResponse;
import com.tcm.ragassistant.dto.KnowledgeDocumentResponse;
import com.tcm.ragassistant.dto.KnowledgeOverviewResponse;
import com.tcm.ragassistant.dto.PipelineStageResponse;
import com.tcm.ragassistant.dto.CorpusInfoResponse;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.config.RagEngineProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final RemoteSyncState remoteSyncState;
    private final RagEngineProperties engineProperties;

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this(repository, new RemoteSyncState(),
                new RagEngineProperties("local", null, null, null, true, null));
    }

    @Autowired
    public KnowledgeBaseService(KnowledgeBaseRepository repository, RemoteSyncState remoteSyncState,
                                RagEngineProperties engineProperties) {
        this.repository = repository;
        this.remoteSyncState = remoteSyncState;
        this.engineProperties = engineProperties;
    }

    public List<CorpusInfoResponse> corpora() {
            int baselineDocuments = repository.findDocuments(CorpusId.BASELINE).size();
            int baselineChunks = repository.findAll(CorpusId.BASELINE).size();
            int expandedDocuments = repository.findDocuments(CorpusId.EXPANDED).size();
            int expandedChunks = repository.findAll(CorpusId.EXPANDED).size();
            return List.of(
                    new CorpusInfoResponse(CorpusId.BASELINE, "标准语料", baselineDocuments, baselineChunks,
                            baselineDocuments, true, "正式评测与历史结果使用此语料"),
                    new CorpusInfoResponse(CorpusId.EXPANDED, "扩充语料", expandedDocuments, expandedChunks,
                            expandedDocuments, false,
                            "许可和全文已核验；主题相关性仍需人工复核；不属于 Experiment A"),
                    new CorpusInfoResponse(CorpusId.COMBINED, "知识库",
                            repository.findDocuments(CorpusId.COMBINED).size(),
                            repository.findAll(CorpusId.COMBINED).size(),
                            repository.findDocuments(CorpusId.COMBINED).size(), false,
                            "标准、扩充及用户登记资料的合并视图；历史评测仍使用标准语料")
            );
    }

    public List<KnowledgeChunkResponse> chunks(
            String keyword,
            String disease,
            String sourceType,
            String evidenceLevel,
            CorpusId corpus
    ) {
        return repository.search(keyword, disease, sourceType, evidenceLevel, corpus).stream()
                .map(KnowledgeChunkResponse::from)
                .toList();
    }

    public KnowledgeChunkResponse chunk(String id, CorpusId corpus) {
        return repository.findById(id, corpus)
                .map(KnowledgeChunkResponse::from)
                .orElseThrow(() -> new BusinessException("CHUNK_NOT_FOUND", "知识片段不存在：" + id));
    }

    public List<KnowledgeDocumentResponse> documents(CorpusId corpus) {
        Map<String, Long> counts = repository.findAll(corpus).stream()
                .collect(Collectors.groupingBy(KnowledgeChunk::documentId, Collectors.counting()));
        return repository.findDocuments(corpus).stream()
                .map(document -> KnowledgeDocumentResponse.from(
                        document,
                        counts.getOrDefault(document.id(), 0L).intValue()
                ))
                .toList();
    }

    public KnowledgeDocumentResponse document(String id, CorpusId corpus) {
        KnowledgeDocument document = repository.findDocumentById(id, corpus)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "知识文档不存在：" + id));
        int chunkCount = (int) repository.findAll(corpus).stream()
                .filter(chunk -> chunk.documentId().equals(id))
                .count();
        return KnowledgeDocumentResponse.from(document, chunkCount);
    }

    public KnowledgeOverviewResponse overview(CorpusId corpus) {
        List<KnowledgeChunk> chunks = repository.findAll(corpus);
        List<KnowledgeDocument> documents = repository.findDocuments(corpus);
        Instant lastUpdatedAt = chunks.stream()
                .map(KnowledgeChunk::ingestedAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return new KnowledgeOverviewResponse(
                documents.size(),
                chunks.size(),
                distinctCount(chunks, KnowledgeChunk::disease),
                distinctCount(chunks, KnowledgeChunk::sourceType),
                (int) chunks.stream().filter(this::isHighEvidence).count(),
                (int) chunks.stream().filter(chunk -> !"ACTIVE".equals(chunk.status())
                        || !List.of("A", "B", "C").contains(chunk.evidenceLevel())).count(),
                round(chunks.stream().mapToInt(chunk -> chunk.content().length()).average().orElse(0.0)),
                lastUpdatedAt,
                distribution(chunks, KnowledgeChunk::disease),
                distribution(chunks, KnowledgeChunk::sourceType),
                distribution(chunks, KnowledgeChunk::evidenceLevel),
                distribution(chunks, KnowledgeChunk::status),
                engineProperties.remoteEnabled() ? remoteSyncState.status().name() : "IN_SYNC"
        );
    }

    public List<PipelineStageResponse> pipeline(CorpusId corpus) {
        List<KnowledgeChunk> chunks = repository.findAll(corpus);
        List<KnowledgeDocument> documents = repository.findDocuments(corpus);
        Instant lastUpdatedAt = chunks.stream()
                .map(KnowledgeChunk::ingestedAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        int metadataReady = (int) chunks.stream()
                .filter(chunk -> !chunk.disease().isBlank())
                .filter(chunk -> !chunk.evidenceLevel().isBlank())
                .count();
        int indexed = (int) chunks.stream().filter(chunk -> "ACTIVE".equals(chunk.status())).count();
        int qualityReady = (int) chunks.stream()
                .filter(chunk -> !chunk.content().isBlank())
                .filter(chunk -> !chunk.checksum().isBlank())
                .count();

        return List.of(
                new PipelineStageResponse(
                        "source",
                        "来源注册",
                        "文档与来源记录",
                        documents.isEmpty() ? "WAITING" : "READY",
                        documents.size(),
                        lastDocumentTime(documents)
                ),
                new PipelineStageResponse(
                        "parse",
                        "内容解析",
                        "文本 / SHA-256",
                        chunks.isEmpty() ? "WAITING" : "READY",
                        chunks.size(),
                        lastUpdatedAt
                ),
                new PipelineStageResponse(
                        "chunk",
                        "段落切分",
                        "段落 / 长度边界",
                        chunks.isEmpty() ? "WAITING" : "READY",
                        chunks.size(),
                        lastUpdatedAt
                ),
                new PipelineStageResponse(
                        "metadata",
                        "元数据补全",
                        "疾病 / 等级",
                        chunks.isEmpty() ? "WAITING" : metadataReady == chunks.size() ? "READY" : "PARTIAL",
                        metadataReady,
                        lastUpdatedAt
                ),
                new PipelineStageResponse(
                        "index",
                        "索引构建",
                        "本地可检索片段",
                        chunks.isEmpty() ? "WAITING" : indexed == chunks.size() ? "READY" : "PARTIAL",
                        indexed,
                        lastUpdatedAt
                ),
                new PipelineStageResponse(
                        "quality",
                        "质量校验",
                        "非空正文 / 校验值",
                        chunks.isEmpty() ? "WAITING" : qualityReady == chunks.size() ? "READY" : "PARTIAL",
                        qualityReady,
                        lastUpdatedAt
                )
        );
    }

    private Instant lastDocumentTime(List<KnowledgeDocument> documents) {
        return documents.stream()
                .map(KnowledgeDocument::registeredAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }

    private boolean isHighEvidence(KnowledgeChunk chunk) {
        return "A".equalsIgnoreCase(chunk.evidenceLevel());
    }

    private int distinctCount(
            List<KnowledgeChunk> chunks,
            Function<KnowledgeChunk, String> classifier
    ) {
        return (int) chunks.stream()
                .map(classifier)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
    }

    private List<DistributionItem> distribution(
            List<KnowledgeChunk> chunks,
            Function<KnowledgeChunk, String> classifier
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            String label = classifier.apply(chunk);
            if (label == null || label.isBlank()) {
                label = "未标注";
            }
            counts.merge(label, 1, Integer::sum);
        }
        int total = chunks.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(entry -> new DistributionItem(
                        entry.getKey(),
                        entry.getValue(),
                        ratio(entry.getValue(), total)
                ))
                .toList();
    }

    private double ratio(int value, int total) {
        return total == 0 ? 0.0 : round((double) value / total);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
