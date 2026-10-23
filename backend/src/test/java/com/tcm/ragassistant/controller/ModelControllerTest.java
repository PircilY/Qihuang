package com.tcm.ragassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ModelControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    private HttpServer server;
    private MockHttpSession session;
    private String base;
    private String reply;
    private int statusCode;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> upstream = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startProvider() throws Exception {
        session = new MockHttpSession();
        calls.set(0);
        statusCode = 200;
        reply = completion("血压检查有助于识别没有症状的高血压。[1]", "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            upstream.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
    }

    @Test
    void configIsRedactedAndIsolatedAndCanBeCleared() throws Exception {
        save();
        mvc.perform(get("/api/v1/rag/model").session(session))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.apiKeySet").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        mvc.perform(get("/api/v1/rag/model"))
                .andExpect(jsonPath("$.data.configured").value(false));
        mvc.perform(delete("/api/v1/rag/model").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rag/model").session(session))
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test
    void connectionTestUsesDraftWithoutSavingIt() throws Exception {
        mvc.perform(post("/api/v1/rag/model/test").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(config(base, "secret-test-key")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connected").value(true));
        assertThat(upstream.get().path("messages").get(0).path("content").asText()).contains("OK");
        mvc.perform(get("/api/v1/rag/model").session(session))
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test
    void ragSendsRetrievedEvidenceAndKeepsCitations() throws Exception {
        save();
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压患者没有症状也需要检查血压吗？", "V3_GUARDED", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refused").value(false))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("[1]")))
                .andExpect(jsonPath("$.data.retrieval.notes").value(org.hamcrest.Matchers.hasItem("generation=llm")))
                .andExpect(jsonPath("$.data.citations[0].quote").isNotEmpty());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorization.get()).isEqualTo("Bearer secret-test-key");
        JsonNode messages = upstream.get().path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        JsonNode context = mapper.readTree(messages.get(1).path("content").asText());
        assertThat(context.path("evidence").size()).isPositive();
        assertThat(context.path("evidence").get(0).path("quote").asText()).contains("高血压");
        assertThat(messages.toString()).doesNotContain("secret-test-key");
        assertThat(upstream.get().path("stream").asBoolean()).isFalse();
    }

    @Test
    void blankKeyPreservesSameEndpointCredentialsButNeverCopiesToNewEndpoint() throws Exception {
        save();
        mvc.perform(put("/api/v1/rag/model").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(config(base, ""))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/rag/model/test").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(config(base, ""))).andExpect(status().isOk());
        assertThat(authorization.get()).isEqualTo("Bearer secret-test-key");
        mvc.perform(put("/api/v1/rag/model").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(config("https://api.deepseek.com/v1", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_CONFIG_INVALID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.deepseek.com/v1", "http://169.254.169.254/v1",
            "file:///etc/passwd", "https://not-approved.example/v1",
            "https://api.deepseek.com/v1?token=hidden", "http://secret@127.0.0.1/v1",
            "http://127.0.0.1/admin", "http://127.0.0.1/v1#fragment"
    })
    void rejectsUnapprovedDestinations(String baseUrl) throws Exception {
        mvc.perform(put("/api/v1/rag/model").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(config(baseUrl, "secret-test-key")))
                .andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsCrossOriginRequests() throws Exception {
        mvc.perform(put("/api/v1/rag/model").header("Origin", "https://untrusted.example")
                        .contentType(MediaType.APPLICATION_JSON).content(config(base, "secret-test-key")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rag/model").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownSessionCannotUseAnotherSessionsModel() throws Exception {
        save();
        mvc.perform(post("/api/v1/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V3_GUARDED", true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(com.tcm.ragassistant.domain.IterationVersion.class)
    void emergencyIsReturnedEvenWhenModelIsRequestedButNotConfigured(
            com.tcm.ragassistant.domain.IterationVersion version) throws Exception {
        mvc.perform(post("/api/v1/rag/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(chat("我现在胸痛", version.name(), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refused").value(true))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("立即拨打 120 / 前往急诊")));
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"胸痛持续不缓解，怎么办？", "我每天吃甘草多少克？", "如何修理汽车发动机？"})
    void refusesUnsafeOrUnsupportedQuestionsWithoutCallingModel(String question) throws Exception {
        save();
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat(question, "V3_GUARDED", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refused").value(true));
        assertThat(calls.get()).isZero();
    }

    @Test
    void extractiveAndDirectModesDoNotCallModel() throws Exception {
        save();
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V3_GUARDED", false))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V0_DIRECT", true))).andExpect(status().isOk());
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"回答没有引用。", "错误引用 [99]", "错误引用 [999999999999999999999]", "错误引用 [0]"})
    void blocksInvalidCitations(String text) throws Exception {
        save();
        reply = completion(text, "stop");
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V3_GUARDED", true)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_INVALID_CITATIONS"));
    }

    @Test
    void blocksTruncatedAnswers() throws Exception {
        save();
        reply = completion("尚未完成的回答 [1]", "length");
        mvc.perform(post("/api/v1/rag/chat").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V3_GUARDED", true)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_INVALID_RESPONSE"));
    }

    @Test
    void neverExposesProviderErrorsOrSilentlyFallsBack() throws Exception {
        save();
        statusCode = 401;
        reply = "{\"error\":\"secret-test-key provider-private-details\"}";
        var response = mvc.perform(post("/api/v1/rag/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("高血压的症状是什么？", "V3_GUARDED", true)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_AUTH_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("secret-test-key", "provider-private-details");
    }

    private void save() throws Exception {
        mvc.perform(put("/api/v1/rag/model").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(config(base, "secret-test-key")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
    }

    private String config(String endpoint, String key) throws Exception {
        return mapper.writeValueAsString(Map.of("baseUrl", endpoint, "model", "protocol-test",
                "apiKey", key, "enabled", true));
    }

    private String chat(String question, String version, boolean useModel) throws Exception {
        return mapper.writeValueAsString(Map.of("question", question, "version", version, "useModel", useModel));
    }

    private String completion(String content, String finishReason) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", new Object[]{Map.of(
                "message", Map.of("role", "assistant", "content", content), "finish_reason", finishReason)}));
    }
}
