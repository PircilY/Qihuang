package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.dto.*;
import com.tcm.ragassistant.service.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationExperimentServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final CitationSupportEvaluator evaluator=new CitationSupportEvaluator(new TextTokenizer());
    private final CitationResponse citation=new CitationResponse("fixture-1", "fixture", "[FIXTURE] 非医学事实", "规则", "fixture", 2026,
            "", "测试", "1", "规则", "测试记录包含蓝色样本。", 1);
    private final ChatCompletionClient client=mock(ChatCompletionClient.class);
    private final RagOrchestrationService rag=mock(RagOrchestrationService.class);
    private final ModelConnection connection=new ModelConnection(URI.create("http://127.0.0.1:1/v1"), "fixture-model", "", true);
    private GenerationExperimentService service(MockEnvironment env) {
        return new GenerationExperimentService(rag,new ModelAnswerService(client,mapper),client,evaluator,
                new LlmCitationJudge(client,mapper),new ModelSessionService("", "", "", ""),mapper,env);
    }
    private void setupRetrieval() {
        when(rag.askExtractive(anyString(),eq(IterationVersion.V3_GUARDED),any(),any())).thenAnswer(invocation ->
                new ChatResponse("fixture",invocation.getArgument(0),IterationVersion.V3_GUARDED,"测试记录包含蓝色样本。[1]",false,null,1,1,
                        List.of(citation),new RetrievalTrace("fixture",1,1,1,1,true,List.of("corpus=BASELINE")),Instant.EPOCH));
    }
    @Test void programmableModelsAndMetricsShareTemperatureAndQuestions() throws Exception {
        setupRetrieval();
        when(client.complete(eq(connection),anyList(),eq(2048),eq(0.25))).thenAnswer(invocation -> {
            List<Map<String,String>> messages=invocation.getArgument(1);
            return messages.get(1).get("content").startsWith("{")?"测试记录包含蓝色样本。[1]":"测试记录包含蓝色样本。";
        });
        var request=new GenerationExperimentService.Request(List.of(new GenerationExperimentService.Question("f1","[FIXTURE] 蓝色样本是什么？")),1,7L,0.25,"BASELINE");
        var result=service(new MockEnvironment()).run(request,connection);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r->r.get("questionId")).containsOnly("f1");
        assertThat(((Map<?,?>)rows.get(0).get("metrics")).get("uncitedSentences")).isEqualTo(1.0);
        assertThat(((Map<?,?>)rows.get(1).get("metrics")).get("sentenceSupport")).isEqualTo(1.0);
        assertThat(((Map<?,?>)rows.get(1).get("metrics")).get("validReferences")).isEqualTo(1.0);
        assertThat(((Map<?,?>)rows.get(2).get("metrics")).get("bigramOverlap")).isEqualTo(1.0);
        verify(client,times(2)).complete(eq(connection),anyList(),eq(2048),eq(0.25));
        assertThat(result.toString()).doesNotContain("apiKey");
        String output=System.getProperty("generationMockReport");
        if(output!=null) { var path=java.nio.file.Path.of(output); java.nio.file.Files.createDirectories(path.getParent()); mapper.writeValue(path.toFile(),result); }
    }
    @Test void failuresRemainRowsAndExtractiveStillRuns() {
        setupRetrieval();
        var result=service(new MockEnvironment()).run(new GenerationExperimentService.Request(
                List.of(new GenerationExperimentService.Question("f1","[FIXTURE] 蓝色样本？")),1,42L,0.0,null),null);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("failureReason")).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(rows.get(1).get("failureReason")).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(rows.get(2).get("failureReason")).isEqualTo("");
        verifyNoInteractions(client);
    }
    @Test void invalidReferencesAndUnsupportedClaimsAreCountedWithoutOverflow() {
        var metrics=GenerationMetrics.measure("测试记录包含蓝色样本。[99999999999999999999]外部结论没有依据。",List.of(citation),evaluator);
        assertThat(metrics.get("validReferences")).isZero();
        assertThat(metrics.get("sentenceSupport")).isZero();
        assertThat(metrics.get("uncitedSentences")).isEqualTo(0.5);
        assertThat(evaluator.splitSentences("数值为 1.5。[1] 第二句。第三句！")).hasSize(3);
    }
    @Test void numberedExtractiveSentencesKeepSpacedCitationWithClaim() {
        var checks=evaluator.sentenceSupport("相关文献摘录：\n1. 测试记录包含蓝色样本。 [1]\n2. 测试记录包含蓝色样本。 [1]",List.of(citation));
        assertThat(checks).hasSize(3);
        assertThat(checks.stream().filter(c->c.startsWith("SUPPORTED\t"))).hasSize(2);
        assertThat(GenerationMetrics.measure("测试记录包含蓝色样本。 [1]",List.of(citation),evaluator).get("sentenceSupport")).isEqualTo(1.0);
    }
    @Test void invalidJudgeConfigurationIsExplicitAndSafetySkipsModel() {
        setupRetrieval();
        when(client.complete(eq(connection),anyList(),eq(2048),eq(0.0))).thenReturn("测试记录包含蓝色样本。[1]");
        var result=service(new MockEnvironment().withProperty("app.experiments.judge-enabled","true")).run(
                new GenerationExperimentService.Request(List.of(new GenerationExperimentService.Question("f1","[FIXTURE] 蓝色样本？")),1,42L,0.0,null),connection);
        assertThat(((List<Map<String,Object>>)result.get("rows"))).allSatisfy(row -> assertThat(row.get("judgeFailureReason")).isEqualTo("JUDGE_NOT_CONFIGURED"));
    }
    @Test void independentJudgeUsesItsOwnModelAndTemperature() {
        setupRetrieval();
        when(client.complete(eq(connection),anyList(),eq(2048),eq(0.0))).thenReturn("测试记录包含蓝色样本。[1]");
        when(client.complete(any(ModelConnection.class),anyList(),eq(512),eq(0.1))).thenReturn("{\"supported\":[true]}");
        var env=new MockEnvironment().withProperty("app.experiments.judge-enabled","true")
            .withProperty("app.experiments.judge-base","http://127.0.0.1:2/v1")
            .withProperty("app.experiments.judge-model","fixture-judge").withProperty("app.experiments.judge-temperature","0.1");
        var result=service(env).run(new GenerationExperimentService.Request(List.of(
            new GenerationExperimentService.Question("f1","[FIXTURE] 蓝色样本？")),1,42L,0.0,null),connection);
        assertThat(((Map<?,?>)result.get("metadata")).get("sameModelAsGenerator")).isEqualTo(false);
        verify(client,times(3)).complete(argThat(c->c.model().equals("fixture-judge") && c.apiKey().isEmpty()),anyList(),eq(512),eq(0.1));
        assertThat((List<Map<String,Object>>)result.get("rows")).allSatisfy(row -> {
            assertThat(row.get("judgeFailureReason")).isEqualTo("");
            assertThat(((Map<?,?>)row.get("metrics")).get("judgeSupport")).isEqualTo(1.0);
        });
    }
    @Test void bootstrapIsReproducibleAndRequestBounded() {
        setupRetrieval();
        var svc=service(new MockEnvironment());
        assertThatThrownBy(()->svc.run(new GenerationExperimentService.Request(null,100,1L,0.0,null),null)).hasMessageContaining("上限");
        Map<String,Object> row=Map.of("mode","A","metrics",Map.of("sentenceSupport",1.0,"uncitedSentences",0.0,"validReferences",1.0,
                "bigramOverlap",0.5,"refusalRate",0.0,"answerLength",10.0,"latencyMs",1.0,"failureRate",0.0,"judgeSupport",0.0));
        assertThat(GenerationMetrics.summarize(List.of(row),42)).isEqualTo(GenerationMetrics.summarize(List.of(row),42));
        assertThat(GenerationMetrics.summarize(List.of(row),42).get("A").get("sentenceSupport").lower95()).isEqualTo(1);
    }
}
