package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.TextTokenizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class GuardedHybridRetrievalStrategy implements RetrievalStrategy {

    private final HybridRetrievalStrategy hybridStrategy;
    private final RetrievalProperties properties;
    private final TextTokenizer tokenizer;

    public GuardedHybridRetrievalStrategy(
            HybridRetrievalStrategy hybridStrategy,
            RetrievalProperties properties,
            TextTokenizer tokenizer
    ) {
        this.hybridStrategy = hybridStrategy;
        this.properties = properties;
        this.tokenizer = tokenizer;
    }

    @Override
    public IterationVersion version() {
        return IterationVersion.V3_GUARDED;
    }

    @Override
    public RetrievalOutput retrieve(String question, RetrievalFilter filter) {
        RetrievalOutput hybrid = hybridStrategy.retrieve(question, filter);
        List<ScoredChunk> reranked = hybrid.chunks().stream()
                .map(candidate -> rerank(question, candidate))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
        boolean sufficient = !reranked.isEmpty()
                && reranked.get(0).score() >= properties.minimumEvidenceScore();
        List<ScoredChunk> selected = sufficient
                ? reranked.stream().limit(properties.finalLimit()).toList()
                : List.of();
        List<String> notes = new ArrayList<>(hybrid.trace().notes());
        notes.add("按疾病、证型、方剂和中药元数据重排");
        notes.add(sufficient ? "证据分数达到回答阈值" : "证据不足，触发拒答");

        return new RetrievalOutput(
                selected,
                new RetrievalTrace(
                        "guarded-hybrid",
                        hybrid.trace().lexicalCandidateCount(),
                        hybrid.trace().vectorCandidateCount(),
                        hybrid.trace().fusedCandidateCount(),
                        selected.size(),
                        sufficient,
                        notes
                ),
                sufficient
        );
    }

    private ScoredChunk rerank(String question, ScoredChunk candidate) {
        KnowledgeChunk chunk = candidate.chunk();
        String normalizedQuestion = tokenizer.normalize(question);
        double metadataBoost = 0.0;
        metadataBoost += containsAny(normalizedQuestion, chunk.disease()) ? 0.22 : 0.0;
        metadataBoost += containsAny(normalizedQuestion, chunk.syndromes()) ? 0.20 : 0.0;
        metadataBoost += containsAny(normalizedQuestion, chunk.formulas()) ? 0.18 : 0.0;
        metadataBoost += containsAny(normalizedQuestion, chunk.herbs()) ? 0.10 : 0.0;
        double evidenceBoost = switch (chunk.evidenceLevel().toUpperCase(Locale.ROOT)) {
            case "A" -> 0.12;
            case "B" -> 0.08;
            case "C" -> 0.04;
            default -> 0.0;
        };
        return candidate.withScore(candidate.score() + metadataBoost + evidenceBoost, "guarded-hybrid-rerank");
    }

    private boolean containsAny(String question, String value) {
        return value != null && !value.isBlank() && question.contains(value.toLowerCase(Locale.ROOT));
    }

    private boolean containsAny(String question, List<String> values) {
        return values.stream().anyMatch(value -> containsAny(question, value));
    }
}

