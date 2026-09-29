package com.tcm.ragassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.EvaluationCaseResult;
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
import java.util.List;

@Service
public class EvaluationService {

    private final RagOrchestrationService ragOrchestrationService;
    private final List<EvaluationCase> cases;

    public EvaluationService(
            RagOrchestrationService ragOrchestrationService,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader
    ) {
        this.ragOrchestrationService = ragOrchestrationService;
        Resource resource = resourceLoader.getResource("classpath:data/evaluation-cases.json");
        try (InputStream inputStream = resource.getInputStream()) {
            this.cases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new BusinessException("EVALUATION_DATA_LOAD_ERROR", "评测数据初始化失败");
        }
    }

    public EvaluationRunResponse run() {
        List<EvaluationCaseResult> caseResults = new ArrayList<>();
        List<EvaluationSummary> summaries = new ArrayList<>();

        for (IterationVersion version : IterationVersion.values()) {
            List<CaseOutcome> outcomes = new ArrayList<>();
            for (EvaluationCase evaluationCase : cases) {
                ChatResponse response = ragOrchestrationService.ask(evaluationCase.question(), version);
                int hitRank = hitRank(evaluationCase, response);
                boolean retrievalHit = hitRank > 0;
                int citationCount = response.citations().size();
                boolean citationPresent = citationCount > 0;
                boolean refusalCorrect = evaluationCase.shouldRefuse() == response.refused();
                outcomes.add(new CaseOutcome(
                        retrievalHit,
                        hitRank,
                        citationPresent,
                        refusalCorrect,
                        response.confidence(),
                        response.latencyMs(),
                        evaluationCase.shouldRefuse()
                ));
                caseResults.add(new EvaluationCaseResult(
                        evaluationCase.id(),
                        evaluationCase.category(),
                        evaluationCase.question(),
                        version,
                        evaluationCase.shouldRefuse(),
                        retrievalHit,
                        hitRank,
                        citationCount,
                        citationPresent,
                        refusalCorrect,
                        response.confidence(),
                        response.latencyMs()
                ));
            }
            summaries.add(summarize(version, outcomes));
        }

        return new EvaluationRunResponse(
                "内置心血管中医 RAG 演示评测集",
                cases.size(),
                summaries,
                caseResults,
                Instant.now()
        );
    }

    private EvaluationSummary summarize(IterationVersion version, List<CaseOutcome> outcomes) {
        List<CaseOutcome> retrievalCases = outcomes.stream()
                .filter(outcome -> !outcome.expectedRefusal())
                .toList();
        long retrievalHitCount = retrievalCases.stream().filter(CaseOutcome::retrievalHit).count();
        long citationCount = retrievalCases.stream().filter(CaseOutcome::citationPresent).count();
        long refusalCorrectCount = outcomes.stream().filter(CaseOutcome::refusalCorrect).count();

        return new EvaluationSummary(
                version,
                ratio(retrievalCases.stream().filter(outcome -> outcome.hitRank() == 1).count(), retrievalCases.size()),
                ratio(retrievalHitCount, retrievalCases.size()),
                round(retrievalCases.stream()
                        .mapToDouble(outcome -> outcome.hitRank() > 0 ? 1.0 / outcome.hitRank() : 0.0)
                        .average()
                        .orElse(0.0)),
                ratio(citationCount, retrievalCases.size()),
                ratio(refusalCorrectCount, outcomes.size()),
                round(outcomes.stream().mapToDouble(CaseOutcome::confidence).average().orElse(0.0)),
                round(outcomes.stream().mapToLong(CaseOutcome::latencyMs).average().orElse(0.0)),
                (int) outcomes.stream()
                        .filter(outcome -> !outcome.retrievalHit() && !outcome.expectedRefusal())
                        .count()
                        + (int) outcomes.stream().filter(outcome -> !outcome.refusalCorrect()).count()
        );
    }

    private int hitRank(EvaluationCase evaluationCase, ChatResponse response) {
        if (evaluationCase.shouldRefuse()) {
            return response.refused() ? 1 : -1;
        }
        List<String> responseIds = response.citations().stream()
                .map(citation -> citation.chunkId())
                .toList();
        for (int index = 0; index < responseIds.size(); index++) {
            if (evaluationCase.expectedChunkIds().contains(responseIds.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return round((double) numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record CaseOutcome(
            boolean retrievalHit,
            int hitRank,
            boolean citationPresent,
            boolean refusalCorrect,
            double confidence,
            long latencyMs,
            boolean expectedRefusal
    ) {
    }
}

