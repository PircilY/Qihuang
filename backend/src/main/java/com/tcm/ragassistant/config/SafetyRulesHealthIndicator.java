package com.tcm.ragassistant.config;

import com.tcm.ragassistant.service.SafetyRules;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("safetyRules")
public class SafetyRulesHealthIndicator implements HealthIndicator {
    private final RagEngineProperties properties;
    private final LlamaIndexRagClient remote;

    public SafetyRulesHealthIndicator(RagEngineProperties properties, LlamaIndexRagClient remote) {
        this.properties = properties;
        this.remote = remote;
    }

    @Override
    public Health health() {
        String localHash = SafetyRules.sha256();
        if (!properties.remoteEnabled()) {
            return Health.up().withDetail("localSha256", localHash).withDetail("remoteSha256", "not-configured").build();
        }
        try {
            Map<String, Object> remoteHealth = remote.health();
            Object value = remoteHealth.get("safetyRulesSha256");
            String remoteHash = value == null ? "missing" : value.toString();
            if (!localHash.equalsIgnoreCase(remoteHash)) {
                return Health.down().withDetail("error", "safety rule hash mismatch")
                        .withDetail("localSha256", localHash).withDetail("remoteSha256", remoteHash).build();
            }
            return Health.up().withDetail("localSha256", localHash).withDetail("remoteSha256", remoteHash).build();
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("localSha256", localHash)
                    .withDetail("remoteSha256", "unavailable").build();
        }
    }
}
