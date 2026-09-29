package com.tcm.ragassistant.domain;

import java.util.Locale;

public record RetrievalFilter(
        String disease,
        String sourceType,
        String evidenceLevel
) {
    public static RetrievalFilter none() {
        return new RetrievalFilter(null, null, null);
    }

    public boolean isEmpty() {
        return blank(disease) && blank(sourceType) && blank(evidenceLevel);
    }

    public boolean matches(KnowledgeChunk chunk) {
        return matchesValue(disease, chunk.disease())
                && matchesValue(sourceType, chunk.sourceType())
                && matchesValue(evidenceLevel, chunk.evidenceLevel());
    }

    public String description() {
        if (isEmpty()) {
            return "全库";
        }
        StringBuilder description = new StringBuilder();
        append(description, "疾病", disease);
        append(description, "来源", sourceType);
        append(description, "证据", evidenceLevel);
        return description.toString();
    }

    private static void append(StringBuilder target, String label, String value) {
        if (blank(value)) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(" / ");
        }
        target.append(label).append("：").append(value);
    }

    private static boolean matchesValue(String expected, String actual) {
        if (blank(expected)) {
            return true;
        }
        return actual != null
                && actual.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
