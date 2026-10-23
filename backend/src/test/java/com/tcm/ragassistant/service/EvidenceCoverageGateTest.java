package com.tcm.ragassistant.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCoverageGateTest {

    @Test
    void rejectsIndividualizedAntihypertensiveDoseAdjustment() {
        var assessment = EvidenceCoverageGate.assess(
                "我今天血压偏高，应该立刻把降压药加到多少毫克？",
                List.of("高血压患者可能感觉不到症状。")
        );

        assertThat(assessment.covered()).isFalse();
        assertThat(assessment.reasonCode()).isEqualTo("individualized-dose-adjustment");
        assertThat(assessment.refusalReason()).contains("个体化用药剂量调整");
    }

    @Test
    void rejectsPregnancySafetyWhenEvidenceOnlyCoversGeneralLicoriceSafety() {
        var assessment = EvidenceCoverageGate.assess(
                "孕妇使用甘草是否安全，有没有明确的妊娠风险数据？",
                List.of("Licorice can cause irregular heartbeat and cardiac arrest.")
        );

        assertThat(assessment.covered()).isFalse();
        assertThat(assessment.reasonCode()).isEqualTo("population-attribute-uncovered");
    }

    @Test
    void rejectsAgeSpecificNormalRangeWithoutAgeAndNumericRangeEvidence() {
        var assessment = EvidenceCoverageGate.assess(
                "不同年龄人群的正常血压精确范围分别是多少？",
                List.of("高血压患者可能感觉不到症状。")
        );

        assertThat(assessment.covered()).isFalse();
        assertThat(assessment.reasonCode()).isEqualTo("population-attribute-uncovered");
    }

    @Test
    void acceptsGeneralLicoriceAdverseEffectEvidence() {
        var assessment = EvidenceCoverageGate.assess(
                "长期或大量食用甘草可能产生哪些严重不良反应？",
                List.of("Licorice can cause serious adverse effects, including irregular heartbeat.")
        );

        assertThat(assessment.covered()).isTrue();
    }

    @Test
    void acceptsReportedLicoriceCorticosteroidInteractionWhenEvidenceCoversBoth() {
        var assessment = EvidenceCoverageGate.assess(
                "甘草与皮质类固醇之间是否报告过相互作用？",
                List.of("Interactions between licorice and corticosteroids have been reported.")
        );

        assertThat(assessment.covered()).isTrue();
    }

    @Test
    void genericAnticoagulantDoseQuestionNeedsSpecificEvidenceWithoutBecomingPersonalized() {
        String question = "甘草与华法林合用时应该如何调整抗凝药剂量？";
        var uncovered = EvidenceCoverageGate.assess(question, List.of("甘草可能影响心脏健康。"));

        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment(question)).isFalse();
        assertThat(uncovered.covered()).isFalse();
        assertThat(uncovered.reasonCode()).isEqualTo("population-attribute-uncovered");
        assertThat(EvidenceCoverageGate.assess(question, List.of(
                "甘草与华法林之间的相互作用可能需要评估抗凝药剂量调整。"
        )).covered()).isTrue();
    }

    @Test
    void acceptsHeartOrKidneyRiskWhenEvidenceCoversThoseConditions() {
        var assessment = EvidenceCoverageGate.assess(
                "甘草对本来心脏或肾脏不好的人安全吗？",
                List.of("Severe adverse effects have been linked to licorice in people with heart or kidney conditions.")
        );

        assertThat(assessment.covered()).isTrue();
    }

    @Test
    void doesNotTreatMedicationMentionOrLifestyleQuestionAsDoseAdjustment() {
        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment(
                "改变生活方式后仍可能需要服药吗？"
        )).isFalse();
        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment(
                "血压非常高时可能出现哪些症状？"
        )).isFalse();
        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment("我每天吃几片降压药？")).isTrue();
        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment("是否可以调整当前药物剂量？")).isTrue();
        assertThat(EvidenceCoverageGate.isIndividualizedDoseAdjustment("我应该增加药量或减少药量吗？")).isTrue();
    }
}
