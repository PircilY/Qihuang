package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.EvaluationRunResponse;
import com.tcm.ragassistant.service.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/run")
    public ApiResponse<EvaluationRunResponse> run() {
        return ApiResponse.success(evaluationService.run());
    }
}

