package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;

public interface RetrievalStrategy {

    IterationVersion version();

    RetrievalOutput retrieve(String question, RetrievalFilter filter, CorpusId corpus);

    default RetrievalOutput retrieve(String question) {
        return retrieve(question, RetrievalFilter.none(), CorpusId.BASELINE);
    }
}

