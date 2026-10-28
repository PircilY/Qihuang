package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads and validates the shared repository-level safety rules. */
public final class SafetyRules {
    private static final Rules RULES = loadDefault();
    private static final Pattern EXPLICIT_MEDICATION_PLAN = Pattern.compile(
            "停(?:掉|用|服|下)?[^药]{0,8}药"
            + "|(?:给|为|帮)(?:我|本人|患者|家人).{0,8}(?:开|制定).{0,8}(?:处方|药方|用药方案)"
            + "|(?:prescrib(?:e|ing)|(?:write|give).{0,8}(?:me|my)).{0,40}(?:prescription|medication|herb|formula)");

    private SafetyRules() {
    }

    public static String sha256() {
        return RULES.sha256();
    }

    public static boolean matchesEvidenceRule(String name, String normalizedText) {
        Pattern pattern = RULES.evidencePatterns().get(name);
        if (pattern == null) throw new IllegalArgumentException("Unknown evidence coverage rule: " + name);
        return pattern.matcher(normalizedText).find();
    }

    public static boolean isEmergency(String question) {
        String normalized = normalize(question);
        List<String> symptoms = RULES.emergencySymptoms().stream()
                .filter(term -> isActiveSymptom(normalized, term)).toList();
        if (symptoms.isEmpty()) return false;
        if (containsAny(normalized, RULES.knowledgeIntentTerms())) return false;
        boolean critical = RULES.criticalSymptoms().stream().anyMatch(symptoms::contains);
        boolean modifier = containsAny(normalized, RULES.emergencyModifiers())
                || containsAny(normalized, RULES.currentPersonTerms());
        int score = Math.max(critical ? 2 : 0, symptoms.size() + (modifier ? 1 : 0));
        return score >= RULES.emergencyThreshold();
    }

    public static boolean isIndividualizedRequest(String question) {
        String normalized = normalize(question);
        return containsAny(normalized, RULES.personTerms())
                && ((containsAny(normalized, RULES.medicationActions())
                && containsAny(normalized, RULES.doseTerms()))
                || EXPLICIT_MEDICATION_PLAN.matcher(normalized).find());
    }

