package com.tcm.ragassistant.service.model;

import com.tcm.ragassistant.dto.ModelConfigRequest;
import com.tcm.ragassistant.exception.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ModelSessionService {
    private static final String ATTRIBUTE = ModelSessionService.class.getName() + ".connection";
    private final Set<String> allowedBases;
    private final ModelConnection defaultConnection;

    public ModelSessionService(@Value("${app.model.allowed-base-urls:"
            + "https://api.deepseek.com/v1,https://api.deepseek.com,"
            + "https://dashscope.aliyuncs.com/compatible-mode/v1,https://api.openai.com/v1}") String bases,
            @Value("${app.model.api-base:}") String defaultBase,
            @Value("${app.model.name:}") String defaultModel,
            @Value("${app.model.api-key:}") String defaultKey) {
        allowedBases = Arrays.stream(bases.split(",")).map(String::trim)
                .map(value -> value.replaceAll("/+$", "")).collect(Collectors.toUnmodifiableSet());
        defaultConnection = defaultBase.isBlank() || defaultModel.isBlank() ? null
                : resolve(new ModelConfigRequest(defaultBase, defaultModel, defaultKey, true), null);
    }

    public ModelConnection resolve(ModelConfigRequest request, HttpSession session) {
        URI base = validateBase(request.baseUrl());
        String model = request.model().trim();
        if (model.isBlank() || model.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("MODEL_CONFIG_INVALID", "模型名称不正确");
        }
        ModelConnection previous = current(session);
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        if (key.isBlank() && previous != null && previous.baseUrl().equals(base)) {
            key = previous.apiKey();
        }
        if (key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new BusinessException("MODEL_CONFIG_INVALID", "API Key 格式不正确");
        }
        if (!isLoopback(base.getHost()) && key.isBlank()) {
            throw new BusinessException("MODEL_CONFIG_INVALID", "远程模型需要 API Key");
        }
        return new ModelConnection(base, model, key, request.enabled());
    }

    private URI validateBase(String input) {
        try {
            URI base = URI.create(input.trim().replaceAll("/+$", ""));
            if (base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            boolean local = isLoopback(base.getHost());
            boolean localApi = local && ("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    && "/v1".equals(base.getPath()) && (base.getPort() == -1 || base.getPort() > 0);
            // Additional gateways must be explicitly authorized by the server operator.
            boolean remoteApi = "https".equals(base.getScheme()) && allowedBases.contains(base.toString());
            if (!localApi && !remoteApi) {
                throw new IllegalArgumentException();
            }
            return base;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("MODEL_CONFIG_INVALID",
                    "接口地址未获授权；本机服务须使用 /v1，其他网关须由管理员加入允许列表");
        }
    }

    private boolean isLoopback(String host) {
        return Set.of("127.0.0.1", "localhost", "[::1]").contains(host);
    }

    public ModelConnection current(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(ATTRIBUTE);
        if (Boolean.FALSE.equals(value)) return null;
        return value instanceof ModelConnection connection ? connection : defaultConnection;
    }

    public ModelConfigView view(HttpSession session) {
        ModelConnection connection = current(session);
        return connection == null ? new ModelConfigView(false, false, "", "", false)
                : new ModelConfigView(true, connection.enabled(), connection.baseUrl().toString(),
                        connection.model(), !connection.apiKey().isBlank());
    }

    public void save(HttpSession session, ModelConnection connection) {
        session.setAttribute(ATTRIBUTE, connection);
    }

    public void clear(HttpSession session) {
        if (session != null) session.setAttribute(ATTRIBUTE, Boolean.FALSE);
    }

    public record ModelConfigView(boolean configured, boolean enabled, String baseUrl,
                                  String model, boolean apiKeySet) {
    }
}
