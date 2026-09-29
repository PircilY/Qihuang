package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.domain.RetrievalFilter;
import com.tcm.ragassistant.dto.ChatResponse;
import com.tcm.ragassistant.service.retrieval.RetrievalOutput;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategy;
import com.tcm.ragassistant.service.retrieval.RetrievalStrategyRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagOrchestrationService {

    private final RetrievalStrategyRegistry strategyRegistry;
    private final AnswerGenerationService answerGenerationService;

    public RagOrchestrationService(
            RetrievalStrategyRegistry strategyRegistry,
            AnswerGenerationService answerGenerationService
    ) {
        this.strategyRegistry = strategyRegistry;
        this.answerGenerationService = answerGenerationService;
    }

    public ChatResponse ask(String question, IterationVersion version) {
        return ask(question, version, RetrievalFilter.none());
    }

    public ChatResponse ask(String question, IterationVersion version, RetrievalFilter filter) {
        long startedAt = System.nanoTime();
        RetrievalStrategy strategy = strategyRegistry.get(version);
        RetrievalOutput retrievalOutput = strategy.retrieve(question, filter == null ? RetrievalFilter.none() : filter);
        AnswerGenerationService.GeneratedAnswer generated = answerGenerationService.generate(
                question,
                version,
                retrievalOutput
        );
        long latencyMs = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000);

        return new ChatResponse(
                UUID.randomUUID().toString(),
                question,
                version,
                generated.answer(),
                generated.refused(),
                generated.refusalReason(),
                round(generated.confidence()),
                latencyMs,
                generated.citations(),
                retrievalOutput.trace(),
                Instant.now()
        );
    }

    public Map<IterationVersion, ChatResponse> compare(String question) {
        return compare(question, RetrievalFilter.none());
    }

    public Map<IterationVersion, ChatResponse> compare(String question, RetrievalFilter filter) {
        Map<IterationVersion, ChatResponse> responses = new EnumMap<>(IterationVersion.class);
        for (IterationVersion version : IterationVersion.values()) {
            responses.put(version, ask(question, version, filter));
        }
        return responses;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

