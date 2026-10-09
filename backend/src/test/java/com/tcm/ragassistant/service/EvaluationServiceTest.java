package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.EvaluationRunResponse;
import com.tcm.ragassistant.dto.EvaluationSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvaluationServiceTest {

    @Autowired
    private EvaluationService evaluationService;

    @Test
    void evaluationShouldShowGuardedVersionImprovesRetrievalAndRefusal() {
        EvaluationRunResponse response = evaluationService.run();
        Map<IterationVersion, EvaluationSummary> summaries = response.summaries().stream()
                .collect(Collectors.toMap(EvaluationSummary::version, Function.identity()));

        EvaluationSummary direct = summaries.get(IterationVersion.V0_DIRECT);
        EvaluationSummary guarded = summaries.get(IterationVersion.V3_GUARDED);

        assertThat(response.datasetVersion()).isEqualTo("2.0.0");
        assertThat(response.totalCases()).isEqualTo(50);
        assertThat(response.retrievalK()).isEqualTo(4);
        assertThat(response.caseResults()).hasSize(200);
        assertThat(direct.hitRateAt3()).isZero();
        assertThat(direct.meanReciprocalRank()).isZero();
        assertThat(guarded.hitRateAtK()).isGreaterThanOrEqualTo(0.7);
        assertThat(guarded.citationCoverage()).isGreaterThanOrEqualTo(0.7);
        var invalidCitations = response.caseResults().stream()
                .filter(item -> item.version() == IterationVersion.V3_GUARDED)
                .filter(item -> item.citationPresent() && !item.citationValid())
                .map(item -> item.caseId() + ":" + item.retrievedChunkIds())
                .toList();
        assertThat(invalidCitations).as(invalidCitations.toString()).isEmpty();
        assertThat(guarded.citationSupportRate()).isEqualTo(guarded.citationCoverage());
        assertThat(guarded.refusalAccuracy()).isGreaterThanOrEqualTo(0.8);
        assertThat(guarded.byCategory()).hasSize(8);
        assertThat(response.summaries())
                .allSatisfy(summary -> {
                    assertThat(summary.hitRateAt1()).isBetween(0.0, 1.0);
                    assertThat(summary.hitRateAt3()).isBetween(0.0, 1.0);
                    assertThat(summary.meanReciprocalRank()).isBetween(0.0, 1.0);
                    assertThat(summary.recallAtK()).isBetween(0.0, 1.0);
                    assertThat(summary.topKJaccard()).isBetween(0.0, 1.0);
                    assertThat(summary.citationCoverage()).isBetween(0.0, 1.0);
                    assertThat(summary.refusalAccuracy()).isBetween(0.0, 1.0);
                });
    }
}
