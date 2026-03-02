package com.jumunhasyeo.ratelimiter.queue.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class VirtualThreadRequestFilter extends OncePerRequestFilter {

    private final QueueMetrics queueMetrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        boolean virtualThread = Thread.currentThread().isVirtual();

        queueMetrics.incrementVtInflight();
        try {
            filterChain.doFilter(request, response);
        } finally {
            queueMetrics.decrementVtInflight();
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            String endpoint = normalizeEndpoint(request.getRequestURI());
            queueMetrics.recordVtRequest(endpoint, response.getStatus(), virtualThread, duration);
        }
    }

    private String normalizeEndpoint(String uri) {
        if (uri == null || uri.isBlank()) {
            return "unknown";
        }
        if (uri.startsWith("/api/queue")) {
            return uri;
        }
        if (uri.startsWith("/actuator")) {
            return "/actuator";
        }
        if (uri.startsWith("/internal/diag")) {
            return "/internal/diag";
        }
        return "other";
    }
}
