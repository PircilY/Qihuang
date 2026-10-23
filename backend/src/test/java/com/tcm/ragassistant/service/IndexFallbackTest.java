package com.tcm.ragassistant.service;

import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.service.remote.*;
import com.tcm.ragassistant.service.retrieval.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IndexFallbackTest {
    @Test
    void buildingIndexFallsBackWithExplicitTraceAndLocalEmbedding() {
        var remote = mock(LlamaIndexRagClient.class);
        var repository = mock(KnowledgeBaseRepository.class);
        when(repository.knowledgeRevision(CorpusId.BASELINE)).thenReturn("revision");
        when(remote.chat(anyString(), any(), any(), any(), anyString())).thenThrow(new IndexUnavailableException("BUILDING"));
        var registry = mock(RetrievalStrategyRegistry.class);
        var strategy = mock(RetrievalStrategy.class);
        when(registry.get(any())).thenReturn(strategy);
        when(strategy.retrieve(anyString(), any(), any())).thenReturn(new RetrievalOutput(List.of(),
                new RetrievalTrace("fixture",0,0,0,0,false,List.of()),false));
        var service = new RagOrchestrationService(new RagEngineProperties("auto",null,null,null,true,null),
                remote,registry,new AnswerGenerationService(),repository);
        var answer = service.ask("[FIXTURE] 非医学事实 占位问题",IterationVersion.V3_GUARDED);
        assertThat(answer.retrieval().engine()).isEqualTo("java-local");
        assertThat(answer.retrieval().notes()).contains("remote-index=BUILDING", "fallback=local",
                "已使用本地引擎", "embedding-backend=hash", "embedding-model=hash-sha256-bigram-v1");
    }
}
