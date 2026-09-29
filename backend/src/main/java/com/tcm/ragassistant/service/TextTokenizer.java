package com.tcm.ragassistant.service;

import org.springframework.stereotype.Component;

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
            Map.entry("高血压", List.of("眩晕", "头痛", "肝阳上亢")),
            Map.entry("冠心病", List.of("胸痹", "心痛", "气虚血瘀")),
            Map.entry("心力衰竭", List.of("心衰", "心水", "阳虚水泛")),
            Map.entry("心衰", List.of("心力衰竭", "心水", "阳虚水泛")),
            Map.entry("心律失常", List.of("心悸", "气阴两虚")),
            Map.entry("心悸", List.of("心律失常", "气阴两虚"))
    );

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = normalize(text);
        Set<String> tokens = new LinkedHashSet<>();

        Matcher latinMatcher = LATIN_TERM.matcher(normalized);
        while (latinMatcher.find()) {
            tokens.add(latinMatcher.group());
        }

        Matcher hanMatcher = HAN_SEQUENCE.matcher(normalized);
        while (hanMatcher.find()) {
            String sequence = hanMatcher.group();
            if (sequence.length() == 2) {
                tokens.add(sequence);
            }
            for (int index = 0; index < sequence.length() - 1; index++) {
                tokens.add(sequence.substring(index, index + 2));
            }
        }

        SYNONYMS.forEach((term, relatedTerms) -> {
            if (normalized.contains(term)) {
                tokens.add(term);
                tokens.addAll(relatedTerms);
            }
        });
        return new ArrayList<>(tokens);
    }

    public String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}

