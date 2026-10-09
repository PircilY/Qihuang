package com.tcm.ragassistant.service;

import org.junit.jupiter.api.Test;
import com.tcm.ragassistant.domain.KnowledgeChunk;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextTokenizerTest {

    private final TextTokenizer tokenizer = new TextTokenizer();

    @Test
    void expandsThreeHighRiskTermsToExistingCorpusVocabulary() {
        List<String> tokens = tokenizer.tokenize("控制三高");

        assertThat(tokens).contains("三高", "高血", "血压", "糖尿", "尿病", "血脂");
    }

    @Test
    void expandsHeartAttackAliasWithoutRemovingOriginalTextFeatures() {
        List<String> tokens = tokenizer.tokenize("心梗");

        assertThat(tokens).contains("心梗", "心脏", "脏病", "病发", "发作");
    }

    @Test
    void expandsStrokeAliasAndPreservesOriginalTextFeatures() {
        List<String> tokens = tokenizer.tokenize("脑卒中");

        assertThat(tokens).contains("脑卒", "卒中", "中风");
    }

    @Test
    void canonicalCorpusTermsRemainUnchanged() {
        assertThat(tokenizer.tokenize("高血压")).contains("高血", "血压");
        assertThat(tokenizer.tokenize("糖尿病")).contains("糖尿", "尿病");
        assertThat(tokenizer.tokenize("高血脂")).contains("高血", "血脂");
        assertThat(tokenizer.tokenize("心脏病发作")).contains("心脏", "脏病", "病发", "发作");
        assertThat(tokenizer.tokenize("中风")).contains("中风");
    }

    @Test
    void unrelatedTextDoesNotReceiveDomainExpansions() {
        List<String> tokens = tokenizer.tokenize("量子纠错编码");

        assertThat(tokens).doesNotContain("高血压", "糖尿病", "高血脂", "心脏病发作", "中风");
    }

    @Test
    void allowsSingleSharedTokenWhenItAnchorsToDiseaseMetadata() {
        KnowledgeChunk chunk = chunk("高血压", "完全无关的证据内容。");

        assertThat(tokenizer.isRelated("血压非常高", chunk)).isTrue();
    }

    @Test
    void doesNotAllowSingleSharedBodyTokenWithoutDiseaseAnchor() {
        KnowledgeChunk chunk = chunk("其他病种", "血压异常。");

        assertThat(tokenizer.isRelated("血压", chunk)).isFalse();
    }

    @Test
    void doesNotUseGenericDiseaseMetadataAsAnAnchor() {
        KnowledgeChunk chunk = chunk("症状", "检查结果需要结合临床判断。");

        assertThat(tokenizer.isRelated("症状", chunk)).isFalse();
    }

    private KnowledgeChunk chunk(String disease, String content) {
        return new KnowledgeChunk(
                "TEST-001", "TEST-DOC", "测试", "公开资料", "测试来源", 2026, disease,
                List.of(), List.of(), List.of(), 0, 0, "测试", "未评级", List.of(), content,
                "test-checksum", "ACTIVE", "v1", Instant.EPOCH
        );
    }
}
