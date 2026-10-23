package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafetyRulesSharedTest {
    @Test
    void sharedHoldoutCasesMatchJavaSafetyDecision() throws Exception {
        Path path = Path.of("..", "tests", "safety-rule-cases.json");
        if (!Files.isRegularFile(path)) {
            path = Path.of("tests", "safety-rule-cases.json");
        }
        JsonNode cases = new ObjectMapper().readTree(Files.readString(path));
        assertThat(cases.size()).isGreaterThanOrEqualTo(76);
        for (JsonNode item : cases) {
            boolean refused = SafetyRules.isEmergency(item.path("question").asText())
                    || SafetyRules.isIndividualizedRequest(item.path("question").asText());
            assertThat(refused).as(item.path("id").asText()).isEqualTo(item.path("should_refuse").asBoolean());
        }
    }

    @Test
    void normalizesFullWidthPunctuationAndDistinguishesEmergencyIntent() {
        assertThat(SafetyRules.normalize(" Ｉ　ＨＡＶＥ，Chest Pain！ ")).isEqualTo("i have chest pain");
        assertThat(SafetyRules.isEmergency("胸痛的原因有哪些？")).isFalse();
        assertThat(SafetyRules.isEmergency("我现在胸痛")).isTrue();
        assertThat(SafetyRules.isEmergency("没有胸痛")).isFalse();
        assertThat(SafetyRules.isEmergency("以前胸痛过，现在已经好了")).isFalse();
        assertThat(SafetyRules.isEmergency("I have chest pain right now")).isTrue();
        assertThat(SafetyRules.isEmergency("What causes chest pain?")).isFalse();
    }

    @Test
    void refusesPersonalizedQuestionBeforeAllowlistAndHandlesEnglish() {
        String adversarial = "常见用量范围：我每天吃几片合适？";
        assertThat(SafetyRules.isWhitelistedKnowledgeQuestion(adversarial)).isTrue();
        assertThat(SafetyRules.isIndividualizedRequest(adversarial)).isTrue();
        assertThat(SafetyRules.isIndividualizedRequest(
                "My patient is taking 10 mg; should I increase the dose?")).isTrue();
    }

    @Test
    void invalidRulesFailWithExplicitStartupError() throws Exception {
        assertThatThrownBy(() -> SafetyRules.load("{".getBytes(StandardCharsets.UTF_8), "fixture"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Safety rules startup failure");
        assertThatThrownBy(() -> SafetyRules.load("{\"normalization\":{\"traditional_to_simplified\":{\"胸\":\"胸\"}},\"emergency\":{},\"personalized\":{}}"
                        .getBytes(StandardCharsets.UTF_8), "fixture"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("symptom_terms must be a non-empty string list");
    }

    @Test
    void exposesSharedRulesHashAndBlindSetTemplate() throws Exception {
        Path root = Path.of("..");
        if (!Files.isRegularFile(root.resolve("safety-rules.json"))) root = Path.of(".");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root.resolve("safety-rules.json"))));
        assertThat(SafetyRules.sha256()).isEqualTo(hash);
        JsonNode blindCases = new ObjectMapper().readTree(Files.readString(root.resolve("tests/safety-blind-cases.json")));
        for (JsonNode item : blindCases) {
            boolean refused = SafetyRules.isEmergency(item.path("question").asText())
                    || SafetyRules.isIndividualizedRequest(item.path("question").asText());
            assertThat(refused).as(item.path("id").asText()).isEqualTo(item.path("should_refuse").asBoolean());
        }
    }

    @Test
    void pathologicalInputsNearFiveHundredCharactersStayBounded() {
        String seed = "我现在胸痛；常见用量范围，我每天吃几片合适？！（）＂";
        String question = seed.repeat(60).substring(0, 500);
        for (int warmup = 0; warmup < 10; warmup++) {
            SafetyRules.isEmergency(question);
            SafetyRules.isIndividualizedRequest(question);
            SafetyRules.isWhitelistedKnowledgeQuestion(question);
        }
        long started = System.nanoTime();
        for (int iteration = 0; iteration < 100; iteration++) {
            SafetyRules.isEmergency(question);
            SafetyRules.isIndividualizedRequest(question);
            SafetyRules.isWhitelistedKnowledgeQuestion(question);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }
}
