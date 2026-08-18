package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmTerminalResendMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockHoldRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase.handleQuarantined 단위 검증.
 *
 * <p>부분 취소가 아닌 격리 사유는 상품별로 선차감 흔적이 있을 때만 캐시를 되돌리고, 그 결과와
 * 무관하게 기록을 되돌림으로 닫은 뒤 quarantineHandler 에 위임한다. 부분 취소 사유는 되돌리기
 * 자체를 건너뛴다 — 기록은 잡음으로, 재고는 그대로 남는다.
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

        sut = buildUseCase(stockCachePort, stockHoldRecordRepository, paymentCommandUseCase,
                paymentEventRepository, dedupeStore, quarantineCompensationHandler, stockCommittedKafkaTemplate);
    }

    @Test
    @DisplayName("QUARANTINED — 상품별 되돌리기가 quarantineHandler 보다 먼저 호출된다 (InOrder 검증)")
    void QUARANTINED_보상_먼저_quarantineHandler_나중() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-order")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        InOrder inOrder = inOrder(stockCachePort, quarantineCompensationHandler);
        inOrder.verify(stockCachePort).compensateIfDecremented(ORDER_ID, order);
        inOrder.verify(quarantineCompensationHandler).handle(eq(ORDER_ID), eq(REASON_CODE));
    }

    @Test
    @DisplayName("QUARANTINED — 이미 종결 상태(FAILED)이면 진입 가드에서 noop (되돌리기 흐름 미진입)")
    void QUARANTINED_이미_종결_noop() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        // FAILED 는 canApplyConfirmResult=false → 진입 가드에서 걸린다
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockHoldRecordRepository).shouldHaveNoInteractions();
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("QUARANTINED — 되돌리기 호출이 RuntimeException 을 던지면 그대로 전파되고 quarantineHandler 미호출")
    void QUARANTINED_보상_RuntimeException_전파() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-order")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willThrow(new RuntimeException("Redis 연결 실패"));

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis 연결 실패");

        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("QUARANTINED — 격리 사유가 부분 취소(FCG_PARTIAL_CANCELED)면 되돌리기 자체를 건너뛴다")
    void 격리_부분취소사유면_즉시_재고보상을_호출하지_않는다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "FCG_PARTIAL_CANCELED", null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).shouldHaveNoInteractions();
        then(stockHoldRecordRepository).shouldHaveNoInteractions();
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
    @DisplayName("QUARANTINED — 격리 사유가 조회 실패(FCG_INDETERMINATE)면 기존대로 상품별 되돌리기를 수행한다")
    void 격리_조회실패사유면_기존대로_즉시_재고보상을_호출한다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-order")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "FCG_INDETERMINATE", null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should().compensateIfDecremented(ORDER_ID, order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FCG_VENDOR_UNSETTLED", "AMOUNT_MISMATCH"})
    @DisplayName("QUARANTINED — 벤더 미결론·금액 불일치 사유도 상품별 되돌리기를 수행한다")
    void 격리_벤더미결론_금액불일치_사유도_즉시_보상한다(String reasonCode) {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-order")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", reasonCode, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should().compensateIfDecremented(ORDER_ID, order);
    }

    @Nested
    @DisplayName("상품별 되돌리기 — 캐시 재고 값 복원까지 단정 (FakeStockCachePort/FakeStockHoldRecordRepository 사용)")
    class RevertPerProductTest {

        private FakePaymentEventRepository fakePaymentEventRepository;
        private FakePaymentEventDedupeStore fakeDedupeStore;
        private FakeStockCachePort fakeStockCachePort;
        private FakeStockHoldRecordRepository fakeStockHoldRecordRepository;
        private QuarantineCompensationHandler fakeQuarantineCompensationHandler;
        private PaymentConfirmResultUseCase sutWithFake;

        @BeforeEach
        void setUp() {
            fakePaymentEventRepository = new FakePaymentEventRepository();
            fakeDedupeStore = new FakePaymentEventDedupeStore();
            fakeStockCachePort = new FakeStockCachePort();
            fakeStockHoldRecordRepository = new FakeStockHoldRecordRepository();
            fakeQuarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);

            sutWithFake = buildUseCase(fakeStockCachePort, fakeStockHoldRecordRepository,
                    Mockito.mock(PaymentCommandUseCase.class),
                    fakePaymentEventRepository, fakeDedupeStore, fakeQuarantineCompensationHandler,
                    Mockito.mock(KafkaTemplate.class));
        }

        @Test
        @DisplayName("선차감 흔적이 있는 상품은 되돌아가고 기록이 되돌림으로 닫힌다 — 재고 값이 원래대로 복원된다")
        void 선차감_흔적_있으면_재고_복원되고_기록_닫힘() {
            PaymentOrder order = buildPaymentOrder(1L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);

            // 체크아웃 시점의 선차감을 흉내: 기록을 열고 캐시를 차감한다
            fakeStockCachePort.set(1L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(7);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

            sutWithFake.handle(message);

            assertThat(fakeStockCachePort.current(1L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 1L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("부분 취소 사유처럼 즉시 되돌리지 않는 격리에서는 기록이 잡음으로 남고 재고도 그대로다")
        void 부분취소_사유는_기록_잡음_유지_재고_그대로() {
            PaymentOrder order = buildPaymentOrder(2L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);

            fakeStockCachePort.set(2L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(2L)).isEqualTo(7);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "QUARANTINED", "FCG_PARTIAL_CANCELED", null, null, EVENT_UUID);

            sutWithFake.handle(message);

            assertThat(fakeStockCachePort.current(2L)).isEqualTo(7);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 2L))
                    .contains(StockHoldRecordStatus.NOISE);
        }

        @Test
        @DisplayName("캐시가 이미 처리됨을 돌려줘도 기록은 되돌림으로 닫힌다 — 다른 경로가 먼저 되돌린 경우")
        void 캐시_이미처리됨_이어도_기록은_되돌림으로_닫힘() {
            PaymentOrder order = buildPaymentOrder(3L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);

            // 선차감 후 다른 경로(예: 거절 전용 되돌리기)가 캐시만 먼저 되돌린 상태 — 기록은 아직 NOISE 로 남아있다
            fakeStockCachePort.set(3L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            fakeStockCachePort.compensateIfDecremented(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 3L)).contains(StockHoldRecordStatus.NOISE);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "QUARANTINED", REASON_CODE, null, null, EVENT_UUID);

            sutWithFake.handle(message);

            // 캐시는 이미 처리됨(ALREADY_DONE)이라 재고 값이 그대로지만, 기록은 되돌림으로 닫혀야 한다
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 3L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }
    }

    // ---- factory helpers ----

    private static PaymentConfirmResultUseCase buildUseCase(
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository,
            PaymentCommandUseCase paymentCommandUseCase,
            FakePaymentEventRepository paymentEventRepository,
            FakePaymentEventDedupeStore dedupeStore,
            QuarantineCompensationHandler quarantineCompensationHandler,
            KafkaTemplate<String, String> stockCommittedKafkaTemplate) {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T12:00:00Z"), ZoneOffset.UTC);
        return new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                stockHoldRecordRepository,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase,
                new PaymentConfirmGuardSkipMetrics(new SimpleMeterRegistry()),
                new PaymentConfirmTerminalResendMetrics(new SimpleMeterRegistry()),
                new StockHoldReverter(new SimpleMeterRegistry())
        );
    }

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
