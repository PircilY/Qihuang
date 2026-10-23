package com.tcm.ragassistant.service;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LexicalScorer {
    private final RetrievalIndex index;
    private final TextTokenizer tokenizer;
    public LexicalScorer(KnowledgeBaseRepository repository, TextTokenizer tokenizer) { this(new RetrievalIndex(repository, tokenizer), tokenizer); }
    @org.springframework.beans.factory.annotation.Autowired
    public LexicalScorer(RetrievalIndex index, TextTokenizer tokenizer) { this.index = index; this.tokenizer = tokenizer; }
    public List<ScoredChunk> score(String question, int limit) { return score(question, limit, RetrievalFilter.none(), CorpusId.BASELINE); }
    public List<ScoredChunk> score(String question, int limit, RetrievalFilter filter, CorpusId corpus) {
        var snapshot = index.snapshot(corpus);
        var tokens = tokenizer.tokenizeWithFrequency(question);
        var unique = new HashSet<>(tokens);
        Set<String> ids = new HashSet<>();
        unique.forEach(term -> ids.addAll(snapshot.postings().getOrDefault(term, Set.of())));
        
        return ids.stream().map(snapshot.entries()::get)
                .filter(entry -> filter == null || filter.matches(entry.chunk()))
                .filter(entry -> index.related(question, unique, entry))
                .map(entry -> {
                    double score = 0;
                    for (String token : tokens) {
                        int tf = entry.frequencies().getOrDefault(token, 0);
                        if (tf > 0) score += snapshot.idf().getOrDefault(token, 0.0) * tf * 2.4 /
                                (tf + 1.4 * (0.28 + 0.72 * entry.length() / snapshot.averageLength()));
                    }
                    return new ScoredChunk(entry.chunk(), score, score, 0, "lexical");
                }).filter(entry -> entry.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(entry -> entry.chunk().id()))
                .limit(limit).toList();
    }
}
