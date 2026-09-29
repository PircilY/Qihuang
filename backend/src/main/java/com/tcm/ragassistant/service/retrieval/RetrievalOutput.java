package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.ScoredChunk;

import java.util.List;

public record RetrievalOutput(
        List<ScoredChunk> chunks,
        RetrievalTrace trace,
        boolean sufficientEvidence
) {
    public RetrievalOutput {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}

