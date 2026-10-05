package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.ModelConfigRequest;
import com.tcm.ragassistant.service.model.ChatCompletionClient;
import com.tcm.ragassistant.service.model.ModelSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/model")
public class ModelController {
    private final ModelSessionService sessions;
    private final ChatCompletionClient client;

    public ModelController(ModelSessionService sessions, ChatCompletionClient client) {
        this.sessions = sessions;
        this.client = client;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ModelSessionService.ModelConfigView>> get(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(sessions.view(request.getSession(false))));
    }

    @PutMapping(consumes = "application/json")
    public ApiResponse<ModelSessionService.ModelConfigView> save(
            @Valid @RequestBody ModelConfigRequest config, HttpServletRequest request) {
        var session = request.getSession();
        var connection = sessions.resolve(config, session);
        sessions.save(session, connection);
        return ApiResponse.success(sessions.view(session));
    }

    @DeleteMapping
    public ApiResponse<ModelSessionService.ModelConfigView> clear(HttpServletRequest request) {
        var session = request.getSession();
        sessions.clear(session);
        return ApiResponse.success(sessions.view(session));
    }

    @PostMapping(value = "/test", consumes = "application/json")
    public ApiResponse<Map<String, Object>> test(
            @Valid @RequestBody ModelConfigRequest config, HttpServletRequest request) {
        var connection = sessions.resolve(config, request.getSession(false));
        long start = System.nanoTime();
        client.complete(connection, List.of(
                Map.of("role", "user", "content", "Reply with OK only.")), 64);
        return ApiResponse.success(Map.of("connected", true, "model", connection.model(),
                "latencyMs", Math.max(1, (System.nanoTime() - start) / 1_000_000)));
    }
}
