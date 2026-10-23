package com.tcm.ragassistant.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
@SpringBootTest
@EnabledIfSystemProperty(named="generationReport",matches=".+")
class ResearchGenerationReportTest {
    @Autowired GenerationExperimentService experiments;
    @Autowired ObjectMapper mapper;
    @Test void exportsOriginalAnswerableQuestionsWithoutExternalModels() throws Exception {
        var result=experiments.run(new GenerationExperimentService.Request(null,50,42L,0.0,"BASELINE"),null);
        var path=Path.of(System.getProperty("generationReport")); Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),result);
    }
}
