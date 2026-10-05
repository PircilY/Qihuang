package com.tcm.ragassistant.service.remote;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@Component
public class LlamaIndexRagClient {

    private final RestClient restClient;

    public LlamaIndexRagClient(
            RestClient.Builder restClientBuilder,
            RagEngineProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    public ChatResponse chat(
            String question,
            IterationVersion version,
            RetrievalFilter filter
    ) {
        return chat(question, version, filter, false);
    }

    public ChatResponse chatExtractive(String question, IterationVersion version, RetrievalFilter filter) {
        return chat(question, version, filter, true);
    }

    private ChatResponse chat(String question, IterationVersion version, RetrievalFilter filter,
                              boolean extractiveOnly) {
        RetrievalFilter effectiveFilter = filter == null ? RetrievalFilter.none() : filter;
        return restClient.post()
                .uri("/v1/rag/chat")
                .body(new RemoteChatRequest(
                        question,
                        version.name(),
                        effectiveFilter.disease(),
                        effectiveFilter.sourceType(),
                        effectiveFilter.evidenceLevel(),
                        extractiveOnly
                ))
                .retrieve()
                .body(ChatResponse.class);
    }

    public RemoteImportResponse importKnowledge(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks
    ) {
        return restClient.post()
                .uri("/v1/knowledge/import")
                .body(new RemoteImportRequest(document, chunks))
                .retrieve()
                .body(RemoteImportResponse.class);
    }

    public Map<String, Object> health() {
        return restClient.get()
                .uri("/health")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
    }

    private record RemoteChatRequest(
            String question,
            String version,
            String disease,
            String sourceType,
            String evidenceLevel,
            boolean extractiveOnly
    ) {
    }

    private record RemoteImportRequest(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks
    ) {
    }

    public record RemoteImportResponse(
            String documentId,
            int importedChunkCount,
            int totalChunkCount,
            String status
    ) {
    }
}
