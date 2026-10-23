package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.service.SafetyRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "management.endpoint.health.show-components=always",
        "management.endpoint.health.show-details=always"
})
@AutoConfigureMockMvc
class SafetyRulesActuatorHealthTest {
    @Autowired private MockMvc mvc;

    @Test
    void actuatorHealthIncludesLoadedRuleHash() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.safetyRules.details.localSha256").value(SafetyRules.sha256()));
    }
}
