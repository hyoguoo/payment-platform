package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeMessagePublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

@DisplayName("OutboxRelayService 테스트")
class OutboxRelayServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String ORDER_ID = "order-relay-001";

    private FakeMessagePublisher fakeMessagePublisher;
    private PaymentOutboxRepository mockOutboxRepository;
    private PaymentLoadUseCase mockPaymentLoadUseCase;

    private OutboxRelayService outboxRelayService;

    @BeforeEach
    void setUp() {
        fakeMessagePublisher = new FakeMessagePublisher();
        mockOutboxRepository = Mockito.mock(PaymentOutboxRepository.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);

        outboxRelayService = new OutboxRelayService(
                mockOutboxRepository,
                fakeMessagePublisher,
                mockPaymentLoadUseCase,
                FIXED_CLOCK
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
                .inFlightAt(FIXED_INSTANT)
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
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_INSTANT)).willReturn(true);
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
                .inFlightAt(FIXED_INSTANT)
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
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_INSTANT)).willReturn(true);
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
                .inFlightAt(FIXED_INSTANT)
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
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        // 첫 번째 호출: claimToInFlight 성공
        // 두 번째 호출: claimToInFlight 실패(다른 워커가 이미 처리 중)
        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_INSTANT))
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

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS"})
    @DisplayName("relay: 결제가 확정 결과 적용 가능한 상태면 정상 발행되고 outbox가 DONE으로 전이된다")
    void relay_WhenPaymentCanApplyConfirmResult_Publishes(PaymentEventStatus paymentStatus) {
        // given
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_INSTANT)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("상품A")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(paymentStatus)
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_INSTANT)).willReturn(true);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox);

        // when
        outboxRelayService.relay(ORDER_ID);

        // then
        assertThat(fakeMessagePublisher.count()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentEventStatus.class,
            names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"}
    )
    @DisplayName("relay: 결제가 확정 결과 적용 불가 상태면 발행을 건너뛰고 outbox를 FAILED로 종결한다")
    void relay_WhenPaymentCannotApplyConfirmResult_SkipsPublishAndMarksOutboxFailed(
            PaymentEventStatus paymentStatus) {
        // given — 브로커 장애로 발행이 지연되는 동안 결제가 만료/취소 등으로 이미 종결된 상황.
        // 이 상태에서 발행이 그대로 나가면 벤더가 뒤늦게 승인해 되돌릴 수 없는 과금이 발생한다.
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_INSTANT)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("상품A")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(paymentStatus)
                .paymentOrderList(java.util.List.of())
                .allArgsBuild();

        given(mockOutboxRepository.claimToInFlight(ORDER_ID, FIXED_INSTANT)).willReturn(true);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox);

        // when
        outboxRelayService.relay(ORDER_ID);

        // then — 확정 명령이 나가지 않고, outbox는 재시도 대상에서 완전히 빠진다(IN_FLIGHT로
        // 남으면 타임아웃 복구가 다시 PENDING으로 되돌려 무한 반복된다).
        assertThat(fakeMessagePublisher.count()).isZero();
        assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
        then(mockOutboxRepository).should().save(outbox);
    }
}
