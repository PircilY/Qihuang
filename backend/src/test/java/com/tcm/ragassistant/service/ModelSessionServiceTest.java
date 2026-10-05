package com.tcm.ragassistant.service;

import com.tcm.ragassistant.dto.ModelConfigRequest;
import com.tcm.ragassistant.service.model.ModelSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSessionServiceTest {
    @Test
    void serverDefaultsRemainRedactedAndCanBeDisabledPerSession() {
        var service = new ModelSessionService("https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1", "protocol-test", "server-test-key");
        assertThat(service.view(null).configured()).isTrue();
        assertThat(service.view(null).apiKeySet()).isTrue();
        assertThat(service.view(null).toString()).doesNotContain("server-test-key");
        assertThat(service.current(null).toString()).doesNotContain("server-test-key");
        var session = new MockHttpSession();
        service.clear(session);
        assertThat(service.current(session)).isNull();
        assertThat(service.current(new MockHttpSession())).isNotNull();
        var override = service.resolve(new ModelConfigRequest(
                "http://127.0.0.1:11434/v1", "local-test", "", true), session);
        service.save(session, override);
        assertThat(service.current(session).apiKey()).isEmpty();
        assertThat(service.current(null).model()).isEqualTo("protocol-test");
    }
}
