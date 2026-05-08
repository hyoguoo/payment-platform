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
 * PaymentConfirmResultUseCase.handleQuarantined 단위 검증 (SCR-6 신규).
 *
 * <p>핵심: 순서 뒤집기가 아닌 "메서드 교체" — 기존 보상 → quarantineHandler 순서를 유지하면서
 * compensateStockCache(for-loop) → compensateAtomic 직접 호출로 교체.
 */
@DisplayName("PaymentConfirmResultUseCase handleQuarantined")
class PaymentConfirmResultUseCaseHandleQuarantinedTest {

    private static final String ORDER_ID = "order-quarantined-001";
    private static final String EVENT_UUID = "evt-quarantined-001";
    private static final String REASON_CODE = "RETRY_EXHAUSTED";

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
    @DisplayName("QUARANTINED — compensateAtomic 이 quarantineHandler 보다 먼저 호출된다 (InOrder 검증)")
    void QUARANTINED_보상_먼저_quarantineHandler_나중() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        InOrder inOrder = inOrder(stockCachePort, quarantineCompensationHandler);
        inOrder.verify(stockCachePort).compensateAtomic(eq(ORDER_ID), any());
        inOrder.verify(quarantineCompensationHandler).handle(eq(ORDER_ID), eq(REASON_CODE));
    }

    @Test
    @DisplayName("QUARANTINED — 이미 종결 상태이면 compensateAtomic 및 quarantineHandler 미호출 (noop)")
    void QUARANTINED_이미_종결_noop() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("QUARANTINED — compensateAtomic RuntimeException 전파 시 quarantineHandler 미호출")
    void QUARANTINED_보상_RuntimeException_전파() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willThrow(new RuntimeException("Redis 연결 실패"));

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis 연결 실패");

        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-quarantined")
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
