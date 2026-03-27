package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.ApiErrorResponse;
import com.jumunhasyeo.ratelimiter.queue.http.QueueHttpHeaders;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueBypassSignalException;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
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
    /**
     * 컨트롤러에 도달하기 전에 요청을 한 번 걸러서,
     * 바로 통과시킬지, 제한을 볼지, 잠시 우회시킬지를 정하는 입구 필터다.
     */

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
            // enter는 "새 요청을 받아도 되는지"를 더 엄격하게 보고,
            // poll은 "현재 상태를 확인하는 요청"이라 조금 더 유연하게 본다.
            decision = switch (policy) {
                case GUARDED_ENTER -> admissionGuard.tryEnter();
                case GUARDED_POLL -> admissionGuard.tryEnterWithoutQueueLimit();
                default -> throw new IllegalStateException("Unsupported admission policy: " + policy);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeTooManyRequests(response, "interrupted");
            return;
        } catch (QueueBypassSignalException e) {
            writeQueueBypass(response);
            return;
        } catch (QueueTemporarilyUnavailableException e) {
            writeQueueTemporarilyUnavailable(response);
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

    private void writeQueueTemporarilyUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", String.valueOf(properties.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                ApiErrorResponse.queueTemporarilyUnavailable(properties.retryAfterSeconds())
        ));
    }

    private void writeQueueBypass(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(QueueHttpHeaders.QUEUE_MODE, QueueHttpHeaders.BYPASS);
        response.setHeader(QueueHttpHeaders.QUEUE_STATE, QueueHttpHeaders.BYPASS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(ApiErrorResponse.queueBypass()));
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
        // 새로 줄을 서는 요청은 대기열 길이와 동시 처리량을 모두 본다.
        if ("POST".equalsIgnoreCase(method) && "/api/queue/enter".equals(uri)) {
            return AdmissionPathPolicy.GUARDED_ENTER;
        }
        // poll은 마지막 순번으로 응답을 복원할 수 있어,
        // enter보다 덜 빡세게 보고 실제 상태 판단은 뒤쪽 서비스로 넘긴다.
        if ("GET".equalsIgnoreCase(method) && "/api/queue/poll".equals(uri)) {
            return AdmissionPathPolicy.GUARDED_POLL;
        }
        return AdmissionPathPolicy.GUARDED_ENTER;
    }

    private enum AdmissionPathPolicy {
        /** 대기열과 상관없는 요청이라 필터가 아무것도 하지 않는다. */
        PASSTHROUGH,
        /** 대기열 요청이지만 후처리 성격이라 검사 없이 바로 통과시킨다. */
        BYPASS,
        /** 새로 줄을 서는 요청이라 더 엄격하게 본다. */
        GUARDED_ENTER,
        /** 현재 상태를 묻는 요청이라 더 유연하게 본다. */
        GUARDED_POLL
    }
}
