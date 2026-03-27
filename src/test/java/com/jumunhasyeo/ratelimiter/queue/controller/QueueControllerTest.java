package com.jumunhasyeo.ratelimiter.queue.controller;

import com.jumunhasyeo.ratelimiter.queue.dto.QueuePaymentCallbackRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePaymentCallbackResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.http.QueueHttpHeaders;
import com.jumunhasyeo.ratelimiter.queue.service.QueuePollResult;
import com.jumunhasyeo.ratelimiter.queue.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueController 단위 테스트")
class QueueControllerTest {

    @Mock
    QueueService queueService;

    @Test
    @DisplayName("stale poll 결과는 STALE 헤더와 함께 200으로 반환한다")
    void stale_poll_결과는_STALE_헤더와_함께_200으로_반환한다() {
        QueueController controller = new QueueController(queueService);
        given(queueService.poll("user-1", 7))
                .willReturn(QueuePollResult.stale(QueuePollResponse.waiting(7, 5)));

        ResponseEntity<QueuePollResponse> response = controller.poll("user-1", 7);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(QueueHttpHeaders.QUEUE_STATE)).isEqualTo(QueueHttpHeaders.STALE);
        assertThat(response.getBody().rank()).isEqualTo(7);
    }

    @Test
    @DisplayName("DEFERRED callback 결과는 202로 반환한다")
    void DEFERRED_callback_결과는_202로_반환한다() {
        QueueController controller = new QueueController(queueService);
        QueuePaymentCallbackRequest request = new QueuePaymentCallbackRequest("user-1", "token", "SUCCESS");
        given(queueService.handlePaymentCallback("user-1", "token", "SUCCESS")).willReturn("DEFERRED");

        ResponseEntity<QueuePaymentCallbackResponse> response = controller.paymentCallback(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().result()).isEqualTo("DEFERRED");
    }
}
