package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmTerminalResendMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase.handleQuarantined 단위 검증.
 *
 * <p>보상 → quarantineHandler 순서로 처리한다 (보상은 compensateAtomic 직접 호출).
 *
 * <p>진입 가드: QUARANTINED / FAILED 등 종결 상태는 canApplyConfirmResult=false 라 걸러진다.
 */
@DisplayName("PaymentConfirmResultUseCase handleQuarantined")
class PaymentConfirmResultUseCaseHandleQuarantinedTest {

    private static final String ORDER_ID = "order-quarantined-001";
    private static final String EVENT_UUID = "evt-quarantined-001";
    private static final String REASON_CODE = "RETRY_EXHAUSTED";

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

        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-04-24T12:00:00Z"), ZoneOffset.UTC);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase,
                new PaymentConfirmGuardSkipMetrics(new SimpleMeterRegistry()),
                new PaymentConfirmTerminalResendMetrics(new SimpleMeterRegistry())
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
    @DisplayName("QUARANTINED — 이미 종결 상태(FAILED)이면 진입 가드에서 noop (compensateAtomic 및 quarantineHandler 미호출)")
    void QUARANTINED_이미_종결_noop() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        // FAILED 는 canApplyConfirmResult=false → 진입 가드에서 걸린다
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

    @Test
    @DisplayName("QUARANTINED — 격리 사유가 부분 취소(FCG_PARTIAL_CANCELED)면 즉시 재고 보상을 호출하지 않는다")
    void 격리_부분취소사유면_즉시_재고보상을_호출하지_않는다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "FCG_PARTIAL_CANCELED", null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("QUARANTINED — 격리 사유가 부분 취소여도 격리 전이(quarantineHandler)는 그대로 수행한다")
    void 격리_부분취소사유여도_격리전이는_그대로_수행한다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "FCG_PARTIAL_CANCELED", null, null, EVENT_UUID);

        sut.handle(message);

        then(quarantineCompensationHandler).should().handle(eq(ORDER_ID), eq("FCG_PARTIAL_CANCELED"));
    }

    @Test
    @DisplayName("QUARANTINED — 격리 사유가 조회 실패(FCG_INDETERMINATE)면 기존대로 즉시 재고 보상을 호출한다")
    void 격리_조회실패사유면_기존대로_즉시_재고보상을_호출한다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "FCG_INDETERMINATE", null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should().compensateAtomic(eq(ORDER_ID), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FCG_VENDOR_UNSETTLED", "AMOUNT_MISMATCH"})
    @DisplayName("QUARANTINED — 벤더 미결론·금액 불일치 사유도 즉시 재고 보상을 호출한다")
    void 격리_벤더미결론_금액불일치_사유도_즉시_보상한다(String reasonCode) {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", reasonCode, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should().compensateAtomic(eq(ORDER_ID), any());
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
