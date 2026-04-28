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
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentConfirmDlqPublisher;
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
 * PaymentConfirmResultUseCase handleFailed / handleQuarantined 이중 진입 가드 검증.
 *
 * <p>race 시나리오: IN_PROGRESS self-loop retry 활성화 후 다른 eventUuid 로 같은 orderId 결과가
 * 두 번 도착하면, markWithLease 는 같은 eventUuid 만 보호하므로 handleFailed/handleQuarantined 에
 * 두 번 진입 가능 → 보상 중복 → redis-stock 발산.
 *
 * <p>이 테스트는 paymentEvent.getStatus().isTerminal() 가드가 없으면 RED 로 떨어진다.
 */
@DisplayName("PaymentConfirmResultUseCase 이중 진입 가드 — 이미 종결 상태면 보상 skip")
class PaymentConfirmResultUseCaseIdempotencyGuardTest {

    private static final String ORDER_ID = "order-guard-001";
    private static final String EVENT_UUID = "evt-guard-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 27, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                dedupeStore,
                new NoopApplicationEventPublisher(),
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                new FakePaymentConfirmDlqPublisher(),
                new FakeStockOutboxRepository(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                PaymentConfirmResultUseCase.DEFAULT_LEASE_TTL,
                PaymentConfirmResultUseCase.DEFAULT_LONG_TTL,
                paymentCommandUseCase
        );
    }

    // -----------------------------------------------------------------------
    // Test 1: handleFailed — 이미 FAILED(terminal) 상태면 보상 skip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — paymentEvent 가 이미 FAILED(terminal)이면 compensateStockCache + markPaymentAsFail 미호출")
    void handleFailed_whenAlreadyTerminal_shouldSkipCompensation() {
        // given: paymentEvent 가 이미 FAILED(종결) 상태
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        // dedupeStore 에는 아직 기록이 없어야 markWithLease 통과
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        // when
        sut.handle(message);

        // then: 종결 상태라 보상 메서드 호출 0회
        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any());
    }

    // -----------------------------------------------------------------------
    // Test 2: handleQuarantined — 이미 QUARANTINED(non-terminal) vs FAILED(terminal) 주의
    //   QUARANTINED 는 isTerminal()=false → 보상 실행
    //   FAILED 는 isTerminal()=true → 보상 skip (handleQuarantined 경유)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 이미 FAILED(terminal)이면 compensateStockCache + quarantineHandler 미호출")
    void handleQuarantined_whenAlreadyTerminal_shouldSkipAll() {
        // given: paymentEvent 가 이미 FAILED(terminal) 상태인데 QUARANTINED 메시지 수신
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        // when
        sut.handle(message);

        // then: 종결 상태라 보상 없음
        then(stockCachePort).shouldHaveNoInteractions();
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    // -----------------------------------------------------------------------
    // Test 3: handleFailed — 정상 IN_PROGRESS 상태에선 그대로 보상 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — paymentEvent 가 IN_PROGRESS(non-terminal)이면 보상 정상 실행")
    void handleFailed_whenInProgress_shouldCompensateNormally() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        // when
        sut.handle(message);

        // then: 정상 보상 경로
        then(paymentCommandUseCase).should(times(1)).markPaymentAsFail(any(PaymentEvent.class), any(String.class));
        then(stockCachePort).should(times(1)).increment(100L, 3);
    }

    // -----------------------------------------------------------------------
    // Test 4: handleQuarantined — 정상 IN_PROGRESS 상태에선 보상 + quarantineHandler 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 IN_PROGRESS(non-terminal)이면 보상 + quarantineHandler 정상 실행")
    void handleQuarantined_whenInProgress_shouldCompensateAndQuarantine() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        // when
        sut.handle(message);

        // then: 보상 + quarantine handler 모두 호출
        then(stockCachePort).should(times(1)).increment(100L, 3);
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
