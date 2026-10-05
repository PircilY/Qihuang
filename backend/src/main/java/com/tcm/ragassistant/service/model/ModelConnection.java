package com.tcm.ragassistant.service.model;

import java.net.URI;

public record ModelConnection(URI baseUrl, String model, String apiKey, boolean enabled) {
    @Override
    public String toString() {
        return "ModelConnection[credentials redacted]";
    }
}
