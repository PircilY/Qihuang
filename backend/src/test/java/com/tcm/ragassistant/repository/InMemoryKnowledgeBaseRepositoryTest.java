package com.tcm.ragassistant.repository;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;
import org.junit.jupiter.api.io.TempDir;

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
                Instant.now(), "", "", "https://example.org/fixture", null
        );
        KnowledgeChunk valid = chunk(documentId, "ATOMIC-TEST-C1");
        KnowledgeChunk mismatched = chunk("OTHER-DOC", "ATOMIC-TEST-C2");

        assertThatThrownBy(() -> repository.registerDocument(document, List.of(valid, mismatched)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前文档");
        assertThat(repository.findDocumentById(documentId)).isEmpty();
        assertThat(repository.findById(valid.id())).isEmpty();
    }

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @Test
    void snapshotRestoresAllImportsAndRejectsDuplicates() {
        Path snapshot = tempDir.resolve("imports.json");
        var first = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        first.registerDocument(document("IMPORT-1", "hash-1"), List.of(chunk("IMPORT-1", "C-1")));
        var second = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        assertThat(second.findById("C-1")).isPresent();
        second.registerDocument(document("IMPORT-2", "hash-2"), List.of(chunk("IMPORT-2", "C-2")));
        var restored = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        assertThat(restored.findById("C-1")).isPresent();
        assertThat(restored.findById("C-2")).isPresent();
        assertThatThrownBy(() -> restored.registerDocument(document("IMPORT-3", "hash-1"),
                List.of(chunk("IMPORT-3", "C-3")))).isInstanceOf(BusinessException.class);
        assertThat(restored.findById("C-3")).isEmpty();
    }

    @Test
    void failedPersistenceDoesNotPublishToMemory() throws Exception {
        Path parent = tempDir.resolve("not-a-directory");
        Files.writeString(parent, "fixture");
        var isolated = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(),
                parent.resolve("imports.json").toString());
        assertThatThrownBy(() -> isolated.registerDocument(document("IMPORT-1", "hash-1"),
                List.of(chunk("IMPORT-1", "C-1")))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("保存失败");
        assertThat(isolated.findDocumentById("IMPORT-1")).isEmpty();
        assertThat(isolated.findById("C-1")).isEmpty();
    }

    private KnowledgeDocument document(String id, String checksum) {
        return new KnowledgeDocument(id, "测试", "测试", "测试", 2025, "", "未评级",
                List.of("高血压"), "", checksum, "v1", "ACTIVE", Instant.now(),
                "测试数据", "", "https://example.org/fixture", null);
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
