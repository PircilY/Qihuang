package com.tcm.ragassistant.dto;

import java.time.Instant;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        Instant timestamp
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, Instant.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}

