package com.tcm.ragassistant.repository;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InMemoryKnowledgeBaseRepositoryTest {

    @Autowired
    private KnowledgeBaseRepository repository;

    @Test
    void invalidBatchMustNotLeavePartialDocument() {
        String documentId = "ATOMIC-TEST-DOC";
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "测试文档", "测试", "测试机构", 2024,
                "测试", "C", List.of("高血压"), "", "", "v1", "ACTIVE",
                Instant.now(), "", ""
        );
        KnowledgeChunk valid = chunk(documentId, "ATOMIC-TEST-C1");
        KnowledgeChunk mismatched = chunk("OTHER-DOC", "ATOMIC-TEST-C2");

        assertThatThrownBy(() -> repository.registerDocument(document, List.of(valid, mismatched)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前文档");
        assertThat(repository.findDocumentById(documentId)).isEmpty();
        assertThat(repository.findById(valid.id())).isEmpty();
    }

    private KnowledgeChunk chunk(String documentId, String id) {
        return new KnowledgeChunk(
                id, documentId, "测试文档", "测试", "测试机构", 2024,
                "高血压", List.of(), List.of(), List.of(), 0, 0,
                "正文", "C", List.of(), "测试内容", id + "-checksum",
                "ACTIVE", "v1", Instant.now()
        );
    }
}
