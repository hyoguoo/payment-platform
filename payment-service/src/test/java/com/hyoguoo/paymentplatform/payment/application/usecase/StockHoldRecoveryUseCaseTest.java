package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordCandidate;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockHoldRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * StockHoldRecoveryUseCase 단위 검증.
 *
 * <p>대상은 결제가 종결이고 잡음(NOISE)으로 남은 기록뿐이다. 진행 중 결제의 기록은 건드리지
 * 않는다. 되돌리기 자체(흔적 유무, 이미 처리됨)의 세부 시맨틱은 {@link RecoverOutcomesTest}가
 * 실제 값으로 검증한다.
 */
@DisplayName("StockHoldRecoveryUseCase")
class StockHoldRecoveryUseCaseTest {

    private static final String ORDER_ID = "order-recovery-001";
    private static final Long PRODUCT_ID = 501L;
    private static final Integer QUANTITY = 3;
    private static final String CYCLE_TOKEN = "cycle-recovery-001";

    private FakePaymentEventRepository paymentEventRepository;
    private StockHoldRecordRepository stockHoldRecordRepository;
    private StockCachePort stockCachePort;
    private StockHoldRecoveryUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        stockHoldRecordRepository = Mockito.mock(StockHoldRecordRepository.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        sut = new StockHoldRecoveryUseCase(
                stockHoldRecordRepository, paymentEventRepository, stockCachePort,
                new StockHoldReverter(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("배치 크기를 그대로 findNoiseCandidates 에 전달한다")
    void 배치_크기를_그대로_전달한다() {
        given(stockHoldRecordRepository.findNoiseCandidates(anyInt())).willReturn(List.of());

        sut.recover(77);

        then(stockHoldRecordRepository).should().findNoiseCandidates(77);
    }

    @ParameterizedTest(name = "종결 상태({0})인 결제의 잡음 기록은 되돌아가고 닫힌다")
    @EnumSource(value = PaymentEventStatus.class,
            names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED"})
    @DisplayName("결제가 종결이면 잡음 기록을 되돌리고 recover 는 1을 반환한다")
    void 종결_결제의_잡음_기록은_되돌아간다(PaymentEventStatus terminalStatus) {
        givenCandidate();
        paymentEventRepository.save(buildPaymentEvent(terminalStatus));
        given(stockCachePort.compensateIfDecremented(eq(ORDER_ID), any(PaymentOrder.class)))
                .willReturn(StockRecoveryCompensationResult.OK);

        int recovered = sut.recover(10);

        assertThat(recovered).isEqualTo(1);
        then(stockCachePort).should().compensateIfDecremented(eq(ORDER_ID), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should().closeAsReverted(eq(ORDER_ID), any(PaymentOrder.class), eq(CYCLE_TOKEN));
    }

    @ParameterizedTest(name = "진행 중 상태({0})인 결제의 잡음 기록은 건드리지 않는다")
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "QUARANTINED"})
    @DisplayName("결제가 종결이 아니면 잡음 기록을 건드리지 않는다 — 되돌리면 게이트가 헐거워진다")
    void 진행_중_결제의_잡음_기록은_대상이_아니다(PaymentEventStatus nonTerminalStatus) {
        givenCandidate();
        paymentEventRepository.save(buildPaymentEvent(nonTerminalStatus));

        int recovered = sut.recover(10);

        assertThat(recovered).isEqualTo(0);
        then(stockCachePort).should(never()).compensateIfDecremented(any(String.class), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should(never())
                .closeAsReverted(any(String.class), any(PaymentOrder.class), any(String.class));
    }

    @Test
    @DisplayName("후보의 결제를 원본 DB 에서 찾지 못하면(고아 기록) 건드리지 않는다")
    void 결제를_찾지_못하면_건드리지_않는다() {
        givenCandidate();
        // paymentEventRepository 에 아무 것도 저장하지 않음 — findByOrderId 가 empty 를 돌려준다

        int recovered = sut.recover(10);

        assertThat(recovered).isEqualTo(0);
        then(stockCachePort).should(never()).compensateIfDecremented(any(String.class), any(PaymentOrder.class));
        then(stockHoldRecordRepository).should(never())
                .closeAsReverted(any(String.class), any(PaymentOrder.class), any(String.class));
    }

    private void givenCandidate() {
        given(stockHoldRecordRepository.findNoiseCandidates(anyInt()))
                .willReturn(List.of(new StockHoldRecordCandidate(ORDER_ID, PRODUCT_ID, QUANTITY, CYCLE_TOKEN)));
    }

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(status)
                .paymentOrderList(List.of())
                .allArgsBuild();
    }

    @Nested
    @DisplayName("되돌리기 결과 — FakeStockCachePort/FakeStockHoldRecordRepository 로 실제 값 확인")
    class RecoverOutcomesTest {

        private FakePaymentEventRepository fakePaymentEventRepository;
        private FakeStockCachePort fakeStockCachePort;
        private FakeStockHoldRecordRepository fakeStockHoldRecordRepository;
        private StockHoldRecoveryUseCase sutWithFake;
        private PaymentOrder order;

        @BeforeEach
        void setUp() {
            fakePaymentEventRepository = new FakePaymentEventRepository();
            fakeStockCachePort = new FakeStockCachePort();
            fakeStockHoldRecordRepository = new FakeStockHoldRecordRepository();
            sutWithFake = new StockHoldRecoveryUseCase(
                    fakeStockHoldRecordRepository, fakePaymentEventRepository, fakeStockCachePort,
                    new StockHoldReverter(new SimpleMeterRegistry()));
            order = PaymentOrder.allArgsBuilder()
                    .orderId(ORDER_ID)
                    .productId(PRODUCT_ID)
                    .quantity(QUANTITY)
                    .allArgsBuild();
            fakePaymentEventRepository.save(buildPaymentEvent(PaymentEventStatus.DONE));
        }

        @Test
        @DisplayName("흔적이 있으면 재고가 원래 값으로 복원되고 기록이 되돌림으로 닫힌다")
        void 흔적_있으면_재고_복원되고_기록_닫힘() {
            // 체크아웃 시점의 선차감을 흉내: 기록을 열고 캐시를 차감한다
            fakeStockCachePort.set(PRODUCT_ID, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(PRODUCT_ID)).isEqualTo(7);

            int recovered = sutWithFake.recover(10);

            assertThat(recovered).isEqualTo(1);
            assertThat(fakeStockCachePort.current(PRODUCT_ID)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, PRODUCT_ID))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("흔적이 없으면 되돌리지 않고 기록만 닫는다 — 재고 값이 변하지 않는다")
        void 흔적_없으면_되돌리지_않고_기록만_닫힘() {
            // 기록만 열리고 캐시 차감은 일어나지 않은 상태(차감 직전에 죽은 상황을 흉내)
            fakeStockCachePort.set(PRODUCT_ID, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);

            int recovered = sutWithFake.recover(10);

            assertThat(recovered).isEqualTo(1);
            assertThat(fakeStockCachePort.current(PRODUCT_ID)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, PRODUCT_ID))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("캐시가 이미 처리됨을 돌려줘도 기록을 닫는다 — 안 닫으면 다음 주기가 같은 기록을 또 못 닫는다")
        void 캐시_이미처리됨_이어도_기록을_닫는다() {
            // 다른 경로(예: 확정 실패 되돌리기)가 캐시만 먼저 되돌린 상태 — 기록은 아직 NOISE 로 남아있다
            fakeStockCachePort.set(PRODUCT_ID, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            fakeStockCachePort.compensateIfDecremented(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(PRODUCT_ID)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, PRODUCT_ID))
                    .contains(StockHoldRecordStatus.NOISE);

            int recovered = sutWithFake.recover(10);

            assertThat(recovered).isEqualTo(1);
            // 캐시는 이미 처리됨(ALREADY_DONE)이라 재고 값이 그대로지만, 기록은 되돌림으로 닫혀야 한다
            assertThat(fakeStockCachePort.current(PRODUCT_ID)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, PRODUCT_ID))
                    .contains(StockHoldRecordStatus.REVERTED);
        }
    }
}
