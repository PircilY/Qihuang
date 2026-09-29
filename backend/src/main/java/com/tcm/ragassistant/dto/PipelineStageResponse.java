package com.tcm.ragassistant.dto;

import java.time.Instant;

public record PipelineStageResponse(
        String key,
        String name,
        String description,
        String status,
        int itemCount,
        Instant updatedAt
) {
}
