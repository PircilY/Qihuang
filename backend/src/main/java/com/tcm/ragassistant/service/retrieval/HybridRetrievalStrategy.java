package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.LexicalScorer;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.VectorSimilarityScorer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HybridRetrievalStrategy implements RetrievalStrategy {

    private final LexicalScorer lexicalScorer;
    private final VectorSimilarityScorer vectorScorer;
    private final RetrievalProperties properties;

    public HybridRetrievalStrategy(
            LexicalScorer lexicalScorer,
            VectorSimilarityScorer vectorScorer,
            RetrievalProperties properties
    ) {
        this.lexicalScorer = lexicalScorer;
        this.vectorScorer = vectorScorer;
        this.properties = properties;
    }

    @Override
    public IterationVersion version() {
        return IterationVersion.V2_HYBRID;
    }

    @Override
    public RetrievalOutput retrieve(String question, RetrievalFilter filter) {
        RetrievalOutput candidates = retrieveCandidates(question, filter);
        List<ScoredChunk> selected = candidates.chunks().stream()
                .limit(properties.finalLimit())
                .toList();
        return new RetrievalOutput(
                selected,
                new RetrievalTrace(
                        "hybrid-rrf",
                        candidates.trace().lexicalCandidateCount(),
                        candidates.trace().vectorCandidateCount(),
                        candidates.trace().fusedCandidateCount(),
                        selected.size(),
                        !selected.isEmpty(),
                        candidates.trace().notes(),
                        "java-local",
                        version().name(),
                        null,
                        selected.isEmpty()
                ),
                !selected.isEmpty()
        );
    }

    public RetrievalOutput retrieveCandidates(String question, RetrievalFilter filter) {
        List<ScoredChunk> lexical = lexicalScorer.score(question, properties.candidateLimit(), filter);
        List<ScoredChunk> vector = vectorScorer.score(question, properties.candidateLimit(), filter);
        List<ScoredChunk> fused = fuse(lexical, vector);
        return new RetrievalOutput(
                fused,
                new RetrievalTrace(
                        "hybrid-rrf",
                        lexical.size(),
                        vector.size(),
                        fused.size(),
                        fused.size(),
                        !fused.isEmpty(),
                        List.of(
                                "BM25 与向量式召回并行",
                                "使用 Reciprocal Rank Fusion 融合排序",
                                "generation=grounded-extractive",
                                filter == null || filter.isEmpty()
                                        ? "检索范围为全库"
                                        : "检索范围：" + filter.description()
                        ),
                        "java-local",
                        version().name(),
                        null,
                        fused.isEmpty()
                ),
                !fused.isEmpty()
        );
    }

    public List<ScoredChunk> fuse(List<ScoredChunk> lexical, List<ScoredChunk> vector) {
        Map<String, FusionScore> scores = new LinkedHashMap<>();
        addRankScores(scores, lexical, properties.lexicalWeight());
        addRankScores(scores, vector, properties.vectorWeight());
        return scores.values().stream()
                .sorted(Comparator.comparingDouble(FusionScore::fusedScore).reversed()
                        .thenComparing(score -> score.chunk().chunk().id()))
                .map(score -> new ScoredChunk(
                        score.chunk().chunk(),
                        score.fusedScore(),
                        score.chunk().lexicalScore(),
                        score.chunk().vectorScore(),
                        "hybrid"
                ))
                .toList();
    }

    private void addRankScores(
            Map<String, FusionScore> scores,
            List<ScoredChunk> rankedChunks,
            double weight
    ) {
        for (int index = 0; index < rankedChunks.size(); index++) {
            ScoredChunk candidate = rankedChunks.get(index);
            double rankContribution = weight / (properties.rrfRankConstant() + index + 1.0);
            scores.compute(candidate.chunk().id(), (id, current) -> {
                if (current == null) {
                    return new FusionScore(candidate, rankContribution);
                }
                return new FusionScore(current.chunk(), current.fusedScore() + rankContribution);
            });
        }
    }

    private record FusionScore(ScoredChunk chunk, double fusedScore) {
    }
}

