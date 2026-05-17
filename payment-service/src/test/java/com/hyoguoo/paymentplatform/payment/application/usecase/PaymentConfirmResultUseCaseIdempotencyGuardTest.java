package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase D7 진입 가드 검증 (PET-8 갱신).
 *
 * <p>D7 가드: handle() 진입 시 paymentEvent.getStatus().isCompensatableByFailureHandler() 로 단일화.
 * isCompensatableByFailureHandler=false (종결 상태: DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)
 * → markIfAbsent 미호출 + warn noop.
 *
 * <p>기존 handleFailed / handleQuarantined 안의 isTerminal 가드와 통합됨.
 * race 시나리오: 다른 eventUuid 로 같은 orderId 결과가 두 번 도착하면
 * 진입 가드가 D7 기준으로 걸러낸다.
 */
@DisplayName("PaymentConfirmResultUseCase D7 진입 가드 — 종결 상태면 전체 skip")
class PaymentConfirmResultUseCaseIdempotencyGuardTest {

    private static final String ORDER_ID = "order-guard-001";
    private static final String EVENT_UUID = "evt-guard-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private FakePaymentEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakePaymentEventDedupeStore();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        LocalDateTimeProvider fixedClock = new LocalDateTimeProvider() {
            @Override
            public LocalDateTime now() {
                return LocalDateTime.of(2026, 4, 27, 12, 0, 0);
            }

            @Override
            public Instant nowInstant() {
                return Instant.parse("2026-04-27T12:00:00Z");
            }
        };

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("handleFailed — paymentEvent 가 이미 FAILED(terminal, isCompensatableByFailureHandler=false)이면 compensateAtomic + markPaymentAsFail 미호출")
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
    @DisplayName("handleFailed — paymentEvent 가 IN_PROGRESS(non-terminal, isCompensatableByFailureHandler=true)이면 보상 정상 실행")
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
}
