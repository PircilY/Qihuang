package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.EvaluationRunResponse;
import com.tcm.ragassistant.service.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.tcm.ragassistant.service.model.ModelSessionService;
import jakarta.servlet.http.HttpServletRequest;
import com.tcm.ragassistant.exception.BusinessException;

@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final ModelSessionService modelSessions;

    public EvaluationController(EvaluationService evaluationService, ModelSessionService modelSessions) {
        this.evaluationService = evaluationService;
        this.modelSessions = modelSessions;
    }

    @PostMapping("/run")
    public ApiResponse<EvaluationRunResponse> run() {
        return ApiResponse.success(evaluationService.run());
    }

    @PostMapping("/holdout/run")
    public ApiResponse<EvaluationRunResponse> runHoldout(
            @RequestParam(defaultValue = "false") boolean useModel,
            @RequestParam(defaultValue = "false") boolean judge,
            HttpServletRequest request) {
        var connection = useModel || judge ? modelSessions.current(request.getSession(false)) : null;
        if ((useModel || judge) && (connection == null || !connection.enabled())) {
            throw new BusinessException("MODEL_NOT_CONFIGURED", "当前会话尚未启用模型");
        }
        return ApiResponse.success(evaluationService.runHoldout(connection, judge));
    }

    @PostMapping(value = "/holdout/review.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> holdoutReviewCsv(
            @RequestParam(defaultValue = "40") int sampleSize,
            @RequestParam(defaultValue = "false") boolean useModel,
            @RequestParam(defaultValue = "false") boolean judge,
            HttpServletRequest request) {
        var connection = useModel || judge ? modelSessions.current(request.getSession(false)) : null;
        if ((useModel || judge) && (connection == null || !connection.enabled())) {
            throw new BusinessException("MODEL_NOT_CONFIGURED", "当前会话尚未启用模型");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=holdout-review.csv")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(evaluationService.holdoutReviewCsv(sampleSize, connection, judge));
    }
}

