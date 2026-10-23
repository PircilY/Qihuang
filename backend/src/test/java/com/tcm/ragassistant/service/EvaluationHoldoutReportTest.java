package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.EvaluationCaseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
class EvaluationHoldoutReportTest {
    @Autowired EvaluationService evaluationService;
    @Autowired ObjectMapper mapper;

    @Test
    void reportHoldoutAblationKCurveAndBootstrapIntervals() throws Exception {
        assumeTrue(Boolean.getBoolean("runHoldoutEvaluation"),
                "Holdout evaluation is opt-in; pass -DrunHoldoutEvaluation=true after explicit approval");
        var run = evaluationService.runHoldout();
        var tuning = evaluationService.run();
        assertThat(run.totalCases()).isEqualTo(100);
        assertThat(run.caseResults()).hasSize(400);
        assertThat(run.caseResults().stream().map(EvaluationCaseResult::engine).distinct()).hasSize(1);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", run.datasetName());
        report.put("cases", run.totalCases());
        report.put("engine", run.caseResults().get(0).engine());
        report.put("versions", List.of("V0_DIRECT", "V1_VECTOR", "V2_HYBRID", "V3_GUARDED"));
        report.put("tuningSet", tuning.summaries());
        Map<String, Object> byVersion = new LinkedHashMap<>();
        for (IterationVersion version : IterationVersion.values()) {
            List<EvaluationCaseResult> cases = run.caseResults().stream()
                    .filter(result -> result.version() == version)
                    .sorted(Comparator.comparing(EvaluationCaseResult::caseId)).toList();
            Map<String, Object> versionReport = new LinkedHashMap<>();
            versionReport.put("summary", run.summaries().stream()
                    .filter(summary -> summary.version() == version).findFirst().orElseThrow());
            List<Map<String, Object>> kCurve = new ArrayList<>();
            for (int k = 1; k <= run.retrievalK(); k++) {
                int cutoff = k;
                long retrievalCount = cases.stream().filter(item -> !item.expectedRefusal()).count();
                double hit = cases.stream().filter(item -> !item.expectedRefusal())
                        .filter(item -> item.expectedChunkIds().stream()
                                .anyMatch(item.retrievedChunkIds().stream().limit(cutoff).toList()::contains)).count()
                        / (double) retrievalCount;
                double recall = cases.stream().filter(item -> !item.expectedRefusal())
                        .mapToDouble(item -> item.expectedChunkIds().isEmpty() ? 0.0
                                : item.expectedChunkIds().stream().filter(item.retrievedChunkIds()
                                .stream().limit(cutoff).toList()::contains).count() / (double) item.expectedChunkIds().size())
                        .average().orElse(0.0);
                double mrr = cases.stream().filter(item -> !item.expectedRefusal())
                        .mapToDouble(item -> reciprocalRank(item, cutoff)).average().orElse(0.0);
                kCurve.add(Map.of("k", k, "hitRate", round(hit), "recall", round(recall), "mrr", round(mrr)));
            }
            versionReport.put("kSensitivity", kCurve);
            versionReport.put("bootstrap95", Map.of(
                    "hitAt4", interval(cases.stream().filter(item -> !item.expectedRefusal()).toList(),
                            this::hitAt4, version.ordinal() + 11),
                    "refusalAccuracy", interval(cases, item -> item.refusalCorrect() ? 1.0 : 0.0,
                            version.ordinal() + 29)));
            byVersion.put(version.name(), versionReport);
        }
        report.put("results", byVersion);
        System.out.println("HOLDOUT_REPORT_JSON=" + mapper.writeValueAsString(report));
    }

    private double hitAt4(EvaluationCaseResult item) {
        return item.expectedRefusal() || item.expectedChunkIds().stream()
                .anyMatch(item.retrievedChunkIds().stream().limit(4).toList()::contains) ? 1.0 : 0.0;
    }

    private double reciprocalRank(EvaluationCaseResult item, int k) {
        if (item.expectedRefusal()) return 0.0;
        for (int index = 0; index < Math.min(k, item.retrievedChunkIds().size()); index++) {
            if (item.expectedChunkIds().contains(item.retrievedChunkIds().get(index))) return 1.0 / (index + 1);
        }
        return 0.0;
    }

    private Map<String, Double> interval(List<EvaluationCaseResult> cases,
                                         java.util.function.ToDoubleFunction<EvaluationCaseResult> metric,
                                         long seed) {
        Random random = new Random(seed);
        double[] estimates = new double[2000];
        for (int sample = 0; sample < estimates.length; sample++) {
            double total = 0.0;
            for (int index = 0; index < cases.size(); index++) {
                total += metric.applyAsDouble(cases.get(random.nextInt(cases.size())));
            }
            estimates[sample] = total / cases.size();
        }
        java.util.Arrays.sort(estimates);
        return Map.of("lower", round(estimates[49]), "upper", round(estimates[1949]));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