    public static boolean isWhitelistedKnowledgeQuestion(String question) {
        String normalized = normalize(question);
        return RULES.whitelistPatterns().stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    public static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder mapped = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            mapped.append(RULES.traditionalToSimplified().getOrDefault(character, character));
        });
        StringBuilder output = new StringBuilder(mapped.length());
        int[] codePoints = mapped.toString().codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int cp = codePoints[index];
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                int previous = index > 0 ? codePoints[index - 1] : -1;
                int next = index + 1 < codePoints.length ? codePoints[index + 1] : -1;
                if (isAsciiAlphaNumeric(previous) && isAsciiAlphaNumeric(next)) output.append(' ');
            } else if (isPunctuationOrSymbol(cp)) {
                int previous = index > 0 ? codePoints[index - 1] : -1;
                int next = index + 1 < codePoints.length ? codePoints[index + 1] : -1;
                if (cp < 128 && isAsciiAlphaNumeric(previous) && isAsciiAlphaNumeric(next)
                        && !output.isEmpty() && output.charAt(output.length() - 1) != ' ') output.append(' ');
            } else {
                output.appendCodePoint(cp);
            }
        }
        return output.toString().trim();
    }

    private static boolean isAsciiAlphaNumeric(int codePoint) {
        return codePoint >= 0 && codePoint < 128 && Character.isLetterOrDigit(codePoint);
    }

    private static boolean isPunctuationOrSymbol(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    private static boolean contains(String text, String term) {
        String normalizedTerm = normalize(term);
        if (normalizedTerm.matches("[a-z0-9]+")) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedTerm) + "(?![a-z0-9])")
                    .matcher(text).find();
        }
        return text.contains(normalizedTerm);
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(term -> contains(text, term));
    }

    private static boolean isActiveSymptom(String normalized, String term) {
        String normalizedTerm = normalize(term);
        int start = normalized.indexOf(normalizedTerm);
        while (start >= 0) {
            String prefix = normalized.substring(Math.max(0, start - 12), start);
            String suffix = normalized.substring(Math.min(normalized.length(), start + normalizedTerm.length()),
                    Math.min(normalized.length(), start + normalizedTerm.length() + 32));
            boolean negated = containsAny(prefix, RULES.negationTerms()) || containsAny(suffix, RULES.negationTerms());
            boolean historical = containsAny(prefix, RULES.historyTerms()) || containsAny(suffix, RULES.historyTerms());
            if (!negated && !historical) return true;
            start = normalized.indexOf(normalizedTerm, start + normalizedTerm.length());
        }
        return false;
    }

    private static Rules loadDefault() {
        try {
            Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            for (int index = 0; index < 5 && current != null; index++) {
                Path candidate = current.resolve("safety-rules.json");
                if (Files.isRegularFile(candidate)) return load(Files.readAllBytes(candidate), candidate.toString());
                current = current.getParent();
            }
            try (InputStream stream = SafetyRules.class.getClassLoader().getResourceAsStream("safety-rules.json")) {
                if (stream == null) throw new IllegalStateException("Safety rules startup failure: safety-rules.json is missing");
                return load(stream.readAllBytes(), "classpath:safety-rules.json");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Safety rules startup failure: unable to read safety-rules.json", exception);
        }
    }

    static Rules load(byte[] raw, String source) {
        try {
            JsonNode root = new ObjectMapper().readTree(raw);
            if (root == null || !root.isObject()) throw invalid(source, "root must be an object");
            JsonNode mappingNode = root.path("normalization").path("traditional_to_simplified");
            if (!mappingNode.isObject() || mappingNode.isEmpty()) throw invalid(source, "normalization.traditional_to_simplified must be a non-empty object");
            Map<String, String> mapping = new java.util.HashMap<>();
            mappingNode.fields().forEachRemaining(entry -> {
                if (entry.getKey().codePointCount(0, entry.getKey().length()) != 1
                        || !entry.getValue().isTextual() || entry.getValue().asText().codePointCount(0, entry.getValue().asText().length()) != 1) {
                    throw invalid(source, "traditional_to_simplified entries must map one character to one character");
                }
                mapping.put(entry.getKey(), entry.getValue().asText());
            });
            JsonNode emergency = root.path("emergency");
            JsonNode personalized = root.path("personalized");
            List<String> symptoms = requiredStrings(emergency, "symptom_terms", source);
            List<String> critical = requiredStrings(emergency, "critical_symptom_terms", source);
            List<String> modifiers = requiredStrings(emergency, "time_severity_help_terms", source);
            List<String> negations = requiredStrings(emergency, "negation_terms", source);
            List<String> history = requiredStrings(emergency, "history_terms", source);
            List<String> knowledge = requiredStrings(emergency, "knowledge_intent_terms", source);
            List<String> current = requiredStrings(emergency, "current_person_terms", source);
            List<String> people = requiredStrings(personalized, "person_terms", source);
            List<String> actions = requiredStrings(personalized, "medication_action_terms", source);
            List<String> doses = requiredStrings(personalized, "dose_or_adjustment_terms", source);
            List<Pattern> whitelist = requiredStrings(personalized, "whitelist_patterns", source).stream()
                    .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)).toList();
            JsonNode evidenceCoverage = root.path("evidence_coverage");
            if (!evidenceCoverage.isObject() || evidenceCoverage.isEmpty()) {
                throw invalid(source, "evidence_coverage must be a non-empty object");
            }
            Map<String, Pattern> evidencePatterns = new java.util.HashMap<>();
            evidenceCoverage.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                    throw invalid(source, "evidence_coverage." + entry.getKey() + " must be a non-empty pattern");
                }
                try {
                    evidencePatterns.put(entry.getKey(), Pattern.compile(entry.getValue().asText(),
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
                } catch (java.util.regex.PatternSyntaxException exception) {
                    throw invalid(source, "evidence_coverage." + entry.getKey() + " is not a valid pattern");
                }
            });
            for (String requiredRule : List.of("pregnancy_request", "pregnancy_evidence", "blood_pressure",
                    "range_request", "age_group", "range_evidence", "age_range_evidence",
                    "condition_safety_request", "condition_safety_evidence", "herb_drug_interaction_request",
                    "herb_drug_interaction_evidence", "anticoagulant_adjustment_request",
                    "anticoagulant_adjustment_evidence")) {
                if (!evidencePatterns.containsKey(requiredRule)) {
                    throw invalid(source, "evidence_coverage." + requiredRule + " is required");
                }
            }
            int threshold = emergency.path("score_threshold").isIntegralNumber()
                    ? emergency.path("score_threshold").asInt(-1) : -1;
            if (threshold <= 0) throw invalid(source, "emergency.score_threshold must be a positive integer");
            return new Rules(Map.copyOf(mapping), symptoms, critical, modifiers, negations, history, knowledge,
                    current, people, actions, doses, whitelist, Map.copyOf(evidencePatterns), threshold,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Safety rules startup failure: invalid safety-rules.json at " + source, exception);
        }
    }

    private static List<String> requiredStrings(JsonNode section, String key, String source) {
        JsonNode value = section.path(key);
        if (!value.isArray() || value.isEmpty()) throw invalid(source, key + " must be a non-empty string list");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) throw invalid(source, key + " contains an empty/non-string entry");
            result.add(item.asText());
        });
        return List.copyOf(result);
    }

    private static IllegalStateException invalid(String source, String detail) {
        return new IllegalStateException("Safety rules startup failure: invalid safety-rules.json at " + source + ": " + detail);
    }

    record Rules(Map<String, String> traditionalToSimplified, List<String> emergencySymptoms,
                 List<String> criticalSymptoms, List<String> emergencyModifiers, List<String> negationTerms,
                 List<String> historyTerms, List<String> knowledgeIntentTerms, List<String> currentPersonTerms,
                 List<String> personTerms, List<String> medicationActions, List<String> doseTerms,
                 List<Pattern> whitelistPatterns, Map<String, Pattern> evidencePatterns,
                 int emergencyThreshold, String sha256) { }
}
