package com.tcm.ragassistant.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks whether ranked evidence covers a small set of safety-critical request attributes.
 * Topic overlap alone is not evidence for an individualized dose or population-specific safety claim.
 */
public final class EvidenceCoverageGate {

    private static final Pattern MEDICATION = Pattern.compile(
            "降压药|药物|药品|用药|服药|吃药|药量|毫克|\\bmg\\b", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DOSE_OR_AMOUNT = Pattern.compile(
            "剂量|药量|加到|增加|减少|加药|减药|加量|减量|调高|调低|"
                    + "多少\\s*(?:毫克|mg|片|粒|克)?|几\\s*(?:毫克|mg|片|粒|克)|"
                    + "每天.{0,8}(?:吃|服).{0,5}(?:片|粒|多少)|应该吃多少|该吃多少",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ADJUSTMENT = Pattern.compile(
            "调整|加到|增加|减少|加药|减药|加量|减量|调高|调低",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PERSONALIZED_CONTEXT = Pattern.compile(
            "我|我的|今天|现在|当前|现用|正在服用|患者|病人|应该|应当|该不该|能不能|是否可以",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMPLICIT_MEDICATION_DOSE = Pattern.compile(
            "每天.{0,8}(?:吃|服).{0,5}(?:几片|多少)|(?:应该|应当|该)吃多少",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern PREGNANCY_REQUEST = Pattern.compile(
            "孕妇|妊娠|怀孕|孕期|pregnan", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PREGNANCY_EVIDENCE = Pattern.compile(
            "(?=.*(?:孕妇|妊娠|怀孕|孕期|pregnan))"
                    + "(?=.*(?:安全|风险|不良|禁忌|safe|safety|risk|adverse|harm))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BLOOD_PRESSURE = Pattern.compile(
            "血压|高血压|blood\\s*pressure|hypertension", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RANGE_REQUEST = Pattern.compile(
            "正常范围|正常值|标准范围|精确范围|正常区间|血压范围|正常数值|"
                    + "normal\\s*(?:range|value)|range",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AGE_GROUP = Pattern.compile(
            "不同年龄|年龄分层|年龄段|年龄|儿童|孩子|老人|老年人|成人|"
                    + "age\\s*groups?|children|child|elderly|older\\s*adults?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RANGE_EVIDENCE = Pattern.compile(
            "(?=.*(?:正常|范围|区间|normal|range))(?=.*\\d{2,3})",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern AGE_RANGE_EVIDENCE = Pattern.compile(
            "(?=.*(?:不同年龄|年龄分层|年龄段|儿童|孩子|老人|老年人|成人|"
                    + "age\\s*groups?|children|child|elderly|older\\s*adults?))"
                    + "(?=.*(?:正常|范围|区间|normal|range))(?=.*\\d{2,3})",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONDITION_SAFETY_REQUEST = Pattern.compile(
            "甘草.{0,16}(?:心脏|肾脏|心肾|heart|kidney).{0,16}(?:安全|风险|不良|safe|risk)|"
                    + "(?:心脏|肾脏|心肾|heart|kidney).{0,16}甘草.{0,16}(?:安全|风险|不良|safe|risk)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONDITION_SAFETY_EVIDENCE = Pattern.compile(
            "(?=.*(?:心脏|肾脏|心肾|heart|cardiac|kidney))"
                    + "(?=.*(?:安全|风险|不良|严重|safe|risk|adverse|severe))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HERB_DRUG_INTERACTION_REQUEST = Pattern.compile(
            "甘草.{0,24}(?:相互作用|合用|interaction).{0,24}(?:皮质类固醇|激素|corticosteroid)|"
                    + "(?:皮质类固醇|激素|corticosteroid).{0,24}甘草.{0,24}(?:相互作用|合用|interaction)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HERB_DRUG_INTERACTION_EVIDENCE = Pattern.compile(
            "(?=.*(?:甘草|licorice))(?=.*(?:皮质类固醇|corticosteroid))"
                    + "(?=.*(?:相互作用|interaction|reported|报告))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final String DOSE_REFUSAL = "现有公开文献无法支持个体化用药剂量调整建议（涉及个体化处方判断）。";
    private static final String COVERAGE_REFUSAL = "现有知识库未包含足以支持问题所要求人群或属性的证据。";

    private EvidenceCoverageGate() {
    }

    public static Assessment assess(String question, List<String> evidenceTexts) {
        String normalizedQuestion = normalize(question);
        String evidence = normalize(String.join(" ", evidenceTexts == null ? List.of() : evidenceTexts));

        if (isIndividualizedDoseAdjustment(normalizedQuestion)) {
            return new Assessment(false, "individualized-dose-adjustment", DOSE_REFUSAL);
        }

        // Population-specific claims need evidence for that population and its requested safety attribute.
        if (PREGNANCY_REQUEST.matcher(normalizedQuestion).find()
                && PREGNANCY_EVIDENCE.matcher(evidence).find()) {
            // Evidence that mentions pregnancy and a safety/risk relationship can answer this dimension.
        } else if (PREGNANCY_REQUEST.matcher(normalizedQuestion).find()) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        boolean asksForRange = BLOOD_PRESSURE.matcher(normalizedQuestion).find()
                && RANGE_REQUEST.matcher(normalizedQuestion).find();
        if (asksForRange) {
            Pattern requiredEvidence = AGE_GROUP.matcher(normalizedQuestion).find()
                    ? AGE_RANGE_EVIDENCE : RANGE_EVIDENCE;
            if (!requiredEvidence.matcher(evidence).find()) {
                return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
            }
        }

        if (CONDITION_SAFETY_REQUEST.matcher(normalizedQuestion).find()
                && !CONDITION_SAFETY_EVIDENCE.matcher(evidence).find()) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        if (HERB_DRUG_INTERACTION_REQUEST.matcher(normalizedQuestion).find()
                && !HERB_DRUG_INTERACTION_EVIDENCE.matcher(evidence).find()) {
            return new Assessment(false, "population-attribute-uncovered", COVERAGE_REFUSAL);
        }

        return new Assessment(true, "covered", "");
    }

    public static boolean isIndividualizedDoseAdjustment(String question) {
        String normalized = normalize(question);
        boolean implicitDose = IMPLICIT_MEDICATION_DOSE.matcher(normalized).find();
        boolean medicationMentioned = MEDICATION.matcher(normalized).find() || implicitDose;
        boolean doseRequest = DOSE_OR_AMOUNT.matcher(normalized).find();
        return medicationMentioned && doseRequest
                && (implicitDose || ADJUSTMENT.matcher(normalized).find()
                || PERSONALIZED_CONTEXT.matcher(normalized).find());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public record Assessment(boolean covered, String reasonCode, String refusalReason) {
    }
}
