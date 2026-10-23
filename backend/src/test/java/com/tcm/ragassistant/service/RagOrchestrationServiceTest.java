package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.tcm.ragassistant.config.RagEngineProperties;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategyRegistry;
import java.time.Duration;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
class RagOrchestrationServiceTest {

    @Autowired
    private RagOrchestrationService ragOrchestrationService;

    @Test
    void hybridRetrievalShouldFindExpectedHypertensionChunk() {
        ChatResponse response = ragOrchestrationService.ask(
                "高血压患者没有症状也需要检查血压吗？",
                IterationVersion.V2_HYBRID
        );

        assertThat(response.refused()).isFalse();
        assertThat(response.citations())
                .extracting(citation -> citation.chunkId())
                .contains("WHO-HTN-001");
        assertThat(response.retrieval().strategy()).isEqualTo("hybrid-rrf");
    }

    @Test
    void guardedVersionShouldReturnTraceableCitations() {
        ChatResponse response = ragOrchestrationService.ask(
                "甘草有哪些严重不良反应？",
                IterationVersion.V3_GUARDED
        );

        assertThat(response.refused()).isFalse();
        assertThat(response.confidence()).isGreaterThan(0.65);
        assertThat(response.citations()).isNotEmpty();
        assertThat(response.citations().get(0).quote()).isNotBlank();
        assertThat(response.retrieval().sufficientEvidence()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(IterationVersion.class)
    void everyVersionEnforcesSafety(IterationVersion version) {
        for (String question : java.util.List.of("胸痛持续不缓解，\n能不能给我一个具体处方？",
                "我每天服用多少克附子？")) {
            ChatResponse response = ragOrchestrationService.ask(question, version);
            assertThat(response.refused()).isTrue();
            assertThat(response.citations()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = IterationVersion.class, names = {"V1_VECTOR", "V2_HYBRID", "V3_GUARDED"})
    void unknownTopicsHaveNoSupportingCitations(IterationVersion version) {
        ChatResponse response = ragOrchestrationService.ask("量子计算机的纠错编码是什么？", version);
        assertThat(response.refused()).isTrue();
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void strictRemoteModeDoesNotFallBackDuringCooldown() {
        var remote = mock(LlamaIndexRagClient.class);
        var registry = mock(RetrievalStrategyRegistry.class);
        var properties = new RagEngineProperties("llama-index", null, null, null, false, Duration.ofHours(1));
        var service = new RagOrchestrationService(properties, remote, registry,
                new AnswerGenerationService(), mock(KnowledgeBaseRepository.class));
        when(remote.chat(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new ResourceAccessException("offline"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.ask("高血压", IterationVersion.V3_GUARDED, RetrievalFilter.none()))
                    .isInstanceOf(BusinessException.class);
        }
        verify(remote, times(1)).chat(anyString(), any(), any(), any(), anyString());
        verifyNoInteractions(registry);
    }

    @Test
    void guardedVersionShouldRefuseIndividualPrescriptionDose() {
        ChatResponse response = ragOrchestrationService.ask(
                "给我一个具体患者每天服用多少克附子的处方",
                IterationVersion.V3_GUARDED
        );

        assertThat(response.refused()).isTrue();
        assertThat(response.refusalReason()).contains("个体化处方");
        assertThat(response.answer()).contains("医师判断");
    }

    @Test
    void guardedVersionShouldRefusePossibleEmergencyQuestion() {
        ChatResponse response = ragOrchestrationService.ask(
                "胸痛持续二十分钟并且呼吸困难应该怎么办？",
                IterationVersion.V3_GUARDED
        );

        assertThat(response.refused()).isTrue();
        assertThat(response.refusalReason()).contains("急症");
        assertThat(response.answer()).contains("立即拨打 120 / 前往急诊");
    }

    @ParameterizedTest
    @EnumSource(IterationVersion.class)
    void everyVersionShortCircuitsCurrentEmergencyBeforeRetrieval(IterationVersion version) {
        ChatResponse response = ragOrchestrationService.ask("我现在胸痛", version);
        assertThat(response.refused()).isTrue();
        assertThat(response.answer()).contains("立即拨打 120 / 前往急诊");
        assertThat(response.citations()).isEmpty();
        assertThat(response.retrieval().strategy()).isEqualTo("safety-triage");
    }

    @ParameterizedTest
    @EnumSource(IterationVersion.class)
    void emergencyBypassesRemoteAndRetrievalForEveryVersion(IterationVersion version) {
        var remote = mock(LlamaIndexRagClient.class);
        var registry = mock(RetrievalStrategyRegistry.class);
        var properties = new RagEngineProperties("llama-index", null, null, null, false, Duration.ofSeconds(1));
        var service = new RagOrchestrationService(properties, remote, registry,
                new AnswerGenerationService(), mock(KnowledgeBaseRepository.class));

        ChatResponse response = service.ask("突然胸口很痛，喘不上气", version);

        assertThat(response.refused()).isTrue();
        assertThat(response.answer()).contains("立即拨打 120 / 前往急诊");
        verifyNoInteractions(remote, registry);
    }
}
