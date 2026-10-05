package com.tcm.ragassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

@Component
public class RagOriginFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/rag/")) {
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            boolean denied = "cross-site".equals(request.getHeader("Sec-Fetch-Site"));
            if (origin != null) {
                try {
                    URI uri = URI.create(origin);
                    int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                    denied |= !request.getScheme().equals(uri.getScheme())
                            || !request.getServerName().equalsIgnoreCase(uri.getHost())
                            || request.getServerPort() != port;
                } catch (IllegalArgumentException exception) {
                    denied = true;
                }
            }
            if (denied) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"ORIGIN_REJECTED\",\"message\":\"Cross-origin request rejected\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
