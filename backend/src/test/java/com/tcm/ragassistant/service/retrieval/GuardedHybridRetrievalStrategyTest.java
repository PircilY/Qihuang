package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.TextTokenizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedHybridRetrievalStrategyTest {

    @Test
    void metadataRankingUsesTheFullCandidatePoolAndGateUsesRankScore() {
        HybridRetrievalStrategy hybrid = mock(HybridRetrievalStrategy.class);
        RetrievalProperties properties = new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016);
        GuardedHybridRetrievalStrategy strategy = new GuardedHybridRetrievalStrategy(
                hybrid, properties, new TextTokenizer()
        );
        List<ScoredChunk> candidates = List.of(
                scored("C-001", "其他病种", 0.10),
                scored("C-002", "其他病种", 0.09),
                scored("C-003", "其他病种", 0.08),
                scored("C-004", "其他病种", 0.07),
                scored("C-005", "高血压", 0.001)
        );
        when(hybrid.retrieveCandidates("高血压有哪些注意事项？", RetrievalFilter.none(), CorpusId.BASELINE))
                .thenReturn(new RetrievalOutput(candidates, new RetrievalTrace(
                        "hybrid-rrf", 5, 5, 5, 5, true, List.of()
                ), true));

        RetrievalOutput output = strategy.retrieve("高血压有哪些注意事项？", RetrievalFilter.none(), CorpusId.BASELINE);

        assertThat(output.chunks()).hasSize(4);
        assertThat(output.chunks().get(0).chunk().id()).isEqualTo("C-005");
        assertThat(output.chunks().get(0).score()).isGreaterThanOrEqualTo(0.016);
        assertThat(output.sufficientEvidence()).isTrue();
        assertThat(output.trace().version()).isEqualTo("V3_GUARDED");
        assertThat(output.trace().threshold()).isEqualTo(0.016);
        assertThat(output.trace().rejected()).isFalse();
    }

    @Test
    void metadataWeightsAreAppliedOncePerCategoryIncludingEvidenceLevel() {
        HybridRetrievalStrategy hybrid = mock(HybridRetrievalStrategy.class);
        GuardedHybridRetrievalStrategy strategy = new GuardedHybridRetrievalStrategy(
                hybrid, new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.016), new TextTokenizer()
        );
        KnowledgeChunk chunk = new KnowledgeChunk(
                "C-META", "DOC-META", "测试", "指南", "测试机构", 2026, "高血压",
                List.of("气虚证"), List.of("测试方"), List.of("甘草"), 0, 0, "正文",
                "A", List.of(), "元数据权重校验内容。", "meta", "ACTIVE", "v1", Instant.now()
        );
        when(hybrid.retrieveCandidates("高血压气虚证测试方甘草", RetrievalFilter.none(), CorpusId.BASELINE))
                .thenReturn(new RetrievalOutput(List.of(new ScoredChunk(chunk, 0.01, 0, 0, "test")),
                        new RetrievalTrace("hybrid-rrf", 1, 1, 1, 1, true, List.of()), true));

        double score = strategy.retrieve("高血压气虚证测试方甘草", RetrievalFilter.none(), CorpusId.BASELINE)
                .chunks().get(0).score();

        assertThat(score).isCloseTo(0.83, within(1.0e-12));
    }

    @Test
    void proportionalMetadataCannotOverturnStrongFusionWithWeakEvidence() {
        HybridRetrievalStrategy hybrid = mock(HybridRetrievalStrategy.class);
        var properties = new RetrievalProperties(8, 4, 60, 1, .85, .3, true, .25);
        var strategy = new GuardedHybridRetrievalStrategy(hybrid, properties, new TextTokenizer());
        when(hybrid.retrieveCandidates("高血压", RetrievalFilter.none(), CorpusId.BASELINE)).thenReturn(
                new RetrievalOutput(List.of(scored("FIXTURE-STRONG", "其他", .9), scored("FIXTURE-WEAK", "高血压", .2)),
                        new RetrievalTrace("hybrid", 2, 2, 2, 2, true, List.of()), true));
        var result = strategy.retrieve("高血压", RetrievalFilter.none(), CorpusId.BASELINE);
        assertThat(result.chunks().get(0).chunk().id()).isEqualTo("FIXTURE-STRONG");
        assertThat(result.chunks().get(1).score()).isCloseTo(.211, within(1e-12));
    }

    private ScoredChunk scored(String id, String disease, double score) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                id, "DOC-" + id, "测试", "指南", "测试机构", 2026, disease,
                List.of(), List.of(), List.of(), 0, 0, "正文", "未评级", List.of(),
                "用于检索契约测试的证据片段内容。", id, "ACTIVE", "v1", Instant.now()
        );
        return new ScoredChunk(chunk, score, score, score, "test");
    }
}
