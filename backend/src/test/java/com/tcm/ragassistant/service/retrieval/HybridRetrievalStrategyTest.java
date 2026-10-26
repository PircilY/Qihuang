package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.service.LexicalScorer;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.VectorSimilarityScorer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HybridRetrievalStrategyTest {

    private final HybridRetrievalStrategy strategy = new HybridRetrievalStrategy(
            mock(LexicalScorer.class), mock(VectorSimilarityScorer.class),
            new RetrievalProperties(8, 4, 60, 1.0, 0.85, 0.9));

    @Test
    void keepsIndependentChannelScoresWhenBothChannelsContainChunk() {
        ScoredChunk lexical = scored("A", 0.8, 0.0, "lexical");
        ScoredChunk vector = scored("A", 0.7, 0.0, "vector");

        ScoredChunk fused = strategy.fuse(List.of(lexical), List.of(vector)).get(0);

        assertThat(fused.lexicalScore()).isEqualTo(0.8);
        assertThat(fused.vectorScore()).isEqualTo(0.0);
        assertThat(fused.score()).isGreaterThan(0.0);
    }

    @Test
    void reportsZeroForMissingChannelAndDeduplicatesWithinChannel() {
        ScoredChunk first = scored("A", 0.8, 0.0, "lexical");
        ScoredChunk duplicate = scored("A", 0.4, 0.0, "lexical");
        ScoredChunk vector = scored("B", 0.0, 0.6, "vector");

        List<ScoredChunk> fused = strategy.fuse(List.of(first, duplicate), List.of(vector));

        ScoredChunk a = fused.stream().filter(item -> item.chunk().id().equals("A")).findFirst().orElseThrow();
        ScoredChunk b = fused.stream().filter(item -> item.chunk().id().equals("B")).findFirst().orElseThrow();
        assertThat(fused).hasSize(2);
        assertThat(a.lexicalScore()).isEqualTo(0.8);
        assertThat(a.vectorScore()).isZero();
        assertThat(b.lexicalScore()).isZero();
        assertThat(b.vectorScore()).isEqualTo(0.6);
    }

    private ScoredChunk scored(String id, double lexical, double vector, String channel) {
        KnowledgeChunk chunk = new KnowledgeChunk(id, "DOC", "title", "article", "publisher", 2026,
                "", List.of(), List.of(), List.of(), 0, 0, "body", "未评级", List.of(), "content",
                id, "ACTIVE", "v1", Instant.EPOCH);
        return new ScoredChunk(chunk, lexical + vector, lexical, vector, channel);
    }
}
