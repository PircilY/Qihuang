package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.domain.*;
import com.tcm.ragassistant.repository.InMemoryKnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetrievalIndexTest {
    @Test
    void queriesReuseTermFrequenciesAndImportsUpdateOnlyNewDocument() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new InMemoryKnowledgeBaseRepository(mapper, new DefaultResourceLoader(), "");
        var tokenizer = spy(new TextTokenizer());
        var index = new RetrievalIndex(repository, tokenizer);
        index.initialize();
        var before = index.snapshot(CorpusId.BASELINE);
        assertThat(before.entries().size()).isEqualTo(11);
        clearInvocations(tokenizer);
        var lexical = new LexicalScorer(index, tokenizer);
        lexical.score("[FIXTURE] 占位占位", 4);
        lexical.score("[FIXTURE] 占位占位", 4);
        verify(tokenizer, times(2)).tokenizeWithFrequency("[FIXTURE] 占位占位");
        verifyNoMoreInteractions(tokenizer);
        repository.registerDocument(document(), List.of(chunk()));
        var after = index.snapshot(CorpusId.BASELINE);
        assertThat(after.entries()).hasSize(12);
        assertThat(after.entries().get("FIXTURE-C1").frequencies().get("占位")).isGreaterThan(1);
        assertThat(after.idf().get("占位")).isPositive();
        before.entries().forEach((id, entry) -> assertThat(after.entries().get(id)).isSameAs(entry));
        assertThat(repository.findByDisease(null, CorpusId.BASELINE)).isEmpty();
    }
    @Test
    void sentenceExcerptAndNullMetadataHaveBoundedSafeDefaults() {
        assertThat(AnswerGenerationService.firstSentence("Alpha beta. Second sentence!")).isEqualTo("Alpha beta.");
        assertThat(AnswerGenerationService.firstSentence("Alpha beta\nSecond sentence")).isEqualTo("Alpha beta");
        assertThat(AnswerGenerationService.firstSentence("x".repeat(500))).hasSize(401).endsWith("…");
        var chunk = new KnowledgeChunk("fixture", "fixture", null, null, null, null, null, null,
                null, null, 0, 0, null, null, null, null, null, null, null, null);
        assertThat(chunk.evidenceLevel()).isEqualTo("未评级");
        assertThat(chunk.disease()).isEmpty();
        assertThat(chunk.content()).isEmpty();
    }
    @Test
    void sharedTokenizerRetainsFrequencyWithoutCrossingPunctuation() {
        var tokenizer = new TextTokenizer();
        assertThat(tokenizer.tokenizeWithFrequency("占位占位 alpha alpha"))
                .containsExactly("alpha", "alpha", "占位", "位占", "占位");
        assertThat(tokenizer.tokenize("占位占位")).containsExactly("占位", "位占");
        assertThat(tokenizer.tokenizeWithFrequency("占位，字段")).containsExactly("占位", "字段");
    }

    @Test
    void frequentTopicDoesNotIndividuallyPassRelatedPreFilterWhenBridgeEnabled() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new InMemoryKnowledgeBaseRepository(mapper, new DefaultResourceLoader(), "");
        for (int i = 1; i <= 3; i++) {
            String id = "TOPIC-" + i;
            repository.registerDocument(new KnowledgeDocument("DOC-" + id, "fixture", "测试", "测试", 2026,
                            "", "未评级", List.of(), "", "fixture-" + i, "v1", "ACTIVE", Instant.EPOCH,
                            "fixture", "", "https://example.org", null),
                    List.of(new KnowledgeChunk(id, "DOC-" + id, "fixture", "测试", "测试", 2026, "",
                            List.of(), List.of(), List.of(), 0, 0, "", "未评级", List.of("心血管"),
                            "无关占位内容", "fixture-" + i, "ACTIVE", "v1", Instant.EPOCH)));
        }
        var tokenizer = new TextTokenizer();
        var index = new RetrievalIndex(repository, tokenizer);
        index.initialize();
        org.springframework.test.util.ReflectionTestUtils.setField(index, "queryBridge", true);
        var entry = index.snapshot(CorpusId.BASELINE).entries().get("TOPIC-1");

        assertThat(index.snapshot(CorpusId.BASELINE).topicFrequency().get("心血管")).isGreaterThan(0.2);
        assertThat(index.related("心血管", java.util.Set.of("心血", "血管"), entry)).isFalse();
    }
    private KnowledgeDocument document() {
        return new KnowledgeDocument("FIXTURE-DOC", "[FIXTURE] 非医学事实", "测试", "测试", 2026,
                "", "未评级", List.of(), "", "fixture", "v1", "ACTIVE", Instant.EPOCH,
                "测试夹具", "", "https://example.org", null);
    }
    private KnowledgeChunk chunk() {
        return new KnowledgeChunk("FIXTURE-C1", "FIXTURE-DOC", "[FIXTURE] 非医学事实", "测试", "测试", 2026,
                "", List.of(), List.of(), List.of(), 0, 0, "", "未评级", List.of(), "占位占位 alpha alpha",
                "fixture", "ACTIVE", "v1", Instant.EPOCH);
    }
}
