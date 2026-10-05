package com.tcm.ragassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionsEndpointShouldExposeIterationTimeline() throws Exception {
        mockMvc.perform(get("/api/v1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].version").value("V0_DIRECT"))
                .andExpect(jsonPath("$.data[3].version").value("V3_GUARDED"));
    }

    @Test
    void chatEndpointShouldReturnTraceableResponse() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "高血压患者没有症状也需要检查血压吗？",
                                  "version": "V3_GUARDED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.version").value("V3_GUARDED"))
                .andExpect(jsonPath("$.data.refused").value(false))
                .andExpect(jsonPath("$.data.citations").isNotEmpty())
                .andExpect(jsonPath("$.data.retrieval.strategy").value("guarded-hybrid"));
    }

    @Test
    void chatEndpointShouldRejectBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": " ",
                                  "version": "V3_GUARDED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("question: 问题不能为空"));
    }

    @Test
    void chatEndpointShouldRejectUnknownVersion() throws Exception {
        mockMvc.perform(post("/api/v1/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "高血压如何辨证？",
                                  "version": "V9_UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求体格式或枚举值不正确"));
    }
}
