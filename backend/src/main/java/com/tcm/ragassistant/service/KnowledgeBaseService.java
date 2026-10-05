package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.dto.DistributionItem;
import com.tcm.ragassistant.dto.KnowledgeChunkResponse;
import com.tcm.ragassistant.dto.KnowledgeDocumentResponse;
import com.tcm.ragassistant.dto.KnowledgeOverviewResponse;
import com.tcm.ragassistant.dto.PipelineStageResponse;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;

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

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    public List<KnowledgeChunkResponse> chunks(
            String keyword,
            String disease,
            String sourceType,
            String evidenceLevel
    ) {
        return repository.search(keyword, disease, sourceType, evidenceLevel).stream()
                .map(KnowledgeChunkResponse::from)
                .toList();
    }

    public KnowledgeChunkResponse chunk(String id) {
        return repository.findById(id)
                .map(KnowledgeChunkResponse::from)
                .orElseThrow(() -> new BusinessException("CHUNK_NOT_FOUND", "知识片段不存在：" + id));
    }

    public List<KnowledgeDocumentResponse> documents() {
        Map<String, Long> counts = repository.findAll().stream()
                .collect(Collectors.groupingBy(KnowledgeChunk::documentId, Collectors.counting()));
        return repository.findDocuments().stream()
                .map(document -> KnowledgeDocumentResponse.from(
                        document,
                        counts.getOrDefault(document.id(), 0L).intValue()
                ))
                .toList();
    }

    public KnowledgeDocumentResponse document(String id) {
        KnowledgeDocument document = repository.findDocumentById(id)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "知识文档不存在：" + id));
        int chunkCount = (int) repository.findAll().stream()
                .filter(chunk -> chunk.documentId().equals(id))
                .count();
        return KnowledgeDocumentResponse.from(document, chunkCount);
    }

    public KnowledgeOverviewResponse overview() {
        List<KnowledgeChunk> chunks = repository.findAll();
        List<KnowledgeDocument> documents = repository.findDocuments();
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
                distribution(chunks, KnowledgeChunk::status)
        );
    }

    public List<PipelineStageResponse> pipeline() {
        List<KnowledgeChunk> chunks = repository.findAll();
        List<KnowledgeDocument> documents = repository.findDocuments();
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
