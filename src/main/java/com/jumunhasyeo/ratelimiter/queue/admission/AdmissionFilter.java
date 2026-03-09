package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class AdmissionFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AdmissionGuard admissionGuard;
    private final AdmissionProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        AdmissionPathPolicy policy = resolvePolicy(request);
        if (policy == AdmissionPathPolicy.PASSTHROUGH || !properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (policy == AdmissionPathPolicy.BYPASS) {
            filterChain.doFilter(request, response);
            return;
        }

        AdmissionGuard.Decision decision;
        try {
            decision = admissionGuard.tryEnter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeTooManyRequests(response, "interrupted");
            return;
        }

        if (!decision.allowed()) {
            writeTooManyRequests(response, decision.reason());
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            admissionGuard.leave(decision.permitAcquired());
        }
    }

    private void writeTooManyRequests(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(properties.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String normalizedReason = reason == null ? "unknown" : reason;
        ApiErrorResponse body = ApiErrorResponse.tooManyRequests(
                "Request rejected by admission control",
                normalizedReason,
                properties.retryAfterSeconds()
        );
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
        log.debug("Admission rejected request. reason={}", reason);
    }

    private AdmissionPathPolicy resolvePolicy(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/queue")) {
            return AdmissionPathPolicy.PASSTHROUGH;
        }

        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) && "/api/queue/payment/callback".equals(uri)) {
            return AdmissionPathPolicy.BYPASS;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/queue/enter".equals(uri)) {
            return AdmissionPathPolicy.GUARDED;
        }
        if ("GET".equalsIgnoreCase(method) && "/api/queue/poll".equals(uri)) {
            return AdmissionPathPolicy.GUARDED;
        }
        return AdmissionPathPolicy.GUARDED;
    }

    private enum AdmissionPathPolicy {
        PASSTHROUGH,
        BYPASS,
        GUARDED
    }
}
