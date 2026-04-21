package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeMessagePublisher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OutboxRelayService 테스트")
class OutboxRelayServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 4, 21, 12, 0, 0);
    private static final String ORDER_ID = "order-relay-001";

    private FakeMessagePublisher fakeMessagePublisher;
    private PaymentOutboxRepository mockOutboxRepository;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private LocalDateTimeProvider mockLocalDateTimeProvider;

    private OutboxRelayService outboxRelayService;

    @BeforeEach
    void setUp() {
        fakeMessagePublisher = new FakeMessagePublisher();
        mockOutboxRepository = Mockito.mock(PaymentOutboxRepository.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);

        given(mockLocalDateTimeProvider.now()).willReturn(FIXED_NOW);

        outboxRelayService = new OutboxRelayService(
                mockOutboxRepository,
                fakeMessagePublisher,
                mockPaymentLoadUseCase,
                mockLocalDateTimeProvider
        );
    }

    @Test
    @DisplayName("relay: claimToInFlight 성공 시 publish 1회 호출 후 outbox가 DONE 상태로 전이된다")
    void relay_PublishesAllPendingOutbox_ThenMarksDone() {
        // given
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("상품A")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_NOW)).willReturn(true);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox);

        // when
        outboxRelayService.relay(ORDER_ID);

        // then
        assertThat(fakeMessagePublisher.count()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
    }

    @Test
    @DisplayName("relay: publish 실패 시 outbox가 DONE으로 전이되지 않는다(재시도 대상으로 남음)")
    void relay_WhenPublishFails_DoesNotMarkDone_LeavesForRetry() {
        // given
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("상품A")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_NOW)).willReturn(true);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        fakeMessagePublisher.failNext();

        // when / then
        assertThatThrownBy(() -> outboxRelayService.relay(ORDER_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(outbox.getStatus()).isNotEqualTo(PaymentOutboxStatus.DONE);
    }

    @Test
    @DisplayName("relay: 동일 orderId를 2회 호출 시 publish는 1회만 수행된다(멱등성)")
    void relay_IsIdempotent_WhenCalledTwice() {
        // given
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("상품A")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        // 첫 번째 호출: claimToInFlight 성공
        // 두 번째 호출: claimToInFlight 실패(다른 워커가 이미 처리 중)
        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_NOW))
                .willReturn(true)
                .willReturn(false);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox);

        // when
        outboxRelayService.relay(ORDER_ID); // 첫 번째 — publish 발생
        outboxRelayService.relay(ORDER_ID); // 두 번째 — claim 실패로 skip

        // then: publish는 정확히 1회
        assertThat(fakeMessagePublisher.count()).isEqualTo(1);
    }
}
