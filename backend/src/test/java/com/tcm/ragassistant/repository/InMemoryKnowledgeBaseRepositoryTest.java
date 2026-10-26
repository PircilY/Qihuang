package com.tcm.ragassistant.repository;

import com.tcm.ragassistant.domain.KnowledgeChunk;
import com.tcm.ragassistant.domain.KnowledgeDocument;
import com.tcm.ragassistant.domain.CorpusId;
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
        assertThat(repository.findDocumentById(documentId, CorpusId.BASELINE)).isEmpty();
        assertThat(repository.findById(valid.id(), CorpusId.BASELINE)).isEmpty();
    }

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @Test
    void snapshotRestoresAllImportsAndRejectsDuplicates() {
        Path snapshot = tempDir.resolve("imports.json");
        var first = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        first.registerDocument(document("IMPORT-1", "hash-1", "B"), List.of(chunk("IMPORT-1", "C-1", "B")));
        var second = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        assertThat(second.findById("C-1", CorpusId.BASELINE)).isPresent();
        assertThat(second.findById("C-1", CorpusId.BASELINE).orElseThrow().evidenceLevel()).isEqualTo("B");
        assertThat(second.findDocumentById("IMPORT-1", CorpusId.BASELINE).orElseThrow().evidenceLevel()).isEqualTo("B");
        second.registerDocument(document("IMPORT-2", "hash-2"), List.of(chunk("IMPORT-2", "C-2")));
        var restored = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        assertThat(restored.findById("C-1", CorpusId.BASELINE)).isPresent();
        assertThat(restored.findById("C-2", CorpusId.BASELINE)).isPresent();
        assertThatThrownBy(() -> restored.registerDocument(document("IMPORT-3", "hash-1"),
                List.of(chunk("IMPORT-3", "C-3")))).isInstanceOf(BusinessException.class);
        assertThat(restored.findById("C-3", CorpusId.BASELINE)).isEmpty();
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
        assertThat(isolated.findDocumentById("IMPORT-1", CorpusId.BASELINE)).isEmpty();
        assertThat(isolated.findById("C-1", CorpusId.BASELINE)).isEmpty();
    }

    @Test
    void readsAreCorpusScopedAndExpandedCorpusCannotBeWritten() {
        assertThat(repository.findAll(CorpusId.BASELINE)).hasSize(11);
        assertThat(repository.findAll(CorpusId.EXPANDED)).isNotEmpty();
        assertThat(repository.findAll(CorpusId.BASELINE)).hasSize(11);
        assertThatThrownBy(() -> ((InMemoryKnowledgeBaseRepository) repository).registerDocument(
                document("EXPANDED-WRITE", "hash-expanded"),
                List.of(chunk("EXPANDED-WRITE", "EXPANDED-WRITE-C1")), CorpusId.EXPANDED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持运行时覆盖");
    }

    @Test
    void oldKnowledgeJsonWithoutTerminologyFieldsUsesEmptyLists() throws Exception {
        String json = "{\"id\":\"DOC-OLD\",\"title\":\"旧格式\",\"sourceType\":\"指南\","
                + "\"publisher\":\"机构\",\"publicationYear\":2024,\"authority\":\"\","
                + "\"evidenceLevel\":\"未评级\",\"diseaseScope\":[],\"fileName\":\"old.txt\","
                + "\"checksum\":\"x\",\"version\":\"v1\",\"status\":\"ACTIVE\","
                + "\"registeredAt\":\"2024-01-01T00:00:00Z\",\"licenseNote\":\"\","
                + "\"description\":\"\",\"sourceUrl\":\"https://example.org\",\"accessedAt\":null}";

        KnowledgeDocument document = objectMapper.readValue(json, KnowledgeDocument.class);

        assertThat(document.herbs()).isEmpty();
        assertThat(document.formulas()).isEmpty();
        assertThat(document.syndromes()).isEmpty();
    }

    @Test
    void newKnowledgeJsonPreservesTerminologyFields() throws Exception {
        String json = "{\"id\":\"DOC-NEW\",\"title\":\"新格式\",\"sourceType\":\"指南\","
                + "\"publisher\":\"机构\",\"publicationYear\":2024,\"authority\":\"\","
                + "\"evidenceLevel\":\"未评级\",\"diseaseScope\":[],\"herbs\":[\"黄芪\"],"
                + "\"formulas\":[\"补阳还五汤\"],\"syndromes\":[\"气虚\"],\"fileName\":\"new.txt\","
                + "\"checksum\":\"x\",\"version\":\"v1\",\"status\":\"ACTIVE\","
                + "\"registeredAt\":\"2024-01-01T00:00:00Z\",\"licenseNote\":\"\","
                + "\"description\":\"\",\"sourceUrl\":\"https://example.org\",\"accessedAt\":null}";

        KnowledgeDocument document = objectMapper.readValue(json, KnowledgeDocument.class);

        assertThat(document.herbs()).containsExactly("黄芪");
        assertThat(document.formulas()).containsExactly("补阳还五汤");
        assertThat(document.syndromes()).containsExactly("气虚");
    }

    @Test
    void oldChunkJsonWithoutMetadataFieldsUsesEmptyLists() throws Exception {
        String json = "{\"id\":\"C-OLD\",\"documentId\":\"DOC-OLD\",\"title\":\"旧格式\","
                + "\"sourceType\":\"指南\",\"publisher\":\"机构\",\"publicationYear\":2024,"
                + "\"disease\":\"\",\"pageStart\":0,\"pageEnd\":0,\"section\":\"正文\","
                + "\"evidenceLevel\":\"未评级\",\"keywords\":[],\"content\":\"内容\","
                + "\"checksum\":\"x\",\"status\":\"ACTIVE\",\"version\":\"v1\","
                + "\"ingestedAt\":\"2024-01-01T00:00:00Z\"}";

        KnowledgeChunk chunk = objectMapper.readValue(json, KnowledgeChunk.class);

        assertThat(chunk.herbs()).isEmpty();
        assertThat(chunk.formulas()).isEmpty();
        assertThat(chunk.syndromes()).isEmpty();
    }

    @Test
    void seedAndExpandedAreExcludedFromRuntimeRevision() {
        var isolated = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), "");
        assertThat(isolated.knowledgeRevision(CorpusId.BASELINE)).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(isolated.knowledgeRevision(CorpusId.EXPANDED)).isEmpty();
        assertThat(isolated.findImportedDocuments()).isEmpty();
        isolated.registerDocument(document("IMPORT-REV", "hash-rev"), List.of(chunk("IMPORT-REV", "REV-C1")));
        assertThat(isolated.knowledgeRevision(CorpusId.BASELINE)).isEqualTo(
                com.tcm.ragassistant.service.KnowledgeRevision.calculate(List.of(document("IMPORT-REV", "hash-rev"))));
    }

    @Test
    void journalReplaysBetweenCheckpointsAndDiscardsIncompleteTail() throws Exception {
        Path snapshot = tempDir.resolve("journal.json");
        var first = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        for (int i = 1; i <= 3; i++) first.registerDocument(document("J-" + i, "jh-" + i),
                List.of(chunk("J-" + i, "JC-" + i)));
        Path journal = tempDir.resolve("journal.json.jsonl");
        assertThat(Files.readString(journal)).contains("J-3").doesNotContain("J-1");
        long length = Files.size(journal);
        Files.writeString(journal, "{incomplete", java.nio.file.StandardOpenOption.APPEND);
        var restored = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), snapshot.toString());
        assertThat(restored.findImportedDocuments()).hasSize(3);
        assertThat(restored.findById("JC-3", CorpusId.BASELINE)).isPresent();
        assertThat(Files.size(journal)).isEqualTo(length);
    }

    @Test
    void capacityBoundaryIsAtomic() {
        var isolated = new InMemoryKnowledgeBaseRepository(objectMapper, new DefaultResourceLoader(), "");
        org.springframework.test.util.ReflectionTestUtils.setField(isolated, "maxImportDocuments", 1);
        int characters = chunk("CAP-1", "CAP-C1").content().length();
        org.springframework.test.util.ReflectionTestUtils.setField(isolated, "maxImportCharacters", (long) characters);
        isolated.registerDocument(document("CAP-1", "cap-h1"), List.of(chunk("CAP-1", "CAP-C1")));
        assertThatThrownBy(() -> isolated.registerDocument(document("CAP-2", "cap-h2"), List.of(chunk("CAP-2", "CAP-C2"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超过配置上限");
        assertThat(isolated.findImportedDocuments()).hasSize(1);
    }

    private KnowledgeDocument document(String id, String checksum) {
        return document(id, checksum, "未评级");
    }

    private KnowledgeDocument document(String id, String checksum, String evidenceLevel) {
        return new KnowledgeDocument(id, "测试", "测试", "测试", 2025, "", evidenceLevel,
                List.of("高血压"), "", checksum, "v1", "ACTIVE", Instant.now(),
                "测试数据", "", "https://example.org/fixture", null);
    }

    private KnowledgeChunk chunk(String documentId, String id) {
        return chunk(documentId, id, "C");
    }

    private KnowledgeChunk chunk(String documentId, String id, String evidenceLevel) {
        return new KnowledgeChunk(
                id, documentId, "测试文档", "测试", "测试机构", 2024,
                "高血压", List.of(), List.of(), List.of(), 0, 0,
                "正文", evidenceLevel, List.of(), "测试内容", id + "-checksum",
                "ACTIVE", "v1", Instant.now()
        );
    }
}
