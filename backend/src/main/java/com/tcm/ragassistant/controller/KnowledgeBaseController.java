package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.KnowledgeChunkResponse;
import com.tcm.ragassistant.dto.KnowledgeDocumentResponse;
import com.tcm.ragassistant.dto.KnowledgeImportRequest;
import com.tcm.ragassistant.dto.KnowledgeImportResponse;
import com.tcm.ragassistant.dto.KnowledgeOverviewResponse;
import com.tcm.ragassistant.dto.PipelineStageResponse;
import com.tcm.ragassistant.service.KnowledgeBaseService;
import com.tcm.ragassistant.service.KnowledgeIngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeIngestionService ingestionService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeIngestionService ingestionService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/overview")
    public ApiResponse<KnowledgeOverviewResponse> overview() {
        return ApiResponse.success(knowledgeBaseService.overview());
    }

    @GetMapping("/chunks")
    public ApiResponse<List<KnowledgeChunkResponse>> chunks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String disease,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String evidenceLevel
    ) {
        return ApiResponse.success(knowledgeBaseService.chunks(
                keyword,
                disease,
                sourceType,
                evidenceLevel
        ));
    }

    @GetMapping("/chunks/{id}")
    public ApiResponse<KnowledgeChunkResponse> chunk(@PathVariable String id) {
        return ApiResponse.success(knowledgeBaseService.chunk(id));
    }

    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> documents() {
        return ApiResponse.success(knowledgeBaseService.documents());
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentResponse> document(@PathVariable String id) {
        return ApiResponse.success(knowledgeBaseService.document(id));
    }

    @GetMapping("/pipeline")
    public ApiResponse<List<PipelineStageResponse>> pipeline() {
        return ApiResponse.success(knowledgeBaseService.pipeline());
    }

    @PostMapping("/import")
    public ApiResponse<KnowledgeImportResponse> importDocument(
            @Valid @RequestBody KnowledgeImportRequest request
    ) {
        return ApiResponse.success(ingestionService.ingest(request));
    }
}

