package com.tcm.ragassistant.service;

import org.springframework.stereotype.Component;
import com.tcm.ragassistant.domain.KnowledgeChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TextTokenizer {

    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9]{2,}");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("心力衰竭", List.of("心衰")),
            Map.entry("心衰", List.of("心力衰竭"))
    );
    private static final Map<String, List<String>> DOMAIN_ALIASES = Map.ofEntries(
            Map.entry("三高", List.of("高血压", "糖尿病", "高血脂")),
            Map.entry("心梗", List.of("心脏病发作")),
            Map.entry("脑卒中", List.of("中风")),
            Map.entry("血压偏高", List.of("高血压"))
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "证据", "研究", "安全", "疗效", "检查", "症状", "未归类"
    );

    public boolean isRelated(String question, KnowledgeChunk chunk) {
        String normalized = normalize(question);
        List<String> topics = new ArrayList<>(chunk.keywords());
        topics.add(chunk.disease());
        topics.addAll(chunk.herbs());
        topics.addAll(chunk.formulas());
        topics.addAll(chunk.syndromes());
        if (topics.stream().filter(term -> term != null && term.length() >= 2)
                .filter(term -> !GENERIC_TERMS.contains(term))
                .anyMatch(term -> normalized.contains(normalize(term)))) {
            return true;
        }
        if (hasDiseaseMetadataOverlap(question, chunk)) {
            return true;
        }
        // Unannotated imports still require meaningful lexical overlap.
        Set<String> contentTokens = new LinkedHashSet<>(tokenize(chunk.content()));
        return tokenize(question).stream().filter(term -> !GENERIC_TERMS.contains(term))
                .filter(contentTokens::contains).count() >= 3;
    }

    private boolean hasDiseaseMetadataOverlap(String question, KnowledgeChunk chunk) {
        Set<String> diseaseTokens = new LinkedHashSet<>(tokenize(chunk.disease()));
        return tokenize(question).stream()
                .filter(term -> term.length() >= 2)
                .filter(term -> !GENERIC_TERMS.contains(term))
                .anyMatch(diseaseTokens::contains);
    }

    /** Unique features for overlap tests; BM25 must use tokenizeWithFrequency. */
    public List<String> tokenize(String text) {
        return new ArrayList<>(new LinkedHashSet<>(tokenizeWithFrequency(text)));
    }

    public List<String> tokenizeWithFrequency(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder featureText = new StringBuilder(normalized);
        DOMAIN_ALIASES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (normalized.contains(entry.getKey())) entry.getValue().forEach(term -> featureText.append(' ').append(term));
        });
        List<String> tokens = new ArrayList<>();
        Matcher latinMatcher = LATIN_TERM.matcher(featureText);
        while (latinMatcher.find()) tokens.add(latinMatcher.group());
        Matcher hanMatcher = HAN_SEQUENCE.matcher(featureText);
        while (hanMatcher.find()) {
            String sequence = hanMatcher.group();
            for (int index = 0; index < sequence.length() - 1; index++) tokens.add(sequence.substring(index, index + 2));
        }
        SYNONYMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (normalized.contains(entry.getKey())) {
                tokens.add(entry.getKey()); tokens.addAll(entry.getValue());
            }
        });
        return tokens;
    }

    public String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}

