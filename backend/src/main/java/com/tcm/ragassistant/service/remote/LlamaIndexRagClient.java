package com.tcm.ragassistant.service.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LlamaIndexRagClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LlamaIndexRagClient(RestClient.Builder restClientBuilder, RagEngineProperties properties,
                               ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus) {
        return chat(question, version, filter, corpus, false, null);
    }

    public ChatResponse chat(String question, IterationVersion version, RetrievalFilter filter) {
        return chat(question, version, filter, CorpusId.BASELINE, false, null);
    }

    public ChatResponse chatExtractive(String question, IterationVersion version, RetrievalFilter filter) {
        return chat(question, version, filter, CorpusId.BASELINE, true, null);
    }

    public ChatResponse chatExtractive(String question, IterationVersion version, RetrievalFilter filter,
                                       CorpusId corpus) {
        return chat(question, version, filter, corpus, true, null);
    }

    public ChatResponse chat(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus,
                             String expectedRevision) {
        return chat(question, version, filter, corpus, false, expectedRevision);
    }

    public ChatResponse chatExtractive(String question, IterationVersion version, RetrievalFilter filter,
                                       CorpusId corpus, String expectedRevision) {
        return chat(question, version, filter, corpus, true, expectedRevision);
    }

    private ChatResponse chat(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus,
                              boolean extractiveOnly, String expectedRevision) {
        RetrievalFilter effectiveFilter = filter == null ? RetrievalFilter.none() : filter;
        try {
            return restClient.post().uri("/v1/rag/chat")
                    .body(new RemoteChatRequest(question, version.name(), effectiveFilter.disease(),
                            effectiveFilter.sourceType(), effectiveFilter.evidenceLevel(), extractiveOnly,
                            corpus.name(), expectedRevision))
                    .retrieve().body(ChatResponse.class);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw translateOutOfSync(exception);
        }
    }

    public RemoteImportResponse importKnowledge(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        return restClient.post().uri("/v1/knowledge/import")
                .body(new RemoteImportRequest(document, chunks)).retrieve().body(RemoteImportResponse.class);
    }

    public RemoteImportResponse importKnowledgeBatch(List<KnowledgeImportBatchItem> items) {
        return restClient.post().uri("/v1/knowledge/import-batch")
                .body(new KnowledgeImportBatchRequest(items, CorpusId.BASELINE.name()))
                .retrieve().body(RemoteImportResponse.class);
    }

    public void reloadKnowledge() {
        restClient.post().uri("/v1/knowledge/reload").retrieve().toBodilessEntity();
    }

    public Map<String, Object> health() {
        return restClient.get().uri("/health").retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
    }

    private record RemoteChatRequest(String question, String version, String disease, String sourceType,
                                     String evidenceLevel, boolean extractiveOnly, String corpus,
                                     String expectedRevision) {
    }

    private record RemoteImportRequest(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
    }

    private record KnowledgeImportBatchRequest(List<KnowledgeImportBatchItem> items, String corpus) {
    }

    public record KnowledgeImportBatchItem(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
    }

    public record RemoteImportResponse(String documentId, int importedChunkCount, int totalChunkCount,
                                       String status) {
    }

    private KnowledgeOutOfSyncException translateOutOfSync(
            org.springframework.web.client.RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 409) {
            throw exception;
        }
        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            List<KnowledgeOutOfSyncException.RemoteDocument> documents = new ArrayList<>();
            root.path("documents").forEach(node -> documents.add(new KnowledgeOutOfSyncException.RemoteDocument(
                    node.path("documentId").asText(), node.path("checksum").asText())));
            return new KnowledgeOutOfSyncException(root.path("knowledgeRevision").asText(""), documents, exception);
        } catch (Exception ignored) {
            return new KnowledgeOutOfSyncException("", List.of(), exception);
        }
    }
}
