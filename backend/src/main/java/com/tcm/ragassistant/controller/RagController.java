package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.ChatRequest;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.dto.CompareRequest;
import com.tcm.ragassistant.dto.VersionResponse;
import com.tcm.ragassistant.service.RagOrchestrationService;
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

    public RagController(RagOrchestrationService ragOrchestrationService) {
        this.ragOrchestrationService = ragOrchestrationService;
    }

    @GetMapping("/versions")
    public ApiResponse<List<VersionResponse>> versions() {
        return ApiResponse.success(Arrays.stream(IterationVersion.values())
                .map(VersionResponse::from)
                .toList());
    }

    @PostMapping("/rag/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(ragOrchestrationService.ask(
                request.question().trim(),
                request.version(),
                request.toRetrievalFilter()
        ));
    }

    @PostMapping("/rag/compare")
    public ApiResponse<Map<IterationVersion, ChatResponse>> compare(
            @Valid @RequestBody CompareRequest request
    ) {
        return ApiResponse.success(ragOrchestrationService.compare(
                request.question().trim(),
                request.toRetrievalFilter()
        ));
    }
}

