package com.tcm.ragassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelConfigRequest(
        @NotBlank @Size(max = 500) String baseUrl,
        @NotBlank @Size(max = 160) String model,
        @Size(max = 4096) String apiKey,
        boolean enabled
) {
    @Override
    public String toString() {
        return "ModelConfigRequest[credentials redacted]";
    }
}
