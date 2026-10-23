package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.domain.RetrievalFilter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题长度不能超过 500 个字符")
        String question,

        @NotNull(message = "必须指定迭代版本")
        IterationVersion version,

        String disease,
        String sourceType,
        String evidenceLevel,
        boolean useModel,
        String corpus
) {
    public ChatRequest(String question, IterationVersion version, String disease,
                       String sourceType, String evidenceLevel, boolean useModel) {
        this(question, version, disease, sourceType, evidenceLevel, useModel, null);
    }

    public CorpusId corpusId() {
        return CorpusId.from(corpus);
    }
    public RetrievalFilter toRetrievalFilter() {
        return new RetrievalFilter(disease, sourceType, evidenceLevel);
    }
}
