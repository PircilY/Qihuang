package com.tcm.ragassistant.service;

import com.tcm.ragassistant.dto.CitationResponse;
import java.util.*;
import java.util.regex.Pattern;

/** Automatic proxies only; never a clinical factuality decision. */
public final class GenerationMetrics {
    private static final Pattern REF = Pattern.compile("\\[(\\d+)]");
    private GenerationMetrics() { }
    public static Map<String, Double> measure(String answer, List<CitationResponse> evidence,
                                              CitationSupportEvaluator evaluator) {
        var checks = evaluator.sentenceSupport(answer, evidence);
        long supported = checks.stream().filter(s -> s.startsWith("SUPPORTED\t")).count();
        long uncited = checks.stream().filter(s -> !REF.matcher(s).find()).count();
        int refs = 0, valid = 0;
        var matcher = REF.matcher(answer);
        while (matcher.find()) {
            refs++;
            String number = matcher.group(1);
            if (number.length() <= 9) {
                int n = Integer.parseInt(number);
                if (n > 0 && n <= evidence.size() && evidence.get(n-1).chunkId() != null
                        && !evidence.get(n-1).chunkId().isBlank()
                        && evidence.get(n-1).documentId() != null && !evidence.get(n-1).documentId().isBlank()) valid++;
            }
        }
        Set<String> answerPairs = bigrams(REF.matcher(answer).replaceAll(""));
        Set<String> evidencePairs = new HashSet<>();
        evidence.forEach(c -> evidencePairs.addAll(bigrams(c.quote())));
        long overlap = answerPairs.stream().filter(evidencePairs::contains).count();
        return Map.of("sentenceSupport", ratio(supported, checks.size()), "uncitedSentences", ratio(uncited, checks.size()),
                "validReferences", ratio(valid, refs), "bigramOverlap", ratio(overlap, answerPairs.size()));
    }
    private static double ratio(long n, long d) { return d == 0 ? 0 : (double)n/d; }
    private static Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        var words = Pattern.compile("[\\p{L}\\p{N}]+").matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (words.find()) {
            int[] points = words.group().codePoints().toArray();
            for (int i=1; i<points.length; i++) result.add(new String(points, i-1, 2));
        }
        return result;
    }
    public static Map<String, Map<String, Interval>> summarize(List<Map<String,Object>> rows, long seed) {
        Map<String, Map<String, Interval>> modes = new LinkedHashMap<>();
        for (String mode : List.of("A", "B", "C")) {
            var subset = rows.stream().filter(r -> mode.equals(r.get("mode"))).toList();
            Map<String, Interval> summary = new LinkedHashMap<>();
            for (String key : List.of("sentenceSupport", "uncitedSentences", "validReferences", "bigramOverlap",
                    "refusalRate", "answerLength", "latencyMs", "failureRate", "judgeSupport")) {
                double[] values = subset.stream().mapToDouble(r -> ((Number)((Map<?,?>)r.get("metrics")).get(key)).doubleValue()).toArray();
                if (values.length == 0) continue;
                Random random = new Random(seed);
                double[] boot = new double[1000];
                for (int b=0;b<boot.length;b++) {
                    for (int i=0;i<values.length;i++) boot[b] += values[random.nextInt(values.length)];
                    boot[b] /= values.length;
                }
                Arrays.sort(boot);
                summary.put(key, new Interval(Arrays.stream(values).average().orElse(0), boot[24], boot[974], values.length));
            }
            modes.put(mode, summary);
        }
        return modes;
    }
    public record Interval(double mean, double lower95, double upper95, int n) { }
}
