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

        assertThat(response.totalCases()).isEqualTo(12);
        assertThat(response.caseResults()).hasSize(48);
        assertThat(direct.hitRateAt3()).isZero();
        assertThat(direct.meanReciprocalRank()).isZero();
        assertThat(guarded.hitRateAt3()).isGreaterThanOrEqualTo(0.8);
        assertThat(guarded.citationCoverage()).isGreaterThanOrEqualTo(0.8);
        assertThat(guarded.refusalAccuracy()).isEqualTo(1.0);
        assertThat(response.summaries())
                .allSatisfy(summary -> {
                    assertThat(summary.hitRateAt1()).isBetween(0.0, 1.0);
                    assertThat(summary.hitRateAt3()).isBetween(0.0, 1.0);
                    assertThat(summary.meanReciprocalRank()).isBetween(0.0, 1.0);
                    assertThat(summary.citationCoverage()).isBetween(0.0, 1.0);
                    assertThat(summary.refusalAccuracy()).isBetween(0.0, 1.0);
                });
    }
}
