package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueBypassSignalException;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdmissionFilter 단위 테스트")
class AdmissionFilterTest {

    @Mock
    AdmissionGuard admissionGuard;

    @Mock
    AdmissionProperties properties;

    @Test
    @DisplayName("입장 제한 시 429와 ApiErrorResponse 형식으로 응답한다")
    void returns429WithApiErrorResponse() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/queue/enter");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(properties.enabled()).willReturn(true);
        given(properties.retryAfterSeconds()).willReturn(2);
        given(admissionGuard.tryEnter()).willReturn(AdmissionGuard.Decision.rejected("queue_full"));

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getContentAsString()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
        assertThat(response.getContentAsString()).contains("\"reason\":\"queue_full\"");
        assertThat(response.getContentAsString()).contains("\"retryAfterSeconds\":2");
    }

    @Test
    @DisplayName("enter 경로에서 Redis 일시 장애가 나면 503 temporary unavailable을 반환한다")
    void returns503WhenEnterSeesTemporaryRedisFailure() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/queue/enter");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(properties.enabled()).willReturn(true);
        given(properties.retryAfterSeconds()).willReturn(1);
        given(admissionGuard.tryEnter()).willThrow(new QueueTemporarilyUnavailableException(new RuntimeException("timeout")));

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("\"code\":\"QUEUE_TEMPORARILY_UNAVAILABLE\"");
    }

    @Test
    @DisplayName("enter 경로에서 circuit breaker가 열리면 503 bypass 신호를 반환한다")
    void returns503BypassSignalWhenBreakerIsOpen() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/queue/enter");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(properties.enabled()).willReturn(true);
        given(admissionGuard.tryEnter()).willThrow(new QueueBypassSignalException(new RuntimeException("open")));

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Queue-Mode")).isEqualTo("BYPASS");
        assertThat(response.getHeader("X-Queue-State")).isEqualTo("BYPASS");
        assertThat(response.getContentAsString()).contains("\"code\":\"QUEUE_BYPASS\"");
    }

    @Test
    @DisplayName("queue API가 아니면 admission 검사 없이 통과한다")
    void skipsAdmissionForNonQueueApi() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        verifyNoInteractions(admissionGuard);
    }

    @Test
    @DisplayName("payment callback은 admission 검사 없이 통과한다")
    void bypassesAdmissionForPaymentCallback() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/queue/payment/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(properties.enabled()).willReturn(true);

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        verifyNoInteractions(admissionGuard);
    }

    @Test
    @DisplayName("poll은 queue size 검사 없이 세마포어 검사만 수행한다")
    void poll은_queue_size_검사_없이_세마포어_검사만_수행한다() throws Exception {
        AdmissionFilter filter = new AdmissionFilter(admissionGuard, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/queue/poll");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(properties.enabled()).willReturn(true);
        given(admissionGuard.tryEnterWithoutQueueLimit()).willReturn(AdmissionGuard.Decision.allowed(true, null));

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
    }
}
