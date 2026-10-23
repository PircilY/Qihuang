package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable query snapshots, with incremental token/posting/vector updates on imports. */
@Component
public class RetrievalIndex {
    private final KnowledgeBaseRepository repository;
    private final TextTokenizer tokenizer;
    private final Map<CorpusId, Snapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<CorpusId, Long> revisions = new java.util.concurrent.ConcurrentHashMap<>();
    public RetrievalIndex(KnowledgeBaseRepository repository, TextTokenizer tokenizer) {
        this.repository = repository; this.tokenizer = tokenizer;
        repository.onChange(this::refresh);
    }
    @jakarta.annotation.PostConstruct
    public void initialize() { for (CorpusId corpus : CorpusId.values()) refresh(corpus); }
    public Snapshot snapshot(CorpusId corpus) {
        long revision = repository.importRevisionNumber(corpus);
        if (!snapshots.containsKey(corpus) || !Objects.equals(revisions.get(corpus), revision)) refresh(corpus);
        return snapshots.get(corpus);
    }
    private void refresh(CorpusId corpus) {
        long revision = repository.importRevisionNumber(corpus);
        var chunks = repository.findAll(corpus);
        if (chunks == null) chunks = List.of();
        synchronized (this) {
        Snapshot previous = snapshots.get(corpus);
        Map<String, Entry> entries = previous == null ? new LinkedHashMap<>() : new LinkedHashMap<>(previous.entries());
        Map<String, Set<String>> postings = new HashMap<>();
        if (previous != null) previous.postings().forEach((term, ids) -> postings.put(term, new HashSet<>(ids)));
        Set<String> activeIds = new HashSet<>();
        for (KnowledgeChunk chunk : chunks) {
            if (!"ACTIVE".equals(chunk.status())) continue;
            activeIds.add(chunk.id());
            Entry existing = entries.get(chunk.id());
            if (existing != null && existing.chunk().equals(chunk)) continue;
            if (existing != null) removePostings(postings, existing);
            Entry entry = entry(chunk);
            entries.put(chunk.id(), entry);
            entry.frequencies().keySet().forEach(term -> postings.computeIfAbsent(term, key -> new HashSet<>()).add(chunk.id()));
        }
        for (String id : new ArrayList<>(entries.keySet())) {
            if (!activeIds.contains(id)) removePostings(postings, entries.remove(id));
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        postings.forEach((term, ids) -> { if (!ids.isEmpty()) frozen.put(term, Set.copyOf(ids)); });
        double average = entries.values().stream().mapToInt(Entry::length).average().orElse(1);
        Map<String, Double> idf = new HashMap<>();
        int count = entries.size();
        frozen.forEach((term, ids) -> idf.put(term, Math.log(1 + (count - ids.size() + .5) / (ids.size() + .5))));
        snapshots.put(corpus, new Snapshot(Map.copyOf(entries), Map.copyOf(frozen), Map.copyOf(idf), Math.max(1, average)));
        revisions.put(corpus, revision);
        }
    }
    private void removePostings(Map<String, Set<String>> postings, Entry entry) {
        entry.frequencies().keySet().forEach(term -> { Set<String> ids = postings.get(term); if (ids != null) ids.remove(entry.chunk().id()); });
    }
    private Entry entry(KnowledgeChunk chunk) {
        String searchable = String.join(" ", chunk.title(), chunk.disease(), String.join(" ", chunk.syndromes()),
                String.join(" ", chunk.formulas()), String.join(" ", chunk.herbs()), String.join(" ", chunk.keywords()), chunk.content());
        Map<String, Integer> frequencies = new HashMap<>();
        tokenizer.tokenizeWithFrequency(searchable).forEach(term -> frequencies.merge(term, 1, Integer::sum));
        List<String> topics = new ArrayList<>(chunk.keywords());
        topics.add(chunk.disease()); topics.addAll(chunk.syndromes()); topics.addAll(chunk.formulas()); topics.addAll(chunk.herbs());
        return new Entry(chunk, Map.copyOf(frequencies), frequencies.values().stream().mapToInt(Integer::intValue).sum(),
                hashVector(tokenizer.tokenizeWithFrequency(searchable)), Set.copyOf(tokenizer.tokenize(chunk.content())),
                Set.copyOf(tokenizer.tokenize(chunk.disease())), topics.stream().filter(Objects::nonNull)
                .filter(term -> term.length() >= 2 && !GENERIC.contains(term)).map(tokenizer::normalize).toList());
    }
    private static final Set<String> GENERIC = Set.of("证据", "研究", "安全", "疗效", "检查", "症状", "未归类");
    public boolean related(String question, Set<String> query, Entry entry) {
        String normalized = tokenizer.normalize(question);
        if (entry.topics().stream().anyMatch(normalized::contains)) return true;
        if (query.stream().filter(term -> !GENERIC.contains(term)).anyMatch(entry.diseaseTokens()::contains)) return true;
        return query.stream().filter(term -> !GENERIC.contains(term)).filter(entry.contentTokens()::contains).count() >= 3;
    }
    public static double[] hashVector(List<String> tokens) {
        double[] vector = new double[512];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String token : tokens) {
                byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
                int index = (((hash[0] & 255) << 24) | ((hash[1] & 255) << 16) | ((hash[2] & 255) << 8) | (hash[3] & 255)) & 511;
                vector[index] += ((hash[4] & 1) == 1 ? 1 : -1) * (1 + Math.min(token.length(), 8) / 8.0);
            }
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        double norm = Math.sqrt(Arrays.stream(vector).map(value -> value * value).sum());
        if (norm > 0) for (int index=0; index<vector.length; index++) vector[index] /= norm;
        return vector;
    }
    public record Entry(KnowledgeChunk chunk, Map<String, Integer> frequencies, int length, double[] vector,
                        Set<String> contentTokens, Set<String> diseaseTokens, List<String> topics) { }
    public record Snapshot(Map<String, Entry> entries, Map<String, Set<String>> postings,
                           Map<String, Double> idf, double averageLength) { }
}
