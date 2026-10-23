package com.tcm.ragassistant.service;

import com.tcm.ragassistant.dto.CitationResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

@Component
public class CitationSupportEvaluator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9]{2,}");
    private static final Set<String> GENERIC_TERMS = Set.of(
            "相关", "资料", "文献", "指出", "显示", "认为", "问题", "内容",
            "the", "and", "that", "with", "from", "have", "been", "this"
    );
    private static final List<String> NEGATIONS = List.of(
            "不会", "不能", "没有", "并非", "不", "无", "未发现", "尚无证据",
            "not", "no", "without", "cannot", "can't", "won't",
            "don't", "don’t", "prevent", "prevents", "prevented"
    );
    private static final List<String> UNCERTAINTY = List.of(
            "可能", "或许", "尚不明确", "证据不足", "may", "might", "could", "can",
            "linked", "reported", "uncertain"
    );

    private final TextTokenizer tokenizer;

    public CitationSupportEvaluator(TextTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public CitationEvaluation evaluate(String answer, List<CitationResponse> citations) {
        List<CitationResponse> safeCitations = citations == null ? List.of() : citations;
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        boolean presence = matcher.find();
        if (!presence) {
            return new CitationEvaluation(false, false, false);
        }

        matcher.reset();
        boolean validity = true;
        boolean support = true;
        int claimStart = 0;
        while (matcher.find()) {
            int citationNumber = Integer.parseInt(matcher.group(1));
            if (citationNumber < 1 || citationNumber > safeCitations.size()) {
                validity = false;
                support = false;
            } else {
                String segment = answer.substring(claimStart, matcher.start());
                int lastLineBreak = Math.max(segment.lastIndexOf('\n'), segment.lastIndexOf('\r'));
                String claim = segment.substring(lastLineBreak + 1)
                        .replaceFirst("^\\d+\\.\\s*", "").trim();
                CitationResponse citation = safeCitations.get(citationNumber - 1);
                if (citation.chunkId() == null || citation.chunkId().isBlank()
                        || citation.documentId() == null || citation.documentId().isBlank()
                        || !supports(claim, citation.quote())) {
                    support = false;
                }
            }
            claimStart = matcher.end();
        }
        return new CitationEvaluation(true, validity, validity && support);
    }

    public double expectedClaimCoverage(List<String> expectedClaims, String answer,
                                        List<CitationResponse> citations) {
        if (expectedClaims == null || expectedClaims.isEmpty()) {
            return 1.0;
        }
        List<String> evidence = new java.util.ArrayList<>();
        evidence.add(answer == null ? "" : answer);
        if (citations != null) {
            citations.forEach(citation -> evidence.add(citation.quote()));
        }
        long supported = expectedClaims.stream()
                .filter(claim -> evidence.stream().anyMatch(value -> supports(claim, value)))
                .count();
        return (double) supported / expectedClaims.size();
    }

    public List<String> sentenceSupport(String answer, List<CitationResponse> citations) {
        List<String> results = new ArrayList<>();
        if (answer == null || answer.isBlank()) return results;
        for (String raw : splitSentences(answer)) {
            String sentence = raw.trim();
            if (sentence.isBlank()) continue;
            if (sentence.contains("不替代医生诊断和处方") || sentence.contains("不替代医疗建议")) continue;
            Matcher refs = CITATION.matcher(sentence);
            List<Integer> indexes = new ArrayList<>();
            while (refs.find()) indexes.add(Integer.parseInt(refs.group(1)) - 1);
            String claim = CITATION.matcher(sentence).replaceAll("").trim()
                    .replaceFirst("^[\\d.、)）]+\\s*", "");
            boolean supported = !claim.isBlank() && !indexes.isEmpty() && citations != null
                    && indexes.stream().allMatch(index -> index >= 0 && index < citations.size()
                    && citations.get(index).chunkId() != null && citations.get(index).documentId() != null
                    && supports(claim, citations.get(index).quote()));
            results.add((supported ? "SUPPORTED" : "UNSUPPORTED") + "\t" + sentence);
        }
        return List.copyOf(results);
    }

    private List<String> splitSentences(String answer) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < answer.length(); index++) {
            char current = answer.charAt(index);
            if (current == '\r' || current == '\n') {
                if (index > start) sentences.add(answer.substring(start, index));
                if (current == '\r' && index + 1 < answer.length() && answer.charAt(index + 1) == '\n') index++;
                start = index + 1;
                continue;
            }
            if (".!?。！？".indexOf(current) < 0) continue;
            int boundary = index + 1;
            while (boundary < answer.length() && answer.charAt(boundary) == '[') {
                int close = answer.indexOf(']', boundary + 1);
                if (close < 0 || !answer.substring(boundary + 1, close).matches("\\d+")) break;
                boundary = close + 1;
            }
            if (boundary == answer.length() || Character.isWhitespace(answer.charAt(boundary))) {
                sentences.add(answer.substring(start, boundary));
                start = boundary;
                index = boundary - 1;
            }
        }
        if (start < answer.length()) sentences.add(answer.substring(start));
        return sentences;
    }

    boolean supports(String claim, String evidence) {
        if (claim == null || claim.isBlank() || evidence == null || evidence.isBlank()) {
            return false;
        }
        return List.of(evidence.split("(?<=[.!?。！？])" )).stream()
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .anyMatch(segment -> supportsSegment(claim, segment));
    }

    private boolean supportsSegment(String claim, String evidence) {
        String normalizedClaim = claim.toLowerCase(Locale.ROOT);
        String normalizedEvidence = evidence.toLowerCase(Locale.ROOT);
        if (!compatiblePolarity(polarity(normalizedClaim), polarity(normalizedEvidence))) {
            return false;
        }
        Set<String> claimNumbers = matches(NUMBER, normalizedClaim);
        if (!matches(NUMBER, normalizedEvidence).containsAll(claimNumbers)) {
            return false;
        }

        Set<String> claimTerms = meaningfulTerms(normalizedClaim);
        Set<String> evidenceTerms = meaningfulTerms(normalizedEvidence);
        claimTerms.retainAll(evidenceTerms);
        return claimTerms.size() >= 2;
    }

    private boolean compatiblePolarity(Polarity claim, Polarity evidence) {
        if (claim == Polarity.NEGATED || evidence == Polarity.NEGATED) {
            return claim == evidence;
        }
        return claim == evidence || claim == Polarity.UNCERTAIN;
    }

    private Set<String> meaningfulTerms(String value) {
        Set<String> terms = new LinkedHashSet<>(tokenizer.tokenize(value));
        terms.addAll(matches(LATIN_TERM, value));
        terms.removeIf(term -> term.length() < 2 || GENERIC_TERMS.contains(term));
        return terms;
    }

    private Set<String> matches(Pattern pattern, String value) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private Polarity polarity(String value) {
        if (NEGATIONS.stream().anyMatch(marker -> containsMarker(value, marker))) {
            return Polarity.NEGATED;
        }
        if (UNCERTAINTY.stream().anyMatch(marker -> containsMarker(value, marker))) {
            return Polarity.UNCERTAIN;
        }
        return Polarity.AFFIRMATIVE;
    }

    private boolean containsMarker(String value, String marker) {
        if (marker.chars().allMatch(character -> character < 128)) {
            return Pattern.compile("(?<![a-z])" + Pattern.quote(marker) + "(?![a-z])")
                    .matcher(value).find();
        }
        return value.contains(marker);
    }

    private enum Polarity {
        AFFIRMATIVE,
        NEGATED,
        UNCERTAIN
    }
}
