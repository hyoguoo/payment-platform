package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PaymentConfirmResultUseCase.handleFailed 단위 검증 (SCR-6 재작성).
 *
 * <p>핵심: 호출 순서 뒤집기 — compensateAtomic (보상 먼저) → markPaymentAsFail (RDB 나중).
 * ALREADY_DONE 이어도 RDB 진행. RuntimeException 은 그대로 전파.
 */
@DisplayName("PaymentConfirmResultUseCase handleFailed")
class PaymentConfirmResultUseCaseHandleFailedTest {

    private static final String ORDER_ID = "order-failed-001";
    private static final String EVENT_UUID = "evt-failed-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private FakeStockOutboxRepository stockOutboxRepository;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        stockOutboxRepository = new FakeStockOutboxRepository();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                new NoopApplicationEventPublisher(),
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("FAILED 수신 — compensateAtomic 이 markPaymentAsFail 보다 먼저 호출된다 (호출 순서 검증)")
    void FAILED_수신_보상_먼저_RDB_나중_호출순서() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        InOrder inOrder = inOrder(stockCachePort, paymentCommandUseCase);
        inOrder.verify(stockCachePort).compensateAtomic(eq(ORDER_ID), any());
        inOrder.verify(paymentCommandUseCase).markPaymentAsFail(any(PaymentEvent.class), eq(REASON_CODE));
    }

    @Test
    @DisplayName("FAILED — 이미 종결 상태이면 compensateAtomic 미호출 (noop)")
    void FAILED_이미_종결_noop() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any());
    }

    @Test
    @DisplayName("FAILED — compensateAtomic 이 ALREADY_DONE 이어도 markPaymentAsFail 는 호출된다")
    void FAILED_보상_ALREADY_DONE_이어도_RDB_진행() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.ALREADY_DONE);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should().compensateAtomic(eq(ORDER_ID), any());
        then(paymentCommandUseCase).should().markPaymentAsFail(any(PaymentEvent.class), eq(REASON_CODE));
    }

    @Test
    @DisplayName("FAILED — compensateAtomic RuntimeException 전파 시 markPaymentAsFail 미호출")
    void FAILED_보상_RuntimeException_전파() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willThrow(new RuntimeException("Redis 연결 실패"));

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis 연결 실패");

        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any());
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-failed")
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

    static class NoopApplicationEventPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }
}
