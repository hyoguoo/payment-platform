package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmTerminalResendMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase 진입 가드 검증.
 *
 * <p>handle() 진입 시 paymentEvent.getStatus().canApplyConfirmResult() 로 판정한다.
 * canApplyConfirmResult=false (종결 상태: DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)
 * → markIfAbsent 미호출 + warn noop.
 *
 * <p>race 시나리오: 다른 eventUuid 로 같은 orderId 결과가 두 번 도착하면 진입 가드가 걸러낸다.
 */
@DisplayName("PaymentConfirmResultUseCase 진입 가드 — 종결 상태면 전체 skip")
class PaymentConfirmResultUseCaseIdempotencyGuardTest {

    private static final String ORDER_ID = "order-guard-001";
    private static final String EVENT_UUID = "evt-guard-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private FakePaymentEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private StockHoldRecordRepository stockHoldRecordRepository;
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
        stockHoldRecordRepository = Mockito.mock(StockHoldRecordRepository.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                stockHoldRecordRepository,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase,
                new PaymentConfirmGuardSkipMetrics(new SimpleMeterRegistry()),
                new PaymentConfirmTerminalResendMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    @DisplayName("handleFailed — paymentEvent 가 이미 FAILED(terminal, canApplyConfirmResult=false)이면 재고 되돌리기 + markPaymentAsFail 미호출")
    void handleFailed_whenAlreadyTerminal_shouldSkipCompensation() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any(), any());
    }

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 이미 FAILED(terminal)이면 재고 되돌리기 + quarantineHandler 미호출")
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
    @DisplayName("handleFailed — paymentEvent 가 IN_PROGRESS(non-terminal, canApplyConfirmResult=true)이면 보상 정상 실행")
    void handleFailed_whenInProgress_shouldCompensateNormally() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(any(String.class), any(PaymentOrder.class)))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-guard-test")));
        given(stockCachePort.compensateIfDecremented(any(String.class), any(PaymentOrder.class)))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should(times(1)).compensateIfDecremented(any(String.class), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should(times(1))
                .closeAsReverted(any(String.class), any(PaymentOrder.class), any(String.class));
        then(paymentCommandUseCase).should(times(1))
                .markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class));
    }

    @Test
    @DisplayName("handleQuarantined — paymentEvent 가 IN_PROGRESS(non-terminal)이면 보상 + quarantineHandler 정상 실행")
    void handleQuarantined_whenInProgress_shouldCompensateAndQuarantine() {
        PaymentOrder order = buildPaymentOrder(100L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(any(String.class), any(PaymentOrder.class)))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-guard-test")));
        given(stockCachePort.compensateIfDecremented(any(String.class), any(PaymentOrder.class)))
                .willReturn(StockRecoveryCompensationResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should(times(1)).compensateIfDecremented(any(String.class), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should(times(1))
                .closeAsReverted(any(String.class), any(PaymentOrder.class), any(String.class));
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
