package com.tcm.ragassistant.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagEnginePropertiesTest {

    @Test
    void shouldNormalizeModeAndExposeRemoteCapability() {
        RagEngineProperties properties = properties("LLAMA_INDEX", true);

        assertThat(properties.mode()).isEqualTo("llama-index");
        assertThat(properties.remoteEnabled()).isTrue();
        assertThat(properties.strictRemote()).isFalse();
    }

    @Test
    void shouldRejectUnsupportedMode() {
        assertThatThrownBy(() -> properties("invalid", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported app.rag-engine.mode");
    }

    private RagEngineProperties properties(String mode, boolean fallbackToLocal) {
        return new RagEngineProperties(
                mode,
                URI.create("http://127.0.0.1:8000"),
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                fallbackToLocal,
                Duration.ofSeconds(10)
        );
    }
}
