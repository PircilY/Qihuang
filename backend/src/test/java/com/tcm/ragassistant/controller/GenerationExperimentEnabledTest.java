package com.tcm.ragassistant.controller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="app.experiments.enabled=true")
@AutoConfigureMockMvc
class GenerationExperimentEnabledTest {
    @Autowired MockMvc mvc;
    @Test void enabledEndpointPreservesEveryFailureAndExtractiveResult() throws Exception {
        mvc.perform(post("/api/v1/experiments/generation").contentType(MediaType.APPLICATION_JSON)
            .content("{\"questions\":[{\"id\":\"fixture\",\"question\":\"[FIXTURE] 非医学测试占位问题\"}],\"limit\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(3))
            .andExpect(jsonPath("$.data.rows[0].failureReason").value("MODEL_NOT_CONFIGURED"));
    }
    @Test void crossSiteExperimentIsRejected() throws Exception {
        mvc.perform(post("/api/v1/experiments/generation").header("Origin","https://untrusted.example")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
