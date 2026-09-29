package com.tcm.ragassistant.service.retrieval;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RetrievalStrategyRegistry {

    private final Map<IterationVersion, RetrievalStrategy> strategies;

    public RetrievalStrategyRegistry(List<RetrievalStrategy> strategies) {
        Map<IterationVersion, RetrievalStrategy> registry = new EnumMap<>(IterationVersion.class);
        strategies.forEach(strategy -> registry.put(strategy.version(), strategy));
        this.strategies = Map.copyOf(registry);
    }

    public RetrievalStrategy get(IterationVersion version) {
        RetrievalStrategy strategy = strategies.get(version);
        if (strategy == null) {
            throw new BusinessException("RETRIEVAL_STRATEGY_NOT_FOUND", "未找到指定检索策略");
        }
        return strategy;
    }
}

