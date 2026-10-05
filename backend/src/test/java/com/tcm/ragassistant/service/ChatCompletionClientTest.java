package com.tcm.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tcm.ragassistant.exception.BusinessException;
import com.tcm.ragassistant.service.model.ChatCompletionClient;
import com.tcm.ragassistant.service.model.ModelConnection;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionClientTest {
    @Test
    void timesOutWhenBodyStallsAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(500); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = new ChatCompletionClient(new ObjectMapper(), Duration.ofMillis(200));
            assertThatThrownBy(() -> client.complete(connection(server), messages(), 20))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("MODEL_TIMEOUT"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutWithoutExposingConnectionDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try { Thread.sleep(400); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = new ChatCompletionClient(new ObjectMapper(), Duration.ofMillis(100));
            assertThatThrownBy(() -> client.complete(connection(server), messages(), 20))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("MODEL_TIMEOUT"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedProviderResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "x".repeat(300_000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new ChatCompletionClient(new ObjectMapper(), Duration.ofSeconds(3));
            assertThatThrownBy(() -> client.complete(connection(server), messages(), 20))
                    .isInstanceOf(BusinessException.class);
        } finally {
            server.stop(0);
        }
    }

    private ModelConnection connection(HttpServer server) {
        return new ModelConnection(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "protocol-test", "", true);
    }

    private List<Map<String, String>> messages() {
        return List.of(Map.of("role", "user", "content", "test"));
    }
}
