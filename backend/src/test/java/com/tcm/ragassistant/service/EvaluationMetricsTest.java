package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.CitationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationMetricsTest {

    @Test
    void rankFourMustNotCountAsHitAtThreeAndFailuresMustNotBeDoubleCounted() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(anyString())).thenReturn(new ByteArrayResource("""
                [
                  {"id":"rank4","category":"metric","question":"rank4","expectedChunkIds":["four"],"shouldRefuse":false},
                  {"id":"miss","category":"metric","question":"miss","expectedChunkIds":["missing"],"shouldRefuse":false},
                  {"id":"refuse","category":"metric","question":"refuse","expectedChunkIds":[],"shouldRefuse":true}
                ]
                """.getBytes(StandardCharsets.UTF_8)));
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
