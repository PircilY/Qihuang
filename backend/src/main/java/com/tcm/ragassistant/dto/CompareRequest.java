package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.RetrievalFilter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompareRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题长度不能超过 500 个字符")
        String question,

        String disease,
        String sourceType,
        String evidenceLevel
) {
    public RetrievalFilter toRetrievalFilter() {
        return new RetrievalFilter(disease, sourceType, evidenceLevel);
    }
}

