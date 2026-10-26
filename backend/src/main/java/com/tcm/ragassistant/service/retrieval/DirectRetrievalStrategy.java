package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.dto.RetrievalTrace;
import org.springframework.stereotype.Component;
import com.tcm.ragassistant.service.TextTokenizer;

import java.util.List;

@Component
public class DirectRetrievalStrategy implements RetrievalStrategy {

    private final TextTokenizer tokenizer;

    public DirectRetrievalStrategy() { this(new TextTokenizer()); }

    @org.springframework.beans.factory.annotation.Autowired
    public DirectRetrievalStrategy(TextTokenizer tokenizer) { this.tokenizer = tokenizer; }

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
                        List.of("未访问知识库", "generation=none", "回答无法回溯到证据片段",
                                "查询扩展=" + (tokenizer.queryFeatures(question).expansions().isEmpty()
                                        ? "无" : String.join("；", tokenizer.queryFeatures(question).expansions())))
                ),
                false
        );
    }
}

