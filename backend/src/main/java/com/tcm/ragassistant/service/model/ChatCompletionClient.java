package com.tcm.ragassistant.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ragassistant.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ChatCompletionClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;
    private final Semaphore requests = new Semaphore(4);

    public ChatCompletionClient(ObjectMapper mapper,
                                @Value("${app.model.timeout:60s}") Duration timeout) {
        this.mapper = mapper;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String complete(ModelConnection connection, List<Map<String, String>> messages, int maxTokens) {
        return complete(connection, messages, maxTokens, null);
    }

    public String complete(ModelConnection connection, List<Map<String, String>> messages, int maxTokens, Double temperature) {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
            throw new BusinessException("MODEL_INVALID_PARAMETERS", "temperature 必须在 0–2 之间");
        if (!requests.tryAcquire()) {
            throw new BusinessException("MODEL_BUSY", "模型请求较多，请稍后重试");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of(
                    "model", connection.model(), "messages", messages,
                    "stream", false, "max_tokens", maxTokens));
            if (temperature != null) payload.put("temperature", temperature);
            String body = mapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(connection.baseUrl() + "/chat/completions"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!connection.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + connection.apiKey());
            }
            var future = client.sendAsync(builder.build(), info -> new LimitedBodySubscriber());
            HttpResponse<String> response;
            try {
                // Bound the entire body read, including gateways that stall after response headers.
                response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new BusinessException("MODEL_AUTH_FAILED", "模型鉴权失败，请检查 API Key 与模型权限");
            }
            if (status == 429) {
                throw new BusinessException("MODEL_RATE_LIMITED", "模型服务限流或额度不足，请稍后重试");
            }
            if (status < 200 || status >= 300) {
                throw new BusinessException("MODEL_UNAVAILABLE", "模型接口请求失败（HTTP " + status + "）");
            }
            JsonNode choice = mapper.readTree(response.body()).path("choices").path(0);
            JsonNode text = choice.path("message").path("content");
            if (!text.isTextual() || text.asText().isBlank()
                    || !"stop".equals(choice.path("finish_reason").asText())) {
                throw new BusinessException("MODEL_INVALID_RESPONSE", "模型未返回完整回答，请检查模型兼容性后重试");
            }
            return text.asText().trim();
        } catch (BusinessException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw new BusinessException("MODEL_TIMEOUT", "模型响应超时，请稍后重试");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new BusinessException("MODEL_TIMEOUT", "模型响应超时，请稍后重试");
            }
            throw new BusinessException("MODEL_UNAVAILABLE", "模型连接失败或响应格式不正确，请检查接口地址");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("MODEL_UNAVAILABLE", "模型请求已中断");
        } catch (Exception exception) {
            // Provider bodies and exception details may contain credentials; do not expose them.
            throw new BusinessException("MODEL_UNAVAILABLE", "模型连接失败或响应格式不正确，请检查接口地址");
        } finally {
            requests.release();
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long bytes;

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) bytes += item.remaining();
            if (bytes > 256 * 1024) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Model response too large"));
            } else {
                delegate.onNext(items);
            }
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
