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
import com.tcm.ragassistant.service.model.ModelAnswerService;
import com.tcm.ragassistant.service.model.ModelConnection;
import com.tcm.ragassistant.service.model.LlmCitationJudge;
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
    static final String HOLDOUT_DATASET_NAME = "隔离留出评测集";
    static final String HOLDOUT_DATASET_VERSION = "1.0.0";

    private final RagOrchestrationService ragOrchestrationService;
    private final CitationSupportEvaluator citationEvaluator;
    private final int retrievalK;
    private final List<EvaluationCase> cases;
    private final java.util.concurrent.locks.ReentrantLock evaluationLock = new java.util.concurrent.locks.ReentrantLock();
    @org.springframework.beans.factory.annotation.Value("${app.limits.evaluation-max-cases:200}")
    private int maximumCases = 200;
    @org.springframework.beans.factory.annotation.Value("${app.limits.evaluation-max-characters:100000}")
    private long maximumCharacters = 100000;
    private final ObjectMapper objectMapper;
    private final Resource holdoutResource;
    private ModelAnswerService modelAnswerService;
    private LlmCitationJudge llmCitationJudge;

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
        this.objectMapper = objectMapper;
        Resource resource = resourceLoader.getResource("classpath:data/evaluation-cases.json");
        this.holdoutResource = resourceLoader.getResource("classpath:data/holdout-cases.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.cases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            validateCases(cases);
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException("EVALUATION_DATA_LOAD_ERROR", "评测数据初始化失败", exception);
        }
    }

    public EvaluationRunResponse run() {
        return runCases(cases, DATASET_NAME, DATASET_VERSION, null, false);
    }

    public EvaluationRunResponse runHoldout() {
        return runHoldout(null, false);
    }

    public EvaluationRunResponse runHoldout(ModelConnection connection, boolean useLlmJudge) {
        if (connection != null && (!connection.enabled() || modelAnswerService == null)) {
            throw new BusinessException("MODEL_NOT_CONFIGURED", "评测模型尚未启用");
        }
        if (useLlmJudge && (connection == null || llmCitationJudge == null)) {
            throw new BusinessException("MODEL_NOT_CONFIGURED", "LLM-as-judge 需要启用模型");
        }
        return runCases(loadHoldoutCases(), HOLDOUT_DATASET_NAME, HOLDOUT_DATASET_VERSION,
                connection, useLlmJudge);
    }

    private List<EvaluationCase> loadHoldoutCases() {
        try (InputStream inputStream = holdoutResource.getInputStream()) {
            List<EvaluationCase> holdout = objectMapper.readValue(inputStream, new TypeReference<>() { });
            validateCases(holdout);
            if (holdout.size() < 100) {
                throw new IllegalArgumentException("Holdout dataset must contain at least 100 cases");
            }
            Set<String> tuningIds = new LinkedHashSet<>();
            cases.forEach(item -> tuningIds.add(item.id()));
            if (holdout.stream().anyMatch(item -> tuningIds.contains(item.id()))) {
                throw new IllegalArgumentException("Tuning and holdout case IDs must be disjoint");
            }
            return List.copyOf(holdout);
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException("EVALUATION_DATA_LOAD_ERROR", "留出评测数据初始化失败", exception);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureModelEvaluation(ModelAnswerService modelAnswerService, LlmCitationJudge llmCitationJudge) {
        this.modelAnswerService = modelAnswerService;
        this.llmCitationJudge = llmCitationJudge;
    }

    private EvaluationRunResponse runCases(List<EvaluationCase> runCases, String datasetName, String datasetVersion,
                                           ModelConnection connection, boolean useLlmJudge) {
        if (runCases.size() > maximumCases || runCases.stream().mapToLong(item -> item.question().length()).sum() > maximumCharacters) {
            throw new BusinessException("EVALUATION_CAPACITY_EXCEEDED", "评测题数超过配置上限");
        }
        if (!evaluationLock.tryLock()) throw new BusinessException("EVALUATION_BUSY", "已有评测正在运行");
        try {
            return runLocked(runCases, datasetName, datasetVersion, connection, useLlmJudge);
        } finally {
            evaluationLock.unlock();
        }
    }

    private EvaluationRunResponse runLocked(List<EvaluationCase> runCases, String datasetName, String datasetVersion,
                                            ModelConnection connection, boolean useLlmJudge) {
        String remoteState = ragOrchestrationService.remoteSyncState();
        if (remoteState != null && !"IN_SYNC".equals(remoteState)) {
            throw new BusinessException("REMOTE_KNOWLEDGE_NOT_IN_SYNC",
                    "远程知识库未同步，不能运行混合引擎评测");
        }
        List<EvaluationCaseResult> caseResults = new ArrayList<>();
        List<EvaluationSummary> summaries = new ArrayList<>();

        for (IterationVersion version : IterationVersion.values()) {
            List<CaseOutcome> outcomes = new ArrayList<>();
            for (EvaluationCase evaluationCase : runCases) {
                ChatResponse response = ragOrchestrationService.askExtractive(
                        evaluationCase.question(), version, RetrievalFilter.none());
                if (connection != null) response = modelAnswerService.generate(response, connection);
                CaseOutcome outcome = evaluate(evaluationCase, response, connection != null);
                outcomes.add(outcome);
                caseResults.add(toResult(evaluationCase, version, response, outcome, connection, useLlmJudge));
            }
            summaries.add(summarize(version, outcomes));
        }

        Set<String> engines = caseResults.stream().map(EvaluationCaseResult::engine)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (engines.size() > 1) {
            throw new BusinessException("EVALUATION_INVALID_ENGINE_MIX",
                    "评测运行检测到混合检索引擎，结果状态为 INVALID: " + String.join(",", engines));
        }

        return new EvaluationRunResponse(
                datasetName,
                datasetVersion,
                runCases.size(),
                retrievalK,
                summaries,
                caseResults,
                Instant.now()
        );
    }

    private CaseOutcome evaluate(EvaluationCase evaluationCase, ChatResponse response, boolean generatedAnswer) {
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
        if (generatedAnswer) {
            List<String> sentenceChecks = citationEvaluator.sentenceSupport(response.answer(), response.citations());
            citation = new CitationEvaluation(citation.presence(), citation.validity(), citation.support()
                    && !sentenceChecks.isEmpty()
                    && sentenceChecks.stream().allMatch(check -> check.startsWith("SUPPORTED\t")));
        }
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
                                          ChatResponse response, CaseOutcome outcome, ModelConnection connection,
                                          boolean useLlmJudge) {
        List<String> sentenceSupport = citationEvaluator.sentenceSupport(response.answer(), response.citations());
        List<String> llmSentenceSupport = useLlmJudge
                ? llmCitationJudge.judge(response.answer(), response.citations(), connection, sentenceSupport)
                : List.of();
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
                response.latencyMs(),
                evaluationEngine(response),
                response.answer(),
                sentenceSupport,
                llmSentenceSupport
        );
    }

    public String holdoutReviewCsv(int sampleSize) {
        return holdoutReviewCsv(sampleSize, null, false);
    }

    public String holdoutReviewCsv(int sampleSize, ModelConnection connection, boolean useLlmJudge) {
        if (sampleSize < 1 || sampleSize > 500) {
            throw new IllegalArgumentException("sampleSize must be between 1 and 500");
        }
        List<EvaluationCaseResult> results = runHoldout(connection, useLlmJudge).caseResults();
        int count = Math.min(sampleSize, results.size());
        StringBuilder csv = new StringBuilder("caseId,category,version,question,answer,expectedRefusal,refusalCorrect,engine,ruleSentenceSupport,llmSentenceSupport\r\n");
        for (int position = 0; position < count; position++) {
            int index = count == 1 ? 0 : (int) Math.round((double) position * (results.size() - 1) / (count - 1));
            EvaluationCaseResult result = results.get(index);
            csv.append(csv(result.caseId())).append(',')
                    .append(csv(result.category())).append(',')
                    .append(csv(result.version().name())).append(',')
                    .append(csv(result.question())).append(',')
                    .append(csv(result.answer())).append(',')
                    .append(result.expectedRefusal()).append(',')
                    .append(result.refusalCorrect()).append(',')
                    .append(csv(result.engine())).append(',')
                    .append(csv(String.join(" | ", result.sentenceSupport()))).append(',')
                    .append(csv(String.join(" | ", result.llmSentenceSupport()))).append("\r\n");
        }
        return csv.toString();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private String evaluationEngine(ChatResponse response) {
        String engine = response.retrieval() == null ? "java-local" : response.retrieval().engine();
        return "llama-index".equalsIgnoreCase(engine) ? "llama-index" : "java-local";
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
