package com.tcm.ragassistant.controller;

import com.tcm.ragassistant.dto.ApiResponse;
import com.tcm.ragassistant.dto.KnowledgeChunkResponse;
import com.tcm.ragassistant.dto.KnowledgeDocumentResponse;
import com.tcm.ragassistant.dto.KnowledgeImportRequest;
import com.tcm.ragassistant.dto.KnowledgeImportResponse;
import com.tcm.ragassistant.dto.KnowledgeOverviewResponse;
import com.tcm.ragassistant.dto.PipelineStageResponse;
import com.tcm.ragassistant.dto.CorpusInfoResponse;
import com.tcm.ragassistant.domain.CorpusId;
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
    public ApiResponse<KnowledgeOverviewResponse> overview(@RequestParam(required = false) String corpus) {
        return ApiResponse.success(knowledgeBaseService.overview(CorpusId.from(corpus)));
    }

    @GetMapping("/corpora")
    public ApiResponse<List<CorpusInfoResponse>> corpora() {
        return ApiResponse.success(knowledgeBaseService.corpora());
    }

    @GetMapping("/chunks")
    public ApiResponse<List<KnowledgeChunkResponse>> chunks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String disease,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String evidenceLevel,
            @RequestParam(required = false) String corpus
    ) {
        return ApiResponse.success(knowledgeBaseService.chunks(
                keyword,
                disease,
                sourceType,
                evidenceLevel,
                CorpusId.from(corpus)
        ));
    }

    @GetMapping("/chunks/{id}")
    public ApiResponse<KnowledgeChunkResponse> chunk(@PathVariable String id,
                                                     @RequestParam(required = false) String corpus) {
        return ApiResponse.success(knowledgeBaseService.chunk(id, CorpusId.from(corpus)));
    }

    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> documents(@RequestParam(required = false) String corpus) {
        return ApiResponse.success(knowledgeBaseService.documents(CorpusId.from(corpus)));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentResponse> document(@PathVariable String id,
                                                           @RequestParam(required = false) String corpus) {
        return ApiResponse.success(knowledgeBaseService.document(id, CorpusId.from(corpus)));
    }

    @GetMapping("/pipeline")
    public ApiResponse<List<PipelineStageResponse>> pipeline(@RequestParam(required = false) String corpus) {
        return ApiResponse.success(knowledgeBaseService.pipeline(CorpusId.from(corpus)));
    }

    @PostMapping("/import")
    public ApiResponse<KnowledgeImportResponse> importDocument(
            @Valid @RequestBody KnowledgeImportRequest request
    ) {
        return ApiResponse.success(ingestionService.ingest(request));
    }
}
