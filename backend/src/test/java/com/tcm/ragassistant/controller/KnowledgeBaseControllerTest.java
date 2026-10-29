package com.tcm.ragassistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeBaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.tcm.ragassistant.repository.KnowledgeBaseRepository repository;

    @Test
    void invalidCorpusIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/overview").param("corpus", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CORPUS"));
    }

    @Test
    void frameworkErrorsKeepTheirHttpStatus() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/overview"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/knowledge/import").contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void corporaEndpointExposesBaselineAndIndependentExpandedView() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/corpora"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("BASELINE"))
                .andExpect(jsonPath("$.data[0].documentCount").value(4))
                .andExpect(jsonPath("$.data[0].chunkCount").value(11))
                .andExpect(jsonPath("$.data[0].frozen").value(true))
                .andExpect(jsonPath("$.data[1].id").value("EXPANDED"))
                .andExpect(jsonPath("$.data[1].documentCount").value(
                        repository.findDocuments(com.tcm.ragassistant.domain.CorpusId.EXPANDED).size()))
                .andExpect(jsonPath("$.data[1].chunkCount").value(
                        repository.findAll(com.tcm.ragassistant.domain.CorpusId.EXPANDED).size()))
                .andExpect(jsonPath("$.data[1].note").value(containsString("不属于 Experiment A")))
                .andExpect(jsonPath("$.data[2].id").value("COMBINED"))
                .andExpect(jsonPath("$.data[2].documentCount").value(
                        repository.findDocuments(com.tcm.ragassistant.domain.CorpusId.COMBINED).size()));
        mockMvc.perform(get("/api/v1/knowledge/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remoteSyncState").value("IN_SYNC"));
    }

    @Test
    void combinedEndpointsExposeStandardAndExpandedDataWithScopedLookups() throws Exception {
        var corpus = com.tcm.ragassistant.domain.CorpusId.COMBINED;
        mockMvc.perform(get("/api/v1/knowledge/overview").param("corpus", corpus.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentCount").value(repository.findDocuments(corpus).size()))
                .andExpect(jsonPath("$.data.chunkCount").value(repository.findAll(corpus).size()));
        var expanded = repository.findAll(com.tcm.ragassistant.domain.CorpusId.EXPANDED).get(0);
        mockMvc.perform(get("/api/v1/knowledge/chunks/" + expanded.id()).param("corpus", corpus.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(expanded.id()));
        mockMvc.perform(get("/api/v1/knowledge/chunks/" + expanded.id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge/documents/" + expanded.documentId()).param("corpus", corpus.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(expanded.documentId()));
    }

    @Test
    void importShouldRejectUnsafeSourcesFutureYearsAndDuplicateContent() throws Exception {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "title", "Isolated import validation fixture",
                "sourceType", "Test", "publisher", "Test",
                "publicationYear", LocalDate.now().getYear(),
                "sourceUrl", "https://example.org/fixture",
                "licenseNote", "Automated tests only",
                "content", "This is an isolated import validation fixture, never delivered as medical evidence."
        ));
        for (String source : new String[]{"file:///etc/passwd", "https://user:secret@example.org/source"}) {
            payload.put("sourceUrl", source);
            mockMvc.perform(post("/api/v1/knowledge/import").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SOURCE_URL"));
        }
        payload.put("sourceUrl", "https://example.org/fixture");
        payload.put("publicationYear", LocalDate.now().getYear() + 1);
        mockMvc.perform(post("/api/v1/knowledge/import").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PUBLICATION_YEAR"));
        payload.put("publicationYear", LocalDate.now().getYear());
        mockMvc.perform(post("/api/v1/knowledge/import").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/knowledge/import").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_EXISTS"));
    }

    @Test
    void importShouldCreateTraceableChunksAndBeFilterable() throws Exception {
        String disease = "验证病种";
        mockMvc.perform(post("/api/v1/knowledge/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "知识库导入接口验证材料",
                                  "sourceType": "临床研究",
                                  "publisher": "测试数据源",
                                  "publicationYear": 2026,
                                  "authority": "自动化测试",
                                  "evidenceLevel": "B",
                                  "disease": "验证病种",
                                  "syndromes": ["测试证型"],
                                  "formulas": ["测试方"],
                                  "herbs": ["测试药"],
                                  "section": "方法",
                                  "version": "v-test",
                                  "licenseNote": "仅用于自动化测试，不进入正式知识库",
                                  "sourceUrl": "https://example.org/test-fixture",
                                  "keywords": ["接口验证", "可追溯"],
                                  "content": "第一段用于验证运行时导入、元数据登记和校验值生成。第二段确认文档与知识片段之间能够建立可追溯关系，并且不会混入正式来源。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("LOCAL_READY"))
                .andExpect(jsonPath("$.data.document.evidenceLevel").value("B"))
                .andExpect(jsonPath("$.data.document.sourceUrl").value("https://example.org/test-fixture"))
                .andExpect(jsonPath("$.data.chunkCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.document.diseaseScope[0]").value(disease))
                .andExpect(jsonPath("$.data.document.syndromes[0]").value("测试证型"))
                .andExpect(jsonPath("$.data.document.formulas[0]").value("测试方"))
                .andExpect(jsonPath("$.data.document.herbs[0]").value("测试药"))
                .andExpect(jsonPath("$.data.document.metadataSource").value("用户导入"))
                .andExpect(jsonPath("$.data.document.checksum").isNotEmpty());

        mockMvc.perform(get("/api/v1/knowledge/chunks")
                        .param("corpus", "COMBINED")
                        .param("disease", disease)
                        .param("sourceType", "临床研究")
                        .param("evidenceLevel", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[*].disease", everyItem(containsString(disease))))
                .andExpect(jsonPath("$.data[*].sourceType", everyItem(containsString("临床研究"))))
                .andExpect(jsonPath("$.data[*].evidenceLevel", everyItem(containsString("B"))))
                .andExpect(jsonPath("$.data[*].syndromes[0]", everyItem(containsString("测试证型"))))
                .andExpect(jsonPath("$.data[*].formulas[0]", everyItem(containsString("测试方"))))
                .andExpect(jsonPath("$.data[*].herbs[0]", everyItem(containsString("测试药"))))
                .andExpect(jsonPath("$.data[*].metadataSource", everyItem(containsString("用户导入"))));
    }

    @Test
    void chunkQueryShouldApplyMetadataFilters() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/chunks")
                        .param("disease", "高血压")
                        .param("sourceType", "健康资料")
                        .param("evidenceLevel", "未评级"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[*].disease", everyItem(containsString("高血压"))))
                .andExpect(jsonPath("$.data[*].sourceType", everyItem(containsString("健康资料"))))
                .andExpect(jsonPath("$.data[*].evidenceLevel", everyItem(containsString("未评级"))));
    }

    @Test
    void importShouldRejectInvalidMetadataAndShortContent() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": " ",
                                  "sourceType": "",
                                  "publisher": "",
                                  "publicationYear": 1800,
                                  "content": "正文过短"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("title: 文档标题不能为空")))
                .andExpect(jsonPath("$.message", containsString("sourceType: 来源类型不能为空")))
                .andExpect(jsonPath("$.message", containsString("publisher: 发布机构不能为空")))
                .andExpect(jsonPath("$.message", containsString("publicationYear: 出版年份不正确")))
                .andExpect(jsonPath("$.message", containsString("content: 正文长度应在 30-12000 个字符之间")));
    }

    @Test
    void importShouldRejectUnsupportedEvidenceLevel() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "非法证据等级测试材料",
                                  "sourceType": "测试",
                                  "publisher": "测试",
                                  "publicationYear": 2026,
                                  "evidenceLevel": "D",
                                  "licenseNote": "仅用于自动化测试",
                                  "sourceUrl": "https://example.org/invalid-evidence-level",
                                  "content": "这段内容用于验证非法证据等级会在进入持久化之前被拒绝处理。"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("evidenceLevel: 证据等级不正确")));
    }

    @Test
    void missingStaticResourceShouldReturnStructuredNotFound() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }
}
