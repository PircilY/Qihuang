package com.tcm.ragassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ResearchRequestLimitFilter extends OncePerRequestFilter {
    private final int importLimit;
    private final int evaluationLimit;
    private final Map<String, Window> windows = new LinkedHashMap<>();
    public ResearchRequestLimitFilter(
            @Value("${app.limits.import-requests-per-minute:30}") int importLimit,
            @Value("${app.limits.evaluation-requests-per-minute:2}") int evaluationLimit) {
        if (importLimit < 1 || evaluationLimit < 1) throw new IllegalArgumentException("Rate limits must be positive");
        this.importLimit = importLimit;
        this.evaluationLimit = evaluationLimit;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI();
        int limit = path.equals("/api/v1/knowledge/import") ? importLimit
                : (path.startsWith("/api/v1/evaluations/") || path.startsWith("/api/v1/experiments/")) ? evaluationLimit : 0;
        if (limit > 0 && "POST".equals(request.getMethod()) && !accept(request.getRemoteAddr() + path, limit)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"RESEARCH_RATE_LIMITED\",\"message\":\"请求频率超过配置上限\"}");
            return;
        }
        chain.doFilter(request, response);
    }
    private synchronized boolean accept(String key, int limit) {
        long minute = System.nanoTime() / 60_000_000_000L;
        windows.entrySet().removeIf(entry -> entry.getValue().minute != minute);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 4096) return false;
            window = new Window(minute);
            windows.put(key, window);
        }
        return ++window.count <= limit;
    }
    private static class Window {
        final long minute;
        int count;
        Window(long minute) { this.minute = minute; }
    }
}
