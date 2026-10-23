package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.CorpusId;
import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

@SpringBootTest
@EnabledIfSystemProperty(named="researchReport", matches="true")
class ResearchBaselineReportTest {
    @Autowired EvaluationService evaluation;
    @Autowired RagOrchestrationService rag;
    @Autowired ObjectMapper mapper;
    @Autowired com.tcm.ragassistant.config.RetrievalProperties properties;
    @Test
    void exportOnlyOriginalTuningCasesAndMeasuredLatency() throws Exception {
        var output = Path.of(System.getProperty("researchOutput", "../.tmp/research-before.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        var report = new LinkedHashMap<String, Object>();
        report.put("configuration", properties);
        report.put("evaluation", evaluation.run());
        var latency = new LinkedHashMap<String, Object>();
        for (var corpus : CorpusId.values()) {
            String question = "高血压患者没有症状也需要检查血压吗？";
            rag.askExtractive(question, IterationVersion.V3_GUARDED, RetrievalFilter.none(), corpus);
            long[] samples = new long[20];
            for (int index=0; index<samples.length; index++) {
                long started = System.nanoTime();
                rag.askExtractive(question, IterationVersion.V3_GUARDED, RetrievalFilter.none(), corpus);
                samples[index] = System.nanoTime()-started;
            }
            java.util.Arrays.sort(samples);
            latency.put(corpus.name(), java.util.Map.of("runs", samples.length,
                    "medianMs", samples[10]/1_000_000.0, "p95Ms", samples[18]/1_000_000.0));
        }
        report.put("latency", latency);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
}
