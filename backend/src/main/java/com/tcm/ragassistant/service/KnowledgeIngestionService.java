package com.tcm.ragassistant.service;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.dto.KnowledgeDocumentResponse;
import com.tcm.ragassistant.dto.KnowledgeImportRequest;
import com.tcm.ragassistant.dto.KnowledgeImportResponse;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final int MAX_CHUNK_LENGTH = 620;

    private final RagEngineProperties engineProperties;
    private final LlamaIndexRagClient llamaIndexRagClient;
    private final KnowledgeBaseRepository repository;

    public KnowledgeIngestionService(
            RagEngineProperties engineProperties,
            LlamaIndexRagClient llamaIndexRagClient,
            KnowledgeBaseRepository repository
    ) {
        this.engineProperties = engineProperties;
        this.llamaIndexRagClient = llamaIndexRagClient;
        this.repository = repository;
    }

    public KnowledgeImportResponse ingest(KnowledgeImportRequest request) {
        validateSource(request);
        Instant now = Instant.now();
        String documentId = "DOC-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        String checksum = sha256(request.content().replace("\r\n", "\n").strip());
        List<String> diseaseScope = request.disease() == null || request.disease().isBlank()
                ? List.of("未归类")
                : List.of(request.disease().trim());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                request.title().trim(),
                request.sourceType().trim(),
                request.publisher().trim(),
                request.publicationYear(),
                valueOrDefault(request.authority(), "待人工核验"),
                valueOrDefault(request.evidenceLevel(), "未评级"),
                diseaseScope,
                "runtime-import.txt",
                checksum,
                valueOrDefault(request.version(), "v1"),
                "ACTIVE",
                now,
                request.licenseNote().trim(),
                "用户登记，来源与临床适用性未经独立复核",
                request.sourceUrl().trim(),
                null
        );
        List<KnowledgeChunk> chunks = buildChunks(document, request, now);
        repository.registerDocument(document, chunks);
        String syncStatus = syncToLlamaIndex(document, chunks);
        return new KnowledgeImportResponse(
                KnowledgeDocumentResponse.from(document, chunks.size()),
                chunks.size(),
                syncStatus,
                now
        );
    }

    private String syncToLlamaIndex(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        if (!engineProperties.remoteEnabled()) {
            return "LOCAL_READY";
        }
        try {
            llamaIndexRagClient.importKnowledge(document, chunks);
            return "READY";
        } catch (RuntimeException exception) {
            log.warn("Document {} was registered locally but LlamaIndex sync failed: {}",
                    document.id(), exception.getMessage());
            return "SYNC_PENDING";
        }
    }

    private void validateSource(KnowledgeImportRequest request) {
        try {
            URI uri = URI.create(request.sourceUrl().trim());
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException("INVALID_SOURCE_URL", "原文链接必须是有效的 HTTP 或 HTTPS 地址");
        }
        if (request.publicationYear() > LocalDate.now().getYear()) {
            throw new BusinessException("INVALID_PUBLICATION_YEAR", "出版年份不能晚于当前年份");
        }
        if (request.content().strip().length() < 30) {
            throw new BusinessException("INVALID_CONTENT", "正文去除首尾空白后至少需要 30 个字符");
        }
    }

    private List<KnowledgeChunk> buildChunks(
            KnowledgeDocument document,
            KnowledgeImportRequest request,
            Instant now
    ) {
        List<String> paragraphs = splitIntoChunks(request.content());
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < paragraphs.size(); index++) {
            chunks.add(new KnowledgeChunk(
                    document.id() + "-C" + String.format("%03d", index + 1),
                    document.id(),
                    document.title(),
                    document.sourceType(),
                    document.publisher(),
                    document.publicationYear(),
                    document.diseaseScope().get(0),
                    request.syndromes(),
                    request.formulas(),
                    request.herbs(),
                    0,
                    0,
                    valueOrDefault(request.section(), "正文"),
                    document.evidenceLevel(),
                    request.keywords(),
                    paragraphs.get(index),
                    sha256(paragraphs.get(index)),
                    "ACTIVE",
                    document.version(),
                    now
            ));
        }
        return chunks;
    }

    private List<String> splitIntoChunks(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String candidate = paragraph.replaceAll("[ \\t]+", " ").trim();
            if (!candidate.isBlank()) {
                paragraphs.add(candidate);
            }
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(normalized);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > MAX_CHUNK_LENGTH) {
                flush(current, chunks);
                splitLongParagraph(paragraph, chunks);
                continue;
            }
            if (!current.isEmpty() && current.length() + paragraph.length() + 2 > MAX_CHUNK_LENGTH) {
                flush(current, chunks);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flush(current, chunks);
        return chunks;
    }

    private void splitLongParagraph(String paragraph, List<String> chunks) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + MAX_CHUNK_LENGTH);
            if (end < paragraph.length()) {
                int punctuationBreak = lastPunctuation(paragraph, start, end);
                if (punctuationBreak > start + 120) {
                    end = punctuationBreak;
                }
            }
            chunks.add(paragraph.substring(start, end).trim());
            start = end;
        }
    }

    private int lastPunctuation(String text, int start, int end) {
        for (int index = end - 1; index > start; index--) {
            if ("。！？；".indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return end;
    }

    private void flush(StringBuilder current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
