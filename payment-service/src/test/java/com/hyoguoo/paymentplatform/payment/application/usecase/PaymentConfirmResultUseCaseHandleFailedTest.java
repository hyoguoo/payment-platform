package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChangeTrigger;
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
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase.handleFailed 단위 검증.
 *
 * <p>상품별로 선차감 흔적이 있을 때만 캐시를 되돌리고, 그 결과와 무관하게 기록을 되돌림으로
 * 닫는다. RuntimeException 은 그대로 전파한다.
 *
 * <p>진입 가드: FAILED 종결 상태는 canApplyConfirmResult=false 라 걸러진다.
 */
@DisplayName("PaymentConfirmResultUseCase handleFailed")
class PaymentConfirmResultUseCaseHandleFailedTest {

    private static final String ORDER_ID = "order-failed-001";
    private static final String EVENT_UUID = "evt-failed-001";
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

        sut = buildUseCase(stockCachePort, stockHoldRecordRepository, paymentCommandUseCase,
                paymentEventRepository, dedupeStore, quarantineCompensationHandler, stockCommittedKafkaTemplate);
    }

    @Test
    @DisplayName("FAILED — 이미 종결 상태(FAILED)이면 진입 가드에서 noop (되돌리기 흐름 미진입)")
    void FAILED_이미_종결_noop() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        // FAILED 는 canApplyConfirmResult=false → 진입 가드에서 걸린다
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockHoldRecordRepository).shouldHaveNoInteractions();
        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any(), any());
    }

    @Test
    @DisplayName("FAILED — 기록 닫기는 조회 시점의 사이클 식별 값을 그대로 전달해 호출한다")
    void FAILED_기록_닫기는_조회한_사이클_식별_값을_그대로_사용() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        String cycleToken = "cycle-abc-001";
        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, cycleToken)));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockHoldRecordRepository).should().closeAsReverted(eq(ORDER_ID), eq(order), eq(cycleToken));
    }

    @Test
    @DisplayName("FAILED — 선차감 기록이 없는 상품은 되돌리기·닫기 모두 부르지 않는다")
    void FAILED_기록_없는_상품은_되돌리기_닫기_미호출() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order)).willReturn(Optional.empty());
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort).should(never()).compensateIfDecremented(any(String.class), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should(never())
                .closeAsReverted(any(String.class), any(PaymentOrder.class), any(String.class));
        then(paymentCommandUseCase).should()
                .markPaymentAsFail(any(PaymentEvent.class), eq(REASON_CODE), eq(PaymentStatusChangeTrigger.CONFIRM));
    }

    @Test
    @DisplayName("FAILED — 되돌리기 호출이 RuntimeException 을 던지면 그대로 전파되고 markPaymentAsFail 미호출")
    void FAILED_되돌리기_RuntimeException_전파() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-x")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willThrow(new RuntimeException("Redis 연결 실패"));

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis 연결 실패");

        then(paymentCommandUseCase).should(never()).markPaymentAsFail(any(), any(), any());
    }

    @Nested
    @DisplayName("상품별 되돌리기 — 캐시 재고 값 복원까지 단정 (FakeStockCachePort/FakeStockHoldRecordRepository 사용)")
    class RevertPerProductTest {

        private FakePaymentEventRepository fakePaymentEventRepository;
        private FakePaymentEventDedupeStore fakeDedupeStore;
        private FakeStockCachePort fakeStockCachePort;
        private FakeStockHoldRecordRepository fakeStockHoldRecordRepository;
        private PaymentCommandUseCase fakePaymentCommandUseCase;
        private PaymentConfirmResultUseCase sutWithFake;

        @BeforeEach
        void setUp() {
            fakePaymentEventRepository = new FakePaymentEventRepository();
            fakeDedupeStore = new FakePaymentEventDedupeStore();
            fakeStockCachePort = new FakeStockCachePort();
            fakeStockHoldRecordRepository = new FakeStockHoldRecordRepository();
            fakePaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

            sutWithFake = buildUseCase(fakeStockCachePort, fakeStockHoldRecordRepository, fakePaymentCommandUseCase,
                    fakePaymentEventRepository, fakeDedupeStore, Mockito.mock(QuarantineCompensationHandler.class),
                    Mockito.mock(KafkaTemplate.class));
        }

        @Test
        @DisplayName("선차감 흔적이 있는 상품은 되돌아가고 기록이 되돌림으로 닫힌다 — 재고 값이 원래대로 복원된다")
        void 선차감_흔적_있으면_재고_복원되고_기록_닫힘() {
            PaymentOrder order = buildPaymentOrder(1L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);
            given(fakePaymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                    .willReturn(event);

            // 체크아웃 시점의 선차감을 흉내: 기록을 열고 캐시를 차감한다
            fakeStockCachePort.set(1L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(7);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

            sutWithFake.handle(message);

            assertThat(fakeStockCachePort.current(1L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 1L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("선차감 흔적이 없는 상품은 되돌리지 않고 기록만 닫는다 — 재고 값이 변하지 않는다")
        void 선차감_흔적_없으면_재고_불변_기록만_닫힘() {
            PaymentOrder order = buildPaymentOrder(2L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);
            given(fakePaymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                    .willReturn(event);

            // 기록만 열고 캐시 차감은 아직 일어나지 않은 상태(부분 반복 도중 죽은 상황을 흉내)
            fakeStockCachePort.set(2L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

            sutWithFake.handle(message);

            assertThat(fakeStockCachePort.current(2L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 2L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("캐시가 이미 처리됨을 돌려줘도 기록은 되돌림으로 닫힌다 — 다른 경로가 먼저 되돌린 경우")
        void 캐시_이미처리됨_이어도_기록은_되돌림으로_닫힘() {
            PaymentOrder order = buildPaymentOrder(3L, 3, BigDecimal.valueOf(300));
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
            fakePaymentEventRepository.save(event);
            given(fakePaymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class), any(String.class)))
                    .willReturn(event);

            // 선차감 후 다른 경로(예: 거절 전용 되돌리기)가 캐시만 먼저 되돌린 상태 — 기록은 아직 NOISE 로 남아있다
            fakeStockCachePort.set(3L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            fakeStockCachePort.compensateIfDecremented(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 3L)).contains(StockHoldRecordStatus.NOISE);

            ConfirmedEventMessage message = new ConfirmedEventMessage(
                    ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

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
                .paymentKey("pk-failed")
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
