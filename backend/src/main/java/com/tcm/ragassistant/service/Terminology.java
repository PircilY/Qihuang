package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import java.util.regex.Pattern;

/** Both engines read the same names-only JSON resources. */
public final class Terminology {
    public static final Terminology SHARED = new Terminology();
    private final JsonNode entries;
    private final Set<String> conflictingIds = new HashSet<>();
    public final Set<String> stop = new HashSet<>();
    public final double genericWeight, expansionWeight, maximumRelatedFrequency;
    private Terminology() {
        try {
            var mapper = new ObjectMapper();
            try (var in = new ClassPathResource("data/tcm-terminology.json").getInputStream()) {
                entries = mapper.readTree(in).get("entries");
            }
            if (Boolean.parseBoolean(System.getProperty("terminology.downgrade-conflicts", "true"))) {
                try (var in = new ClassPathResource("data/terminology-conflicts.json").getInputStream()) {
                    mapper.readTree(in).path("conflictingEntryIds").forEach(value -> conflictingIds.add(value.asText()));
                }
            }
            JsonNode policy;
            try (var in = new ClassPathResource("data/retrieval-stopwords.json").getInputStream()) {
                policy = mapper.readTree(in);
            }
            genericWeight = policy.get("genericWeight").asDouble();
            expansionWeight = policy.get("expansionWeight").asDouble();
            maximumRelatedFrequency = policy.get("maximumRelatedFrequency").asDouble();
            policy.get("en").forEach(term -> stop.add(term.asText()));
            policy.get("zh").forEach(term -> {
                stop.add(term.asText());
                stop.addAll(new TextTokenizer().tokenize(term.asText()));
            });
        } catch (Exception e) { throw new IllegalStateException("Shared terminology unavailable", e); }
    }
    private List<String> aliases(JsonNode entry, boolean chinese) {
        var result = new ArrayList<String>();
        if (chinese) {
            result.add(entry.get("zh").asText());
            entry.get("zhAliases").forEach(value -> result.add(value.asText()));
        } else entry.get("en").forEach(value -> result.add(value.asText()));
        return result;
    }
    public static boolean contains(String text, String alias) {
        text = text.toLowerCase(Locale.ROOT); alias = alias.toLowerCase(Locale.ROOT);
        return alias.matches(".*[a-z0-9].*")
                ? Pattern.compile("(?<![a-z0-9])" + Pattern.quote(alias) + "(?![a-z0-9])").matcher(text).find()
                : text.contains(alias);
    }
    public Features query(String question, boolean enabled, TextTokenizer tokenizer) {
        var original = tokenizer.tokenizeWithFrequency(question);
        var weights = new LinkedHashMap<String, Double>();
        original.forEach(term -> weights.merge(term, enabled && stop.contains(term) ? genericWeight : 1.0, Double::sum));
        var notes = new ArrayList<String>();
        if (enabled) {
            var extra = new TreeSet<String>();
            for (var entry : entries) {
                if (conflictingIds.contains(entry.path("id").asText())) continue;
                boolean zh = aliases(entry, true).stream().anyMatch(alias -> contains(question, alias));
                if (!zh && aliases(entry, false).stream().noneMatch(alias -> contains(question, alias))) continue;
                var expansion = aliases(entry, !zh);
                expansion.forEach(alias -> extra.addAll(tokenizer.tokenize(alias)));
                notes.add(entry.get("id").asText() + ":" + entry.get("zh").asText() + "↔"
                        + String.join("|", expansion) + "（" + entry.get("sourceType").asText() + "）");
            }
            extra.forEach(term -> weights.putIfAbsent(term, expansionWeight * (stop.contains(term) ? genericWeight : 1)));
        }
        return new Features(weights, notes);
    }
    public Map<String, List<String>> extract(String text) {
        var result = new LinkedHashMap<String, List<String>>();
        result.put("disease", new ArrayList<>()); result.put("herbs", new ArrayList<>());
        result.put("formulas", new ArrayList<>()); result.put("syndromes", new ArrayList<>());
        var targets = Map.of("disease", "disease", "herb", "herbs", "formula", "formulas", "syndrome", "syndromes");
        for (var entry : entries) {
            if (conflictingIds.contains(entry.path("id").asText())) continue;
            String target = targets.get(entry.path("kind").asText());
            if (target == null) continue;
            var aliases = new ArrayList<String>(); aliases.add(entry.path("zh").asText());
            entry.path("zhAliases").forEach(value -> aliases.add(value.asText()));
            entry.path("en").forEach(value -> aliases.add(value.asText()));
            if (aliases.stream().filter(alias -> !alias.isBlank()).anyMatch(alias -> contains(text, alias))) {
                String name = entry.path("zh").asText();
                if (!result.get(target).contains(name)) result.get(target).add(name);
            }
        }
        return result;
    }
    public record Features(Map<String, Double> weights, List<String> expansions) { }
}
