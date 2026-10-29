package com.tcm.ragassistant.service;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.service.remote.KnowledgeOutOfSyncException;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategyRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class RagKnowledgeSyncTest {
    @Test
    void combinedRequestsRecoverRuntimeImportsAfterRemoteRestart() {
        var remote = mock(LlamaIndexRagClient.class);
        var repository = mock(KnowledgeBaseRepository.class);
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("local");
        when(repository.knowledgeRevision(CorpusId.COMBINED)).thenReturn("local");
        when(repository.findImportedDocuments()).thenReturn(List.of(document()));
        when(repository.findAll(CorpusId.COMBINED)).thenReturn(List.of(chunk(document().id())));
        when(remote.health()).thenReturn(java.util.Map.of("knowledgeRevision", "local"));
        var answer = new ChatResponse("id", "fixture", IterationVersion.V2_HYBRID, "fixture", false,
                null, 0.5, 1, List.of(), new RetrievalTrace("remote", 0, 0, 0, 0, false, List.of()), Instant.EPOCH);
        when(remote.chat(anyString(), any(), any(), eq(CorpusId.COMBINED), eq("local")))
                .thenThrow(new KnowledgeOutOfSyncException("remote", List.of(), null)).thenReturn(answer);
        var state = new RemoteSyncState();
        state.markPending();
        var service = new RagOrchestrationService(
                new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1)),
                remote, mock(RetrievalStrategyRegistry.class), new AnswerGenerationService(), repository, state);
        assertThat(service.ask("fixture", IterationVersion.V2_HYBRID, RetrievalFilter.none(), CorpusId.COMBINED))
                .isEqualTo(answer);
        assertThat(service.remoteSyncState()).isEqualTo("IN_SYNC");
        verify(remote).importKnowledgeBatch(argThat(items -> items.size() == 1
                && items.get(0).chunks().get(0).id().equals(chunk(document().id()).id())));
    }

    @Test
    void emergencyBypassesRemoteAndRetrievalForEveryVersion() {
        LlamaIndexRagClient remote = mock(LlamaIndexRagClient.class);
        RetrievalStrategyRegistry strategies = mock(RetrievalStrategyRegistry.class);
        RagOrchestrationService service = new RagOrchestrationService(
                new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1)),
                remote, strategies, new AnswerGenerationService(), mock(KnowledgeBaseRepository.class),
                new RemoteSyncState());
        for (IterationVersion version : IterationVersion.values()) {
            ChatResponse response = service.ask("我现在胸痛", version, RetrievalFilter.none());
            assertThat(response.refused()).isTrue();
            assertThat(response.answer()).contains("立即拨打 120 / 前往急诊");
        }
        verifyNoInteractions(remote, strategies);
    }

    @Test
    void concurrentRevisionMismatchTriggersOneBatchSync() throws Exception {
        LlamaIndexRagClient remote = mock(LlamaIndexRagClient.class);
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        KnowledgeDocument document = document();
        KnowledgeChunk chunk = chunk(document.id());
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("local");
        when(repository.findImportedDocuments()).thenReturn(List.of(document));
        when(repository.findAll(CorpusId.BASELINE)).thenReturn(List.of(chunk));
        ChatResponse answer = new ChatResponse("id", "问题", IterationVersion.V2_HYBRID, "答", false,
                null, 0.5, 1, List.of(), new RetrievalTrace("remote", 0, 0, 0, 0, false, List.of()),
                Instant.now());
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(remote.chat(anyString(), any(), any(), any(), eq("local"))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                barrier.await(5, TimeUnit.SECONDS);
                throw new KnowledgeOutOfSyncException("remote", List.of(), null);
            }
            return answer;
        });
        when(remote.health()).thenReturn(java.util.Map.of("knowledgeRevision", "local"));
        when(remote.importKnowledgeBatch(anyList())).thenReturn(new LlamaIndexRagClient.RemoteImportResponse(
                document.id(), 1, 1, "READY"));
        RagOrchestrationService service = new RagOrchestrationService(
                new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1)),
                remote, mock(RetrievalStrategyRegistry.class), new AnswerGenerationService(), repository,
                new RemoteSyncState());

        var pool = Executors.newFixedThreadPool(2);
        var first = pool.submit(() -> service.ask("问题", IterationVersion.V2_HYBRID, RetrievalFilter.none()));
        var second = pool.submit(() -> service.ask("问题", IterationVersion.V2_HYBRID, RetrievalFilter.none()));
        assertThat(first.get(5, TimeUnit.SECONDS).answer()).isEqualTo("答");
        assertThat(second.get(5, TimeUnit.SECONDS).answer()).isEqualTo("答");
        pool.shutdownNow();
        verify(remote, times(1)).importKnowledgeBatch(anyList());
    }

    @Test
    void extraRemoteDocumentsAreClearedAndPendingRecovers() {
        LlamaIndexRagClient remote = mock(LlamaIndexRagClient.class);
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("local");
        when(repository.findImportedDocuments()).thenReturn(List.of(document()));
        when(repository.findAll(CorpusId.BASELINE)).thenReturn(List.of(chunk(document().id())));
        when(remote.health()).thenReturn(java.util.Map.of("knowledgeRevision", "local"));
        ChatResponse answer = new ChatResponse("id", "问题", IterationVersion.V2_HYBRID, "答", false,
                null, 0.5, 1, List.of(), new RetrievalTrace("remote", 0, 0, 0, 0, false, List.of()), Instant.now());
        var mismatch = new KnowledgeOutOfSyncException("remote", List.of(
                new KnowledgeOutOfSyncException.RemoteDocument("EXTRA", "old")), null);
        when(remote.chat(anyString(), any(), any(), any(), eq("local")))
                .thenThrow(mismatch).thenReturn(answer).thenThrow(mismatch).thenReturn(answer);
        var state = new RemoteSyncState();
        state.markPending();
        var service = new RagOrchestrationService(
                new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1)),
                remote, mock(RetrievalStrategyRegistry.class), new AnswerGenerationService(), repository, state);
        assertThat(service.ask("[FIXTURE] 非医学事实", IterationVersion.V2_HYBRID).answer()).isEqualTo("答");
        assertThat(service.remoteSyncState()).isEqualTo("IN_SYNC");
        // The same local revision must still resync after a subsequent remote restart.
        assertThat(service.ask("[FIXTURE] 非医学事实", IterationVersion.V2_HYBRID).answer()).isEqualTo("答");
        verify(remote, times(2)).reloadKnowledge();
        verify(remote, times(2)).importKnowledgeBatch(anyList());
    }

    @Test
    void unconfirmedRevisionRemainsPending() {
        var remote = mock(LlamaIndexRagClient.class);
        var repository = mock(KnowledgeBaseRepository.class);
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("local");
        when(repository.findImportedDocuments()).thenReturn(List.of());
        when(remote.chat(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new KnowledgeOutOfSyncException("old", List.of(), null));
        when(remote.health()).thenReturn(java.util.Map.of("knowledgeRevision", "old"));
        var state = new RemoteSyncState();
        var service = new RagOrchestrationService(
                new RagEngineProperties("llama-index", null, null, null, false, Duration.ofSeconds(1)),
                remote, mock(RetrievalStrategyRegistry.class), new AnswerGenerationService(), repository, state);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ask("[FIXTURE] 非医学事实", IterationVersion.V2_HYBRID))
                .isInstanceOf(com.tcm.ragassistant.exception.BusinessException.class);
        assertThat(state.status()).isEqualTo(RemoteSyncState.Status.PENDING);
    }

    @Test
    void syncSplitsAtConfiguredBatchBoundary() {
        var remote = mock(LlamaIndexRagClient.class);
        var repository = mock(KnowledgeBaseRepository.class);
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("local");
        var imported = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
                new KnowledgeDocument("FIXTURE-" + index, "[FIXTURE] 非医学事实", "测试", "测试", 2026,
                        "", "未评级", List.of(), "", "hash-" + index, "v1", "ACTIVE", Instant.EPOCH,
                        "测试夹具", "", "https://example.org", null)).toList();
        when(repository.findImportedDocuments()).thenReturn(imported);
        when(repository.findAll(CorpusId.BASELINE)).thenReturn(List.of());
        when(remote.health()).thenReturn(java.util.Map.of("knowledgeRevision", "local"));
        ChatResponse answer = new ChatResponse("fixture", "占位问题", IterationVersion.V2_HYBRID, "占位回答", false,
                null, 0.5, 1, List.of(), new RetrievalTrace("remote", 0, 0, 0, 0, false, List.of()), Instant.EPOCH);
        when(remote.chat(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new KnowledgeOutOfSyncException("remote", List.of(), null)).thenReturn(answer);
        var service = new RagOrchestrationService(
                new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1), 2),
                remote, mock(RetrievalStrategyRegistry.class), new AnswerGenerationService(), repository,
                new RemoteSyncState());
        service.ask("[FIXTURE] 非医学事实", IterationVersion.V2_HYBRID);
        var batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(remote, times(2)).importKnowledgeBatch(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(2, 1);
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument("DOC-SYNC", "[FIXTURE] 同步 非医学事实", "测试", "测试", 2026, "", "C",
                List.of("测试"), "", "checksum-sync", "v1", "ACTIVE", Instant.EPOCH,
                "", "", "https://example.org", null);
    }

    private KnowledgeChunk chunk(String documentId) {
        return new KnowledgeChunk("DOC-SYNC-C1", documentId, "[FIXTURE] 同步 非医学事实", "测试", "测试", 2026,
                "测试", List.of(), List.of(), List.of(), 0, 0, "正文", "C", List.of(),
                "[FIXTURE] 用于同步测试的占位内容，非医学事实。", "chunk", "ACTIVE", "v1", Instant.EPOCH);
    }
}
