package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
import org.mockito.Mockito;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PaymentConfirmResultUseCase handleFailed / handleQuarantined 이중 진입 가드 검증 (SCR-6 재작성).
 *
 * <p>race 시나리오: 다른 eventUuid 로 같은 orderId 결과가 두 번 도착하면
 * handleFailed/handleQuarantined 에 두 번 진입 가능 → 보상 중복 → redis-stock 발산.
 *
 * <p>이 테스트는 paymentEvent.getStatus().isTerminal() 가드를 검증한다.
 * SCR-6 이후 dedupe lease 의존은 제거됐으나 isTerminal 가드는 여전히 유효하다.
 */
@DisplayName("PaymentConfirmResultUseCase 이중 진입 가드 — 이미 종결 상태면 보상 skip")
class PaymentConfirmResultUseCaseIdempotencyGuardTest {

    private static final String ORDER_ID = "order-guard-001";
    private static final String EVENT_UUID = "evt-guard-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 27, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                new NoopApplicationEventPublisher(),
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                new FakeStockOutboxRepository(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("handleFailed — paymentEvent 가 이미 FAILED(terminal)이면 compensateAtomic + markPaymentAsFail 미호출")
    void handleFailed_whenAlreadyTerminal_shouldSkipCompensation() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any());
    }

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 이미 FAILED(terminal)이면 compensateAtomic + quarantineHandler 미호출")
    void handleQuarantined_whenAlreadyTerminal_shouldSkipAll() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("handleFailed — paymentEvent 가 IN_PROGRESS(non-terminal)이면 보상 정상 실행")
    void handleFailed_whenInProgress_shouldCompensateNormally() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(any(), any()))
                .willReturn(StockCompensationAtomicResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should(times(1)).compensateAtomic(any(), any());
        then(paymentCommandUseCase).should(times(1)).markPaymentAsFail(any(PaymentEvent.class), any(String.class));
    }

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 IN_PROGRESS(non-terminal)이면 보상 + quarantineHandler 정상 실행")
    void handleQuarantined_whenInProgress_shouldCompensateAndQuarantine() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(any(), any()))
                .willReturn(StockCompensationAtomicResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should(times(1)).compensateAtomic(any(), any());
        then(quarantineCompensationHandler).should(times(1)).handle(ORDER_ID, REASON_CODE);
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-guard")
                .status(status)
                .retryCount(0)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildPaymentOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(ORDER_ID)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(300L))
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
