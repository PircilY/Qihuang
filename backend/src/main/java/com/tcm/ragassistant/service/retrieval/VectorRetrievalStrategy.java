package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.config.RetrievalProperties;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.RetrievalTrace;
import com.tcm.ragassistant.service.ScoredChunk;
import com.tcm.ragassistant.service.VectorSimilarityScorer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VectorRetrievalStrategy implements RetrievalStrategy {

    private final VectorSimilarityScorer vectorScorer;
    private final RetrievalProperties properties;

    public VectorRetrievalStrategy(
            VectorSimilarityScorer vectorScorer,
            RetrievalProperties properties
    ) {
        this.vectorScorer = vectorScorer;
        this.properties = properties;
    }

    @Override
    public IterationVersion version() {
        return IterationVersion.V1_VECTOR;
    }

    @Override
    public RetrievalOutput retrieve(String question, RetrievalFilter filter) {
        List<ScoredChunk> chunks = vectorScorer.score(question, properties.finalLimit(), filter);
        return new RetrievalOutput(
                chunks,
                new RetrievalTrace(
                        "vector",
                        0,
                        chunks.size(),
                        chunks.size(),
                        chunks.size(),
                        !chunks.isEmpty(),
                        List.of(
                                "仅使用字符级语义向量近似，未使用外部 Embedding 模型",
                                "generation=grounded-extractive",
                                filter == null || filter.isEmpty()
                                        ? "未启用知识范围过滤"
                                        : "已应用知识范围过滤：" + filter.description()
                        )
                ),
                !chunks.isEmpty()
        );
    }
}

