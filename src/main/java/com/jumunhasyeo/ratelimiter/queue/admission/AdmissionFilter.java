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

        if (!isQueueApi(request.getRequestURI()) || !properties.enabled()) {
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

    private boolean isQueueApi(String uri) {
        return uri != null && uri.startsWith("/api/queue");
    }
}
