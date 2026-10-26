package com.tcm.ragassistant.service;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class VectorSimilarityScorer {
    private final RetrievalIndex index;
    private final TextTokenizer tokenizer;
    @org.springframework.beans.factory.annotation.Value("${app.retrieval.query-bridge:false}")
    private boolean queryBridge;
    public VectorSimilarityScorer(KnowledgeBaseRepository repository, TextTokenizer tokenizer) { this(new RetrievalIndex(repository, tokenizer), tokenizer); }
    @org.springframework.beans.factory.annotation.Autowired
    public VectorSimilarityScorer(RetrievalIndex index, TextTokenizer tokenizer) { this.index = index; this.tokenizer = tokenizer; }
    public List<ScoredChunk> score(String question, int limit) { return score(question, limit, RetrievalFilter.none(), CorpusId.BASELINE); }
    public List<ScoredChunk> score(String question, int limit, RetrievalFilter filter, CorpusId corpus) {
        var snapshot = index.snapshot(corpus);
        var features = features(question);
        var unique = features.weights().keySet();
        Set<String> ids = new HashSet<>();
        unique.forEach(term -> ids.addAll(snapshot.postings().getOrDefault(term, Set.of())));
        double[] query = RetrievalIndex.hashVector(features.weights());
        ids.addAll(snapshot.entries().keySet());
        return ids.stream().map(snapshot.entries()::get)
                .filter(entry -> filter == null || filter.matches(entry.chunk()))
                .filter(entry -> index.related(question, unique, entry))
                .map(entry -> {
                    double score = 0;
                    for (int i=0; i<query.length; i++) score += query[i] * entry.vector()[i];
                    return new ScoredChunk(entry.chunk(), score, 0, score, "vector");
                }).filter(entry -> entry.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(entry -> entry.chunk().id()))
                .limit(limit).toList();
    }

    private Terminology.Features features(String question) {
        if (queryBridge) return tokenizer.queryFeatures(question);
        var weights = new java.util.LinkedHashMap<String, Double>();
        tokenizer.tokenizeWithFrequency(question).forEach(term -> weights.merge(term, 1.0, Double::sum));
        return new Terminology.Features(weights, List.of());
    }
}
