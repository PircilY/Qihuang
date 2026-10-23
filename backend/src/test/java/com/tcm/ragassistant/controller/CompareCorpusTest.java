package com.tcm.ragassistant.controller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class CompareCorpusTest {
    @Autowired MockMvc mvc;
    @Test void comparisonUsesSelectedCorpusForAllVersions() throws Exception {
        mvc.perform(post("/api/v1/rag/compare").contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"高血压没有症状也需要检查血压吗？\",\"corpus\":\"EXPANDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.V0_DIRECT.retrieval.notes").value(org.hamcrest.Matchers.hasItem("corpus=EXPANDED")))
            .andExpect(jsonPath("$.data.V1_VECTOR.retrieval.notes").value(org.hamcrest.Matchers.hasItem("corpus=EXPANDED")))
            .andExpect(jsonPath("$.data.V2_HYBRID.retrieval.notes").value(org.hamcrest.Matchers.hasItem("corpus=EXPANDED")))
            .andExpect(jsonPath("$.data.V3_GUARDED.retrieval.notes").value(org.hamcrest.Matchers.hasItem("corpus=EXPANDED")));
    }
    @Test void invalidComparisonCorpusIs400() throws Exception {
        mvc.perform(post("/api/v1/rag/compare").contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"[FIXTURE] 非医学占位问题\",\"corpus\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
    }
}
