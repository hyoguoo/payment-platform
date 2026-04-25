package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T-C3 RED — PaymentConfirmResultUseCase two-phase lease + remove 실패 DLQ 전송 검증.
 *
 * <p>검증 목표:
 * <ul>
 *   <li>handle_whenProcessSucceeds_shouldExtendLease: processMessage 성공 시 extendLease 1회 호출</li>
 *   <li>handle_whenProcessFails_shouldRemove: processMessage 실패 시 remove 1회 호출</li>
 *   <li>handle_whenRemoveFails_shouldPublishDlq: remove false → DLQ publisher 1회 호출 + 원본 예외 전파</li>
 *   <li>handle_whenMarkWithLeaseFails_shouldSkipProcess: markWithLease false → processMessage 미호출</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCaseTest — T-C3 two-phase lease + DLQ")
class PaymentConfirmResultUseCaseTwoPhaseLeaseTest {

    private static final String ORDER_ID = "order-tc3-001";
    private static final String EVENT_UUID = "evt-tc3-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private EventDedupeStore eventDedupeStore;
    private ApplicationEventPublisher applicationEventPublisher;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private FailureCompensationService failureCompensationService;
    private PaymentConfirmDlqPublisher dlqPublisher;
    private FakeStockOutboxRepository stockOutboxRepository;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        eventDedupeStore = Mockito.mock(EventDedupeStore.class);
        applicationEventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
        dlqPublisher = Mockito.mock(PaymentConfirmDlqPublisher.class);
        stockOutboxRepository = new FakeStockOutboxRepository();

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                eventDedupeStore,
                applicationEventPublisher,
                quarantineCompensationHandler,
                fixedClock,
                failureCompensationService,
                dlqPublisher,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule())
        );
    }

    // -----------------------------------------------------------------------
    // TC-C3-1: processMessage 성공 → extendLease 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — processMessage 성공 시 extendLease가 1회 호출된다")
    void handle_whenProcessSucceeds_shouldExtendLease() {
        // given
        given(eventDedupeStore.markWithLease(eq(EVENT_UUID), any(Duration.class))).willReturn(true);
        given(eventDedupeStore.extendLease(eq(EVENT_UUID), any(Duration.class))).willReturn(true);

        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, AMOUNT, APPROVED_AT_STR
        );

        // when
        sut.handle(message);

        // then — extendLease 1회 호출
        then(eventDedupeStore).should(times(1)).extendLease(eq(EVENT_UUID), any(Duration.class));
        // then — DLQ 미발행
        then(dlqPublisher).should(never()).publishDlq(any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-C3-2: processMessage 실패 → remove 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — processMessage 실패 시 remove가 1회 호출된다")
    void handle_whenProcessFails_shouldRemove() {
        // given — PaymentEvent 없으면 processMessage 내부에서 예외 발생
        given(eventDedupeStore.markWithLease(eq(EVENT_UUID), any(Duration.class))).willReturn(true);
        given(eventDedupeStore.remove(EVENT_UUID)).willReturn(true); // remove 성공

        // paymentEventRepository에 이벤트 없음 → findByOrderId empty → PaymentFoundException
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, AMOUNT, APPROVED_AT_STR
        );

        // when & then — 예외는 전파되어야 한다
        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class);

        // then — remove 1회 호출
        then(eventDedupeStore).should(times(1)).remove(EVENT_UUID);
        // then — DLQ 미발행 (remove 성공)
        then(dlqPublisher).should(never()).publishDlq(any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-C3-3: remove 실패(false) → DLQ publisher 1회 호출 + 원본 예외 전파
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — remove 실패 시 DLQ publisher가 호출되고 원본 예외가 전파된다")
    void handle_whenRemoveFails_shouldPublishDlq() {
        // given
        given(eventDedupeStore.markWithLease(eq(EVENT_UUID), any(Duration.class))).willReturn(true);
        given(eventDedupeStore.remove(EVENT_UUID)).willReturn(false); // remove 실패

        // paymentEventRepository에 이벤트 없음 → processMessage 내부 예외
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, AMOUNT, APPROVED_AT_STR
        );

        // when & then — 원본 예외 전파
        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class);

        // then — remove 1회 호출
        then(eventDedupeStore).should(times(1)).remove(EVENT_UUID);
        // then — DLQ publisher 1회 호출
        then(dlqPublisher).should(times(1)).publishDlq(eq(EVENT_UUID), any());
    }

    // -----------------------------------------------------------------------
    // TC-C3-4: markWithLease 실패(false) → processMessage 미호출 (다른 consumer 처리 중)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — markWithLease 실패 시 processMessage가 호출되지 않는다")
    void handle_whenMarkWithLeaseFails_shouldSkipProcess() {
        // given — markWithLease false: 다른 consumer가 처리 중
        given(eventDedupeStore.markWithLease(eq(EVENT_UUID), any(Duration.class))).willReturn(false);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, AMOUNT, APPROVED_AT_STR
        );

        // when
        sut.handle(message);

        // then — extendLease 미호출 (processMessage 진입 없음)
        then(eventDedupeStore).should(never()).extendLease(any(), any());
        // then — DLQ 미발행
        then(dlqPublisher).should(never()).publishDlq(any(), any());
        // then — PaymentEvent 상태 변경 없음
        assertThat(paymentEventRepository.findByOrderId(ORDER_ID)).isEmpty();
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildPaymentOrder(Long productId, int quantity, BigDecimal totalAmount) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(ORDER_ID)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
