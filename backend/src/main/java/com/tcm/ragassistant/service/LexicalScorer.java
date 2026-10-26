package com.tcm.ragassistant.service;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LexicalScorer {
    private final RetrievalIndex index;
    private final TextTokenizer tokenizer;
    @org.springframework.beans.factory.annotation.Value("${app.retrieval.query-bridge:false}")
    private boolean queryBridge;
    public LexicalScorer(KnowledgeBaseRepository repository, TextTokenizer tokenizer) { this(new RetrievalIndex(repository, tokenizer), tokenizer); }
    @org.springframework.beans.factory.annotation.Autowired
    public LexicalScorer(RetrievalIndex index, TextTokenizer tokenizer) { this.index = index; this.tokenizer = tokenizer; }
    public List<ScoredChunk> score(String question, int limit) { return score(question, limit, RetrievalFilter.none(), CorpusId.BASELINE); }
    public List<ScoredChunk> score(String question, int limit, RetrievalFilter filter, CorpusId corpus) {
        var snapshot = index.snapshot(corpus);
        var features = features(question);
        var unique = features.weights().keySet();
        Set<String> ids = new HashSet<>();
        unique.forEach(term -> ids.addAll(snapshot.postings().getOrDefault(term, Set.of())));
        
        return ids.stream().map(snapshot.entries()::get)
                .filter(entry -> filter == null || filter.matches(entry.chunk()))
                .filter(entry -> index.related(question, unique, entry))
                .map(entry -> {
                    double score = 0;
                    for (String token : features.weights().keySet()) {
                        int tf = entry.frequencies().getOrDefault(token, 0);
                        if (tf > 0) score += features.weights().get(token) * snapshot.idf().getOrDefault(token, 0.0) * tf * 2.4 /
                                (tf + 1.4 * (0.28 + 0.72 * entry.length() / snapshot.averageLength()));
                    }
                    return new ScoredChunk(entry.chunk(), score, score, 0, "lexical");
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
