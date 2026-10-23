package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.EvidenceCoverageGate;
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
    public RetrievalOutput retrieve(String question, RetrievalFilter filter, CorpusId corpus) {
        RetrievalOutput hybrid = hybridStrategy.retrieveCandidates(question, filter, corpus);
        List<ScoredChunk> reranked = hybrid.chunks().stream()
                .map(candidate -> rerank(question, candidate))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(candidate -> candidate.chunk().id()))
                .toList();
        boolean sufficient = !reranked.isEmpty()
                && reranked.get(0).score() >= properties.minimumEvidenceScore();
        List<ScoredChunk> rankedTopK = sufficient
                ? reranked.stream().limit(properties.finalLimit()).toList()
                : List.of();
        EvidenceCoverageGate.Assessment coverage = EvidenceCoverageGate.assess(
                question,
                rankedTopK.stream().map(candidate -> candidate.chunk().content()).toList()
        );
        sufficient = sufficient && coverage.covered();
        List<ScoredChunk> selected = sufficient ? rankedTopK : List.of();
        List<String> notes = new ArrayList<>(hybrid.trace().notes());
        notes.add("fusion=weighted-rrf");
        notes.add("deduplication=chunk-id");
        notes.add("ranking=rrf+metadata");
        notes.add("gate=post-ranking-score+question-evidence-coverage");
        notes.add("evidence-coverage=" + coverage.reasonCode());
        notes.add(sufficient ? "证据分数与问题属性覆盖均通过" : "证据不足或问题属性未覆盖，触发拒答");

        return new RetrievalOutput(
                selected,
                new RetrievalTrace(
                        "guarded-hybrid",
                        hybrid.trace().lexicalCandidateCount(),
                        hybrid.trace().vectorCandidateCount(),
                        hybrid.trace().fusedCandidateCount(),
                        selected.size(),
                        sufficient,
                        notes,
                        "java-local",
                        version().name(),
                        properties.minimumEvidenceScore(),
                        !sufficient
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
        return value != null && !value.isBlank()
                && question.contains(tokenizer.normalize(value));
    }

    private boolean containsAny(String question, List<String> values) {
        return values.stream().anyMatch(value -> containsAny(question, value));
    }
}

