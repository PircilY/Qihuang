package com.tcm.ragassistant.service;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.service.retrieval.RetrievalOutput;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategy;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategyRegistry;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    private final RagEngineProperties engineProperties;
    private final LlamaIndexRagClient llamaIndexRagClient;
    private final RetrievalStrategyRegistry strategyRegistry;
    private final AnswerGenerationService answerGenerationService;
    private final AtomicLong remoteRetryAfter = new AtomicLong();
    private final KnowledgeBaseRepository repository;

    public RagOrchestrationService(
            RagEngineProperties engineProperties,
            LlamaIndexRagClient llamaIndexRagClient,
            RetrievalStrategyRegistry strategyRegistry,
            AnswerGenerationService answerGenerationService,
            KnowledgeBaseRepository repository
    ) {
        this.engineProperties = engineProperties;
        this.llamaIndexRagClient = llamaIndexRagClient;
        this.strategyRegistry = strategyRegistry;
        this.answerGenerationService = answerGenerationService;
        this.repository = repository;
    }

    public ChatResponse ask(String question, IterationVersion version) {
        return ask(question, version, RetrievalFilter.none());
    }

    public ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter) {
        return ask(question, version, filter, false);
    }

    public ChatResponse askExtractive(String question, IterationVersion version, RetrievalFilter filter) {
        return ask(question, version, filter, true);
    }

    private ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter,
                             boolean extractiveOnly) {
        RetrievalFilter effectiveFilter = filter == null ? RetrievalFilter.none() : filter;
        if (engineProperties.strictRemote() && !shouldTryRemote()) {
            throw new BusinessException("RAG_SERVICE_UNAVAILABLE", "远程检索服务暂不可用，请稍后重试");
        }
        if (shouldTryRemote()) {
            try {
                // Reconcile durable imports after either service restarts; upserts are idempotent.
                for (var document : repository.findDocuments()) {
                    if (document.id().startsWith("DOC-")) {
                        llamaIndexRagClient.importKnowledge(document, repository.findAll().stream()
                                .filter(chunk -> chunk.documentId().equals(document.id())).toList());
                    }
                }
                ChatResponse response = extractiveOnly
                        ? llamaIndexRagClient.chatExtractive(question, version, effectiveFilter)
                        : llamaIndexRagClient.chat(question, version, effectiveFilter);
                if (response == null) {
                    throw new IllegalStateException("Empty RAG response");
                }
                remoteRetryAfter.set(0);
                return response;
            } catch (RuntimeException exception) {
                remoteRetryAfter.set(System.nanoTime() + engineProperties.retryInterval().toNanos());
                if (engineProperties.strictRemote()) {
                    throw new BusinessException(
                            "RAG_SERVICE_UNAVAILABLE",
                            "LlamaIndex RAG 服务不可用",
                            exception
                    );
                }
                log.warn("LlamaIndex RAG service unavailable, falling back to local engine: {}",
                        exception.getMessage());
            }
        }
        return askLocal(question, version, effectiveFilter);
    }

    private ChatResponse askLocal(
            String question,
            IterationVersion version,
            RetrievalFilter filter
    ) {
        long startedAt = System.nanoTime();
        RetrievalStrategy strategy = strategyRegistry.get(version);
        RetrievalOutput retrievalOutput = strategy.retrieve(question, filter);
        AnswerGenerationService.GeneratedAnswer generated = answerGenerationService.generate(
                question,
                version,
                retrievalOutput
        );
        long latencyMs = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000);

        return new ChatResponse(
                UUID.randomUUID().toString(),
                question,
                version,
                generated.answer(),
                generated.refused(),
                generated.refusalReason(),
                round(generated.confidence()),
                latencyMs,
                generated.citations(),
                retrievalOutput.trace(),
                Instant.now()
        );
    }

    private boolean shouldTryRemote() {
        return engineProperties.remoteEnabled()
                && System.nanoTime() >= remoteRetryAfter.get();
    }

    public Map<IterationVersion, ChatResponse> compare(String question) {
        return compare(question, RetrievalFilter.none());
    }

    public Map<IterationVersion, ChatResponse> compare(String question, RetrievalFilter filter) {
        Map<IterationVersion, ChatResponse> responses = new EnumMap<>(IterationVersion.class);
        for (IterationVersion version : IterationVersion.values()) {
            responses.put(version, ask(question, version, filter));
        }
        return responses;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

