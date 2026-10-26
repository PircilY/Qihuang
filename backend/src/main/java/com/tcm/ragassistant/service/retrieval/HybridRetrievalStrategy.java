package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.LexicalScorer;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.VectorSimilarityScorer;
import com.tcm.ragassistant.service.TextTokenizer;
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
    private final TextTokenizer tokenizer;

    public HybridRetrievalStrategy(
            LexicalScorer lexicalScorer,
            VectorSimilarityScorer vectorScorer,
            RetrievalProperties properties
    ) {
        this(lexicalScorer, vectorScorer, properties, new TextTokenizer());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HybridRetrievalStrategy(
            LexicalScorer lexicalScorer,
            VectorSimilarityScorer vectorScorer,
            RetrievalProperties properties,
            TextTokenizer tokenizer
    ) {
        this.lexicalScorer = lexicalScorer;
        this.vectorScorer = vectorScorer;
        this.properties = properties;
        this.tokenizer = tokenizer;
    }

    @Override
    public IterationVersion version() {
        return IterationVersion.V2_HYBRID;
    }

    @Override
    public RetrievalOutput retrieve(String question, RetrievalFilter filter, CorpusId corpus) {
        RetrievalOutput candidates = retrieveCandidates(question, filter, corpus);
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

    public RetrievalOutput retrieveCandidates(String question, RetrievalFilter filter, CorpusId corpus) {
        List<ScoredChunk> lexical = lexicalScorer.score(question, properties.candidateLimit(), filter, corpus);
        List<ScoredChunk> vector = vectorScorer.score(question, properties.candidateLimit(), filter, corpus);
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
                                queryNote(question),
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

    private String queryNote(String question) {
        var expansion = tokenizer.queryFeatures(question).expansions();
        return "查询扩展=" + (expansion.isEmpty() ? "无" : String.join("；", expansion));
    }

    public List<ScoredChunk> fuse(List<ScoredChunk> lexical, List<ScoredChunk> vector) {
        Map<String, FusionScore> scores = new LinkedHashMap<>();
        addRankScores(scores, lexical, properties.lexicalWeight(), true);
        addRankScores(scores, vector, properties.vectorWeight(), false);
        return scores.values().stream()
                .sorted(Comparator.comparingDouble(FusionScore::fusedScore).reversed()
                        .thenComparing(score -> score.chunk().chunk().id()))
                .map(score -> new ScoredChunk(
                        score.chunk().chunk(),
                        (properties.normalizedFusion() ? score.fusedScore() / properties.maximumFusionScore() : score.fusedScore()),
                        score.lexicalScore(),
                        score.vectorScore(),
                        "hybrid"
                ))
                .toList();
    }

    private void addRankScores(
            Map<String, FusionScore> scores,
            List<ScoredChunk> rankedChunks,
            double weight,
            boolean lexicalChannel
    ) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int rank = 0;
        for (ScoredChunk candidate : rankedChunks) {
            if (!seen.add(candidate.chunk().id())) continue;
            rank++;
            double rankContribution = weight / (properties.rrfRankConstant() + rank);
            scores.compute(candidate.chunk().id(), (id, current) -> {
                if (current == null) {
                    return new FusionScore(candidate, rankContribution,
                            lexicalChannel ? candidate.lexicalScore() : 0,
                            lexicalChannel ? 0 : candidate.vectorScore());
                }
                return new FusionScore(current.chunk(), current.fusedScore() + rankContribution,
                        current.lexicalScore() + (lexicalChannel ? candidate.lexicalScore() : 0),
                        current.vectorScore() + (lexicalChannel ? 0 : candidate.vectorScore()));
            });
        }
    }

    private record FusionScore(ScoredChunk chunk, double fusedScore, double lexicalScore, double vectorScore) {
    }
}

