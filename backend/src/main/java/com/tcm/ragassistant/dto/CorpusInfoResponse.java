package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.CorpusId;

public record CorpusInfoResponse(
        CorpusId id,
        String name,
        int documentCount,
        int chunkCount,
        int verifiedFullTextCount,
        boolean frozen,
        String note
) {
}
