package com.tcm.ragassistant.service;

import java.util.List;

/**
 * Checks whether ranked evidence covers a small set of safety-critical request attributes.
 * Topic overlap alone is not evidence for an individualized dose or population-specific safety claim.
 */
public final class EvidenceCoverageGate {

    private static final String DOSE_REFUSAL = "现有公开文献无法支持个体化用药剂量调整建议（涉及个体化处方判断）。";
    private static final String COVERAGE_REFUSAL = "现有知识库未包含足以支持问题所要求人群或属性的证据。";

    private EvidenceCoverageGate() {
    }

    public static Assessment assess(String question, List<String> evidenceTexts) {
        String normalizedQuestion = SafetyRules.normalize(question);
        List<String> evidence = (evidenceTexts == null ? List.<String>of() : evidenceTexts).stream()
                .map(SafetyRules::normalize).toList();

        if (SafetyRules.isIndividualizedRequest(question)) {
            return new Assessment(false, "individualized-dose-adjustment", DOSE_REFUSAL);
        }

        // Population-specific claims need evidence for that population and its requested safety attribute.
        if (matches("pregnancy_request", normalizedQuestion)
                && !covered("pregnancy_evidence", evidence)) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        boolean asksForRange = matches("blood_pressure", normalizedQuestion)
                && matches("range_request", normalizedQuestion);
        if (asksForRange) {
            String requiredEvidence = matches("age_group", normalizedQuestion)
                    ? "age_range_evidence" : "range_evidence";
            if (!covered(requiredEvidence, evidence)) {
                return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
            }
        }

        if (matches("condition_safety_request", normalizedQuestion)
                && !covered("condition_safety_evidence", evidence)) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        if (matches("herb_drug_interaction_request", normalizedQuestion)
                && !covered("herb_drug_interaction_evidence", evidence)) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        if (matches("anticoagulant_adjustment_request", normalizedQuestion)
                && !covered("anticoagulant_adjustment_evidence", evidence)) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        return new Assessment(true, "covered", "");
    }

    public static boolean isIndividualizedDoseAdjustment(String question) {
        return SafetyRules.isIndividualizedRequest(question);
    }

    private static boolean covered(String rule, List<String> evidence) {
        return evidence.stream().anyMatch(chunk -> matches(rule, chunk));
    }

    private static boolean matches(String rule, String normalizedText) {
        return SafetyRules.matchesEvidenceRule(rule, normalizedText);
    }

    public record Assessment(boolean covered, String reasonCode, String refusalReason) {
    }
}
