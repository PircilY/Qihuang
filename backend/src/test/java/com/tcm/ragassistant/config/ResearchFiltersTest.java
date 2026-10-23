package com.tcm.ragassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchFiltersTest {
    @Test
    void importAndEvaluationRateLimitsReturn429() throws Exception {
        for (String path : new String[]{"/api/v1/knowledge/import", "/api/v1/evaluations/run"}) {
            var filter = new ResearchRequestLimitFilter(1, 1);
            for (int i = 0; i < 2; i++) {
                var request = new MockHttpServletRequest("POST", path);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> { });
                assertThat(response.getStatus()).isEqualTo(i == 0 ? 200 : 429);
                if (i == 1) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            }
        }
    }
    @Test
    void forwardedHeadersAllowExternalSameOriginAndRejectOtherOrigins() throws Exception {
        for (String origin : new String[]{"https://research.example", "https://other.example"}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/rag/chat");
            request.setServerName("internal");
            request.setServerPort(8080);
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", "research.example");
            request.addHeader("X-Forwarded-Port", "443");
            request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            new ForwardedHeaderFilter().doFilter(request, response, (req, res) ->
                    new RagOriginFilter().doFilter(req, res, (r, s) -> { }));
            assertThat(response.getStatus()).isEqualTo(origin.contains("other") ? 403 : 200);
        }
    }
}
