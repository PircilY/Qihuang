package com.tcm.ragassistant.config;

import org.springframework.stereotype.Component;
import org.springframework.boot.actuate.health.*;
import com.tcm.ragassistant.service.remote.LlamaIndexRagClient;

@Component("retrieval")
public class RetrievalHealthIndicator implements HealthIndicator {
    private final RagEngineProperties properties;
    private final LlamaIndexRagClient remote;
    public RetrievalHealthIndicator(RagEngineProperties properties, LlamaIndexRagClient remote) {
        this.properties=properties; this.remote=remote;
    }
    public Health health() {
        var result = Health.up().withDetail("localEngine", "java-local").withDetail("embeddingBackend", "hash")
                .withDetail("embeddingModel", "hash-sha256-bigram-v1");
        if (properties.remoteEnabled()) {
            try { result.withDetail("remote", remote.health()); }
            catch (RuntimeException exception) { result.withDetail("remote", "unavailable"); }
        }
        return result.build();
    }
}
