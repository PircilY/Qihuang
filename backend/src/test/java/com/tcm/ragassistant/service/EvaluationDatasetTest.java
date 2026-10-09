package com.tcm.ragassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void datasetHasRequiredDistributionAndValidKnowledgeReferences() throws Exception {
        List<EvaluationCase> cases = readCases();
        Map<String, Long> distribution = cases.stream().collect(Collectors.groupingBy(
                EvaluationCase::category, Collectors.counting()));
        Set<String> knowledgeChunkIds = new HashSet<>();
        Set<String> knowledgeDocumentIds = new HashSet<>();
        Map<String, String> knowledgeContent = new java.util.HashMap<>();
        try (InputStream input = resource("data/knowledge-base.json")) {
            for (JsonNode chunk : objectMapper.readTree(input)) {
                knowledgeChunkIds.add(chunk.get("id").asText());
                knowledgeDocumentIds.add(chunk.get("documentId").asText());
                knowledgeContent.put(chunk.get("id").asText(), chunk.get("content").asText());
            }
        }

        assertThat(cases).hasSize(50);
        assertThat(distribution).containsExactlyInAnyOrderEntriesOf(Map.of(
                "direct", 8L,
                "synonym", 8L,
                "vague", 6L,
                "long", 5L,
                "distractor", 6L,
                "no-answer", 8L,
                "citation", 5L,
                "multi-document", 4L
        ));
        assertThat(cases.stream().map(EvaluationCase::id)).doesNotHaveDuplicates();
        assertThat(cases.stream().map(EvaluationCase::question)).doesNotHaveDuplicates();
        assertThat(cases.stream().filter(item -> !item.shouldRefuse())).allSatisfy(item -> {
            assertThat(item.expectedChunkIds()).isNotEmpty().allMatch(knowledgeChunkIds::contains);
            assertThat(item.expectedDocumentIds()).isNotEmpty().allMatch(knowledgeDocumentIds::contains);
            assertThat(item.expectedClaims()).isNotEmpty();
        });
        assertThat(cases.stream().filter(EvaluationCase::shouldRefuse)).allSatisfy(item -> {
            assertThat(item.expectedChunkIds()).isEmpty();
            assertThat(item.expectedDocumentIds()).isEmpty();
            assertThat(item.expectedClaims()).isEmpty();
        });
        assertThat(cases.stream().filter(item -> "multi-document".equals(item.category())))
                .allSatisfy(item -> assertThat(item.expectedDocumentIds()).hasSizeGreaterThanOrEqualTo(2));
        CitationSupportEvaluator evaluator = new CitationSupportEvaluator(new TextTokenizer());
        assertThat(cases.stream().filter(item -> !item.shouldRefuse())).allSatisfy(item ->
                assertThat(item.expectedClaims()).allSatisfy(claim ->
                        assertThat(item.expectedChunkIds().stream()
                                .map(knowledgeContent::get)
                                .anyMatch(content -> evaluator.supports(claim, content)))
                                .as(item.id() + ": " + claim)
                                .isTrue()));
    }

    private List<EvaluationCase> readCases() throws Exception {
        try (InputStream input = resource("data/evaluation-cases.json")) {
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        }
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertThat(input).as(path).isNotNull();
        return input;
    }
}
