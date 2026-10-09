package com.tcm.ragassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.EvaluationCaseResult;
import com.tcm.ragassistant.dto.EvaluationCategorySummary;
import com.tcm.ragassistant.dto.EvaluationRunResponse;
import com.tcm.ragassistant.dto.EvaluationSummary;
import com.tcm.ragassistant.exception.BusinessException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EvaluationService {

    static final String DATASET_NAME = "独立 RAG 回归评测集";
    static final String DATASET_VERSION = "2.0.0";

    private final RagOrchestrationService ragOrchestrationService;
    private final CitationSupportEvaluator citationEvaluator;
    private final int retrievalK;
    private final List<EvaluationCase> cases;

    public EvaluationService(
            RagOrchestrationService ragOrchestrationService,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            RetrievalProperties retrievalProperties,
            CitationSupportEvaluator citationEvaluator
    ) {
        this.ragOrchestrationService = ragOrchestrationService;
        this.citationEvaluator = citationEvaluator;
        this.retrievalK = retrievalProperties.finalLimit();
        Resource resource = resourceLoader.getResource("classpath:data/evaluation-cases.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.cases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            validateCases(cases);
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException("EVALUATION_DATA_LOAD_ERROR", "评测数据初始化失败", exception);
        }
    }

    public EvaluationRunResponse run() {
        List<EvaluationCaseResult> caseResults = new ArrayList<>();
        List<EvaluationSummary> summaries = new ArrayList<>();

        for (IterationVersion version : IterationVersion.values()) {
            List<CaseOutcome> outcomes = new ArrayList<>();
            for (EvaluationCase evaluationCase : cases) {
                ChatResponse response = ragOrchestrationService.askExtractive(
                        evaluationCase.question(), version, RetrievalFilter.none());
                CaseOutcome outcome = evaluate(evaluationCase, response);
                outcomes.add(outcome);
                caseResults.add(toResult(evaluationCase, version, response, outcome));
            }
            summaries.add(summarize(version, outcomes));
        }

        return new EvaluationRunResponse(
                DATASET_NAME,
                DATASET_VERSION,
                cases.size(),
                retrievalK,
                summaries,
                caseResults,
                Instant.now()
        );
    }

    private CaseOutcome evaluate(EvaluationCase evaluationCase, ChatResponse response) {
        List<String> retrievedChunkIds = response.citations().stream()
                .map(citation -> citation.chunkId()).toList();
        List<String> retrievedDocumentIds = response.citations().stream()
                .map(citation -> citation.documentId()).toList();
        List<String> expectedIds = evaluationCase.expectedChunkIds().isEmpty()
                ? evaluationCase.expectedDocumentIds() : evaluationCase.expectedChunkIds();
        List<String> retrievedIds = evaluationCase.expectedChunkIds().isEmpty()
                ? retrievedDocumentIds : retrievedChunkIds;
        int hitRank = evaluationCase.shouldRefuse() ? -1 : firstRelevantRank(expectedIds, retrievedIds);
        Set<String> expectedSet = new LinkedHashSet<>(expectedIds);
        Set<String> retrievedSet = new LinkedHashSet<>(retrievedIds);
        int matches = intersectionSize(expectedSet, retrievedSet);
        CitationEvaluation citation = citationEvaluator.evaluate(response.answer(), response.citations());
        double expectedClaimCoverage = evaluationCase.shouldRefuse() ? 1.0
                : citationEvaluator.expectedClaimCoverage(
                evaluationCase.expectedClaims(), response.answer(), response.citations());

        return new CaseOutcome(
                evaluationCase.category(),
                !evaluationCase.shouldRefuse(),
                hitRank,
                hitRank > 0 && hitRank <= retrievalK,
                ratio(matches, expectedSet.size()),
                hitRank > 0 ? round(1.0 / hitRank) : 0.0,
                jaccard(expectedSet, retrievedSet),
                expectedClaimCoverage,
                citation,
                evaluationCase.shouldRefuse(),
                response.refused(),
                evaluationCase.shouldRefuse() == response.refused(),
                response.confidence(),
                response.latencyMs(),
                retrievedDocumentIds,
                retrievedChunkIds
        );
    }

    private EvaluationCaseResult toResult(EvaluationCase evaluationCase, IterationVersion version,
                                          ChatResponse response, CaseOutcome outcome) {
        return new EvaluationCaseResult(
                evaluationCase.id(),
                evaluationCase.category(),
                evaluationCase.question(),
                version,
                evaluationCase.shouldRefuse(),
                evaluationCase.expectedDocumentIds(),
                evaluationCase.expectedChunkIds(),
                outcome.retrievedDocumentIds(),
                outcome.retrievedChunkIds(),
                outcome.hitRank() > 0,
                outcome.hitRank(),
                outcome.hitRank() == 1,
                outcome.hitRank() > 0 && outcome.hitRank() <= 3,
                outcome.hitAtK(),
                outcome.recallAtK(),
                outcome.reciprocalRank(),
                outcome.topKJaccard(),
                round(outcome.expectedClaimCoverage()),
                response.citations().size(),
                outcome.citation().presence(),
                outcome.citation().validity(),
                outcome.citation().support(),
                outcome.refusalCorrect(),
                response.confidence(),
                response.latencyMs()
        );
    }

    private EvaluationSummary summarize(IterationVersion version, List<CaseOutcome> outcomes) {
        EvaluationCategorySummary overall = summarizeGroup("overall", outcomes);
        Map<String, List<CaseOutcome>> grouped = new LinkedHashMap<>();
        outcomes.forEach(outcome -> grouped.computeIfAbsent(outcome.category(), ignored -> new ArrayList<>())
                .add(outcome));
        List<EvaluationCategorySummary> byCategory = grouped.entrySet().stream()
                .map(entry -> summarizeGroup(entry.getKey(), entry.getValue()))
                .toList();

        return new EvaluationSummary(
                version,
                overall.caseCount(),
                overall.retrievalCaseCount(),
                overall.hitRateAt1(),
                overall.hitRateAt3(),
                overall.hitRateAtK(),
                overall.recallAtK(),
                overall.meanReciprocalRank(),
                overall.topKJaccard(),
                overall.expectedClaimCoverage(),
                overall.citationPresenceRate(),
                overall.citationValidityRate(),
                overall.citationSupportRate(),
                overall.trueReject(),
                overall.falseReject(),
                overall.falseAccept(),
                overall.trueAccept(),
                overall.rejectPrecision(),
                overall.rejectRecall(),
                overall.rejectAccuracy(),
                round(outcomes.stream().mapToDouble(CaseOutcome::confidence).average().orElse(0.0)),
                round(outcomes.stream().mapToLong(CaseOutcome::latencyMs).average().orElse(0.0)),
                overall.failureCount(),
                byCategory
        );
    }

    private EvaluationCategorySummary summarizeGroup(String category, List<CaseOutcome> outcomes) {
        List<CaseOutcome> retrievalCases = outcomes.stream().filter(CaseOutcome::retrievalCase).toList();
        int trueReject = (int) outcomes.stream()
                .filter(outcome -> outcome.expectedRefusal() && outcome.actualRefusal()).count();
        int falseReject = (int) outcomes.stream()
                .filter(outcome -> !outcome.expectedRefusal() && outcome.actualRefusal()).count();
        int falseAccept = (int) outcomes.stream()
                .filter(outcome -> outcome.expectedRefusal() && !outcome.actualRefusal()).count();
        int trueAccept = (int) outcomes.stream()
                .filter(outcome -> !outcome.expectedRefusal() && !outcome.actualRefusal()).count();

        return new EvaluationCategorySummary(
                category,
                outcomes.size(),
                retrievalCases.size(),
                ratio(retrievalCases.stream().filter(outcome -> outcome.hitRank() == 1).count(),
                        retrievalCases.size()),
                ratio(retrievalCases.stream()
                        .filter(outcome -> outcome.hitRank() > 0 && outcome.hitRank() <= 3).count(),
                        retrievalCases.size()),
                ratio(retrievalCases.stream().filter(CaseOutcome::hitAtK).count(), retrievalCases.size()),
                average(retrievalCases, CaseOutcome::recallAtK),
                average(retrievalCases, CaseOutcome::reciprocalRank),
                average(retrievalCases, CaseOutcome::topKJaccard),
                average(retrievalCases, CaseOutcome::expectedClaimCoverage),
                ratio(retrievalCases.stream().filter(outcome -> outcome.citation().presence()).count(),
                        retrievalCases.size()),
                ratio(retrievalCases.stream().filter(outcome -> outcome.citation().validity()).count(),
                        retrievalCases.size()),
                ratio(retrievalCases.stream().filter(outcome -> outcome.citation().support()).count(),
                        retrievalCases.size()),
                trueReject,
                falseReject,
                falseAccept,
                trueAccept,
                ratio(trueReject, trueReject + falseReject),
                ratio(trueReject, trueReject + falseAccept),
                ratio(trueReject + trueAccept, outcomes.size()),
                (int) outcomes.stream().filter(this::failed).count()
        );
    }

    private boolean failed(CaseOutcome outcome) {
        if (!outcome.refusalCorrect()) {
            return true;
        }
        return outcome.retrievalCase() && (!outcome.hitAtK()
                || !outcome.citation().presence()
                || !outcome.citation().validity()
                || !outcome.citation().support()
                || outcome.expectedClaimCoverage() < 1.0);
    }

    private int firstRelevantRank(List<String> expectedIds, List<String> retrievedIds) {
        for (int index = 0; index < retrievedIds.size(); index++) {
            if (expectedIds.contains(retrievedIds.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private double jaccard(Set<String> expected, Set<String> retrieved) {
        Set<String> union = new LinkedHashSet<>(expected);
        union.addAll(retrieved);
        return union.isEmpty() ? 1.0 : round((double) intersectionSize(expected, retrieved) / union.size());
    }

    private double average(List<CaseOutcome> outcomes,
                           java.util.function.ToDoubleFunction<CaseOutcome> metric) {
        return round(outcomes.stream().mapToDouble(metric).average().orElse(0.0));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round((double) numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private void validateCases(List<EvaluationCase> evaluationCases) {
        Set<String> ids = new LinkedHashSet<>();
        for (EvaluationCase evaluationCase : evaluationCases) {
            if (evaluationCase.id() == null || evaluationCase.id().isBlank()
                    || evaluationCase.question() == null || evaluationCase.question().isBlank()
                    || !ids.add(evaluationCase.id())) {
                throw new IllegalArgumentException("Evaluation cases require unique IDs and questions");
            }
            if (evaluationCase.shouldRefuse()
                    && (!evaluationCase.expectedChunkIds().isEmpty()
                    || !evaluationCase.expectedDocumentIds().isEmpty())) {
                throw new IllegalArgumentException("Rejected cases cannot declare expected retrieval IDs");
            }
            if (!evaluationCase.shouldRefuse() && evaluationCase.expectedChunkIds().isEmpty()
                    && evaluationCase.expectedDocumentIds().isEmpty()) {
                throw new IllegalArgumentException("Answerable cases require expected retrieval IDs");
            }
        }
    }

    private record CaseOutcome(
            String category,
            boolean retrievalCase,
            int hitRank,
            boolean hitAtK,
            double recallAtK,
            double reciprocalRank,
            double topKJaccard,
            double expectedClaimCoverage,
            CitationEvaluation citation,
            boolean expectedRefusal,
            boolean actualRefusal,
            boolean refusalCorrect,
            double confidence,
            long latencyMs,
            List<String> retrievedDocumentIds,
            List<String> retrievedChunkIds
    ) {
    }
}
