package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.ChatRequest;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.CompareRequest;
import com.tcm.ragassistant.dto.VersionResponse;
import com.tcm.ragassistant.service.RagOrchestrationService;
import com.tcm.ragassistant.service.model.ModelAnswerService;
import com.tcm.ragassistant.service.model.ModelSessionService;
import com.tcm.ragassistant.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class RagController {

    private final RagOrchestrationService ragOrchestrationService;
    private final ModelSessionService modelSessions;
    private final ModelAnswerService modelAnswers;

    public RagController(RagOrchestrationService ragOrchestrationService,
                         ModelSessionService modelSessions, ModelAnswerService modelAnswers) {
        this.ragOrchestrationService = ragOrchestrationService;
        this.modelSessions = modelSessions;
        this.modelAnswers = modelAnswers;
    }

    @GetMapping("/versions")
    public ApiResponse<List<VersionResponse>> versions() {
        return ApiResponse.success(Arrays.stream(IterationVersion.values())
                .map(VersionResponse::from)
                .toList());
    }

    @PostMapping("/rag/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        var connection = modelSessions.current(http.getSession(false));
        ChatResponse retrieved = ragOrchestrationService.askExtractive(
                request.question().trim(),
                request.version(),
                request.toRetrievalFilter(),
                request.corpusId()
        );
        if (retrieved.refused()) return ApiResponse.success(retrieved);
        if (request.useModel() && (connection == null || !connection.enabled())) {
            throw new BusinessException("MODEL_NOT_CONFIGURED", "当前会话尚未启用模型，请先配置模型");
        }
        return ApiResponse.success(request.useModel() ? modelAnswers.generate(retrieved, connection) : retrieved);
    }

    @PostMapping("/rag/compare")
    public ApiResponse<Map<IterationVersion, ChatResponse>> compare(
            @Valid @RequestBody CompareRequest request
    ) {
        return ApiResponse.success(ragOrchestrationService.compare(
                request.question().trim(),
                request.toRetrievalFilter(), request.corpusId()
        ));
    }
}
