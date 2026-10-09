package com.tcm.ragassistant.service;

import com.tcm.ragassistant.dto.CitationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSupportEvaluatorTest {

    private final CitationSupportEvaluator evaluator = new CitationSupportEvaluator(new TextTokenizer());

    @Test
    void validatesCitationNumberAndStableIdentity() {
        CitationEvaluation valid = evaluator.evaluate(
                "甘草可能引起心律失常。[1]",
                List.of(citation("C-1", "甘草可能引起严重心律失常。"))
        );
        CitationEvaluation missing = evaluator.evaluate("甘草可能引起心律失常。", List.of());
        CitationEvaluation invalid = evaluator.evaluate(
                "甘草可能引起心律失常。[2]",
                List.of(citation("C-1", "甘草可能引起严重心律失常。"))
        );

        assertThat(valid).isEqualTo(new CitationEvaluation(true, true, true));
        assertThat(missing).isEqualTo(new CitationEvaluation(false, false, false));
        assertThat(invalid).isEqualTo(new CitationEvaluation(true, false, false));
    }

    @Test
    void rejectsPolarityAndNumberConflicts() {
        assertThat(evaluator.supports("甘草可能增加心脏风险", "甘草不会增加心脏风险")).isFalse();
        assertThat(evaluator.supports("风险增加 20%", "风险增加 10%")).isFalse();
        assertThat(evaluator.supports("风险可能增加 20%", "研究提示风险可能增加 20%")).isTrue();
    }

    @Test
    void supportsMultipleNumberedLatinClaims() {
        assertThat(evaluator.supports("test evidence", "test evidence")).isTrue();
        CitationEvaluation result = evaluator.evaluate(
                "test evidence [1]\ntest evidence [2]\ntest evidence [3]\ntest evidence [4]",
                List.of(citation("one", "test evidence"), citation("two", "test evidence"),
                        citation("three", "test evidence"), citation("four", "test evidence"))
        );

        assertThat(result).isEqualTo(new CitationEvaluation(true, true, true));
    }

    @Test
    void supportsParaphrasedClaimsWithMatchingModality() {
        assertThat(evaluator.supports(
                "Chinese herbal products may contain heavy metals and pesticides and cause serious side effects.",
                "Some Chinese herbal products have been contaminated with toxic compounds, heavy metals, "
                        + "pesticides, and microorganisms and may have serious side effects."
        )).isTrue();
        assertThat(evaluator.supports(
                "Licorice glycyrrhizin can cause serious adverse effects with long-term use.",
                "Licorice contains glycyrrhizin that can cause serious adverse effects, especially when "
                        + "consumed in large amounts or in the long term."
        )).isTrue();
    }

    private CitationResponse citation(String chunkId, String quote) {
        return new CitationResponse(chunkId, "DOC-1", "test", "test", "test", 2026,
                "test", "test", "", "A", quote, 0.5);
    }
}
