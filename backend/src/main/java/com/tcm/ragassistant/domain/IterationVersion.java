package com.tcm.ragassistant.domain;

public enum IterationVersion {
    V0_DIRECT(
            "V0",
            "无检索对照",
            "固定边界提示，不调用模型或知识库，用于衡量检索增益。",
            "不执行检索",
            "未校准",
            "无引用对照"
    ),
    V1_VECTOR(
            "V1",
            "向量式检索",
            "基于文本特征相似度的轻量检索。",
            "文本相似召回",
            "未校准",
            "原文引用"
    ),
    V2_HYBRID(
            "V2",
            "混合检索",
            "BM25 与向量式召回并行，使用 RRF 融合并重排。",
            "关键词 + 文本相似度",
            "未校准",
            "带来源"
    ),
    V3_GUARDED(
            "V3",
            "证据约束",
            "加入病证方药约束、证据阈值、引用校验和拒答。",
            "混合检索 + 安全约束",
            "未校准",
            "可追溯"
    );

    private final String code;
    private final String displayName;
    private final String description;
    private final String retrievalFeature;
    private final String reliability;
    private final String citationFeature;

    IterationVersion(
            String code,
            String displayName,
            String description,
            String retrievalFeature,
            String reliability,
            String citationFeature
    ) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
        this.retrievalFeature = retrievalFeature;
        this.reliability = reliability;
        this.citationFeature = citationFeature;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String retrievalFeature() {
        return retrievalFeature;
    }

    public String reliability() {
        return reliability;
    }

    public String citationFeature() {
        return citationFeature;
    }
}
