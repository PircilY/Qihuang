package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorSimilarityScorer {

    private final KnowledgeBaseRepository repository;
    private final TextTokenizer tokenizer;

    public VectorSimilarityScorer(KnowledgeBaseRepository repository, TextTokenizer tokenizer) {
        this.repository = repository;
        this.tokenizer = tokenizer;
    }

    public List<ScoredChunk> score(String question, int limit) {
        return score(question, limit, RetrievalFilter.none());
    }

    public List<ScoredChunk> score(String question, int limit, RetrievalFilter filter) {
        Map<String, Double> queryVector = toVector(question);
        List<KnowledgeChunk> chunks = repository.findAll().stream()
                .filter(filter == null ? chunk -> true : filter::matches)
                .toList();
        List<Map<String, Double>> documentVectors = chunks.stream()
                .map(this::toDocumentVector)
                .toList();
        return java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    double score = cosine(queryVector, documentVectors.get(index));
                    return new ScoredChunk(chunks.get(index), score, 0.0, score, "vector");
                })
                .filter(scored -> scored.score() > 0.0)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(limit)
                .toList();
    }

    private Map<String, Double> toDocumentVector(KnowledgeChunk chunk) {
        String searchableText = String.join(" ",
                chunk.title(),
                chunk.disease(),
                String.join(" ", chunk.syndromes()),
                String.join(" ", chunk.formulas()),
                String.join(" ", chunk.herbs()),
                chunk.content()
        );
        return toVector(searchableText);
    }

    private Map<String, Double> toVector(String text) {
        List<String> tokens = tokenizer.tokenize(text);
        Map<String, Double> vector = new HashMap<>();
        tokens.forEach(token -> vector.merge(token, 1.0, Double::sum));
        double norm = Math.sqrt(vector.values().stream()
                .mapToDouble(value -> value * value)
                .sum());
        if (norm == 0.0) {
            return vector;
        }
        vector.replaceAll((token, value) -> value / norm);
        return vector;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = smaller == left ? right : left;
        double score = 0.0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            score += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return score;
    }
}

