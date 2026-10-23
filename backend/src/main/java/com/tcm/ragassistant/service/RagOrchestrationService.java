package com.tcm.ragassistant.service;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.service.retrieval.RetrievalOutput;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategy;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategyRegistry;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import com.tcm.ragassistant.service.remote.KnowledgeOutOfSyncException;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    private final RagEngineProperties engineProperties;
    private final LlamaIndexRagClient llamaIndexRagClient;
    private final RetrievalStrategyRegistry strategyRegistry;
    private final AnswerGenerationService answerGenerationService;
    private final AtomicLong remoteRetryAfter = new AtomicLong();
    private final KnowledgeBaseRepository repository;
    private final RemoteSyncState remoteSyncState;
    private final ReentrantLock syncLock = new ReentrantLock();
    private volatile String remoteFailureNote = "remote-unavailable";
    private final AtomicLong syncEpoch = new AtomicLong();

    public RagOrchestrationService(
            RagEngineProperties engineProperties,
            LlamaIndexRagClient llamaIndexRagClient,
            RetrievalStrategyRegistry strategyRegistry,
            AnswerGenerationService answerGenerationService,
            KnowledgeBaseRepository repository
    ) {
        this(engineProperties, llamaIndexRagClient, strategyRegistry, answerGenerationService, repository,
                new RemoteSyncState());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagOrchestrationService(
            RagEngineProperties engineProperties,
            LlamaIndexRagClient llamaIndexRagClient,
            RetrievalStrategyRegistry strategyRegistry,
            AnswerGenerationService answerGenerationService,
            KnowledgeBaseRepository repository,
            RemoteSyncState remoteSyncState
    ) {
        this.engineProperties = engineProperties;
        this.llamaIndexRagClient = llamaIndexRagClient;
        this.strategyRegistry = strategyRegistry;
        this.answerGenerationService = answerGenerationService;
        this.repository = repository;
        this.remoteSyncState = remoteSyncState;
    }

    public ChatResponse ask(String question, IterationVersion version) {
        return ask(question, version, RetrievalFilter.none());
    }

    public ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter) {
        return ask(question, version, filter, CorpusId.BASELINE, false);
    }

    public ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus) {
        return ask(question, version, filter, corpus, false);
    }

    public ChatResponse askExtractive(String question, IterationVersion version, RetrievalFilter filter) {
        return ask(question, version, filter, CorpusId.BASELINE, true);
    }

    public ChatResponse askExtractive(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus) {
        return ask(question, version, filter, corpus, true);
    }

    private ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter, CorpusId corpus,
                             boolean extractiveOnly) {
        RetrievalFilter effectiveFilter = filter == null ? RetrievalFilter.none() : filter;
        ChatResponse emergency = emergencyResponse(question, version);
        if (emergency != null) return emergency;
        if (engineProperties.strictRemote() && !shouldTryRemote()) {
            throw new BusinessException("RAG_SERVICE_UNAVAILABLE", "远程检索服务暂不可用，请稍后重试");
        }
        if (shouldTryRemote()) {
            long observedEpoch = syncEpoch.get();
            String expectedRevision = currentRevision(corpus);
            try {
                ChatResponse response = callRemote(question, version, effectiveFilter, corpus, extractiveOnly,
                        expectedRevision);
                if (response == null) {
                    throw new IllegalStateException("Empty RAG response");
                }
                remoteRetryAfter.set(0);
                if (corpus == CorpusId.BASELINE) remoteSyncState.markInSync(expectedRevision);
                return response;
            } catch (KnowledgeOutOfSyncException outOfSync) {
                try {
                    synchronize(outOfSync, corpus, observedEpoch);
                    String retriedRevision = currentRevision(corpus);
                    ChatResponse response = callRemote(question, version, effectiveFilter, corpus, extractiveOnly,
                            retriedRevision);
                    if (response == null) {
                        throw new IllegalStateException("Empty RAG response");
                    }
                    remoteRetryAfter.set(0);
                    if (corpus == CorpusId.BASELINE) remoteSyncState.markInSync(retriedRevision);
                    return response;
                } catch (KnowledgeOutOfSyncException | ResourceAccessException |
                         RestClientResponseException | com.tcm.ragassistant.service.remote.IndexUnavailableException | IllegalStateException retryFailure) {
                    return handleRemoteFailure(question, version, effectiveFilter, corpus, retryFailure);
                }
            } catch (RuntimeException exception) {
                return handleRemoteFailure(question, version, effectiveFilter, corpus, exception);
            }
        }
        return askLocal(question, version, effectiveFilter, corpus);
    }

    private ChatResponse emergencyResponse(String question, IterationVersion version) {
        if (!SafetyRules.isEmergency(question)) return null;
        String message = "问题可能涉及急症，请立即拨打 120 / 前往急诊。";
        var trace = new com.tcm.ragassistant.dto.RetrievalTrace(
                "safety-triage", 0, 0, 0, 0, false,
                List.of("safety=emergency", "retrieval=bypassed", "generation=bypassed"),
                engineProperties.remoteEnabled() ? "safety-gateway" : "java-local", version.name(), null, true);
        return new ChatResponse(UUID.randomUUID().toString(), question, version, message, true,
                "问题可能涉及急症，超出线上问答边界", 0.2, 1, List.of(), trace, Instant.now());
    }

    private ChatResponse callRemote(String question, IterationVersion version, RetrievalFilter filter,
                                    CorpusId corpus, boolean extractiveOnly, String expectedRevision) {
        return extractiveOnly
                ? llamaIndexRagClient.chatExtractive(question, version, filter, corpus, expectedRevision)
                : llamaIndexRagClient.chat(question, version, filter, corpus, expectedRevision);
    }

    private String currentRevision(CorpusId corpus) {
        String revision = repository.knowledgeRevision(corpus);
        return revision == null ? "" : revision;
    }

    private ChatResponse handleRemoteFailure(String question, IterationVersion version, RetrievalFilter filter,
                                             CorpusId corpus, RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException
                && responseException.getStatusCode().is4xxClientError()) {
            throw new BusinessException("RAG_SERVICE_REQUEST_REJECTED", "远程检索服务拒绝了请求", exception);
        }
        if (!(exception instanceof com.tcm.ragassistant.service.remote.IndexUnavailableException)
                && !(exception instanceof KnowledgeOutOfSyncException)
                && !(exception instanceof ResourceAccessException)
                && !(exception instanceof RestClientResponseException responseException
                && responseException.getStatusCode().is5xxServerError())) {
            throw exception;
        }
        remoteFailureNote = exception instanceof com.tcm.ragassistant.service.remote.IndexUnavailableException unavailable
                ? "remote-index=" + unavailable.state() : "remote-unavailable";
        remoteRetryAfter.set(System.nanoTime() + engineProperties.retryInterval().toNanos());
        remoteSyncState.markPending();
        if (engineProperties.strictRemote()) {
            throw new BusinessException("RAG_SERVICE_UNAVAILABLE", "LlamaIndex RAG 服务不可用", exception);
        }
        log.warn("LlamaIndex RAG service unavailable, falling back to local engine: {}", exception.getMessage());
        return askLocal(question, version, filter, corpus);
    }

    private void synchronize(KnowledgeOutOfSyncException outOfSync, CorpusId corpus, long observedEpoch) {
        syncLock.lock();
        try {
            String localRevision = currentRevision(corpus);
            if (syncEpoch.get() != observedEpoch && remoteSyncState.status() == RemoteSyncState.Status.IN_SYNC
                    && localRevision.equals(remoteSyncState.revision())) {
                return;
            }
            remoteSyncState.markPending();
            Map<String, String> remote = new HashMap<>();
            outOfSync.documents().forEach(document -> remote.put(document.documentId(), document.checksum()));
            List<com.tcm.ragassistant.domain.KnowledgeDocument> imported = repository.findImportedDocuments();
            java.util.Set<String> localIds = imported.stream().map(com.tcm.ragassistant.domain.KnowledgeDocument::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (remote.keySet().stream().anyMatch(id -> !localIds.contains(id))) {
                llamaIndexRagClient.reloadKnowledge();
                remote.clear();
            }
            Map<String, List<com.tcm.ragassistant.domain.KnowledgeChunk>> chunksByDocument =
                    repository.findAll(corpus).stream().collect(java.util.stream.Collectors.groupingBy(
                            com.tcm.ragassistant.domain.KnowledgeChunk::documentId));
            List<LlamaIndexRagClient.KnowledgeImportBatchItem> pending = imported.stream()
                    .filter(document -> !document.checksum().equals(remote.get(document.id())))
                    .map(document -> new LlamaIndexRagClient.KnowledgeImportBatchItem(document,
                            chunksByDocument.getOrDefault(document.id(), List.of())))
                    .toList();
            for (int start = 0; start < pending.size(); start += engineProperties.syncBatchSize()) {
                int end = Math.min(start + engineProperties.syncBatchSize(), pending.size());
                llamaIndexRagClient.importKnowledgeBatch(pending.subList(start, end));
            }
            String confirmed = String.valueOf(llamaIndexRagClient.health().get("knowledgeRevision"));
            if (!localRevision.equals(confirmed)) {
                throw new KnowledgeOutOfSyncException(confirmed, List.of(), null);
            }
            remoteSyncState.markInSync(localRevision);
            syncEpoch.incrementAndGet();
        } catch (RuntimeException exception) {
            remoteSyncState.markPending();
            throw exception;
        } finally {
            syncLock.unlock();
        }
    }

    public String remoteSyncState() {
        if (!engineProperties.remoteEnabled()) return RemoteSyncState.Status.IN_SYNC.name();
        if (remoteSyncState.status() == RemoteSyncState.Status.IN_SYNC
                && !currentRevision(CorpusId.BASELINE).equals(remoteSyncState.revision())) return "PENDING";
        return remoteSyncState.status().name();
    }

    private ChatResponse askLocal(
            String question,
            IterationVersion version,
            RetrievalFilter filter,
            CorpusId corpus
    ) {
        long startedAt = System.nanoTime();
        RetrievalStrategy strategy = strategyRegistry.get(version);
        RetrievalOutput retrievalOutput = strategy.retrieve(question, filter, corpus);
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
                localTrace(retrievalOutput.trace(), version, corpus),
                Instant.now()
        );
    }

    private com.tcm.ragassistant.dto.RetrievalTrace localTrace(com.tcm.ragassistant.dto.RetrievalTrace trace,
                                                            IterationVersion version, CorpusId corpus) {
        var notes = new ArrayList<>(trace.notes());
        notes.add("embedding-backend=hash");
        notes.add("embedding-model=hash-sha256-bigram-v1");
        notes.add("corpus=" + corpus.name());
        if (engineProperties.remoteEnabled()) {
            notes.add("fallback=local"); notes.add(remoteFailureNote); notes.add("已使用本地引擎");
        }
        return new com.tcm.ragassistant.dto.RetrievalTrace(trace.strategy(), trace.lexicalCandidateCount(),
                trace.vectorCandidateCount(), trace.fusedCandidateCount(), trace.selectedCount(), trace.sufficientEvidence(),
                notes, "java-local", version.name(), trace.threshold(), trace.rejected());
    }

    private boolean shouldTryRemote() {
        long now = System.nanoTime();
        long deadline = remoteRetryAfter.get();
        return engineProperties.remoteEnabled() && now - deadline >= 0;
    }

    public Map<IterationVersion, ChatResponse> compare(String question) {
        return compare(question, RetrievalFilter.none());
    }

    public Map<IterationVersion, ChatResponse> compare(String question, RetrievalFilter filter) {
        return compare(question, filter, CorpusId.BASELINE);
    }

    public Map<IterationVersion, ChatResponse> compare(String question, RetrievalFilter filter, CorpusId corpus) {
        Map<IterationVersion, ChatResponse> responses = new EnumMap<>(IterationVersion.class);
        for (IterationVersion version : IterationVersion.values()) {
            responses.put(version, askExtractive(question, version, filter, corpus));
        }
        return responses;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
