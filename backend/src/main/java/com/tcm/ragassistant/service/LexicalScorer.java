package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LexicalScorer {

    private static final double K1 = 1.4;
    private static final double B = 0.72;

    private final KnowledgeBaseRepository repository;
    private final TextTokenizer tokenizer;

    public LexicalScorer(KnowledgeBaseRepository repository, TextTokenizer tokenizer) {
        this.repository = repository;
        this.tokenizer = tokenizer;
    }

    public List<ScoredChunk> score(String question, int limit) {
        return score(question, limit, RetrievalFilter.none());
    }

    public List<ScoredChunk> score(String question, int limit, RetrievalFilter filter) {
        List<String> queryTokens = tokenizer.tokenize(question);
        List<KnowledgeChunk> chunks = repository.findAll().stream()
                .filter(filter == null ? chunk -> true : filter::matches)
                .toList();
        List<Map<String, Integer>> termFrequencies = chunks.stream()
                .map(this::termFrequency)
                .toList();
        Map<String, Integer> documentFrequencies = buildDocumentFrequencies(termFrequencies);
        double averageDocumentLength = termFrequencies.stream()
                .mapToInt(map -> map.values().stream().mapToInt(Integer::intValue).sum())
                .average()
                .orElse(1.0);
        return java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    double score = scoreDocument(
                            queryTokens,
                            termFrequencies.get(index),
                            documentFrequencies,
                            termFrequencies.size(),
                            averageDocumentLength
                    );
                    KnowledgeChunk chunk = chunks.get(index);
                    return new ScoredChunk(chunk, score, score, 0.0, "lexical");
                })
                .filter(scored -> scored.score() > 0.0)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(limit)
                .toList();
    }

    private Map<String, Integer> termFrequency(KnowledgeChunk chunk) {
        Map<String, Integer> frequencies = new HashMap<>();
        String searchableText = String.join(" ",
                chunk.title(),
                chunk.disease(),
                String.join(" ", chunk.syndromes()),
                String.join(" ", chunk.formulas()),
                String.join(" ", chunk.herbs()),
                chunk.content()
        );
        tokenizer.tokenize(searchableText).forEach(token ->
                frequencies.merge(token, 1, Integer::sum)
        );
        return frequencies;
    }

    private Map<String, Integer> buildDocumentFrequencies(List<Map<String, Integer>> frequencies) {
        Map<String, Integer> result = new HashMap<>();
        frequencies.forEach(document ->
                document.keySet().forEach(token -> result.merge(token, 1, Integer::sum))
        );
        return result;
    }

    private double scoreDocument(
            List<String> queryTokens,
            Map<String, Integer> frequencies,
            Map<String, Integer> documentFrequencies,
            int documentCount,
            double averageDocumentLength
    ) {
        int documentLength = frequencies.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0.0;
        for (String token : queryTokens) {
            int frequency = frequencies.getOrDefault(token, 0);
            if (frequency == 0) {
                continue;
            }
            int documentFrequency = documentFrequencies.getOrDefault(token, 0);
            double idf = Math.log(1.0 + (documentCount - documentFrequency + 0.5)
                    / (documentFrequency + 0.5));
            double denominator = frequency + K1 * (1.0 - B + B * documentLength / averageDocumentLength);
            score += idf * (frequency * (K1 + 1.0)) / denominator;
        }
        return score;
    }
}

