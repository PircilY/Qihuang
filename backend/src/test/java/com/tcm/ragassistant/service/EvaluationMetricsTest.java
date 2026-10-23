package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.CitationResponse;
import com.tcm.ragassistant.dto.RetrievalTrace;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationMetricsTest {

    @Test
    void unsynchronizedEvaluationIsRejectedBeforeAskingAnyQuestion() {
        var rag = mock(RagOrchestrationService.class);
        when(rag.remoteSyncState()).thenReturn("PENDING");
        var service = new EvaluationService(rag, new ObjectMapper(), new org.springframework.core.io.DefaultResourceLoader(),
                new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016), new CitationSupportEvaluator(new TextTokenizer()));
        assertThatThrownBy(service::run).isInstanceOf(com.tcm.ragassistant.exception.BusinessException.class)
                .hasMessageContaining("未同步");
        org.mockito.Mockito.verify(rag, org.mockito.Mockito.never()).askExtractive(anyString(), any(), any());
    }

    @Test
    void evaluationMaximumCaseLimitAppliesBeforeQueryExecution() {
        var rag = mock(RagOrchestrationService.class);
        var service = new EvaluationService(rag, new ObjectMapper(), new org.springframework.core.io.DefaultResourceLoader(),
                new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016), new CitationSupportEvaluator(new TextTokenizer()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maximumCases", 49);
        assertThatThrownBy(service::run).isInstanceOf(com.tcm.ragassistant.exception.BusinessException.class)
                .hasMessageContaining("超过配置上限");
        org.mockito.Mockito.verifyNoInteractions(rag);
    }

    @Test
    void mixedEngineRunIsInvalid() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return path.endsWith("holdout-cases.json")
                    ? new org.springframework.core.io.ClassPathResource("data/holdout-cases.json")
                    : new ByteArrayResource("""
                    [
                      {"id":"local","category":"metric","question":"local","expectedChunkIds":["x"],"shouldRefuse":false},
                      {"id":"remote","category":"metric","question":"remote","expectedChunkIds":["x"],"shouldRefuse":false}
                    ]
                    """.getBytes(StandardCharsets.UTF_8));
        });
        RagOrchestrationService rag = mock(RagOrchestrationService.class);
        when(rag.askExtractive(anyString(), any(IterationVersion.class), any())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            String engine = "remote".equals(question) ? "llama-index" : "java-local";
            return new ChatResponse("test", question, invocation.getArgument(1), "answer", false,
                    null, 0.5, 1, List.of(), new RetrievalTrace("test", 0, 0, 0, 0,
                    false, List.of(), engine, null, null, false), Instant.EPOCH);
        });
        EvaluationService service = new EvaluationService(rag, new ObjectMapper(), loader,
                new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016),
                new CitationSupportEvaluator(new TextTokenizer()));

        assertThatThrownBy(service::run)
                .isInstanceOf(com.tcm.ragassistant.exception.BusinessException.class)
                .hasMessageContaining("INVALID")
                .satisfies(exception -> assertThat(
                        ((com.tcm.ragassistant.exception.BusinessException) exception).code())
                        .isEqualTo("EVALUATION_INVALID_ENGINE_MIX"));
    }

    @Test
    void rankFourMustNotCountAsHitAtThreeAndFailuresMustNotBeDoubleCounted() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.endsWith("holdout-cases.json")) {
                return new org.springframework.core.io.ClassPathResource("data/holdout-cases.json");
            }
            return new ByteArrayResource("""
                [
                  {"id":"rank4","category":"metric","question":"rank4","expectedChunkIds":["four"],"shouldRefuse":false},
                  {"id":"miss","category":"metric","question":"miss","expectedChunkIds":["missing"],"shouldRefuse":false},
                  {"id":"refuse","category":"metric","question":"refuse","expectedChunkIds":[],"shouldRefuse":true}
                ]
                """.getBytes(StandardCharsets.UTF_8));
        });
        RagOrchestrationService rag = mock(RagOrchestrationService.class);
        when(rag.askExtractive(anyString(), any(IterationVersion.class), any())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            boolean refused = !"rank4".equals(question);
            List<CitationResponse> citations = refused ? List.of() : List.of(
                    citation("one"), citation("two"), citation("three"), citation("four"));
            String answer = refused ? "test" : "test evidence [1]\ntest evidence [2]\n"
                    + "test evidence [3]\ntest evidence [4]";
            return new ChatResponse("test", question, invocation.getArgument(1), answer, refused,
                    "", 0.5, 1, citations, null, Instant.EPOCH);
        });

        var result = new EvaluationService(
                rag,
                new ObjectMapper(),
                loader,
                new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016),
                new CitationSupportEvaluator(new TextTokenizer())
        ).run();
        assertThat(result.summaries()).allSatisfy(summary -> {
            assertThat(summary.hitRateAt3()).isZero();
            assertThat(summary.meanReciprocalRank()).isEqualTo(0.125);
            assertThat(summary.citationCoverage()).isEqualTo(0.5);
            assertThat(summary.refusalAccuracy()).isEqualTo(0.667);
            assertThat(summary.failureCount()).isEqualTo(1);
            assertThat(summary.trueReject()).isEqualTo(1);
            assertThat(summary.falseReject()).isEqualTo(1);
            assertThat(summary.falseAccept()).isZero();
            assertThat(summary.trueAccept()).isEqualTo(1);
            assertThat(summary.rejectPrecision()).isEqualTo(0.5);
            assertThat(summary.rejectRecall()).isEqualTo(1.0);
        });
        assertThat(result.caseResults().stream().filter(item -> item.expectedRefusal()))
                .allSatisfy(item -> assertThat(item.hitRank()).isEqualTo(-1));
    }

    private CitationResponse citation(String id) {
        return new CitationResponse(id, "test", "test", "", "", 2026,
                "", "", "", "", "test evidence", 0.5);
    }
}
