package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.RetrievalTrace;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DirectRetrievalStrategy implements RetrievalStrategy {

    @Override
    public IterationVersion version() {
        return IterationVersion.V0_DIRECT;
    }

    @Override
    public RetrievalOutput retrieve(String question, RetrievalFilter filter, CorpusId corpus) {
        return new RetrievalOutput(
                List.of(),
                new RetrievalTrace(
                        "direct",
                        0,
                        0,
                        0,
                        0,
                        false,
                        List.of("未访问知识库", "generation=none", "回答无法回溯到证据片段")
                ),
                false
        );
    }
}

