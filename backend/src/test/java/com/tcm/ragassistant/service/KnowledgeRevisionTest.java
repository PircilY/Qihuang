package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRevisionTest {
    @Test
    void revisionUsesTheCrossLanguageCanonicalVector() {
        KnowledgeDocument a = document("DOC-A", "abc");
        KnowledgeDocument b = document("DOC-B", "def");
        assertThat(KnowledgeRevision.calculate(List.of(b, a)))
                .isEqualTo("679852034c6e66a9aceff2816972266392a6a651cd00a353ce8bd8ed21ddd6c0");
    }

    @Test
    void metadataOnlyChangesDoNotChangeRevision() {
        KnowledgeDocument before = document("DOC-META", "stable");
        KnowledgeDocument after = new KnowledgeDocument("DOC-META", "测试", "测试", "测试", 2026,
                "", "C", List.of("测试"), List.of("黄芪"), List.of("补阳还五汤"), List.of("气虚"),
                "", "stable", "v1", "ACTIVE", Instant.EPOCH, "", "", "https://example.org",
                null, "词典匹配");

        assertThat(KnowledgeRevision.calculate(List.of(after)))
                .isEqualTo(KnowledgeRevision.calculate(List.of(before)));
    }

    private KnowledgeDocument document(String id, String checksum) {
        return new KnowledgeDocument(id, "测试", "测试", "测试", 2026, "", "C",
                List.of("测试"), "", checksum, "v1", "ACTIVE", Instant.EPOCH,
                "", "", "https://example.org", null);
    }
}
