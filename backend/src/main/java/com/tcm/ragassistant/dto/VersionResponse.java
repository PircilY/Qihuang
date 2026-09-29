package com.tcm.ragassistant.dto;

import com.tcm.ragassistant.domain.IterationVersion;

public record VersionResponse(
        IterationVersion version,
        String code,
        String name,
        String description,
        String retrievalFeature,
        String reliability,
        String citationFeature
) {
    public static VersionResponse from(IterationVersion version) {
        return new VersionResponse(
                version,
                version.code(),
                version.displayName(),
                version.description(),
                version.retrievalFeature(),
                version.reliability(),
                version.citationFeature()
        );
    }
}

