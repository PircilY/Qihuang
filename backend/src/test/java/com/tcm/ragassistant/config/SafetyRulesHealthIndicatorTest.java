package com.tcm.ragassistant.config;

import com.tcm.ragassistant.service.SafetyRules;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafetyRulesHealthIndicatorTest {
    @Test
    void reportsDownWhenRemoteRulesHashDiffers() {
        var properties = new RagEngineProperties("auto", null, null, null, true, Duration.ofSeconds(1));
        LlamaIndexRagClient remote = mock(LlamaIndexRagClient.class);
        when(remote.health()).thenReturn(Map.of("safetyRulesSha256", "different"));
        var health = new SafetyRulesHealthIndicator(properties, remote).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("localSha256", SafetyRules.sha256())
                .containsEntry("remoteSha256", "different");
    }

    @Test
    void reportsUpWhenLocalOnlyModeHasLoadedRules() {
        var properties = new RagEngineProperties("local", null, null, null, true, Duration.ofSeconds(1));
        var health = new SafetyRulesHealthIndicator(properties, mock(LlamaIndexRagClient.class)).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("localSha256")).isEqualTo(SafetyRules.sha256());
    }
}
