package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.config.*;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.repository.KnowledgeBaseRepository;
import com.tcm.ragassistant.service.retrieval.*;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;

@SpringBootTest
@EnabledIfSystemProperty(named="researchGrid", matches="true")
class ResearchParameterGridTest {
    @Autowired KnowledgeBaseRepository repository;
    @Autowired LexicalScorer lexical;
    @Autowired VectorSimilarityScorer vector;
    @Autowired TextTokenizer tokenizer;
    @Autowired ObjectMapper mapper;
    @Autowired LlamaIndexRagClient remote;
    @Test
    void selectParametersUsingOnlyOriginal50WithPerfectRefusalGate() throws Exception {
        List<Map<String,Object>> rows = new ArrayList<>();
        com.tcm.ragassistant.dto.EvaluationRunResponse best = null;
        double bestWeight=0, bestThreshold=0;
        for (double weight : new double[]{0, .05, .1, .2, .25, .5}) {
            for (double threshold : new double[]{.3, .5, .6, .75, .9}) {
                var properties = new RetrievalProperties(8, 4, 60, 1, .85, threshold, true, weight);
                var hybrid = new HybridRetrievalStrategy(lexical, vector, properties);
                var strategies = new RetrievalStrategyRegistry(List.of(new DirectRetrievalStrategy(),
                        new VectorRetrievalStrategy(vector, properties), hybrid,
                        new GuardedHybridRetrievalStrategy(hybrid, properties, tokenizer)));
                var rag = new RagOrchestrationService(new RagEngineProperties("local", null, null, null, true, null),
                        remote, strategies, new AnswerGenerationService(), repository);
                var evaluation = new EvaluationService(rag, mapper, new DefaultResourceLoader(), properties,
                        new CitationSupportEvaluator(tokenizer));
                var result = evaluation.run();
                var guarded = result.summaries().stream().filter(s -> s.version()==IterationVersion.V3_GUARDED).findFirst().orElseThrow();
                rows.add(Map.of("metadataWeight",weight,"threshold",threshold,"summary",guarded));
                if (guarded.refusalAccuracy() != 1) continue;
                var previous = best == null ? null : best.summaries().get(3);
                if (previous == null || guarded.hitRateAt1() > previous.hitRateAt1()
                        || (guarded.hitRateAt1()==previous.hitRateAt1() && guarded.meanReciprocalRank()>previous.meanReciprocalRank())
                        || (guarded.hitRateAt1()==previous.hitRateAt1() && guarded.meanReciprocalRank()==previous.meanReciprocalRank() && threshold>bestThreshold)) {
                    best=result; bestWeight=weight; bestThreshold=threshold;
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(best).isNotNull();
        var output = Path.of("../.tmp/research-grid.json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("grid",rows,
                "selectedMetadataWeight",bestWeight,"selectedThreshold",bestThreshold,"selected",best));
    }
}
