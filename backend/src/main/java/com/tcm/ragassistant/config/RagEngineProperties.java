package com.tcm.ragassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "app.rag-engine")
public record RagEngineProperties(
        String mode,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        boolean fallbackToLocal,
        Duration retryInterval,
        int syncBatchSize
) {

    private static final Set<String> SUPPORTED_MODES = Set.of("auto", "llama-index", "local");

    @ConstructorBinding
    public RagEngineProperties {
        mode = normalizeMode(mode);
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "Unsupported app.rag-engine.mode: " + mode + "; expected auto, llama-index or local"
            );
        }
        baseUrl = baseUrl == null ? URI.create("http://127.0.0.1:8000") : baseUrl;
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofMillis(500));
        readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(30));
        retryInterval = positiveOrDefault(retryInterval, Duration.ofSeconds(10));
        syncBatchSize = syncBatchSize <= 0 ? 20 : syncBatchSize;
    }

    public RagEngineProperties(String mode, URI baseUrl, Duration connectTimeout, Duration readTimeout,
                               boolean fallbackToLocal, Duration retryInterval) {
        this(mode, baseUrl, connectTimeout, readTimeout, fallbackToLocal, retryInterval, 20);
    }

    public boolean remoteEnabled() {
        return "llama-index".equals(mode) || "auto".equals(mode);
    }

    public boolean strictRemote() {
        return "llama-index".equals(mode) && !fallbackToLocal;
    }

    private static String normalizeMode(String value) {
        if (value == null || value.isBlank()) {
            return "auto";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
