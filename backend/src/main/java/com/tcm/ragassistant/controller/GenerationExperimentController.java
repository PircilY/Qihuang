package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.service.GenerationExperimentService;
import com.tcm.ragassistant.service.model.ModelSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/experiments")
@ConditionalOnProperty(name="app.experiments.enabled", havingValue="true")
public class GenerationExperimentController {
    private final GenerationExperimentService experiments;
    private final ModelSessionService sessions;
    public GenerationExperimentController(GenerationExperimentService experiments, ModelSessionService sessions) {
        this.experiments=experiments; this.sessions=sessions;
    }
    @PostMapping("/generation")
    public ApiResponse<Map<String,Object>> generation(@RequestBody GenerationExperimentService.Request input, HttpServletRequest request) {
        return ApiResponse.success(experiments.run(input,sessions.current(request.getSession(false))));
    }
}
